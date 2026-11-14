package art.arcane.animus.core.editor;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeCatalogs;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.hotload.HotloadService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A running editor service over a running runtime: an {@code animus edit}, a hotloaded hand edit and a hand edit picked up by
 * {@code /animus reload} each reach an open event stream as a catalog frame with the new summary and its source.
 */
class EditorChangeSourcesTest {
    private static final String NPC = """
            {"schemaVersion":2,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
            """;
    @TempDir Path directory;
    private Path root;
    private AnimusRuntime runtime;
    private HotloadService hotload;
    private EditorHarness harness;
    private EditorService service;
    private final List<JsonObject> frames = new CopyOnWriteArrayList<>();

    @BeforeEach void open() throws Exception {
        root = directory.resolve("server");
        Files.createDirectories(root.resolve("npcs/town"));
        Files.writeString(root.resolve("config.toml"), "[editor]\nlistenerEnabled = false\nrelayEnabled = false\n");
        Files.writeString(root.resolve("npcs/town/guard.json"), NPC);
        runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root));
        runtime.start();
        runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, () -> {}, () -> null, () -> CompletableFuture.completedFuture(null),
                () -> CompletableFuture.completedFuture(null), () -> CompletableFuture.completedFuture(null), (String line) -> {})));
        hotload = new HotloadService(new HotloadService.Parts(runtime, runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {}),
                Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()), (List<String> sent, boolean sound) -> {}, () -> {}, (String line) -> {}, (String line) -> {}));
        hotload.start();
        harness = new EditorHarness(directory.resolve("host"));
        service = new EditorService(runtime, harness.host, (String line) -> {}, (String line) -> {});
        service.start();
        EditorApi api = service.api().orElseThrow();
        await(() -> api.parts().catalog().primed(), "the catalog index read at start");
        EditorTokens.Pairing pairing = api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        EditorResponse paired = api.handle(new EditorRequest(EditorRequest.Transport.RELAY, "POST", "/v1/pair", Map.of(), Optional.of(body))).toCompletableFuture().get(10, TimeUnit.SECONDS);
        String token = EditorHarness.json(paired).get("token").getAsString();
        EditorEventSink sink = new EditorEventSink() {
            @Override public boolean send(JsonObject event) { frames.add(event); return true; }
            @Override public void close() {}
        };
        EditorEventStream stream = api.openEvents(new EditorRequest(EditorRequest.Transport.RELAY, "GET", "/v1/events", Map.of("Authorization", "Bearer " + token), Optional.empty()), sink)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(stream.open());
    }

    @AfterEach void close() throws Exception {
        service.close();
        hotload.close();
        runtime.close();
        harness.close();
    }

    /** The guard's newest catalog entry once one names {@code name}, waiting for the service's own timers. */
    private JsonObject announced(String name) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            for (JsonObject frame : frames) {
                if (!frame.get("type").getAsString().equals("catalog")) continue;
                for (JsonElement element : frame.getAsJsonArray("documents")) {
                    JsonObject entry = element.getAsJsonObject();
                    if (entry.get("id").getAsString().equals("town/guard") && name.equals(entry.getAsJsonObject("summary").get("name").getAsString())) return entry;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no catalog frame named " + name + "; frames " + frames);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void animusEdit_isAnnouncedAsServer() throws Exception {
        new Administration(runtime).edit("guard", "{\"name\":\"Commanded\"}");
        JsonObject entry = announced("Commanded");
        assertEquals("server", entry.get("source").getAsString());
        assertEquals(2, entry.get("revision").getAsLong());
    }

    @Test
    void hotloadedHandEdit_isAnnouncedAsFile() throws Exception {
        Files.writeString(root.resolve("npcs/town/guard.json"), NPC.replace("\"Guard\"", "\"Hotloaded\""));
        await(() -> runtime.npcs().find("guard").map((art.arcane.animus.core.npc.NpcDefinition npc) -> npc.name().equals("Hotloaded")).orElse(false), "the hotload");
        assertEquals("file", announced("Hotloaded").get("source").getAsString());
    }

    @Test
    void handEditLoadedByReload_isAnnouncedAsFile() throws Exception {
        Files.writeString(root.resolve("npcs/town/guard.json"), NPC.replace("\"Guard\"", "\"Reloaded\""));
        new Administration(runtime).reload();
        assertEquals("Reloaded", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals("file", announced("Reloaded").get("source").getAsString());
    }
}
