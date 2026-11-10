package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Relinking another plugin's files: preview writes only a report, apply keeps the originals, rollback puts them back. */
class LinkRemapTest {
    private static final java.util.UUID INNKEEPER = java.util.UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final java.util.UUID FOUR = java.util.UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final NpcLinks LINKS = NpcLinks.of(List.of(new NpcLinks.Link("citizens-3", INNKEEPER, 3, "Innkeeper"), new NpcLinks.Link("citizens-4", FOUR, 4, "Baker")));
    private static final String PACKAGE = "npcs:\n  innkeeper: citizens 3\nobjectives:\n  kill: npckill innkeeper\n";

    @TempDir Path plugins;
    @TempDir Path data;
    private DocumentStore store;
    private Path quest;

    @BeforeEach void files() throws IOException {
        store = new DocumentStore(data);
        quest = plugins.resolve("BetonQuest/QuestPackages/town/package.yml");
        Files.createDirectories(quest.getParent());
        Files.writeString(quest, PACKAGE);
    }

    @AfterEach void close() throws IOException { store.close(); }

    private LinkRemap remap(LinkRemap.Plugin plugin) { return new LinkRemap(store, plugins, plugin, LINKS); }

    @Test void previewChangesNothingButTheReport() throws IOException {
        List<String> lines = remap(LinkRemap.Plugin.BETONQUEST).preview();
        assertEquals(PACKAGE, Files.readString(quest));
        assertTrue(lines.getFirst().contains("2 references in 1 file"), lines.toString());
        assertTrue(Files.isRegularFile(store.resolve("imports/links/betonquest/report.json")));
    }

    @Test void applyThenRollbackRestoresTheOriginal() throws IOException {
        List<String> applied = remap(LinkRemap.Plugin.BETONQUEST).apply();
        assertTrue(applied.getFirst().startsWith("Relinked 2 references in 1 file"), applied.toString());
        assertEquals("npcs:\n  innkeeper: animus citizens-3\nobjectives:\n  kill: animusnpckill innkeeper\n", Files.readString(quest));
        IOException twice = assertThrows(IOException.class, () -> remap(LinkRemap.Plugin.BETONQUEST).apply());
        assertTrue(twice.getMessage().contains("rollback"), twice.getMessage());

        List<String> rolled = remap(LinkRemap.Plugin.BETONQUEST).rollback();
        assertEquals(PACKAGE, Files.readString(quest));
        assertTrue(rolled.getFirst().startsWith("Restored 1 file"), rolled.toString());
        assertFalse(Files.exists(store.resolve("imports/links/betonquest/applied.json")));
    }

    @Test void rollbackRefusesAFileEditedSinceApply() throws IOException {
        remap(LinkRemap.Plugin.BETONQUEST).apply();
        Files.writeString(quest, "npcs:\n  innkeeper: animus citizens-3\n# edited by hand\n");
        IOException refused = assertThrows(IOException.class, () -> remap(LinkRemap.Plugin.BETONQUEST).rollback());
        assertTrue(refused.getMessage().contains("town/package.yml"), refused.getMessage());
        assertTrue(Files.readString(quest).contains("edited by hand"));
    }

    @Test void relinksOnlyTheSupportedQuestPlugins() {
        assertEquals(List.of("quests", "beautyquests", "betonquest"), java.util.Arrays.stream(LinkRemap.Plugin.values()).map(LinkRemap.Plugin::key).toList());
    }

    @Test void missingPluginFolderIsExplained() {
        IOException missing = assertThrows(IOException.class, () -> new LinkRemap(store, plugins.resolve("none"), LinkRemap.Plugin.BETONQUEST, LINKS).preview());
        assertTrue(missing.getMessage().contains("BetonQuest/QuestPackages"), missing.getMessage());
    }

    private static final String QUESTS = "quests:\n  bread:\n    name: Bread\n    npc-giver-id: 4\n";
    private static final String BEAUTY = "id: 0\nstarterNPC: citizens#4\nmanager:\n  stages:\n    '0':\n      npcID: citizens#12\n";

    /** Quests and BeautyQuests go through the same preview, apply and rollback as every other quest plugin. */
    @Test void questsAndBeautyQuestsRelinkThroughTheSameSteps() throws IOException {
        Path quests = plugins.resolve("Quests/storage/quests.yml"), beauty = plugins.resolve("BeautyQuests/quests/0.yml");
        Files.createDirectories(quests.getParent());
        Files.createDirectories(beauty.getParent());
        Files.writeString(quests, QUESTS);
        Files.writeString(beauty, BEAUTY);
        List<String> preview = remap(LinkRemap.Plugin.QUESTS).preview();
        assertTrue(preview.getFirst().contains("1 reference in 1 file"), preview.toString());
        assertEquals(QUESTS, Files.readString(quests));
        remap(LinkRemap.Plugin.QUESTS).apply();
        assertTrue(Files.readString(quests).contains("npc-giver-uuid: '" + FOUR + "'"), Files.readString(quests));
        assertEquals(BEAUTY, Files.readString(beauty), "each plugin links only its own files");
        List<String> beautyLines = remap(LinkRemap.Plugin.BEAUTYQUESTS).apply();
        assertTrue(Files.readString(beauty).contains("starterNPC: animus#citizens-4"));
        assertTrue(beautyLines.stream().anyMatch((String line) -> line.contains("Citizens NPC 12")), "a reference no imported NPC answers is reported: " + beautyLines);
        remap(LinkRemap.Plugin.QUESTS).rollback();
        assertEquals(QUESTS, Files.readString(quests));
        assertTrue(Files.readString(beauty).contains("animus#citizens-4"), "rolling back Quests keeps the BeautyQuests link");
        remap(LinkRemap.Plugin.BEAUTYQUESTS).rollback();
        assertEquals(BEAUTY, Files.readString(beauty));
    }

    @Test void aQuestsFileQuestsHasNotMovedIntoStorageIsRelinkedToo() throws IOException {
        Path root = plugins.resolve("Quests/quests.yml");
        Files.createDirectories(root.getParent());
        Files.writeString(root, QUESTS);
        assertTrue(remap(LinkRemap.Plugin.QUESTS).preview().getFirst().contains("1 reference in 1 file"));
    }

    @Test void linksIndexEveryNpcAndTheImportedOnesByCitizensId() throws Exception {
        java.util.UUID made = java.util.UUID.fromString("00000000-0000-4000-8000-000000000009");
        art.arcane.animus.core.npc.NpcDefinition imported = art.arcane.animus.core.npc.NpcDefinition.parse(("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"%s\",\"id\":\"citizens-4\",\"group\":\"citizens\",\"name\":\"Baker\","
                + "\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},"
                + "\"migration\":{\"importId\":\"a\",\"citizensId\":4}}").formatted(FOUR));
        art.arcane.animus.core.npc.NpcDefinition guard = art.arcane.animus.core.npc.NpcDefinition.parse(("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"%s\",\"id\":\"guard\",\"group\":\"\",\"name\":\"Guard\","
                + "\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}").formatted(made));
        NpcLinks links = NpcLinks.from(List.of(imported, guard));
        assertEquals(java.util.Optional.of("citizens-4"), links.citizens(4));
        assertTrue(links.has(made) && links.key("guard").isPresent(), "an NPC made in Animus is a link target too");
        assertEquals(1, links.size(), "only imported NPCs count as relinked");
    }
}
