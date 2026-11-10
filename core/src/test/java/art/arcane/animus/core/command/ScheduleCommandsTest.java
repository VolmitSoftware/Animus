package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class ScheduleCommandsTest {
    @Test void timesReadAsTicksOrTheWordsTimeSetUses() {
        assertEquals(1000, ScheduleCommands.time("day"));
        assertEquals(13000, ScheduleCommands.time("NIGHT"));
        assertEquals(0, ScheduleCommands.time("0"));
        assertEquals(23999, ScheduleCommands.time("23999"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCommands.time("24000"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCommands.time("dusk"));
    }

    @Test void staysReadAsSecondsOrARange() {
        assertEquals(new JsonPrimitive(60), ScheduleCommands.seconds("60"));
        assertEquals("[60,120]", ScheduleCommands.seconds("60-120").toString());
        assertThrows(IllegalArgumentException.class, () -> ScheduleCommands.seconds("120-60"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCommands.seconds("0"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCommands.seconds("soon"));
    }
}
