package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
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
    private static final String IDENTIFIER = "[a-z0-9][a-z0-9_-]{0,63}";

    public static final class Invalid extends RuntimeException {
        private final String field;
        Invalid(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }
    private TriggerCompiler() {}

    public static List<TriggerBinding> compile(JsonElement raw, Set<String> routines) {
        if (raw == null || !raw.isJsonArray()) throw new Invalid("$.triggers", "expected array");
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > MAX_TRIGGERS) throw new Invalid("$.triggers", "at most " + MAX_TRIGGERS + " triggers");
        List<TriggerBinding> result = new ArrayList<>(array.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            TriggerBinding binding = binding(array.get(index), "$.triggers[" + index + "]", routines);
            if (!ids.add(binding.id())) throw new Invalid("$.triggers[" + index + "].id", "duplicate trigger id");
            result.add(binding);
        }
        return List.copyOf(result);
    }

    private static TriggerBinding binding(JsonElement element, String path, Set<String> routines) {
        if (element == null || !element.isJsonObject()) throw new Invalid(path, "expected object");
        JsonObject object = element.getAsJsonObject();
        String id = identifier(object, "id", path);
        String event = text(object, "event", path);
        TriggerKind kind = TriggerKind.find(event).orElseThrow(() -> new Invalid(path + ".event",
                "expected one of: " + Suggestions.list(TriggerKind.names()) + Suggestions.didYouMean(event, TriggerKind.names())));
        fields(object, path, kind);
        Optional<String> routine = Optional.empty();
        if (object.has("routine")) {
            String name = text(object, "routine", path);
            if (!routines.contains(name)) throw new Invalid(path + ".routine", "unknown routine");
            routine = Optional.of(name);
        }
        OptionalInt cooldown = object.has("cooldownTicks") ? OptionalInt.of(integer(object, "cooldownTicks", path, 0, MAX_COOLDOWN_TICKS)) : OptionalInt.empty();
        return new TriggerBinding(id, spec(kind, object, path), routine, cooldown);
    }

    private static TriggerBinding.Spec spec(TriggerKind kind, JsonObject object, String path) {
        return switch (kind) {
            case INTERACTION -> new TriggerBinding.Interaction(object.has("action")
                    ? named(TriggerBinding.InteractionFilter.class, text(object, "action", path), path + ".action", "interact, attack or any")
                    : TriggerBinding.InteractionFilter.INTERACT);
            case PROXIMITY -> {
                if (!object.has("phase")) throw new Invalid(path + ".phase", "expected enter or leave");
                TriggerBinding.Phase phase = named(TriggerBinding.Phase.class, text(object, "phase", path), path + ".phase", "enter or leave");
                yield new TriggerBinding.Proximity(phase, radius(object, path));
            }
            case SPAWN, DESPAWN, DAMAGE, DEATH, BUILD_FINISHED, BUILD_PAUSED -> new TriggerBinding.Lifecycle(kind);
            case WAYPOINT -> new TriggerBinding.Waypoint(object.has("waypoint") ? OptionalInt.of(integer(object, "waypoint", path, -1, MAX_WAYPOINT)) : OptionalInt.empty());
            case TIMER -> new TriggerBinding.Timer(integer(object, "intervalTicks", path, 1, MAX_INTERVAL_TICKS));
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
