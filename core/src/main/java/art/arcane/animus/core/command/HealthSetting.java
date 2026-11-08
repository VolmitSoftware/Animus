package art.arcane.animus.core.command;

import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /animus npc health}: the few {@code animus:health} settings worth a command; the editor and toolkit reach the rest. */
final class HealthSetting {
    private enum Kind { FLAG, NUMBER, TICKS, AFTER_DEATH }
    private record Field(String section, String key, Kind kind) {}
    private static final Map<String, Field> FIELDS = Map.of(
            "killable", new Field("", "killable", Kind.FLAG),
            "maximum", new Field("", "maximumHealth", Kind.NUMBER),
            "regenerate", new Field("", "regenerate", Kind.FLAG),
            "show-health", new Field("", "showHealth", Kind.FLAG),
            "after-death", new Field("respawn", "afterDeath", Kind.AFTER_DEATH),
            "delay", new Field("respawn", "delayTicks", Kind.TICKS));
    static final List<String> NAMES = FIELDS.keySet().stream().sorted().toList();

    private HealthSetting() {}

    /** The NPC's health module as stored, or one setting of it. */
    static String show(NpcRepository repository, String id, String setting) throws IOException {
        NpcDefinition definition = repository.find(id).orElseThrow(() -> new IOException("Unknown NPC: " + id));
        JsonObject modules = definition.json().getAsJsonObject("modules");
        JsonElement health = modules == null ? null : modules.get(HealthSettings.MODULE);
        if (health == null || !health.isJsonObject()) return id + ": not killable (no " + HealthSettings.MODULE + " module)";
        if (setting.isEmpty()) return id + ": " + health;
        Field field = field(setting);
        JsonObject holder = field.section().isEmpty() ? health.getAsJsonObject() : child(health.getAsJsonObject(), field.section());
        JsonElement value = holder == null ? null : holder.get(field.key());
        return id + ": " + setting + " = " + (value == null ? "default" : value.toString());
    }

    /** Sets or resets one setting in the NPC's own file as a new revision; the definition validates it. */
    static String set(NpcRepository repository, String id, String setting, String value) throws IOException {
        Field field = field(setting);
        boolean reset = value.equalsIgnoreCase(NpcCommands.RESET);
        JsonPrimitive parsed = reset ? null : parse(field.kind(), value);
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        JsonObject health = modules.has(HealthSettings.MODULE) && modules.get(HealthSettings.MODULE).isJsonObject()
                ? modules.getAsJsonObject(HealthSettings.MODULE) : new JsonObject();
        JsonObject holder = health;
        if (!field.section().isEmpty()) {
            holder = child(health, field.section());
            if (holder == null) holder = new JsonObject();
        }
        if (reset) holder.remove(field.key());
        else holder.add(field.key(), parsed);
        if (!field.section().isEmpty()) {
            if (holder.isEmpty()) health.remove(field.section());
            else health.add(field.section(), holder);
        }
        if (health.isEmpty()) modules.remove(HealthSettings.MODULE);
        else modules.add(HealthSettings.MODULE, health);
        if (modules.isEmpty()) document.remove("modules");
        else document.add("modules", modules);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + ": " + setting + (reset ? " reset" : " = " + parsed) + "; tx " + transaction;
    }

    private static Field field(String setting) {
        Field field = FIELDS.get(setting.toLowerCase(Locale.ROOT));
        if (field == null) throw new IllegalArgumentException("setting must be one of " + String.join(", ", NAMES));
        return field;
    }

    private static JsonPrimitive parse(Kind kind, String value) {
        String text = value.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case FLAG -> switch (text) {
                case "true", "on", "yes" -> new JsonPrimitive(true);
                case "false", "off", "no" -> new JsonPrimitive(false);
                default -> throw new IllegalArgumentException("value must be on or off");
            };
            case NUMBER -> {
                try { yield new JsonPrimitive(Double.parseDouble(value)); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("value must be a number"); }
            }
            case TICKS -> {
                try { yield new JsonPrimitive(Integer.parseInt(value)); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("value must be a whole number of ticks"); }
            }
            case AFTER_DEATH -> {
                if (!text.equals("respawn") && !text.equals("permadeath")) throw new IllegalArgumentException("value must be respawn or permadeath");
                yield new JsonPrimitive(text);
            }
        };
    }

    private static JsonObject child(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
