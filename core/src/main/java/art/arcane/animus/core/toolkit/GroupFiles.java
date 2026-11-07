package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.GroupDocuments;
import art.arcane.animus.core.npc.GroupMerge;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Trait group file work for the toolkit, on the file lane. Each change writes the group file and every member it
 * touches in one transaction, refused whole when the group file or any member would become invalid.
 */
final class GroupFiles {
    private final NpcRepository npcs;
    private final DocumentStore store;

    GroupFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** The ids of the stored NPCs that list the group. */
    static List<String> members(NpcRepository npcs, String group) {
        List<String> members = new ArrayList<>();
        for (NpcDefinition definition : npcs.stored()) if (definition.groups().contains(group)) members.add(definition.id());
        members.sort(String::compareTo);
        return members;
    }

    /** Saves {@code groups/<id>.json} with no values and, in the same transaction, assigns it to the listed NPCs. */
    String create(String id, List<String> ids) throws IOException {
        if (!id.matches(NpcPaths.SEGMENT)) throw new IOException("Group id " + id + " is not 1-64 lowercase letters, digits, _ or -");
        String path = path(id);
        if (store.read(path) != null) throw new IOException(path + " already exists");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", 1);
        document.addProperty("id", id);
        document.add("values", new JsonObject());
        String content = checked(id, document);
        List<String> assigned = new ArrayList<>();
        for (String npc : ids) if (!strings(npcs.draft(npc).document()).contains(id)) assigned.add(npc);
        String tx = commit(Map.of(id, Optional.of(content)), List.of(new DocumentStore.Change(path, "missing", content)), assigned,
                (List<String> groups) -> { groups.add(id); return groups; });
        return "Created group " + id + (assigned.isEmpty() ? "" : ", assigned to " + ToolkitFiles.count(assigned.size())) + "; tx " + tx;
    }

    /** Renames the group file and points every member at the new id, keeping each member's group order. */
    String rename(String from, String to) throws IOException {
        if (!to.matches(NpcPaths.SEGMENT)) throw new IOException("Group id " + to + " is not 1-64 lowercase letters, digits, _ or -");
        if (to.equals(from)) return "Group " + from + " already has that id";
        if (store.read(path(to)) != null) throw new IOException(path(to) + " already exists");
        JsonObject document = current(from);
        document.addProperty("id", to);
        document.addProperty("revision", document.get("revision").getAsLong() + 1);
        String content = checked(to, document);
        List<String> members = members(npcs, from);
        String tx = commit(Map.of(from, Optional.empty(), to, Optional.of(content)),
                List.of(removal(from), new DocumentStore.Change(path(to), "missing", content)), members, (List<String> groups) -> {
                    groups.replaceAll((String group) -> group.equals(from) ? to : group);
                    return new ArrayList<>(new LinkedHashSet<>(groups));
                });
        return "Renamed group " + from + " to " + to + ", " + ToolkitFiles.count(members.size()) + " repointed; tx " + tx;
    }

    /** Sets the description; an empty one removes the field. */
    String describe(String group, String description) throws IOException {
        JsonObject document = current(group);
        if (document.has("description") ? document.get("description").getAsString().equals(description) : description.isEmpty())
            return "Description of " + group + " unchanged";
        JsonObject next = new JsonObject();
        for (String key : document.keySet()) if (!key.equals("description") && !key.equals("values")) next.add(key, document.get(key));
        if (!description.isEmpty()) next.addProperty("description", description);
        next.add("values", document.get("values"));
        return rewrite(group, next, (description.isEmpty() ? "Removed the description of " : "Described ") + group);
    }

    /** Replaces the values the group supplies; every member must still merge into a valid NPC. */
    String values(String group, JsonObject values) throws IOException {
        JsonObject document = current(group);
        if (document.get("values").equals(values)) return "Values of " + group + " unchanged";
        document.add("values", values.deepCopy());
        int members = members(npcs, group).size();
        return rewrite(group, document, "Saved the values of " + group + " for " + ToolkitFiles.count(members));
    }

    /** Deletes the group file and unassigns it from every member in the same transaction. */
    String delete(String group) throws IOException {
        current(group);
        List<String> members = members(npcs, group);
        String tx = commit(Map.of(group, Optional.empty()), List.of(removal(group)), members, (List<String> groups) -> {
            groups.remove(group);
            return groups;
        });
        return "Deleted group " + group + ", unassigned from " + ToolkitFiles.count(members.size()) + "; tx " + tx;
    }

    private String rewrite(String group, JsonObject document, String head) throws IOException {
        document.addProperty("revision", document.get("revision").getAsLong() + 1);
        String content = checked(group, document);
        String tx = commit(Map.of(group, Optional.of(content)), List.of(new DocumentStore.Change(path(group), store.hash(path(group)), content)), List.of(),
                UnaryOperator.identity());
        return head + "; tx " + tx;
    }

    /**
     * Writes the group changes and the edited members together. {@code groups} maps each group id the change touches to
     * its new file, or empty when it is removed; every member after the change is checked against that catalog first.
     */
    private String commit(Map<String, Optional<String>> groups, List<DocumentStore.Change> changes, List<String> edited, UnaryOperator<List<String>> edit)
            throws IOException {
        Map<String, GroupDocument> catalog = new HashMap<>(npcs.groups().groups());
        for (Map.Entry<String, Optional<String>> group : groups.entrySet()) {
            if (group.getValue().isEmpty()) { catalog.remove(group.getKey()); continue; }
            catalog.put(group.getKey(), GroupDocument.parse(group.getKey(), group.getValue().get()).group().orElseThrow());
        }
        List<NpcDefinition> definitions = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (String id : edited) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            List<String> listed = edit.apply(strings(document));
            JsonArray next = new JsonArray();
            for (String group : listed) next.add(group);
            if (next.isEmpty()) document.remove("groups"); else document.add("groups", next);
            document.addProperty("revision", draft.expectedRevision() + 1);
            definitions.add(NpcDefinition.parse(JsonDocuments.write(document)));
            expected.put(draft.path(), draft.expectedHash());
        }
        List<NpcDefinition> checked = new ArrayList<>(definitions);
        for (NpcDefinition definition : npcs.stored())
            if (!edited.contains(definition.id()) && definition.groups().stream().anyMatch(groups::containsKey)) checked.add(definition);
        for (NpcDefinition definition : checked) {
            List<GroupDocument> listed = new ArrayList<>();
            for (String group : definition.groups()) {
                GroupDocument found = catalog.get(group);
                if (found == null) { errors.add(definition.id() + ": unknown group " + group); continue; }
                listed.add(found);
            }
            try { NpcDefinition.parse(JsonDocuments.write(GroupMerge.merge(definition.json(), listed).effective())); }
            catch (IOException invalid) { errors.add(definition.id() + ": " + invalid.getMessage()); }
        }
        if (!errors.isEmpty()) throw new IOException("Refused, nothing changed: " + String.join("; ", errors));
        String tx = DefinitionBatch.commit(store, definitions, expected, changes);
        npcs.reload();
        return tx;
    }

    /** The group file as it is on disk; refused when it is missing or currently invalid. */
    private JsonObject current(String group) throws IOException {
        if (!group.matches(GroupDocument.ID) || store.read(path(group)) == null) throw new IOException("No group " + group);
        String content = GroupDocuments.read(store, group);
        GroupDocument.Result parsed = GroupDocument.parse(group, content);
        if (parsed.group().isEmpty()) throw new IOException(path(group) + " is invalid; fix it first: " + parsed.problems().getFirst());
        return JsonDocuments.object(content);
    }

    /** The file text after the group validator passes it. */
    private static String checked(String id, JsonObject document) throws IOException {
        String content = JsonDocuments.write(document);
        GroupDocument.Result parsed = GroupDocument.parse(id, content);
        if (parsed.group().isEmpty()) throw new IOException("Refused, nothing changed: " + String.join("; ", parsed.problems()));
        return content;
    }

    private DocumentStore.Change removal(String group) throws IOException { return new DocumentStore.Change(path(group), store.hash(path(group)), null); }

    private static String path(String group) { return GroupDocuments.FOLDER + "/" + group + ".json"; }

    private static List<String> strings(JsonObject document) {
        List<String> list = new ArrayList<>();
        if (document.has("groups") && document.get("groups").isJsonArray())
            for (JsonElement element : document.getAsJsonArray("groups")) list.add(element.getAsString());
        return list;
    }
}
