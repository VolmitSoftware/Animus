package art.arcane.animus.core.navigation;

import java.util.Objects;
import java.util.Set;

/** What a native body type can do: flyers fly, aquatic bodies only swim, most others walk and swim at the surface. */
public final class BodyMobility {
    public static final double ORDINARY_STEP = 0.6, LARGE_STEP = 1, JUMP = 1.25;
    public static final int FALL = 3;
    private static final Set<String> FLYERS = Set.of("minecraft:ender_dragon", "minecraft:phantom", "minecraft:bat", "minecraft:parrot",
            "minecraft:bee", "minecraft:allay", "minecraft:vex", "minecraft:ghast", "minecraft:happy_ghast", "minecraft:blaze", "minecraft:wither");
    private static final Set<String> AQUATIC = Set.of("minecraft:dolphin", "minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish",
            "minecraft:pufferfish", "minecraft:squid", "minecraft:glow_squid", "minecraft:guardian", "minecraft:elder_guardian", "minecraft:tadpole");
    private static final Set<String> SINKERS = Set.of("minecraft:armor_stand");
    private BodyMobility() {}

    /** The body's own capabilities, used when a player steers it. */
    public static MovementProfile forBody(String type, double width, double height) {
        Objects.requireNonNull(type, "type");
        Set<MovementProfile.Mode> modes = FLYERS.contains(type) ? Set.of(MovementProfile.Mode.FLY)
                : AQUATIC.contains(type) ? Set.of(MovementProfile.Mode.SWIM)
                : SINKERS.contains(type) ? Set.of(MovementProfile.Mode.WALK)
                : Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM);
        return profile(modes, width, height);
    }
    /** Bodies that fall when nothing holds them up: flyers hover and aquatic bodies keep their depth. */
    public static boolean falls(String type) {
        Objects.requireNonNull(type, "type");
        return !FLYERS.contains(type) && !AQUATIC.contains(type);
    }
    /** Bodies that sink in water instead of swimming. */
    public static boolean sinks(String type) { return SINKERS.contains(Objects.requireNonNull(type, "type")); }
    /** A route's requested mode: walk stays on land, swim also crosses water at the surface (aquatic bodies dive), fly flies. */
    public static MovementProfile forRoute(String type, double width, double height, MovementProfile.Mode mode) {
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(mode, "mode");
        Set<MovementProfile.Mode> modes = switch (mode) {
            case WALK -> Set.of(MovementProfile.Mode.WALK);
            case SWIM -> AQUATIC.contains(type) ? Set.of(MovementProfile.Mode.SWIM) : Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM);
            case FLY -> Set.of(MovementProfile.Mode.FLY);
        };
        return profile(modes, width, height);
    }
    private static MovementProfile profile(Set<MovementProfile.Mode> modes, double width, double height) {
        return new MovementProfile(modes, width, height, width > 1 ? LARGE_STEP : ORDINARY_STEP, JUMP, FALL, true);
    }
}
