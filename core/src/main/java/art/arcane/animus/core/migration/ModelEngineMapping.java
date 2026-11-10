package art.arcane.animus.core.migration;

import art.arcane.animus.core.integration.ModelEngineModule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Model Engine's Citizens trait, {@code meg_model}, as an {@code animus:modelengine} module. The trait keeps the modeled
 * entity as JSON text in {@code model_data}: Model Engine 4 writes a {@code version} and a {@code models} list of
 * {@code blueprint}, {@code render_scale} and the rest; Model Engine 3 data, which 4 still loads, keys {@code models} by
 * model id with each model's default state names. Animus shows one model, so only the first is kept.
 */
final class ModelEngineMapping {
    static final String TRAIT = "meg_model";
    private static final String PATH = "/traits/" + TRAIT;
    private static final List<String> ID_KEYS = List.of("blueprint", "model_id", "modelId", "model", "id");
    private static final List<String> VISIBLE_KEYS = List.of("base_visible", "isBaseEntityVisible");
    private static final List<String> SCALE_KEYS = List.of("render_scale", "scale");
    private static final int DEPTH = 4;

    record Result(Optional<JsonObject> module, Map<String, CitizensImporter.Note> notes, List<String> warnings) {}

    private ModelEngineMapping() {}

    static Result map(JsonElement trait) {
        Optional<JsonElement> models = find(trait, "models", DEPTH);
        List<String> ids = new ArrayList<>();
        JsonObject first = null;
        if (models.isPresent() && models.get().isJsonArray()) {
            for (JsonElement model : models.get().getAsJsonArray()) {
                Optional<String> id = model.isJsonObject() ? id(model.getAsJsonObject()) : Optional.empty();
                if (id.isEmpty()) continue;
                ids.add(id.get());
                if (first == null) first = model.getAsJsonObject();
            }
        } else if (models.isPresent() && models.get().isJsonObject()) {
            for (Map.Entry<String, JsonElement> model : models.get().getAsJsonObject().entrySet()) {
                ids.add(model.getKey());
                if (first == null) first = model.getValue().isJsonObject() ? model.getValue().getAsJsonObject() : new JsonObject();
            }
        } else if (trait.isJsonObject()) {
            id(trait.getAsJsonObject()).ifPresent(ids::add);
            first = trait.getAsJsonObject();
        }
        if (ids.isEmpty()) return review("Model Engine data names no model this converter can read; kept in citizens:review");
        String model = ids.getFirst();
        if (!model.matches(ModelEngineModule.MODEL_PATTERN)) return review("Model Engine model '" + model + "' is not a valid Animus model id; kept in citizens:review");
        double scale = scale(first).filter((Double value) -> value >= ModelEngineModule.MINIMUM_SCALE && value <= ModelEngineModule.MAXIMUM_SCALE).orElse(1.0);
        boolean visible = false;
        for (String key : VISIBLE_KEYS) {
            Optional<JsonElement> flag = find(trait, key, DEPTH);
            if (flag.isPresent() && flag.get().isJsonPrimitive() && flag.get().getAsJsonPrimitive().isBoolean()) { visible = flag.get().getAsBoolean(); break; }
        }
        ModelEngineModule module = new ModelEngineModule(model, scale, visible, true, Optional.empty(), defaultStates(first));
        List<String> warnings = ids.size() > 1
                ? List.of("Model Engine showed " + ids.size() + " models (" + String.join(", ", ids) + "); Animus shows the first, " + model) : List.of();
        return new Result(Optional.of(module.json()), Map.of(PATH, new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "")), warnings);
    }

    private static Result review(String warning) {
        return new Result(Optional.empty(), Map.of(PATH, new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, warning)), List.of(warning));
    }

    /** Model Engine 3's per-model {@code defaultStates}: the idle, walk and death animations it renamed. */
    private static Map<ModelEngineModule.State, String> defaultStates(JsonObject model) {
        Map<ModelEngineModule.State, String> states = new EnumMap<>(ModelEngineModule.State.class);
        JsonElement defaults = model.get("defaultStates");
        if (defaults == null || !defaults.isJsonObject()) return states;
        for (ModelEngineModule.State state : List.of(ModelEngineModule.State.IDLE, ModelEngineModule.State.WALK, ModelEngineModule.State.DEATH)) {
            JsonElement property = defaults.getAsJsonObject().get(state.name());
            if (property == null || !property.isJsonObject()) continue;
            JsonElement name = property.getAsJsonObject().get("stateId");
            if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString() && !name.getAsString().equals(state.fallback())) states.put(state, name.getAsString());
        }
        return states;
    }

    private static Optional<String> id(JsonObject model) {
        for (String key : ID_KEYS) {
            JsonElement value = model.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank()) return Optional.of(value.getAsString());
        }
        return Optional.empty();
    }

    private static Optional<Double> scale(JsonObject model) {
        for (String key : SCALE_KEYS) {
            JsonElement value = model.get(key);
            if (value != null && value.isJsonObject()) value = value.getAsJsonObject().get("x");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return Optional.of(value.getAsDouble());
        }
        return Optional.empty();
    }

    /** The first value under {@code key}, looking inside objects, arrays and strings that hold JSON. */
    private static Optional<JsonElement> find(JsonElement element, String key, int depth) {
        if (depth < 0) return Optional.empty();
        JsonElement value = unwrap(element);
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has(key)) return Optional.of(unwrap(object.get(key)));
            for (Map.Entry<String, JsonElement> child : object.entrySet()) {
                Optional<JsonElement> found = find(child.getValue(), key, depth - 1);
                if (found.isPresent()) return found;
            }
        } else if (value.isJsonArray()) {
            for (JsonElement child : (JsonArray) value) {
                Optional<JsonElement> found = find(child, key, depth - 1);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    private static JsonElement unwrap(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return element;
        String text = element.getAsString().trim();
        if (!text.startsWith("{") && !text.startsWith("[")) return element;
        try { return JsonParser.parseString(text); }
        catch (JsonParseException notJson) { return element; }
    }
}
