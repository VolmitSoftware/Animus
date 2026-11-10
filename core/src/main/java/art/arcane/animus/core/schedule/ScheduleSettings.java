package art.arcane.animus.core.schedule;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * {@code modules["animus:schedule"]}: where an NPC spends each part of the Minecraft day. Each location starts at a
 * world time; the NPC walks to the latest location whose time has passed, draws between locations that share a time by
 * their chances, waits there, wanders around it and applies the location's pose, equipment, skin and commands when it
 * arrives. Locations lie in the NPC's own world.
 */
public record ScheduleSettings(OptionalDouble speed, List<Location> locations) {
    public static final String MODULE = "animus:schedule";
    public static final int MAXIMUM_LOCATIONS = 64, MAXIMUM_COMMANDS = 16, MAXIMUM_COMMAND = 256, MAXIMUM_WANDER = 32, MAXIMUM_AREA = 256, MAXIMUM_SECONDS = 86_400;
    public static final int DAY_TICKS = 24_000;
    public static final String NAME = "[a-z0-9][a-z0-9_-]{0,31}";
    private static final String PATH = "$.modules[\"" + MODULE + "\"]";
    private static final Set<String> FIELDS = Set.of("enabled", "speed", "locations");
    private static final Set<String> LOCATION_FIELDS = Set.of("name", "time", "x", "y", "z", "yaw", "pitch", "chance", "weather", "staySeconds", "wander",
            "pose", "equipment", "clearEquipment", "skin", "skinOnDeparture", "commands");
    private static final Set<String> WANDER_FIELDS = Set.of("radius", "area", "waitSeconds");

    public enum Weather {
        ANY, CLEAR, STORM;
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public boolean allows(boolean storm) { return this == ANY || (this == STORM) == storm; }
    }

    /** Whole seconds from {@code min} to {@code max}, both included. */
    public record Seconds(int min, int max) {
        public int draw(RandomGenerator random) { return min == max ? min : min + random.nextInt(max - min + 1); }
    }

    /** A cuboid of blocks, both corners included. */
    public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    /** Walks to random blocks around the location: within {@code radius} on x and z, or inside {@code area}. */
    public record Wander(int radius, Optional<Area> area, Seconds pause) {
        public Wander { Objects.requireNonNull(area); Objects.requireNonNull(pause); }
    }

    public record Location(Optional<String> name, int time, int x, int y, int z, OptionalDouble yaw, OptionalDouble pitch, int chance, Weather weather,
                           Optional<Seconds> stay, Optional<Wander> wander, Optional<NpcPose> pose, Map<EquipmentSlot, NpcItem> equipment,
                           boolean clearEquipment, Optional<String> skin, boolean skinOnDeparture, List<String> commands) {
        public Location {
            Objects.requireNonNull(name); Objects.requireNonNull(yaw); Objects.requireNonNull(pitch); Objects.requireNonNull(weather);
            Objects.requireNonNull(stay); Objects.requireNonNull(wander); Objects.requireNonNull(pose); Objects.requireNonNull(skin);
            equipment = Map.copyOf(equipment); commands = List.copyOf(commands);
        }
        /** What replies and the log call this location: its name, or its index and time. */
        public String label(int index) { return name.orElse("#" + index) + " at " + time; }
    }

    public ScheduleSettings { Objects.requireNonNull(speed); locations = List.copyOf(locations); }

    public static Optional<ScheduleSettings> of(NpcDefinition definition) { return parse(definition.json()); }

    /**
     * Empty when the module is absent or disabled; throws with the exact field when it is malformed.
     */
    public static Optional<ScheduleSettings> parse(JsonObject definition) {
        if (!definition.has("modules") || !definition.get("modules").isJsonObject()) return Optional.empty();
        JsonElement value = definition.getAsJsonObject("modules").get(MODULE);
        if (value == null) return Optional.empty();
        if (!value.isJsonObject()) throw new IllegalArgumentException(PATH + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException(PATH + "." + key + ": unknown field");
        boolean enabled = !module.has("enabled") || bool(module.get("enabled"), PATH + ".enabled");
        OptionalDouble speed = OptionalDouble.empty();
        if (module.has("speed")) speed = OptionalDouble.of(number(module.get("speed"), PATH + ".speed", 1, 10));
        String type = definition.has("type") && definition.get("type").isJsonPrimitive() ? definition.get("type").getAsString() : "";
        JsonElement declared = module.get("locations");
        if (declared == null || !declared.isJsonArray() || declared.getAsJsonArray().isEmpty() || declared.getAsJsonArray().size() > MAXIMUM_LOCATIONS)
            throw new IllegalArgumentException(PATH + ".locations: expected 1-" + MAXIMUM_LOCATIONS + " locations");
        List<Location> locations = new ArrayList<>();
        JsonArray array = declared.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) locations.add(location(array.get(index), PATH + ".locations[" + index + "]", type));
        if (!enabled) return Optional.empty();
        if (navigates(definition)) throw new IllegalArgumentException(PATH + ": an NPC follows a schedule or an animus:navigation route, not both; turn one off");
        return Optional.of(new ScheduleSettings(speed, locations));
    }

    /**
     * Whether the document sets arrival commands anywhere: in an NPC's own module or in one a trait group gives its members.
     * It reads the JSON as written, enabled or not, so a schedule that is off still counts.
     */
    public static boolean runsCommands(JsonElement document) {
        java.util.ArrayDeque<JsonElement> pending = new java.util.ArrayDeque<>(List.of(document));
        while (!pending.isEmpty()) {
            JsonElement element = pending.removeFirst();
            if (element.isJsonArray()) element.getAsJsonArray().forEach(pending::add);
            else if (element.isJsonObject()) {
                JsonElement module = element.getAsJsonObject().get(MODULE);
                if (module != null && module.isJsonObject() && module.getAsJsonObject().get("locations") instanceof JsonArray locations)
                    for (JsonElement location : locations)
                        if (location.isJsonObject() && location.getAsJsonObject().get("commands") instanceof JsonArray commands && !commands.isEmpty()) return true;
                element.getAsJsonObject().entrySet().forEach((Map.Entry<String, JsonElement> entry) -> pending.add(entry.getValue()));
            }
        }
        return false;
    }

    /** The definition turns on its own route as well, which would fight the schedule for the NPC's legs. */
    private static boolean navigates(JsonObject definition) {
        JsonElement navigation = definition.getAsJsonObject("modules").get("animus:navigation");
        if (navigation == null || !navigation.isJsonObject()) return false;
        JsonElement enabled = navigation.getAsJsonObject().get("enabled");
        return enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && enabled.getAsBoolean();
    }

    private static Location location(JsonElement value, String path, String type) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
        JsonObject location = value.getAsJsonObject();
        for (String key : location.keySet()) if (!LOCATION_FIELDS.contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        Optional<String> name = Optional.empty();
        if (location.has("name")) name = Optional.of(text(location.get("name"), path + ".name", NAME, "1-32 lowercase letters, digits, _ or -"));
        int time = integer(location.get("time"), path + ".time", 0, DAY_TICKS - 1);
        int x = integer(location.get("x"), path + ".x", -30_000_000, 30_000_000);
        int y = integer(location.get("y"), path + ".y", -2048, 2048);
        int z = integer(location.get("z"), path + ".z", -30_000_000, 30_000_000);
        OptionalDouble yaw = location.has("yaw") ? OptionalDouble.of(number(location.get("yaw"), path + ".yaw", -360, 360)) : OptionalDouble.empty();
        OptionalDouble pitch = location.has("pitch") ? OptionalDouble.of(number(location.get("pitch"), path + ".pitch", -90, 90)) : OptionalDouble.empty();
        int chance = location.has("chance") ? integer(location.get("chance"), path + ".chance", 1, 100) : 100;
        Weather weather = Weather.ANY;
        if (location.has("weather")) {
            String key = text(location.get("weather"), path + ".weather", "any|clear|storm", "any, clear or storm");
            weather = Weather.valueOf(key.toUpperCase(Locale.ROOT));
        }
        Optional<Seconds> stay = location.has("staySeconds") ? Optional.of(seconds(location.get("staySeconds"), path + ".staySeconds", 1)) : Optional.empty();
        Optional<Wander> wander = location.has("wander") ? Optional.of(wander(location.get("wander"), path + ".wander")) : Optional.empty();
        Optional<NpcPose> pose = Optional.empty();
        if (location.has("pose")) {
            String key = text(location.get("pose"), path + ".pose", "standing|sneaking|sleeping|sitting", "standing, sneaking, sleeping or sitting");
            NpcPose chosen = NpcPose.fromKey(key).orElseThrow();
            if (!chosen.availableFor(type)) throw new IllegalArgumentException(path + ".pose: only player bodies show " + key);
            pose = Optional.of(chosen);
        }
        Map<EquipmentSlot, NpcItem> equipment = new EnumMap<>(EquipmentSlot.class);
        if (location.has("equipment")) {
            JsonElement slots = location.get("equipment");
            if (!slots.isJsonObject()) throw new IllegalArgumentException(path + ".equipment: expected object of slot to item");
            for (Map.Entry<String, JsonElement> entry : slots.getAsJsonObject().entrySet()) {
                EquipmentSlot slot = EquipmentSlot.fromKey(entry.getKey())
                        .orElseThrow(() -> new IllegalArgumentException(path + ".equipment." + entry.getKey() + ": unknown slot"));
                String body = MinecraftIds.normalize(type);
                if (!BodyRules.equipmentSlots(body).contains(slot))
                    throw new IllegalArgumentException(path + ".equipment." + entry.getKey() + ": " + BodyRules.slotRefusal(body, slot));
                List<String> errors = new ArrayList<>();
                Optional<NpcItem> item = NpcItem.parse(entry.getValue(), path + ".equipment." + entry.getKey(), errors);
                if (item.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
                equipment.put(slot, item.get());
            }
        }
        boolean clearEquipment = location.has("clearEquipment") && bool(location.get("clearEquipment"), path + ".clearEquipment");
        Optional<String> skin = Optional.empty();
        if (location.has("skin")) {
            skin = Optional.of(text(location.get("skin"), path + ".skin", "[a-f0-9]{64}", "a skin file hash from skins/"));
            if (!"minecraft:player".equals(type)) throw new IllegalArgumentException(path + ".skin: only player bodies show skins");
        }
        boolean skinOnDeparture = location.has("skinOnDeparture") && bool(location.get("skinOnDeparture"), path + ".skinOnDeparture");
        List<String> commands = new ArrayList<>();
        if (location.has("commands")) {
            JsonElement list = location.get("commands");
            if (!list.isJsonArray() || list.getAsJsonArray().size() > MAXIMUM_COMMANDS)
                throw new IllegalArgumentException(path + ".commands: expected at most " + MAXIMUM_COMMANDS + " commands");
            for (int index = 0; index < list.getAsJsonArray().size(); index++) {
                JsonElement command = list.getAsJsonArray().get(index);
                String field = path + ".commands[" + index + "]";
                if (!command.isJsonPrimitive() || !command.getAsJsonPrimitive().isString() || command.getAsString().isBlank()
                        || command.getAsString().length() > MAXIMUM_COMMAND || command.getAsString().startsWith("/")
                        || command.getAsString().codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException(field + ": expected a command of 1-" + MAXIMUM_COMMAND + " characters without a leading slash");
                commands.add(command.getAsString());
            }
        }
        return new Location(name, time, x, y, z, yaw, pitch, chance, weather, stay, wander, pose, equipment, clearEquipment, skin, skinOnDeparture, commands);
    }

    private static Wander wander(JsonElement value, String path) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
        JsonObject wander = value.getAsJsonObject();
        for (String key : wander.keySet()) if (!WANDER_FIELDS.contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        if (wander.has("radius") == wander.has("area")) throw new IllegalArgumentException(path + ": expected radius or area, not both");
        int radius = wander.has("radius") ? integer(wander.get("radius"), path + ".radius", 1, MAXIMUM_WANDER) : 0;
        Optional<Area> area = Optional.empty();
        if (wander.has("area")) {
            JsonElement corners = wander.get("area");
            if (!corners.isJsonArray() || corners.getAsJsonArray().size() != 6) throw new IllegalArgumentException(path + ".area: expected [x1, y1, z1, x2, y2, z2]");
            int[] at = new int[6];
            for (int index = 0; index < 6; index++) at[index] = integer(corners.getAsJsonArray().get(index), path + ".area[" + index + "]", -30_000_000, 30_000_000);
            if (Math.abs(at[0] - at[3]) >= MAXIMUM_AREA || Math.abs(at[2] - at[5]) >= MAXIMUM_AREA)
                throw new IllegalArgumentException(path + ".area: at most " + MAXIMUM_AREA + " blocks across");
            area = Optional.of(new Area(Math.min(at[0], at[3]), Math.min(at[1], at[4]), Math.min(at[2], at[5]), Math.max(at[0], at[3]), Math.max(at[1], at[4]), Math.max(at[2], at[5])));
        }
        Seconds wait = wander.has("waitSeconds") ? seconds(wander.get("waitSeconds"), path + ".waitSeconds", 0) : new Seconds(1, 1);
        return new Wander(radius, area, wait);
    }

    /** A whole number of seconds, or {@code [min, max]}. */
    private static Seconds seconds(JsonElement value, String path, int minimum) {
        if (value.isJsonArray()) {
            JsonArray range = value.getAsJsonArray();
            if (range.size() != 2) throw new IllegalArgumentException(path + ": expected seconds or [min, max]");
            int min = integer(range.get(0), path + "[0]", minimum, MAXIMUM_SECONDS), max = integer(range.get(1), path + "[1]", minimum, MAXIMUM_SECONDS);
            if (min > max) throw new IllegalArgumentException(path + ": min above max");
            return new Seconds(min, max);
        }
        int exact = integer(value, path, minimum, MAXIMUM_SECONDS);
        return new Seconds(exact, exact);
    }

    private static int integer(JsonElement value, String path, long min, long max) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long number = value.getAsBigDecimal().longValueExact();
            if (number < min || number > max) throw new ArithmeticException();
            return (int) number;
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(path + ": expected integer " + min + ".." + max); }
    }

    private static double number(JsonElement value, String path, double min, double max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !(value.getAsDouble() >= min && value.getAsDouble() <= max))
            throw new IllegalArgumentException(path + ": expected a number " + (long) min + ".." + (long) max);
        return value.getAsDouble();
    }

    private static boolean bool(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(path + ": expected true or false");
        return value.getAsBoolean();
    }

    private static String text(JsonElement value, String path, String pattern, String expected) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches(pattern))
            throw new IllegalArgumentException(path + ": expected " + expected);
        return value.getAsString();
    }
}
