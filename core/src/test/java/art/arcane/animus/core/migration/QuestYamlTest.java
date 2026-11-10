package art.arcane.animus.core.migration;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Quest files keep every byte except the Citizens NPC references, which point at the imported Animus NPCs. */
class QuestYamlTest {
    private static final UUID FOUR = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID SEVEN = UUID.fromString("00000000-0000-4000-8000-000000000007");
    private static final UUID NINE = UUID.fromString("00000000-0000-4000-8000-000000000009");
    private static final UUID GUARD = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final NpcLinks LINKS = NpcLinks.of(List.of(
            new NpcLinks.Link("citizens-4", FOUR, 4, "citizens-4"), new NpcLinks.Link("citizens-7", SEVEN, 7, "citizens-7"),
            new NpcLinks.Link("citizens-9", NINE, 9, "citizens-9"), new NpcLinks.Link("guard", GUARD, -1, "guard")));

    @Test void questsLegacyIdsBecomeUuidKeysAndEverythingElseStays() {
        String before = String.join("\n",
                "quests:",
                "  custom1:",
                "    name: Bread run",
                "    npc-giver-id: 4",
                "    stages:",
                "      ordered:",
                "        '1':",
                "          items-to-deliver:",
                "          - ==: org.bukkit.inventory.ItemStack",
                "            id: minecraft:bread",
                "            count: 1",
                "          npc-delivery-ids:",
                "          - 7",
                "          npc-ids-to-talk-to:",
                "          - 4",
                "          - '7'",
                "        '2':",
                "          npc-ids-to-kill: [9]",
                "          npc-kill-amounts: [1]",
                "  custom2:",
                "    npc-giver-uuid: " + FOUR,
                "",
                "");
        String after = String.join("\n",
                "quests:",
                "  custom1:",
                "    name: Bread run",
                "    npc-giver-uuid: '" + FOUR + "'",
                "    stages:",
                "      ordered:",
                "        '1':",
                "          items-to-deliver:",
                "          - ==: org.bukkit.inventory.ItemStack",
                "            id: minecraft:bread",
                "            count: 1",
                "          npc-delivery-uuids:",
                "          - '" + SEVEN + "'",
                "          npc-uuids-to-talk-to:",
                "          - '" + FOUR + "'",
                "          - '" + SEVEN + "'",
                "        '2':",
                "          npc-uuids-to-kill: ['" + NINE + "']",
                "          npc-kill-amounts: [1]",
                "  custom2:",
                "    npc-giver-uuid: " + FOUR,
                "",
                "");
        QuestYaml.Rewrite rewrite = QuestYaml.quests(before, LINKS);
        assertEquals(after, rewrite.text());
        assertEquals(4, rewrite.changes().size(), rewrite.changes().toString());
        assertEquals(1, rewrite.linked());
        assertEquals(List.of(), rewrite.problems());
        assertTrue(rewrite.changes().getFirst().startsWith("line 4: npc-giver-id 4 -> citizens-4"), rewrite.changes().getFirst());
    }

    @Test void questsKeepAKeyWhoseNpcsAreNotAllImported() {
        String before = "quests:\n  a:\n    npc-giver-id: 99\n    stages:\n      ordered:\n        '1':\n          npc-ids-to-talk-to:\n          - 4\n          - 12\n";
        QuestYaml.Rewrite rewrite = QuestYaml.quests(before, LINKS);
        assertEquals(before, rewrite.text());
        assertEquals(List.of(), rewrite.changes());
        assertEquals(List.of("line 3: npc-giver-id names Citizens NPC 99, which no imported NPC has", "line 7: npc-ids-to-talk-to names Citizens NPC 12, which no imported NPC has"), rewrite.problems());
    }

    @Test void questsReportUuidsNoAnimusNpcHas() {
        UUID stray = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
        String before = "quests:\n  a:\n    npc-giver-uuid: '" + stray + "'\n    stages:\n      ordered:\n        '1':\n          npc-uuids-to-talk-to:\n          - " + FOUR + "\n";
        QuestYaml.Rewrite rewrite = QuestYaml.quests(before, LINKS);
        assertEquals(before, rewrite.text());
        assertEquals(1, rewrite.linked());
        assertEquals(List.of("line 3: npc-giver-uuid " + stray + " is not an Animus NPC"), rewrite.problems());
    }

    @Test void questsConditionsRideNpcIsLinked() {
        String before = "conditions:\n  riding:\n    ride-npc:\n    - 9\n";
        assertEquals("conditions:\n  riding:\n    ride-npc-uuid:\n    - '" + NINE + "'\n", QuestYaml.quests(before, LINKS).text());
    }

    @Test void beautyQuestsNpcIdsAndKillTargetsMoveToTheAnimusFactory() {
        String before = String.join("\r\n",
                "id: 3",
                "starterNPC: citizens#4",
                "manager:",
                "  branches:",
                "    '0':",
                "      stages:",
                "        '0':",
                "          stageType: NPC",
                "          npcID: 'citizens#7'",
                "        '1':",
                "          stageType: MOBS",
                "          mobs:",
                "          - factoryName: citizensNPC",
                "            value: '9'",
                "            name: Bandit",
                "          - value: ZOMBIE",
                "            factoryName: bukkitEntity",
                "        '2':",
                "          npcID: znpcsplus#5",
                "        '3':",
                "          npcID: animus#guard",
                "        '4':",
                "          npcID: 4 # legacy",
                "");
        String after = String.join("\r\n",
                "id: 3",
                "starterNPC: animus#citizens-4",
                "manager:",
                "  branches:",
                "    '0':",
                "      stages:",
                "        '0':",
                "          stageType: NPC",
                "          npcID: animus#citizens-7",
                "        '1':",
                "          stageType: MOBS",
                "          mobs:",
                "          - factoryName: animusNPC",
                "            value: citizens-9",
                "            name: Bandit",
                "          - value: ZOMBIE",
                "            factoryName: bukkitEntity",
                "        '2':",
                "          npcID: znpcsplus#5",
                "        '3':",
                "          npcID: animus#guard",
                "        '4':",
                "          npcID: animus#citizens-4",
                "");
        QuestYaml.Rewrite rewrite = QuestYaml.beautyQuests(before, LINKS);
        assertEquals(after, rewrite.text());
        assertEquals(4, rewrite.changes().size(), rewrite.changes().toString());
        assertEquals(1, rewrite.linked());
        assertEquals(List.of(), rewrite.problems());
    }

    @Test void beautyQuestsKillTargetsSavedAsCountedObjectsAreLinked() {
        String before = "      objects:\n        5d2c6f0e-0000-4000-8000-000000000001:\n          amount: 2\n          object:\n            value: '9'\n            factoryName: citizensNPC\n            name: Bandit\n";
        String after = "      objects:\n        5d2c6f0e-0000-4000-8000-000000000001:\n          amount: 2\n          object:\n            value: citizens-9\n            factoryName: animusNPC\n            name: Bandit\n";
        QuestYaml.Rewrite rewrite = QuestYaml.beautyQuests(before, LINKS);
        assertEquals(after, rewrite.text());
        assertEquals(1, rewrite.references());
    }

    @Test void beautyQuestsReportsMissingNpcsAndLeavesThem() {
        String before = "starterNPC: citizens#12\nmobs:\n- factoryName: citizensNPC\n  value: '13'\nnpcID: animus#gone\n";
        QuestYaml.Rewrite rewrite = QuestYaml.beautyQuests(before, LINKS);
        assertEquals(before, rewrite.text());
        assertEquals(List.of("line 1: starterNPC names Citizens NPC 12, which no imported NPC has",
                "line 3: kill target names Citizens NPC 13, which no imported NPC has",
                "line 5: npcID animus#gone is not an Animus NPC"), rewrite.problems());
    }

    @Test void linksIgnoreNpcsThatAreNotFromCitizens() {
        NpcLinks links = NpcLinks.of(List.of(new NpcLinks.Link("guard", GUARD, -1, "guard")));
        assertEquals(0, links.size());
        assertTrue(links.imported(-1).isEmpty());
        assertTrue(links.has(GUARD));
    }
}
