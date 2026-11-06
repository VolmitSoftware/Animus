package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An immutable server-wide event for server behaviors. {@code context} holds plain Boolean, Long, Double, String and List of String values
 * that a run sees as {@code context.<key>}. Publishing grants no world authority: a run only carries the player UUID.
 */
public record ServerEvent(TriggerKind kind, Optional<UUID> player, Map<String, Object> context, Optional<Capabilities.Key> module) {
    /** Text carried by one event; longer chat lines and command arguments are cut at this many characters. */
    public static final int MAXIMUM_TEXT = 4096, MAXIMUM_ARGUMENTS = 256;

    public ServerEvent {
        Objects.requireNonNull(kind); Objects.requireNonNull(player); Objects.requireNonNull(module);
        if (!kind.server()) throw new IllegalArgumentException(kind.jsonName() + " is not a server event");
        context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** The context keys a firing of this event carries; expressions read them as {@code context.<key>}. */
    public static List<String> contextKeys(TriggerKind kind) {
        return switch (kind) {
            case PLAYER_JOIN -> List.of("first");
            case PLAYER_CHAT, PLAYER_DEATH -> List.of("message");
            case PLAYER_WORLD_CHANGE -> List.of("from", "to");
            case COMMAND -> List.of("command", "alias", "args", "raw_args");
            default -> List.of();
        };
    }

    public static ServerEvent join(UUID player, boolean first) { return of(TriggerKind.PLAYER_JOIN, player, Map.of("first", first)); }
    public static ServerEvent quit(UUID player) { return of(TriggerKind.PLAYER_QUIT, player, Map.of()); }
    public static ServerEvent chat(UUID player, String message) { return of(TriggerKind.PLAYER_CHAT, player, Map.of("message", bounded(message))); }
    public static ServerEvent death(UUID player, Optional<String> message) {
        return of(TriggerKind.PLAYER_DEATH, player, message.isPresent() ? Map.of("message", bounded(message.get())) : Map.of());
    }
    public static ServerEvent respawn(UUID player) { return of(TriggerKind.PLAYER_RESPAWN, player, Map.of()); }
    public static ServerEvent worldChange(UUID player, String from, String to) { return of(TriggerKind.PLAYER_WORLD_CHANGE, player, Map.of("from", from, "to", to)); }
    /** Entering or leaving a named area; {@code cause} is join, move or quit. */
    public static ServerEvent area(UUID player, TriggerBinding.Phase phase, String area, String world, String cause) {
        return of(TriggerKind.PLAYER_AREA, player, Map.of("phase", phase.name().toLowerCase(java.util.Locale.ROOT), "area", area, "world", world, "cause", cause));
    }
    /** Opening or closing an inventory; the type is a lowercase name such as chest, player or furnace. */
    public static ServerEvent inventory(TriggerKind kind, UUID player, String type) {
        if (kind != TriggerKind.PLAYER_INVENTORY_OPEN && kind != TriggerKind.PLAYER_INVENTORY_CLOSE) throw new IllegalArgumentException(kind.jsonName() + " is not an inventory open or close");
        return of(kind, player, Map.of("inventory_type", type));
    }
    /** A click on slot {@code slot} (-1 outside the window); {@code click} is left, right, shift_left, number_key and so on, and {@code item} the clicked item's key. */
    public static ServerEvent inventoryClick(UUID player, String type, String click, long slot, String item) {
        return of(TriggerKind.PLAYER_INVENTORY_CLICK, player, Map.of("inventory_type", type, "click", click, "slot", slot, "item", item));
    }
    /** Dropping or picking up {@code amount} of an item, or finishing eating or drinking one. */
    public static ServerEvent item(TriggerKind kind, UUID player, String item, long amount) {
        if (kind != TriggerKind.PLAYER_ITEM_DROP && kind != TriggerKind.PLAYER_ITEM_PICKUP && kind != TriggerKind.PLAYER_ITEM_CONSUME) throw new IllegalArgumentException(kind.jsonName() + " is not an item event");
        return of(kind, player, Map.of("item", item, "amount", amount));
    }
    /** Placing or breaking a block at a block position. */
    public static ServerEvent block(TriggerKind kind, UUID player, String block, String world, long x, long y, long z) {
        if (kind != TriggerKind.PLAYER_BLOCK_PLACE && kind != TriggerKind.PLAYER_BLOCK_BREAK) throw new IllegalArgumentException(kind.jsonName() + " is not a block place or break");
        return of(kind, player, blockContext(block, world, x, y, z));
    }
    /** A left or right click on a block with a hand (main_hand or off_hand). */
    public static ServerEvent blockClick(UUID player, String click, String hand, String block, String world, long x, long y, long z) {
        Map<String, Object> context = blockContext(block, world, x, y, z);
        context.put("click", click);
        context.put("hand", hand);
        return of(TriggerKind.PLAYER_BLOCK_CLICK, player, context);
    }
    private static Map<String, Object> blockContext(String block, String world, long x, long y, long z) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("block", block);
        context.put("world", world);
        context.put("x", x);
        context.put("y", y);
        context.put("z", z);
        context.put("location", art.arcane.animus.core.behavior.SnapshotFacts.location(x, y, z, world));
        return context;
    }
    /** The player takes {@code damage}; {@code cause} is a lowercase damage type and {@code damager} an entity type, empty for none. */
    public static ServerEvent damaged(UUID player, String cause, double damage, String damager) {
        return of(TriggerKind.PLAYER_DAMAGED, player, Map.of("cause", cause, "damage", damage, "damager", damager));
    }
    /** The player hits the living entity {@code entity} (its type key) with UUID {@code entityId}. */
    public static ServerEvent damageEntity(UUID player, String entity, UUID entityId, String cause, double damage) {
        return of(TriggerKind.PLAYER_DAMAGE_ENTITY, player, Map.of("entity", entity, "entity_uuid", entityId.toString(), "cause", cause, "damage", damage));
    }
    /** The player kills the living entity {@code entity}. */
    public static ServerEvent kill(UUID player, String entity, UUID entityId) {
        return of(TriggerKind.PLAYER_KILL, player, Map.of("entity", entity, "entity_uuid", entityId.toString()));
    }
    public static ServerEvent serverStart() { return new ServerEvent(TriggerKind.SERVER_START, Optional.empty(), Map.of(), Optional.empty()); }
    public static ServerEvent timer() { return new ServerEvent(TriggerKind.TIMER, Optional.empty(), Map.of(), Optional.empty()); }
    public static ServerEvent module(Optional<UUID> player, Capabilities.Key key) { return new ServerEvent(TriggerKind.MODULE, player, Map.of(), Optional.of(key)); }

    /**
     * A run of the Animus-managed command {@code name}, typed as {@code alias}. An empty player is the console.
     * {@code args} splits {@code raw_args} on whitespace.
     */
    public static ServerEvent command(Optional<UUID> player, String name, String alias, String rawArguments) {
        String raw = bounded(rawArguments.strip());
        List<String> args = new ArrayList<>();
        if (!raw.isEmpty()) for (String part : raw.split("\\s+")) if (args.size() < MAXIMUM_ARGUMENTS) args.add(part);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("command", name);
        context.put("alias", alias);
        context.put("args", List.copyOf(args));
        context.put("raw_args", raw);
        return new ServerEvent(TriggerKind.COMMAND, player, context, Optional.empty());
    }

    private static ServerEvent of(TriggerKind kind, UUID player, Map<String, Object> context) {
        return new ServerEvent(kind, Optional.of(Objects.requireNonNull(player)), context, Optional.empty());
    }
    private static String bounded(String text) {
        Objects.requireNonNull(text);
        if (text.length() <= MAXIMUM_TEXT) return text;
        int end = MAXIMUM_TEXT;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** Whether this event fires a trigger with this spec. */
    public boolean matches(TriggerBinding.Spec spec) {
        if (spec.kind() != kind) return false;
        return switch (spec) {
            case TriggerBinding.PlayerJoin join -> join.first().isEmpty() || join.first().get().equals(context.get("first"));
            case TriggerBinding.PlayerChat chat -> chat.pattern().isEmpty() || glob(chat.pattern().get(), (String) context.get("message"));
            case TriggerBinding.Command command -> command.name().equals(context.get("command"));
            case TriggerBinding.WorldChange change -> change.from().map(world -> world.equals(context.get("from"))).orElse(true)
                    && change.to().map(world -> world.equals(context.get("to"))).orElse(true);
            case TriggerBinding.ModuleEvent event -> module.map(event.key()::equals).orElse(false);
            case TriggerBinding.WorldEvent world -> filtered(world);
            case TriggerBinding.Area area -> area.phase().name().toLowerCase(java.util.Locale.ROOT).equals(context.get("phase"))
                    && area.area().equals(context.get("area")) && area.world().equals(context.get("world"));
            case TriggerBinding.Lifecycle ignored -> true;
            default -> false;
        };
    }

    private boolean filtered(TriggerBinding.WorldEvent world) {
        for (Map.Entry<String, String> filter : world.filters().entrySet()) {
            Object value = context.get(TriggerKind.FILTERS.get(filter.getKey()));
            if (!(value instanceof String text) || !key(filter.getValue(), text)) return false;
        }
        return true;
    }
    /** A pattern without a colon matches the name after the namespace of a namespaced key, or the whole text. */
    static boolean key(String pattern, String text) {
        if (glob(pattern, text)) return true;
        int colon = text.indexOf(':');
        return colon >= 0 && pattern.indexOf(':') < 0 && glob(pattern, text.substring(colon + 1));
    }

    /** Case-insensitive match of the whole text, where {@code *} matches any run of characters. */
    static boolean glob(String pattern, String text) {
        String p = pattern.toLowerCase(java.util.Locale.ROOT), t = text.toLowerCase(java.util.Locale.ROOT);
        int pi = 0, ti = 0, star = -1, mark = 0;
        while (ti < t.length()) {
            if (pi < p.length() && p.charAt(pi) != '*' && p.charAt(pi) == t.charAt(ti)) { pi++; ti++; }
            else if (pi < p.length() && p.charAt(pi) == '*') { star = pi++; mark = ti; }
            else if (star >= 0) { pi = star + 1; ti = ++mark; }
            else return false;
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }
}
