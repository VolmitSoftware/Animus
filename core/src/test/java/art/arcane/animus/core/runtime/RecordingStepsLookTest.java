package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.recording.RecordingFrame;
import art.arcane.animus.core.recording.RecordingPose;
import org.junit.jupiter.api.Test;

/** What a recorded pose shows on a player body and on any other body. */
class RecordingStepsLookTest {
    private static RecordingPose pose(int flags) { return new RecordingPose(0, 64, 0, 0, 0, flags); }

    @Test void aPlayerBodyShowsThePoseAndTheSprintAndGlideFlags() {
        assertEquals(NpcPose.SNEAKING, RecordingSteps.pose(pose(RecordingFrame.SNEAKING), NpcPose.STANDING));
        assertEquals(0, RecordingSteps.motion(pose(RecordingFrame.SNEAKING), true));
        assertEquals(BodyLook.SPRINTING | BodyLook.GLIDING, RecordingSteps.motion(pose(RecordingFrame.SPRINTING | RecordingFrame.GLIDING), true));
    }

    @Test void anotherBodyShowsTheSneakAndSprintFlags() {
        assertEquals(BodyLook.SNEAKING, RecordingSteps.motion(pose(RecordingFrame.SNEAKING), false));
        assertEquals(BodyLook.SPRINTING, RecordingSteps.motion(pose(RecordingFrame.SPRINTING | RecordingFrame.GLIDING), false));
    }
}
