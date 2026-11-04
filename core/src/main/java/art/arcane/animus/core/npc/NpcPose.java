package art.arcane.animus.core.npc;

import java.util.Locale;
import java.util.Optional;

/** Body pose declared in a definition. Only standing applies to every body type. */
public enum NpcPose {
    STANDING, SNEAKING, SLEEPING, SWIMMING, SITTING;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public boolean availableFor(String type) { return this == STANDING || type.equals("minecraft:player"); }

    public static Optional<NpcPose> fromKey(String key) {
        for (NpcPose pose : values()) if (pose.key().equals(key)) return Optional.of(pose);
        return Optional.empty();
    }
}
