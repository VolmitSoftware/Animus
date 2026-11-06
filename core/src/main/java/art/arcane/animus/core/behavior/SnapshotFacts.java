package art.arcane.animus.core.behavior;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link BehaviorFacts} answered from snapshots a platform publishes from its owner threads. Player snapshots are bounded
 * by online players: platforms {@link #player publish} them at most every {@link #REFRESH_TICKS} ticks and {@link #forget}
 * them on quit. NPC facts come from a quick, thread-safe lookup of the runtime's NPC state.
 */
public final class SnapshotFacts implements BehaviorFacts {
    /** How often, in ticks, a platform refreshes each online player's snapshot. */
    public static final int REFRESH_TICKS = 10;
    public static final int MAXIMUM_PLAYERS = 16384;
    private final Map<UUID, Map<String, Object>> players = new ConcurrentHashMap<>();
    private final Function<UUID, Optional<Map<String, Object>>> npcs;
    private volatile Map<String, Object> server = Map.of();
    private volatile Function<String, Optional<String>> worlds = (String reference) -> Optional.empty();

    public SnapshotFacts(Function<UUID, Optional<Map<String, Object>>> npcs) { this.npcs = Objects.requireNonNull(npcs); }

    /** Replaces the player's snapshot. Values must be Boolean, Long, Double or String. */
    public void player(UUID player, Map<String, Object> facts) {
        Objects.requireNonNull(player);
        if (players.size() >= MAXIMUM_PLAYERS && !players.containsKey(player)) return;
        players.put(player, Map.copyOf(facts));
    }
    public void forget(UUID player) { players.remove(player); }
    public void server(Map<String, Object> facts) { server = Map.copyOf(facts); }
    /**
     * The platform's world resolver, which must be quick and safe on any thread: the form its player facts give for a
     * world name, dimension key or UUID. NPC worlds are reported in that form too, so {@code npc.world} and
     * {@code player.world} are equal in the same world.
     */
    public void worlds(Function<String, Optional<String>> resolver) { worlds = Objects.requireNonNull(resolver); }

    @Override public Optional<Map<String, Object>> player(UUID player) { return Optional.ofNullable(players.get(player)); }
    @Override public Optional<Map<String, Object>> npc(UUID npc) { return npcs.apply(npc).map(this::canonicalWorld); }
    @Override public Map<String, Object> server() { return server; }
    @Override public Optional<String> world(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        try { return worlds.apply(reference); }
        catch (RuntimeException failure) { return Optional.empty(); }
    }

    /** The facts with the world in the resolver's form; a world it does not know stays as the runtime reported it. */
    private Map<String, Object> canonicalWorld(Map<String, Object> facts) {
        if (!(facts.get("world") instanceof String reported)) return facts;
        Optional<String> named = world(reported);
        if (named.isEmpty() || named.get().equals(reported)) return facts;
        Map<String, Object> copy = new LinkedHashMap<>(facts);
        copy.put("world", named.get());
        if (facts.get("x") instanceof Double x && facts.get("y") instanceof Double y && facts.get("z") instanceof Double z) copy.put("location", location(x, y, z, named.get()));
        return copy;
    }

    /** The player facts the expression language names, built from plain values; {@code location} is {@code x,y,z,world} to two decimals. */
    public static Map<String, Object> player(String name, UUID uuid, double health, double maxHealth, long food, long level, String world,
                                             double x, double y, double z, String gamemode, boolean op, boolean sneaking, boolean flying,
                                             String itemInHand, long itemInHandAmount) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", name);
        facts.put("uuid", uuid.toString());
        facts.put("online", true);
        facts.put("health", health);
        facts.put("max_health", maxHealth);
        facts.put("food", food);
        facts.put("level", level);
        facts.put("world", world);
        facts.put("x", x);
        facts.put("y", y);
        facts.put("z", z);
        facts.put("location", location(x, y, z, world));
        facts.put("gamemode", gamemode.toLowerCase(Locale.ROOT));
        facts.put("op", op);
        facts.put("sneaking", sneaking);
        facts.put("flying", flying);
        facts.put("item_in_hand", itemInHand);
        facts.put("item_in_hand_amount", itemInHandAmount);
        return facts;
    }

    /**
     * NPC facts: name, id, citizens_id, uuid, spawned, health, world, x, y, z, location. {@code citizensId} is the numeric
     * Citizens id of an NPC migrated from Citizens, and the Animus id of any other NPC.
     */
    public static Map<String, Object> npc(String name, String id, String citizensId, UUID uuid, boolean spawned, double health, String world, double x, double y, double z) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", name);
        facts.put("id", id);
        facts.put("citizens_id", citizensId);
        facts.put("uuid", uuid.toString());
        facts.put("spawned", spawned);
        facts.put("health", health);
        facts.put("world", world);
        facts.put("x", x);
        facts.put("y", y);
        facts.put("z", z);
        facts.put("location", location(x, y, z, world));
        return facts;
    }

    /** The most player UUIDs the {@code players} fact lists. */
    public static final int MAXIMUM_LISTED_PLAYERS = 1024;

    /** Server facts; {@code players} lists the online players' UUIDs, at most {@link #MAXIMUM_LISTED_PLAYERS}. */
    public static Map<String, Object> server(long onlinePlayers, long maxPlayers, long tick, long timeMillis, java.util.Collection<UUID> players) {
        java.util.List<String> listed = new java.util.ArrayList<>(Math.min(players.size(), MAXIMUM_LISTED_PLAYERS));
        for (UUID player : players) { if (listed.size() >= MAXIMUM_LISTED_PLAYERS) break; listed.add(player.toString()); }
        return Map.of("online_players", onlinePlayers, "players", java.util.List.copyOf(listed), "max_players", maxPlayers, "tick", tick, "time_millis", timeMillis);
    }

    /**
     * NPC facts from the definitions and the runtime's NPC state: a definition's NPC is known even while it is not
     * presented, with spawned false, health 0 and no position facts. The UUID index is rebuilt only when the definitions
     * snapshot changes.
     */
    public static Function<UUID, Optional<Map<String, Object>>> npcs(java.util.function.Supplier<Map<String, art.arcane.animus.core.npc.NpcDefinition>> definitions,
                                                                     Function<UUID, Optional<art.arcane.animus.core.virtual.VirtualNpcState.Snapshot>> states) {
        Objects.requireNonNull(definitions); Objects.requireNonNull(states);
        record Indexed(Map<String, art.arcane.animus.core.npc.NpcDefinition> source, Map<UUID, art.arcane.animus.core.npc.NpcDefinition> byUuid) {}
        java.util.concurrent.atomic.AtomicReference<Indexed> cache = new java.util.concurrent.atomic.AtomicReference<>(new Indexed(Map.of(), Map.of()));
        return (UUID npc) -> {
            Map<String, art.arcane.animus.core.npc.NpcDefinition> source = definitions.get();
            Indexed indexed = cache.get();
            if (indexed.source() != source) {
                Map<UUID, art.arcane.animus.core.npc.NpcDefinition> byUuid = new java.util.HashMap<>();
                for (art.arcane.animus.core.npc.NpcDefinition definition : source.values()) byUuid.put(definition.uuid(), definition);
                indexed = new Indexed(source, Map.copyOf(byUuid));
                cache.set(indexed);
            }
            art.arcane.animus.core.npc.NpcDefinition definition = indexed.byUuid().get(npc);
            if (definition == null) return Optional.empty();
            Optional<art.arcane.animus.core.virtual.VirtualNpcState.Snapshot> state = states.apply(npc);
            if (state.isEmpty()) {
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("name", definition.name());
                facts.put("id", definition.id());
                facts.put("citizens_id", definition.citizensId().orElse(definition.id()));
                facts.put("uuid", npc.toString());
                facts.put("spawned", false);
                facts.put("health", 0.0);
                return Optional.of(facts);
            }
            art.arcane.animus.core.virtual.VirtualNpcState.Snapshot snapshot = state.get();
            return Optional.of(npc(definition.name(), definition.id(), definition.citizensId().orElse(definition.id()), npc, snapshot.active(), snapshot.health(), snapshot.position().world(),
                    snapshot.position().point().x(), snapshot.position().point().y(), snapshot.position().point().z()));
        };
    }

    public static String location(double x, double y, double z, String world) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%s", x, y, z, world);
    }
}
