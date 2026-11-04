package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExamplePacksTest {
    private static final List<String> PACKS = List.of("dialogue", "greeter", "patrol", "personal-guard", "quest", "shop", "town-guard");
    private static OfflineCapabilities capabilities;
    @BeforeAll static void start() { capabilities = new OfflineCapabilities(); }
    @AfterAll static void stop() { capabilities.close(); }

    private static BehaviorCatalog.CapabilityResolver granting(boolean commands) {
        BehaviorCatalog.CapabilityResolver offline = capabilities.resolver();
        return new BehaviorCatalog.CapabilityResolver() {
            @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) { return offline.resolve(requirement); }
            @Override public List<art.arcane.animus.api.Diagnostic> validate(BehaviorCatalog.Requirement requirement, String arguments) { return offline.validate(requirement, arguments); }
            @Override public BehaviorCatalog.Capability authorize(BehaviorCatalog.Requirement requirement, String behavior) {
                if (!requirement.key().equals(new Capabilities.Key("animus", "command")) || commands) return BehaviorCatalog.Capability.present();
                return new BehaviorCatalog.Capability(false, "privileged-capability-not-granted");
            }
        };
    }

    @Test void theSevenPacksShip() throws Exception {
        assertEquals(PACKS, RepositoryFiles.packDirectories().stream().map(path -> path.getFileName().toString()).toList());
    }

    @Test void everyPackCompilesInARealCatalogWithBuiltInsOnly() throws Exception {
        for (Path pack : RepositoryFiles.packDirectories()) {
            BehaviorCatalog catalog = new BehaviorCatalog(pack, BehaviorCatalog.Limits.defaults(), granting(true), BehaviorCheckpoints.disabled());
            assertTrue(catalog.reload(0, Set.of()).published());
            assertFalse(catalog.snapshot().entries().isEmpty(), pack.toString());
            for (BehaviorCatalog.Entry entry : catalog.snapshot().entries().values()) {
                assertEquals(BehaviorCatalog.SourceState.VALID, entry.state(), pack + ": " + entry.diagnostics());
                assertEquals(List.of(), entry.diagnostics(), entry.path());
                for (BehaviorCatalog.Requirement requirement : entry.accepted().orElseThrow().requirements())
                    assertEquals("animus", requirement.key().namespace(), entry.path() + " must use only built-in capabilities");
            }
            for (BehaviorCatalog.Resolution resolution : catalog.snapshot().byId().values()) assertTrue(resolution.available(), resolution.id());
        }
    }

    @Test void theShopNeedsTheAllowListAndSaysSo() throws Exception {
        Path shop = RepositoryFiles.packs().resolve("shop");
        BehaviorCatalog catalog = new BehaviorCatalog(shop, BehaviorCatalog.Limits.defaults(), granting(false), BehaviorCheckpoints.disabled());
        assertTrue(catalog.reload(0, Set.of()).published());
        BehaviorCatalog.Resolution keeper = catalog.snapshot().find("shop:keeper").orElseThrow();
        assertFalse(keeper.available());
        assertTrue(keeper.accepted().isPresent());
        assertTrue(keeper.diagnostics().stream().anyMatch(problem -> problem.message().contains("privileged-capability-not-granted")));
        String readme = Files.readString(RepositoryFiles.packs().resolve("README.md"));
        assertTrue(readme.contains("commandNamespaces = [\"shop\"]"));
    }

    @Test void onlyTheShopUsesCommandsAndGuardsDocumentTheirLimits() throws Exception {
        for (Path pack : RepositoryFiles.packDirectories()) {
            for (Path file : RepositoryFiles.jsonUnder(pack.resolve("behaviors"))) {
                boolean usesCommand = Files.readString(file).contains("\"animus:command\"");
                assertEquals(pack.getFileName().toString().equals("shop"), usesCommand, file.toString());
            }
        }
        String readme = Files.readString(RepositoryFiles.packs().resolve("README.md"));
        assertTrue(readme.contains("animus:combat-control"));
        assertTrue(readme.contains("cannot follow"));
    }

    @Test void everyExampleNpcReferencesABehaviorOfItsOwnPack() throws Exception {
        for (Path pack : RepositoryFiles.packDirectories()) {
            for (Path file : RepositoryFiles.jsonUnder(pack.resolve("npcs"))) {
                JsonObject npc = JsonDocuments.object(Files.readString(file));
                for (com.google.gson.JsonElement reference : npc.getAsJsonArray("behaviors")) {
                    String[] parts = reference.getAsString().split(":");
                    assertTrue(Files.isRegularFile(pack.resolve("behaviors/" + parts[0] + "/" + parts[1] + ".json")), file + " -> " + reference);
                }
            }
        }
    }

    @Test void theMigrationSectionShowsDocumentsThatCompile() throws Exception {
        String documentation = Files.readString(RepositoryFiles.root().resolve("docs/behaviors.md"));
        int start = documentation.indexOf("## Moving imported flags and assignments");
        assertTrue(start >= 0, "migration section is missing");
        int end = documentation.indexOf("\n## ", start + 1);
        String section = end < 0 ? documentation.substring(start) : documentation.substring(start, end);
        Matcher blocks = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL).matcher(section);
        List<String> documents = new ArrayList<>();
        while (blocks.find()) if (blocks.group(1).contains("\"schemaVersion\"")) documents.add(blocks.group(1));
        assertTrue(documents.size() >= 3, "expected dialogue, quest and flag workflows");
        JsonSchemaCheck schema = new JsonSchemaCheck(BehaviorSchemaGenerator.behaviorSchema(capabilities.capabilities()));
        for (String text : documents) {
            JsonObject document = JsonDocuments.object(text);
            assertEquals(List.of(), schema.validate(document), text);
            document.remove("revision");
            BehaviorCompiler.Result result = BehaviorCompiler.compile(document.toString());
            assertTrue(result.program().isPresent(), result.diagnostics() + " in " + text);
        }
    }
}
