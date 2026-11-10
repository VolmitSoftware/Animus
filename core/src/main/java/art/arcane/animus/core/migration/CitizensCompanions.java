package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Files saved beside a Citizens save that belong to the same NPCs: Citizens' own {@code shops.yml}, the CitizensCMD
 * add-on's {@code data/saves.yml} and the CommandNPC add-on's {@code commands.yml}. Each is looked for next to the save,
 * as {@code plugins/Citizens/saves.yml} has them in {@code plugins/}, or in the folder of the save when it was copied
 * to {@code imports/}.
 */
final class CitizensCompanions {
    static final CitizensCompanions NONE = new CitizensCompanions(new TreeMap<>());
    /** Staged copy name, then where to look relative to the save's folder. */
    private static final Map<String, List<String>> PLACES = new LinkedHashMap<>();
    static {
        PLACES.put("shops.yml", List.of("shops.yml"));
        PLACES.put("citizenscmd.yml", List.of("citizenscmd/saves.yml", "../CitizensCMD/data/saves.yml"));
        PLACES.put("commandnpc.yml", List.of("commandnpc/commands.yml", "../CommandNPC/commands.yml"));
    }

    private final SortedMap<String, String> texts;
    private final Map<String, JsonObject> parsed = new HashMap<>();

    private CitizensCompanions(SortedMap<String, String> texts) { this.texts = texts; }

    /** The companions found beside the save; none when the save has no folder. */
    static CitizensCompanions find(Path save) throws IOException {
        Path folder = save.toAbsolutePath().getParent();
        SortedMap<String, String> texts = new TreeMap<>();
        if (folder == null) return NONE;
        for (Map.Entry<String, List<String>> place : PLACES.entrySet())
            for (String relative : place.getValue()) {
                Path candidate = folder.resolve(relative).normalize();
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                texts.put(place.getKey(), JsonDocuments.read(candidate));
                break;
            }
        return new CitizensCompanions(texts);
    }

    /** Empty when nothing was found, so a save without companions keeps its import id. */
    String digest() {
        if (texts.isEmpty()) return "";
        StringBuilder digest = new StringBuilder();
        for (Map.Entry<String, String> text : texts.entrySet()) digest.append('\n').append(text.getKey()).append('=').append(JsonDocuments.hash(text.getValue()));
        return digest.toString();
    }

    /** Copies the companions into the staged preview under {@code companions/}. */
    void stage(Path staged) throws IOException {
        for (Map.Entry<String, String> text : texts.entrySet()) DocumentStore.atomic(staged.resolve("companions/" + text.getKey()), text.getValue());
    }

    List<String> found() { return List.copyOf(texts.keySet()); }

    /** Citizens' shops file, or an empty mapping. */
    JsonObject shops() throws IOException { return yaml("shops.yml"); }

    /** What the add-ons saved for the NPC with this Citizens id. */
    CitizensCommands.Addons addons(String id) throws IOException {
        JsonObject citizensCmd = CitizensBehaviors.object(yaml("citizenscmd.yml").get("npc-data"));
        Optional<JsonObject> section = Optional.ofNullable(citizensCmd == null ? null : CitizensBehaviors.object(citizensCmd.get("npc-" + id)));
        JsonObject commandNpc = CitizensBehaviors.object(yaml("commandnpc.yml").get("NPCS"));
        JsonObject npc = commandNpc == null ? null : CitizensBehaviors.object(commandNpc.get(id));
        List<String> lines = new ArrayList<>();
        if (npc != null && npc.get("Commands") != null && npc.get("Commands").isJsonArray())
            for (JsonElement line : npc.getAsJsonArray("Commands")) if (line.isJsonPrimitive()) lines.add(line.getAsString());
        return new CitizensCommands.Addons(section, lines);
    }

    private JsonObject yaml(String name) throws IOException {
        if (!texts.containsKey(name)) return new JsonObject();
        JsonObject cached = parsed.get(name);
        if (cached != null) return cached;
        JsonObject root;
        try { root = CitizensImporter.parseYaml(texts.get(name)); }
        catch (IOException invalid) { throw new IOException(name + ": " + invalid.getMessage(), invalid); }
        parsed.put(name, root);
        return root;
    }
}
