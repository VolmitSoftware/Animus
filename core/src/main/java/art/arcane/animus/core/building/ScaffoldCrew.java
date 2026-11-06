package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The scaffolding of one build's team, on the owner thread. Each NPC climbs and works from its own {@link Scaffold}.
 * Every scaffolding block is protection-checked, paid from the build's materials (free in creative), journaled, and
 * handed back to the build's materials when it comes down. Blocks whose NPC is gone, and blocks a restart left standing,
 * are taken down directly. Nothing here loads a chunk.
 */
final class ScaffoldCrew {
    /** Ladders and scaffolding are climbed at about this many blocks a second. */
    static final double CLIMB_SPEED = 2.35;
    private static final int SEARCH_READS = 4096, RETARGET_RISE = 2;
    private final String world;
    private final BuildOptions options;
    private final MaterialLedger ledger;
    private final UndoJournal journal;
    private final Optional<UUID> startedBy;
    private final Map<String, Long> nextPlace;
    private final Runnable changed;
    private final Map<String, Scaffold> active = new LinkedHashMap<>();
    private final List<Scaffold.Cell> orphans = new ArrayList<>();
    private final Set<BlockPos> standing = new HashSet<>();
    private final Map<String, Optional<String>> held;
    private final Map<String, Long> nextWalk = new HashMap<>();
    private final Set<String> aborted = new HashSet<>();
    private final Map<String, String> states = new HashMap<>();

    ScaffoldCrew(String world, BuildOptions options, MaterialLedger ledger, UndoJournal journal, Optional<UUID> startedBy,
                 Map<String, Long> nextPlace, Map<String, Optional<String>> held, Runnable changed) {
        this.world = world; this.options = options; this.ledger = ledger; this.journal = journal; this.startedBy = startedBy;
        this.nextPlace = nextPlace; this.held = held; this.changed = changed;
    }

    Optional<Scaffold> of(String npc) { return Optional.ofNullable(active.get(npc)); }
    /** The NPC is putting up, moving or taking down its scaffold, not building from it. */
    boolean busy(String npc) { Scaffold scaffold = active.get(npc); return scaffold != null && !scaffold.working(); }
    boolean empty() { return active.isEmpty() && orphans.isEmpty(); }
    /** A scaffolding block the build placed stands here. */
    boolean holds(BlockPos at) { return standing.contains(at); }
    /** Scaffolding blocks standing now. */
    int standing() { return standing.size(); }
    /** Some block of scaffolding still to come down, if any. */
    Optional<BlockPos> anyCell() {
        if (!orphans.isEmpty()) return Optional.of(orphans.getFirst().at());
        return standing.stream().findFirst();
    }

    /** Plans a scaffold for a block no ground reaches. False when no place fits. */
    boolean start(BuildCrew.Member member, BlockPos target, List<BlockPos> upcoming, BuildPlan plan, BuildSession.Context context) {
        Optional<ScaffoldPlanner.Layout> layout = ScaffoldPlanner.find(target, options.reach(), upcoming, site(plan, context, member.id()), SEARCH_READS);
        if (layout.isEmpty()) return false;
        active.put(member.id(), new Scaffold(member.id(), layout.get()));
        return true;
    }

    /**
     * When the block is out of reach of the scaffold's top: grows or shrinks the column under the NPC when a stand above
     * it reaches the block, otherwise takes the scaffold down so a new one can go up.
     */
    void follow(BuildCrew.Member member, BlockPos target, BuildPlan plan, BuildSession.Context context) {
        Scaffold scaffold = active.get(member.id());
        if (scaffold == null) return;
        ScaffoldPlanner.Layout layout = scaffold.layout();
        BlockPos base = layout.base();
        if (layout.walkway().isEmpty()) {
            ScaffoldPlanner.Site site = site(plan, context, member.id());
            for (int rise = -RETARGET_RISE; rise <= RETARGET_RISE; rise++) {
                BlockPos stand = new BlockPos(base.x(), target.y() + rise, base.z());
                if (stand.y() <= base.y() || !ScaffoldPlanner.reaches(stand, target, options.reach())) continue;
                List<BlockPos> column = new ArrayList<>();
                boolean fits = true;
                for (int y = base.y(); y < stand.y() && fits; y++) {
                    BlockPos cell = new BlockPos(base.x(), y, base.z());
                    fits = standing.contains(cell) || site.free(cell);
                    column.add(cell);
                }
                if (!fits || !site.free(stand) || !site.free(new BlockPos(stand.x(), stand.y() + 1, stand.z()))) continue;
                if (scaffold.retarget(new ScaffoldPlanner.Layout(column, List.of(), stand))) return;
            }
        }
        scaffold.lower(0);
    }

    /** True once after the NPC's scaffold could not go up where it was planned. */
    boolean aborted(String npc) { return aborted.remove(npc); }

    /** Takes every NPC's scaffold down. */
    void lowerAll() { for (Scaffold scaffold : active.values()) scaffold.lower(0); }

    /** The NPC is gone: its scaffolding is taken down without it. */
    void abandon(String npc) {
        Scaffold scaffold = active.remove(npc);
        if (scaffold != null) orphans.addAll(scaffold.placed());
    }
    void abandonAll() { for (String npc : List.copyOf(active.keySet())) abandon(npc); }

    /** Undo puts the world back from the journal, scaffolding included; nothing is tracked any more. */
    void forget() { active.clear(); orphans.clear(); standing.clear(); }

    /**
     * One step of the NPC's scaffold work. Returns why the build must wait, or null.
     */
    String turn(BuildCrew.Member member, BuildSession.Context context, long tick) {
        Scaffold scaffold = active.get(member.id());
        if (scaffold == null) return null;
        Vec3 feet = member.position().point();
        Scaffold.Action action = scaffold.next(feet, tick);
        switch (action) {
            case Scaffold.Move move -> {
                if (scaffold.moving(tick) > (long) options.moveTimeoutSeconds() * 20) {
                    // Last resort, as for any block: move the NPC to the spot it could not reach.
                    context.crew().relocate(member.id(), new WorldPosition(world, move.feet()));
                    return null;
                }
                if (!context.crew().routing(member.id()) && tick >= nextWalk.getOrDefault(member.id(), 0L)) {
                    nextWalk.put(member.id(), tick + 10);
                    try { context.crew().walk(member.id(), new WorldPosition(world, move.feet()), Math.min(options.speed(), CLIMB_SPEED)); }
                    catch (IllegalArgumentException | IllegalStateException refused) { /* the move timeout moves the NPC instead */ }
                }
                return null;
            }
            case Scaffold.Put put -> { return put(member, scaffold, put, context, tick); }
            case Scaffold.Take take -> { take(member, scaffold, take.cell(), context, tick); return null; }
            case Scaffold.Ready ready -> { return null; }
            case Scaffold.Done done -> {
                active.remove(member.id());
                if (scaffold.aborted()) aborted.add(member.id());
                changed.run();
                return null;
            }
        }
    }
    private String put(BuildCrew.Member member, Scaffold scaffold, Scaffold.Put put, BuildSession.Context context, long tick) {
        if (tick < nextPlace.getOrDefault(member.id(), 0L) || !context.budget().place()) return null;
        BlockPos at = put.at();
        if (!context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return BuildSession.waitingFor(context.world(), world, at);
        Optional<String> before = context.world().block(world, at);
        Optional<String> state = state(put.distance(), context.world());
        if (before.isEmpty() || !BuildSession.air(before.get()) || state.isEmpty()
                || !context.protection().allows(new BuildProtection.Request(member.uuid(), startedBy, world, at, BuildProtection.Action.PLACE))) {
            // The spot is taken or refused now: take this scaffold down and reach the block another way.
            scaffold.abort();
            return null;
        }
        if (options.materials() != BuildOptions.Materials.CREATIVE) {
            long have = available(context.world());
            if (have < 0) return BuildSession.containerMissing(context.world(), world, options.container().orElseThrow());
            if (have < 1) return "needs 1 " + ScaffoldPlanner.BLOCK + " to put up scaffolding (have 0)";
        }
        if (!context.world().place(world, at, state.get())) { scaffold.abort(); return null; }
        if (options.materials() != BuildOptions.Materials.CREATIVE) take(context.world());
        journal.scaffold(at, before.get(), state.get());
        Scaffold.Cell cell = new Scaffold.Cell(at, before.get(), state.get());
        scaffold.placed(cell);
        standing.add(at);
        act(member.id(), at, state.get(), context, tick);
        return null;
    }

    private void take(BuildCrew.Member member, Scaffold scaffold, Scaffold.Cell cell, BuildSession.Context context, long tick) {
        if (tick < nextPlace.getOrDefault(member.id(), 0L) || !context.budget().place()) return;
        if (!context.world().chunkLoaded(world, Math.floorDiv(cell.at().x(), 16), Math.floorDiv(cell.at().z(), 16))) return;
        scaffold.removed();
        if (remove(cell, member.uuid(), context)) act(member.id(), cell.at(), cell.after(), context, tick);
    }

    /** Takes down blocks no NPC looks after, within the tick's placement budget; all of them when {@code unbounded}. */
    void dismantle(BuildSession.Context context, UUID npc, boolean unbounded) {
        orphans.sort(Comparator.comparingInt((Scaffold.Cell cell) -> -cell.at().y()));
        while (!orphans.isEmpty()) {
            Scaffold.Cell cell = orphans.getFirst();
            if (!context.world().chunkLoaded(world, Math.floorDiv(cell.at().x(), 16), Math.floorDiv(cell.at().z(), 16))) return;
            if (!unbounded && !context.budget().place()) return;
            orphans.removeFirst();
            remove(cell, npc, context);
        }
    }

    /** Removes one block if it is still the scaffolding the build placed; true when it came down. */
    private boolean remove(Scaffold.Cell cell, UUID npc, BuildSession.Context context) {
        standing.remove(cell.at());
        Optional<String> current = context.world().block(world, cell.at());
        if (current.isEmpty() || !current.get().equals(cell.after())) { changed.run(); return false; }
        if (!context.protection().allows(new BuildProtection.Request(npc, startedBy, world, cell.at(), BuildProtection.Action.BREAK))) { changed.run(); return false; }
        if (!context.world().place(world, cell.at(), cell.before())) { changed.run(); return false; }
        journal.scaffold(cell.at(), cell.after(), cell.before());
        if (options.materials() != BuildOptions.Materials.CREATIVE) ledger.add(ScaffoldPlanner.BLOCK, 1);
        context.crew().terrainChanged(world, cell.at());
        changed.run();
        return true;
    }

    private void act(String npc, BlockPos at, String state, BuildSession.Context context, long tick) {
        nextPlace.put(npc, tick + options.placeIntervalTicks());
        if (options.holdBlock() && !Optional.of(ScaffoldPlanner.BLOCK).equals(held.get(npc))) {
            held.put(npc, Optional.of(ScaffoldPlanner.BLOCK));
            context.crew().hold(npc, Optional.of(ScaffoldPlanner.BLOCK));
        }
        context.crew().face(npc, new WorldPosition(world, StandPoints.centre(at)));
        context.crew().swing(npc);
        context.crew().terrainChanged(world, at);
        context.world().placed(world, at, state, !options.silent());
        changed.run();
    }

    private Optional<String> state(int distance, BuildWorld access) {
        String text = ScaffoldPlanner.BLOCK + "[bottom=false,distance=" + Math.max(0, Math.min(7, distance)) + ",waterlogged=false]";
        String known = states.get(text);
        if (known != null) return Optional.of(known);
        Optional<String> resolved = access.resolve(BlockState.parse(text));
        resolved.ifPresent((String value) -> states.put(text, value));
        return resolved;
    }

    /** Scaffolding comes from what came down before, then from the build's own materials. */
    private long available(BuildWorld access) {
        long held = ledger.available(ScaffoldPlanner.BLOCK);
        if (options.materials() != BuildOptions.Materials.CONTAINER) return held;
        long contained = access.containerCount(world, options.container().orElseThrow(), ScaffoldPlanner.BLOCK);
        return contained < 0 ? (held > 0 ? held : -1) : held + contained;
    }
    private void take(BuildWorld access) {
        if (ledger.take(ScaffoldPlanner.BLOCK, 1)) return;
        access.containerTake(world, options.container().orElseThrow(), ScaffoldPlanner.BLOCK, 1);
    }

    /** Cells a new scaffold may use: empty now, not wanted by the build, and not used by any other scaffold. */
    private ScaffoldPlanner.Site site(BuildPlan plan, BuildSession.Context context, String npc) {
        Set<BlockPos> reserved = new HashSet<>(standing);
        for (Map.Entry<String, Scaffold> entry : active.entrySet())
            if (!entry.getKey().equals(npc)) reserved.addAll(entry.getValue().layout().cells());
        return new ScaffoldPlanner.Site() {
            @Override public boolean free(BlockPos at) {
                if (reserved.contains(at) || !context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return false;
                Optional<String> current = context.world().block(world, at);
                return current.isPresent() && BuildSession.air(current.get()) && plan.designAt(at).map(BlockState::air).orElse(true);
            }
            @Override public boolean ground(BlockPos at) {
                if (!context.world().chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return false;
                VoxelSnapshot.Cell cell = context.world().collision(world).sample(at.x(), at.y(), at.z());
                return !cell.unavailable() && cell.collides() && !cell.hazard() && !cell.climbable() && Math.abs(cell.top() - 1) < 1e-6;
            }
        };
    }

    // Saving

    JsonArray toJson() {
        JsonArray scaffolds = new JsonArray();
        for (Scaffold scaffold : active.values()) scaffolds.add(json(scaffold.npc, scaffold.placed()));
        if (!orphans.isEmpty()) scaffolds.add(json("", orphans));
        return scaffolds;
    }
    private static JsonObject json(String npc, List<Scaffold.Cell> cells) {
        JsonObject json = new JsonObject();
        json.addProperty("npc", npc);
        JsonArray rows = new JsonArray();
        for (Scaffold.Cell cell : cells) {
            JsonArray row = new JsonArray();
            row.add(cell.at().x()); row.add(cell.at().y()); row.add(cell.at().z()); row.add(cell.before()); row.add(cell.after());
            rows.add(row);
        }
        json.add("cells", rows);
        return json;
    }

    /** Scaffolding saved with the build is taken down, never built on, after a restart. */
    void restore(JsonArray saved) {
        for (JsonElement element : saved) {
            for (JsonElement row : element.getAsJsonObject().getAsJsonArray("cells")) {
                JsonArray cell = row.getAsJsonArray();
                if (cell.size() != 5) throw new IllegalArgumentException("scaffolding cell needs x, y, z, before and after");
                BlockPos at = new BlockPos(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt());
                orphans.add(new Scaffold.Cell(at, cell.get(3).getAsString(), cell.get(4).getAsString()));
                standing.add(at);
            }
        }
        if (orphans.size() > ScaffoldPlanner.MAXIMUM_HEIGHT * 64) throw new IllegalArgumentException("too many saved scaffolding blocks");
    }
}
