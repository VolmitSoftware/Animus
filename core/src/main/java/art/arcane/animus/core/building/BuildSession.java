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
 * when a sourced build runs short. Every change is journaled for undo.
 */
public final class BuildSession {
    public static final int SCHEMA_VERSION = 1;
    private static final int TICKS_PER_SECOND = 20, WAIT_RETRY_TICKS = 40, WALK_RETRY_TICKS = 20, MOVE_LEG = 30, MOVE_RISE = 7;
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
        final long since;
        Optional<BlockPos> stand = Optional.empty();
        long nextWalk;
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
    private BuildPlan plan;
    private MaterialRules.Need[] needs;
    private BitSet done = new BitSet();
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
    }
    /** The plan could not be rebuilt; building stops, undo still works. */
    public void fail(String why) {
        state = State.FAILED;
        reason = why;
        releaseClaims(null);
        listener.changed(this);
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
            default -> { }
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
        if (cursor >= plan.size()) { finish(context.crew()); return; }
        if (!context.world().worldLoaded(world)) { waitFor("the build's world is not loaded", before, waitingFor); return; }
        List<BuildCrew.Member> active = new ArrayList<>();
        for (String npc : team) context.crew().member(npc).filter((BuildCrew.Member member) -> member.position().world().equals(world)).ifPresent(active::add);
        if (active.isEmpty()) { waitFor("no builder NPC is active in the build's world", before, waitingFor); return; }
        runTicks++;
        String wait = null;
        for (BuildCrew.Member member : active) {
            wait = work(member, context);
            if (wait != null) break;
        }
        advance();
        if (wait != null) { waitFor(wait, before, waitingFor); return; }
        if (cursor >= plan.size()) { finish(context.crew()); return; }
        if (before == State.WAITING) listener.changed(this);
    }

    /** One NPC's turn. Returns why the build must wait, or null. */
    private String work(BuildCrew.Member member, Context context) {
        Claim claim = claims.get(member.id());
        if (claim == null) {
            int end = plan.segmentEnd(cursor);
            for (int step = done.nextClearBit(cursor); step < end && claim == null; step = done.nextClearBit(step + 1)) {
                if (claimed(step)) continue;
                if (!context.budget().check()) return null;
                Check checked = check(step, member.uuid(), context);
                if (checked == Check.WAIT) return reason;
                if (checked == Check.DONE) continue;
                claim = new Claim(step, tick);
                claims.put(member.id(), claim);
                claim.stand = StandPoints.near(context.world().collision(world), plan.stepPosition(step), member.position().point(), options.reach(), member.height());
                hold(member.id(), context);
            }
            advance();
            if (claim == null) return null;
        }
        BlockPos at = plan.stepPosition(claim.step);
        Vec3 centre = StandPoints.centre(at);
        double distance = member.position().point().distance(centre);
        boolean timedOut = tick - claim.since > (long) options.moveTimeoutSeconds() * TICKS_PER_SECOND;
        boolean inside = !plan.target(claim.step).air() && occupies(member, at);
        if (distance > options.reach() && !timedOut) {
            if (!context.crew().routing(member.id()) && tick >= claim.nextWalk) {
                claim.nextWalk = tick + WALK_RETRY_TICKS;
                walk(member, claim, centre, context.crew());
            }
            return null;
        }
        if (tick < nextPlace.getOrDefault(member.id(), 0L)) return null;
        if ((inside || timedOut && distance > options.reach()) && claim.stand.isPresent()) {
            BlockPos stand = claim.stand.get();
            context.crew().relocate(member.id(), new WorldPosition(world, new Vec3(stand.x() + .5, stand.y(), stand.z() + .5)));
        }
        if (!context.budget().place()) return null;
        return place(member, claim, context);
    }

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
        String target = plan.target(claim.step).toString();
        String before = context.world().block(world, at).orElse("minecraft:air");
        MaterialRules.Need need = plan.excavation(claim.step) ? MaterialRules.Need.FREE : need(plan.paletteIndex(claim.step), context.world());
        if (!context.world().place(world, at, target)) {
            refused++;
            complete(claim.step);
            return null;
        }
        context.crew().terrainChanged(world, at);
        if (need.kind() == MaterialRules.Kind.ITEM) consume(need, context.world());
        journal.record(at, before, target);
        placed++;
        complete(claim.step);
        nextPlace.put(member.id(), tick + options.placeIntervalTicks());
        context.crew().face(member.id(), new WorldPosition(world, StandPoints.centre(at)));
        context.crew().swing(member.id());
        context.world().placed(world, at, target, !options.silent());
        listener.changed(this);
        return null;
    }

    /** Whether the step still needs work now, is already settled, or must wait. Settled steps are marked done here. */
    private Check check(int step, UUID npc, Context context) {
        BlockPos at = plan.stepPosition(step);
        if (!context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) {
            reason = "waiting for an unloaded chunk at " + at.x() + " " + at.z();
            return Check.WAIT;
        }
        Optional<String> current = context.world().block(world, at);
        if (current.isEmpty()) { refused++; complete(step); return Check.DONE; }
        String target = plan.target(step).toString();
        if (plan.excavation(step)) {
            if (air(current.get()) || current.get().equals(plan.design(step).toString())) { skipped++; complete(step); return Check.DONE; }
        } else if (current.get().equals(target)) { skipped++; complete(step); return Check.DONE; }
        BuildProtection.Action action = plan.target(step).air() ? BuildProtection.Action.BREAK : BuildProtection.Action.PLACE;
        if (!context.protection().allows(new BuildProtection.Request(npc, startedBy, world, at, action))) { denied++; complete(step); return Check.DONE; }
        if (plan.excavation(step) || options.materials() == BuildOptions.Materials.CREATIVE) return Check.WORK;
        MaterialRules.Need need = need(plan.paletteIndex(step), context.world());
        if (need.kind() == MaterialRules.Kind.UNOBTAINABLE) { unobtainable++; complete(step); return Check.DONE; }
        if (need.kind() == MaterialRules.Kind.FREE) return Check.WORK;
        long have = available(need.item(), context.world());
        if (have < 0) { reason = "the material container at " + container() + " is not loaded or not a container"; return Check.WAIT; }
        if (have < need.count()) {
            long wanted = remaining().getOrDefault(need.item(), (long) need.count());
            reason = "needs " + need.count() + " " + need.item() + " (have " + have + ", the rest of the build needs " + wanted + ")";
            return Check.WAIT;
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
            UndoJournal.Entry entry = journal.get(undoRemaining - 1);
            BlockPos at = entry.position();
            if (!context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) {
                reason = "waiting for an unloaded chunk at " + at.x() + " " + at.z();
                return;
            }
            if (!context.budget().revert()) return;
            reason = "";
            undoRemaining--;
            Optional<String> current = context.world().block(world, at);
            if (current.isEmpty() || !current.get().equals(entry.after())) { undoSkipped++; continue; }
            BuildProtection.Action action = air(entry.before()) ? BuildProtection.Action.BREAK : BuildProtection.Action.PLACE;
            UUID npc = context.crew().member(leader()).map(BuildCrew.Member::uuid).orElse(new UUID(0, 0));
            if (!context.protection().allows(new BuildProtection.Request(npc, startedBy, world, at, action))) { undoSkipped++; continue; }
            if (context.world().place(world, at, entry.before())) { reverted++; context.crew().terrainChanged(world, at); } else undoSkipped++;
            listener.changed(this);
        }
        finishUndo();
    }

    private void finishUndo() {
        state = State.UNDONE;
        undoRemaining = 0;
        listener.tell(startedBy, "Build " + id + " undone: " + reverted + " blocks restored, " + undoSkipped + " left because they changed since");
        listener.changed(this);
    }

    // Materials

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
     * found when the NPC reaches them. Creative builds need nothing.
     */
    public Map<String, Long> remaining() {
        Map<String, Long> totals = new TreeMap<>();
        if (plan == null || options.materials() == BuildOptions.Materials.CREATIVE || worldAccess == null) return totals;
        int[] perPalette = new int[needs.length];
        for (int step = Math.max(cursor, plan.excavations()); step < plan.size(); step++) if (!done.get(step)) perPalette[plan.paletteIndex(step)]++;
        for (int index = 0; index < perPalette.length; index++) {
            if (perPalette[index] == 0) continue;
            MaterialRules.Need need = need(index, worldAccess);
            if (need.kind() == MaterialRules.Kind.ITEM) totals.merge(need.item(), (long) need.count() * perPalette[index], Long::sum);
        }
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
            return session;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid saved build: " + invalid.getMessage(), invalid);
        }
    }
}
