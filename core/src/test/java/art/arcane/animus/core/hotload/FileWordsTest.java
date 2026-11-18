package art.arcane.animus.core.hotload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileWordsTest {
    static final String ELDER = """
            {"schemaVersion":1,"revision":1,"uuid":"0a1b2c3d-0000-4000-8000-000000000901","id":"elder","group":"town","name":"Elder","type":"minecraft:player","spawned":true}
            """;
    static final String GUARDS = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"guards\",\"values\":{}}";
    private static final Function<String, String> FILES = Map.of("npcs/town/elder.json", ELDER, "groups/guards.json", GUARDS)::get;

    @Test void aRolledBackUpgradeSaysTheOriginalFileCannotLoad() {
        assertEquals("Restored the original file of NPC Elder; this version cannot load it, so the current one stays in use.",
                FileWords.refusal("npcs/town/elder.json: $.schemaVersion: expected integer 2..2; kept the last valid revision 1", FILES, FileWords.After.ROLLBACK));
        assertEquals("Restored the original file of trait group guards; this version cannot load it, so the current one stays in use.",
                FileWords.refusal("groups/guards.json: $.schemaVersion: expected 2 (and 1 more); kept the last valid revision 1", FILES, FileWords.After.ROLLBACK));
    }

    @Test void aRolledBackFileWithAFieldErrorSaysWhyInWords() {
        assertEquals("Restored the earlier file of NPC Elder, but it cannot load: position y should be a coordinate within ±30000000; the current one stays in use.",
                FileWords.refusal("npcs/town/elder.json: $.position.y: expected finite coordinate within ±30000000; kept the last valid revision 4", FILES, FileWords.After.ROLLBACK));
        assertEquals("Restored the earlier file of NPC Elder, but it cannot load: UUID 0a1b2c3d-0000-4000-8000-000000000901 is already used by another file; it is not loaded.",
                FileWords.refusal("npcs/town/elder.json: $.uuid: duplicate 0a1b2c3d-0000-4000-8000-000000000901", FILES, FileWords.After.ROLLBACK));
    }

    @Test void aReloadAfterAnImportRefusesInTheHotloadStyle() {
        assertEquals("Refused NPC Elder: name should not be blank (and 2 more); the last working version stays.",
                FileWords.refusal("npcs/town/elder.json: $.name: expected nonblank string; $.type: unknown field; $.id: unknown field; kept the last valid revision 2", FILES, FileWords.After.RELOAD));
        assertEquals("Refused NPC Elder: group 1 names group missing, which does not exist; it was not loaded.",
                FileWords.refusal("npcs/town/elder.json: $.groups[0]: unknown group missing", FILES, FileWords.After.RELOAD));
        assertEquals("Refused NPC Elder: this version cannot load its file; it was not loaded.",
                FileWords.refusal("npcs/town/elder.json: $.schemaVersion: expected integer 2..2", FILES, FileWords.After.RELOAD));
    }

    @Test void aFileThatNoLongerReadsIsNamedByItsFile() {
        assertEquals("Refused NPC bob: the file ends too early at line 3, column 1; it was not loaded.",
                FileWords.refusal("npcs/town/bob.json: End of input at line 3 column 1 path $.name", FILES, FileWords.After.RELOAD));
    }

    @Test void manyRefusalsAreCappedAndTheRestCounted() {
        List<String> diagnostics = new ArrayList<>();
        for (int index = 0; index < 5; index++) diagnostics.add("npcs/town/npc" + index + ".json: $.name: expected nonblank string");
        List<String> lines = FileWords.refusals(diagnostics, FILES, FileWords.After.RELOAD);
        assertEquals(4, lines.size(), lines.toString());
        assertEquals("Refused NPC npc0: name should not be blank; it was not loaded.", lines.getFirst());
        assertEquals("2 more files were refused; the server log lists them.", lines.getLast());
    }

    @Test void aCheckNamesEachFileAndWhyWithoutSayingWhatStays() {
        assertEquals("NPC Elder: position y should be a coordinate within ±30000000.",
                FileWords.refusal("npcs/town/elder.json: $.position.y: expected finite coordinate within ±30000000; kept the last valid revision 4", FILES, FileWords.After.CHECK));
        assertEquals("Trait group guards: this version cannot load its file.",
                FileWords.refusal("groups/guards.json: $.schemaVersion: expected 2", FILES, FileWords.After.CHECK));
        List<String> diagnostics = new ArrayList<>();
        for (int index = 0; index < 12; index++) diagnostics.add("npcs/town/npc" + index + ".json: $.name: expected nonblank string");
        List<String> lines = FileWords.refusals(diagnostics, FILES, FileWords.After.CHECK);
        assertEquals(11, lines.size(), lines.toString());
        assertEquals("2 more files have problems; the server log lists them.", lines.getLast());
    }

    @Test void aFileIsNamedFromItsPathAsPlayersNameIt() {
        assertEquals("NPC Elder", FileWords.named("npcs/town/elder.json", FILES));
        assertEquals("NPC Elder", FileWords.named("state/npc-definitions/last-valid/npcs/town/elder.json", FILES), "a recovery copy names its NPC");
        assertEquals("shop dtltraders:bakery", FileWords.named("shops/dtltraders/bakery.json", FILES));
    }

    @Test void onlyTheFilesAnOperationWroteAreNamedAndTheRestCounted() {
        List<String> lines = FileWords.refusals(List.of(
                "npcs/town/fern.json: $.position.y: expected finite coordinate within ±30000000",
                "npcs/town/elder.json: $.schemaVersion: expected integer 2..2; kept the last valid revision 1",
                "npcs/town/moss.json: $.name: expected nonblank string"),
                java.util.Set.of("state/npc-definitions/last-valid/npcs/town/elder.json", "npcs/town/elder.json"), FILES, FileWords.After.ROLLBACK);
        assertEquals(List.of("Restored the original file of NPC Elder; this version cannot load it, so the current one stays in use.",
                "2 other files cannot load; /animus validate names them."), lines);
        assertEquals(List.of("1 other file cannot load; /animus validate names it."),
                FileWords.refusals(List.of("npcs/town/fern.json: $.name: expected nonblank string"), java.util.Set.of(), FILES, FileWords.After.RELOAD));
    }

    @Test void schematicProblemsReadInPlainWords() {
        String unreadable = "it is not a schematic file Animus can read";
        for (String parser : List.of("$: the root tag is not a compound (tag id 110)", "$: unknown NBT tag id 13", "$: the gzip data is damaged: Not in GZIP format",
                "$: unexpected end of data", "$: unsupported schematic format", "$: the schematic has no Blocks section", "$: structure block 3 is not a compound",
                "$: palette id 4 is used twice", "$: malformed NBT string", "$: unreadable NBT data: null", "$: the schematic cannot be read",
                "$: the block data names palette id 9, which the palette does not define", "$: the schematic Offset must hold 3 numbers, not 2"))
            assertEquals(unreadable, FileWords.schematicReason(parser), parser);
        assertEquals("it is larger than 1048576 bytes once unpacked", FileWords.schematicReason("$: decompressed size exceeds 1048576 bytes"));
        assertEquals("it is larger than 8388608 bytes", FileWords.schematicReason("$: data size exceeds 8388608 bytes"));
        assertEquals("it holds 1000000 blocks, more than the 262144 allowed", FileWords.schematicReason("$: the schematic volume 1000000 exceeds the maximum volume of 262144 blocks"));
        assertEquals("it is 600 blocks wide; each side must be from 1 to 512", FileWords.schematicReason("$: the schematic Width 600 is outside 1..512 (maximum side)"));
        assertEquals("it uses 5000 block types; at most 4096 are allowed", FileWords.schematicReason("$: the schematic palette has 5000 entries; it must have 1..4096 (maximum palette)"));
        assertEquals("it is an old MCEdit schematic, which Animus cannot read; save the build again as a .schem file",
                FileWords.schematicReason("$: Legacy MCEdit .schematic files use numeric block IDs and are not supported; save the build again as a Sponge .schem file"));
        assertEquals("it is a Sponge schematic version 4, which Animus cannot read", FileWords.schematicReason("$: unsupported Sponge schematic version 4"));
        assertEquals("the file exceeds 4194304 bytes ([hotload] maximumFileBytes)", FileWords.schematicReason("$: the file exceeds 4194304 bytes ([hotload] maximumFileBytes)"));
    }

    @Test void noRefusalShowsAPathAJsonPathARangeOrARevision() {
        for (String line : List.of(
                FileWords.refusal("npcs/town/elder.json: $.schemaVersion: expected integer 2..2; kept the last valid revision 1", FILES, FileWords.After.ROLLBACK),
                FileWords.refusal("npcs/town/elder.json: $.revision: cannot decrease; kept the last valid revision 7", FILES, FileWords.After.RELOAD),
                FileWords.refusal("groups/guards.json: $.values.tags: expected array; kept the last valid revision 1", FILES, FileWords.After.ROLLBACK)))
            assertFalse(line.contains("/") || line.contains("$") || line.contains("..") || line.contains("revision 1") || line.contains("revision 7")
                    || line.contains(".json"), line);
    }
}
