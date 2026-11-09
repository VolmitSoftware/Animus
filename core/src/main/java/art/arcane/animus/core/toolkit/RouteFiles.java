package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Route file work for the route screens, on the file lane: rename, duplicate, delete, route settings and saving an
 * edited capture. A change that touches NPCs too is one transaction, so its rollback receipt puts the route and every
 * NPC back together.
 */
final class RouteFiles {
    private final NpcRepository npcs;
    private final DocumentStore store;

    RouteFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** A saved route: its point count ({@code -1} when the file is invalid), the NPCs that name it and its settings. */
    record Route(String id, int points, List<String> users, JsonObject settings, Optional<String> error) {
        Route { users = List.copyOf(users); settings = settings.deepCopy(); }
        @Override public JsonObject settings() { return settings.deepCopy(); }
        boolean loop() { return settings.has("loop") && settings.get("loop").getAsBoolean(); }
        String order() { return text("order", WaypointSettings.Order.SEQUENCE.key()); }
        String mode() { return text("mode", "walk"); }
        double speed() { return settings.has("speed") ? settings.get("speed").getAsDouble() : 2; }
        private String text(String key, String fallback) { return settings.has(key) && settings.get(key).isJsonPrimitive() ? settings.get(key).getAsString() : fallback; }
    }

    /** What saving a capture wrote: the route id and the line for the player. */
    /** A saved capture: its route id and the short reply; the file is {@link #path(String)}. */
    record Saved(String id, String line) {}

    /** Every saved route with its users, in id order. */
    List<Route> routes() throws IOException {
        Map<String, List<String>> users = users();
        List<Route> routes = new ArrayList<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.ROUTE)))
            routes.add(route(location.id(), users.getOrDefault(location.id(), List.of())));
        return routes;
    }

    /** One saved route, or empty when the file is gone. */
    Optional<Route> find(String id) throws IOException {
        if (!id.matches(NpcPaths.SEGMENT) || store.read(path(id)) == null) return Optional.empty();
        return Optional.of(route(id, users().getOrDefault(id, List.of())));
    }

    /** Renames the file and repoints every NPC that names it, in one transaction. */
    String rename(String from, String to) throws IOException {
        String text = existing(from);
        free(to);
        List<NpcRepository.Draft> drafts = repoint(from, (JsonObject navigation) -> navigation.addProperty("route", to));
        List<DocumentStore.Change> files = List.of(new DocumentStore.Change(path(from), JsonDocuments.hash(text), null), new DocumentStore.Change(path(to), "missing", text));
        return "Renamed route " + from + " to " + to + (drafts.isEmpty() ? "" : ", " + ToolkitFiles.count(drafts.size()) + " repointed") + "; tx " + commit(npcs, store, drafts, files);
    }

    /** Copies the route under a new id; nothing uses the copy yet. */
    String duplicate(String from, String to) throws IOException {
        String text = existing(from);
        free(to);
        return "Copied route " + from + " as " + to + "; tx " + store.commit(List.of(new DocumentStore.Change(path(to), "missing", text)));
    }

    /**
     * Deletes the route. While NPCs name it the delete is refused, unless {@code unassign} also removes their route
     * movement in the same transaction.
     */
    String delete(String id, boolean unassign) throws IOException {
        String text = existing(id);
        Map<String, List<String>> users = users();
        List<String> using = users.getOrDefault(id, List.of());
        if (!using.isEmpty() && !unassign)
            throw new IOException("Route " + id + " is used by " + ToolkitFiles.count(using.size()) + " (" + ToolScreen.names(using, 4) + "); use Unassign and delete to clear them too");
        List<NpcRepository.Draft> drafts = unassign ? repoint(id, null) : List.of();
        String tx = commit(npcs, store, drafts, List.of(new DocumentStore.Change(path(id), JsonDocuments.hash(text), null)));
        return "Deleted route " + id + (drafts.isEmpty() ? "" : ", unassigned from " + ToolkitFiles.count(drafts.size())) + "; tx " + tx;
    }

    /** Changes the route's own settings; the result is checked as the runtime reads it before it is written. */
    String settings(String id, String what, Consumer<JsonObject> edit) throws IOException {
        String text = existing(id);
        JsonObject document = JsonDocuments.object(text);
        edit.accept(document);
        String next = JsonDocuments.write(document);
        if (next.equals(text)) return "Route " + id + " already has that " + what;
        try { WaypointSettings.document(next); }
        catch (IllegalArgumentException invalid) { throw new IOException("Route " + id + " " + what + " refused: " + invalid.getMessage()); }
        JsonElement value = document.get(what);
        String now = value == null ? "off" : value.isJsonPrimitive() ? value.getAsString() : JsonDocuments.write(value);
        return "Route " + id + " " + what + " set to " + now + "; tx " + store.commit(List.of(new DocumentStore.Change(path(id), JsonDocuments.hash(text), next)));
    }

    /** The route ready for editing in the player's world. */
    WaypointCapture load(String id, Set<String> worlds) throws IOException {
        String text = existing(id);
        return WaypointCapture.load(id, JsonDocuments.hash(text), text, worlds);
    }

    /**
     * Saves a capture: a capture loaded from a route writes back over that file, refused when the file changed
     * meanwhile; a new capture saves as {@code wp-<player>}.
     */
    Saved save(String player, WaypointCapture capture, String document) throws IOException {
        int count = capture.points().size();
        if (capture.source().isEmpty()) {
            String id = new ToolkitFiles(npcs, store).saveRoute(player, document);
            return new Saved(id, "Saved " + id + ", " + count + " points; sneak-click it in Routes to rename");
        }
        String id = capture.source().get();
        if (!store.hash(path(id)).equals(capture.hash().orElseThrow())) throw DocumentStore.conflict(path(id));
        return new Saved(id, "Saved " + id + ", " + count + " points; tx " + store.commit(List.of(new DocumentStore.Change(path(id), capture.hash().get(), document))));
    }

    private Route route(String id, List<String> users) throws IOException {
        String text = store.read(path(id));
        JsonObject document;
        try { document = JsonDocuments.object(text); }
        catch (IOException | RuntimeException invalid) { return new Route(id, -1, users, new JsonObject(), Optional.of(String.valueOf(invalid.getMessage()))); }
        Optional<String> error = Optional.empty();
        try { WaypointSettings.document(text); }
        catch (IOException | RuntimeException invalid) { error = Optional.of(String.valueOf(invalid.getMessage())); }
        int points = document.has("points") && document.get("points").isJsonArray() ? document.getAsJsonArray("points").size() : 0;
        document.remove("points");
        return new Route(id, error.isPresent() ? -1 : points, users, document, error);
    }

    /** Route id to the NPCs whose navigation names it, enabled or not. */
    private Map<String, List<String>> users() {
        Map<String, List<String>> users = new HashMap<>();
        for (NpcDefinition definition : npcs.stored()) {
            JsonObject navigation = navigation(definition.json());
            if (navigation != null && navigation.has("route") && navigation.get("route").isJsonPrimitive())
                users.computeIfAbsent(navigation.get("route").getAsString(), (String ignored) -> new ArrayList<>()).add(definition.id());
        }
        for (List<String> list : users.values()) list.sort(String::compareTo);
        return users;
    }

    /** Drafts that change each user's navigation module; a null edit removes the module. */
    private List<NpcRepository.Draft> repoint(String route, Consumer<JsonObject> edit) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : users().getOrDefault(route, List.of())) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            JsonObject modules = document.getAsJsonObject("modules");
            if (edit == null) {
                modules.remove(ToolkitFiles.NAVIGATION);
                if (modules.isEmpty()) document.remove("modules");
            } else edit.accept(modules.getAsJsonObject(ToolkitFiles.NAVIGATION));
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        return drafts;
    }

    private String existing(String id) throws IOException {
        String text = id.matches(NpcPaths.SEGMENT) ? store.read(path(id)) : null;
        if (text == null) throw new IOException("No route routes/" + id + ".json");
        return text;
    }

    private void free(String id) throws IOException {
        if (!id.matches(NpcPaths.SEGMENT)) throw new IOException("A route id is 1 to 64 lowercase letters, digits, _ or -");
        if (store.read(path(id)) != null) throw new IOException("routes/" + id + ".json already exists");
    }

    static String path(String id) { return "routes/" + id + ".json"; }

    /** The NPC's {@code animus:navigation} module, or null. */
    static JsonObject navigation(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        if (modules == null || !modules.isJsonObject()) return null;
        JsonElement navigation = modules.getAsJsonObject().get(ToolkitFiles.NAVIGATION);
        return navigation != null && navigation.isJsonObject() ? navigation.getAsJsonObject() : null;
    }

    /** Writes NPC drafts and other files as one transaction; the NPCs reload after it. */
    static String commit(NpcRepository npcs, DocumentStore store, List<NpcRepository.Draft> drafts, List<DocumentStore.Change> files) throws IOException {
        List<NpcDefinition> definitions = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        for (NpcRepository.Draft draft : drafts) {
            JsonObject document = draft.document();
            document.addProperty("revision", draft.expectedRevision() + 1);
            definitions.add(NpcDefinition.parse(JsonDocuments.write(document)));
            expected.put(draft.path(), draft.expectedHash());
        }
        String tx = DefinitionBatch.commit(store, definitions, expected, files);
        if (!drafts.isEmpty()) npcs.reload();
        return tx;
    }
}
