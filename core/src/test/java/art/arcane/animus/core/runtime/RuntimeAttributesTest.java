package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeAttributesTest {
    private static final NpcAttributes DECLARED = NpcAttributes.NONE.with("scale", 2);

    @Test void aChangeSetsAndAResetReturnsToTheDefinition() {
        NpcAttributes running = RuntimeAttributes.change(DECLARED, DECLARED, "minecraft:movement_speed", OptionalDouble.of(0.3));
        assertEquals(OptionalDouble.of(0.3), running.get("movement_speed"));
        running = RuntimeAttributes.change(DECLARED, running.with("scale", 4), "scale", OptionalDouble.empty());
        assertEquals(2, running.scale(), "the definition's own value comes back");
        running = RuntimeAttributes.change(DECLARED, running, "movement_speed", OptionalDouble.empty());
        assertTrue(running.get("movement_speed").isEmpty(), "an attribute the definition leaves out returns to its default");
    }

    @Test void unknownAttributesAndValuesOutOfRangeAreRefused() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> RuntimeAttributes.change(DECLARED, DECLARED, "bogus", OptionalDouble.of(1)));
        assertTrue(unknown.getMessage().startsWith("unknown attribute bogus"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeAttributes.change(DECLARED, DECLARED, "scale", OptionalDouble.of(99)));
    }

    @Test void theRunningDefinitionCarriesTheChangedAttributes() {
        JsonObject document = JsonParser.parseString("{\"type\":\"minecraft:player\",\"attributes\":{\"scale\":2}}").getAsJsonObject();
        JsonObject running = RuntimeAttributes.definition(document, DECLARED.with("gravity", 0));
        assertEquals(JsonParser.parseString("{\"gravity\":0.0,\"scale\":2.0}"), running.get("attributes"));
        assertFalse(RuntimeAttributes.definition(document, NpcAttributes.NONE).has("attributes"));
        assertTrue(document.getAsJsonObject("attributes").has("scale"), "the document itself is left alone");
    }

    @Test void aResizedStateKeepsItsFeetAndGrowsItsBounds() {
        VirtualNpcState state = new VirtualNpcState(UUID.randomUUID(), new WorldPosition("world", new art.arcane.animus.core.virtual.Vec3(0, 64, 0)), .6, 1.8, 20);
        long before = state.snapshot().revision();
        state.resize(1.2, 3.6);
        Hitbox bounds = state.snapshot().bounds();
        assertEquals(3.6, bounds.max().y() - bounds.min().y(), 1e-9);
        assertEquals(1.2, bounds.max().x() - bounds.min().x(), 1e-9);
        assertEquals(64, bounds.min().y(), 1e-9);
        assertTrue(state.snapshot().revision() > before);
    }
}
