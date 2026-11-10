package art.arcane.animus.core.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelEngineModuleTest {
    private static JsonObject definition(String module, String extra) {
        return JsonParser.parseString("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"drake","group":"",
                 "name":"Drake","type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0}%s%s}
                """.formatted(module == null ? "" : ",\"modules\":{\"animus:modelengine\":" + module + "}", extra)).getAsJsonObject();
    }
    private static String failure(String module) {
        return assertThrows(IllegalArgumentException.class, () -> ModelEngineModule.parse(definition(module, "")), module).getMessage();
    }

    @Test void aModelIdAloneTakesTheDefaults() {
        assertTrue(ModelEngineModule.parse(definition(null, "")).isEmpty());
        ModelEngineModule module = ModelEngineModule.parse(definition("{\"model\":\"kindletronsr\"}", "")).orElseThrow();
        assertEquals("kindletronsr", module.model());
        assertEquals(1.0, module.scale());
        assertFalse(module.showBody());
        assertTrue(module.hitbox());
        assertEquals(Optional.empty(), module.nametagBone());
        assertEquals("idle", module.animation(ModelEngineModule.State.IDLE));
        assertEquals("walk", module.animation(ModelEngineModule.State.WALK));
        assertEquals("attack", module.animation(ModelEngineModule.State.ATTACK));
        assertEquals("hurt", module.animation(ModelEngineModule.State.HURT));
        assertEquals("death", module.animation(ModelEngineModule.State.DEATH));
        assertNull(module.animation(ModelEngineModule.State.INTERACT));
    }

    @Test void everySettingIsRead() {
        ModelEngineModule module = ModelEngineModule.parse(definition("""
                {"enabled":true,"model":"Drake.v2","scale":2.5,"showBody":true,"hitbox":false,"nametagBone":"tag_name",
                 "states":{"walk":"run","interact":"wave","death":"die_slow"}}""", "")).orElseThrow();
        assertEquals("Drake.v2", module.model());
        assertEquals(2.5, module.scale());
        assertTrue(module.showBody());
        assertFalse(module.hitbox());
        assertEquals(Optional.of("tag_name"), module.nametagBone());
        assertEquals("run", module.animation(ModelEngineModule.State.WALK));
        assertEquals("wave", module.animation(ModelEngineModule.State.INTERACT));
        assertEquals("die_slow", module.animation(ModelEngineModule.State.DEATH));
        assertEquals("idle", module.animation(ModelEngineModule.State.IDLE));
    }

    @Test void aDisabledModuleShowsNoModel() {
        assertTrue(ModelEngineModule.parse(definition("{\"enabled\":false,\"model\":\"drake\"}", "")).isEmpty());
    }

    @Test void malformedSettingsNameTheirField() {
        assertEquals("$.modules.animus:modelengine.model: required", failure("{}"));
        assertEquals("$.modules.animus:modelengine.model: expected 1-64 letters, digits, _, -, . or /", failure("{\"model\":\"has space\"}"));
        assertEquals("$.modules.animus:modelengine.scale: expected 0.0625..16", failure("{\"model\":\"a\",\"scale\":40}"));
        assertEquals("$.modules.animus:modelengine.showBody: expected boolean", failure("{\"model\":\"a\",\"showBody\":1}"));
        assertEquals("$.modules.animus:modelengine.colour: unknown field", failure("{\"model\":\"a\",\"colour\":1}"));
        assertEquals("$.modules.animus:modelengine.states.swim: unknown state; expected idle, walk, attack, hurt, death or interact", failure("{\"model\":\"a\",\"states\":{\"swim\":\"x\"}}"));
        assertEquals("$.modules.animus:modelengine.states.walk: expected an animation name", failure("{\"model\":\"a\",\"states\":{\"walk\":\"\"}}"));
        assertEquals("$.modules.animus:modelengine: expected object", failure("\"drake\""));
    }

    @Test void aRigAndAModelCannotShareABody() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> ModelEngineModule.parse(definition("{\"model\":\"drake\"}", ",\"rig\":{\"id\":\"animus:drake\"}"))).getMessage();
        assertEquals("$.modules.animus:modelengine: an NPC shows either a rig or a Model Engine model; remove one", message);
    }

    @Test void writesBackWhatItReads() {
        ModelEngineModule module = new ModelEngineModule("drake", 1.5, true, false, Optional.of("tag"), Map.of(ModelEngineModule.State.WALK, "run"));
        JsonObject document = definition(module.json().toString(), "");
        assertEquals(module, ModelEngineModule.parse(document).orElseThrow());
        assertEquals(JsonParser.parseString("{\"model\":\"drake\"}"), ModelEngineModule.of("drake").json());
    }
}
