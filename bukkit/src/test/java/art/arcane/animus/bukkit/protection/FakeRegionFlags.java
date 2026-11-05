package art.arcane.animus.bukkit.protection;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Answers flag queries from a table keyed by block column, and records each query. */
final class FakeRegionFlags implements RegionFlags {
    record Query(String kind, WorldPosition at, UUID player, List<Flag> flags) { }

    private final Map<String, State> states = new HashMap<>();
    private final Map<String, Boolean> builds = new HashMap<>();
    private final Map<String, Boolean> regions = new HashMap<>();
    final List<Query> queries = new ArrayList<>();
    RuntimeException failure;

    FakeRegionFlags set(double x, double z, Flag flag, State state) { states.put(key(x, z, flag), state); return this; }
    FakeRegionFlags build(double x, double z, UUID player, boolean allowed) { builds.put(x + "," + z + "," + player, allowed); return this; }
    FakeRegionFlags region(String name, double x, double z, boolean inside) { regions.put(name + "@" + (int) Math.floor(x) + "," + (int) Math.floor(z), inside); return this; }

    @Override public State state(WorldPosition at, UUID player, Flag... flags) {
        queries.add(new Query("state", at, player, List.of(flags)));
        if (failure != null) throw failure;
        State combined = State.UNSET;
        for (Flag flag : flags) {
            State found = states.getOrDefault(key(at.point().x(), at.point().z(), flag), State.UNSET);
            if (found == State.DENY) return State.DENY;
            if (found == State.ALLOW) combined = State.ALLOW;
        }
        return combined;
    }

    @Override public boolean canBuild(WorldPosition at, UUID player, Flag flag) {
        queries.add(new Query("build", at, player, List.of(flag)));
        if (failure != null) throw failure;
        Boolean listed = builds.get(at.point().x() + "," + at.point().z() + "," + player);
        if (listed != null) return listed;
        for (Flag needed : List.of(Flag.BUILD, flag)) if (states.get(key(at.point().x(), at.point().z(), needed)) == State.DENY) return false;
        return true;
    }

    @Override public Optional<Boolean> inRegion(String region, WorldPosition at) {
        queries.add(new Query("region", at, null, List.of()));
        if (failure != null) throw failure;
        return Optional.ofNullable(regions.get(region + "@" + (int) Math.floor(at.point().x()) + "," + (int) Math.floor(at.point().z())));
    }

    private static String key(double x, double z, Flag flag) { return (int) Math.floor(x) + "," + (int) Math.floor(z) + "," + flag; }
}
