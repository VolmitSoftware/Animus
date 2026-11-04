package art.arcane.animus.core;

import art.arcane.animus.api.*;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.storage.DocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeFilesTest {
    @TempDir Path root;

    private AnimusRuntime runtime() throws Exception {
        AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root));
        runtime.start();
        return runtime;
    }
    private static void create(AnimusRuntime runtime) throws Exception {
        new Administration(runtime).execute(new String[]{"create", "guard", "minecraft:player", "world", "0", "64", "0", "default", "Guard"});
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for controlled work"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Test void apiDraftCannotOvertakeAnAlreadyAdmittedCommandEdit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (AnimusRuntime runtime = runtime()) {
            create(runtime);
            DefinitionService api = runtime.definitionGateway().scoped(() -> true);
            NpcHandle npc = new NpcHandle(runtime.npcs().find("guard").orElseThrow().uuid());
            DefinitionSnapshot before = api.find(npc).toCompletableFuture().get(5, TimeUnit.SECONDS).value().orElseThrow();
            AtomicInteger failures = new AtomicInteger();
            Executor first = runtime.fileExecutor((task, event, error) -> failures.incrementAndGet());
            Executor second = runtime.fileExecutor((task, event, error) -> failures.incrementAndGet());
            first.execute(() -> {
                entered.countDown(); await(release);
                try { new Administration(runtime).execute(new String[]{"edit", "guard", "{\"name\":\"Command\"}"}); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            await(entered);
            var result = api.apply(new DefinitionEdit(npc, before.token(), before.json().replace("Guard", "API")));
            CompletableFuture<String> observed = new CompletableFuture<>();
            second.execute(() -> observed.complete(runtime.npcs().find("guard").orElseThrow().name()));
            release.countDown();
            assertEquals(Outcome.Status.CONFLICT, result.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertEquals("Command", observed.get(5, TimeUnit.SECONDS));
            assertEquals(0, failures.get());
        } finally { release.countDown(); }
    }

    @Test void shutdownCancelsQueuedWorkButLetsTheRunningCommitKeepItsStorage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger accidentallyRan = new AtomicInteger();
        AnimusRuntime runtime = runtime();
        try {
            Executor files = runtime.fileExecutor((task, event, error) -> {
                if (event == MutationLane.EngineTaskEvent.CANCELLED) cancelled.countDown();
                else throw new AssertionError(error);
            });
            DefinitionService api = runtime.definitionGateway().scoped(() -> true);
            files.execute(() -> {
                entered.countDown(); await(release);
                try { create(runtime); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            await(entered);
            files.execute(accidentallyRan::incrementAndGet);
            var queued = api.find(new NpcHandle(java.util.UUID.randomUUID()));
            runtime.closeAsync();
            assertFalse(runtime.isRunning());
            assertFalse(runtime.fileStatus().mutationsDrained());
            assertThrows(RejectedExecutionException.class, () -> files.execute(accidentallyRan::incrementAndGet));
            assertThrows(IllegalStateException.class, runtime::start);
            assertThrows(java.io.IOException.class, () -> new DocumentStore(root));
            assertNotNull(runtime.documents());
            assertEquals(1, cancelled.getCount());
            release.countDown();
            runtime.close();
            assertEquals(MutationLane.CleanupState.SUCCEEDED, runtime.fileStatus().cleanupState());
            await(cancelled);
            assertEquals(0, accidentallyRan.get());
            assertEquals(Outcome.Status.CANCELLED, queued.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertTrue(runtime.awaitFileQuiescence(java.time.Duration.ofSeconds(5)));
            runtime.start();
            assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        } finally { release.countDown(); runtime.close(); }
    }

    @Test void blockedApiResultListenersCannotRetainTheStorageLockAfterShutdown() throws Exception {
        CountDownLatch gate = new CountDownLatch(1), entered = new CountDownLatch(1), callbacks = new CountDownLatch(2), unblock = new CountDownLatch(1);
        AnimusRuntime runtime = runtime();
        try {
            create(runtime);
            runtime.fileExecutor((task, event, error) -> {}).execute(() -> { entered.countDown(); await(gate); });
            await(entered);
            NpcHandle npc = new NpcHandle(runtime.npcs().find("guard").orElseThrow().uuid());
            DefinitionService api = runtime.definitionGateway().scoped(() -> true);
            for (int i = 0; i < 2; i++) api.find(npc).thenAccept(result -> { callbacks.countDown(); await(unblock); });
            gate.countDown(); await(callbacks);
            runtime.close();
            assertEquals(2, runtime.fileStatus().activeDeliveries());
            assertEquals(MutationLane.CleanupState.SUCCEEDED, runtime.fileStatus().cleanupState());
            assertThrows(IllegalStateException.class, runtime::start);
            try (DocumentStore reopened = new DocumentStore(root)) {
                assertNotNull(reopened.read("npcs/default/guard.json"));
            }
            assertEquals(Outcome.Status.UNAVAILABLE, api.find(npc).toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            unblock.countDown();
            assertTrue(runtime.awaitFileQuiescence(java.time.Duration.ofSeconds(5)));
            runtime.start();
            assertEquals("Guard", runtime.npcs().find("guard").orElseThrow().name());
        } finally { gate.countDown(); unblock.countDown(); runtime.close(); }
    }
}
