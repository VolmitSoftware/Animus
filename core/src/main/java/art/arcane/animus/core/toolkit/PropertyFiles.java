package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.npc.DefinitionBatch;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The property editor's writes, on the file lane. A change is found by comparing the object the screen read with the
 * same object after one edit; it is then made on every target NPC's own file, so a bulk change sets one field and leaves
 * the rest of each NPC alone. List changes that only add or only remove entries add or remove those entries on each NPC.
 * Every write is one transaction, checked as {@link NpcRepository#applyAll} checks it and refused whole when any NPC
 * would be invalid.
 */
final class PropertyFiles {
    /** Fields that name or place one NPC; a change to them goes to the first target only. */
    static final Set<String> IDENTITY = Set.of("id", "uuid", "position");
    private final NpcRepository npcs;
    private final DocumentStore store;

    PropertyFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    enum Op { SET, REMOVE, ADD, DROP, PRUNE }

    /** One field change: set or remove the value at {@code path}, or add or drop one list entry there. */
    record Change(List<String> path, Op op, JsonElement value) {
        Change { path = List.copyOf(path); }
    }

    static boolean identity(List<String> path) { return !path.isEmpty() && IDENTITY.contains(path.getFirst()); }

    /** The field changes that turn {@code before} into {@code after}; nested objects are compared field by field. */
    static List<Change> changes(JsonObject before, JsonObject after) {
        List<Change> changes = new ArrayList<>();
        diff(before, after, List.of(), changes);
        return changes;
    }

    private static void diff(JsonObject before, JsonObject after, List<String> prefix, List<Change> out) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            JsonElement was = before.get(key), now = after.get(key);
            if (was != null && was.equals(now)) continue;
            List<String> path = ValueScreen.append(prefix, key);
            if (was != null && now != null && was.isJsonObject() && now.isJsonObject()) { diff(was.getAsJsonObject(), now.getAsJsonObject(), path, out); continue; }
            if (was == null && now != null && now.isJsonObject() && !now.getAsJsonObject().isEmpty()) { diff(new JsonObject(), now.getAsJsonObject(), path, out); continue; }
            if (now == null && was != null && was.isJsonObject() && !was.getAsJsonObject().isEmpty()) { diff(was.getAsJsonObject(), new JsonObject(), path, out); continue; }
            if (was != null && was.isJsonArray() && primitives(was.getAsJsonArray()) && (now == null || now.isJsonArray() && primitives(now.getAsJsonArray()))) {
                JsonArray old = was.getAsJsonArray(), next = now == null ? new JsonArray() : now.getAsJsonArray();
                List<JsonElement> added = new ArrayList<>(next.asList()), removed = new ArrayList<>(old.asList());
                added.removeAll(old.asList());
                removed.removeAll(next.asList());
                List<JsonElement> grown = new ArrayList<>(old.asList()), shrunk = new ArrayList<>(old.asList());
                grown.addAll(added);
                shrunk.removeAll(removed);
                if (removed.isEmpty() && grown.equals(next.asList()) || added.isEmpty() && shrunk.equals(next.asList())) {
                    for (JsonElement entry : added) out.add(new Change(path, Op.ADD, entry));
                    for (JsonElement entry : removed) out.add(new Change(path, Op.DROP, entry));
                    if (now == null) out.add(new Change(path, Op.PRUNE, null));
                    continue;
                }
            }
            out.add(now == null ? new Change(path, Op.REMOVE, null) : new Change(path, Op.SET, now));
        }
    }

    private static boolean primitives(JsonArray array) {
        for (JsonElement entry : array) if (!entry.isJsonPrimitive()) return false;
        return true;
    }

    /**
     * Makes the change between {@code before} and {@code after} on every listed NPC in one transaction; identity fields
     * change on the first only, and a new {@code id} renames its file. NPCs the change leaves as they were are skipped.
     */
    String change(List<String> ids, JsonObject before, JsonObject after) throws IOException {
        List<Change> changes = changes(before, after);
        if (changes.isEmpty() || ids.isEmpty()) return "Nothing changed";
        for (Change change : changes)
            if (change.path().equals(List.of("id"))) {
                if (change.op() != Op.SET || !change.value().isJsonPrimitive()) throw new IOException("An NPC needs an id");
                return rename(ids.getFirst(), change.value().getAsString());
            }
        boolean single = changes.stream().anyMatch((Change change) -> identity(change.path()));
        List<String> targets = single ? List.of(ids.getFirst()) : ids;
        boolean retyped = changes.stream().anyMatch((Change change) -> change.path().equals(List.of("type")));
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : targets) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            for (Change change : changes) make(document, change);
            if (retyped) body(document);
            if (!document.equals(draft.document())) drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String who = targets.size() == 1 ? ToolkitFiles.named(npcs, targets) : ToolkitFiles.count(drafts.size()) + (drafts.size() == targets.size() ? "" : ", " + (targets.size() - drafts.size()) + " unchanged");
        if (drafts.isEmpty()) return "Nothing changed on " + ToolkitFiles.named(npcs, targets);
        return describe(changes.getFirst()) + " on " + who + "; tx " + npcs.applyAll(drafts);
    }

    static void make(JsonObject document, Change change) {
        switch (change.op()) {
            case SET -> ValueScreen.with(document, change.path(), Optional.of(change.value().deepCopy()));
            case REMOVE -> ValueScreen.with(document, change.path(), Optional.empty());
            case ADD, DROP -> {
                JsonElement here = ValueScreen.at(document, change.path());
                JsonArray list = here != null && here.isJsonArray() ? here.getAsJsonArray() : new JsonArray();
                if (change.op() == Op.ADD && !list.contains(change.value())) list.add(change.value());
                if (change.op() == Op.DROP) list.remove(change.value());
                if (here == null && !list.isEmpty()) ValueScreen.with(document, change.path(), Optional.of(list));
            }
            case PRUNE -> {
                JsonElement here = ValueScreen.at(document, change.path());
                if (here != null && here.isJsonArray() && here.getAsJsonArray().isEmpty()) ValueScreen.with(document, change.path(), Optional.empty());
            }
        }
    }

    /** After a new {@code type}: drops the settings only another body has, as the schema's type rules say. */
    static void body(JsonObject document) {
        String type = PropertyScreen.type(document);
        for (Map.Entry<String, String> owned : PropertyScreen.bodyFields().entrySet())
            if (!owned.getValue().equals(type)) document.remove(owned.getKey());
        JsonElement pose = document.get("pose");
        if (!type.equals(ToolkitFiles.PLAYER) && pose != null && !pose.getAsString().equals("standing")) document.remove("pose");
    }

    /** The chat line's start for a change, in words: {@code Set team color to red}, {@code Reset scale}. Ticks read as seconds. */
    static String describe(Change change) {
        String key = change.path().getLast(), name = ValueScreen.name(key, change.path().size() == 1);
        String field = name.equals(name.toUpperCase(java.util.Locale.ROOT)) ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
        boolean ticks = schemaAt(change.path()).map((JsonObject node) -> ValueScreen.ticks(key, node)).orElse(false);
        String value = change.value() == null ? "" : ticks && change.value().isJsonPrimitive() && change.value().getAsJsonPrimitive().isNumber()
                ? ValueScreen.seconds(change.value().getAsDouble()) : ValueScreen.text(change.value());
        return switch (change.op()) {
            case SET -> "Set " + field + " to " + value;
            case REMOVE, PRUNE -> "Reset " + field;
            case ADD -> "Added " + value + " to " + field;
            case DROP -> "Removed " + value + " from " + field;
        };
    }

    /** The schema of the field at {@code path} in an NPC file, when the schema names it. */
    static Optional<JsonObject> schemaAt(List<String> path) {
        JsonObject here = PropertyScreen.SCHEMA;
        for (String key : path) {
            JsonObject shaped = ValueScreen.shape(here);
            if (shaped.has("properties") && shaped.getAsJsonObject("properties").has(key)) here = shaped.getAsJsonObject("properties").getAsJsonObject(key);
            else if (key.matches("\\d+") && shaped.has("items") && shaped.get("items").isJsonObject()) here = shaped.getAsJsonObject("items");
            else if (shaped.has("additionalProperties") && shaped.get("additionalProperties").isJsonObject()) here = shaped.getAsJsonObject("additionalProperties");
            else return Optional.empty();
        }
        return Optional.of(ValueScreen.shape(here));
    }

    /** Renames the NPC and its file in one transaction; rollback brings the old file back. */
    String rename(String id, String next) throws IOException {
        if (next.equals(id)) return "Already named " + id;
        if (!next.matches(NpcPaths.SEGMENT)) throw new IOException(next + " is not 1-64 lowercase letters, digits, _ or -");
        if (npcs.stored().stream().anyMatch((NpcDefinition definition) -> definition.id().equals(next))) throw new IOException("An NPC named " + next + " exists");
        NpcRepository.Draft draft = npcs.draft(id);
        JsonObject document = draft.document();
        document.addProperty("id", next);
        document.addProperty("revision", draft.expectedRevision() + 1);
        NpcDefinition renamed = NpcDefinition.parse(JsonDocuments.write(document));
        if (store.read(renamed.path()) != null) throw new IOException(renamed.path() + " already exists");
        List<DocumentStore.Change> removals = new ArrayList<>(List.of(new DocumentStore.Change(draft.path(), draft.expectedHash(), null)));
        String pointer = DocumentStore.DEFINITION_POINTERS + draft.path(), last = store.read(pointer);
        if (last != null) removals.add(new DocumentStore.Change(pointer, JsonDocuments.hash(last), null));
        String tx = DefinitionBatch.commit(store, List.of(renamed), Map.of(renamed.path(), "missing"), removals);
        npcs.reload();
        return "Renamed " + id + " to " + next + "; tx " + tx;
    }
}
