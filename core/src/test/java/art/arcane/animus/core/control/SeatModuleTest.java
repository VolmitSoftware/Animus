package art.arcane.animus.core.control;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeatModuleTest {
    private static JsonObject json(String modules) throws IOException {
        return JsonDocuments.object("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"bench",
                 "group":"test","name":"Bench","type":"minecraft:player","spawned":false,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}%s}
                """.formatted(modules.isEmpty() ? "" : ",\"modules\":" + modules));
    }

    @Test void withoutAModuleTheConfiguredDefaultDecides() throws IOException {
        assertFalse(SeatModule.of(json(""), false).enabled());
        SeatModule seat = SeatModule.of(json(""), true);
        assertTrue(seat.enabled());
        assertEquals(SeatModule.Click.RIGHT, seat.click());
        assertEquals("animus.seat", seat.permission());
        assertEquals(SeatModule.DEFAULT_STACK, seat.stack());
        assertTrue(seat.emptyHand());
    }

    @Test void aModuleTurnsTheSeatOnOrOffWhateverTheDefault() throws IOException {
        assertTrue(SeatModule.of(json("{\"animus:seat\":{}}"), false).enabled());
        assertFalse(SeatModule.of(json("{\"animus:seat\":{\"enabled\":false}}"), true).enabled());
        SeatModule custom = SeatModule.of(json("{\"animus:seat\":{\"click\":\"sneak-right\",\"permission\":\"town.bench\",\"stack\":2,\"emptyHand\":false}}"), false);
        assertEquals(new SeatModule(true, SeatModule.Click.SNEAK_RIGHT, "town.bench", 2, false), custom);
    }

    @Test void onlyTheConfiguredClickSitsDown() throws IOException {
        SeatModule plain = SeatModule.of(json("{\"animus:seat\":{}}"), false), sneak = SeatModule.of(json("{\"animus:seat\":{\"click\":\"sneak-right\"}}"), false);
        assertTrue(plain.sitsOn(false));
        assertFalse(plain.sitsOn(true));
        assertTrue(sneak.sitsOn(true));
        assertFalse(sneak.sitsOn(false));
        assertFalse(SeatModule.byDefault(false).sitsOn(false));
    }

    @Test void definitionsRejectMalformedSeatsWithTheExactField() throws IOException {
        for (String bad : List.of("3", "{\"click\":\"left\"}", "{\"stack\":0}", "{\"stack\":9}", "{\"stack\":1.5}", "{\"permission\":\"Bad Node\"}", "{\"enabled\":\"yes\"}", "{\"chair\":1}")) {
            List<String> errors = NpcDefinition.validate(json("{\"animus:seat\":" + bad + "}"));
            assertTrue(errors.stream().anyMatch((String error) -> error.startsWith("$.modules.animus:seat")), bad + " -> " + errors);
        }
        assertEquals(List.of("$.modules.animus:seat.click: a right-click mounts this NPC through riding; use sneak-right for the seat"),
                NpcDefinition.validate(json("{\"animus:riding\":{\"enabled\":true},\"animus:seat\":{}}")));
        assertEquals(List.of(), NpcDefinition.validate(json("{\"animus:riding\":{\"enabled\":true},\"animus:seat\":{\"click\":\"sneak-right\"}}")));
    }
}
