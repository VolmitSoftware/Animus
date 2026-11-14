package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog events for every write, whoever made it, and their coalescing into a few bounded frames. */
class EditorCatalogTest {
    private static final String GREETER = "npcs/examples/greeter.json";
    @TempDir Path directory;
    private EditorHarness harness;
    private SchemaValidator schema;
    private final List<JsonObject> frames = new ArrayList<>();

    @BeforeEach void open() throws Exception {
        harness = new EditorHarness(directory);
        schema = EditorMessageContractTest.validator();
        EditorEventSink sink = new EditorEventSink() {
            @Override public synchronized boolean send(JsonObject event) { frames.add(event); return true; }
            @Override public void close() {}
        };
        String token = harness.token();
        EditorEventStream stream = harness.api.openEvents(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/events",
                Map.of("Authorization", "Bearer " + token, "Origin", EditorHarness.ORIGIN), Optional.empty()), sink).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(stream.open());
    }

    @AfterEach void close() throws Exception { harness.close(); }

    /** Sends every waiting frame and returns the catalog frames, each checked against the schema. */
    private List<JsonObject> drain() {
        int before = frames.size();
        for (int tick = 0; tick < 200; tick++) harness.api.parts().events().tick();
        List<JsonObject> catalog = new ArrayList<>();
        for (JsonObject frame : frames.subList(before, frames.size())) {
            assertTrue(schema.check("event", frame).isEmpty(), () -> schema.check("event", frame) + " in " + frame);
            if (frame.get("type").getAsString().equals("catalog")) catalog.add(frame);
        }
        return catalog;
    }

    private static JsonObject only(List<JsonObject> catalog) {
        assertEquals(1, catalog.size(), catalog::toString);
        JsonArray documents = catalog.getFirst().getAsJsonArray("documents");
        assertEquals(1, documents.size(), catalog::toString);
        return documents.get(0).getAsJsonObject();
    }

    @Test
    void commandEdit_publishesTheNewVersionWithItsSummaryAsServer() throws Exception {
        NpcRepository.Draft draft = harness.npcs.draft("greeter");
        JsonObject edited = draft.document();
        edited.addProperty("name", "&aBrickworks console");
        harness.npcs.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
        harness.catalog.flush();
        JsonObject entry = only(drain());
        assertEquals("examples/greeter", entry.get("id").getAsString());
        assertEquals("server", entry.get("source").getAsString());
        assertEquals("Brickworks console", entry.getAsJsonObject("summary").get("name").getAsString());
        assertEquals("sha256:" + JsonDocuments.hash(harness.read(GREETER)), entry.get("hash").getAsString());
        assertEquals(EditorDocuments.revision(harness.read(GREETER)), entry.get("revision").getAsLong());
    }

    @Test
    void handEditAndDelete_areFoundByTheScanAsFile() throws Exception {
        String text = harness.read(GREETER).replace("\"Greeter\"", "\"Edited by hand\"");
        Files.writeString(harness.root.resolve(GREETER), text);
        harness.catalog.scan();
        JsonObject entry = only(drain());
        assertEquals("file", entry.get("source").getAsString());
        assertEquals("Edited by hand", entry.getAsJsonObject("summary").get("name").getAsString());
        harness.catalog.scan();
        assertEquals(List.of(), drain(), "a change is told once");
        Files.delete(harness.root.resolve(GREETER));
        harness.catalog.scan();
        List<JsonObject> deleted = drain();
        assertEquals(1, deleted.size());
        JsonObject reference = deleted.getFirst().getAsJsonArray("deleted").get(0).getAsJsonObject();
        assertEquals("examples/greeter", reference.get("id").getAsString());
        assertEquals("file", reference.get("source").getAsString());
    }

    @Test
    void rewriteWithTheSameBytes_publishesNothing() throws Exception {
        String text = harness.read(GREETER);
        harness.store.commit(List.of(new DocumentStore.Change(GREETER, JsonDocuments.hash(text), text)));
        harness.catalog.flush();
        harness.catalog.scan();
        assertEquals(List.of(), drain());
    }

    @Test
    void editorWrites_areMarkedEditor_andAWriteWaitingForTheFlushIsNotToldAsFileByTheScan() throws Exception {
        String text = harness.read(GREETER).replace("\"Greeter\"", "\"Through the editor\"");
        EditorCatalog.Source outer = EditorCatalog.enter(EditorCatalog.Source.EDITOR);
        try { harness.store.commit(List.of(new DocumentStore.Change(GREETER, JsonDocuments.hash(harness.read(GREETER)), text))); }
        finally { EditorCatalog.leave(outer); }
        harness.catalog.scan();
        assertEquals(List.of(), drain(), "the scan leaves an announced write to the flush");
        harness.catalog.flush();
        assertEquals("editor", only(drain()).get("source").getAsString());
    }

    @Test
    void aBurstOf1200Files_isOneScanSentInAFewFramesUnderTheFrameBudget() throws Exception {
        int written = SyntheticStore.write(harness.root, 500, 400, 140, 110, 50);
        harness.catalog.scan();
        List<JsonObject> catalog = drain();
        Set<String> seen = new HashSet<>();
        for (JsonObject frame : catalog) {
            int bytes = 0;
            for (JsonElement entry : frame.getAsJsonArray("documents")) {
                bytes += entry.toString().getBytes(StandardCharsets.UTF_8).length;
                assertTrue(seen.add(entry.getAsJsonObject().get("kind").getAsString() + ":" + entry.getAsJsonObject().get("id").getAsString()));
                assertEquals("file", entry.getAsJsonObject().get("source").getAsString());
            }
            assertTrue(bytes <= EditorEvents.CATALOG_FRAME_BYTES, "frame entries " + bytes);
        }
        assertEquals(written, seen.size());
        assertTrue(catalog.size() >= 2 && catalog.size() <= 20, catalog.size() + " frames");
    }

    @Test
    void manyStoreWrites_beforeOneFlush_coalesceAndALaterVersionWins() throws Exception {
        for (int round = 0; round < 3; round++) {
            String current = harness.read(GREETER);
            harness.store.commit(List.of(new DocumentStore.Change(GREETER, JsonDocuments.hash(current), current.replaceAll("\"name\": \"[^\"]*\"", "\"name\": \"Round " + round + "\""))));
            harness.catalog.flush();
        }
        JsonObject entry = only(drain());
        assertEquals("Round 2", entry.getAsJsonObject("summary").get("name").getAsString());
    }

    @Test
    void locate_mapsPackBehaviorsBeforeTheirPackAndIgnoresStateFiles() {
        assertEquals(DocumentKind.BEHAVIOR, EditorCatalog.locate("packs/shop/behaviors/shop/keeper.json").orElseThrow().kind());
        assertEquals("shop:keeper", EditorCatalog.locate("packs/shop/behaviors/shop/keeper.json").orElseThrow().id());
        assertEquals(DocumentKind.PACK, EditorCatalog.locate("packs/shop/pack.json").orElseThrow().kind());
        assertEquals(DocumentKind.PRESETS, EditorCatalog.locate("gui/presets.json").orElseThrow().kind());
        assertEquals(DocumentKind.SERVER_BEHAVIORS, EditorCatalog.locate("server-behaviors.json").orElseThrow().kind());
        assertFalse(EditorCatalog.locate("state/editor/tokens.json").isPresent());
        assertFalse(EditorCatalog.locate("npcs/examples/notes.txt").isPresent());
    }
}
