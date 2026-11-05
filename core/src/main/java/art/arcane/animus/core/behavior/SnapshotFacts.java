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

    public SnapshotFacts(Function<UUID, Optional<Map<String, Object>>> npcs) { this.npcs = Objects.requireNonNull(npcs); }

    /** Replaces the player's snapshot. Values must be Boolean, Long, Double or String. */
    public void player(UUID player, Map<String, Object> facts) {
        Objects.requireNonNull(player);
        if (players.size() >= MAXIMUM_PLAYERS && !players.containsKey(player)) return;
        players.put(player, Map.copyOf(facts));
    }
    public void forget(UUID player) { players.remove(player); }
    public void server(Map<String, Object> facts) { server = Map.copyOf(facts); }

    @Override public Optional<Map<String, Object>> player(UUID player) { return Optional.ofNullable(players.get(player)); }
    @Override public Optional<Map<String, Object>> npc(UUID npc) { return npcs.apply(npc); }
    @Override public Map<String, Object> server() { return server; }

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

    /** NPC facts: name, id, uuid, spawned, health, world, x, y, z, location. */
    public static Map<String, Object> npc(String name, String id, UUID uuid, boolean spawned, double health, String world, double x, double y, double z) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", name);
        facts.put("id", id);
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

    public static Map<String, Object> server(long onlinePlayers, long maxPlayers, long tick, long timeMillis) {
        return Map.of("online_players", onlinePlayers, "max_players", maxPlayers, "tick", tick, "time_millis", timeMillis);
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
                facts.put("uuid", npc.toString());
                facts.put("spawned", false);
                facts.put("health", 0.0);
                return Optional.of(facts);
            }
            art.arcane.animus.core.virtual.VirtualNpcState.Snapshot snapshot = state.get();
            return Optional.of(npc(definition.name(), definition.id(), npc, snapshot.active(), snapshot.health(), snapshot.position().world(),
                    snapshot.position().point().x(), snapshot.position().point().y(), snapshot.position().point().z()));
        };
    }

    public static String location(double x, double y, double z, String world) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%s", x, y, z, world);
    }
}
