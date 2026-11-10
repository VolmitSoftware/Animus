package art.arcane.animus.core.quest;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.BehaviorFlags;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.presentation.DisplayTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A stage write the behavior state budgets refuse never undoes or drops the quest. */
class QuestStateBudgetTest {
    @TempDir Path directory;

    @Test void aRefusedStageWriteKeepsTheQuest() throws Exception {
        Files.writeString(directory.resolve("config.toml"), "[behaviors.state]\nmaximumKeys = 1\n");
        Files.createDirectories(directory.resolve("quests/town"));
        Files.writeString(directory.resolve("quests/town/herbs.json"), QuestDocumentTest.HERBS);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        Player alex = new Player("Alex");
        List<String> log = new ArrayList<>();
        QuestService<Player, Stack> quests = new QuestService<>(runtime, new ScreenManager<>(new MockScreenHost(), (UUID npc) -> true, log::add),
                new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()), DisplayTransport.unavailable("none"), (Player watched) -> {},
                Runnable::run, () -> null, () -> BehaviorFacts.NONE, (String name) -> Optional.empty(), log::add);
        try {
            quests.reload((QuestCatalog ignored) -> {});
            runtime.behaviorState().write(BehaviorFlags.subject(BehaviorFlags.FLAGS, "other:thing", ServerNpc.HANDLE.uuid(), Optional.empty()),
                    Map.of("full", new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, true)), Map.of("full", BehaviorFlags.declaration("global")));
            assertEquals(1, quests.start(alex.id, "town:herbs", false));
            assertTrue(log.stream().anyMatch((String line) -> line.contains("stage not written")), log.toString());
            quests.tick(alex);
            assertEquals("active", quests.status(alex.id, "town:herbs"), "the record keeps the quest the state could not hold");
            assertEquals(2, quests.advance(alex.id, "town:herbs", OptionalInt.empty()));
            quests.tick(alex);
            assertEquals(1, quests.store().record(alex.id).active("town:herbs").orElseThrow().stage(), "and its stage");
        } finally {
            quests.close();
            assertTrue(runtime.closeAndWait(Duration.ofSeconds(10)));
        }
    }
}
