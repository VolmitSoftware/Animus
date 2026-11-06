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
import art.arcane.animus.core.text.RichText;
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
                () -> { refreshed.add("shops"); return Completed.now(); }, () -> { refreshed.add("holograms"); return Completed.now(); }, (String line) -> {})));
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

    @Test void aBehaviorRunRightAfterTheNoticeUsesTheHotloadedVersion() throws Exception {
        Path behavior = root.resolve("behaviors/town/greeter.json");
        Files.createDirectories(behavior.getParent());
        String source = "{\"schemaVersion\":1,\"revision\":REV,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}";
        Files.writeString(behavior, source.replace("REV", "1"));
        new art.arcane.animus.core.admin.Administration(runtime).execute(new String[]{"create", "test", "minecraft:player", "world", "0", "64", "0", "default", "Test"});
        java.util.concurrent.atomic.AtomicReference<BehaviorCommands> ready = new java.util.concurrent.atomic.AtomicReference<>();
        ExtensionRuntime extensions = new ExtensionRuntime(root.resolve("modules"), art.arcane.animus.api.Platform.PAPER, runtime.definitionGateway(),
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
        runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, () -> {}, () -> behaviors, Completed::now, Completed::now, (String line) -> {})));
        hotload = new HotloadService(new HotloadService.Parts(runtime, runtime.fileExecutor((Runnable task, art.arcane.animus.core.extension.MutationLane.EngineTaskEvent event, Throwable failure) -> {}),
                Runnable::run, new FakeNpcRuntime<Object>((java.util.UUID id) -> Optional.empty()), (List<String> sent, boolean sound) -> {
                    if (sent.stream().noneMatch((String line) -> line.contains("behaviors/town/greeter.json"))) return;
                    atNotice.add(behaviors.statusLines().getFirst());
                    behaviors.execute(console, new String[]{"behaviors", "run", "test", "town:greeter"});
                }, () -> {}, (String line) -> {}));
        hotload.listen();
        hotload.cycle();
        try {
            extensions.start();
            until(() -> behaviors.statusLines().getFirst().startsWith("Behaviors READY"), replies);
            Files.writeString(behavior, source.replace("REV", "2").replace("\"ticks\":1", "\"ticks\":2"));
            settle();
            assertEquals(1, atNotice.size(), atNotice.toString());
            assertTrue(atNotice.getFirst().contains("catalog-generation=2"), atNotice.getFirst());
            until(() -> runs.stream().anyMatch((String line) -> line.startsWith("Behavior run:")), replies);
            String run = runs.stream().filter((String line) -> line.startsWith("Behavior run:")).findFirst().orElseThrow();
            assertTrue(run.startsWith("Behavior run: OK"), run);
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
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
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
        settle();
        assertTrue(lines.isEmpty(), lines.toString());
    }
}
