package art.arcane.animus.core.npc;

import java.util.Locale;
import java.util.Optional;

/**
 * A vanilla animation an NPC shows: a one-off gesture every viewer sees once, or a pose or held-item change that lasts
 * until another changes it. {@code gesture} is the client's animate action, or {@link #HURT_FLASH} for the hurt flash.
 */
public enum NpcAnimation {
    SWING(0, null), SWING_OFFHAND(3, null), HURT(NpcAnimation.HURT_FLASH, null), CRIT(4, null), MAGIC_CRIT(5, null),
    WAKE(2, NpcPose.STANDING), SNEAK(-1, NpcPose.SNEAKING), SLEEP(-1, NpcPose.SLEEPING), SIT(-1, NpcPose.SITTING), STAND(-1, NpcPose.STANDING),
    USE_ITEM(-1, null), STOP_USING_ITEM(-1, null);

    /** The gesture code of the hurt flash, which is a damage event rather than an animate packet. */
    public static final int HURT_FLASH = -2;
    private final int gesture;
    private final NpcPose pose;

    NpcAnimation(int gesture, NpcPose pose) { this.gesture = gesture; this.pose = pose; }

    /** The animate action or {@link #HURT_FLASH}; -1 when the animation is not a gesture. */
    public int gesture() { return gesture; }
    /** The pose the animation leaves the NPC in, or empty when it keeps its pose. */
    public Optional<NpcPose> pose() { return Optional.ofNullable(pose); }
    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<NpcAnimation> parse(String key) {
        for (NpcAnimation animation : values()) if (animation.key().equals(key)) return Optional.of(animation);
        return Optional.empty();
    }
}
