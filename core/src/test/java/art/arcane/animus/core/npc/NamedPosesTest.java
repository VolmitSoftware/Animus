package art.arcane.animus.core.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NamedPosesTest {
    private static JsonObject definition(String poses) {
        return JsonParser.parseString("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"guide","group":"test","name":"Guide",
                 "type":"minecraft:player","spawned":true,"position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0}%s}
                """.formatted(poses == null ? "" : ",\"poses\":" + poses)).getAsJsonObject();
    }

    @Test void namedPosesHoldALookDirection() {
        NamedPoses poses = NamedPoses.parse(definition("{\"rest\":{\"yaw\":90,\"pitch\":-10},\"door\":{\"yaw\":-180}}"));
        assertEquals(Optional.of(new NamedPoses.Facing(90, -10)), poses.find("rest"));
        assertEquals(Optional.of(new NamedPoses.Facing(-180, 0)), poses.find("door"), "pitch defaults to level");
        assertEquals(Optional.empty(), poses.find("missing"));
        assertEquals(NamedPoses.NONE, NamedPoses.parse(definition(null)));
    }

    @Test void errorsNameTheirField() {
        assertEquals(List.of("$.poses.Rest: expected a name of 1-32 lowercase letters, digits, _ or -", "$.poses.up.pitch: expected -90..90 degrees",
                        "$.poses.spin.roll: unknown field", "$.poses.spin.yaw: expected -360..360 degrees"),
                NpcDefinition.validate(definition("{\"Rest\":{\"yaw\":0},\"up\":{\"yaw\":0,\"pitch\":91},\"spin\":{\"roll\":1}}")));
        assertEquals(List.of("$.poses: expected object"), NpcDefinition.validate(definition("[]")));
    }
}
