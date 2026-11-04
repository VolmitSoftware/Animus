package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Packs join the catalog under the same rules as loose behaviors, and reload without a restart. */
class PackCatalogTest {
    @TempDir Path root;
    private final Set<String> modules = new HashSet<>();

    private final BehaviorCatalog.CapabilityResolver resolver = new BehaviorCatalog.CapabilityResolver() {
        @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) {
            boolean present = requirement.key().namespace().equals("animus") || modules.contains(requirement.key().namespace());
            return new BehaviorCatalog.Capability(present, present ? "available" : "capability-not-registered");
        }
        @Override public BehaviorCatalog.Capability module(String namespace) {
            boolean present = modules.contains(namespace);
            return new BehaviorCatalog.Capability(present, present ? "available" : "module-missing");
        }
    };
    private BehaviorCatalog catalog() throws Exception { return new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), resolver, BehaviorCheckpoints.disabled()); }
    private static void reload(BehaviorCatalog catalog) { assertTrue(catalog.reload(catalog.snapshot().generation(), Set.of()).published()); }
    private static String behavior(String id, long revision, String capability) {
        return "{\"schemaVersion\":1,\"revision\":" + revision + ",\"id\":\"" + id + "\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"" + capability + "\"}}}";
    }
    private Path write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
    private void manifest(String pack, String extra) throws Exception {
        write("packs/" + pack + "/pack.json", "{\"schemaVersion\":1,\"id\":\"" + pack + "\"" + extra + "}");
    }
    private static boolean has(List<Diagnostic> diagnostics, String code) { return diagnostics.stream().anyMatch(problem -> problem.code().equals(code)); }

    @Test void packBehaviorLoadsAndIsAvailable() throws Exception {
        manifest("town", ",\"name\":\"Town\",\"version\":\"1.0.0\",\"description\":\"A town\"");
        write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:greeter").orElseThrow().available());
        BehaviorCatalog.Entry entry = catalog.snapshot().entries().get("packs/town/behaviors/town/greeter.json");
        assertEquals(BehaviorCatalog.SourceState.VALID, entry.state());
        BehaviorCatalog.PackStatus status = catalog.snapshot().packs().get("town");
        assertEquals("Town", status.manifest().orElseThrow().name().orElseThrow());
        assertEquals(List.of(), status.missingModules());
    }

    @Test void identityMustMatchItsPathInsideThePack() throws Exception {
        manifest("town", "");
        write("packs/town/behaviors/town/greeter.json", behavior("town:other", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        assertTrue(has(catalog.snapshot().entries().get("packs/town/behaviors/town/greeter.json").diagnostics(), "behavior-path-mismatch"));
    }

    @Test void newAndEditedPacksReloadWithoutRestart() throws Exception {
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:greeter").isEmpty());
        manifest("town", "");
        write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:greeter").orElseThrow().available());
        write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 2, "animus:stop-navigation"));
        reload(catalog);
        assertEquals(2, catalog.snapshot().find("town:greeter").orElseThrow().accepted().orElseThrow().revision());
    }

    @Test void invalidEditRetainsTheLastValidProgram() throws Exception {
        manifest("town", "");
        Path source = write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        Files.writeString(source, "broken");
        reload(catalog);
        assertEquals(1, catalog.snapshot().find("town:greeter").orElseThrow().accepted().orElseThrow().revision());
        assertEquals("broken", Files.readString(source));
    }

    @Test void missingOrWrongManifestRejectsNewBehaviorsAndRetainsAccepted() throws Exception {
        write("packs/nomanifest/behaviors/nomanifest/one.json", behavior("nomanifest:one", 1, "animus:stop-navigation"));
        manifest("town", "");
        write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        BehaviorCatalog.Entry orphan = catalog.snapshot().entries().get("packs/nomanifest/behaviors/nomanifest/one.json");
        assertTrue(has(orphan.diagnostics(), "pack-manifest-missing"));
        assertTrue(orphan.accepted().isEmpty());
        assertTrue(has(catalog.snapshot().diagnostics(), "pack-manifest-missing"));

        write("packs/town/pack.json", "{\"schemaVersion\":1,\"id\":\"elsewhere\"}");
        reload(catalog);
        BehaviorCatalog.Entry retained = catalog.snapshot().entries().get("packs/town/behaviors/town/greeter.json");
        assertTrue(has(retained.diagnostics(), "pack-manifest-invalid"), retained.diagnostics().toString());
        assertTrue(retained.accepted().isPresent());
        assertTrue(catalog.snapshot().find("town:greeter").orElseThrow().available());
    }

    @Test void missingRequiredModuleMarksBehaviorsUnavailableAndKeepsEverything() throws Exception {
        String pack = "{\"schemaVersion\":1,\"id\":\"town\",\"modules\":{\"mod\":{\"configuration\":{\"keep\":\"me\"}}}}";
        Path manifest = write("packs/town/pack.json", pack);
        String source = behavior("town:greeter", 1, "animus:stop-navigation");
        Path file = write("packs/town/behaviors/town/greeter.json", source);
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        BehaviorCatalog.Resolution missing = catalog.snapshot().find("town:greeter").orElseThrow();
        assertFalse(missing.available());
        assertTrue(missing.accepted().isPresent(), "the program stays compiled");
        assertTrue(has(missing.diagnostics(), "pack-module-unavailable"), missing.diagnostics().toString());
        assertEquals(List.of("mod"), catalog.snapshot().packs().get("town").missingModules());
        assertEquals("{\"keep\":\"me\"}", catalog.snapshot().packs().get("town").manifest().orElseThrow().modules().get("mod").configuration().orElseThrow());
        assertEquals(pack, Files.readString(manifest));
        assertEquals(source, Files.readString(file));

        modules.add("mod");
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:greeter").orElseThrow().available(), "no edit is needed once the module returns");
        assertEquals(List.of(), catalog.snapshot().packs().get("town").missingModules());
    }

    @Test void optionalModuleOnlyAffectsBehaviorsThatUseIt() throws Exception {
        manifest("town", ",\"modules\":{\"mod\":{\"required\":false}}");
        write("packs/town/behaviors/town/plain.json", behavior("town:plain", 1, "animus:stop-navigation"));
        write("packs/town/behaviors/town/fancy.json", behavior("town:fancy", 1, "mod:fancy"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:plain").orElseThrow().available());
        assertFalse(catalog.snapshot().find("town:fancy").orElseThrow().available());
        assertEquals(List.of(), catalog.snapshot().packs().get("town").missingModules());
    }

    @Test void sameIdentityInTwoPlacesHasNoWinner() throws Exception {
        manifest("one", "");
        manifest("two", "");
        write("packs/one/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        write("packs/two/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        write("behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        assertFalse(catalog.snapshot().find("town:greeter").orElseThrow().available());
        assertEquals(BehaviorCatalog.SourceState.DUPLICATE, catalog.snapshot().entries().get("packs/one/behaviors/town/greeter.json").state());
    }

    @Test void movingABehaviorIntoAPackIsNotADuplicate() throws Exception {
        Path loose = write("behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        Files.delete(loose);
        manifest("town", "");
        write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:greeter").orElseThrow().available(), catalog.snapshot().find("town:greeter").orElseThrow().diagnostics().toString());
    }

    @Test void deletedPackLeavesItsProgramsUnavailableNotForgotten() throws Exception {
        manifest("town", "");
        Path file = write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        Files.delete(file);
        reload(catalog);
        BehaviorCatalog.Resolution resolution = catalog.snapshot().find("town:greeter").orElseThrow();
        assertFalse(resolution.available());
        assertTrue(resolution.accepted().isPresent());
    }

    @Test void packCheckpointsSurviveARestart() throws Exception {
        manifest("town", "");
        Path file = write("packs/town/behaviors/town/greeter.json", behavior("town:greeter", 3, "animus:stop-navigation"));
        try (DocumentStore store = new DocumentStore(root)) {
            BehaviorCatalog first = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), resolver, new BehaviorCheckpoints(store));
            reload(first);
            Files.writeString(file, "broken after accepted");
            BehaviorCatalog second = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), resolver, new BehaviorCheckpoints(store));
            reload(second);
            assertEquals(3, second.snapshot().find("town:greeter").orElseThrow().accepted().orElseThrow().revision());
        }
    }

    @Test void manifestChangesCountAsSourceAndInvalidDirectoryNamesAreReported() throws Exception {
        manifest("Bad Name", "");
        write("packs/Bad Name/behaviors/town/one.json", behavior("town:one", 1, "animus:stop-navigation"));
        BehaviorCatalog catalog = catalog();
        reload(catalog);
        assertTrue(catalog.snapshot().find("town:one").isPresent() && !catalog.snapshot().find("town:one").orElseThrow().available()
                || catalog.snapshot().entries().values().stream().anyMatch(entry -> has(entry.diagnostics(), "behavior-path-invalid")));
        assertTrue(has(catalog.snapshot().diagnostics(), "pack-manifest-invalid"), catalog.snapshot().diagnostics().toString());
    }
}
