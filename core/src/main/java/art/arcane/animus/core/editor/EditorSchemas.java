package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.schema.BehaviorSchemaGenerator;
import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /v1/schemas}: the generated NPC, behavior, pack and completion documents for the live capability registry,
 * and the hand-written shop, chest GUI, rig, animation and Gloss schemas bundled from the repository's {@code schema/}.
 */
public final class EditorSchemas {
    /** Schema name to bundled resource. */
    public static final Map<String, String> BUNDLED = bundled();
    private static final String RESOURCES = "/animus/schema/";
    private List<Capabilities.Availability> cachedFor;
    private JsonObject cached;

    private static Map<String, String> bundled() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("shop", "shop-v1.schema.json");
        names.put("inventory", "gui-inventory-v1.schema.json");
        names.put("rig", "rig-v1.schema.json");
        names.put("animation", "animation-v1.schema.json");
        names.put("menu", "gloss/gloss.schema.json");
        names.put("hologram", "gloss/gloss-holograms.schema.json");
        names.put("presets", "gloss/gloss-presets.schema.json");
        return Map.copyOf(names);
    }

    /** The response body. Rebuilt only when the capability list changes. */
    public synchronized JsonObject response(List<Capabilities.Availability> capabilities) throws IOException {
        if (cached != null && capabilities.equals(cachedFor)) return cached;
        JsonObject schemas = new JsonObject();
        schemas.add("npc", NpcSchemaGenerator.schema());
        schemas.add("behavior", BehaviorSchemaGenerator.behaviorSchema(capabilities));
        schemas.add("pack", BehaviorSchemaGenerator.packSchema());
        schemas.add("completions", BehaviorSchemaGenerator.completions(capabilities));
        for (Map.Entry<String, String> entry : BUNDLED.entrySet()) {
            try (InputStream input = EditorSchemas.class.getResourceAsStream(RESOURCES + entry.getValue())) {
                if (input == null) continue;
                schemas.add(entry.getKey(), JsonDocuments.object(new String(input.readNBytes(JsonDocuments.MAX_BYTES), StandardCharsets.UTF_8)));
            }
        }
        JsonObject body = new JsonObject();
        body.add("schemas", schemas);
        cachedFor = List.copyOf(capabilities);
        cached = body;
        return body;
    }
}
