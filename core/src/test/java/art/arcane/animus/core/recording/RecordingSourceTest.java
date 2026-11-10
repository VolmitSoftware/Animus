package art.arcane.animus.core.recording;

import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingSourceTest {
    private static JsonObject definition(String navigation) {
        return JsonParser.parseString("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"8667ba71-b85a-4004-af54-457a9734eed7\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"Guard\","
                + "\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0.5,\"y\":64,\"z\":0.5,\"yaw\":0,\"pitch\":0},"
                + "\"modules\":{\"animus:navigation\":" + navigation + "}}").getAsJsonObject();
    }

    private static String problem(String navigation) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> RecordingSource.parse(definition(navigation)));
        return refused.getMessage();
    }

    @Test void parsesRecordingWithoutPlaybackAsTheDocumentDefault() {
        Optional<RecordingSource> source = RecordingSource.parse(definition("{\"enabled\":true,\"recording\":\"market-loop\"}"));
        assertEquals(Optional.of(new RecordingSource("market-loop", Optional.empty())), source);
    }

    @Test void parsesEveryPlaybackMode() {
        for (Playback mode : Playback.values())
            assertEquals(mode, RecordingSource.parse(definition("{\"enabled\":true,\"recording\":\"walk\",\"playback\":\"" + mode.key() + "\"}")).orElseThrow().playback().orElseThrow());
    }

    @Test void disabledOrRouteNavigationHasNoRecording() {
        assertEquals(Optional.empty(), RecordingSource.parse(definition("{\"enabled\":false,\"recording\":\"walk\"}")));
        assertEquals(Optional.empty(), RecordingSource.parse(definition("{\"enabled\":true,\"route\":\"patrol\"}")));
        assertEquals(Optional.empty(), RecordingSource.parse(JsonParser.parseString("{\"id\":\"plain\"}").getAsJsonObject()));
    }

    @Test void refusesAnInvalidIdentifier() {
        assertEquals("$.modules.animus:navigation.recording: expected 1-64 lowercase letters, digits, _ or -", problem("{\"enabled\":true,\"recording\":\"Bad Name\"}"));
        assertEquals("$.modules.animus:navigation.recording: expected 1-64 lowercase letters, digits, _ or -", problem("{\"enabled\":true,\"recording\":7}"));
    }

    @Test void refusesUnknownPlayback() {
        assertEquals("$.modules.animus:navigation.playback: expected once, loop or pingpong", problem("{\"enabled\":true,\"recording\":\"walk\",\"playback\":\"twice\"}"));
        assertEquals("$.modules.animus:navigation.playback: requires recording", problem("{\"enabled\":true,\"route\":\"patrol\",\"playback\":\"once\"}"));
    }

    @Test void refusesARecordingCombinedWithARouteOrPoints() {
        assertEquals("$.modules.animus:navigation.recording: cannot be combined with route", problem("{\"enabled\":true,\"recording\":\"walk\",\"route\":\"patrol\"}"));
        assertEquals("$.modules.animus:navigation.recording: cannot be combined with points",
                problem("{\"enabled\":true,\"recording\":\"walk\",\"points\":[{\"x\":1,\"y\":64,\"z\":1}]}"));
    }

    @Test void waypointSettingsLeaveARecordingSourceAlone() {
        JsonObject recorded = definition("{\"enabled\":true,\"recording\":\"walk\",\"playback\":\"once\"}");
        assertEquals(Optional.empty(), WaypointSettings.parse(recorded));
        assertNull(WaypointSettings.reference(recorded));
    }

    @Test void definitionValidationReportsRecordingProblems() {
        List<String> errors = NpcDefinition.validate(definition("{\"enabled\":true,\"recording\":\"walk\",\"route\":\"patrol\"}"));
        assertTrue(errors.contains("$.modules.animus:navigation.recording: cannot be combined with route"), errors.toString());
        assertEquals(List.of(), NpcDefinition.validate(definition("{\"enabled\":true,\"recording\":\"walk\",\"playback\":\"pingpong\"}")));
    }
}
