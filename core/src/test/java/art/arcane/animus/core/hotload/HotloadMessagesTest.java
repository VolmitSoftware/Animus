package art.arcane.animus.core.hotload;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HotloadMessagesTest {
    static final String BEHAVIOR = """
            {"schemaVersion":1,"revision":1,"id":"int3:greet","entry":"main","routines":{"main":{"type":"capability","capability":"animus:look","arguments":{"target":"player"}}}}
            """;
    static final String NPC = HotloadServiceTest.NPC;
    static final String CONFIG = "packetProofEnabled = false\n[npcs]\nenabled = true\nviewDistance = 48\nmaximumVisible = 64\n[hotload]\nsound = true\n";

    private static HotloadTargets.Target target(String path) { return HotloadTargets.of(path).orElseThrow(); }

    private static ChatLine applied(String path, String before, String after, Predicate<String> restart) {
        return HotloadMessages.applied(target(path), before, after, HotloadDiff.diff(path, before, after), restart);
    }

    private static String shown(String path, String before, String after) { return applied(path, before, after, (String key) -> false).visible(); }

    private static String refused(String path, String known, String content, String error) {
        return HotloadMessages.refused(target(path), known, content, error).visible();
    }

    @Test void aNewBehaviorFileIsOneSentenceWithItsId() {
        assertEquals("[Animus]: Added behavior int3:greet.", shown("behaviors/int3/greet.json", null, BEHAVIOR));
    }

    @Test void aChangedNpcIsNamedByNameWithItsChangedFieldsInWords() {
        String after = NPC.replace("\"y\":64", "\"y\":70").replace("\"spawned\":false", "\"spawned\":true");
        assertEquals("[Animus]: Changed NPC Guard: position and spawned.", shown("npcs/town/guard.json", NPC, after));
    }

    @Test void aRenamedNpcIsNamedByItsNewName() {
        assertEquals("[Animus]: Changed NPC Captain: name.", shown("npcs/town/guard.json", NPC, NPC.replace("\"Guard\"", "\"Captain\"")));
    }

    @Test void colourCodesInAnNpcNameAreNotShown() {
        assertEquals("[Animus]: Added NPC Guard.", shown("npcs/town/guard.json", null, NPC.replace("\"Guard\"", "\"&6<bold>Guard\"")));
    }

    @Test void manyChangedFieldsBecomeACount() {
        String after = NPC.replace("\"Guard\"", "\"Captain\"").replace("\"y\":64", "\"y\":70").replace("\"spawned\":false", "\"spawned\":true")
                .replace("\"minecraft:player\"", "\"minecraft:villager\"");
        assertEquals("[Animus]: Changed NPC Captain: 4 fields.", shown("npcs/town/guard.json", NPC, after));
    }

    @Test void revisionIsLeftOutBesideARealChange() {
        String after = BEHAVIOR.replace("\"revision\":1", "\"revision\":2").replace("\"player\"", "\"nearest\"");
        assertEquals("[Animus]: Changed behavior int3:greet: routines.", shown("behaviors/int3/greet.json", BEHAVIOR, after));
        assertEquals("[Animus]: Changed behavior int3:greet: revision.", shown("behaviors/int3/greet.json", BEHAVIOR, BEHAVIOR.replace("\"revision\":1", "\"revision\":2")));
    }

    @Test void conversationIsCalledByItsOwnWord() {
        String after = NPC.replace("\"spawned\":false", "\"spawned\":false,\"dialogue\":{\"start\":\"hello\"}");
        assertEquals("[Animus]: Changed NPC Guard: conversation.", shown("npcs/town/guard.json", NPC, after));
    }

    @Test void aDeletedNpcIsNamedFromItsLastVersion() {
        assertEquals("[Animus]: Removed NPC Guard.", shown("npcs/town/guard.json", NPC, null));
        assertEquals("[Animus]: Removed behavior int3:greet.", shown("behaviors/int3/greet.json", BEHAVIOR, null));
    }

    @Test void configIsSettingsWithItsKeysInWords() {
        String after = CONFIG.replace("viewDistance = 48", "viewDistance = 40").replace("packetProofEnabled = false", "packetProofEnabled = true");
        assertEquals("[Animus]: Changed Settings: NPCs view distance and packet proof enabled.", shown("config.toml", CONFIG, after));
    }

    @Test void aListSettingCountsOnceForAllItsEntries() {
        String before = "[behaviors.actions]\ncommandNamespaces = []\n", after = "[behaviors.actions]\ncommandNamespaces = [\"town\", \"market\"]\n";
        assertEquals("[Animus]: Changed Settings: behaviors actions command namespaces.", shown("config.toml", before, after));
    }

    @Test void aRestartOnlyKeySaysItNeedsARestart() {
        String after = CONFIG.replace("enabled = true", "enabled = false");
        assertEquals("[Animus]: Changed Settings: NPCs enabled (needs a restart).",
                applied("config.toml", CONFIG, after, (String key) -> SettingsLiveness.restartRequired(key, false)).visible());
        assertEquals("[Animus]: Changed Settings: NPCs enabled.", applied("config.toml", CONFIG, after, (String key) -> SettingsLiveness.restartRequired(key, true)).visible());
    }

    @Test void manyChangedSettingsBecomeACountThatStillNamesARestart() {
        String after = CONFIG.replace("enabled = true", "enabled = false").replace("viewDistance = 48", "viewDistance = 40")
                .replace("maximumVisible = 64", "maximumVisible = 32").replace("sound = true", "sound = false");
        assertEquals("[Animus]: Changed 4 settings; NPCs enabled needs a restart.",
                applied("config.toml", CONFIG, after, (String key) -> SettingsLiveness.restartRequired(key, false)).visible());
        assertEquals("[Animus]: Changed 4 settings.", shown("config.toml", CONFIG, after));
    }

    @Test void brokenJsonNamesTheLineAndColumnAndKeepsTheLastVersion() {
        assertEquals("[Animus]: Refused behavior int3:greet: the file ends too early at line 1, column 146; the last working version stays.",
                refused("behaviors/int3/greet.json", BEHAVIOR, "{\"id\":", "End of input at line 1 column 146 path $.routines"));
        assertEquals("[Animus]: Refused NPC Guard: a quote is not closed at line 1, column 41; the last working version stays.",
                refused("npcs/town/guard.json", NPC, NPC.substring(0, 40), "Unterminated string at line 1 column 41 path $.uuid"));
    }

    @Test void aFieldErrorNamesTheFieldInWords() {
        assertEquals("[Animus]: Refused behavior int3:greet: routine main step 1 ticks should be a whole number from 1 to 72000; the last working version stays.",
                refused("behaviors/int3/greet.json", BEHAVIOR, BEHAVIOR, "$.routines.main.steps[0].ticks: expected integer 1..72000"));
        assertEquals("[Animus]: Refused NPC Guard: position y should be a coordinate within ±30000000; the last working version stays.",
                refused("npcs/town/guard.json", NPC, NPC.replace("\"y\":64", "\"y\":\"high\""), "$.position.y: expected finite coordinate within ±30000000"));
        assertEquals("[Animus]: Refused NPC Guard: colour is not a known field (and 2 more); the last working version stays.",
                refused("npcs/town/guard.json", NPC, NPC, "$.colour: unknown field (and 2 more)"));
        assertEquals("[Animus]: Refused behavior int3:greet: routine main type should not be blank; the last working version stays.",
                refused("behaviors/int3/greet.json", BEHAVIOR, BEHAVIOR, "$.routines.main.type: expected nonblank string"));
    }

    @Test void aReloadRefusalDoesNotRepeatTheKeptRevision() {
        assertEquals("[Animus]: Refused NPC Guard: revision cannot decrease; the last working version stays.",
                refused("npcs/town/guard.json", NPC, NPC, "$.revision: cannot decrease; kept the last valid revision 3"));
    }

    @Test void aNewFileThatFailsSaysItWasNotLoaded() {
        assertEquals("[Animus]: Refused behavior int3:greet: the file is not valid JSON at line 1, column 3; it was not loaded.",
                refused("behaviors/int3/greet.json", null, "{,", "Expected name at line 1 column 3 path $."));
    }

    @Test void aConfigErrorNamesItsLineAndKeepsTheRunningSettings() {
        assertEquals("[Animus]: Refused Settings: line 2, NPCs view distance must be from 8 to 256, not 300; the running settings stay.",
                refused("config.toml", CONFIG, CONFIG, "line 2: npcs: viewDistance must be within 8..256, not 300"));
        assertEquals("[Animus]: Refused Settings: line 1, column 1, the file is not valid TOML; the running settings stay.",
                refused("config.toml", CONFIG, "[npcs\n", "line 1, column 1: invalid TOML: Invalid key near [npcs"));
    }

    @Test void aSchematicThatCannotBeReadSaysBuildsCannotUseIt() {
        assertEquals("[Animus]: Refused schematic castle.schem: it is not a schematic file Animus can read; builds cannot use it until it is fixed.",
                refused("schematics/castle.schem", null, "{}", "$: the root tag is not a compound (tag id 110)"));
        assertEquals("[Animus]: Refused schematic castle.schem: it holds 1000000 blocks, more than the 262144 allowed; builds cannot use it until it is fixed.",
                refused("schematics/castle.schem", null, "{}", "$: the schematic volume 1000000 exceeds the maximum volume of 262144 blocks"));
    }

    @Test void anUpgradedNpcOffersItsRollback() {
        ChatLine line = HotloadMessages.upgraded(target("npcs/town/guard.json"), NPC, "0f6c1b52-9a7e-4a7b-8d0b-1f2e3d4c5b6a");
        assertEquals("[Animus]: Upgraded NPC Guard to the current file format. [Rollback]", line.visible());
        assertEquals("[Animus]: Upgraded NPC Guard to the current file format.", line.toString());
        String json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(line.component());
        assertTrue(json.contains("/animus rollback 0f6c1b52-9a7e-4a7b-8d0b-1f2e3d4c5b6a"), json);
    }

    @Test void noLineShowsAFilePathAJsonPathAMissingMarkerOrAnArrow() {
        List<String> lines = new ArrayList<>(List.of(
                shown("behaviors/int3/greet.json", null, BEHAVIOR),
                shown("npcs/town/guard.json", NPC, NPC.replace("\"y\":64", "\"y\":70")),
                shown("npcs/town/guard.json", NPC, null),
                shown("config.toml", CONFIG, CONFIG.replace("sound = true", "sound = false")),
                refused("npcs/town/guard.json", NPC, NPC, "$.position.y: expected finite coordinate within ±30000000"),
                refused("behaviors/int3/greet.json", BEHAVIOR, "{", "End of input at line 1 column 2 path $."),
                HotloadMessages.upgraded(target("groups/guards.json"), "{}", "0f6c1b52-9a7e-4a7b-8d0b-1f2e3d4c5b6a").toString()));
        for (String line : lines) {
            String text = line.substring("[Animus]: ".length());
            assertFalse(text.contains("/") || text.contains("$") || text.contains("<missing>") || text.contains("->") || text.contains(".json") || text.contains("["), line);
            assertEquals(1, text.split("\\. ").length, "one sentence: " + line);
        }
    }

    @Test void theServerLogKeepsEachChangedKeyWithItsValues() {
        String after = NPC.replace("\"Guard\"", "\"Captain\"").replace("\"y\":64", "\"y\":70");
        assertEquals("npcs/town/guard.json (2 changes): name \"Guard\" -> \"Captain\"; position.y 64 -> 70",
                HotloadMessages.logged("npcs/town/guard.json", NPC, after, HotloadDiff.diff("npcs/town/guard.json", NPC, after)));
        assertEquals("behaviors/int3/greet.json (new file)", HotloadMessages.logged("behaviors/int3/greet.json", null, BEHAVIOR,
                HotloadDiff.diff("behaviors/int3/greet.json", null, BEHAVIOR)));
        assertEquals("behaviors/int3/greet.json (removed)", HotloadMessages.logged("behaviors/int3/greet.json", BEHAVIOR, null,
                HotloadDiff.diff("behaviors/int3/greet.json", BEHAVIOR, null)));
        List<HotloadDiff.Change> many = new ArrayList<>();
        for (int index = 0; index < 15; index++) many.add(new HotloadDiff.Change("key" + index, "1", "2"));
        String logged = HotloadMessages.logged("config.toml", "a = 1", "a = 2", many);
        assertTrue(logged.startsWith("config.toml (15 changes): key0 1 -> 2; "), logged);
        assertTrue(logged.endsWith("; +5 more"), logged);
    }

    @Test void linesUseTheBrandPrefixAndSemanticColours() {
        String added = applied("behaviors/int3/greet.json", null, BEHAVIOR, (String key) -> false).console();
        String failed = HotloadMessages.refused(target("behaviors/int3/greet.json"), BEHAVIOR, BEHAVIOR, "$.id: unknown field").console();
        assertEquals("§a", added.substring(RichText.colorize(art.arcane.animus.core.text.Palette.PREFIX).length()).substring(0, 2), added);
        assertEquals("§c", failed.substring(RichText.colorize(art.arcane.animus.core.text.Palette.PREFIX).length()).substring(0, 2), failed);
    }
}
