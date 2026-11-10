package art.arcane.animus.core.command;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** {@code /animus npc model}: the Model Engine model an NPC shows, one setting at a time. */
final class ModelSetting {
    private enum Kind { TEXT, NUMBER, FLAG, STATE }
    private record Field(String key, Kind kind) {}
    private static final Map<String, Field> FIELDS = fields();
    static final List<String> NAMES = List.copyOf(FIELDS.keySet());
    static final String NOT_DRAWN = "Model Engine models are not drawn on Folia, so the NPC shows its own body on this server";

    private ModelSetting() {}

    private static Map<String, Field> fields() {
        Map<String, Field> fields = new TreeMap<>();
        fields.put("model", new Field("model", Kind.TEXT));
        fields.put("scale", new Field("scale", Kind.NUMBER));
        fields.put("show-body", new Field("showBody", Kind.FLAG));
        fields.put("hitbox", new Field("hitbox", Kind.FLAG));
        fields.put("nametag-bone", new Field("nametagBone", Kind.TEXT));
        for (ModelEngineModule.State state : ModelEngineModule.State.values()) fields.put(state.key(), new Field(state.key(), Kind.STATE));
        return fields;
    }

    static String show(NpcRepository repository, String id) throws IOException {
        NpcDefinition definition = repository.find(id).orElseThrow(() -> new IOException(CommandGroup.unknownNpc(id)));
        JsonObject modules = definition.json().getAsJsonObject("modules");
        JsonElement module = modules == null ? null : modules.get(ModelEngineModule.MODULE);
        return module == null ? id + ": no Model Engine model" : id + ": " + module;
    }

    /** Sets or resets one setting as a new revision; resetting {@code model} removes the whole module. */
    static String set(NpcRepository repository, String id, String setting, String value) throws IOException {
        Field field = FIELDS.get(setting.toLowerCase(Locale.ROOT));
        if (field == null) throw new IllegalArgumentException("setting must be one of " + String.join(", ", NAMES));
        boolean reset = value.equalsIgnoreCase(NpcCommands.RESET);
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        JsonObject module = modules.has(ModelEngineModule.MODULE) && modules.get(ModelEngineModule.MODULE).isJsonObject()
                ? modules.getAsJsonObject(ModelEngineModule.MODULE) : null;
        String answer;
        if (field.key().equals("model") && reset) {
            modules.remove(ModelEngineModule.MODULE);
            answer = id + ": model removed";
        } else {
            if (module == null && !field.key().equals("model")) throw new IllegalArgumentException(setting + " needs a model first: /animus npc model " + id + " model <id>");
            if (module == null) module = new JsonObject();
            JsonObject holder = module;
            if (field.kind() == Kind.STATE) {
                holder = module.has("states") && module.get("states").isJsonObject() ? module.getAsJsonObject("states") : new JsonObject();
                module.add("states", holder);
            }
            JsonPrimitive parsed = reset ? null : parse(field.kind(), value);
            if (reset) holder.remove(field.key());
            else holder.add(field.key(), parsed);
            if (holder != module && holder.isEmpty()) module.remove("states");
            modules.add(ModelEngineModule.MODULE, module);
            answer = id + ": " + setting.toLowerCase(Locale.ROOT) + (reset ? " reset" : " = " + (parsed.isString() ? parsed.getAsString() : parsed.toString()));
        }
        if (modules.isEmpty()) document.remove("modules");
        else document.add("modules", modules);
        ModelEngineModule.parse(document);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return answer + "; tx " + transaction;
    }

    /** The command's reply, with a note under it on a server that draws no models. */
    static List<String> reply(String line, boolean drawn) { return drawn ? List.of(line) : List.of(line, NOT_DRAWN); }

    /** Values offered for a setting by completion. */
    static List<String> values(String setting) {
        Field field = FIELDS.get(setting.toLowerCase(Locale.ROOT));
        if (field == null) return List.of(NpcCommands.RESET);
        return switch (field.kind()) {
            case FLAG -> List.of("on", "off", NpcCommands.RESET);
            case NUMBER -> List.of("0.5", "1", "2", NpcCommands.RESET);
            case TEXT, STATE -> List.of(NpcCommands.RESET);
        };
    }

    private static JsonPrimitive parse(Kind kind, String value) {
        return switch (kind) {
            case TEXT, STATE -> new JsonPrimitive(value);
            case NUMBER -> {
                try { yield new JsonPrimitive(Double.parseDouble(value)); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("value must be a number"); }
            }
            case FLAG -> switch (value.toLowerCase(Locale.ROOT)) {
                case "true", "on", "yes" -> new JsonPrimitive(true);
                case "false", "off", "no" -> new JsonPrimitive(false);
                default -> throw new IllegalArgumentException("value must be on or off");
            };
        };
    }
}
