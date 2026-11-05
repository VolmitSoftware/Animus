package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.CollisionCells;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** In-memory world, crew and listener for driving builds without a server. */
final class BuildFixtures {
    static final String WORLD = "world";

    private BuildFixtures() {}

    /** A schematic from layer strings: each layer is rows of characters, one row per Z, one character per X. */
    static Schematic schematic(Map<Character, String> legend, String[]... layers) {
        int height = layers.length, length = layers[0].length, width = layers[0][0].length();
        List<BlockState> palette = new ArrayList<>();
        Map<Character, Integer> indices = new HashMap<>();
        int[] cells = new int[width * height * length];
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++) {
                    char symbol = layers[y][z].charAt(x);
                    int index = x + z * width + y * width * length;
                    if (symbol == '.') { cells[index] = Schematic.VOID; continue; }
                    cells[index] = indices.computeIfAbsent(symbol, (Character key) -> {
                        palette.add(BlockState.parse(legend.get(key)));
                        return palette.size() - 1;
                    });
                }
        return new Schematic("test", width, height, length, 0, 0, 0, palette, cells, 0, List.of());
    }

    static BuildPlan plan(Schematic schematic, BuildOptions options, BlockPos origin) {
        List<BlockState> palette = new ArrayList<>();
        for (BlockState state : schematic.palette()) palette.add(options.transform().apply(state));
        boolean[] placeable = new boolean[palette.size()];
        Arrays.fill(placeable, true);
        return BuildPlanner.plan(schematic, palette, placeable, options, origin);
    }

    static final class World implements BuildWorld {
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();
        final Map<String, Long> container = new LinkedHashMap<>();
        final Map<String, String> items = new HashMap<>();
        final List<BlockPos> effects = new ArrayList<>();
        boolean containerPresent = true;
        int minimumY = -64, maximumY = 320;

        @Override public boolean worldLoaded(String world) { return world.equals(WORLD); }
        @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) { return !unloaded.contains(key(chunkX, chunkZ)); }
        @Override public Optional<String> block(String world, BlockPos at) {
            if (at.y() < minimumY || at.y() >= maximumY || !chunkLoaded(world, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return Optional.empty();
            return Optional.of(blocks.getOrDefault(at, "minecraft:air"));
        }
        @Override public Optional<String> resolve(BlockState state) { return state.path().startsWith("unknown") ? Optional.empty() : Optional.of(state.toString()); }
        @Override public Optional<String> item(String state) {
            String name = BlockState.parse(state).name();
            if (items.containsKey(name)) return Optional.ofNullable(items.get(name)).filter((String item) -> !item.isEmpty());
            return Optional.of(name);
        }
        @Override public boolean place(String world, BlockPos at, String state) {
            if (block(world, at).isEmpty()) return false;
            if (BuildSession.air(state)) blocks.remove(at); else blocks.put(at, state);
            return true;
        }
        @Override public void placed(String world, BlockPos at, String state, boolean sound) { effects.add(at); }
        @Override public long containerCount(String world, BlockPos at, String item) { return containerPresent ? container.getOrDefault(item, 0L) : -1; }
        @Override public boolean containerTake(String world, BlockPos at, String item, int count) {
            long have = container.getOrDefault(item, 0L);
            if (have < count) return false;
            container.put(item, have - count);
            return true;
        }
        @Override public MotionValidator.Sampler collision(String world) {
            return (int x, int y, int z) -> {
                if (!chunkLoaded(world, Math.floorDiv(x, 16), Math.floorDiv(z, 16))) return VoxelSnapshot.Cell.UNAVAILABLE;
                if (y < 64) return VoxelSnapshot.Cell.SOLID;
                String state = blocks.get(new BlockPos(x, y, z));
                if (state != null && state.startsWith(ScaffoldPlanner.BLOCK)) return CollisionCells.classify("SCAFFOLDING", List.of(), false);
                return state == null || BuildSession.air(state) ? VoxelSnapshot.Cell.AIR : VoxelSnapshot.Cell.SOLID;
            };
        }
        void unload(int chunkX, int chunkZ) { unloaded.add(key(chunkX, chunkZ)); }
        void load(int chunkX, int chunkZ) { unloaded.remove(key(chunkX, chunkZ)); }
        private static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }
    }

    static final class Crew implements BuildCrew {
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, Integer> swings = new HashMap<>();
        final Map<String, Optional<String>> held = new HashMap<>();
        final List<WorldPosition> walks = new ArrayList<>();
        final List<BlockPos> terrain = new ArrayList<>();
        final List<WorldPosition> path = new ArrayList<>();
        int relocations, stops;
        boolean walkInstantly = true;
        /** Where a walk can take an NPC; anywhere unless a test says otherwise. */
        Predicate<WorldPosition> walkable = (WorldPosition goal) -> true;

        Member add(String id, double x, double y, double z) {
            Member member = new Member(id, UUID.nameUUIDFromBytes(id.getBytes()), at(x, y, z), .6, 1.8);
            members.put(id, member);
            return member;
        }
        @Override public Optional<Member> member(String npc) { return Optional.ofNullable(members.get(npc)); }
        @Override public boolean routing(String npc) { return false; }
        @Override public void walk(String npc, WorldPosition goal, double speed) {
            walks.add(goal);
            if (walkInstantly && walkable.test(goal)) move(npc, goal);
        }
        @Override public void stop(String npc) { stops++; }
        @Override public boolean relocate(String npc, WorldPosition to) { relocations++; move(npc, to); return true; }
        @Override public void face(String npc, WorldPosition at) { }
        @Override public void swing(String npc) { swings.merge(npc, 1, Integer::sum); }
        @Override public void hold(String npc, Optional<String> item) { held.put(npc, item); }
        @Override public void terrainChanged(String world, BlockPos at) { terrain.add(at); }
        private void move(String npc, WorldPosition to) {
            Member old = members.get(npc);
            members.put(npc, new Member(old.id(), old.uuid(), to, old.width(), old.height()));
            path.add(to);
        }
    }

    static final class Events implements BuildSession.Listener {
        final List<String> paused = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        int finished, changes;
        @Override public void paused(BuildSession session, String reason) { paused.add(reason); }
        @Override public void finished(BuildSession session) { finished++; }
        @Override public void changed(BuildSession session) { changes++; }
        @Override public void tell(Optional<UUID> player, String message) { messages.add(message); }
    }

    static WorldPosition at(double x, double y, double z) { return new WorldPosition(WORLD, new Vec3(x, y, z)); }

    /** Ticks until the session leaves the given states or the limit runs out. */
    static int run(BuildSession session, BuildSession.Context context, int limit) {
        int ticks = 0;
        while (ticks < limit && (session.state() == BuildSession.State.RUNNING || session.state() == BuildSession.State.UNDOING)) {
            session.tick(new BuildSession.Context(context.world(), context.crew(), context.protection(), new BuildSession.Budget(64, 512, 64)));
            ticks++;
        }
        return ticks;
    }
}
