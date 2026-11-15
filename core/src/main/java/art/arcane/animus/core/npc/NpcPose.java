package art.arcane.animus.core.npc;

import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.virtual.DragonBody;
import java.util.Locale;
import java.util.Optional;

/**
 * Body pose declared in a definition. Standing applies to every body and sitting to every living body but the dragon;
 * the rest show only on player bodies. Crawling, bellyflop and spinning are the poses GSit gives players.
 */
public enum NpcPose {
    STANDING, SNEAKING, SLEEPING, SWIMMING, SITTING, CRAWLING, BELLYFLOP, SPINNING;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public boolean availableFor(String type) {
        return switch (this) {
            case STANDING -> true;
            case SITTING -> BodyRules.LIVING.contains(type) && !type.equals(DragonBody.TYPE);
            default -> type.equals(BodyRules.PLAYER);
        };
    }

    /** The bodies that can show this pose, as a refusal names them. */
    public String bodies() { return this == SITTING ? "living bodies other than the ender dragon" : this == STANDING ? "every body" : "minecraft:player bodies"; }

    public static Optional<NpcPose> fromKey(String key) {
        for (NpcPose pose : values()) if (pose.key().equals(key)) return Optional.of(pose);
        return Optional.empty();
    }

    /** Every key, in declaration order, as a refusal lists them. */
    public static String keys() {
        StringBuilder keys = new StringBuilder();
        NpcPose[] all = values();
        for (int i = 0; i < all.length; i++) keys.append(i == 0 ? "" : i == all.length - 1 ? " or " : ", ").append(all[i].key());
        return keys.toString();
    }
}
