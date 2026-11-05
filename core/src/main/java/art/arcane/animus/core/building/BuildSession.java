package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One build and its NPC team, run on the owner thread one tick at a time. Each NPC claims the next step of the current
 * segment, walks within reach, and places it. A step whose block already matches is skipped, so a resumed build only
 * needs its saved step number. Work waits, never loads terrain, when a chunk is not loaded, and waits for materials
 * when a sourced build runs short. Every change is journaled for undo. A multi-block object, such as a door or a bed,
 * is placed, paid for, cleared and undone as one: all its cells change in the same tick, never one half alone.
 */
public final class BuildSession {
    public static final int SCHEMA_VERSION = 1;
    private static final int TICKS_PER_SECOND = 20, WAIT_RETRY_TICKS = 40, WALK_RETRY_TICKS = 20, MOVE_LEG = 30, MOVE_RISE = 7, REACH_SCAN = 64;
    private static final double STALL = .5;
    private static final String EXCAVATION_TOOL = "minecraft:iron_pickaxe";

    public enum State {
        RUNNING, PAUSED, WAITING, FINISHED, CANCELLED, UNDOING, UNDONE, FAILED;
        /** Still building or able to build again. */
        public boolean open() { return this == RUNNING || this == PAUSED || this == WAITING; }
        public String word() { return name().toLowerCase(Locale.ROOT); }
    }

    /** What the session reports back: trigger events, messages and save requests. */
    public interface Listener {
        void paused(BuildSession session, String reason);
        void finished(BuildSession session);
        void changed(BuildSession session);
        void tell(Optional<UUID> player, String message);
    }

    /** Work allowance for one owner tick, shared by every build. */
    public static final class Budget {
        private int placements, checks, undo;
        public Budget(int placements, int checks, int undo) { this.placements = placements; this.checks = checks; this.undo = undo; }
        boolean place() { return placements-- > 0; }
        boolean check() { return checks-- > 0; }
        boolean revert() { return undo-- > 0; }
    }

    /** Where to find the block and NPC services a tick needs. */
    public record Context(BuildWorld world, BuildCrew crew, BuildProtection.Gate protection, Budget budget) {}

    private static final class Claim {
        final int step;
        long since;
        Optional<BlockPos> stand = Optional.empty();
        long nextWalk;
        int walks;
        double lastDistance;
        /** Walking got no closer; grounded: no scaffold fits, so the move timeout decides. */
        boolean stalled, grounded;
        Claim(int step, long since) { this.step = step; this.since = since; }
    }
    private enum Check { WORK, DONE, WAIT }

    private final String id;
    private final String world;
    private final BlockPos origin;
    private final String schematic;
    private final String schematicHash;
    private final BuildOptions options;
    private final List<String> team = new ArrayList<>();
    private final MaterialLedger ledger;
    private final UndoJournal journal;
    private final Optional<UUID> startedBy;
    private final String startedAt;
    private final Listener listener;
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final Map<String, Long> nextPlace = new HashMap<>();
    private final Map<String, Optional<String>> held = new HashMap<>();
    private final ScaffoldCrew scaffolds;
    private BuildPlan plan;
    private MaterialRules.Need[] needs;
    private final Map<String, MaterialRules.Need> partNeeds = new HashMap<>();
    private BitSet done = new BitSet(), unitSteps = new BitSet();
    private int cursor, totalSteps;
    private State state;
    private String reason = "";
    private long placed, skipped, denied, unobtainable, refused, reverted, undoSkipped;
    private int undoRemaining = -1;
    private long tick, retryAt, runTicks, processed;
    private BuildWorld worldAccess;

    public BuildSession(String id, String leader, String world, BlockPos origin, String schematic, String schematicHash, BuildOptions options,
                        Optional<UUID> startedBy, String startedAt, int undoCapacity, Listener listener) {
        this(id, List.of(leader), world, origin, schematic, schematicHash, options, new MaterialLedger(), new UndoJournal(undoCapacity),
                startedBy, startedAt, State.RUNNING, listener);
    }

    private BuildSession(String id, List<String> team, String world, BlockPos origin, String schematic, String schematicHash, BuildOptions options,
                         MaterialLedger ledger, UndoJournal journal, Optional<UUID> startedBy, String startedAt, State state, Listener listener) {
        this.id = Objects.requireNonNull(id); this.world = Objects.requireNonNull(world); this.origin = Objects.requireNonNull(origin);
        this.schematic = Objects.requireNonNull(schematic); this.schematicHash = Objects.requireNonNull(schematicHash);
        this.options = Objects.requireNonNull(options); this.ledger = Objects.requireNonNull(ledger); this.journal = Objects.requireNonNull(journal);
        this.startedBy = Objects.requireNonNull(startedBy); this.startedAt = Objects.requireNonNull(startedAt);
        this.state = Objects.requireNonNull(state); this.listener = Objects.requireNonNull(listener);
        if (team.isEmpty()) throw new IllegalArgumentException("A build needs a leader NPC");
        this.team.addAll(team);
        scaffolds = new ScaffoldCrew(world, options, ledger, journal, startedBy, nextPlace, held, () -> listener.changed(this));
    }

    public String id() { return id; }
    public String leader() { return team.getFirst(); }
    public List<String> team() { return List.copyOf(team); }
    public String world() { return world; }
    public BlockPos origin() { return origin; }
    public String schematic() { return schematic; }
    public String schematicHash() { return schematicHash; }
    public BuildOptions options() { return options; }
    public State state() { return state; }
    public String reason() { return reason; }
    public int cursor() { return cursor; }
    public int totalSteps() { return plan != null ? plan.size() : totalSteps; }
    public long placed() { return placed; }
    public long skipped() { return skipped; }
    public long denied() { return denied; }
    public long unobtainable() { return unobtainable; }
    public long reverted() { return reverted; }
    public long undoSkipped() { return undoSkipped; }
    public int undoRemaining() { return undoRemaining; }
    public MaterialLedger ledger() { return ledger; }
    public UndoJournal journal() { return journal; }
    public Optional<UUID> startedBy() { return startedBy; }
    public boolean planned() { return plan != null; }
    public Optional<BuildPlan> plan() { return Optional.ofNullable(plan); }

    /** Gives a new or restored session its plan. The saved step number must fit the plan. */
    public void attach(BuildPlan next) {
        Objects.requireNonNull(next);
        if (totalSteps != 0 && totalSteps != next.size())
            throw new IllegalArgumentException("The schematic now gives " + next.size() + " steps, but the build was saved with " + totalSteps);
        if (cursor > next.size()) throw new IllegalArgumentException("Saved step " + cursor + " is past the end of the plan");
        plan = next;
        totalSteps = next.size();
        needs = new MaterialRules.Need[next.schematic().palette().size()];
        done = new BitSet(next.size());
        done.set(0, cursor);
        unitSteps = new BitSet();
        for (int step = next.excavations(); step < next.size(); step++) if (next.unit(step).isPresent()) unitSteps.set(step);
    }
    /** The plan could not be rebuilt; building stops, undo still works. */
    public void fail(String why) {
        state = State.FAILED;
        reason = why;
        releaseClaims(null);
        scaffolds.abandonAll();
        listener.changed(this);
    }
    /** Scaffolding blocks the build has standing now. */
    public int scaffolding() { return scaffolds.standing(); }
    /** Takes every scaffolding block down at once, without the NPCs, as at shutdown. Owner thread. */
    public void dismantle(Context context) {
        scaffolds.abandonAll();
        scaffolds.dismantle(context, npc(context.crew()), true);
    }

    // Commands

    public String pause() {
        if (!state.open()) throw new IllegalStateException("Build " + id + " is " + state.word());
        if (state == State.PAUSED) return "Build " + id + " is already paused";
        state = State.PAUSED;
        reason = "paused by command";
        listener.paused(this, reason);
        listener.changed(this);
        return "Paused build " + id;
    }
    /** Releases claims; the stopped NPCs keep their place. Owner thread. */
    public void stopCrew(BuildCrew crew) { releaseClaims(crew); }
    public String resume() {
        if (state != State.PAUSED && state != State.WAITING) throw new IllegalStateException("Build " + id + " is " + state.word() + ", not paused");
        if (plan == null) throw new IllegalStateException("Build " + id + " has no plan: " + reason);
        state = State.RUNNING;
        reason = "";
        retryAt = 0;
        listener.changed(this);
        return "Resumed build " + id;
    }
    public String cancel() {
        if (!state.open() && state != State.FAILED) throw new IllegalStateException("Build " + id + " is " + state.word());
        state = State.CANCELLED;
        reason = "cancelled by command";
        listener.changed(this);
        return "Cancelled build " + id + "; " + journal.size() + " changes can be undone";
    }
    public String undo() {
        if (state == State.UNDOING || state == State.UNDONE) throw new IllegalStateException("Build " + id + " is " + state.word());
        state = State.UNDOING;
        reason = "";
        undoRemaining = journal.size();
        // Scaffolding still standing comes down with the rest of the journal.
        scaffolds.forget();
        listener.changed(this);
        return "Undoing " + journal.size() + " changes of build " + id + (journal.truncated() ? "; the journal was full, so later changes stay" : "");
    }
    public void addMember(String npc, int maximumTeam) {
        if (team.contains(npc)) throw new IllegalArgumentException(npc + " is already on build " + id);
        if (team.size() >= maximumTeam) throw new IllegalStateException("A build team holds at most " + maximumTeam + " NPCs");
        team.add(npc);
        listener.changed(this);
    }
    public void removeMember(String npc, BuildCrew crew) {
        if (npc.equals(leader())) throw new IllegalArgumentException("The leader cannot leave; cancel the build instead");
        if (!team.remove(npc)) throw new IllegalArgumentException(npc + " is not on build " + id);
        if (claims.remove(npc) != null) crew.stop(npc);
        scaffolds.abandon(npc);
        listener.changed(this);
    }

    /** Credits donated items. Only items the rest of the build still needs are taken; returns how many. */
    public long donate(String item, long offered) {
        if (options.materials() != BuildOptions.Materials.DONATE) throw new IllegalStateException("Build " + id + " does not take donations");
        long wanted = Math.max(0, remaining().getOrDefault(item, 0L) - ledger.available(item));
        long taken = Math.min(wanted, offered);
        if (taken > 0) { ledger.add(item, taken); listener.changed(this); }
        return taken;
    }

    // Ticking

    public void tick(Context context) {
        tick++;
        worldAccess = context.world();
        switch (state) {
            case RUNNING, WAITING -> build(context);
            case UNDOING -> revert(context);
            case PAUSED, CANCELLED -> lower(context);
            case FINISHED, FAILED, UNDONE -> { if (!scaffolds.empty()) { scaffolds.abandonAll(); scaffolds.dismantle(context, npc(context.crew()), false); } }
        }
    }

    private void build(Context context) {
        if (plan == null) return;
        if (state == State.WAITING && tick < retryAt) return;
        State before = state;
        String waitingFor = reason;
        state = State.RUNNING;
        reason = "";
        advance();
        if (cursor >= plan.size()) { if (lower(context)) finish(context.crew()); return; }
        if (!context.world().worldLoaded(world)) { waitFor("the build's world is not loaded", before, waitingFor); return; }
        List<BuildCrew.Member> active = active(context.crew());
        scaffolds.dismantle(context, npc(context.crew()), false);
        if (active.isEmpty()) { waitFor("no builder NPC is active in the build's world", before, waitingFor); return; }
        runTicks++;
        String wait = null;
        for (BuildCrew.Member member : active) {
            wait = work(member, context);
            if (wait != null) break;
        }
        advance();
        if (wait != null) { waitFor(wait, before, waitingFor); return; }
        if (cursor >= plan.size()) { if (lower(context)) finish(context.crew()); return; }
        if (before == State.WAITING) listener.changed(this);
    }

    /** Team members active in the build's world; the scaffolding of any other member is taken down without it. */
    private List<BuildCrew.Member> active(BuildCrew crew) {
        List<BuildCrew.Member> active = new ArrayList<>();
        for (String npc : team) {
            Optional<BuildCrew.Member> member = crew.member(npc).filter((BuildCrew.Member found) -> found.position().world().equals(world));
            if (member.isPresent()) active.add(member.get()); else scaffolds.abandon(npc);
        }
        return active;
    }

    /** The NPCs climb down and take their scaffolding with them. True once none is left. */
    private boolean lower(Context context) {
        if (scaffolds.empty()) return true;
        scaffolds.lowerAll();
        if (context.world().worldLoaded(world)) for (BuildCrew.Member member : active(context.crew())) scaffolds.turn(member, context, tick);
        scaffolds.dismantle(context, npc(context.crew()), false);
        return scaffolds.empty();
    }

    /** One NPC's turn. Returns why the build must wait, or null. */
    private String work(BuildCrew.Member member, Context context) {
        Claim claim = claims.get(member.id());
        if (scaffolds.busy(member.id())) {
            if (claim != null) claim.since = tick;
            return scaffolds.turn(member, context, tick);
        }
        if (claim == null) {
            int end = plan.segmentEnd(cursor);
            Optional<Scaffold> scaffold = scaffolds.of(member.id());
            if (scaffold.isPresent()) {
                // Work from the scaffold while it reaches anything left in this part of the build.
                int looked = 0;
                for (int step = done.nextClearBit(cursor); step < end && claim == null && looked++ < REACH_SCAN; step = done.nextClearBit(step + 1)) {
                    if (claimed(step) || !ScaffoldPlanner.reaches(scaffold.get().stand(), plan.stepPosition(step), options.reach())) continue;
                    if (!context.budget().check()) return null;
                    Check checked = check(step, member.uuid(), context);
                    if (checked == Check.WAIT) return reason;
                    if (checked == Check.WORK) claim = claim(member, step, context);
                }
            }
            for (int step = done.nextClearBit(cursor); step < end && claim == null; step = done.nextClearBit(step + 1)) {
                if (claimed(step)) continue;
                if (!context.budget().check()) return null;
                Check checked = check(step, member.uuid(), context);
                if (checked == Check.WAIT) return reason;
                if (checked == Check.DONE) continue;
                claim = claim(member, step, context);
            }
            advance();
            if (claim == null) return null;
        }
        BlockPos at = plan.stepPosition(claim.step);
        if (scaffolds.aborted(member.id())) claim.grounded = true;
        Optional<Scaffold> scaffold = scaffolds.of(member.id());
        if (scaffold.isPresent()) {
            if (!ScaffoldPlanner.reaches(scaffold.get().stand(), at, options.reach())) {
                scaffolds.follow(member, at, plan, context);
                claim.since = tick;
                return scaffolds.turn(member, context, tick);
            }
            claim.stand = Optional.of(scaffold.get().stand());
        } else if (climb(member, claim, at) && context.budget().check()) {
            if (scaffolds.start(member, at, upcoming(), plan, context)) {
                claim.since = tick;
                return scaffolds.turn(member, context, tick);
            }
            claim.grounded = true;
        }
        Vec3 centre = StandPoints.centre(at);
        double distance = member.position().point().distance(centre);
        boolean timedOut = tick - claim.since > (long) options.moveTimeoutSeconds() * TICKS_PER_SECOND;
        boolean inside = !plan.target(claim.step).air() && occupies(member, claim.step);
        if ((distance > options.reach() || inside && claim.stand.isPresent()) && !timedOut) {
            if (!context.crew().routing(member.id()) && tick >= claim.nextWalk) {
                if (claim.walks > 0 && distance > claim.lastDistance - STALL) claim.stalled = true;
                claim.walks++;
                claim.lastDistance = distance;
                claim.nextWalk = tick + WALK_RETRY_TICKS;
                walk(member, claim, centre, context.crew());
            }
            return null;
        }
        if (tick < nextPlace.getOrDefault(member.id(), 0L)) return null;
        if (timedOut && (inside || distance > options.reach()) && claim.stand.isPresent()) {
            // Last resort after the move timeout: move the NPC next to the block.
            BlockPos stand = claim.stand.get();
            context.crew().relocate(member.id(), new WorldPosition(world, new Vec3(stand.x() + .5, stand.y(), stand.z() + .5)));
        }
        if (!context.budget().place()) return null;
        hold(member.id(), context);
        return place(member, claim, context);
    }

    private Claim claim(BuildCrew.Member member, int step, Context context) {
        Claim claim = new Claim(step, tick);
        claims.put(member.id(), claim);
        claim.stand = StandPoints.near(context.world().collision(world), plan.stepPosition(step), member.position().point(), options.reach(), member.height());
        hold(member.id(), context);
        return claim;
    }

    /**
     * Whether to put up scaffolding for the claimed block: it is too high to reach from where the NPC stands and no place
     * near it holds the NPC, or the NPC cannot walk to the raised place that does.
     */
    private boolean climb(BuildCrew.Member member, Claim claim, BlockPos at) {
        if (!options.scaffolding() || claim.grounded) return false;
        double rise = at.y() + .5 - member.position().point().y();
        return rise > options.reach() - .5 && claim.stand.isEmpty() || claim.stalled && rise > 1.5;
    }

    /** Where the next blocks of the build go, so a scaffold can be placed to reach many of them. */
    private List<BlockPos> upcoming() {
        List<BlockPos> next = new ArrayList<>(REACH_SCAN);
        for (int step = done.nextClearBit(cursor); step < plan.size() && next.size() < REACH_SCAN; step = done.nextClearBit(step + 1))
            if (!plan.excavation(step) && !claimed(step)) next.add(plan.stepPosition(step));
        return next;
    }

    /** The NPC protection is asked about for work no NPC does in person. */
    private UUID npc(BuildCrew crew) { return crew.member(leader()).map(BuildCrew.Member::uuid).orElse(new UUID(0, 0)); }

    private void walk(BuildCrew.Member member, Claim claim, Vec3 centre, BuildCrew crew) {
        Vec3 goal = claim.stand.map((BlockPos stand) -> new Vec3(stand.x() + .5, stand.y(), stand.z() + .5)).orElse(centre);
        Vec3 from = member.position().point();
        Vec3 delta = goal.subtract(from);
        Vec3 leg = new Vec3(from.x() + clamp(delta.x(), MOVE_LEG), Math.floor(from.y() + clamp(delta.y(), MOVE_RISE)), from.z() + clamp(delta.z(), MOVE_LEG));
        try { crew.walk(member.id(), new WorldPosition(world, leg), options.speed()); }
        catch (IllegalArgumentException | IllegalStateException refused) { /* the move timeout moves the NPC instead */ }
    }

    private String place(BuildCrew.Member member, Claim claim, Context context) {
        claims.remove(member.id());
        Check checked = check(claim.step, member.uuid(), context);
        if (checked == Check.WAIT) { claims.put(member.id(), claim); return reason; }
        if (checked == Check.DONE) return null;
        BlockPos at = plan.stepPosition(claim.step);
        List<UndoJournal.Change> changes = changes(claim.step, context.world());
        int written = 0;
        for (UndoJournal.Change change : changes) {
            if (!context.world().place(world, change.at(), change.after())) break;
            written++;
        }
        if (written < changes.size()) {
            // The platform refused a cell: put back what this object already changed, so no half stays behind.
            for (int index = written - 1; index >= 0; index--) context.world().place(world, changes.get(index).at(), changes.get(index).before());
            refused++;
            complete(claim.step);
            return null;
        }
        boolean clearing = plan.target(claim.step).air();
        for (UndoJournal.Change change : changes) {
            context.crew().terrainChanged(world, change.at());
            if (plan.excavation(claim.step) || air(change.after())) continue;
            MaterialRules.Need need = partNeed(change.after(), context.world());
            if (need.kind() == MaterialRules.Kind.ITEM) consume(need, context.world());
        }
        if (changes.size() == 1) journal.record(changes.getFirst().at(), changes.getFirst().before(), changes.getFirst().after());
        else journal.group(changes);
        complete(claim.step);
        nextPlace.put(member.id(), tick + options.placeIntervalTicks());
        context.crew().face(member.id(), new WorldPosition(world, StandPoints.centre(at)));
        context.crew().swing(member.id());
        boolean sound = !options.silent();
        for (UndoJournal.Change change : changes) {
            if (air(change.after()) && !clearing) continue;
            placed++;
            context.world().placed(world, change.at(), change.after(), sound);
            sound = false;
        }
        listener.changed(this);
        return null;
    }

    /**
     * Every cell the step changes now, in placement order: the cells of its object that do not match yet, then the other
     * halves of any door, bed or similar object it replaces, which would otherwise be left alone in the world.
     */
    private List<UndoJournal.Change> changes(int step, BuildWorld access) {
        List<UndoJournal.Change> changes = new ArrayList<>(2);
        List<BlockPos> cells = new ArrayList<>(2);
        Optional<BuildPlan.Unit> unit = plan.unit(step);
        if (unit.isPresent()) {
            for (BuildPlan.Part part : unit.get().parts()) {
                cells.add(part.at());
                String current = access.block(world, part.at()).orElse("minecraft:air");
                String wanted = part.state().toString();
                if (!current.equals(wanted)) changes.add(new UndoJournal.Change(part.at(), current, wanted));
            }
        } else {
            BlockPos at = plan.stepPosition(step);
            cells.add(at);
            changes.add(new UndoJournal.Change(at, access.block(world, at).orElse("minecraft:air"), plan.target(step).toString()));
        }
        int replaced = changes.size();
        for (int index = 0; index < replaced; index++) {
            UndoJournal.Change change = changes.get(index);
            Optional<BlockUnits.Partner> partner = PaletteResolver.parse(change.before()).flatMap(BlockUnits::partner);
            if (partner.isEmpty()) continue;
            BlockUnits.Partner other = partner.get();
            BlockPos at = new BlockPos(change.at().x() + other.dx(), change.at().y() + other.dy(), change.at().z() + other.dz());
            if (cells.contains(at)) continue;
            Optional<String> current = access.block(world, at);
            Optional<BlockState> held = current.flatMap(PaletteResolver::parse);
            if (held.isEmpty() || !held.get().name().equals(other.state().name()) || BlockUnits.partner(held.get())
                    .filter((BlockUnits.Partner back) -> back.dx() == -other.dx() && back.dy() == -other.dy() && back.dz() == -other.dz()).isEmpty()) continue;
            cells.add(at);
            changes.add(new UndoJournal.Change(at, current.get(), "minecraft:air"));
        }
        return changes;
    }

    /** Whether the step still needs work now, is already settled, or must wait. Settled steps are marked done here. */
    private Check check(int step, UUID npc, Context context) {
        BlockPos at = plan.stepPosition(step);
        if (!context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) {
            reason = "waiting for an unloaded chunk at " + at.x() + " " + at.z();
            return Check.WAIT;
        }
        Optional<BuildPlan.Unit> unit = plan.unit(step);
        if (unit.isPresent()) for (BuildPlan.Part part : unit.get().parts())
            if (!context.world().chunkLoaded(world, Math.floorDiv(part.at().x(), 16), Math.floorDiv(part.at().z(), 16))) {
                reason = "waiting for an unloaded chunk at " + part.at().x() + " " + part.at().z();
                return Check.WAIT;
            }
        Optional<String> current = context.world().block(world, at);
        if (current.isEmpty()) { refused++; complete(step); return Check.DONE; }
        // Air the build wants where its own scaffolding stands comes when the scaffolding does.
        if (plan.target(step).air() && scaffolds.holds(at)) { skipped++; complete(step); return Check.DONE; }
        if (plan.excavation(step)) {
            if (air(current.get()) || current.get().equals(plan.design(step).toString())) { skipped++; complete(step); return Check.DONE; }
        } else if (unit.isEmpty() && current.get().equals(plan.target(step).toString())) { skipped++; complete(step); return Check.DONE; }
        List<UndoJournal.Change> changes = changes(step, context.world());
        if (changes.isEmpty()) { skipped++; complete(step); return Check.DONE; }
        for (UndoJournal.Change change : changes) {
            BuildProtection.Action action = air(change.after()) ? BuildProtection.Action.BREAK : BuildProtection.Action.PLACE;
            if (!context.protection().allows(new BuildProtection.Request(npc, startedBy, world, change.at(), action))) { denied++; complete(step); return Check.DONE; }
        }
        if (plan.excavation(step) || options.materials() == BuildOptions.Materials.CREATIVE) return Check.WORK;
        Map<String, Long> wanted = new TreeMap<>();
        for (UndoJournal.Change change : changes) {
            if (air(change.after())) continue;
            MaterialRules.Need need = partNeed(change.after(), context.world());
            if (need.kind() == MaterialRules.Kind.UNOBTAINABLE) { unobtainable++; complete(step); return Check.DONE; }
            if (need.kind() == MaterialRules.Kind.ITEM) wanted.merge(need.item(), (long) need.count(), Long::sum);
        }
        for (Map.Entry<String, Long> item : wanted.entrySet()) {
            long have = available(item.getKey(), context.world());
            if (have < 0) { reason = "the material container at " + container() + " is not loaded or not a container"; return Check.WAIT; }
            if (have < item.getValue()) {
                long rest = remaining().getOrDefault(item.getKey(), item.getValue());
                reason = "needs " + item.getValue() + " " + item.getKey() + " (have " + have + ", the rest of the build needs " + rest + ")";
                return Check.WAIT;
            }
        }
        return Check.WORK;
    }

    private void waitFor(String why, State before, String previous) {
        state = State.WAITING;
        reason = why;
        retryAt = tick + WAIT_RETRY_TICKS;
        if (before != State.WAITING || !why.equals(previous)) {
            listener.paused(this, why);
            listener.tell(startedBy, "Build " + id + " is waiting: " + why);
            listener.changed(this);
        }
    }

    private void finish(BuildCrew crew) {
        state = State.FINISHED;
        reason = "";
        releaseClaims(crew);
        for (String npc : team) if (options.holdBlock()) crew.hold(npc, Optional.empty());
        listener.finished(this);
        listener.tell(startedBy, "Build " + id + " finished: " + placed + " placed, " + skipped + " already in place"
                + (denied > 0 ? ", " + denied + " refused by protection" : "") + (unobtainable > 0 ? ", " + unobtainable + " with no item" : ""));
        listener.changed(this);
    }

    private void revert(Context context) {
        if (undoRemaining <= 0) { finishUndo(); return; }
        while (undoRemaining > 0) {
            int start = journal.groupStart(undoRemaining - 1), size = undoRemaining - start;
            for (int index = start; index < undoRemaining; index++) {
                BlockPos at = journal.get(index).position();
                if (!context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) {
                    reason = "waiting for an unloaded chunk at " + at.x() + " " + at.z();
                    return;
                }
            }
            if (!context.budget().revert()) return;
            for (int extra = 1; extra < size; extra++) context.budget().revert();
            reason = "";
            undoRemaining = start;
            revertGroup(start, size, context);
            listener.changed(this);
        }
        finishUndo();
    }

    /**
     * Puts back one group of changes as a whole: when any of its cells changed since, the whole group stays. Scaffolding
     * the build took down again is left out, and scaffolding still standing is removed; neither is counted.
     */
    private void revertGroup(int start, int size, Context context) {
        List<UndoJournal.Entry> entries = new ArrayList<>(size);
        for (int index = start; index < start + size; index++) entries.add(journal.get(index));
        boolean scaffold = entries.getFirst().scaffold();
        if (scaffold && !air(entries.getFirst().before())) return;
        for (UndoJournal.Entry entry : entries) {
            Optional<String> current = context.world().block(world, entry.position());
            if (current.isEmpty() || !current.get().equals(entry.after())) { if (!scaffold) undoSkipped += size; return; }
        }
        UUID npc = context.crew().member(leader()).map(BuildCrew.Member::uuid).orElse(new UUID(0, 0));
        for (UndoJournal.Entry entry : entries) {
            BuildProtection.Action action = air(entry.before()) ? BuildProtection.Action.BREAK : BuildProtection.Action.PLACE;
            if (!context.protection().allows(new BuildProtection.Request(npc, startedBy, world, entry.position(), action))) { if (!scaffold) undoSkipped += size; return; }
        }
        for (int index = entries.size() - 1; index >= 0; index--) {
            UndoJournal.Entry entry = entries.get(index);
            if (context.world().place(world, entry.position(), entry.before())) {
                if (!scaffold) reverted++;
                context.crew().terrainChanged(world, entry.position());
            } else if (!scaffold) undoSkipped++;
        }
    }

    private void finishUndo() {
        state = State.UNDONE;
        undoRemaining = 0;
        listener.tell(startedBy, "Build " + id + " undone: " + reverted + " blocks restored, " + undoSkipped + " left because they changed since");
        listener.changed(this);
    }

    // Materials

    /** What one cell of an object costs, by the platform's state text. */
    private MaterialRules.Need partNeed(String state, BuildWorld access) {
        MaterialRules.Need known = partNeeds.get(state);
        if (known == null) {
            known = PaletteResolver.parse(state).map((BlockState parsed) -> MaterialRules.need(parsed, access.item(state))).orElse(MaterialRules.Need.UNOBTAINABLE);
            if (partNeeds.size() < 4096) partNeeds.put(state, known);
        }
        return known;
    }
    private MaterialRules.Need need(int paletteIndex, BuildWorld access) {
        MaterialRules.Need known = needs[paletteIndex];
        if (known == null) {
            String state = plan.state(paletteIndex).toString();
            known = MaterialRules.need(plan.state(paletteIndex), access.item(state));
            needs[paletteIndex] = known;
        }
        return known;
    }
    private long available(String item, BuildWorld access) {
        return options.materials() == BuildOptions.Materials.CONTAINER ? access.containerCount(world, container(), item) : ledger.available(item);
    }
    private void consume(MaterialRules.Need need, BuildWorld access) {
        switch (options.materials()) {
            case CREATIVE -> { }
            case DONATE -> ledger.take(need.item(), need.count());
            case CONTAINER -> access.containerTake(world, container(), need.item(), need.count());
        }
    }
    private BlockPos container() { return options.container().orElseThrow(); }

    /**
     * Items still needed by the steps not yet done, by item: an upper bound, since blocks already in place are only
     * found when the NPC reaches them. Creative builds need nothing. A build that may use scaffolding also counts enough
     * scaffolding for one column to its top; scaffolding comes back to the build when it is taken down.
     */
    public Map<String, Long> remaining() {
        Map<String, Long> totals = new TreeMap<>();
        if (plan == null || options.materials() == BuildOptions.Materials.CREATIVE || worldAccess == null) return totals;
        int[] perPalette = new int[needs.length];
        int highest = Integer.MIN_VALUE;
        for (int step = Math.max(cursor, plan.excavations()); step < plan.size(); step++) {
            if (done.get(step)) continue;
            highest = Math.max(highest, plan.stepPosition(step).y());
            if (!unitSteps.get(step)) { perPalette[plan.paletteIndex(step)]++; continue; }
            for (BuildPlan.Part part : plan.unit(step).orElseThrow().parts()) {
                MaterialRules.Need need = partNeed(part.state().toString(), worldAccess);
                if (need.kind() == MaterialRules.Kind.ITEM) totals.merge(need.item(), (long) need.count(), Long::sum);
            }
        }
        for (int index = 0; index < perPalette.length; index++) {
            if (perPalette[index] == 0) continue;
            MaterialRules.Need need = need(index, worldAccess);
            if (need.kind() == MaterialRules.Kind.ITEM) totals.merge(need.item(), (long) need.count() * perPalette[index], Long::sum);
        }
        // A stand one block under the highest block, on a column from the ground at the origin, less what already stands.
        int column = highest - origin.y() - 1;
        if (options.scaffolding() && highest + .5 - origin.y() > options.reach() - .5 && column > scaffolds.standing())
            totals.merge(ScaffoldPlanner.BLOCK, (long) column - scaffolds.standing(), Long::sum);
        return totals;
    }
    /** Lets survey and donations price items before the first tick. */
    public void bind(BuildWorld access) { worldAccess = Objects.requireNonNull(access); }

    /** One line per item: needed for the rest of the build against what the build holds. */
    public List<String> survey(BuildWorld access) {
        bind(access);
        List<String> lines = new ArrayList<>();
        Map<String, Long> totals = remaining();
        if (options.materials() == BuildOptions.Materials.CREATIVE) { lines.add("Build " + id + " uses creative materials; nothing is needed"); return lines; }
        lines.add("Build " + id + " needs, for the steps left (blocks already in place are not counted off until reached):");
        for (Map.Entry<String, Long> entry : totals.entrySet()) {
            long have = options.materials() == BuildOptions.Materials.CONTAINER ? Math.max(0, access.containerCount(world, container(), entry.getKey())) : ledger.available(entry.getKey());
            lines.add(entry.getKey() + ": need " + entry.getValue() + ", have " + have + (have >= entry.getValue() ? "" : ", short " + (entry.getValue() - have)));
        }
        if (totals.isEmpty()) lines.add("nothing");
        return lines;
    }

    // Progress

    public String status() {
        int total = totalSteps();
        int percent = total == 0 ? 100 : (int) Math.min(100, (long) cursor * 100 / total);
        StringBuilder text = new StringBuilder("Build ").append(id).append(": ").append(state.word());
        if (state == State.UNDOING) text.append(' ').append(journal.size() - undoRemaining).append('/').append(journal.size());
        else text.append(' ').append(cursor).append('/').append(total).append(" (").append(percent).append("%)");
        text.append(" | schematic=").append(schematic).append(" | team=").append(String.join(",", team))
                .append(" | placed=").append(placed).append(" skipped=").append(skipped).append(" denied=").append(denied)
                .append(" no-item=").append(unobtainable).append(" refused=").append(refused)
                .append(" | materials=").append(options.materials().name().toLowerCase(Locale.ROOT))
                .append(" | undo=").append(journal.size()).append(journal.truncated() ? " (full)" : "");
        if (scaffolds.standing() > 0) text.append(" | scaffolding=").append(scaffolds.standing());
        if (state == State.RUNNING) eta().ifPresent((String eta) -> text.append(" | eta=").append(eta));
        if (state == State.UNDONE || state == State.UNDOING) text.append(" | restored=").append(reverted).append(" kept=").append(undoSkipped);
        if (!reason.isEmpty()) text.append(" | ").append(reason);
        return text.toString();
    }
    /** Remaining time at the rate seen while running since the build was loaded. */
    public Optional<String> eta() {
        if (runTicks < TICKS_PER_SECOND || processed == 0 || plan == null) return Optional.empty();
        double perTick = (double) processed / runTicks;
        long seconds = (long) Math.ceil((plan.size() - cursor) / perTick / TICKS_PER_SECOND);
        return Optional.of(seconds >= 3600 ? seconds / 3600 + "h" + seconds % 3600 / 60 + "m" : seconds >= 60 ? seconds / 60 + "m" + seconds % 60 + "s" : seconds + "s");
    }

    private void complete(int step) { done.set(step); processed++; }
    private void advance() { while (plan != null && cursor < plan.size() && done.get(cursor)) cursor++; }
    private boolean claimed(int step) {
        for (Claim claim : claims.values()) if (claim.step == step) return true;
        return false;
    }
    private void releaseClaims(BuildCrew crew) {
        if (crew != null) for (String npc : claims.keySet()) crew.stop(npc);
        claims.clear();
    }
    private void hold(String npc, Context context) {
        if (!options.holdBlock()) return;
        Claim claim = claims.get(npc);
        Optional<String> item = plan.excavation(claim.step) ? Optional.of(EXCAVATION_TOOL)
                : context.world().item(plan.target(claim.step).toString()).filter((String value) -> !value.equals("minecraft:air"));
        if (item.equals(held.get(npc))) return;
        held.put(npc, item);
        context.crew().hold(npc, item);
    }
    /** The NPC stands in a cell the step fills. */
    private boolean occupies(BuildCrew.Member member, int step) {
        Optional<BuildPlan.Unit> unit = plan.unit(step);
        if (unit.isEmpty()) return occupies(member, plan.stepPosition(step));
        for (BuildPlan.Part part : unit.get().parts()) if (occupies(member, part.at())) return true;
        return false;
    }
    private boolean occupies(BuildCrew.Member member, BlockPos at) {
        Vec3 feet = member.position().point();
        double half = member.width() / 2;
        return at.x() + 1 > feet.x() - half && at.x() < feet.x() + half && at.z() + 1 > feet.z() - half && at.z() < feet.z() + half
                && at.y() + 1 > feet.y() && at.y() < feet.y() + member.height();
    }
    /** Platform block text names air, cave air or void air. */
    static boolean air(String state) {
        int open = state.indexOf('[');
        String name = open < 0 ? state : state.substring(0, open);
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }
    private static double clamp(double value, double limit) { return Math.max(-limit, Math.min(limit, value)); }

    // Saving

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        JsonArray members = new JsonArray();
        team.forEach(members::add);
        json.add("team", members);
        json.addProperty("world", world);
        json.add("origin", BuildOptions.point(origin));
        json.addProperty("schematic", schematic);
        json.addProperty("schematicSha256", schematicHash);
        json.add("options", options.toJson());
        json.addProperty("state", state.word());
        json.addProperty("reason", reason);
        json.addProperty("cursor", cursor);
        json.addProperty("steps", totalSteps());
        JsonObject counters = new JsonObject();
        counters.addProperty("placed", placed); counters.addProperty("skipped", skipped); counters.addProperty("denied", denied);
        counters.addProperty("noItem", unobtainable); counters.addProperty("refused", refused);
        counters.addProperty("restored", reverted); counters.addProperty("kept", undoSkipped);
        json.add("counters", counters);
        json.add("materials", ledger.toJson());
        if (startedBy.isPresent()) json.addProperty("startedBy", startedBy.get().toString());
        json.addProperty("startedAt", startedAt);
        JsonObject undo = new JsonObject();
        undo.addProperty("entries", journal.size());
        undo.addProperty("capacity", journal.capacity());
        undo.addProperty("full", journal.truncated());
        undo.addProperty("remaining", undoRemaining);
        json.add("undo", undo);
        if (!scaffolds.empty()) json.add("scaffolding", scaffolds.toJson());
        return json;
    }

    /** Reads saved state. The journal is restored separately; {@link #attach} adds the plan for an open build. */
    public static BuildSession restore(JsonObject json, UndoJournal journal, Listener listener) {
        try {
            if (!json.has("schemaVersion") || json.get("schemaVersion").getAsInt() != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported schemaVersion");
            List<String> team = new ArrayList<>();
            for (JsonElement member : json.getAsJsonArray("team")) team.add(member.getAsString());
            State state = State.valueOf(json.get("state").getAsString().toUpperCase(Locale.ROOT));
            Optional<UUID> startedBy = json.has("startedBy") ? Optional.of(UUID.fromString(json.get("startedBy").getAsString())) : Optional.empty();
            BuildSession session = new BuildSession(json.get("id").getAsString(), team, json.get("world").getAsString(),
                    BuildOptions.point(json.getAsJsonObject("origin"), "$.origin"), json.get("schematic").getAsString(), json.get("schematicSha256").getAsString(),
                    BuildOptions.fromJson(json.getAsJsonObject("options"), "$.options"), MaterialLedger.fromJson(json.getAsJsonObject("materials"), "$.materials"),
                    journal, startedBy, json.get("startedAt").getAsString(), state, listener);
            session.reason = json.has("reason") ? json.get("reason").getAsString() : "";
            session.cursor = json.get("cursor").getAsInt();
            session.totalSteps = json.get("steps").getAsInt();
            if (session.cursor < 0 || session.totalSteps < 0 || session.cursor > session.totalSteps) throw new IllegalArgumentException("step out of range");
            JsonObject counters = json.getAsJsonObject("counters");
            session.placed = counters.get("placed").getAsLong(); session.skipped = counters.get("skipped").getAsLong();
            session.denied = counters.get("denied").getAsLong(); session.unobtainable = counters.get("noItem").getAsLong();
            session.refused = counters.get("refused").getAsLong(); session.reverted = counters.get("restored").getAsLong();
            session.undoSkipped = counters.get("kept").getAsLong();
            session.undoRemaining = json.getAsJsonObject("undo").get("remaining").getAsInt();
            if (session.undoRemaining > journal.size()) throw new IllegalArgumentException("undo position past the journal");
            if (state == State.WAITING) session.retryAt = 0;
            if (json.has("scaffolding")) session.scaffolds.restore(json.getAsJsonArray("scaffolding"));
            return session;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid saved build: " + invalid.getMessage(), invalid);
        }
    }
}
