package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who sees an NPC, from the definition's {@code viewers}. A {@code deny} filter hides the NPC from any player it names,
 * from members of any group it lists and from holders of any permission it lists. An {@code allow} filter shows the
 * NPC only to players who pass every list it has. Group membership is the {@code group.<name>} permission, which
 * permission plugins such as LuckPerms grant to a group's members. A deny filter counts only nodes set for the player,
 * so an operator's default grant of every unset node never hides an NPC from operators. With a {@code range}, players
 * farther away than it are not filtered.
 */
public record ViewerFilter(boolean allow, Set<UUID> players, Set<String> groups, Set<String> permissions, OptionalDouble range) {
    public static final String FIELD = "viewers";
    public static final int MAX_ENTRIES = 256;
    public static final double MAX_RANGE = 512;
    public static final ViewerFilter NONE = new ViewerFilter(false, Set.of(), Set.of(), Set.of(), OptionalDouble.empty());
    private static final Set<String> KEYS = Set.of("mode", "players", "groups", "permissions", "range");
    private static final String NODE = "[A-Za-z0-9_.*-]{1,128}";

    public ViewerFilter { players = Set.copyOf(players); groups = Set.copyOf(groups); permissions = Set.copyOf(permissions); java.util.Objects.requireNonNull(range); }

    /**
     * True when the filter hides the NPC from this player. {@code permitted} answers the player's permission checks, an
     * operator's defaults included; {@code granted} answers only for nodes set for the player and decides a deny filter.
     */
    public boolean hides(UUID player, double distance, Predicate<String> permitted, Predicate<String> granted) {
        if (this == NONE || range.isPresent() && distance > range.getAsDouble()) return false;
        boolean named = players.contains(player);
        if (!allow) return named || groups.stream().anyMatch((String group) -> granted.test("group." + group)) || permissions.stream().anyMatch(granted);
        boolean grouped = groups.stream().anyMatch((String group) -> permitted.test("group." + group));
        boolean held = permissions.stream().anyMatch(permitted);
        return !players.isEmpty() && !named || !groups.isEmpty() && !grouped || !permissions.isEmpty() && !held;
    }

    /** The filter of a definition, NONE when it declares none. */
    public static ViewerFilter of(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        Optional<ViewerFilter> filter = read(definition, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return filter.orElse(NONE);
    }

    static Optional<ViewerFilter> read(JsonObject definition, List<String> errors) {
        JsonElement declared = definition.get(FIELD);
        if (declared == null) return Optional.empty();
        if (!declared.isJsonObject()) { errors.add("$.viewers: expected object"); return Optional.empty(); }
        JsonObject value = declared.getAsJsonObject();
        int before = errors.size();
        for (String key : value.keySet()) if (!KEYS.contains(key)) errors.add("$.viewers." + key + ": unknown field");
        boolean allow = false;
        if (value.has("mode")) {
            JsonElement mode = value.get("mode");
            if (!mode.isJsonPrimitive() || !List.of("allow", "deny").contains(mode.getAsString())) errors.add("$.viewers.mode: expected allow or deny");
            else allow = mode.getAsString().equals("allow");
        }
        Set<UUID> players = new LinkedHashSet<>();
        for (String text : strings(value, "players", errors)) {
            try {
                UUID uuid = UUID.fromString(text);
                if (!uuid.toString().equals(text)) throw new IllegalArgumentException();
                players.add(uuid);
            } catch (IllegalArgumentException invalid) { errors.add("$.viewers.players: expected canonical player UUIDs"); break; }
        }
        Set<String> groups = new LinkedHashSet<>(), permissions = new LinkedHashSet<>();
        for (String group : strings(value, "groups", errors)) {
            if (!group.matches(NODE)) { errors.add("$.viewers.groups: expected group names of 1-128 letters, digits, _, -, . or *"); break; }
            groups.add(group);
        }
        for (String node : strings(value, "permissions", errors)) {
            if (!node.matches(NODE)) { errors.add("$.viewers.permissions: expected permission nodes of 1-128 letters, digits, _, -, . or *"); break; }
            permissions.add(node);
        }
        OptionalDouble range = OptionalDouble.empty();
        if (value.has("range")) {
            JsonElement distance = value.get("range");
            if (!distance.isJsonPrimitive() || !distance.getAsJsonPrimitive().isNumber() || !(distance.getAsDouble() >= 1 && distance.getAsDouble() <= MAX_RANGE))
                errors.add("$.viewers.range: expected 1-" + (int) MAX_RANGE + " blocks");
            else range = OptionalDouble.of(distance.getAsDouble());
        }
        if (errors.size() != before) return Optional.empty();
        return Optional.of(new ViewerFilter(allow, players, groups, permissions, range));
    }

    private static List<String> strings(JsonObject value, String key, List<String> errors) {
        JsonElement declared = value.get(key);
        if (declared == null) return List.of();
        if (!declared.isJsonArray() || declared.getAsJsonArray().size() > MAX_ENTRIES) { errors.add("$.viewers." + key + ": expected an array of at most " + MAX_ENTRIES); return List.of(); }
        List<String> result = new ArrayList<>();
        for (JsonElement entry : declared.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) { errors.add("$.viewers." + key + ": expected strings"); return List.of(); }
            result.add(entry.getAsString());
        }
        return result;
    }
}
