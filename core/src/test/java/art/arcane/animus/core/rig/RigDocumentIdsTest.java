package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RigDocumentIdsTest {
    private static final String RIG = """
            {"schemaVersion":1,"revision":1,"id":"town:golem","bones":[
              {"id":"root","pivot":[0,0,0]},
              {"id":"torso","parent":"root","pivot":[0,1,0],"visual":{"type":"block","block":"%s"}},
              {"id":"head","parent":"torso","pivot":[0,1.5,0],"visual":{"type":"item","item":{"id":"%s"}}}]}""";

    private static RigDocument.Result parse(String block, String item) { return RigDocument.parse("town:golem", RIG.formatted(block, item)); }

    @Test void bareBlockAndItemIdsAreMinecraftIds() {
        RigDocument.Result bare = parse("oak_log[axis=y]", "stick");
        assertEquals(List.of(), bare.problems());
        RigDocument rig = bare.document().orElseThrow();
        assertEquals(Optional.of("minecraft:oak_log[axis=y]"), rig.bones().get(1).visual().block());
        assertEquals("minecraft:stick", rig.bones().get(2).visual().item().orElseThrow().id());
    }

    @Test void namespacedIdsAreKeptAndMalformedOnesRefused() {
        RigDocument rig = parse("example:marble", "minecraft:stick").document().orElseThrow();
        assertEquals(Optional.of("example:marble"), rig.bones().get(1).visual().block());
        assertFalse(parse(":marble", "minecraft:stick").problems().isEmpty());
    }
}
