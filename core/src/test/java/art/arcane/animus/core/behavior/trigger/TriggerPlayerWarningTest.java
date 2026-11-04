package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** The runtime refuses a player-less start of any behavior declaring player variables, so saying so early must not be silent. */
class TriggerPlayerWarningTest {
    @TempDir Path root;
    private static final String CODE = "behavior-trigger-needs-player";

    private static String source(String scope, String trigger) {
        return "{\"schemaVersion\":1,\"id\":\"town:guard\",\"entry\":\"main\",\"variables\":{\"seen\":{\"type\":\"boolean\",\"initial\":false,\"scope\":\"" + scope + "\",\"lifetime\":\"persistent\"}},"
                + "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":[" + trigger + "]}";
    }
    private static final String TIMER = "{\"id\":\"clock\",\"event\":\"timer\",\"intervalTicks\":100}";

    @ParameterizedTest @ValueSource(strings = {
            TIMER, "{\"id\":\"up\",\"event\":\"spawn\"}", "{\"id\":\"down\",\"event\":\"despawn\"}", "{\"id\":\"hit\",\"event\":\"damage\"}",
            "{\"id\":\"end\",\"event\":\"death\"}", "{\"id\":\"at\",\"event\":\"waypoint\"}"})
    void playerlessEventsWarnWithTheirField(String trigger) {
        for (String scope : List.of("player", "relationship")) {
            BehaviorCompiler.Result result = BehaviorCompiler.compile(source(scope, trigger));
            assertTrue(result.program().isPresent(), result.diagnostics().toString());
            Diagnostic warning = result.diagnostics().getFirst();
            assertEquals(Diagnostic.Severity.WARNING, warning.severity());
            assertEquals(CODE, warning.code());
            assertEquals("$.triggers[0]", warning.field());
            assertTrue(warning.message().contains("seen"), warning.message());
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"id\":\"click\",\"event\":\"interaction\"}", "{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":4}",
            "{\"id\":\"said\",\"event\":\"dialogue_choice\",\"dialogue\":\"greeting\"}", "{\"id\":\"mod\",\"event\":\"module\",\"key\":\"example:fired\"}"})
    void eventsThatCanCarryAPlayerStayQuiet(String trigger) {
        assertTrue(BehaviorCompiler.compile(source("player", trigger)).diagnostics().isEmpty());
    }

    @Test void variablesThatNeedNoPlayerStayQuiet() {
        for (String scope : List.of("npc", "global")) assertTrue(BehaviorCompiler.compile(source(scope, TIMER)).diagnostics().isEmpty(), scope);
    }

    @Test void catalogKeepsReportingAcrossReloadsWithoutRejectingTheBehavior() throws Exception {
        Path file = root.resolve(BehaviorPath.forId("town:guard"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, source("player", TIMER).replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"revision\":1,"));
        BehaviorCatalog catalog = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), requirement -> BehaviorCatalog.Capability.present(), BehaviorCheckpoints.disabled());
        for (int reload = 0; reload < 2; reload++) {
            assertTrue(catalog.reload(catalog.snapshot().generation(), Set.of()).published());
            BehaviorCatalog.Entry entry = catalog.snapshot().entries().get(BehaviorPath.forId("town:guard"));
            assertEquals(BehaviorCatalog.SourceState.VALID, entry.state());
            assertTrue(entry.diagnostics().stream().anyMatch(problem -> problem.code().equals(CODE) && problem.severity() == Diagnostic.Severity.WARNING), entry.diagnostics().toString());
            assertTrue(catalog.snapshot().byId().get("town:guard").available());
        }
        assertTrue(catalog.validate(catalog.snapshot().generation()).valid());
    }
}
