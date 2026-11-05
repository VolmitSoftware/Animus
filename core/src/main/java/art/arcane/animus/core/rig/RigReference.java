package art.arcane.animus.core.rig;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;

/** An NPC definition's {@code "rig": {"id": "namespace:path"}}: the rig its body shows. */
public final class RigReference {
    private RigReference() {}

    /** The rig id, or empty without a {@code rig} field. Throws {@link IllegalArgumentException} with the field path. */
    public static Optional<String> parse(JsonObject definition) {
        if (!definition.has("rig")) return Optional.empty();
        JsonElement value = definition.get("rig");
        if (!value.isJsonObject()) throw new IllegalArgumentException("$.rig: expected {\"id\": \"namespace:path\"}");
        JsonObject rig = value.getAsJsonObject();
        for (String key : rig.keySet()) if (!key.equals("id")) throw new IllegalArgumentException("$.rig." + key + ": unknown field");
        JsonElement id = rig.get("id");
        if (!RigFields.string(id) || id.getAsString().length() > 256 || !id.getAsString().matches(RigFields.DOCUMENT_ID))
            throw new IllegalArgumentException("$.rig.id: expected namespace:path of a rig under rigs/");
        return Optional.of(id.getAsString());
    }
}
