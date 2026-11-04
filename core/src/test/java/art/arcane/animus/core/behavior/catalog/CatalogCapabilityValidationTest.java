package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.api.Diagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Invalid arguments reject the source. An unavailable capability keeps a valid program but cannot run it. */
class CatalogCapabilityValidationTest {
    @TempDir Path root;
    private static final String ACTION = "{\"type\":\"action\",\"capability\":\"sample:greet\",\"arguments\":{\"text\":\"Hi\"}}";
    private static final String BOUND = "{\"type\":\"action\",\"capability\":\"sample:greet\",\"arguments\":{\"text\":null},\"bindings\":{\"/text\":{\"variable\":\"line\"}}}";
    private final List<String> validated = new ArrayList<>();

    private static String source(long revision, String node) {
        return "{\"schemaVersion\":1,\"revision\":" + revision + ",\"id\":\"sample:test\",\"entry\":\"main\",\"variables\":{\"line\":{\"type\":\"string\",\"initial\":\"Hi\"}},\"routines\":{\"main\":" + node + "}}";
    }
    private void put(long revision, String node) throws Exception {
        Path file = root.resolve(BehaviorPath.forId("sample:test"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, source(revision, node));
    }
    private BehaviorCatalog catalog(boolean available, List<Diagnostic> invalid, boolean authorized) throws Exception {
        return new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), new BehaviorCatalog.CapabilityResolver() {
            @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) {
                return new BehaviorCatalog.Capability(available, available ? "available" : "platform-unavailable: Folia");
            }
            @Override public BehaviorCatalog.Capability authorize(BehaviorCatalog.Requirement requirement, String behavior) {
                return new BehaviorCatalog.Capability(authorized, authorized ? "available" : "privileged-capability-not-granted: " + behavior);
            }
            @Override public List<Diagnostic> validate(BehaviorCatalog.Requirement requirement, String argumentsJson) {
                validated.add(argumentsJson);
                return invalid;
            }
        });
    }
    private static BehaviorCatalog.Entry entry(BehaviorCatalog catalog) { return catalog.snapshot().entries().get(BehaviorPath.forId("sample:test")); }
    private static BehaviorCatalog.Resolution resolution(BehaviorCatalog catalog) { return catalog.snapshot().byId().get("sample:test"); }
    private static void reload(BehaviorCatalog catalog) { assertTrue(catalog.reload(catalog.snapshot().generation(), Set.of()).published()); }
    private static final List<Diagnostic> BAD = List.of(new Diagnostic(Diagnostic.Severity.ERROR, "schema-required", "$.text", "Missing"));

    @Test void invalidLiteralArgumentsRejectTheSource() throws Exception {
        put(1, ACTION);
        BehaviorCatalog catalog = catalog(true, BAD, true);
        reload(catalog);
        assertEquals(BehaviorCatalog.SourceState.INVALID, entry(catalog).state());
        assertTrue(entry(catalog).accepted().isEmpty());
        assertTrue(entry(catalog).diagnostics().stream().anyMatch(problem -> problem.code().equals("behavior-arguments-invalid")), entry(catalog).diagnostics().toString());
        assertEquals(List.of("{\"text\":\"Hi\"}"), validated);
    }

    @Test void unavailableCapabilityIsAcceptedButReportedUnavailableNotInvalid() throws Exception {
        put(1, ACTION);
        BehaviorCatalog catalog = catalog(false, List.of(), true);
        reload(catalog);
        assertEquals(BehaviorCatalog.SourceState.VALID, entry(catalog).state());
        assertTrue(entry(catalog).accepted().isPresent());
        assertFalse(resolution(catalog).available());
        Diagnostic reported = resolution(catalog).diagnostics().getFirst();
        assertEquals("behavior-capability-unavailable", reported.code());
        assertTrue(reported.message().contains("Folia"), reported.message());
    }

    @Test void validatingArgumentsStillRunsWhileUnavailable() throws Exception {
        put(1, ACTION);
        BehaviorCatalog catalog = catalog(false, BAD, true);
        reload(catalog);
        assertEquals(BehaviorCatalog.SourceState.INVALID, entry(catalog).state());
    }

    @Test void boundArgumentsWaitForRuntimeValidation() throws Exception {
        put(1, BOUND);
        BehaviorCatalog catalog = catalog(true, BAD, true);
        reload(catalog);
        assertEquals(BehaviorCatalog.SourceState.VALID, entry(catalog).state());
        assertTrue(validated.isEmpty());
    }

    @Test void behaviorIdentityDecidesPrivilegedAvailability() throws Exception {
        put(1, ACTION);
        BehaviorCatalog catalog = catalog(true, List.of(), false);
        reload(catalog);
        assertEquals(BehaviorCatalog.SourceState.VALID, entry(catalog).state());
        assertFalse(resolution(catalog).available());
        assertTrue(resolution(catalog).diagnostics().getFirst().message().contains("sample:test"));
    }

    @Test void invalidEditRetainsThePreviousAcceptedProgram() throws Exception {
        put(1, ACTION);
        List<Diagnostic> problems = new ArrayList<>();
        BehaviorCatalog catalog = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), new BehaviorCatalog.CapabilityResolver() {
            @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) { return BehaviorCatalog.Capability.present(); }
            @Override public List<Diagnostic> validate(BehaviorCatalog.Requirement requirement, String argumentsJson) { return List.copyOf(problems); }
        });
        reload(catalog);
        BehaviorCatalog.Accepted previous = entry(catalog).accepted().orElseThrow();
        problems.addAll(BAD);
        put(2, ACTION.replace("Hi", "Changed"));
        reload(catalog);
        assertSame(previous, entry(catalog).accepted().orElseThrow());
        assertEquals(BehaviorCatalog.SourceState.INVALID, entry(catalog).state());
    }
}
