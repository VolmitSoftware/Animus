package art.arcane.animus.core.schema;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilitySchemaTest {
    private static void valid(String schema, String instance) {
        assertTrue(CapabilitySchema.compile(schema).validate(instance).isEmpty(), () -> instance + " invalid for " + schema);
    }
    private static void invalid(String schema, String instance) {
        assertFalse(CapabilitySchema.compile(schema).validate(instance).isEmpty(), () -> instance + " valid for " + schema);
    }
    private static String property(String schema) { return "{\"properties\":{\"x\":" + schema + "}}"; }
    @Test void emptyAndBooleanSchemasDoNotCoerceOrRequireFields() {
        valid("{}", "{\"arbitrary\":[null,1,\"x\"]}");
        valid(property("false"), "{}");
        invalid(property("false"), "{\"x\":null}");
        valid(property("true"), "{\"x\":false}");
        invalid(property("{\"type\":\"integer\"}"), "{\"x\":\"1\"}");
        valid(property("{\"type\":\"integer\"}"), "{\"x\":1.0}");
        invalid(property("{\"type\":\"integer\"}"), "{\"x\":1.01}");
    }
    @Test void exactNumbersDoNotRoundLongsOrDecimalMultiples() {
        valid(property("{\"const\":9223372036854775807}"), "{\"x\":9223372036854775807}");
        invalid(property("{\"const\":9223372036854775807}"), "{\"x\":9223372036854775806}");
        valid(property("{\"multipleOf\":0.1}"), "{\"x\":0.3}");
        invalid(property("{\"multipleOf\":0.1}"), "{\"x\":0.31}");
        valid(property("{\"minimum\":1,\"exclusiveMaximum\":2}"), "{\"x\":1}");
        invalid(property("{\"minimum\":1,\"exclusiveMaximum\":2}"), "{\"x\":2}");
        invalid(property("{\"exclusiveMinimum\":1,\"maximum\":2}"), "{\"x\":1}");
        valid(property("{\"exclusiveMinimum\":1,\"maximum\":2}"), "{\"x\":2}");
        valid(property("{\"minimum\":1}"), "{\"x\":\"unrelated type\"}");
    }
    @Test void typesDistinguishBooleanStringAndNullAndAllowUnions() {
        for (String type : List.of("null", "boolean", "object", "array", "number", "integer", "string")) {
            String value = Map.of("null", "null", "boolean", "true", "object", "{}", "array", "[]", "number", "1.2", "integer", "2", "string", "\"s\"").get(type);
            valid(property("{\"type\":\"" + type + "\"}"), "{\"x\":" + value + "}");
        }
        valid(property("{\"type\":[\"string\",\"null\"]}"), "{\"x\":null}");
        invalid(property("{\"type\":[\"string\",\"null\"]}"), "{\"x\":false}");
        invalid(property("{\"enum\":[1]}"), "{\"x\":true}");
        invalid(property("{\"enum\":[1]}"), "{\"x\":\"1\"}");
    }
    @Test void requiredAndAdditionalPropertiesAreLocalAndPointToExactFields() {
        String schema = "{\"required\":[\"a/b~c\"],\"properties\":{\"a/b~c\":{\"type\":\"string\"}},\"additionalProperties\":false}";
        var missing = CapabilitySchema.compile(schema).validate("{}");
        assertEquals("$/a~1b~0c", missing.getFirst().field());
        valid(schema, "{\"a/b~c\":\"yes\"}");
        invalid(schema, "{\"a/b~c\":\"yes\",\"extra\":0}");
        valid("{\"additionalProperties\":{\"type\":\"integer\"}}", "{\"x\":1,\"y\":2}");
        invalid("{\"additionalProperties\":{\"type\":\"integer\"}}", "{\"x\":\"1\"}");
        invalid("{\"allOf\":[{\"properties\":{\"x\":true}},{\"additionalProperties\":false}]}", "{\"x\":1}");
    }
    @Test void arrayPrefixAndItemsHaveDraft202012Semantics() {
        String schema = property("{\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"integer\"}],\"items\":false}");
        valid(schema, "{\"x\":[]}");
        valid(schema, "{\"x\":[\"s\",2]}");
        invalid(schema, "{\"x\":[\"s\",2,3]}");
        invalid(schema, "{\"x\":[1,\"s\"]}");
        valid(property("{\"items\":{\"type\":\"boolean\"}}"), "{\"x\":[true,false]}");
        invalid(property("{\"items\":{\"type\":\"boolean\"}}"), "{\"x\":[true,0]}");
    }
    @Test void sizeConstraintsCountUnicodeCodePointsAndIgnoreOtherTypes() {
        valid(property("{\"minLength\":1,\"maxLength\":1}"), "{\"x\":\"😀\"}");
        invalid(property("{\"maxLength\":1}"), "{\"x\":\"😀😀\"}");
        invalid(property("{\"minLength\":1}"), "{\"x\":\"\"}");
        valid(property("{\"minLength\":1}"), "{\"x\":0}");
        valid(property("{\"minItems\":1,\"maxItems\":2}"), "{\"x\":[0]}");
        invalid(property("{\"minItems\":1,\"maxItems\":2}"), "{\"x\":[]}");
        invalid("{\"minProperties\":1,\"maxProperties\":2}", "{\"a\":0,\"b\":0,\"c\":0}");
        valid("{\"maxProperties\":1e100}", "{\"a\":0}");
        invalid("{\"minProperties\":1e100}", "{\"a\":0}");
    }
    @Test void equalityIsStructuralOrderIndependentAndNumeric() {
        valid(property("{\"const\":{\"a\":[1,null],\"b\":true}}"), "{\"x\":{\"b\":true,\"a\":[1.0,null]}}");
        invalid(property("{\"const\":{\"a\":[1,null]}}"), "{\"x\":{\"a\":[null,1]}}");
        valid(property("{\"enum\":[null,[1],{\"a\":false}]}"), "{\"x\":{\"a\":false}}");
        invalid(property("{\"enum\":[null,[1],{\"a\":false}]}"), "{\"x\":{\"a\":0}}");
        invalid(property("{\"const\":null}"), "{\"x\":false}");
        invalid(property("{\"enum\":[]}"), "{\"x\":null}");
    }
    @Test void combinatorsDoNotLeakFailedBranchDiagnostics() {
        valid(property("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}"), "{\"x\":3}");
        invalid(property("{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"integer\"}]}"), "{\"x\":3}");
        valid(property("{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"integer\"}]}"), "{\"x\":3.2}");
        valid(property("{\"allOf\":[{\"minimum\":1},{\"maximum\":3}]}"), "{\"x\":2}");
        invalid(property("{\"not\":{\"type\":\"string\"}}"), "{\"x\":\"s\"}");
        String conditional = property("{\"if\":{\"type\":\"string\"},\"then\":{\"minLength\":2},\"else\":{\"type\":\"null\"}}");
        valid(conditional, "{\"x\":null}"); valid(conditional, "{\"x\":\"ok\"}");
        invalid(conditional, "{\"x\":\"s\"}"); invalid(conditional, "{\"x\":1}");
        valid("{\"then\":false,\"else\":false}", "{}");
    }
    @Test void annotationValuesNeverAddDefaultsOrAssertReadOnly() {
        String schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"title\":\"x\",\"description\":\"y\",\"examples\":[null],\"default\":{\"a\":3},\"readOnly\":true,\"writeOnly\":true,\"deprecated\":true}";
        valid(schema, "{}");
    }
    @Test void unsupportedKeywordsAreExplicitCompileFailuresIncludingNestedOnes() {
        for (String keyword : List.of("$ref", "$id", "$defs", "pattern", "format", "uniqueItems", "contains", "unevaluatedProperties", "unknown")) {
            var error = assertThrows(CapabilitySchema.SchemaException.class, () -> CapabilitySchema.compile(property("{\"" + keyword + "\":\"x\"}")));
            assertEquals("schema-keyword-unavailable", error.diagnostics().getFirst().code());
            assertEquals("$/properties/x/" + keyword, error.diagnostics().getFirst().field());
        }
    }
    @Test void malformedSchemasFailBeforeAnyInstanceIsAccepted() {
        for (String schema : List.of("[]", "true", "{\"type\":[]}", "{\"type\":[\"string\",\"string\"]}",
                "{\"type\":\"uuid\"}", "{\"properties\":[]}", "{\"required\":[1]}", "{\"required\":[\"x\",\"x\"]}",
                "{\"minimum\":\"1\"}", "{\"multipleOf\":0}", "{\"minLength\":-1}", "{\"maxItems\":0.5}",
                "{\"allOf\":[]}", "{\"items\":[]}", "{\"examples\":null}", "{\"readOnly\":0}",
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}", "{\"type\":\"object\",\"type\":\"array\"}"))
            assertThrows(CapabilitySchema.SchemaException.class, () -> CapabilitySchema.compile(schema), schema);
    }
    @Test void invalidInputsHaveBoundedPayloadFreeDiagnostics() {
        CapabilitySchema schema = CapabilitySchema.compile("{}");
        for (String value : List.of("[]", "null", "{\"x\":1,\"x\":2}", "{} {}", "{\"x\":NaN}", "{\"x\":1e2147483647}", "{\"x\":\"\\ud800\"}"))
            assertFalse(schema.validate(value).isEmpty(), value);
        assertFalse(schema.validate("{\"x\":\"" + "a".repeat(65_536) + "\"}").isEmpty());
        assertFalse(schema.validate("{\"x\":\"" + "😀".repeat(20_000) + "\"}").isEmpty());
        assertFalse(schema.validate("{\"" + "\\ud800" + "\":0}").isEmpty());
        var errors = CapabilitySchema.compile("{\"additionalProperties\":false}").validate("{\"secret-value\":\"do-not-echo\"}");
        assertTrue(errors.stream().noneMatch(e -> e.message().contains("do-not-echo")));
    }
    @Test void schemaAndInstanceStructuresAreBounded() {
        String nested = "true";
        for (int i = 0; i < 18; i++) nested = "{\"items\":" + nested + "}";
        final String tooDeep = nested;
        assertThrows(CapabilitySchema.SchemaException.class, () -> CapabilitySchema.compile(tooDeep));
        String many = "{\"prefixItems\":[" + String.join(",", Collections.nCopies(512, "true")) + "]}";
        assertThrows(CapabilitySchema.SchemaException.class, () -> CapabilitySchema.compile(many));
        assertFalse(CapabilitySchema.compile("{}").validate("{\"x\":[" + String.join(",", Collections.nCopies(4_096, "0")) + "]}").isEmpty());
    }
    @Test void expensiveValidationStopsWithoutClaimingSuccess() {
        // 500 item schemas would exceed the compile node budget. Repeated object scans exercise the work bound instead.
        String schema = "{\"allOf\":[" + String.join(",", Collections.nCopies(500, "{}")) + "]}";
        String instance = "{" + java.util.stream.IntStream.range(0, 100).mapToObj(i -> "\"x" + i + "\":0").collect(java.util.stream.Collectors.joining(",")) + "}";
        var errors = CapabilitySchema.compile(schema).validate(instance);
        assertTrue(errors.stream().anyMatch(e -> e.code().equals("schema-work-limit")));
    }
    @Test void diagnosticsAreBoundedEvenWithManyInvalidProperties() {
        String instance = "{" + java.util.stream.IntStream.range(0, 100).mapToObj(i -> "\"x" + i + "\":0").collect(java.util.stream.Collectors.joining(",")) + "}";
        assertEquals(32, CapabilitySchema.compile("{\"additionalProperties\":false}").validate(instance).size());
    }
    @Test void diagnosticPathsRespectUtf8BytesWithoutSplittingSurrogates() {
        for (String key : List.of("😀".repeat(400), "a".repeat(1_000) + "😀".repeat(30), "é".repeat(800))) {
            var diagnostic = CapabilitySchema.compile("{\"additionalProperties\":false}").validate("{\"" + key + "\":0}").getFirst();
            assertTrue(diagnostic.field().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_024);
            assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(diagnostic.field()));
            assertTrue(diagnostic.field().endsWith("…"));
            var failure = assertThrows(CapabilitySchema.SchemaException.class, () -> CapabilitySchema.compile("{\"properties\":{\"" + key + "\":{\"unknown\":true}}}"));
            assertTrue(failure.diagnostics().getFirst().field().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_024);
        }
    }
    @Test void compiledSchemaCanValidateConcurrentlyWithoutMutatingItsTemplate() throws Exception {
        CapabilitySchema schema = CapabilitySchema.compile("{\"properties\":{\"x\":{\"const\":{\"n\":1}}},\"required\":[\"x\"]}");
        try (ExecutorService threads = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                final int current = i;
                calls.add(() -> schema.validate(current % 2 == 0 ? "{\"x\":{\"n\":1.0}}" : "{}").isEmpty() == (current % 2 == 0));
            }
            for (Future<Boolean> future : threads.invokeAll(calls)) assertTrue(future.get());
        }
    }
}
