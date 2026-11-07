package art.arcane.animus.core.behavior.state;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BehaviorStateRuntimeTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final BehaviorState.Subject SUBJECT = new BehaviorState.Subject("example:quest", NPC, Optional.empty());
    private static final ScopedVariable DECLARATION = new ScopedVariable(Scope.NPC, Lifetime.PERSISTENT);
    private static final String PATH = "state/behavior-state/npc/" + NPC + "/example/quest.json";

    @TempDir Path root;

    private AnimusRuntime runtime(String config) throws Exception {
        if (config != null) Files.writeString(root.resolve("config.toml"), config);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", root));
        runtime.start();
        return runtime;
    }

    @Test
    void runtimeShutdownFlushesThroughTheFileLaneAndRestartRestores() throws Exception {
        AnimusRuntime first = runtime(null);
        first.behaviorState().write(SUBJECT, Map.of("v", new Value(Type.INTEGER, 42L)), Map.of("v", DECLARATION));
        assertFalse(Files.exists(root.resolve(PATH)));
        first.close();
        assertTrue(Files.exists(root.resolve(PATH)));
        AnimusRuntime second = runtime(null);
        try {
            assertEquals(new Value(Type.INTEGER, 42L), second.behaviorState().read(SUBJECT, "v", DECLARATION, new Value(Type.INTEGER, 0L)));
        } finally {
            second.close();
        }
    }

    @Test
    void attachedLaneFlushesOnceTheConfiguredIntervalPasses() throws Exception {
        Files.createDirectories(root);
        AnimusRuntime runtime = runtime("[behaviors.state]\nflushMillis = 100\n");
        try {
            BehaviorStateStore state = runtime.behaviorState();
            state.write(SUBJECT, Map.of("v", new Value(Type.INTEGER, 1L)), Map.of("v", DECLARATION));
            long until = System.nanoTime() + 4_000_000_000L;
            while (!Files.exists(root.resolve(PATH)) && System.nanoTime() < until) {
                state.maintain();
                Thread.sleep(10);
            }
            assertTrue(Files.exists(root.resolve(PATH)));
            assertFalse(Files.exists(root.resolve("backups")));
        } finally {
            runtime.close();
        }
    }

    @Test
    void finalFlushStillWorksAfterThePluginArchiveCloses() throws Exception {
        Set<URL> urls = new LinkedHashSet<>();
        for (Class<?> type : List.of(BehaviorStateStore.class, ColdStateFlushProbe.class, art.arcane.animus.api.Outcome.class, com.google.gson.Gson.class))
            urls.add(type.getProtectionDomain().getCodeSource().getLocation());
        Runnable finish;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> probe = Class.forName(ColdStateFlushProbe.class.getName(), true, loader);
            finish = (Runnable) probe.getMethod("prepare", Path.class).invoke(null, root);
        }
        finish.run();
        assertTrue(Files.readString(root.resolve(PATH)).contains("\"value\": 7"));
    }
}
