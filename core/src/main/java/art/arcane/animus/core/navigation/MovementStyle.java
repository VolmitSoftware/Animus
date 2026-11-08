package art.arcane.animus.core.navigation;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * How an NPC moves along a leg: the pose and flags viewers see, its speed against the route speed, and where the planner
 * may take it. Speeds follow vanilla ratios: sneaking and crawling move at 0.3 of a walk, sprinting and sprint-swimming
 * at 1.3. Swimming lets the planner cross water; crawling lets a player body plan through gaps one block high; gliding
 * flies with the elytra pose.
 */
public enum MovementStyle {
    WALK(1), SNEAK(0.3), SPRINT(1.3), SWIM(1.3), CRAWL(0.3), GLIDE(1);

    /** Height of a crawling or swimming player body, as vanilla sizes the swimming pose. */
    public static final double CRAWL_HEIGHT = 0.6;
    private final double ratio;

    MovementStyle(double ratio) { this.ratio = ratio; }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static List<String> keys() { return Arrays.stream(values()).map(MovementStyle::key).toList(); }

    public static Optional<MovementStyle> fromKey(String key) {
        for (MovementStyle style : values()) if (style.key().equals(key)) return Optional.of(style);
        return Optional.empty();
    }

    /** The speed ratio of a step: swimming is faster only in water, every other style everywhere. */
    public double ratio(boolean inWater) { return this == SWIM && !inWater ? 1 : ratio; }

    /** What a swimming NPC does on land: walks there, or keeps to water so a leg over land is blocked. */
    public enum OnLand {
        WALK, AVOID;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * The planner profile for a leg in this style. Swimming adds water, or keeps to it with {@link OnLand#AVOID}; a body that
     * sinks keeps its own profile. Gliding flies. Crawling, and automatic crawling, lower a player body to
     * {@link #CRAWL_HEIGHT} so the plan may pass gaps one block high.
     */
    public MovementProfile profile(MovementProfile profile, boolean player, boolean sinks, OnLand onLand, boolean autoCrawl) {
        Set<MovementProfile.Mode> modes = profile.modes();
        boolean flies = modes.contains(MovementProfile.Mode.FLY);
        if (this == GLIDE) modes = Set.of(MovementProfile.Mode.FLY);
        else if (this == SWIM && !sinks && !flies)
            modes = onLand == OnLand.AVOID ? Set.of(MovementProfile.Mode.SWIM) : Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM);
        boolean walks = modes.contains(MovementProfile.Mode.WALK);
        double height = player && walks && (this == CRAWL || autoCrawl) ? Math.min(profile.height(), CRAWL_HEIGHT) : profile.height();
        boolean climbs = profile.climbs() && walks, doors = profile.opensDoors() && walks;
        if (modes.equals(profile.modes()) && height == profile.height() && climbs == profile.climbs() && doors == profile.opensDoors()) return profile;
        return new MovementProfile(modes, profile.width(), height, profile.stepHeight(), profile.jumpHeight(), profile.maximumFall(), profile.diagonals(), climbs, doors);
    }
}
