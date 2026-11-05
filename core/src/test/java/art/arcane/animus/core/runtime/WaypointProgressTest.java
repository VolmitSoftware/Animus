package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.WaypointSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointProgressTest {
    private static WaypointSettings settings(boolean loop, int end) throws Exception {
        return WaypointSettings.document("""
                {"schemaVersion":1,"loop":%s,"points":[{"x":0,"y":1,"z":0},{"x":%d,"y":1,"z":0}]}
                """.formatted(loop,end));
    }
    @Test void unchangedReloadRetainsCompletedOnceRouteAndItsLastValidData() throws Exception {
        var valid=settings(false,2);var progress=new WaypointProgress(valid);
        assertEquals(1,progress.arrived(0).orElseThrow());assertTrue(progress.arrived(1).isEmpty());assertTrue(progress.complete());
        assertFalse(progress.reload(settings(false,2)));assertTrue(progress.complete());assertSame(valid,progress.settings());
        assertThrows(IllegalStateException.class,()->progress.arrived(0));
    }
    @Test void changedValidRouteRestartsAtFirstPointAndRejectsOldArrival() throws Exception {
        var progress=new WaypointProgress(settings(false,2));progress.arrived(0);progress.arrived(1);
        assertTrue(progress.reload(settings(false,4)));assertFalse(progress.complete());
        assertThrows(IllegalStateException.class,()->progress.arrived(1));assertEquals(1,progress.arrived(0).orElseThrow());
    }
    @Test void loopingRouteKeepsOrderedProgressAcrossEqualReload() throws Exception {
        var progress=new WaypointProgress(settings(true,2));assertEquals(1,progress.arrived(0).orElseThrow());
        assertFalse(progress.reload(settings(true,2)));assertEquals(0,progress.arrived(1).orElseThrow());
        assertFalse(progress.complete());assertEquals(1,progress.arrived(0).orElseThrow());
    }
}
