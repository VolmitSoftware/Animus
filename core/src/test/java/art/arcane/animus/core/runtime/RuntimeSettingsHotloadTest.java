package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.config.NavigationSettings;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.config.RigSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hotload of {@code config.toml} reaches the main-thread NPC runtime on its next tick: budgets and view distance
 * apply at once, lowered caps keep what is already shown and only hold back new work, and {@code [npcs] enabled}
 * turns the runtime off and on again.
 */
class RuntimeSettingsHotloadTest {
    @TempDir Path directory;

    @Test
    void viewersAboveALoweredMaximumStayAndNewViewersWaitUntilUnderIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(npcs(true, 48, 64, 256, 16, 32, 8, 32));
        for (int i = 0; i < 3; i++) harness.online.add(new ViewerWatchRaceTest.Player(UUID.randomUUID(), "Viewer" + i));
        harness.tick(3);
        assertEquals(4, count(harness, "viewers"));

        apply(harness, npcs(true, 48, 64, 2, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(3);
        assertEquals(4, count(harness, "viewers"), "viewers already admitted keep their session");

        ViewerWatchRaceTest.Player late = new ViewerWatchRaceTest.Player(UUID.randomUUID(), "Late");
        harness.online.add(late);
        harness.service.joined(late);
        harness.tick(3);
        assertEquals(4, count(harness, "viewers"), "no new viewer while above the lowered maximum");

        for (int i = 0; i < 3; i++) leave(harness, harness.online.get(1));
        harness.tick(3);
        assertEquals(2, count(harness, "viewers"), "the late viewer is admitted once under the maximum");
    }

    @Test
    void visibleBodiesAboveALoweredMaximumStayAndARaisedMaximumShowsMore() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(npcs(true, 48, 2, 256, 16, 32, 8, 32));
        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i, true, "");
        reload(harness);
        harness.tick(6);
        assertEquals(2, count(harness, "visible"));

        apply(harness, npcs(true, 48, 6, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(6);
        assertEquals(6, count(harness, "visible"), harness.service.status());
        assertFalse(harness.service.status().contains("last-error"), harness.service.status());

        harness.transport.sent.clear();
        apply(harness, npcs(true, 48, 1, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(6);
        assertEquals(6, count(harness, "visible"), "a lowered maximum retires nothing that is already shown");
        assertTrue(harness.transport.sent.stream().noneMatch((String line) -> line.startsWith("despawn")), harness.transport.sent.toString());

        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i, i == 5, "");
        reload(harness);
        harness.tick(6);
        assertEquals(1, count(harness, "visible"));
        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i, true, "");
        reload(harness);
        harness.tick(6);
        assertEquals(1, count(harness, "visible"), "new spawns wait while the viewer is at the lowered maximum");
    }

    @Test
    void aShorterViewDistanceRetiresFarBodiesOnTheNextTick() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(PersistedNpcService.Settings.DEFAULT);
        write(harness, "near", 4.5, true, "");
        write(harness, "far", 30.5, true, "");
        reload(harness);
        harness.tick(4);
        assertEquals(2, count(harness, "visible"));

        apply(harness, npcs(true, 16, 64, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(1);
        assertTrue(harness.transport.sent.contains("despawn " + uuid("far")), harness.transport.sent.toString());
        assertEquals(1, count(harness, "visible"));
    }

    @Test
    void theNextTickSpendsTheHotloadedPacketBudget() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(npcs(true, 48, 64, 256, 16, 1, 8, 32));
        for (int i = 0; i < 6; i++) write(harness, "crowd-" + i, 2.5 + i, true, "");
        reload(harness);
        harness.tick(1);
        harness.tick(1);
        int before = harness.transport.spawned.get();
        assertTrue(before <= 2, "one packet a tick: " + before);

        apply(harness, npcs(true, 48, 64, 256, 16, 8, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(1);
        assertTrue(harness.transport.spawned.get() - before >= 3, "the raised budget spawns several in the next tick: " + harness.transport.spawned.get());
        harness.tick(2);
        assertEquals(6, harness.transport.spawned.get());
    }

    @Test
    void disablingHidesEveryBodyAndEnablingBindsAndShowsThemAgain() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(PersistedNpcService.Settings.DEFAULT);
        write(harness, "keeper", 4.5, true, "");
        reload(harness);
        harness.tick(4);
        assertEquals(1, harness.transport.spawned.get());

        apply(harness, npcs(false, 48, 64, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        assertTrue(harness.service.available(), "nothing changes before the owner's next tick");
        harness.tick(1);
        assertFalse(harness.service.available());
        assertEquals("NPC runtime disabled in config.toml", harness.service.status());
        assertEquals(1, harness.transport.forgotten.get(), "the viewer is forgotten, which removes its bodies");
        assertEquals(1, harness.transport.closed.get(), "the transport is closed");
        harness.tick(4);
        assertEquals(1, harness.transport.spawned.get(), "nothing is presented while disabled");

        apply(harness, npcs(true, 48, 64, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(6);
        assertTrue(harness.service.available(), harness.service.status());
        assertEquals(2, harness.transport.watches.get(), "the viewer is watched again");
        assertEquals(2, harness.transport.spawned.get(), "the NPC is shown again");
        assertTrue(harness.service.inspect("keeper").contains("active"), harness.service.inspect("keeper"));
    }

    @Test
    void aRuntimeStartedDisabledBindsWhenAHotloadEnablesIt() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(npcs(false, 48, 64, 256, 16, 32, 8, 32), false);
        write(harness, "keeper", 4.5, true, "");
        reload(harness);
        harness.tick(3);
        assertEquals("NPC runtime disabled in config.toml", harness.service.status());

        apply(harness, npcs(true, 48, 64, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(6);
        assertTrue(harness.service.available(), harness.service.status());
        assertEquals(1, harness.transport.spawned.get());
    }

    @Test
    void rigsFollowAHotloadedEnabledSwitch() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(PersistedNpcService.Settings.DEFAULT);
        write(harness, "dancer", 4.5, true, ", \"rig\": {\"id\": \"test:dancer\"}");
        reload(harness);
        harness.tick(3);
        assertTrue(harness.service.inspect("dancer").contains("display bones are unavailable"), harness.service.inspect("dancer"));

        RigSettings off = new RigSettings(false, 512, 64, 2, 64);
        apply(harness, npcs(true, 48, 64, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, off);
        harness.tick(1);
        assertTrue(harness.service.inspect("dancer").contains("rigs are disabled in config.toml"), harness.service.inspect("dancer"));

        apply(harness, npcs(true, 48, 64, 256, 16, 32, 8, 32), NavigationSettings.DEFAULT, RigSettings.DEFAULT);
        harness.tick(1);
        assertTrue(harness.service.inspect("dancer").contains("display bones are unavailable"), harness.service.inspect("dancer"));
    }

    @Test
    void navigationBudgetsReachTheRuntimeWithoutStoppingRoutes() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(PersistedNpcService.Settings.DEFAULT);
        write(harness, "walker", 4.5, true, "");
        reload(harness);
        harness.tick(3);
        apply(harness, npcs(true, 48, 64, 256, 16, 32, 8, 32), new NavigationSettings(1, 512, 1, 1, 1, 256), RigSettings.DEFAULT);
        harness.tick(3);
        assertTrue(harness.service.available());
        assertFalse(harness.service.status().contains("last-error"), harness.service.status());
    }

    @Test
    void recordingSettingsReachTheRuntimeOnAHotload() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(PersistedNpcService.Settings.DEFAULT);
        assertEquals(RecordingSettings.DEFAULT, harness.runtime.recordingSettings());
        PluginSettings base = PluginSettings.load(harness.runtime.documents());
        RecordingSettings lowered = new RecordingSettings(false, 4, 600, 65_536, 1, false, 40, 20, false, "red", 1);
        PluginSettings next = new PluginSettings(base.packetProofEnabled(), base.splashScreen(), base.metrics(), base.npcs(), base.files(), base.modules(), base.behaviors(),
                base.combat(), base.builds(), base.shops(), base.relay(), base.editor(), base.rigs(), base.navigation(), base.hotload(), lowered, base.placeholders());
        Thread lane = new Thread(() -> harness.runtime.applySettings(next), "file-lane");
        lane.start();
        try { lane.join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        assertEquals(lowered, harness.runtime.recordingSettings());
        harness.tick(3);
        assertTrue(harness.service.available(), harness.service.status());
    }

    private ViewerWatchRaceTest.Harness harness(PersistedNpcService.Settings settings) throws IOException { return harness(settings, true); }
    private ViewerWatchRaceTest.Harness harness(PersistedNpcService.Settings settings, boolean available) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, settings);
        assertEquals(available, harness.service.available(), harness.service.status());
        return harness;
    }

    private static PersistedNpcService.Settings npcs(boolean enabled, int viewDistance, int maximumVisible, int maximumViewers, int updatesPerTick,
                                                     int packetsPerTick, int pendingSpawnsPerViewer, int lookUpdatesPerTick) {
        return PersistedNpcService.Settings.from(new NpcRuntimeSettings(enabled, viewDistance, maximumVisible, maximumViewers, updatesPerTick,
                packetsPerTick, pendingSpawnsPerViewer, lookUpdatesPerTick, 512, 24, 4));
    }

    /** Publishes settings as a hotload does, on a thread that is not the owner. */
    private static void apply(ViewerWatchRaceTest.Harness harness, PersistedNpcService.Settings npcs, NavigationSettings navigation, RigSettings rigs) throws IOException {
        PluginSettings base = PluginSettings.load(harness.runtime.documents());
        NpcRuntimeSettings table = new NpcRuntimeSettings(npcs.enabled(), (int) npcs.viewDistance(), npcs.maximumVisible(), npcs.maximumViewers(),
                npcs.updatesPerTick(), npcs.packetsPerTick(), npcs.pendingSpawnsPerViewer(), npcs.lookUpdatesPerTick(),
                npcs.simulationPerTick(), npcs.queuePerViewer(), npcs.degradedIntervalTicks());
        PluginSettings next = new PluginSettings(base.packetProofEnabled(), base.splashScreen(), base.metrics(), table, base.files(), base.modules(), base.behaviors(),
                base.combat(), base.builds(), base.shops(), base.relay(), base.editor(), rigs, navigation, base.hotload(), base.recordings(), base.placeholders());
        Thread lane = new Thread(() -> harness.runtime.applySettings(next), "file-lane");
        lane.start();
        try { lane.join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static void leave(ViewerWatchRaceTest.Harness harness, ViewerWatchRaceTest.Player player) {
        harness.online.remove(player);
        harness.service.quit(player);
    }

    private static int count(ViewerWatchRaceTest.Harness harness, String field) {
        Matcher matcher = Pattern.compile("\\| " + field + "=(\\d+)").matcher(harness.service.status());
        assertTrue(matcher.find(), harness.service.status());
        return Integer.parseInt(matcher.group(1));
    }

    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }

    private static void write(ViewerWatchRaceTest.Harness harness, String id, double x, boolean spawned, String extra) throws IOException {
        Path file = harness.npcs.resolve(id + ".json");
        int revision = Files.exists(file) ? Integer.parseInt(Files.readString(file).replaceAll("(?s).*\"revision\": (\\d+).*", "$1")) + 1 : 1;
        Files.writeString(file, """
                {"schemaVersion": 2, "revision": %d, "uuid": "%s", "id": "%s", "group": "test",
                 "name": "%s", "type": "minecraft:villager", "spawned": %s,
                 "position": {"world": "world", "x": %s, "y": 64, "z": 6.5, "yaw": 0, "pitch": 0}%s}
                """.formatted(revision, uuid(id), id, id, spawned, x, extra));
    }

    private static void reload(ViewerWatchRaceTest.Harness harness) throws IOException {
        harness.runtime.npcs().reload();
        harness.service.refresh();
    }
}
