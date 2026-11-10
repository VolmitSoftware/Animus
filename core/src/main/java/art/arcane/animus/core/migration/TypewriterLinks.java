package art.arcane.animus.core.migration;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Relinks one Typewriter page from the Citizens extension to the Animus extension. A {@code reference_npc} entry of an
 * imported NPC becomes an {@code animus_npc} entry naming the Animus id, and an {@code on_npc_interact} event on such an
 * entry becomes {@code on_animus_npc_interact}. Entry ids stay, so speakers, triggers and other pages keep their links.
 */
public final class TypewriterLinks {
    public static final String REFERENCE = "reference_npc", INTERACT = "on_npc_interact";
    public static final String ANIMUS_REFERENCE = "animus_npc", ANIMUS_INTERACT = "on_animus_npc_interact";

    public record Change(String path, String entry, String before, String after) {}
    /** The new text (the same instance when nothing changed), the changes, the relinked reference entry ids and notes. */
    public record Result(String text, List<Change> changes, Set<String> linkedEntries, List<String> notes) {
        public Result { changes = List.copyOf(changes); linkedEntries = Set.copyOf(linkedEntries); notes = List.copyOf(notes); }
    }

    private TypewriterLinks() {}

    /** One page on its own: interact events relink only when their NPC entry is on the same page. */
    public static Result rewrite(String path, String text, NpcLinks links) {
        return rewrite(path, text, links, rewrite(path, text, links, Set.of()).linkedEntries());
    }

    /** {@code linkedEntries} are the reference entry ids relinked on any page, found by a first pass with an empty set. */
    public static Result rewrite(String path, String text, NpcLinks links, Set<String> linkedEntries) {
        List<Change> changes = new ArrayList<>();
        Set<String> linked = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        JsonObject page;
        try { page = JsonParser.parseString(text).getAsJsonObject(); }
        catch (RuntimeException unreadable) { return new Result(text, List.of(), Set.of(), List.of(path + ": not a readable page; left as is")); }
        JsonArray entries = page.has("entries") && page.get("entries").isJsonArray() ? page.getAsJsonArray("entries") : new JsonArray();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String key = entry.has("blueprintId") ? "blueprintId" : "type";
            String blueprint = string(entry, key).orElse("");
            String id = string(entry, "id").orElse("?");
            if (blueprint.equals(REFERENCE)) {
                JsonElement npcId = entry.get("npcId");
                Optional<String> target = npcId != null && npcId.isJsonPrimitive() && npcId.getAsJsonPrimitive().isNumber()
                        ? links.citizens(npcId.getAsInt()) : Optional.empty();
                if (target.isEmpty()) { notes.add(path + " " + id + ": Citizens NPC " + (npcId == null ? "(none)" : npcId) + " was not imported; left as is"); continue; }
                entry.addProperty(key, ANIMUS_REFERENCE);
                entry.remove("npcId");
                entry.addProperty("npc", target.get());
                linked.add(id);
                changes.add(new Change(path, id, REFERENCE + " npcId " + npcId, ANIMUS_REFERENCE + " npc " + target.get()));
            } else if (blueprint.equals(INTERACT)) {
                String identifier = string(entry, "identifier").orElse("");
                if (!linkedEntries.contains(identifier)) { notes.add(path + " " + id + ": its NPC entry " + identifier + " was not relinked; left as is"); continue; }
                entry.addProperty(key, ANIMUS_INTERACT);
                changes.add(new Change(path, id, INTERACT, ANIMUS_INTERACT));
            }
        }
        String written = changes.isEmpty() ? text : new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(page);
        return new Result(written, changes, linked, notes);
    }

    private static Optional<String> string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }
}
