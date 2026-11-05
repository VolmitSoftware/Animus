package art.arcane.animus.core.behavior;

import static org.junit.jupiter.api.Assertions.*;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopedVariableCompilerTest {
    private static final String EMPTY = "{\"type\":\"sequence\",\"steps\":[]}";

    private static String source(String variables) {
        return "{\"schemaVersion\":1,\"id\":\"example:quest\",\"entry\":\"main\",\"variables\":" + variables
                + ",\"routines\":{\"main\":" + EMPTY + "}}";
    }

    private static Diagnostic rejected(String variables) {
        BehaviorCompiler.Result result = BehaviorCompiler.compile(source(variables));
        assertTrue(result.program().isEmpty(), "Expected rejection");
        return result.diagnostics().getFirst();
    }

    @Test
    void everyScopeAndLifetimeCompilesAndExecutionStaysUnscoped() {
        BehaviorCompiler.Result result = BehaviorCompiler.compile(source("{"
                + "\"plain\":{\"type\":\"integer\",\"initial\":0},"
                + "\"local\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"execution\"},"
                + "\"mood\":{\"type\":\"string\",\"initial\":\"calm\",\"scope\":\"npc\",\"lifetime\":\"transient\"},"
                + "\"seen\":{\"type\":\"boolean\",\"initial\":false,\"scope\":\"player\",\"lifetime\":\"persistent\"},"
                + "\"trust\":{\"type\":\"decimal\",\"initial\":0.5,\"scope\":\"relationship\",\"lifetime\":\"persistent\"},"
                + "\"phase\":{\"type\":\"integer\",\"initial\":1,\"scope\":\"global\",\"lifetime\":\"transient\"}}"));
        BehaviorProgram program = result.program().orElseThrow();
        assertEquals(6, program.initial().size());
        assertEquals(4, program.scoped().size());
        assertFalse(program.scoped().containsKey("plain"));
        assertFalse(program.scoped().containsKey("local"));
        assertEquals(new ScopedVariable(Scope.NPC, Lifetime.TRANSIENT), program.scoped().get("mood"));
        assertEquals(new ScopedVariable(Scope.PLAYER, Lifetime.PERSISTENT), program.scoped().get("seen"));
        assertEquals(new ScopedVariable(Scope.RELATIONSHIP, Lifetime.PERSISTENT), program.scoped().get("trust"));
        assertEquals(new ScopedVariable(Scope.GLOBAL, Lifetime.TRANSIENT), program.scoped().get("phase"));
        assertTrue(program.requiresPlayer());
    }

    @Test
    void programsWithoutPlayerScopesDoNotRequireAPlayer() {
        BehaviorProgram program = BehaviorCompiler.compile(source(
                "{\"mood\":{\"type\":\"string\",\"initial\":\"calm\",\"scope\":\"npc\",\"lifetime\":\"transient\"}}")).program().orElseThrow();
        assertFalse(program.requiresPlayer());
    }

    @Test
    void invalidScopeAndLifetimeDeclarationsReportTheExactField() {
        record Case(String variable, String field, String fragment) {}
        List<Case> cases = List.of(
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"server\",\"lifetime\":\"persistent\"}}", "$.variables.a.scope", "expected execution, npc, player, relationship or global"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"NPC\",\"lifetime\":\"persistent\"}}", "$.variables.a.scope", "expected execution"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":7,\"lifetime\":\"persistent\"}}", "$.variables.a.scope", "nonblank string"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\"}}", "$.variables.a.lifetime", "required"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"forever\"}}", "$.variables.a.lifetime", "expected transient or persistent"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"lifetime\":\"persistent\"}}", "$.variables.a.lifetime", "execution variables"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"execution\",\"lifetime\":\"transient\"}}", "$.variables.a.lifetime", "execution variables"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":\"0\",\"scope\":\"npc\",\"lifetime\":\"persistent\"}}", "$.variables.a.initial", "integer"),
                new Case("{\"a\":{\"type\":\"tuple\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"persistent\"}}", "$.variables.a.type", "boolean, integer, decimal, string, list, map or any"),
                new Case("{\"a\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"persistent\",\"extra\":1}}", "$.variables.a.extra", "unsupported"));
        for (Case item : cases) {
            Diagnostic diagnostic = rejected(item.variable());
            assertEquals(item.field(), diagnostic.field(), item.variable());
            assertTrue(diagnostic.message().contains(item.fragment()), diagnostic.message());
        }
    }

    @Test
    void assignmentAcrossScopesKeepsExactTypesWithoutCoercion() {
        String declarations = "\"variables\":{\"count\":{\"type\":\"integer\",\"initial\":0,\"scope\":\"npc\",\"lifetime\":\"persistent\"},"
                + "\"label\":{\"type\":\"string\",\"initial\":\"x\",\"scope\":\"global\",\"lifetime\":\"transient\"},"
                + "\"local\":{\"type\":\"integer\",\"initial\":5}},";
        String head = "{\"schemaVersion\":1,\"id\":\"example:quest\",\"entry\":\"main\"," + declarations + "\"routines\":{\"main\":";
        assertTrue(BehaviorCompiler.compile(head + "{\"type\":\"set\",\"variable\":\"count\",\"value\":{\"variable\":\"local\"}}}}").program().isPresent());
        BehaviorCompiler.Result mismatch = BehaviorCompiler.compile(head + "{\"type\":\"set\",\"variable\":\"count\",\"value\":{\"variable\":\"label\"}}}}");
        assertEquals("$.routines.main.value.variable", mismatch.diagnostics().getFirst().field());
        BehaviorCompiler.Result literal = BehaviorCompiler.compile(head + "{\"type\":\"set\",\"variable\":\"count\",\"value\":\"3\"}}}");
        assertEquals("$.routines.main.value", literal.diagnostics().getFirst().field());
    }
}
