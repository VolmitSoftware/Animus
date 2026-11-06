package art.arcane.animus.core.hotload;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeCatalogs;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HotloadServiceTest {
    static final String NPC = """
            {"schemaVersion":1,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
            """;
    @TempDir Path root;
    private AnimusRuntime runtime;
    private HotloadService hotload;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Boolean> dings = new CopyOnWriteArrayList<>();
    private final List<String> refreshed = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root));
        runtime.start();
        Executor files = runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {});
        runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, () -> refreshed.add("npcs"), () -> null,
                () -> refreshed.add("shops"), () -> refreshed.add("holograms"), (String line) -> {})));
        hotload = new HotloadService(new HotloadService.Parts(runtime, files, Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()),
                (List<String> sent, boolean sound) -> { lines.addAll(sent); dings.add(sound); }, () -> refreshed.add("screens"), (String line) -> {}));
        hotload.listen();
        hotload.cycle();
    }

    @AfterEach void stop() {
        hotload.close();
        runtime.close();
    }

    /** Two polls: the first starts the stability window, the second applies. */
    private void settle() { hotload.cycle(); hotload.cycle(); }

    private List<String> shown() { return lines.stream().map((String line) -> RichText.sectionRuns(line).stream().map(RichText.Run::text).reduce("", String::concat)).toList(); }

    @Test void configEditAppliesEveryKeyLiveWithoutARestartMarker() throws Exception {
        Path config = root.resolve("config.toml");
        String text = Files.readString(config);
        Files.writeString(config, text.replace("statusMessage = \"Hello from Animus\"", "statusMessage = \"Hotloaded hello\"")
                .replace("viewDistance = 48", "viewDistance = 40"));
        settle();
        assertTrue(runtime.status().endsWith("Hotloaded hello"), runtime.status());
        assertEquals(40, runtime.npcSettings().viewDistance());
        List<String> shown = shown();
        assertTrue(shown.contains("[Animus]: Animus Hotloaded: [config.toml] [statusMessage] [\"Hello from Animus\" -> \"Hotloaded hello\"]"), shown.toString());
        assertTrue(shown.contains("[Animus]: Animus Hotloaded: [config.toml] [npcs.viewDistance] [48 -> 40]"), shown.toString());
        assertTrue(shown.stream().noneMatch((String line) -> line.contains("restart required")), shown.toString());
        assertTrue(dings.stream().allMatch(Boolean::booleanValue));
        assertTrue(refreshed.contains("screens"));
    }

    @Test void theNpcSwitchIsMarkedForRestartWhereTheRuntimeCannotSwitchLive() throws Exception {
        hotload.close();
        hotload = new HotloadService(new HotloadService.Parts(runtime, runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {}),
                Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()) { @Override public boolean switchesLive() { return false; } },
                (List<String> sent, boolean sound) -> lines.addAll(sent), () -> {}, (String line) -> {}));
        hotload.listen();
        hotload.cycle();
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replaceFirst("\\[npcs]\n((?:.*\n)*?)enabled = true", "[npcs]\n$1enabled = false").replace("viewDistance = 48", "viewDistance = 40"));
        settle();
        assertTrue(shown().contains("[Animus]: Animus Hotloaded: [config.toml] [npcs.enabled] [true -> false] [restart required]"), shown().toString());
        assertTrue(shown().contains("[Animus]: Animus Hotloaded: [config.toml] [npcs.viewDistance] [48 -> 40]"), shown().toString());
    }

    @Test void invalidConfigKeepsTheRunningSettingsWithTheFieldError() throws Exception {
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replace("viewDistance = 48", "viewDistance = \"far\""));
        settle();
        assertEquals(48, runtime.npcSettings().viewDistance());
        assertEquals(List.of("[Animus]: Animus Hotload refused: [config.toml] npcs: viewDistance must be an integer [kept the last valid version]"), shown());
        lines.clear();
        settle();
        assertTrue(lines.isEmpty(), "a refused edit is reported once");
    }

    @Test void soundCanBeTurnedOff() throws Exception {
        Path config = root.resolve("config.toml");
        String text = Files.readString(config);
        assertTrue(text.contains("sound = true"), text);
        Files.writeString(config, text.replace("sound = true", "sound = false"));
        settle();
        assertFalse(runtime.hotloadSettings().sound());
        assertEquals(List.of(false), dings.stream().distinct().toList());
    }

    @Test void npcEditReloadsAndAnInvalidEditKeepsTheLastValidVersion() throws Exception {
        Path file = root.resolve("npcs/town/guard.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, NPC);
        settle();
        assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertTrue(shown().contains("[Animus]: Animus Hotloaded: [npcs/town/guard.json] [name] [<missing> -> \"Guard\"]"), shown().toString());
        assertTrue(refreshed.contains("npcs"));
        lines.clear();

        Files.writeString(file, NPC.replace("\"y\":64", "\"y\":\"high\""));
        settle();
        assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals(1, lines.size());
        assertTrue(shown().getFirst().startsWith("[Animus]: Animus Hotload refused: [npcs/town/guard.json] $.position.y"), shown().getFirst());
        assertTrue(shown().getFirst().endsWith("[kept the last valid version]"), shown().getFirst());
        lines.clear();

        Files.writeString(file, NPC.replace("\"Guard\"", "\"Captain Guard\""));
        settle();
        assertEquals("Captain Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals(List.of("[Animus]: Animus Hotloaded: [npcs/town/guard.json] [name] [\"Guard\" -> \"Captain Guard\"]"), shown());
    }

    @Test void aValidNpcEditAppliesWhileAnotherNpcFileIsInvalid() throws Exception {
        Path guard = root.resolve("npcs/town/guard.json");
        Files.createDirectories(guard.getParent());
        Files.writeString(guard, NPC);
        settle();
        Files.writeString(guard, NPC.replace("\"y\":64", "\"y\":\"high\""));
        settle();
        lines.clear();

        Path baker = root.resolve("npcs/town/baker.json");
        Files.writeString(baker, NPC.replace("12345678-1234-1234-1234-123456789abc", "22345678-1234-1234-1234-123456789abc")
                .replace("\"id\":\"guard\"", "\"id\":\"baker\"").replace("\"Guard\"", "\"Baker\""));
        settle();
        assertEquals("Baker", runtime.npcs().find("baker").orElseThrow().name());
        assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals(List.of("[Animus]: Animus Hotloaded: [npcs/town/baker.json] [name] [<missing> -> \"Baker\"]"), shown().stream().filter((String line) -> line.contains("[name]")).toList());
        assertTrue(shown().stream().noneMatch((String line) -> line.contains("refused")), shown().toString());

        lines.clear();
        Files.writeString(baker, Files.readString(baker).replace("\"Baker\"", "\"Head Baker\""));
        settle();
        assertEquals("Head Baker", runtime.npcs().find("baker").orElseThrow().name());
        assertEquals(List.of("[Animus]: Animus Hotloaded: [npcs/town/baker.json] [name] [\"Baker\" -> \"Head Baker\"]"), shown());
    }

    @Test void animusOwnWritesProduceNoHotloadMessage() throws Exception {
        runtime.documents().commit(List.of(new DocumentStore.Change("npcs/town/guard.json", JsonDocuments.hash(null), NPC)));
        runtime.npcs().reload();
        settle();
        NpcRepository.Draft draft = runtime.npcs().draft("guard");
        JsonObject edited = draft.document();
        edited.addProperty("name", "Edited in the editor");
        runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
        settle();
        settle();
        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test void behaviorAndShopEditsAreValidatedLikeTheEditor() throws Exception {
        Path behavior = root.resolve("behaviors/town/greeter.json");
        Files.createDirectories(behavior.getParent());
        Files.writeString(behavior, "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}");
        Path shop = root.resolve("shops/town/baker.json");
        Files.createDirectories(shop.getParent());
        Files.writeString(shop, "{\"title\":");
        settle();
        List<String> shown = shown();
        assertTrue(shown.stream().anyMatch((String line) -> line.startsWith("[Animus]: Animus Hotloaded: [behaviors/town/greeter.json] [entry]")), shown.toString());
        assertTrue(shown.stream().anyMatch((String line) -> line.startsWith("[Animus]: Animus Hotload refused: [shops/town/baker.json] ")), shown.toString());
        assertTrue(refreshed.contains("shops") || shown.stream().noneMatch((String line) -> line.contains("Hotloaded: [shops")));
    }

    @Test void recordingEditsReloadNpcsAndRefuseWithTheFieldOrTheConfiguredCap() throws Exception {
        String golden = art.arcane.animus.core.editor.SharedFixtures.text(art.arcane.animus.core.editor.SharedFixtures.fixture("recordings/market-loop.json"));
        Path recording = root.resolve("recordings/market-loop.json");
        Files.createDirectories(recording.getParent());
        Files.writeString(recording, golden);
        settle();
        assertTrue(shown().stream().anyMatch((String line) -> line.startsWith("[Animus]: Animus Hotloaded: [recordings/market-loop.json]")), shown().toString());
        assertTrue(refreshed.contains("npcs"), refreshed.toString());
        lines.clear();
        Files.writeString(recording, golden.replace("\"sampleTicks\": 2", "\"sampleTicks\": 0"));
        settle();
        assertTrue(shown().stream().anyMatch((String line) -> line.startsWith("[Animus]: Animus Hotload refused: [recordings/market-loop.json] $.sampleTicks: expected integer 1..20")),
                shown().toString());
        lines.clear();
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replace("maximumFileBytes = 1048576", "maximumFileBytes = 65536"));
        settle();
        assertEquals(65536, runtime.recordingSettings().maximumFileBytes());
        lines.clear();
        Files.writeString(recording, golden + " ".repeat(65_536));
        settle();
        assertTrue(shown().stream().anyMatch((String line) -> line.startsWith("[Animus]: Animus Hotload refused: [recordings/market-loop.json] $: recording exceeds 65536 bytes")),
                shown().toString());
    }

    @Test void stateAndGeneratedFoldersAreNotWatched() throws Exception {
        Files.createDirectories(root.resolve("state/behavior-state"));
        Files.writeString(root.resolve("state/behavior-state/x.json"), "{}");
        Files.createDirectories(root.resolve("backups"));
        Files.writeString(root.resolve("backups/y.json"), "{}");
        Files.createDirectories(root.resolve("npcs/town"));
        Files.writeString(root.resolve("npcs/town/.guard.json.swp"), "{}");
        settle();
        assertTrue(lines.isEmpty(), lines.toString());
    }
}
