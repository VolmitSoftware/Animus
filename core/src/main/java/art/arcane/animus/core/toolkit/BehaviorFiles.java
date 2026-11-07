package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.behavior.conversation.ConversationDocument;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The behavior screens' file work, on the file lane: which behaviors exist and who lists them, putting one on the
 * selection or taking it off in one transaction, and reading, saving and creating conversations. A saved conversation
 * is checked by the same compiler the web editor's save uses and refused whole when it would not load.
 */
final class BehaviorFiles {
    private final NpcRepository npcs;
    private final DocumentStore store;

    BehaviorFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /**
     * One behavior file. {@code listed} counts the selected NPCs whose {@code behaviors} name it, {@code grouped} the
     * others that get it from a trait group; {@code misfit} says why it is no conversation, empty when it is one.
     */
    record Behavior(String id, String path, Optional<String> pack, String triggers, int listed, int grouped, Optional<String> misfit) {}

    /** A conversation as read, with the file's hash so the save refuses a file changed meanwhile. */
    record Loaded(String id, String path, String hash, JsonObject behavior, ConversationDocument conversation) {}

    /** The chat line of a save and the conversation as it now is on disk. */
    record Saved(String line, Loaded loaded) {}

    /** Every behavior file, loose and in packs, sorted by id. */
    List<Behavior> behaviors(List<String> selected) throws IOException {
        Map<String, List<String>> direct = new HashMap<>(), inherited = new HashMap<>();
        for (String id : selected) {
            Optional<NpcDefinition> definition = stored(id);
            if (definition.isEmpty()) continue;
            List<String> own = strings(definition.get().json().get("behaviors"));
            for (String behavior : own) direct.computeIfAbsent(behavior, (String key) -> new ArrayList<>()).add(id);
            for (String group : definition.get().groups()) {
                Optional<GroupDocument> document = npcs.groups().find(group);
                if (document.isEmpty()) continue;
                for (String behavior : strings(document.get().values().get("behaviors")))
                    if (!own.contains(behavior)) inherited.computeIfAbsent(behavior, (String key) -> new ArrayList<>()).add(id);
            }
        }
        List<Behavior> found = new ArrayList<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.BEHAVIOR))) {
            JsonObject behavior;
            try { behavior = JsonDocuments.object(store.read(location.path())); }
            catch (IOException unreadable) { behavior = new JsonObject(); }
            Optional<String> misfit = switch (ConversationDocument.read(behavior)) {
                case ConversationDocument.Fits fits -> Optional.empty();
                case ConversationDocument.Misfit unfit -> Optional.of(unfit.reason());
            };
            found.add(new Behavior(location.id(), location.path(), BehaviorPath.pack(location.path()), triggers(behavior),
                    direct.getOrDefault(location.id(), List.of()).size(), (int) inherited.getOrDefault(location.id(), List.of()).stream().distinct().count(), misfit));
        }
        found.sort((Behavior a, Behavior b) -> a.id().compareTo(b.id()));
        return found;
    }

    /** Adds the behavior to, or removes it from, the {@code behaviors} of every listed NPC. */
    String attach(String behavior, List<String> ids, boolean attach) throws IOException {
        if (attach && find(behavior).isEmpty()) throw new IOException("No behavior " + behavior + "; save it under behaviors/, then reload");
        int unchanged = 0;
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            List<String> listed = strings(document.get("behaviors"));
            if (listed.contains(behavior) == attach) { unchanged++; continue; }
            JsonArray next = new JsonArray();
            for (String existing : listed) if (!existing.equals(behavior)) next.add(existing);
            if (attach) next.add(behavior);
            if (next.isEmpty()) document.remove("behaviors"); else document.add("behaviors", next);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String head = (attach ? "Attached " + behavior + " to " : "Detached " + behavior + " from ") + ToolkitFiles.count(drafts.size()) + ", " + unchanged + " unchanged";
        return drafts.isEmpty() ? head : head + "; tx " + npcs.applyAll(drafts);
    }

    /** The behavior read as a conversation; refused with the editor's reason when it is not one. */
    Loaded conversation(String id) throws IOException {
        EditorDocuments.Location location = find(id).orElseThrow(() -> new IOException("No behavior " + id));
        String content = store.read(location.path());
        if (content == null) throw new IOException("No behavior " + id);
        JsonObject behavior = JsonDocuments.object(content);
        return switch (ConversationDocument.read(behavior)) {
            case ConversationDocument.Fits fits -> new Loaded(id, location.path(), JsonDocuments.hash(content), behavior, fits.conversation());
            case ConversationDocument.Misfit misfit -> throw new IOException(id + " is not a conversation (" + misfit.path() + ": " + misfit.reason() + "); edit it in the web editor");
        };
    }

    /**
     * Writes {@code next}, the conversation as the screen wrote it, back with the next revision in one transaction.
     * Refused when the compiler finds an error or the file changed since it was read.
     */
    Saved save(Loaded loaded, JsonObject next) throws IOException {
        if (next.equals(loaded.behavior())) return new Saved("No changes to " + loaded.id(), loaded);
        JsonObject revised = next.deepCopy();
        revised.addProperty("revision", EditorDocuments.revision(JsonDocuments.write(loaded.behavior())) + 1);
        Committed committed = commit(loaded.id(), loaded.path(), loaded.hash(), revised);
        return new Saved("Saved " + loaded.id() + "; tx " + committed.tx(), committed.loaded());
    }

    /** A new conversation that says "Hello" on a right click, at {@code behaviors/<namespace>/<path>.json}. */
    Saved create(String id) throws IOException {
        String path;
        try { path = BehaviorPath.forId(id); }
        catch (IllegalArgumentException invalid) { throw new IOException("A behavior id is namespace:path in lowercase, like town:greeter"); }
        if (find(id).isPresent()) throw new IOException("Behavior " + id + " already exists");
        JsonObject blank = new JsonObject();
        blank.addProperty("schemaVersion", 1);
        blank.addProperty("revision", 1);
        blank.addProperty("id", id);
        blank.addProperty("entry", "main");
        blank.add("routines", new JsonObject());
        Committed committed = commit(id, path, "missing", ConversationDocument.started("Hello").write(blank));
        return new Saved("Created " + id + "; tx " + committed.tx(), committed.loaded());
    }

    private Committed commit(String id, String path, String expected, JsonObject next) throws IOException {
        for (JsonElement problem : new EditorDocuments(store, () -> 0).validate(DocumentKind.BEHAVIOR, id, next)) {
            JsonObject diagnostic = problem.getAsJsonObject();
            if ("error".equals(diagnostic.get("severity").getAsString()))
                throw new IOException("Not saved: " + diagnostic.get("path").getAsString() + " " + diagnostic.get("message").getAsString());
        }
        String text = JsonDocuments.write(next);
        String tx = store.commit(List.of(new DocumentStore.Change(path, expected, text)));
        JsonObject written = JsonDocuments.object(text);
        ConversationDocument fresh = ((ConversationDocument.Fits) ConversationDocument.read(written)).conversation();
        return new Committed(tx, new Loaded(id, path, JsonDocuments.hash(text), written, fresh));
    }

    private record Committed(String tx, Loaded loaded) {}

    private Optional<EditorDocuments.Location> find(String id) throws IOException {
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.BEHAVIOR)))
            if (location.id().equals(id)) return Optional.of(location);
        return Optional.empty();
    }

    private Optional<NpcDefinition> stored(String id) {
        for (NpcDefinition definition : npcs.stored()) if (definition.id().equals(id)) return Optional.of(definition);
        return Optional.empty();
    }

    /** What starts it: {@code interaction, dialogue_choice x2}, or how it runs without triggers. */
    static String triggers(JsonObject behavior) {
        JsonElement server = behavior.get("server");
        String kind = server != null && server.isJsonPrimitive() && server.getAsJsonPrimitive().isBoolean() && server.getAsBoolean() ? "Server behavior; " : "";
        Map<String, Integer> events = new LinkedHashMap<>();
        JsonElement triggers = behavior.get("triggers");
        if (triggers != null && triggers.isJsonArray()) for (JsonElement trigger : triggers.getAsJsonArray()) {
            JsonElement event = trigger.isJsonObject() ? trigger.getAsJsonObject().get("event") : null;
            events.merge(event != null && event.isJsonPrimitive() ? event.getAsString() : "unknown", 1, Integer::sum);
        }
        if (events.isEmpty()) return kind + "no triggers; runs by command";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> event : events.entrySet()) parts.add(event.getKey() + (event.getValue() > 1 ? " x" + event.getValue() : ""));
        return kind + "on " + String.join(", ", parts);
    }

    private static List<String> strings(JsonElement value) {
        List<String> list = new ArrayList<>();
        if (value != null && value.isJsonArray()) for (JsonElement element : value.getAsJsonArray()) if (element.isJsonPrimitive()) list.add(element.getAsString());
        return list;
    }
}
