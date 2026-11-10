package art.arcane.animus.core.migration;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** BetonQuest package files keep every byte except the Citizens NPC references, which name the imported Animus NPCs. */
class BetonQuestLinksTest {
    private static final NpcLinks LINKS = NpcLinks.of(List.of(new NpcLinks.Link("citizens-3", java.util.UUID.randomUUID(), 3, "Innkeeper"), new NpcLinks.Link("citizens-8", java.util.UUID.randomUUID(), 8, "Bandit")));

    @Test void npcsSectionPointsAtImportedNpcsAndKeepsComments() {
        String before = String.join("\n",
                "# Town package",
                "npcs:",
                "  innkeeper: \"citizens 3\"   # the inn",
                "  bandit: Citizens 8",
                "  guard: citizens Bandit byName",
                "  ghost: citizens 99",
                "  fancy: FancyNpcs merchant",
                "conversations:",
                "  innkeeper: talk",
                "");
        BetonQuestLinks.Result result = BetonQuestLinks.rewrite("town/package.yml", before, LINKS, Set.of());
        assertEquals(String.join("\n",
                "# Town package",
                "npcs:",
                "  innkeeper: \"animus citizens-3\"   # the inn",
                "  bandit: animus citizens-8",
                "  guard: animus Bandit byName",
                "  ghost: citizens 99",
                "  fancy: FancyNpcs merchant",
                "conversations:",
                "  innkeeper: talk",
                ""), result.text());
        assertEquals(Set.of("innkeeper", "bandit", "guard"), result.linkedNpcs());
        assertEquals(3, result.changes().size());
        assertTrue(result.notes().stream().anyMatch((String note) -> note.contains("npcs.ghost") && note.contains("Citizens NPC 99")), result.notes().toString());
    }

    @Test void npcKillObjectivesOnLinkedNpcsBecomeAnimusKills_citizensOnlyActionsAreReported() {
        String before = String.join("\n",
                "objectives:",
                "  killBandit: npckill bandit amount:2 actions:reward",
                "  killOther: \"npckill elsewhere\"",
                "  talk: npcinteract innkeeper",
                "actions:",
                "  walk: npcmove innkeeper home wait:20",
                "");
        BetonQuestLinks.Result result = BetonQuestLinks.rewrite("town/objectives.yml", before, LINKS, Set.of("bandit", "innkeeper"));
        assertEquals(String.join("\n",
                "objectives:",
                "  killBandit: animusnpckill bandit amount:2 actions:reward",
                "  killOther: \"npckill elsewhere\"",
                "  talk: npcinteract innkeeper",
                "actions:",
                "  walk: npcmove innkeeper home wait:20",
                ""), result.text());
        assertTrue(result.notes().stream().anyMatch((String note) -> note.contains("actions.walk") && note.contains("npcmove")), result.notes().toString());
        assertTrue(result.notes().stream().anyMatch((String note) -> note.contains("objectives.killOther")), result.notes().toString());
    }

    @Test void packageQualifiedReferenceUsesItsLastPart() {
        BetonQuestLinks.Result result = BetonQuestLinks.rewrite("quests.yml", "objectives:\n  kill: npckill town>bandit\n", LINKS, Set.of("bandit"));
        assertEquals("objectives:\n  kill: animusnpckill town>bandit\n", result.text());
    }

    @Test void flowStyleNpcsAreLeftForReview() {
        BetonQuestLinks.Result result = BetonQuestLinks.rewrite("p.yml", "npcs: {innkeeper: citizens 3}\n", LINKS, Set.of());
        assertEquals("npcs: {innkeeper: citizens 3}\n", result.text());
        assertTrue(result.notes().getFirst().contains("one line"), result.notes().toString());
    }

    @Test void unchangedFileIsReturnedAsIs() {
        String text = "npcs:\r\n  shop: FancyNpcs merchant\r\n";
        BetonQuestLinks.Result result = BetonQuestLinks.rewrite("p.yml", text, LINKS, Set.of());
        assertSame(text, result.text());
        assertTrue(result.changes().isEmpty());
    }

    @Test void byNameMatchesAColouredImportedNameTheWayTheRuntimeDoes() {
        NpcLinks links = NpcLinks.of(List.of(new NpcLinks.Link("citizens-5", java.util.UUID.randomUUID(), 5, "&6Old &lBaker")));
        BetonQuestLinks.Result result = BetonQuestLinks.rewrite("town/package.yml", "npcs:\n  baker: citizens OldBaker byName\n  plain: citizens oldbaker byName\n", NpcLinks.of(List.of(
                new NpcLinks.Link("citizens-6", java.util.UUID.randomUUID(), 6, "&6Old&lBaker"))), Set.of());
        assertEquals("npcs:\n  baker: animus OldBaker byName\n  plain: animus oldbaker byName\n", result.text());
        assertTrue(links.named("Old Baker"));
    }
}
