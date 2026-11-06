package art.arcane.animus.core.config;

import art.arcane.animus.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginSettingsTest {
    @TempDir Path root;
    @Test void createsTomlOnlyAndPreservesCommentsOnReload() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals("Hello from Animus", PluginSettings.load(store).statusMessage());
            assertTrue(Files.exists(root.resolve("config.toml")));
            assertFalse(Files.exists(root.resolve("config.json")));
            assertFalse(PluginSettings.load(store).packetProofEnabled());
            assertEquals(NpcRuntimeSettings.DEFAULT, PluginSettings.load(store).npcs());
            assertEquals(FileWorkSettings.DEFAULT, PluginSettings.load(store).files());
            assertEquals(ModuleSettings.DEFAULT, PluginSettings.load(store).modules());
            String source = "# Admin comment\nstatusMessage = 'Custom message'\n[future]\nkeep = true\n";
            Files.writeString(root.resolve("config.toml"), source);
            assertEquals("Custom message", PluginSettings.load(store).statusMessage());
            assertEquals(source, Files.readString(root.resolve("config.toml")));
        }
    }
    @Test void combatBudgetsAreReadFromTomlAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(CombatBudgets.DEFAULT, PluginSettings.load(store).combat());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[combat]\nenabled = false\nmaximumNpcs = 8\nhelpersPerNpc = 2\n");
            CombatBudgets budgets = PluginSettings.load(store).combat();
            assertFalse(budgets.enabled());
            assertEquals(8, budgets.maximumNpcs());
            assertEquals(2, budgets.helpersPerNpc());
            assertEquals(CombatBudgets.DEFAULT.targetScansPerTick(), budgets.targetScansPerTick());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[combat]\ntargetScansPerTick = 0\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("combat: targetScansPerTick must be within 1..64"), error.getMessage());
        }
    }
    @Test void rigBudgetsAreReadFromTomlAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(RigSettings.DEFAULT, PluginSettings.load(store).rigs());
            assertTrue(Files.readString(root.resolve("config.toml")).contains("[rigs]"));
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[rigs]\nenabled = false\nupdatesPerTick = 8\n");
            RigSettings rigs = PluginSettings.load(store).rigs();
            assertFalse(rigs.enabled());
            assertEquals(8, rigs.updatesPerTick());
            assertEquals(RigSettings.DEFAULT.maximumBones(), rigs.maximumBones());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[rigs]\nsampleTicks = 0\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("rigs: sampleTicks must be within 1..20"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[rigs]\nbones = 3\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("bones is not a rig setting"), error.getMessage());
        }
    }
    @Test void hotloadSettingsAreReadFromTomlAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(HotloadSettings.DEFAULT, PluginSettings.load(store).hotload());
            assertTrue(Files.readString(root.resolve("config.toml")).contains("[hotload]"));
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[hotload]\nenabled = false\nsound = false\nreadsPerPoll = 8\n");
            HotloadSettings hotload = PluginSettings.load(store).hotload();
            assertFalse(hotload.enabled());
            assertFalse(hotload.sound());
            assertEquals(8, hotload.readsPerPoll());
            assertEquals(HotloadSettings.DEFAULT.maximumFiles(), hotload.maximumFiles());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[hotload]\nmaximumFileBytes = 10\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("hotload: maximumFileBytes must be within 1024..16777216"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[hotload]\nding = true\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("ding is not a hotload setting"), error.getMessage());
        }
    }
    @Test void backupRotationIsReadFromFilesTableAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(200, FileWorkSettings.DEFAULT.backupsKept());
            assertEquals(14, FileWorkSettings.DEFAULT.backupDays());
            assertEquals(FileWorkSettings.DEFAULT, PluginSettings.load(store).files());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[files]\nbackupsKept = 50\nbackupDays = 3\n");
            assertEquals(new FileWorkSettings(32, 250, 50, 3), PluginSettings.load(store).files());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[files]\nbackupsKept = 0\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("backupsKept=1..100000"), error.getMessage());
        }
    }
    @Test void parsedTextNamesTheLabelAndTheField() {
        IOException error = assertThrows(IOException.class, () -> PluginSettings.parse("statusMessage = 'x'\n[npcs]\nviewDistance = 'far'\n", Path.of("config.toml")));
        assertEquals("config.toml: npcs: viewDistance must be an integer", error.getMessage());
        error = assertThrows(IOException.class, () -> PluginSettings.parse("statusMessage = 'x'\n[npcs\nviewDistance = 1\n", Path.of("config.toml")));
        assertTrue(error.getMessage().startsWith("config.toml: invalid TOML"), error.getMessage());
    }
    @Test void navigationBudgetsAreReadFromTomlAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(NavigationSettings.DEFAULT, PluginSettings.load(store).navigation());
            assertTrue(Files.readString(root.resolve("config.toml")).contains("[navigation]"));
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[navigation]\nroutesPerTick = 512\n");
            NavigationSettings navigation = PluginSettings.load(store).navigation();
            assertEquals(512, navigation.routesPerTick());
            assertEquals(NavigationSettings.DEFAULT.motionCellsPerTick(), navigation.motionCellsPerTick());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[navigation]\nroutesPerTick = 0\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("navigation: routesPerTick must be within 1..4096"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[navigation]\nmaximumRoutes = 32\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("maximumRoutes is not a navigation setting"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[navigation]\nplanningJobs = 96\ncaptureReadsPerTick = 8192\n");
            navigation = PluginSettings.load(store).navigation();
            assertEquals(96, navigation.planningJobs());
            assertEquals(8192, navigation.captureReadsPerTick());
            assertEquals(new art.arcane.animus.core.navigation.NavigationLimits(8192, 96, 8192), navigation.limits());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[navigation]\nplanningJobs = 257\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("navigation: planningJobs must be within 1..256"), error.getMessage());
        }
    }
    @Test void recordingSettingsAreReadFromTomlAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(RecordingSettings.DEFAULT, PluginSettings.load(store).recordings());
            assertEquals(new RecordingSettings(true, 2, 12000, 1048576, 4, true, 100, 100, true, "aqua", 8), RecordingSettings.DEFAULT);
            assertTrue(Files.readString(root.resolve("config.toml")).contains("[recordings]"));
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[recordings]\nenabled = false\nmaximumTicks = 72000\nghostColor = 'gold'\n");
            RecordingSettings recordings = PluginSettings.load(store).recordings();
            assertFalse(recordings.enabled());
            assertEquals(72000, recordings.maximumTicks());
            assertEquals("gold", recordings.ghostColor());
            assertEquals(RecordingSettings.DEFAULT.sampleTicks(), recordings.sampleTicks());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[recordings]\nmaximumTicks = 72001\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("recordings: maximumTicks must be within 200..72000"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[recordings]\nghostColor = 'pink'\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("recordings: ghostColor must be one of the sixteen chat colours"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[recordings]\nghostOpacity = 3\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("ghostOpacity is not a recording setting"), error.getMessage());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[recordings]\nghostGlow = 1\n");
            error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("recordings: ghostGlow must be a boolean"), error.getMessage());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"sampleTicks = 0", "sampleTicks = 21", "maximumTicks = 199", "maximumFileBytes = 65535", "maximumFileBytes = 4194305",
            "maximumSessions = 0", "maximumSessions = 17", "ghostIntervalTicks = 39", "ghostIntervalTicks = 1201", "ghostStretchTicks = 19",
            "ghostStretchTicks = 601", "maximumPreviews = 0", "maximumPreviews = 33"})
    void recordingRangesMatchTheDesign(String line) {
        IOException error = assertThrows(IOException.class, () -> PluginSettings.parse("statusMessage = 'x'\n[recordings]\n" + line + "\n", Path.of("config.toml")));
        assertTrue(error.getMessage().startsWith("config.toml: recordings: " + line.substring(0, line.indexOf(' ')) + " must be within "), error.getMessage());
    }
    @Test void recordingRangeEdgesAreAccepted() throws IOException {
        RecordingSettings low = PluginSettings.parse("statusMessage = 'x'\n[recordings]\nsampleTicks = 1\nmaximumTicks = 200\nmaximumFileBytes = 65536\n"
                + "maximumSessions = 1\nghostIntervalTicks = 40\nghostStretchTicks = 20\nmaximumPreviews = 1\n", Path.of("config.toml")).recordings();
        assertEquals(new RecordingSettings(true, 1, 200, 65536, 1, true, 40, 20, true, "aqua", 1), low);
        RecordingSettings high = PluginSettings.parse("statusMessage = 'x'\n[recordings]\nsampleTicks = 20\nmaximumTicks = 72000\nmaximumFileBytes = 4194304\n"
                + "maximumSessions = 16\nghostIntervalTicks = 1200\nghostStretchTicks = 600\nmaximumPreviews = 32\nghostEnabled = false\nghostGlow = false\n"
                + "ghostColor = 'dark_purple'\n", Path.of("config.toml")).recordings();
        assertEquals(new RecordingSettings(true, 20, 72000, 4194304, 16, false, 1200, 600, false, "dark_purple", 32), high);
    }
    @Test void buildLimitsAreReadFromTomlAndBounded() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals(BuildSettings.DEFAULT, PluginSettings.load(store).builds());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[builds]\nplacementsPerTick = 4\nmaximumUndoEntries = 0\n");
            BuildSettings builds = PluginSettings.load(store).builds();
            assertEquals(4, builds.placementsPerTick());
            assertEquals(0, builds.maximumUndoEntries());
            assertEquals(BuildSettings.DEFAULT.maximumVolume(), builds.maximumVolume());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[builds]\nmaximumVolume = 20000000\n");
            IOException error = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(error.getMessage().contains("builds: maximumVolume must be within 1..16000000"), error.getMessage());
        }
    }
    @Test void extensionSettingsAreTomlAndDoNotRewriteExistingFilesOrModuleData() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            String toml = "# Existing operator comments\nstatusMessage = 'Test'\n[files]\nmaximumOperations = 8\n"
                    + "[modules]\nenabled = false\nmaximumInvocations = 12\ninvocationTimeoutMillis = 1500\n";
            Files.writeString(root.resolve("config.toml"), toml);
            DocumentStore.atomic(store.resolve("npcs/default/future.json"), "{\"modules\":{\"missing:feature\":{\"keep\":true}}}");
            PluginSettings settings = PluginSettings.load(store);
            assertEquals(new FileWorkSettings(8, 250, 200, 14), settings.files());
            assertEquals(new ModuleSettings(false, 12, 1500, 250), settings.modules());
            assertEquals(toml, Files.readString(root.resolve("config.toml")));
            assertEquals("{\"modules\":{\"missing:feature\":{\"keep\":true}}}", store.read("npcs/default/future.json"));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"[files]\nmaximumOperations = 0", "[files]\nmaximumOperations = 129",
            "[files]\nmaximumOperations = 1.5", "[files]\ndeliveryBudgetMillis = 60001",
            "[modules]\nenabled = 'false'", "[modules]\nmaximumInvocations = 257",
            "[modules]\ninvocationTimeoutMillis = 0", "[modules]\ndeliveryBudgetMillis = '250'",
            "files = []", "modules = true"})
    void rejectsInvalidExtensionBudgetsWithoutChangingTheFile(String body) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            String original = "statusMessage = 'Test'\n" + body + "\n";
            Files.writeString(root.resolve("config.toml"), original);
            IOException failure = assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertTrue(failure.getMessage().contains(body.contains("files") ? "files" : "modules"));
            assertEquals(original, Files.readString(root.resolve("config.toml")));
        }
    }
    @Test void runtimeBudgetsUseTomlAndPreserveUnspecifiedDefaults() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            String toml = "statusMessage = 'Test'\n[npcs]\nenabled = false\nmaximumVisible = 12\nupdatesPerTick = 3\npacketsPerTick = 96\nlookUpdatesPerTick = 8\n";
            Files.writeString(root.resolve("config.toml"), toml);
            NpcRuntimeSettings settings = PluginSettings.load(store).npcs();
            assertFalse(settings.enabled());
            assertEquals(12, settings.maximumVisible());
            assertEquals(3, settings.updatesPerTick());
            assertEquals(48, settings.viewDistance());
            assertEquals(96, settings.packetsPerTick());
            assertEquals(8, settings.lookUpdatesPerTick());
            assertEquals(8, settings.pendingSpawnsPerViewer());
            assertEquals(toml, Files.readString(root.resolve("config.toml")));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"enabled = 'true'", "viewDistance = 0", "viewDistance = 257", "maximumVisible = 129", "maximumVisible = 1.5", "maximumViewers = 1025", "updatesPerTick = 0", "updatesPerTick = 65", "maximumVisible = '12'",
            "packetsPerTick = 0", "packetsPerTick = 1025", "pendingSpawnsPerViewer = 0", "pendingSpawnsPerViewer = 65", "lookUpdatesPerTick = 0", "lookUpdatesPerTick = 1025"})
    void rejectsInvalidRuntimeBudgetsBeforeStartup(String field) throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\n[npcs]\n" + field + "\n");
            assertTrue(assertThrows(IOException.class, () -> PluginSettings.load(store)).getMessage().contains("npcs"));
        }
    }
    @Test void migratesLegacyJsonLosslesslyWithoutRemovingTheOriginal() throws Exception {
        String original = "{\"statusMessage\":\"He said \\\"hello\\\"\\nAgain\",\"unknown\":{\"preserved\":true}}";
        Files.writeString(root.resolve("config.json"), original);
        try (DocumentStore store = new DocumentStore(root)) {
            assertEquals("He said \"hello\"\nAgain", PluginSettings.load(store).statusMessage());
            assertEquals(original, store.read("config.json"));
            assertEquals(original, store.read("backups/config-" + JsonDocuments.hash(original) + ".json"));
            assertTrue(store.read("config.toml").contains("preserved"));
            Files.writeString(root.resolve("config.json"), "invalid legacy now");
            assertEquals("He said \"hello\"\nAgain", PluginSettings.load(store).statusMessage());
        }
    }
    @Test void proofRequiresExplicitBooleanOptIn() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\npacketProofEnabled = true\n");
            assertTrue(PluginSettings.load(store).packetProofEnabled());
            Files.writeString(root.resolve("config.toml"), "statusMessage = 'Test'\npacketProofEnabled = 'true'\n");
            assertThrows(IOException.class, () -> PluginSettings.load(store));
        }
    }
    @Test void refusesLossyLegacyNullsAndRetainsTheSource() throws Exception {
        String original = "{\"statusMessage\":\"Valid\",\"future\":null}";
        Files.writeString(root.resolve("config.json"), original);
        try (DocumentStore store = new DocumentStore(root)) {
            assertThrows(IOException.class, () -> PluginSettings.load(store));
            assertFalse(Files.exists(root.resolve("config.toml")));
            assertEquals(original, store.read("config.json"));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "statusMessage = 123", "statusMessage = true", "statusMessage = []", "statusMessage = ''", "statusMessage = '  '", "statusMessage = 'a'\nstatusMessage = 'b'", "statusMessage = \"broken", "{\"statusMessage\":\"json is not toml\"}"})
    void invalidTomlDoesNotFallBackOrOverwrite(String original) throws Exception {
        Files.writeString(root.resolve("config.toml"), original);
        Files.writeString(root.resolve("config.json"), "{\"statusMessage\":\"Valid legacy\"}");
        try (DocumentStore store = new DocumentStore(root)) {
            assertTrue(assertThrows(IOException.class, () -> PluginSettings.load(store)).getMessage().contains("config.toml"));
            assertEquals(original, store.read("config.toml"));
        }
    }
}
