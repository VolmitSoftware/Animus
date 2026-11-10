package art.arcane.animus.core.quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestDocumentTest {
    static final String HERBS = read();

    static String read() {
        try {
            Path here = Path.of("").toAbsolutePath();
            Path root = Files.isDirectory(here.resolve("examples")) ? here : here.getParent();
            return Files.readString(root.resolve("examples/quests/town/herbs.json"));
        } catch (java.io.IOException missing) { throw new IllegalStateException(missing); }
    }

    static QuestDocument herbs() { return QuestDocument.parse("town:herbs", HERBS).quest().orElseThrow(); }

    private static List<String> problems(String json) { return QuestDocument.parse("town:herbs", json).problems(); }

    @Test void theExampleParses() {
        QuestDocument quest = herbs();
        assertEquals("town", quest.namespace());
        assertEquals("herbs", quest.name());
        assertEquals(List.of("healer"), quest.givers());
        assertEquals(Optional.of("healer"), quest.turnIn());
        assertEquals(QuestDocument.RepeatMode.DAILY, quest.repeat().mode());
        assertEquals(2, quest.stages().size());
        assertEquals(3, quest.finishedStage());
        QuestDocument.Objective bring = quest.stages().get(1).objectives().get(0);
        assertEquals(QuestDocument.Type.BRING, bring.type());
        assertEquals("bring-1", bring.id());
        assertEquals(3, bring.amount());
        assertEquals(Optional.of("minecraft:zombie"), quest.stages().get(1).objectives().get(1).entity());
        assertEquals(Set.of("healer", "farmer"), quest.npcs());
        assertEquals(50, quest.rewards().experience());
        assertEquals("helped_healer", quest.rewards().flags().getFirst().name());
        assertFalse(quest.hasCommands());
    }

    @Test void problemsNameTheirField() {
        assertTrue(problems(HERBS.replace("\"town:herbs\"", "\"town:other\"")).contains("$.id: id town:other does not match its file, which names town:herbs"));
        assertTrue(problems(HERBS.replace("\"type\": \"talk\", \"npc\": \"farmer\"", "\"type\": \"talk\"")).contains("$.stages[0].objectives[0].npc: a talk objective needs an NPC id"));
        assertTrue(problems(HERBS.replace("\"entity\": \"minecraft:zombie\"", "\"entity\": \"minecraft:zombie\", \"npc\": \"bandit\""))
                .contains("$.stages[1].objectives[1].entity: a kill objective needs exactly one of entity or npc"));
        assertTrue(problems(HERBS.replace("{\"mode\": \"daily\"}", "{\"mode\": \"cooldown\"}")).contains("$.repeat.seconds: a cooldown needs seconds"));
        assertTrue(problems(HERBS.replace("\"experience\": 50", "\"experience\": 50, \"commands\": [{\"command\": \"/say hi\"}]"))
                .contains("$.rewards.commands[0].command: write the command without the slash"));
        assertTrue(problems(HERBS.replace("\"type\": \"talk\", \"npc\": \"farmer\"", "\"type\": \"talk\", \"npc\": \"farmer\", \"amount\": 2"))
                .contains("$.stages[0].objectives[0].amount: a talk objective does not take amount"));
        assertTrue(problems(HERBS.replace("\"stages\"", "\"extra\": 1, \"stages\"")).contains("$.extra: unknown field"));
    }

    @Test void customObjectivesNeedAnId() {
        String custom = HERBS.replace("{\"type\": \"talk\", \"npc\": \"farmer\", \"description\": \"Talk to the farmer\"}", "{\"type\": \"custom\", \"amount\": 2}");
        assertTrue(problems(custom).contains("$.stages[0].objectives[0].id: a custom objective needs an id, which behaviors and commands name"));
        String named = custom.replace("{\"type\": \"custom\", \"amount\": 2}", "{\"type\": \"custom\", \"id\": \"lever\", \"amount\": 2}");
        assertEquals(List.of(), problems(named));
    }

    @Test void conditionsCompileAsBehaviorExpressions() {
        String guarded = HERBS.replace("\"repeat\"", "\"requirements\": {\"condition\": \"quest('player', 'intro') >= 2\", \"quests\": [\"town:intro\"]}, \"repeat\"");
        QuestDocument quest = QuestDocument.parse("town:herbs", guarded).quest().orElseThrow();
        assertTrue(quest.requirements().condition().isPresent());
        assertEquals(List.of("town:intro"), quest.requirements().quests());
        assertFalse(problems(HERBS.replace("\"repeat\"", "\"requirements\": {\"condition\": \"unknown_root.x\"}, \"repeat\"")).isEmpty());
    }

    @Test void namesWithSlashesAreRefused() {
        assertFalse(QuestDocument.parse("town:a/b", HERBS.replace("town:herbs", "town:a/b")).problems().isEmpty());
    }
}
