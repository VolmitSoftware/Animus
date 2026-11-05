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
            assertEquals(new FileWorkSettings(8, 250), settings.files());
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
