package art.arcane.animus.core.hotload;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeCatalogs;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.ModuleSettings;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HotloadServiceTest {
    static final String NPC = """
            {"schemaVersion":2,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
            """;
    @TempDir Path root;
    private AnimusRuntime runtime;
    private HotloadService hotload;
    private final List<ChatLine> lines = new CopyOnWriteArrayList<>();
    private final List<Boolean> dings = new CopyOnWriteArrayList<>();
    private final List<String> refreshed = new ArrayList<>();
    private final List<String> logged = new CopyOnWriteArrayList<>(), warned = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root));
        runtime.start();
        Executor files = runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {});
        runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, () -> refreshed.add("npcs"), () -> null,
                () -> { refreshed.add("shops"); return Completed.now(); }, () -> { refreshed.add("holograms"); return Completed.now(); }, () -> { refreshed.add("quests"); return Completed.now(); }, (String line) -> {})));
        hotload = new HotloadService(new HotloadService.Parts(runtime, files, Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()),
                (ChatLine sent, boolean sound) -> { lines.add(sent); dings.add(sound); }, () -> refreshed.add("screens"), logged::add, warned::add));
        hotload.listen();
        hotload.cycle();
    }

    @AfterEach void stop() {
        hotload.close();
        runtime.close();
    }

    /** Polls until a new notice reaches operators. */
    private void settle() throws InterruptedException {
        int before = lines.size();
        settle(() -> lines.size() > before, "a hotload notice");
    }

    /**
     * Polls until {@code done} holds. A change applies on the second poll that reads it the same, but a busy file lane
     * or a slow activation check retries it on a later poll, so the wait is bounded by time rather than poll count.
     */
    private void settle(java.util.function.BooleanSupplier done, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        hotload.cycle();
        while (true) {
            hotload.cycle();
            if (done.getAsBoolean()) return;
            if (System.nanoTime() > deadline) fail("expected " + expected + " within 30 s; notices: " + shown());
            Thread.sleep(10);
        }
    }

    /** Polls where nothing should be reported: the first starts the stability window, the second would apply. */
    private void quiet() { hotload.cycle(); hotload.cycle(); }

    private List<String> shown() { return lines.stream().map(ChatLine::visible).toList(); }

    @Test void configEditAppliesEveryKeyLiveWithoutARestartMarker() throws Exception {
        Path config = root.resolve("config.toml");
        String text = Files.readString(config);
        Files.writeString(config, text.replace("packetProofEnabled = false", "packetProofEnabled = true").replace("viewDistance = 48", "viewDistance = 40"));
        settle();
        assertTrue(runtime.packetProofEnabled());
        assertEquals(40, runtime.npcSettings().viewDistance());
        List<String> shown = shown();
        assertEquals(List.of("[Animus]: Changed Settings: NPCs view distance and packet proof enabled."), shown);
        assertTrue(logged.contains("Hotloaded config.toml (2 changes): npcs.viewDistance 48 -> 40; packetProofEnabled false -> true"), logged.toString());
        assertTrue(dings.stream().allMatch(Boolean::booleanValue));
        assertTrue(refreshed.contains("screens"));
    }

    @Test void theNpcSwitchIsMarkedForRestartWhereTheRuntimeCannotSwitchLive() throws Exception {
        hotload.close();
        hotload = new HotloadService(new HotloadService.Parts(runtime, runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {}),
                Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()) { @Override public boolean switchesLive() { return false; } },
                (ChatLine sent, boolean sound) -> lines.add(sent), () -> {}, (String line) -> {}, (String line) -> {}));
        hotload.listen();
        hotload.cycle();
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replaceFirst("\\[npcs]\n((?:.*\n)*?)enabled = true", "[npcs]\n$1enabled = false").replace("viewDistance = 48", "viewDistance = 40"));
        settle();
        assertEquals(List.of("[Animus]: Changed Settings: NPCs enabled (needs a restart) and NPCs view distance."), shown());
    }

    @Test void invalidConfigKeepsTheRunningSettingsWithTheFieldError() throws Exception {
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replace("viewDistance = 48", "viewDistance = \"far\""));
        settle();
        assertEquals(48, runtime.npcSettings().viewDistance());
        assertEquals(1, shown().size(), shown().toString());
        assertTrue(shown().getFirst().matches("\\[Animus]: Refused Settings: line \\d+, NPCs view distance must be a whole number; the running settings stay\\."), shown().getFirst());
        assertTrue(warned.stream().anyMatch((String line) -> line.startsWith("Hotload refused config.toml: line ")), warned.toString());
        assertTrue(logged.stream().noneMatch((String line) -> line.contains("refused")), "a refusal is a warning: " + logged);
        lines.clear();
        quiet();
        assertTrue(lines.isEmpty(), "a refused edit is reported once");
    }

    @Test void anUnknownConfigKeyIsIgnoredWithAWarningThatNamesTheLineAndTheNearestKey() throws Exception {
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replace("viewDistance = 48", "viewDistance = 40\nviewDistence = 10"));
        settle(() -> runtime.npcSettings().viewDistance() == 40, "the valid key applying");
        assertTrue(warned.stream().anyMatch((String line) -> line.matches("config\\.toml line \\d+: viewDistence is not a setting in \\[npcs]; it is ignored; did you mean viewDistance\\?")),
                warned.toString());
    }

    @Test void manyFilesInOnePollAreLoggedAsOneSummaryLine() throws Exception {
        for (int index = 0; index < HotloadService.LISTED_FILES + 2; index++) {
            Path file = root.resolve("npcs/town/guard" + index + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, NPC.replace("\"guard\"", "\"guard" + index + "\"").replace("123456789abc", "123456789a" + (10 + index)));
        }
        settle(() -> runtime.npcs().all().size() == HotloadService.LISTED_FILES + 2, "every NPC loading");
        settle(() -> logged.stream().anyMatch((String line) -> line.startsWith("Hotloaded " + (HotloadService.LISTED_FILES + 2) + " files, first ")), "the summary line");
        assertTrue(logged.stream().noneMatch((String line) -> line.startsWith("Hotloaded npcs/")), logged.toString());
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

    @Test void packSubfoldersCopiedSeparatelyAreNotWatchedAsBehaviors() {
        for (String folder : List.of("npcs", "shops", "gui")) {
            assertTrue(HotloadTargets.of("packs/town/" + folder + "/guard.json").isEmpty(), folder);
        }
        assertTrue(HotloadTargets.of("packs/town/behaviors/greet.json").isPresent());
        assertTrue(HotloadTargets.of("packs/town/pack.json").isPresent());
    }

    @Test void npcEditReloadsAndAnInvalidEditKeepsTheLastValidVersion() throws Exception {
        Path file = root.resolve("npcs/town/guard.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, NPC);
        settle();
        assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals(List.of("[Animus]: Added NPC Guard."), shown());
        assertTrue(logged.contains("Hotloaded npcs/town/guard.json (new file)"), logged.toString());
        assertTrue(refreshed.contains("npcs"));
        lines.clear();

        Files.writeString(file, NPC.replace("\"y\":64", "\"y\":\"high\""));
        settle();
        assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals(1, lines.size());
        assertEquals("[Animus]: Refused NPC Guard: position y should be a coordinate within ±30000000; the last working version stays.", shown().getFirst());
        assertTrue(warned.contains("Hotload refused npcs/town/guard.json: $.position.y: expected finite coordinate within ±30000000"), warned.toString());
        lines.clear();

        Files.writeString(file, NPC.replace("\"Guard\"", "\"Captain Guard\""));
        settle();
        assertEquals("Captain Guard", runtime.npcs().find("guard").orElseThrow().name());
        assertEquals(List.of("[Animus]: Changed NPC Captain Guard: name."), shown());
        assertTrue(logged.contains("Hotloaded npcs/town/guard.json (1 change): name \"Guard\" -> \"Captain Guard\""), logged.toString());
    }

    @Test void anEditAndADeleteAfterAHotloadRefusalReplaceTheRefusedFile() throws Exception {
        Path file = root.resolve("npcs/town/guard.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, NPC);
        settle();
        Files.writeString(file, NPC.replace("\"y\":64", "\"y\":\"high\""));
        settle();
        assertTrue(shown().stream().anyMatch((String line) -> line.startsWith("[Animus]: Refused NPC Guard: ")), shown().toString());

        NpcRepository.Draft draft = runtime.npcs().draft("guard");
        JsonObject edited = draft.document();
        edited.addProperty("name", "Watchman");
        String saved = runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
        assertTrue(saved.endsWith("; replaced the refused hand edits in npcs/town/guard.json"), saved);
        assertTrue(Files.readString(file).contains("\"Watchman\""));

        Files.writeString(file, NPC.replace("\"y\":64", "\"y\":\"low\""));
        settle();
        assertTrue(runtime.npcs().delete("guard").endsWith("; removed the refused hand edits in npcs/town/guard.json"));
        assertFalse(Files.exists(file));
    }

    @Test void aVersionOneNpcAndGroupCopiedInAreUpgradedWithAReceiptAndLoad() throws Exception {
        Path before = art.arcane.animus.core.editor.SharedFixtures.fixture("upgrades/before"), after = art.arcane.animus.core.editor.SharedFixtures.fixture("upgrades/after");
        List<String> paths = List.of("groups/guards.json", "npcs/town/citizens-9.json");
        for (String path : paths) {
            Files.createDirectories(root.resolve(path).getParent());
            Files.writeString(root.resolve(path), Files.readString(before.resolve(path)));
        }
        settle(() -> runtime.npcs().find("citizens-9").isPresent() && shown().stream().filter((String line) -> line.contains("to the current file format")).count() == 2, "the upgraded NPC loading");
        for (String path : paths) assertEquals(Files.readString(after.resolve(path)), Files.readString(root.resolve(path)), path);
        assertEquals(List.of("guards"), runtime.npcs().find("citizens-9").orElseThrow().groups());
        assertTrue(runtime.npcs().groups().find("guards").isPresent());
        assertEquals(2, shown().size(), "one line per upgraded file: " + shown());
        assertTrue(shown().stream().allMatch((String line) -> line.endsWith(" to the current file format. [Rollback]")), shown().toString());
        assertTrue(shown().contains("[Animus]: Upgraded trait group guards to the current file format. [Rollback]"), shown().toString());
        java.util.regex.Matcher upgraded = java.util.regex.Pattern.compile("Upgraded npcs/town/citizens-9.json to schemaVersion 2; rollback ([a-f0-9-]{36})").matcher(String.join("\n", logged));
        assertTrue(upgraded.find(), logged.toString());
        assertTrue(logged.stream().anyMatch((String line) -> line.equals("Upgraded npcs/town/citizens-9.json to schemaVersion 2; rollback " + upgraded.group(1))), logged.toString());
        assertTrue(warned.stream().noneMatch((String line) -> line.contains("refused")), warned.toString());
        assertNotNull(runtime.documents().read("backups/" + upgraded.group(1) + ".json"), "a rollback receipt holds the original");
        lines.clear();
        quiet();
        quiet();
        assertTrue(lines.isEmpty(), "the upgrade write is not reported as a hand edit: " + shown());
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
        assertEquals(List.of("[Animus]: Added NPC Baker."), shown());
        assertTrue(shown().stream().noneMatch((String line) -> line.contains("refused")), shown().toString());

        lines.clear();
        Files.writeString(baker, Files.readString(baker).replace("\"Baker\"", "\"Head Baker\""));
        settle();
        assertEquals("Head Baker", runtime.npcs().find("baker").orElseThrow().name());
        assertEquals(List.of("[Animus]: Changed NPC Head Baker: name."), shown());
    }

    @Test void animusOwnWritesProduceNoHotloadMessage() throws Exception {
        runtime.documents().commit(List.of(new DocumentStore.Change("npcs/town/guard.json", JsonDocuments.hash(null), NPC)));
        runtime.npcs().reload();
        quiet();
        NpcRepository.Draft draft = runtime.npcs().draft("guard");
        JsonObject edited = draft.document();
        edited.addProperty("name", "Edited in the editor");
        runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
        quiet();
        quiet();
        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test void behaviorAndShopEditsAreValidatedLikeTheEditor() throws Exception {
        Path behavior = root.resolve("behaviors/town/greeter.json");
        Files.createDirectories(behavior.getParent());
        Files.writeString(behavior, "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}");
        Path shop = root.resolve("shops/town/baker.json");
        Files.createDirectories(shop.getParent());
        Files.writeString(shop, "{\"title\":");
        settle(() -> shown().size() == 2, "a notice for each file");
        assertEquals(List.of("[Animus]: Added behavior town:greeter.", "[Animus]: Refused shop town:baker: the file ends too early at line 1, column 10; it was not loaded."), shown());
    }

    @Test void recordingEditsReloadNpcsAndRefuseWithTheFieldOrTheConfiguredCap() throws Exception {
        String golden = art.arcane.animus.core.editor.SharedFixtures.text(art.arcane.animus.core.editor.SharedFixtures.fixture("recordings/market-loop.json"));
        Path recording = root.resolve("recordings/market-loop.json");
        Files.createDirectories(recording.getParent());
        Files.writeString(recording, golden);
        settle();
        assertEquals(List.of("[Animus]: Added recording market-loop."), shown());
        assertTrue(refreshed.contains("npcs"), refreshed.toString());
        lines.clear();
        Files.writeString(recording, golden.replace("\"sampleTicks\": 2", "\"sampleTicks\": 0"));
        settle();
        assertEquals(List.of("[Animus]: Refused recording market-loop: sample ticks should be a whole number from 1 to 20; the last working version stays."), shown());
        lines.clear();
        Path config = root.resolve("config.toml");
        Files.writeString(config, Files.readString(config).replace("maximumFileBytes = 1048576", "maximumFileBytes = 65536"));
        settle();
        assertEquals(65536, runtime.recordingSettings().maximumFileBytes());
        lines.clear();
        Files.writeString(recording, golden + " ".repeat(65_536));
        settle();
        assertEquals(List.of("[Animus]: Refused recording market-loop: recording exceeds 65536 bytes; the last working version stays."), shown());
    }

    @Test void aBehaviorRunRightAfterTheNoticeUsesTheHotloadedVersion() throws Exception {
        Path behavior = root.resolve("behaviors/town/greeter.json");
        Files.createDirectories(behavior.getParent());
        String source = "{\"schemaVersion\":1,\"revision\":REV,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}";
        Files.writeString(behavior, source.replace("REV", "1"));
        new art.arcane.animus.core.admin.Administration(runtime).create("test", "minecraft:player", "world", 0, 64, 0, "default", "Test");
        java.util.concurrent.atomic.AtomicReference<BehaviorCommands> ready = new java.util.concurrent.atomic.AtomicReference<>();
        ExtensionRuntime extensions = new ExtensionRuntime(root.resolve("modules"), art.arcane.animus.api.Platform.PAPER,
                new ModuleSettings(false, 4, 1000, 250), (String line) -> {}, (ExtensionRuntime.State state) -> ready.get().modulesInitialized(state));
        BehaviorCommands behaviors = new BehaviorCommands(root, runtime, extensions, (String line) -> {});
        ready.set(behaviors);
        Queue<Runnable> replies = new ConcurrentLinkedQueue<>();
        List<String> runs = new CopyOnWriteArrayList<>();
        CommandActor console = new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { runs.add(message); }
            @Override public Optional<java.util.UUID> player() { return Optional.empty(); }
            @Override public boolean reply(Runnable action) { replies.add(action); return true; }
        };
        List<String> atNotice = new CopyOnWriteArrayList<>();
        hotload.close();
        runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, () -> {}, () -> behaviors, Completed::now, Completed::now, Completed::now, (String line) -> {})));
        hotload = new HotloadService(new HotloadService.Parts(runtime, runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {}),
                Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()), (ChatLine sent, boolean sound) -> {
                    if (!sent.visible().contains("behavior town:greeter")) return;
                    atNotice.add(behaviors.statusLines().getFirst());
                    behaviors.run(console, "test", "town:greeter");
                }, () -> {}, (String line) -> {}, (String line) -> {}));
        hotload.listen();
        hotload.cycle();
        try {
            extensions.start();
            until(() -> behaviors.statusLines().getFirst().startsWith("Behaviors READY"), replies);
            Files.writeString(behavior, source.replace("REV", "2").replace("\"ticks\":1", "\"ticks\":2"));
            settle(() -> !atNotice.isEmpty(), "the behavior notice");
            assertEquals(1, atNotice.size(), atNotice.toString());
            assertTrue(atNotice.getFirst().contains("catalog-generation=2"), atNotice.getFirst());
            until(() -> runs.stream().anyMatch((String line) -> line.startsWith("Behavior run:")), replies);
            String run = runs.stream().filter((String line) -> line.startsWith("Behavior run:")).findFirst().orElseThrow();
            assertTrue(run.startsWith("Behavior run: done"), run);
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("execution=([a-f0-9-]{36})").matcher(run);
            assertTrue(id.find(), run);
            java.util.UUID execution = java.util.UUID.fromString(id.group(1));
            until(() -> behaviors.diagnosticRuntime().inspect(execution).isPresent(), replies);
            assertEquals(2, behaviors.diagnosticRuntime().inspect(execution).orElseThrow().behaviorRevision());
        } finally {
            behaviors.close();
            extensions.close();
        }
    }

    /** A shop or hologram reload that has nothing queued. */
    private static final class Completed {
        static CompletableFuture<Void> now() { return CompletableFuture.completedFuture(null); }
    }

    private static void until(java.util.function.BooleanSupplier condition, Queue<Runnable> replies) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            for (Runnable reply; (reply = replies.poll()) != null; ) reply.run();
            Thread.sleep(5);
        }
        for (Runnable reply; (reply = replies.poll()) != null; ) reply.run();
        assertTrue(condition.getAsBoolean());
    }

    @Test void stateAndGeneratedFoldersAreNotWatched() throws Exception {
        Files.createDirectories(root.resolve("state/behavior-state"));
        Files.writeString(root.resolve("state/behavior-state/x.json"), "{}");
        Files.createDirectories(root.resolve("backups"));
        Files.writeString(root.resolve("backups/y.json"), "{}");
        Files.createDirectories(root.resolve("npcs/town"));
        Files.writeString(root.resolve("npcs/town/.guard.json.swp"), "{}");
        quiet();
        assertTrue(lines.isEmpty(), lines.toString());
    }
}
