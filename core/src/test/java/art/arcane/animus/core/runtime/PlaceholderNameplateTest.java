package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.text.CachedPlaceholders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nameplates and hologram lines are filled for each viewer when shown, and again every refreshTicks while they change. */
class PlaceholderNameplateTest {
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong();
    private volatile String balance = "5";

    private ViewerWatchRaceTest.Harness harness(int refreshTicks) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        CachedPlaceholders placeholders = new CachedPlaceholders(new CachedPlaceholders.Source() {
            @Override public boolean available() { return true; }
            @Override public String fill(UUID viewer, String text) {
                String name = harness.player.id().equals(viewer) ? harness.player.name() : null;
                return name == null ? null : text.replace("%player_name%", name).replace("%bal%", balance);
            }
        }, clock::get);
        placeholders.refreshTicks(refreshTicks);
        runtime.placeholders(placeholders);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("banker.json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c12", "id": "banker", "group": "test",
                 "name": "%player_name%", "type": "minecraft:villager", "spawned": true, "hologram": {"lines": ["Balance %bal%"]},
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0}}
                """);
        runtime.npcs().reload();
        harness.service.refresh();
        return harness;
    }

    @Test
    void shouldShowEachViewerTheirOwnFilledNameAndHologram() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(0);
        harness.tick(6);
        assertEquals(1, harness.transport.labels.size(), harness.transport.labels.toString());
        NpcPresentation.Label shown = harness.transport.labels.getFirst();
        assertEquals("Watcher", shown.name());
        assertEquals(List.of("Balance 5"), shown.appearance().hologram());
    }

    @Test
    void shouldRelabelInPlaceEveryRefreshWhenTheValueChanges() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(20);
        harness.tick(6);
        harness.tick(30);
        assertTrue(harness.transport.relabels.isEmpty(), "unchanged text is not sent again: " + harness.transport.relabels);
        balance = "7";
        clock.addAndGet(CachedPlaceholders.TTL_NANOS);
        harness.tick(25);
        assertEquals(1, harness.transport.relabels.size(), harness.transport.relabels.toString());
        assertTrue(String.join("\n", harness.transport.relabels.getFirst()).contains("Balance 7"), harness.transport.relabels.toString());
        assertEquals(1, harness.transport.spawned.get(), "a relabel never respawns the body");
    }

    @Test
    void shouldFillOnlyWhenShownWhileRefreshIsOff() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness(0);
        harness.tick(6);
        balance = "7";
        clock.addAndGet(CachedPlaceholders.TTL_NANOS);
        harness.tick(60);
        assertTrue(harness.transport.relabels.isEmpty(), harness.transport.relabels.toString());
    }
}
