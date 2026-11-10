package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.core.npc.NpcDisguise;
import com.google.gson.JsonObject;
import java.util.Optional;

/**
 * How a Mythic mob's disguise and Model Engine model reach a new NPC. The LibsDisguises and Model Engine integrations
 * supply these; each writes its own definition fields into {@code fields} and may return a line for the reply.
 */
public interface LookHooks {
    /** Applies a Mythic {@code Disguise} value, such as {@code player:Notch}. */
    Optional<String> disguise(JsonObject fields, String spec);

    /** Applies the Model Engine model a {@code model{mid=...}} skill shows. */
    Optional<String> model(JsonObject fields, String model);

    /** The Model Engine module that shows a model in place of the body. */
    String MODEL_MODULE = "animus:modelengine";

    /**
     * Writes the disguise into the definition's {@code disguise} field, which the LibsDisguises integration draws, and the
     * model into the {@code animus:modelengine} module, which the Model Engine integration draws.
     */
    LookHooks DEFAULT = new LookHooks() {
        @Override public Optional<String> disguise(JsonObject fields, String spec) {
            String text = disguiseText(spec);
            fields.addProperty(NpcDisguise.FIELD, text);
            return Optional.of(NpcDisguise.available() ? "wears disguise " + text : "wears disguise " + text + " once LibsDisguises is installed");
        }
        @Override public Optional<String> model(JsonObject fields, String model) {
            JsonObject modules = fields.has("modules") && fields.get("modules").isJsonObject() ? fields.getAsJsonObject("modules") : new JsonObject();
            JsonObject module = new JsonObject();
            module.addProperty("model", model);
            modules.add(MODEL_MODULE, module);
            fields.add("modules", modules);
            return Optional.of("shows Model Engine model " + model);
        }
    };

    /**
     * A Mythic {@code Disguise} value as LibsDisguises reads it: Mythic's {@code player:Notch} becomes {@code player Notch};
     * any other value, such as {@code zombie setBaby true}, is already LibsDisguises text.
     */
    static String disguiseText(String spec) {
        String text = spec.strip();
        int colon = text.indexOf(':');
        return colon > 0 && text.indexOf(' ') < 0 && text.substring(0, colon).equalsIgnoreCase("player") ? "player " + text.substring(colon + 1) : text;
    }

    /** No disguise or model support: the reply names what was left out. */
    LookHooks NONE = new LookHooks() {
        @Override public Optional<String> disguise(JsonObject fields, String spec) {
            return Optional.of("disguise " + spec + " not applied; LibsDisguises support is not available");
        }
        @Override public Optional<String> model(JsonObject fields, String model) {
            return Optional.of("Model Engine model " + model + " not applied; Model Engine support is not available");
        }
    };
}
