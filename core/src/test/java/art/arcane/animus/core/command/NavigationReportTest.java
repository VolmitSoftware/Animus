package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import org.junit.jupiter.api.Test;

class NavigationReportTest {
    @Test void theDebugStatusReadsAsASentence() {
        String raw = "Navigation fountain-keeper: walking; route=390fa066-8810-43a0-a570-fe4faaaad7eb; waypoint=0; goal=Vec3[x=4.5, y=64.0, z=2.5]"
                + "; capture=10..12; sampled=400; unavailable=0; position=Vec3[x=3.25, y=64.0, z=1.0]; tick=812; arrivals=0";
        assertEquals("Navigation fountain-keeper: walking to 4.5 64 2.5, now at 3.25 64 1; route 390fa066", NavigationReport.readable(raw));
    }

    @Test void finishedRoutesErrorsAndRecordingsKeepWhatMatters() {
        assertEquals("Navigation guard: finished, now at 1 64 1, 2 arrivals",
                NavigationReport.readable("Navigation guard: finished; position=Vec3[x=1.0, y=64.0, z=1.0]; tick=5; arrivals=2"));
        assertEquals("Navigation guard: blocked: no path, now at 1 64 1; error no path",
                NavigationReport.readable("Navigation guard: blocked: no path; position=Vec3[x=1.0, y=64.0, z=1.0]; tick=5; arrivals=0; route-error=no path"));
        assertEquals("Navigation guard: playing, now at 1 64 1; recording wave 40/200",
                NavigationReport.readable("Navigation guard: playing; position=Vec3[x=1.0, y=64.0, z=1.0]; arrivals=0; recording=wave playback=once cursor=40/200 state=playing"));
        assertEquals("Navigation guard: not active", NavigationReport.readable("Navigation guard: not active"));
    }

    @Test void walkingGoalsRoundTheFeetHeightAndCentreWholeBlocks() {
        assertEquals(new Vec3(10.5, 64, 2.25), WorldCommands.Navigation.goal(new Vec3(10, 63.87, 2.25), MovementProfile.Mode.WALK));
        assertEquals(new Vec3(10.5, 63.87, 2.5), WorldCommands.Navigation.goal(new Vec3(10, 63.87, 2), MovementProfile.Mode.FLY));
    }

    @Test void stopAnswersOnce() {
        assertEquals("Stopped guard's route", WorldCommands.Navigation.stopped("guard", true));
        assertEquals("guard has no route to stop", WorldCommands.Navigation.stopped("guard", false));
    }
}
