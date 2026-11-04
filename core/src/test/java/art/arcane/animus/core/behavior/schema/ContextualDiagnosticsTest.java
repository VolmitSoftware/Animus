package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.schema.CapabilitySchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A diagnostic names what is allowed at that position and the likely intended word, not just what went wrong. */
class ContextualDiagnosticsTest {
    private static Diagnostic compile(String routine, String extra) {
        BehaviorCompiler.Result result = BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"a:b\",\"entry\":\"main\"," + extra + "\"routines\":{\"main\":" + routine + "}}");
        assertTrue(result.program().isEmpty());
        return result.diagnostics().getFirst();
    }

    @Test void misspelledNodeFieldListsItsFieldsAndSuggests() {
        Diagnostic diagnostic = compile("{\"type\":\"action\",\"capabilty\":\"animus:say\"}", "");
        assertEquals("$.routines.main.capabilty", diagnostic.field());
        assertTrue(diagnostic.message().contains("capability, arguments, bindings"), diagnostic.message());
        assertTrue(diagnostic.message().contains("did you mean capability?"), diagnostic.message());
    }

    @Test void unknownNodeTypeListsTheLanguage() {
        Diagnostic diagnostic = compile("{\"type\":\"sequense\"}", "");
        assertEquals("$.routines.main.type", diagnostic.field());
        assertTrue(diagnostic.message().contains("sequence, action, condition, wait, repeat, parallel, call, set"), diagnostic.message());
        assertTrue(diagnostic.message().contains("did you mean sequence?"), diagnostic.message());
    }

    @Test void unknownTriggerEventAndForeignFieldExplainThemselves() {
        Diagnostic event = compile("{\"type\":\"wait\",\"ticks\":1}", "\"triggers\":[{\"id\":\"a\",\"event\":\"timmer\"}],");
        assertTrue(event.message().contains("did you mean timer?"), event.message());
        Diagnostic field = compile("{\"type\":\"wait\",\"ticks\":1}", "\"triggers\":[{\"id\":\"a\",\"event\":\"timer\",\"intervalTicks\":20,\"radius\":3}],");
        assertTrue(field.message().contains("timer"), field.message());
        assertTrue(field.message().contains("intervalTicks"), field.message());
    }

    @Test void unknownArgumentListsAllowedOnesAndSuggests() {
        CapabilitySchema schema = CapabilitySchema.compile("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"radius\":{\"type\":\"number\"},\"text\":{\"type\":\"string\"}}}");
        Diagnostic diagnostic = schema.validate("{\"radious\":1}").getFirst();
        assertEquals("schema-false", diagnostic.code());
        assertEquals("$/radious", diagnostic.field());
        assertTrue(diagnostic.message().contains("radius, text"), diagnostic.message());
        assertTrue(diagnostic.message().contains("did you mean radius?"), diagnostic.message());
    }

    @Test void valueProblemsStateTheExpectation() {
        CapabilitySchema schema = CapabilitySchema.compile("{\"type\":\"object\",\"properties\":{\"mode\":{\"enum\":[\"walk\",\"fly\"]},\"radius\":{\"type\":\"number\",\"minimum\":1,\"maximum\":64},\"name\":{\"type\":\"string\",\"maxLength\":4}}}");
        assertTrue(schema.validate("{\"mode\":\"run\"}").getFirst().message().contains("walk, fly"));
        assertTrue(schema.validate("{\"radius\":\"far\"}").getFirst().message().contains("number"));
        assertTrue(schema.validate("{\"radius\":100}").getFirst().message().contains("maximum 64"));
        assertTrue(schema.validate("{\"name\":\"toolong\"}").getFirst().message().contains("at most 4"));
    }

    @Test void alternativesNameWhatToProvide() {
        CapabilitySchema schema = CapabilitySchema.compile("{\"type\":\"object\",\"oneOf\":[{\"required\":[\"route\"]},{\"required\":[\"x\",\"y\",\"z\"]}]}");
        String message = schema.validate("{}").getFirst().message();
        assertTrue(message.contains("route") && message.contains("x, y, z"), message);
    }

    @Test void unregisteredCapabilitySuggestsTheNearestAndCorrectsTheKind() {
        try (OfflineCapabilities offline = new OfflineCapabilities()) {
            BehaviorCatalog.Capability typo = offline.resolver().resolve(new BehaviorCatalog.Requirement(new Capabilities.Key("animus", "sya"), Capabilities.Kind.ACTION));
            assertFalse(typo.available());
            assertTrue(typo.reason().contains("did you mean animus:say?"), typo.reason());
            BehaviorCatalog.Capability kind = offline.resolver().resolve(new BehaviorCatalog.Requirement(new Capabilities.Key("animus", "say"), Capabilities.Kind.CONDITION));
            assertTrue(kind.reason().contains("is an action"), kind.reason());
        }
    }

    @Test void suggestionsIgnoreDistantWords() {
        assertEquals("", Suggestions.didYouMean("completely-different", List.of("say", "look")));
        assertEquals("; did you mean look?", Suggestions.didYouMean("lok", List.of("say", "look")));
    }
}
