package art.arcane.animus.core.quest;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
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
import art.arcane.animus.core.text.RichText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QuestServiceTest {
    @TempDir Path directory;
    private AnimusRuntime runtime;
    private final MockScreenHost host = new MockScreenHost();
    private final Player alex = new Player("Alex");
    private final List<String> log = new ArrayList<>();
    private QuestService<Player, Stack> quests;
    private UUID healer, farmer;

    @BeforeEach void start() throws Exception {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        Administration administration = new Administration(runtime);
        administration.create("healer", "minecraft:player", "world", 0, 64, 0, "default", "Healer");
        administration.create("farmer", "minecraft:player", "world", 10, 64, 0, "default", "Farmer");
        healer = runtime.npcs().find("healer").orElseThrow().uuid();
        farmer = runtime.npcs().find("farmer").orElseThrow().uuid();
        Files.createDirectories(directory.resolve("quests/town"));
        Files.writeString(directory.resolve("quests/town/herbs.json"), QuestDocumentTest.HERBS);
        quests = service();
    }

    private QuestService<Player, Stack> service() throws IOException {
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty());
        QuestService<Player, Stack> service = new QuestService<>(runtime, new ScreenManager<>(host, (UUID npc) -> true, log::add), living,
                DisplayTransport.unavailable("none"), (Player watched) -> {}, Runnable::run, () -> null, () -> BehaviorFacts.NONE, (String name) -> Optional.empty(), log::add);
        service.reload((QuestCatalog ignored) -> {});
        assertEquals(List.of(), service.catalog().problems());
        return service;
    }

    @AfterEach void stop() throws InterruptedException { quests.close(); assertTrue(runtime.closeAndWait(java.time.Duration.ofSeconds(10))); }

    private long stage() {
        return (Long) runtime.behaviorState().read(BehaviorFlags.subject(BehaviorFlags.QUESTS, "town:herbs", ServerNpc.HANDLE.uuid(), Optional.of(alex.id)), "herbs",
                BehaviorFlags.declaration("player"), new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, 0L), BehaviorProgram.Type.INTEGER).value();
    }

    private String token() {
        for (String message : alex.messages) if (message.startsWith("parts:")) {
            int at = message.indexOf(" choice ") + 8;
            return message.substring(at, message.indexOf(' ', at));
        }
        throw new AssertionError("no offer buttons in " + alex.messages);
    }

    @Test void aPlayerTakesTalksBringsKillsAndTurnsInTheQuest() throws Exception {
        assertFalse(quests.interacted(alex, farmer, false, false), "the farmer has nothing for a player without the quest");
        assertTrue(quests.interacted(alex, healer, false, false), "the healer offers the quest");
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Could you help me with the harvest?")), alex.messages.toString());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("/animus quest choice")), alex.messages.toString());
        quests.choice(alex, token(), "accept");
        assertEquals(1, stage(), "accepting writes stage 1 to behavior state");
        assertEquals("active", quests.status(alex.id, "town:herbs"));

        assertTrue(quests.interacted(alex, farmer, false, false), "talking to the farmer is the first objective");
        assertEquals(2, stage());

        alex.inventory.with(0, Stack.of("minecraft:wheat", 2));
        assertTrue(quests.interacted(alex, healer, false, false));
        assertEquals(0, alex.inventory.total("minecraft:wheat"), "the healer takes what the player has");
        alex.inventory.with(0, Stack.of("minecraft:wheat", 5));
        quests.interacted(alex, healer, false, false);
        assertEquals(4, alex.inventory.total("minecraft:wheat"), "and only what is still missing");
        assertEquals("active", quests.status(alex.id, "town:herbs"));

        quests.killed(alex.id, "minecraft:skeleton", Optional.empty());
        quests.killed(alex.id, "minecraft:zombie", Optional.empty());
        quests.killed(alex.id, "minecraft:zombie", Optional.empty());
        assertEquals("ready", quests.status(alex.id, "town:herbs"), "the last stage waits for the turn-in NPC");
        assertEquals(2, stage());

        assertTrue(quests.interacted(alex, healer, false, false));
        assertEquals("finished", quests.status(alex.id, "town:herbs"));
        assertEquals(3, stage(), "a finished quest is one past its last stage");
        assertEquals(5, alex.inventory.total("minecraft:bread"));
        assertEquals(50, alex.experience);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Thank you, Alex.")), alex.messages.toString());
        assertTrue(log.stream().anyMatch((String line) -> line.contains("money reward skipped")), log.toString());
        BehaviorProgram.Value flag = runtime.behaviorState().read(BehaviorFlags.subject(BehaviorFlags.FLAGS, "town:herbs", ServerNpc.HANDLE.uuid(), Optional.of(alex.id)),
                "helped_healer", BehaviorFlags.declaration("player"), new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, false), BehaviorProgram.Type.ANY);
        assertEquals(Boolean.TRUE, flag.value());

        alex.messages.clear();
        assertFalse(quests.interacted(alex, healer, false, false), "a daily quest is not offered again the same day");

        quests.close();
        quests = service();
        assertEquals(Optional.of(1), quests.store().record(alex.id).finished("town:herbs").map(QuestRecord.Finished::count), "the record survives a restart");
    }

    @Test void stageValuesWrittenByBehaviorsMoveTheQuest() {
        BehaviorFlags.subject(BehaviorFlags.QUESTS, "town:herbs", ServerNpc.HANDLE.uuid(), Optional.of(alex.id));
        runtime.behaviorState().write(BehaviorFlags.subject(BehaviorFlags.QUESTS, "town:herbs", ServerNpc.HANDLE.uuid(), Optional.of(alex.id)),
                Map.of("herbs", new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, 2L)), Map.of("herbs", BehaviorFlags.declaration("player")));
        quests.tick(alex);
        assertEquals(1, quests.store().record(alex.id).active("town:herbs").orElseThrow().stage(), "a behavior that sets stage 2 starts the quest there");
        runtime.behaviorState().write(BehaviorFlags.subject(BehaviorFlags.QUESTS, "town:herbs", ServerNpc.HANDLE.uuid(), Optional.of(alex.id)),
                Map.of("herbs", new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, 0L)), Map.of("herbs", BehaviorFlags.declaration("player")));
        quests.tick(alex);
        assertEquals("none", quests.status(alex.id, "town:herbs"), "stage 0 abandons it");
    }

    @Test void behaviorRequestsStartAdvanceCountAndComplete() {
        assertEquals(1, quests.start(alex.id, "town:herbs", false));
        QuestControl.Refused twice = assertThrows(QuestControl.Refused.class, () -> quests.start(alex.id, "town:herbs", false));
        assertEquals("quest-active", twice.code());
        assertEquals(2, quests.advance(alex.id, "town:herbs", OptionalInt.empty()));
        assertEquals("objective-unknown", assertThrows(QuestControl.Refused.class, () -> quests.objective(alex.id, "town:herbs", "lever", 1)).code());
        assertEquals(3, quests.complete(alex.id, "town:herbs", false));
        assertEquals(0, alex.experience, "rewards: false pays nothing");
        assertEquals("quest-unknown", assertThrows(QuestControl.Refused.class, () -> quests.start(alex.id, "town:nothing", true)).code());
    }

    @Test void theJournalListsActiveAndFinishedQuests() {
        quests.start(alex.id, "town:herbs", false);
        assertTrue(quests.journal(alex));
        assertTrue(alex.frame.items().values().stream().anyMatch((var item) -> item.name().map(RichText::plain).orElse("").equals("Herbs for the healer")));
        assertTrue(alex.frame.items().values().stream().flatMap((var item) -> item.lore().stream()).map(RichText::plain).anyMatch((String line) -> line.contains("Talk to the farmer")),
                alex.frame.items().toString());
        quests.complete(alex.id, "town:herbs", false);
        quests.screens().click(alex, alex.window, QuestJournal.FINISHED_TAB, art.arcane.animus.core.gui.GuiModel.Click.LEFT);
        assertTrue(alex.frame.items().values().stream().flatMap((var item) -> item.lore().stream()).map(RichText::plain).anyMatch((String line) -> line.startsWith("Finished")),
                alex.frame.items().toString());
    }
}
