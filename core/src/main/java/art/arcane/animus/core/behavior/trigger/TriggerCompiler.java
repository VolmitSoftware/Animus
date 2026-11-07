package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorExpression;
import art.arcane.animus.core.behavior.schema.Suggestions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Compile-time validation of a document's {@code triggers} array. Reports the exact invalid field. */
public final class TriggerCompiler {
    public static final int MAX_TRIGGERS = 32, MAX_COOLDOWN_TICKS = 72000, MAX_INTERVAL_TICKS = 72000, MAX_WAYPOINT = 4095;
    public static final double MAX_RADIUS = 64;
    /** Server behavior bounds: timers fire at most once a second; commands are lowercase words with a few aliases. */
    public static final int MIN_SERVER_INTERVAL_TICKS = 20, MAX_ALIASES = 8, MAX_PATTERN = 256, MAX_COMMAND_TEXT = 256, MAX_WORLD_NAME = 64;
    public static final String COMMAND_NAME = "[a-z0-9_-]{1,32}", PERMISSION = "[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*";
    private static final String IDENTIFIER = "[a-z0-9][a-z0-9_-]{0,63}";
    /** The {@code action} names of an interaction trigger. */
    public static final List<String> ACTIONS = java.util.Arrays.stream(TriggerBinding.InteractionFilter.values())
            .map((TriggerBinding.InteractionFilter filter) -> filter.name().toLowerCase(Locale.ROOT)).toList();

    public static final class Invalid extends RuntimeException {
        private final String field;
        Invalid(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }
    private TriggerCompiler() {}

    public static List<TriggerBinding> compile(JsonElement raw, Set<String> routines) { return compile(raw, routines, false); }

    /** {@code server} compiles the triggers of a server behavior: server events only, with the server timer bounds. */
    public static List<TriggerBinding> compile(JsonElement raw, Set<String> routines, boolean server) {
        if (raw == null || !raw.isJsonArray()) throw new Invalid("$.triggers", "expected array");
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > MAX_TRIGGERS) throw new Invalid("$.triggers", "at most " + MAX_TRIGGERS + " triggers");
        List<TriggerBinding> result = new ArrayList<>(array.size());
        Set<String> ids = new HashSet<>(), labels = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "$.triggers[" + index + "]";
            TriggerBinding binding = binding(array.get(index), path, routines, server);
            if (!ids.add(binding.id())) throw new Invalid(path + ".id", "duplicate trigger id");
            if (binding.spec() instanceof TriggerBinding.Command command) {
                if (!labels.add(command.name())) throw new Invalid(path + ".name", "another command trigger of this behavior already uses this name");
                for (int alias = 0; alias < command.aliases().size(); alias++)
                    if (!labels.add(command.aliases().get(alias))) throw new Invalid(path + ".aliases[" + alias + "]", "another command trigger of this behavior already uses this name");
            }
            result.add(binding);
        }
        return List.copyOf(result);
    }

    private static TriggerBinding binding(JsonElement element, String path, Set<String> routines, boolean server) {
        if (element == null || !element.isJsonObject()) throw new Invalid(path, "expected object");
        JsonObject object = element.getAsJsonObject();
        String id = identifier(object, "id", path);
        String event = text(object, "event", path);
        TriggerKind kind = TriggerKind.find(event).orElseThrow(() -> new Invalid(path + ".event",
                "expected one of: " + Suggestions.list(TriggerKind.names()) + Suggestions.didYouMean(event, TriggerKind.names())));
        if (server && !kind.server())
            throw new Invalid(path + ".event", "a server behavior has no NPC, so it cannot use " + event + "; expected one of: " + Suggestions.list(names(true)));
        if (!server && !kind.npc())
            throw new Invalid(path + ".event", event + " is a server event: only server behaviors (\"server\": true) use it");
        fields(object, path, kind);
        Optional<String> routine = Optional.empty();
        if (object.has("routine")) {
            String name = text(object, "routine", path);
            if (!routines.contains(name)) throw new Invalid(path + ".routine", "unknown routine");
            routine = Optional.of(name);
        }
        OptionalInt cooldown = object.has("cooldownTicks") ? OptionalInt.of(integer(object, "cooldownTicks", path, 0, MAX_COOLDOWN_TICKS)) : OptionalInt.empty();
        return new TriggerBinding(id, spec(kind, object, path, server), routine, cooldown);
    }

    /** The events a server behavior, or an NPC behavior, may use. */
    public static List<String> names(boolean server) {
        List<String> names = new ArrayList<>();
        for (TriggerKind kind : TriggerKind.values()) if (server ? kind.server() : kind.npc()) names.add(kind.jsonName());
        return List.copyOf(names);
    }

    private static TriggerBinding.Spec spec(TriggerKind kind, JsonObject object, String path, boolean server) {
        return switch (kind) {
            case INTERACTION -> {
                TriggerBinding.InteractionFilter action = object.has("action")
                        ? named(TriggerBinding.InteractionFilter.class, text(object, "action", path), path + ".action", Suggestions.list(ACTIONS))
                        : TriggerBinding.InteractionFilter.INTERACT;
                boolean queue = false;
                if (object.has("whileRunning")) {
                    String mode = text(object, "whileRunning", path);
                    if (!mode.equals("drop") && !mode.equals("queue")) throw new Invalid(path + ".whileRunning", "expected drop or queue");
                    queue = mode.equals("queue");
                }
                yield new TriggerBinding.Interaction(action, object.has("consume") ? Optional.of(consume(object, path)) : Optional.empty(),
                        object.has("playerCooldownTicks") ? OptionalInt.of(integer(object, "playerCooldownTicks", path, 0, MAX_COOLDOWN_TICKS)) : OptionalInt.empty(), queue);
            }
            case PROXIMITY -> {
                if (!object.has("phase")) throw new Invalid(path + ".phase", "expected enter or leave");
                TriggerBinding.Phase phase = named(TriggerBinding.Phase.class, text(object, "phase", path), path + ".phase", "enter or leave");
                yield new TriggerBinding.Proximity(phase, radius(object, path));
            }
            case SPAWN, DESPAWN, DAMAGE, DEATH, BUILD_FINISHED, BUILD_PAUSED, PLAYER_QUIT, SERVER_START, PLAYER_DEATH, PLAYER_RESPAWN -> new TriggerBinding.Lifecycle(kind);
            case WAYPOINT -> new TriggerBinding.Waypoint(object.has("waypoint") ? OptionalInt.of(integer(object, "waypoint", path, -1, MAX_WAYPOINT)) : OptionalInt.empty());
            case TIMER -> new TriggerBinding.Timer(integer(object, "intervalTicks", path, server ? MIN_SERVER_INTERVAL_TICKS : 1, MAX_INTERVAL_TICKS));
            case PLAYER_JOIN -> new TriggerBinding.PlayerJoin(object.has("first") ? Optional.of(bool(object, "first", path)) : Optional.empty());
            case PLAYER_CHAT -> new TriggerBinding.PlayerChat(object.has("pattern") ? Optional.of(bounded(object, "pattern", path, MAX_PATTERN)) : Optional.empty());
            case COMMAND -> command(object, path);
            case PLAYER_WORLD_CHANGE -> new TriggerBinding.WorldChange(optional(object, "from", path, MAX_WORLD_NAME), optional(object, "to", path, MAX_WORLD_NAME));
            case PLAYER_AREA -> area(object, path);
            case PLAYER_INVENTORY_OPEN, PLAYER_INVENTORY_CLOSE, PLAYER_INVENTORY_CLICK, PLAYER_ITEM_DROP, PLAYER_ITEM_PICKUP, PLAYER_ITEM_CONSUME,
                 PLAYER_BLOCK_CLICK, PLAYER_BLOCK_PLACE, PLAYER_BLOCK_BREAK, PLAYER_DAMAGED, PLAYER_DAMAGE_ENTITY, PLAYER_KILL,
                 PROJECTILE_LAUNCH, PROJECTILE_HIT, ENTITY_SHOOT_BOW -> worldEvent(kind, object, path);
            case DIALOGUE_CHOICE -> new TriggerBinding.DialogueChoice(identifier(object, "dialogue", path),
                    object.has("choice") ? Optional.of(identifier(object, "choice", path)) : Optional.empty());
            case MODULE -> new TriggerBinding.ModuleEvent(key(text(object, "key", path), path + ".key"));
            case ANIMATION_FINISHED -> {
                if (!object.has("animation")) yield new TriggerBinding.AnimationFinished(Optional.empty());
                String animation = text(object, "animation", path);
                if (animation.length() > 256 || !animation.matches("[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}"))
                    throw new Invalid(path + ".animation", "expected an animation id such as town:golem/wave");
                yield new TriggerBinding.AnimationFinished(Optional.of(animation));
            }
        };
    }

    /** A click-time condition: it reads flags, facts and the click's context, and no variables, since no run exists yet. */
    private static BehaviorExpression consume(JsonObject object, String path) { return condition(object, "consume", path); }
    private static BehaviorExpression condition(JsonObject object, String key, String path) {
        String source = text(object, key, path);
        try { return BehaviorExpression.compile(source, Set.of(), path + "." + key); }
        catch (BehaviorExpression.Invalid invalid) { throw new Invalid(path + "." + key, invalid.getMessage()); }
    }

    /** Filter patterns: namespaced keys or bare names such as {@code stone}, lowercase types and actions, with {@code *} wildcards. */
    public static final String FILTER = "[a-z0-9_.*:/-]{1,128}";
    /** An event's filters and its cancel condition, checked as the event happens over flags, facts and the event's context. */
    private static TriggerBinding.WorldEvent worldEvent(TriggerKind kind, JsonObject object, String path) {
        java.util.TreeMap<String, String> filters = new java.util.TreeMap<>();
        for (String field : kind.fields()) {
            if (!TriggerKind.FILTERS.containsKey(field) || !object.has(field)) continue;
            String value = text(object, field, path);
            if (kind == TriggerKind.PLAYER_BLOCK_CLICK && field.equals("click") && !value.equals("left") && !value.equals("right")) throw new Invalid(path + ".click", "expected left or right");
            if (!value.matches(FILTER)) throw new Invalid(path + "." + field, "expected a lowercase name or namespaced key such as minecraft:stone, with * matching any text");
            filters.put(field, value);
        }
        return new TriggerBinding.WorldEvent(kind, filters, object.has("cancel") ? Optional.of(condition(object, "cancel", path)) : Optional.empty());
    }

    /** Coordinates stay within the world border; ellipsoid radii run from a tenth of a block to {@link #MAX_AREA_RADIUS}. */
    public static final double MAX_COORDINATE = 30_000_000, MAX_AREA_RADIUS = 100_000;
    private static TriggerBinding.Area area(JsonObject object, String path) {
        if (!object.has("phase")) throw new Invalid(path + ".phase", "expected enter or leave");
        TriggerBinding.Phase phase = named(TriggerBinding.Phase.class, text(object, "phase", path), path + ".phase", "enter or leave");
        String name = identifier(object, "area", path);
        String world = bounded(object, "world", path, MAX_WORLD_NAME);
        if (object.has("cuboid") == object.has("ellipsoid")) throw new Invalid(path + ".cuboid", "expected exactly one of cuboid or ellipsoid");
        TriggerBinding.Shape shape = object.has("cuboid") ? TriggerBinding.Shape.CUBOID : TriggerBinding.Shape.ELLIPSOID;
        String key = shape == TriggerBinding.Shape.CUBOID ? "cuboid" : "ellipsoid";
        double[] bounds = numbers(object.get(key), path + "." + key);
        if (shape == TriggerBinding.Shape.CUBOID) {
            for (int axis = 0; axis < 3; axis++) {
                double low = Math.min(bounds[axis], bounds[axis + 3]), high = Math.max(bounds[axis], bounds[axis + 3]);
                bounds[axis] = Math.floor(low); bounds[axis + 3] = Math.floor(high);
            }
        } else {
            for (int axis = 3; axis < 6; axis++) if (bounds[axis] < 0.1 || bounds[axis] > MAX_AREA_RADIUS)
                throw new Invalid(path + ".ellipsoid", "expected radii from 0.1 to " + (int) MAX_AREA_RADIUS + " blocks");
        }
        return new TriggerBinding.Area(phase, name, world, shape, bounds);
    }
    private static double[] numbers(JsonElement raw, String field) {
        String message = "expected six numbers: x1, y1, z1, x2, y2, z2 for a cuboid, or x, y, z and three radii for an ellipsoid";
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 6) throw new Invalid(field, message);
        double[] values = new double[6];
        for (int index = 0; index < 6; index++) {
            JsonElement item = raw.getAsJsonArray().get(index);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw new Invalid(field, message);
            double value = item.getAsDouble();
            if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) throw new Invalid(field, message);
            values[index] = value;
        }
        return values;
    }

    private static TriggerBinding.Command command(JsonObject object, String path) {
        String name = label(object.get("name"), path + ".name");
        List<String> aliases = new ArrayList<>();
        if (object.has("aliases")) {
            JsonElement raw = object.get("aliases");
            if (!raw.isJsonArray() || raw.getAsJsonArray().size() > MAX_ALIASES) throw new Invalid(path + ".aliases", "expected an array of at most " + MAX_ALIASES + " command names");
            JsonArray array = raw.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String alias = label(array.get(index), path + ".aliases[" + index + "]");
                if (alias.equals(name) || aliases.contains(alias)) throw new Invalid(path + ".aliases[" + index + "]", "duplicate command name");
                aliases.add(alias);
            }
        }
        Optional<String> permission = optional(object, "permission", path, 128);
        if (permission.isPresent() && !permission.get().matches(PERMISSION)) throw new Invalid(path + ".permission", "expected a permission node such as town.spawn");
        return new TriggerBinding.Command(name, aliases, permission, optional(object, "description", path, MAX_COMMAND_TEXT), optional(object, "usage", path, MAX_COMMAND_TEXT));
    }
    private static String label(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches(COMMAND_NAME))
            throw new Invalid(field, "expected a lowercase command name of 1..32 letters, digits, _ or -");
        return value.getAsString();
    }
    private static Optional<String> optional(JsonObject object, String key, String path, int maximum) {
        return object.has(key) ? Optional.of(bounded(object, key, path, maximum)) : Optional.empty();
    }
    private static String bounded(JsonObject object, String key, String path, int maximum) {
        String value = text(object, key, path);
        if (value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)) throw new Invalid(path + "." + key, "expected text of at most " + maximum + " characters without control characters");
        return value;
    }
    private static boolean bool(JsonObject object, String key, String path) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new Invalid(path + "." + key, "expected true or false");
        return value.getAsBoolean();
    }

    private static void fields(JsonObject object, String path, TriggerKind kind) {
        List<String> allowed = kind.keys();
        for (String key : object.keySet()) {
            if (!allowed.contains(key))
                throw new Invalid(path + "." + key, "unsupported field for a " + kind.jsonName() + " trigger; allowed: " + Suggestions.list(allowed) + Suggestions.didYouMean(key, allowed));
        }
    }
    private static String text(JsonObject object, String key, String path) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new Invalid(path + "." + key, "expected nonblank string");
        return value.getAsString();
    }
    private static String identifier(JsonObject object, String key, String path) {
        String value = text(object, key, path);
        if (!value.matches(IDENTIFIER)) throw new Invalid(path + "." + key, "expected a lowercase identifier within 64 characters");
        return value;
    }
    private static int integer(JsonObject object, String key, String path, int minimum, int maximum) {
        String message = "expected integer " + minimum + ".." + maximum;
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new Invalid(path + "." + key, message);
        try {
            int number = value.getAsBigDecimal().intValueExact();
            if (number < minimum || number > maximum) throw new Invalid(path + "." + key, message);
            return number;
        } catch (ArithmeticException | NumberFormatException invalid) { throw new Invalid(path + "." + key, message); }
    }
    private static double radius(JsonObject object, String path) {
        String message = "expected a radius above 0 and at most " + (int) MAX_RADIUS + " blocks";
        JsonElement value = object.get("radius");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new Invalid(path + ".radius", message);
        double radius;
        try { radius = value.getAsDouble(); }
        catch (NumberFormatException invalid) { throw new Invalid(path + ".radius", message); }
        if (!Double.isFinite(radius) || radius <= 0 || radius > MAX_RADIUS) throw new Invalid(path + ".radius", message);
        return radius;
    }
    private static <E extends Enum<E>> E named(Class<E> type, String value, String field, String expected) {
        for (E constant : type.getEnumConstants()) if (constant.name().toLowerCase(Locale.ROOT).equals(value)) return constant;
        throw new Invalid(field, "expected " + expected);
    }
    private static Capabilities.Key key(String value, String field) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2) throw new Invalid(field, "expected namespace:name");
        try { return new Capabilities.Key(parts[0], parts[1]); }
        catch (IllegalArgumentException invalid) { throw new Invalid(field, "expected namespace:name identifiers"); }
    }
}
