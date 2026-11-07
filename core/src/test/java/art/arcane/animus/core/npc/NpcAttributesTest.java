package art.arcane.animus.core.npc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcAttributesTest {
    @Test void attributesParseWithBareAndNamespacedIds() {
        NpcAttributes attributes = NpcAppearance.of(definition("\"attributes\":{\"scale\":1.5,\"minecraft:gravity\":0.02,\"movement_speed\":0.3}")).traits().attributes();
        assertEquals(new TreeMap<>(Map.of("scale", 1.5, "gravity", 0.02, "movement_speed", 0.3)), attributes.values());
        assertEquals(1.5, attributes.scale());
        assertEquals(OptionalDouble.of(0.02), attributes.get("minecraft:gravity"));
        assertEquals(0.6, attributes.value("step_height"), "an unset attribute reads as its vanilla default");
    }

    @Test void unknownIdsWrongTypesAndOutOfRangeValuesAreRefused() {
        List<String> errors = NpcDefinition.validate(json("\"attributes\":{\"scale\":40,\"bogus\":1,\"gravity\":\"low\",\"armor\":-1}"));
        assertTrue(errors.contains("$.attributes.scale: expected 0.0625..16 for scale"), errors.toString());
        assertTrue(errors.stream().anyMatch((String error) -> error.startsWith("$.attributes.bogus: unknown attribute bogus")), errors.toString());
        assertTrue(errors.contains("$.attributes.gravity: expected a number"), errors.toString());
        assertTrue(errors.contains("$.attributes.armor: expected 0..30 for armor"), errors.toString());
        assertEquals(List.of("$.attributes: expected an object of attribute ids and numbers"), NpcDefinition.validate(json("\"attributes\":[1]")));
    }

    @Test void theSchemaNamesTheBodiesThatCarryEachAttribute() {
        com.google.gson.JsonObject properties = art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.schema().getAsJsonObject("properties")
                .getAsJsonObject("attributes").getAsJsonObject("properties");
        java.util.List<String> scale = properties.getAsJsonObject("scale").getAsJsonArray("x-bodies").asList().stream().map(com.google.gson.JsonElement::getAsString).toList();
        assertEquals(art.arcane.animus.core.presentation.BodyRules.TYPES.stream().sorted().toList(), scale);
        java.util.List<String> damage = properties.getAsJsonObject("minecraft:attack_damage").getAsJsonArray("x-bodies").asList().stream().map(com.google.gson.JsonElement::getAsString).toList();
        assertEquals(java.util.List.of("minecraft:frog", "minecraft:player"), damage);
        assertEquals(java.util.List.of("minecraft:player"), properties.getAsJsonObject("luck").getAsJsonArray("x-bodies").asList().stream().map(com.google.gson.JsonElement::getAsString).toList());
    }

    @Test void theSameAttributeTwiceIsRefused() {
        List<String> errors = NpcDefinition.validate(json("\"attributes\":{\"scale\":2,\"minecraft:scale\":3}"));
        assertEquals(List.of("$.attributes.minecraft:scale: scale is set twice"), errors);
    }

    @Test void everyAttributeOfTheGeneratedTableAcceptsItsDefault() {
        for (VanillaAttributes.Attribute attribute : NpcAttributes.table()) {
            assertNull(NpcAttributes.problem(attribute.id(), attribute.defaultValue()), attribute.id());
            assertNull(NpcAttributes.problem("minecraft:" + attribute.id(), attribute.minimum()), attribute.id());
        }
        assertTrue(NpcAttributes.attribute("waypoint_transmit_range").isPresent());
        assertTrue(NpcAttributes.attribute("camera_distance").isPresent());
    }

    @Test void withAndWithoutChangeOneAttribute() {
        NpcAttributes base = NpcAttributes.NONE.with("minecraft:scale", 2);
        assertEquals(2, base.scale());
        assertEquals(1, base.without("scale").scale());
        assertThrows(IllegalArgumentException.class, () -> base.with("scale", 0));
        assertThrows(IllegalArgumentException.class, () -> base.with("nope", 1));
        assertEquals(new TreeMap<>(Map.of("scale", 3.0, "gravity", 0.0)), base.merged(NpcAttributes.NONE.with("scale", 3).with("gravity", 0)).values());
    }

    @Test void effectsSeparateSimulatedAttributesFromPlayerOnlyOnes() {
        assertEquals(NpcAttributes.Effect.SIMULATED, NpcAttributes.effect("minecraft:movement_speed"));
        assertEquals(NpcAttributes.Effect.SIMULATED, NpcAttributes.effect("scale"));
        assertEquals(NpcAttributes.Effect.NONE, NpcAttributes.effect("block_interaction_range"));
        assertEquals(NpcAttributes.Effect.NONE, NpcAttributes.effect("mining_efficiency"));
    }

    @Test void groupsSetAttributesThatMergePerAttributeWithProvenance() {
        GroupDocument giants = GroupDocument.parse("giants", """
                {"schemaVersion":1,"revision":1,"id":"giants","values":{"attributes":{"minecraft:scale":2,"gravity":0.04}}}
                """).group().orElseThrow();
        GroupDocument slow = GroupDocument.parse("slow", """
                {"schemaVersion":1,"revision":1,"id":"slow","values":{"attributes":{"movement_speed":0.05}}}
                """).group().orElseThrow();
        GroupMerge.Result result = GroupMerge.merge(json("\"attributes\":{\"scale\":3}"), List.of(giants, slow));
        JsonObject attributes = result.effective().getAsJsonObject("attributes");
        assertEquals(JsonParser.parseString("{\"scale\":3,\"gravity\":0.04,\"movement_speed\":0.05}"), attributes);
        assertEquals("npc", result.provenance().get("attributes.scale"));
        assertEquals("giants", result.provenance().get("attributes.gravity"));
        assertEquals("slow", result.provenance().get("attributes.movement_speed"));
    }

    @Test void aGroupWithABadAttributeFailsTheGroupFile() {
        GroupDocument.Result result = GroupDocument.parse("bad", """
                {"schemaVersion":1,"revision":1,"id":"bad","values":{"attributes":{"scale":99}}}
                """);
        assertEquals(List.of("$.values.attributes.scale: expected 0.0625..16 for scale"), result.problems());
    }

    private static JsonObject json(String extra) { return NpcAppearanceTest.definition("minecraft:player", extra); }
    private static NpcDefinition definition(String extra) {
        try { return NpcDefinition.parse(json(extra).toString()); }
        catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }
}
