package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Recording file work for the recording screens, on the file lane: who plays what, assigning a recording to NPCs, their
 * playback, rename and delete. A change that touches NPCs or routes too is one transaction.
 */
final class RecordingFiles {
    static final List<Playback> PLAYBACKS = List.of(Playback.ONCE, Playback.LOOP, Playback.PINGPONG);
    private final NpcRepository npcs;
    private final DocumentStore store;

    RecordingFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /**
     * A saved recording: frames and length ({@code -1} when the file is invalid), the NPCs that play it with their
     * playback, and the routes that play it as a step.
     */
    record Recording(String id, int frames, int durationTicks, Map<String, Optional<Playback>> players, List<String> routes, Optional<String> error) {
        Recording { players = new TreeMap<>(players); routes = List.copyOf(routes); }
        List<String> playerIds() { return List.copyOf(players.keySet()); }
    }

    /** Every saved recording in id order. */
    List<Recording> recordings() throws IOException {
        Map<String, Map<String, Optional<Playback>>> players = players();
        Map<String, List<String>> routes = routes();
        List<Recording> recordings = new ArrayList<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.RECORDING)))
            recordings.add(recording(location.id(), players.getOrDefault(location.id(), Map.of()), routes.getOrDefault(location.id(), List.of())));
        return recordings;
    }

    /** One saved recording, or empty when the file is gone. */
    Optional<Recording> find(String id) throws IOException {
        if (!id.matches(RecordingDocument.ID) || store.read(path(id)) == null) return Optional.empty();
        return Optional.of(recording(id, players().getOrDefault(id, Map.of()), routes().getOrDefault(id, List.of())));
    }

    /** Every listed NPC plays the recording instead of a route; an NPC keeps the playback it had, else loops. */
    String assign(String id, List<String> ids) throws IOException {
        existing(id);
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String npc : ids) {
            NpcRepository.Draft draft = npcs.draft(npc);
            JsonObject document = draft.document();
            JsonObject previous = RouteFiles.navigation(document);
            JsonObject navigation = new JsonObject();
            navigation.addProperty("enabled", true);
            navigation.addProperty("recording", id);
            navigation.addProperty("playback", previous != null && previous.has("playback") ? previous.get("playback").getAsString() : Playback.DEFAULT.key());
            if (previous != null && previous.has("reviewAccepted")) navigation.add("reviewAccepted", previous.get("reviewAccepted"));
            if (navigation.equals(previous)) continue;
            JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
            modules.add(ToolkitFiles.NAVIGATION, navigation);
            document.add("modules", modules);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        if (drafts.isEmpty()) return "Recording " + id + " already plays on " + ToolkitFiles.count(ids.size());
        return "Recording " + id + " on " + ToolkitFiles.named(drafts) + "; tx " + RouteFiles.commit(npcs, store, drafts, List.of());
    }

    /**
     * Moves the listed NPCs that play the recording to the playback after the first one's: once, loop, pingpong. Others
     * are skipped.
     */
    String playback(String id, List<String> ids) throws IOException {
        Map<String, Optional<Playback>> playing = players().getOrDefault(id, Map.of());
        List<String> chosen = ids.stream().filter(playing::containsKey).toList();
        if (chosen.isEmpty()) return "None of the selection plays " + id + "; left-click it to assign it first";
        Playback current = playing.get(chosen.getFirst()).orElse(Playback.DEFAULT);
        Playback next = PLAYBACKS.get((PLAYBACKS.indexOf(current) + 1) % PLAYBACKS.size());
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String npc : chosen) {
            NpcRepository.Draft draft = npcs.draft(npc);
            JsonObject document = draft.document();
            RouteFiles.navigation(document).addProperty("playback", next.key());
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        int skipped = ids.size() - chosen.size();
        return "Playback " + next.key() + " for " + id + " on " + ToolkitFiles.named(chosen) + (skipped == 0 ? "" : ", " + skipped + " skipped (not playing it)")
                + "; tx " + RouteFiles.commit(npcs, store, drafts, List.of());
    }

    /** Renames the file and the id inside it, and repoints every NPC and route step that plays it, in one transaction. */
    String rename(String from, String to) throws IOException {
        String text = existing(from);
        if (!to.matches(RecordingDocument.ID)) throw new IOException("A recording id is 1 to 64 lowercase letters, digits, _ or -");
        if (store.read(path(to)) != null) throw new IOException("recordings/" + to + ".json already exists");
        RecordingDocument old = RecordingDocument.parse(text, JsonDocuments.MAX_BYTES);
        String renamed = new RecordingDocument(old.revision(), to, old.recordedBy(), old.world(), old.origin(), old.sampleTicks(), old.durationTicks(),
                old.frames(), old.events(), old.details()).serialize();
        List<DocumentStore.Change> files = new ArrayList<>(List.of(new DocumentStore.Change(path(from), JsonDocuments.hash(text), null), new DocumentStore.Change(path(to), "missing", renamed)));
        List<String> routes = routes().getOrDefault(from, List.of());
        for (String route : routes) {
            String routePath = "routes/" + route + ".json", routeText = store.read(routePath);
            JsonObject document = JsonDocuments.object(routeText);
            for (JsonElement point : document.getAsJsonArray("points"))
                if (point.getAsJsonObject().has("recording") && point.getAsJsonObject().get("recording").getAsString().equals(from)) point.getAsJsonObject().addProperty("recording", to);
            files.add(new DocumentStore.Change(routePath, JsonDocuments.hash(routeText), JsonDocuments.write(document)));
        }
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String npc : players().getOrDefault(from, Map.of()).keySet()) {
            NpcRepository.Draft draft = npcs.draft(npc);
            JsonObject document = draft.document();
            RouteFiles.navigation(document).addProperty("recording", to);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String tx = RouteFiles.commit(npcs, store, drafts, files);
        String also = (drafts.isEmpty() ? "" : ", " + ToolkitFiles.count(drafts.size()) + " repointed") + (routes.isEmpty() ? "" : ", " + routes.size() + (routes.size() == 1 ? " route" : " routes") + " repointed");
        return "Renamed recording " + from + " to " + to + also + "; tx " + tx;
    }

    /**
     * Deletes the recording. A route step that plays it always refuses the delete; NPCs that play it refuse it unless
     * {@code unassign} also removes their navigation in the same transaction.
     */
    String delete(String id, boolean unassign) throws IOException {
        String text = existing(id);
        List<String> routes = routes().getOrDefault(id, List.of());
        if (!routes.isEmpty()) throw new IOException("Recording " + id + " is a step in " + ToolScreen.names(routes, 4) + "; edit those routes in the web editor first");
        List<String> playing = List.copyOf(players().getOrDefault(id, Map.of()).keySet());
        if (!playing.isEmpty() && !unassign)
            throw new IOException("Recording " + id + " plays on " + ToolkitFiles.count(playing.size()) + " (" + ToolScreen.names(playing, 4) + "); use Unassign and delete to clear them too");
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String npc : unassign ? playing : List.<String>of()) {
            NpcRepository.Draft draft = npcs.draft(npc);
            JsonObject document = draft.document();
            JsonObject modules = document.getAsJsonObject("modules");
            modules.remove(ToolkitFiles.NAVIGATION);
            if (modules.isEmpty()) document.remove("modules");
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String tx = RouteFiles.commit(npcs, store, drafts, List.of(new DocumentStore.Change(path(id), JsonDocuments.hash(text), null)));
        return "Deleted recording " + id + (drafts.isEmpty() ? "" : ", unassigned from " + ToolkitFiles.count(drafts.size())) + "; tx " + tx;
    }

    private Recording recording(String id, Map<String, Optional<Playback>> players, List<String> routes) throws IOException {
        try {
            RecordingDocument document = RecordingDocument.parse(store.read(path(id)), JsonDocuments.MAX_BYTES);
            return new Recording(id, document.frames().size(), document.durationTicks(), players, routes, Optional.empty());
        } catch (IOException | RuntimeException invalid) { return new Recording(id, -1, -1, players, routes, Optional.of(String.valueOf(invalid.getMessage()))); }
    }

    /** Recording id to the NPCs whose navigation plays it, with the playback each names. */
    private Map<String, Map<String, Optional<Playback>>> players() {
        Map<String, Map<String, Optional<Playback>>> players = new HashMap<>();
        for (NpcDefinition definition : npcs.stored()) {
            JsonObject navigation = RouteFiles.navigation(definition.json());
            if (navigation == null || !navigation.has("recording") || !navigation.get("recording").isJsonPrimitive()) continue;
            Optional<Playback> playback = navigation.has("playback") && navigation.get("playback").isJsonPrimitive() ? Playback.parse(navigation.get("playback").getAsString()) : Optional.empty();
            players.computeIfAbsent(navigation.get("recording").getAsString(), (String ignored) -> new TreeMap<>()).put(definition.id(), playback);
        }
        return players;
    }

    /** Recording id to the routes that play it as a step. */
    private Map<String, List<String>> routes() throws IOException {
        Map<String, List<String>> routes = new HashMap<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.ROUTE))) {
            JsonObject document;
            try { document = JsonDocuments.object(store.read(location.path())); }
            catch (IOException invalid) { continue; }
            JsonElement points = document.get("points");
            if (points == null || !points.isJsonArray()) continue;
            for (JsonElement point : points.getAsJsonArray()) {
                if (!point.isJsonObject() || !point.getAsJsonObject().has("recording")) continue;
                List<String> using = routes.computeIfAbsent(point.getAsJsonObject().get("recording").getAsString(), (String ignored) -> new ArrayList<>());
                if (!using.contains(location.id())) using.add(location.id());
            }
        }
        return routes;
    }

    private String existing(String id) throws IOException {
        String text = id.matches(RecordingDocument.ID) ? store.read(path(id)) : null;
        if (text == null) throw new IOException("No recording recordings/" + id + ".json");
        return text;
    }

    private static String path(String id) { return "recordings/" + id + ".json"; }
}
