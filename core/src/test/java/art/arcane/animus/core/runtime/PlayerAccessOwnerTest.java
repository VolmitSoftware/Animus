package art.arcane.animus.core.runtime;

import java.util.Optional;
import art.arcane.animus.core.editor.EditorNotice;
import art.arcane.animus.core.editor.EditorNoticeChannel;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.editor.EditorHost;
import art.arcane.animus.core.editor.RuntimeEditorHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player delivery and console work promise to run on the owner "now or later" from any thread. The web editor's timer
 * thread re-checks token holders' permission every minute through them; that must hop to the owner, not throw.
 */
class PlayerAccessOwnerTest {
    @TempDir Path directory;

    @Test
    void deliveryFromAnotherThreadRunsLaterOnTheOwner() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        harness.ownerThread = false;
        List<ViewerWatchRaceTest.Player> delivered = new ArrayList<>();

        assertTrue(harness.service.players().deliver(harness.player.id(), delivered::add));
        assertTrue(delivered.isEmpty(), "nothing runs off the owner");
        harness.runScheduled();
        assertEquals(List.of(harness.player), delivered);
    }

    @Test
    void consoleWorkAndOnlineChecksWorkFromAnotherThread() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        harness.ownerThread = false;
        List<String> ran = new ArrayList<>();

        assertTrue(harness.service.players().console(() -> ran.add("console")));
        assertTrue(harness.service.players().online(harness.player.id()));
        assertFalse(harness.service.players().online(UUID.randomUUID()));
        harness.runScheduled();
        assertEquals(List.of("console"), ran);
    }

    @Test
    void anOfflinePlayerIsNotDeliveredToFromAnotherThread() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        harness.service.quit(harness.player);
        harness.online.clear();
        harness.ownerThread = false;
        assertFalse(harness.service.players().online(harness.player.id()));
        List<ViewerWatchRaceTest.Player> delivered = new ArrayList<>();
        harness.service.players().deliver(harness.player.id(), delivered::add);
        harness.runScheduled();
        assertTrue(delivered.isEmpty());
    }

    @Test
    void theEditorAdminRecheckFromItsTimerThreadAnswersOnTheOwner() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness();
        RuntimeEditorHost<ViewerWatchRaceTest.Player> host = new RuntimeEditorHost<>(new RuntimeEditorHost.Parts<>(harness.runtime, Runnable::run, harness.service, null,
                "test", "", Map::of, () -> EditorHost.Lookups.NONE, (ViewerWatchRaceTest.Player player) -> false, new EditorNoticeChannel() {
                    @Override public Optional<String> name(UUID player) { return Optional.empty(); }
                    @Override public void deliver(EditorNotice notice) {}
                }, (String line) -> {}, null));
        harness.ownerThread = false;
        List<EditorHost.Admin> answers = new ArrayList<>();

        host.admin(harness.player.id(), answers::add);
        harness.runScheduled();
        assertEquals(List.of(EditorHost.Admin.NOT_ADMIN), answers);
    }

    private ViewerWatchRaceTest.Harness harness() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        assertTrue(harness.service.available(), harness.service.status());
        harness.service.joined(harness.player);
        return harness;
    }
}
