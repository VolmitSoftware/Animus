package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Turns a staged import preview into one the converter before schema version 2 wrote: its NPC files are schemaVersion 1
 * and carry the review flag of the accept step that version 2 removed. The manifest hashes follow.
 */
public final class OlderPreviews {
    private static final List<String> REVIEWED = List.of("animus:combat", "animus:navigation", "animus:shop", "animus:riding", "animus:schedule", "animus:builder");

    private OlderPreviews() {}

    public static void age(Path preview) throws Exception {
        Path report = preview.resolve("report.json");
        JsonObject summary = JsonDocuments.object(Files.readString(report));
        JsonObject documents = summary.getAsJsonObject("documents");
        for (Map.Entry<String, JsonElement> entry : documents.entrySet()) {
            if (!entry.getKey().startsWith("npcs/")) continue;
            Path file = preview.resolve(entry.getKey());
            JsonObject npc = JsonDocuments.object(Files.readString(file));
            npc.addProperty("schemaVersion", 1);
            if (npc.get("modules") instanceof JsonObject modules)
                for (String module : REVIEWED) if (modules.get(module) instanceof JsonObject section) section.addProperty("reviewAccepted", false);
            String text = JsonDocuments.write(npc);
            Files.writeString(file, text);
            entry.setValue(new com.google.gson.JsonPrimitive(JsonDocuments.hash(text)));
        }
        Files.writeString(report, JsonDocuments.write(summary));
    }

    /** How the refusal of every apply path starts. */
    public static String refusal(String importId) { return "Import " + importId.substring(0, 12) + " was previewed by an older Animus and is not applied: npcs/"; }
}
