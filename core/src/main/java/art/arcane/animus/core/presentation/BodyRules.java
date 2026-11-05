package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.virtual.DragonBody;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Wire rules every packet transport shares: body types, reserved IDs, rotation and passenger rosters. */
public final class BodyRules {
    public static final String PLAYER = "minecraft:player";
    /** The body types a transport may present. */
    public static final Set<String> TYPES = Set.of(PLAYER, "minecraft:armor_stand", "minecraft:horse", "minecraft:turtle", "minecraft:frog",
            "minecraft:llama", "minecraft:armadillo", DragonBody.TYPE);
    public static final int NO_REAL_RIDER = -1;
    public static final int MAX_PASSENGER_IDS = 16;
    /** Bodies of one viewer and outstanding operations a transport accepts. */
    public static final int MAX_BODIES_PER_VIEWER = 256;
    public static final int MAX_OPERATIONS = 32;
    /** Minimum time between two accepted interactions of one viewer. */
    public static final long MIN_INPUT_INTERVAL_NANOS = 100_000_000L;

    private BodyRules() {}

    public static int reservedIds(String typeKey) { return typeKey.equals(DragonBody.TYPE) ? DragonBody.RESERVED_IDS : 1; }
    public static float wireYaw(String typeKey, float yaw) { return typeKey.equals(DragonBody.TYPE) ? DragonBody.wireYaw(yaw) : yaw; }

    /** A body's own text display always stays in its roster, after every other passenger. */
    public static int[] withText(int[] roster, int displayId) {
        return displayId < 0 ? roster : withText(roster, new int[]{displayId});
    }

    /** A body's own text displays always stay in its roster, after every other passenger, in row order. */
    public static int[] withText(int[] roster, int[] displayIds) {
        if (displayIds.length == 0) return roster;
        // The rows count as one passenger slot: a body keeps room for its text however many rows it has.
        if (roster.length + 1 > MAX_PASSENGER_IDS) throw new IllegalArgumentException("Passenger list must contain at most " + MAX_PASSENGER_IDS + " bodies");
        int[] result = Arrays.copyOf(roster, roster.length + displayIds.length);
        System.arraycopy(displayIds, 0, result, roster.length, displayIds.length);
        return result;
    }

    /** The real rider is identified by its own server entity, never by a fabricated virtual body. */
    public static int[] passengerRoster(List<Integer> virtualPassengers, int realRider) {
        if (realRider == NO_REAL_RIDER) return virtualPassengers.stream().mapToInt(Integer::intValue).toArray();
        if (virtualPassengers.contains(realRider)) throw new IllegalArgumentException("A real rider cannot share a native ID with a virtual body");
        if (virtualPassengers.size() + 1 > MAX_PASSENGER_IDS) throw new IllegalArgumentException("Passenger list must contain at most " + MAX_PASSENGER_IDS + " bodies");
        int[] roster = new int[virtualPassengers.size() + 1];
        for (int i = 0; i < virtualPassengers.size(); i++) roster[i] = virtualPassengers.get(i);
        roster[virtualPassengers.size()] = realRider;
        return roster;
    }

    /** Native PLAYER_NAME uses stringUtf8(16), not the authenticated-login username policy. */
    public static void validateProfileName(String name) {
        if (name == null || name.isBlank() || name.length() > 16 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Player profile name must contain 1..16 characters without control characters");
    }

    /** Which vanilla class holds a body type's baby flag: each keeps its own {@code DATA_BABY_ID}. */
    public enum BabyFlag { AGEABLE, ZOMBIE, PIGLIN, ZOGLIN }
    private static final Set<String> AGEABLE = Set.of("minecraft:armadillo", "minecraft:axolotl", "minecraft:bee", "minecraft:camel", "minecraft:cat",
            "minecraft:chicken", "minecraft:cow", "minecraft:mooshroom", "minecraft:dolphin", "minecraft:donkey", "minecraft:fox", "minecraft:frog",
            "minecraft:goat", "minecraft:glow_squid", "minecraft:happy_ghast", "minecraft:hoglin", "minecraft:horse", "minecraft:llama", "minecraft:mule",
            "minecraft:ocelot", "minecraft:panda", "minecraft:parrot", "minecraft:pig", "minecraft:polar_bear", "minecraft:rabbit", "minecraft:sheep",
            "minecraft:skeleton_horse", "minecraft:sniffer", "minecraft:squid", "minecraft:strider", "minecraft:trader_llama", "minecraft:turtle",
            "minecraft:villager", "minecraft:wandering_trader", "minecraft:wolf", "minecraft:zombie_horse", "minecraft:camel_husk", "minecraft:nautilus",
            "minecraft:zombie_nautilus");
    private static final Set<String> ZOMBIES = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager");

    /** The class whose baby flag a body type uses; empty for bodies with no baby form. */
    public static java.util.Optional<BabyFlag> babyFlag(String type) {
        if (AGEABLE.contains(type)) return java.util.Optional.of(BabyFlag.AGEABLE);
        if (ZOMBIES.contains(type)) return java.util.Optional.of(BabyFlag.ZOMBIE);
        if (type.equals("minecraft:piglin")) return java.util.Optional.of(BabyFlag.PIGLIN);
        if (type.equals("minecraft:zoglin")) return java.util.Optional.of(BabyFlag.ZOGLIN);
        return java.util.Optional.empty();
    }
    public static boolean canBeBaby(String type) { return babyFlag(type).isPresent(); }

    /** The vanilla {@code EquipmentSlot} constant name. */
    public static String nativeSlot(EquipmentSlot slot) {
        return switch (slot) {
            case MAIN_HAND -> "MAINHAND";
            case OFF_HAND -> "OFFHAND";
            case HEAD -> "HEAD";
            case CHEST -> "CHEST";
            case LEGS -> "LEGS";
            case FEET -> "FEET";
            case BODY -> "BODY";
            case SADDLE -> "SADDLE";
        };
    }
}
