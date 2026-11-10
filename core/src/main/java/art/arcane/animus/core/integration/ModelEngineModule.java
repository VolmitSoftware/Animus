package art.arcane.animus.core.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code modules["animus:modelengine"]}: the Model Engine model an NPC shows in place of its body. Only the settings are
 * read here; the Bukkit integration draws the model when Model Engine is installed, and the plain body shows without it.
 *
 * @param states animations that replace the default name of a state; a state left out plays its default
 */
public record ModelEngineModule(String model, double scale, boolean showBody, boolean hitbox, Optional<String> nametagBone, Map<State, String> states) {
    public static final String MODULE = "animus:modelengine";
    public static final double MINIMUM_SCALE = 0.0625, MAXIMUM_SCALE = 16;
    public static final String MODEL_PATTERN = "[A-Za-z0-9_./-]{1,64}";
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "model", "scale", "showBody", "hitbox", "nametagBone", "states");

    /** What the NPC is doing; each plays one animation of the model. */
    public enum State {
        IDLE("idle"), WALK("walk"), ATTACK("attack"), HURT("hurt"), DEATH("death"), INTERACT(null);
        private final String fallback;
        State(String fallback) { this.fallback = fallback; }
        public String key() { return name().toLowerCase(Locale.ROOT); }
        /** The animation played when the module names none; null when the state plays nothing by default. */
        public String fallback() { return fallback; }
        static Optional<State> of(String key) {
            for (State state : values()) if (state.key().equals(key)) return Optional.of(state);
            return Optional.empty();
        }
    }

    public ModelEngineModule {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(nametagBone, "nametagBone");
        if (!model.matches(MODEL_PATTERN)) throw new IllegalArgumentException(FIELD + ".model: expected 1-64 letters, digits, _, -, . or /");
        if (!Double.isFinite(scale) || scale < MINIMUM_SCALE || scale > MAXIMUM_SCALE) throw new IllegalArgumentException(FIELD + ".scale: expected " + MINIMUM_SCALE + ".." + (int) MAXIMUM_SCALE);
        nametagBone.ifPresent((String bone) -> { if (!animationName(bone)) throw new IllegalArgumentException(FIELD + ".nametagBone: expected a bone name"); });
        EnumMap<State, String> copy = new EnumMap<>(State.class);
        for (Map.Entry<State, String> state : states.entrySet()) {
            if (!animationName(state.getValue())) throw new IllegalArgumentException(FIELD + ".states." + state.getKey().key() + ": expected an animation name");
            copy.put(state.getKey(), state.getValue());
        }
        states = Map.copyOf(copy);
    }

    /** The model at its own size with every default. */
    public static ModelEngineModule of(String model) { return new ModelEngineModule(model, 1, false, true, Optional.empty(), Map.of()); }

    /** The animation the model plays for {@code state}, or null when it plays none. */
    public String animation(State state) { return states.getOrDefault(state, state.fallback()); }

    /** The module as a definition stores it; defaults are left out. */
    public JsonObject json() {
        JsonObject module = new JsonObject();
        module.addProperty("model", model);
        if (scale != 1) module.addProperty("scale", scale);
        if (showBody) module.addProperty("showBody", true);
        if (!hitbox) module.addProperty("hitbox", false);
        nametagBone.ifPresent((String bone) -> module.addProperty("nametagBone", bone));
        if (!states.isEmpty()) {
            JsonObject named = new JsonObject();
            for (State state : State.values()) if (states.containsKey(state)) named.addProperty(state.key(), states.get(state));
            module.add("states", named);
        }
        return module;
    }

    /** @throws IllegalArgumentException when the module is malformed, naming its exact field */
    public static Optional<ModelEngineModule> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        if (!flag(module, "enabled", true)) return Optional.empty();
        if (document.has("rig")) throw new IllegalArgumentException(FIELD + ": an NPC shows either a rig or a Model Engine model; remove one");
        if (!module.has("model")) throw new IllegalArgumentException(FIELD + ".model: required");
        String model = text(module, "model");
        double scale = 1;
        if (module.has("scale")) {
            JsonElement number = module.get("scale");
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(FIELD + ".scale: expected number");
            scale = number.getAsDouble();
        }
        Optional<String> bone = module.has("nametagBone") ? Optional.of(text(module, "nametagBone")) : Optional.empty();
        EnumMap<State, String> states = new EnumMap<>(State.class);
        if (module.has("states")) {
            JsonElement named = module.get("states");
            if (!named.isJsonObject()) throw new IllegalArgumentException(FIELD + ".states: expected object");
            for (Map.Entry<String, JsonElement> entry : named.getAsJsonObject().entrySet()) {
                State state = State.of(entry.getKey()).orElseThrow(() -> new IllegalArgumentException(FIELD + ".states." + entry.getKey()
                        + ": unknown state; expected idle, walk, attack, hurt, death or interact"));
                JsonElement animation = entry.getValue();
                if (!animation.isJsonPrimitive() || !animation.getAsJsonPrimitive().isString() || !animationName(animation.getAsString()))
                    throw new IllegalArgumentException(FIELD + ".states." + state.key() + ": expected an animation name");
                states.put(state, animation.getAsString());
            }
        }
        return Optional.of(new ModelEngineModule(model, scale, flag(module, "showBody", false), flag(module, "hitbox", true), bone, states));
    }

    /** 1-64 characters without control characters, as Blockbench names animations and bones. */
    static boolean animationName(String name) {
        return !name.isBlank() && name.length() <= 64 && name.chars().noneMatch(Character::isISOControl);
    }
    private static String text(JsonObject module, String key) {
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(FIELD + "." + key + ": expected string");
        return value.getAsString();
    }
    private static boolean flag(JsonObject module, String key, boolean fallback) {
        if (!module.has(key)) return fallback;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }
}
