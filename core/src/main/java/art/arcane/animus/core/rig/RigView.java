package art.arcane.animus.core.rig;

import java.util.Arrays;

/**
 * What one viewer has received of one NPC's bones. Owned by the viewer's owner; picks changed bones round-robin so a
 * short packet budget delays bones fairly instead of starving the last ones.
 */
public final class RigView {
    private final long[] sent;
    private int cursor;

    public RigView(int bones) {
        sent = new long[bones];
        Arrays.fill(sent, -1);
    }

    /** Bones whose target changed since they were last sent, at most {@code budget}, starting after the last bone sent. */
    public int[] due(RigFrame frame, int budget) {
        int count = Math.min(sent.length, frame.bones());
        int[] picked = new int[Math.max(0, Math.min(budget, count))];
        int found = 0;
        for (int step = 0; step < count && found < picked.length; step++) {
            int bone = (cursor + step) % count;
            if (frame.revision(bone) > sent[bone]) picked[found++] = bone;
        }
        return Arrays.copyOf(picked, found);
    }

    public void sent(RigFrame frame, int[] bones) {
        for (int bone : bones) sent[bone] = frame.revision(bone);
        if (bones.length > 0) cursor = (bones[bones.length - 1] + 1) % Math.max(1, sent.length);
    }

    /** A send failed: these bones go again with the next frame or budget. */
    public void unsent(int[] bones) {
        for (int bone : bones) if (bone >= 0 && bone < sent.length) sent[bone] = -1;
    }

    /** Every bone was just spawned with this frame's targets. */
    public void shown(RigFrame frame) {
        for (int bone = 0; bone < Math.min(sent.length, frame.bones()); bone++) sent[bone] = frame.revision(bone);
    }
}
