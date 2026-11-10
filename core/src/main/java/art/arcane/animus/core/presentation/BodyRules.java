package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.virtual.DragonBody;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wire rules every packet transport shares: body types, reserved IDs, rotation and passenger rosters. */
public final class BodyRules {
    public static final String PLAYER = "minecraft:player";
    /** Every living entity type of the supported Minecraft versions; a transport shows those its server registers. */
    public static final Set<String> LIVING = Set.of(
            "minecraft:allay", "minecraft:armadillo", "minecraft:armor_stand", "minecraft:axolotl", "minecraft:bat", "minecraft:bee",
            "minecraft:blaze", "minecraft:bogged", "minecraft:breeze", "minecraft:camel", "minecraft:camel_husk", "minecraft:cat",
            "minecraft:cave_spider", "minecraft:chicken", "minecraft:cod", "minecraft:copper_golem", "minecraft:cow", "minecraft:creaking",
            "minecraft:creeper", "minecraft:dolphin", "minecraft:donkey", "minecraft:drowned", "minecraft:elder_guardian", "minecraft:ender_dragon",
            "minecraft:enderman", "minecraft:endermite", "minecraft:evoker", "minecraft:fox", "minecraft:frog", "minecraft:ghast", "minecraft:giant",
            "minecraft:glow_squid", "minecraft:goat", "minecraft:guardian", "minecraft:happy_ghast", "minecraft:hoglin", "minecraft:horse",
            "minecraft:husk", "minecraft:illusioner", "minecraft:iron_golem", "minecraft:llama", "minecraft:magma_cube", "minecraft:mannequin",
            "minecraft:mooshroom", "minecraft:mule", "minecraft:nautilus", "minecraft:ocelot", "minecraft:panda", "minecraft:parched",
            "minecraft:parrot", "minecraft:phantom", "minecraft:pig", "minecraft:piglin", "minecraft:piglin_brute", "minecraft:pillager",
            "minecraft:player", "minecraft:polar_bear", "minecraft:pufferfish", "minecraft:rabbit", "minecraft:ravager", "minecraft:salmon",
            "minecraft:sheep", "minecraft:shulker", "minecraft:silverfish", "minecraft:skeleton", "minecraft:skeleton_horse", "minecraft:slime",
            "minecraft:sniffer", "minecraft:snow_golem", "minecraft:spider", "minecraft:squid", "minecraft:stray", "minecraft:strider", "minecraft:sulfur_cube",
            "minecraft:tadpole", "minecraft:trader_llama", "minecraft:tropical_fish", "minecraft:turtle", "minecraft:vex", "minecraft:villager",
            "minecraft:vindicator", "minecraft:wandering_trader", "minecraft:warden", "minecraft:witch", "minecraft:wither",
            "minecraft:wither_skeleton", "minecraft:wolf", "minecraft:zoglin", "minecraft:zombie", "minecraft:zombie_horse",
            "minecraft:zombie_nautilus", "minecraft:zombie_villager", "minecraft:zombified_piglin");
    /** The body types a transport may present: every living entity, and every other body with settings of its own. */
    public static final Set<String> TYPES;
    static {
        Set<String> types = new java.util.TreeSet<>(LIVING);
        types.addAll(art.arcane.animus.core.npc.BodyVariant.types());
        TYPES = Set.copyOf(types);
    }
    public static final int NO_REAL_RIDER = -1;
    /** The least width and height a body's hitbox has, for displays that have no size of their own. */
    public static final double MINIMUM_SIZE = 0.25;
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

    private static final Set<EquipmentSlot> WORN = Collections.unmodifiableSet(EnumSet.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET));
    /** Mobs the client draws in armor and with both hands, as a player. */
    private static final List<String> HUMANOIDS = List.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:giant", "minecraft:zombified_piglin",
            "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton", "minecraft:parched", "minecraft:piglin_brute", "minecraft:mannequin");
    private static final Set<EquipmentSlot> EVERY_SLOT = Collections.unmodifiableSet(EnumSet.allOf(EquipmentSlot.class));
    /** The equipment slots the client draws on each presented body; the web editor's {@code mcDrawnSlots} is the same map. */
    private static final Map<String, Set<EquipmentSlot>> SLOTS;
    static {
        Map<String, Set<EquipmentSlot>> slots = new java.util.HashMap<>(Map.of(PLAYER, WORN, "minecraft:armor_stand", WORN,
                "minecraft:horse", slots(EquipmentSlot.BODY, EquipmentSlot.SADDLE), "minecraft:llama", slots(EquipmentSlot.BODY),
                "minecraft:zombie_villager", WORN, "minecraft:piglin", WORN, "minecraft:villager", slots(EquipmentSlot.HEAD, EquipmentSlot.MAIN_HAND),
                "minecraft:vex", slots(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND), "minecraft:wolf", slots(EquipmentSlot.BODY)));
        for (String held : List.of("minecraft:allay", "minecraft:fox", "minecraft:evoker", "minecraft:copper_golem")) slots.put(held, slots(EquipmentSlot.MAIN_HAND));
        for (String saddled : List.of("minecraft:pig", "minecraft:donkey", "minecraft:mule", "minecraft:camel")) slots.put(saddled, slots(EquipmentSlot.SADDLE));
        for (String humanoid : HUMANOIDS) slots.put(humanoid, WORN);
        for (String armed : List.of("minecraft:pillager", "minecraft:vindicator", "minecraft:illusioner", "minecraft:witch")) slots.put(armed, slots(EquipmentSlot.MAIN_HAND));
        // A body with settings of its own draws no equipment unless listed above; any other living body keeps every slot.
        for (String type : art.arcane.animus.core.npc.BodyVariant.types()) slots.putIfAbsent(type, Set.of());
        for (String type : List.of("minecraft:turtle", "minecraft:frog", "minecraft:armadillo", DragonBody.TYPE)) slots.putIfAbsent(type, Set.of());
        SLOTS = Map.copyOf(slots);
    }
    private static Set<EquipmentSlot> slots(EquipmentSlot... shown) { return Collections.unmodifiableSet(EnumSet.copyOf(List.of(shown))); }

    /** The slots a body of {@code type} shows, in slot order. A body {@link #limitedEquipment} does not name keeps every slot. */
    public static Set<EquipmentSlot> equipmentSlots(String type) { return SLOTS.getOrDefault(type, EVERY_SLOT); }

    /** The presented body types whose slots are limited, each with the slots it shows. */
    public static Map<String, Set<EquipmentSlot>> limitedEquipment() { return SLOTS; }

    /** Only the items of {@code items} a body of {@code type} shows; nothing else is ever sent. */
    public static <I> Map<EquipmentSlot, I> shownEquipment(String type, Map<EquipmentSlot, I> items) {
        Set<EquipmentSlot> shown = equipmentSlots(type);
        if (shown.containsAll(items.keySet())) return items;
        Map<EquipmentSlot, I> kept = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<EquipmentSlot, I> item : items.entrySet()) if (shown.contains(item.getKey())) kept.put(item.getKey(), item.getValue());
        return kept;
    }

    /** Why a body of {@code type} cannot show {@code slot}: the body, the slot and the slots it does show. */
    public static String slotRefusal(String type, EquipmentSlot slot) {
        Set<EquipmentSlot> shown = equipmentSlots(type);
        if (shown.isEmpty()) return type + " bodies show no equipment";
        return type + " bodies cannot show " + slot.key() + "; they show "
                + String.join(", ", shown.stream().map(EquipmentSlot::key).toList()).replaceFirst(", ([^,]+)$", " and $1");
    }

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
