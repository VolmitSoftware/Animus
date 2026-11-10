package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Settings only one body type has, each in a definition field named after the body: a horse's coat, a sheep's wool,
 * a villager's profession, a slime's size, a player's skin layers and the rest. A setting left out keeps the client's
 * own default, so nothing is sent for it.
 *
 * @param field   the definition field the settings came from, empty for none
 * @param choices chosen values by setting key; a number setting holds its decimal text
 * @param flags   boolean settings that are on
 */
public record BodyVariant(String field, Map<String, String> choices, Set<String> flags) {
    public static final BodyVariant NONE = new BodyVariant("", Map.of(), Set.of());
    /** The sixteen dye colours in wire order. */
    public static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black");
    /** A part that shows by default and may be hidden. */
    public static final List<String> SHOWN = List.of("shown", "hidden");

    /** What a setting holds: a value from a list, a boolean, a whole number, a block state, an item, display text or a namespaced id. */
    public enum Kind { CHOICE, FLAG, NUMBER, BLOCK, ITEM, TEXT, ID }

    /** A block state such as {@code minecraft:oak_stairs[facing=east]}. */
    public static final String BLOCK_STATE = "([a-z0-9_.-]+:)?[a-z0-9_./-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\\])?";
    /** A namespaced id such as {@code minecraft:kebab}. */
    public static final String ID = "([a-z0-9_.-]+:)?[a-z0-9_./-]+";

    /**
     * One setting: its key and kind, and its values in wire order for a choice, or the range of a whole number from
     * {@code minimum} to {@code maximum}.
     */
    public record Setting(String key, Kind kind, List<String> values, String description, int minimum, int maximum) {
        public Setting { values = List.copyOf(values); java.util.Objects.requireNonNull(kind); }
        public Setting(String key, List<String> values, String description) { this(key, values.isEmpty() ? Kind.FLAG : Kind.CHOICE, values, description, 0, 0); }
        public Setting(String key, List<String> values, String description, int minimum, int maximum) { this(key, Kind.NUMBER, values, description, minimum, maximum); }
        public Setting(String key, Kind kind, String description) { this(key, kind, List.of(), description, 0, 0); }
        public boolean number() { return kind == Kind.NUMBER; }
        public boolean flag() { return kind == Kind.FLAG; }
    }

    /** A body type's own definition field and its settings. */
    public record Field(String field, String type, List<Setting> settings, String description) {
        public Optional<Setting> setting(String key) { return settings.stream().filter((Setting setting) -> setting.key().equals(key)).findFirst(); }
    }

    /** Values are in the order of the vanilla enums, so a value's index is its wire ID unless a transport says otherwise. */
    public static final Map<String, Field> FIELDS;
    static {
        Map<String, Field> fields = new LinkedHashMap<>();
        put(fields, "horse", "minecraft:horse", "minecraft:horse bodies only: the coat. The saddle and armour are the saddle and body equipment slots.",
                choice("color", List.of("white", "creamy", "chestnut", "brown", "black", "gray", "dark_brown"), "The coat colour. white by default."),
                choice("markings", List.of("none", "white", "white_field", "white_dots", "black_dots"), "White or black patterns on the coat. none by default."));
        put(fields, "llama", "minecraft:llama", "minecraft:llama bodies only. The carpet it wears is the body equipment slot.",
                choice("variant", List.of("creamy", "white", "brown", "gray"), "The wool colour. creamy by default."),
                flag("chest", "Carry a chest on its back."));
        put(fields, "frog", "minecraft:frog", "minecraft:frog bodies only.",
                choice("variant", List.of("temperate", "warm", "cold"), "temperate (orange) by default, warm (white) or cold (green)."));
        put(fields, "armadillo", "minecraft:armadillo", "minecraft:armadillo bodies only.",
                choice("state", List.of("idle", "rolling", "scared", "unrolling"), "scared shows it rolled up. idle by default."));
        put(fields, "dragon", "minecraft:ender_dragon", "minecraft:ender_dragon bodies only. A moving dragon always flaps.",
                choice("phase", List.of("hovering", "sitting"), "How it rests when it is not moving: hovering in the air or sitting with folded wings. "
                        + "By default it sits when something is under it and hovers otherwise."));
        put(fields, "player", "minecraft:player", "minecraft:player bodies only: the game mode and the parts of the skin's outer layer to hide. Every layer shows by default.",
                choice("gameMode", List.of("survival", "creative", "adventure", "spectator"), "spectator shows only a see-through head. survival by default."), layer("cape"), layer("hat"), layer("jacket"), layer("leftSleeve"), layer("rightSleeve"), layer("leftPants"), layer("rightPants"));
        put(fields, "sheep", "minecraft:sheep", "minecraft:sheep bodies only.",
                choice("color", DYES, "The wool colour. white by default."), flag("sheared", "Shorn wool."));
        put(fields, "wolf", "minecraft:wolf", "minecraft:wolf bodies only.",
                choice("variant", List.of("pale", "spotted", "snowy", "black", "ashen", "rusty", "woods", "chestnut", "striped"), "The coat. pale by default."),
                choice("collar", DYES, "A collar of this colour, which shows the wolf as tamed."),
                flag("sitting", "Sits."), flag("angry", "Angry eyes and raised fur."), flag("interested", "Tilts its head."));
        put(fields, "cat", "minecraft:cat", "minecraft:cat bodies only.",
                choice("variant", List.of("tabby", "black", "red", "siamese", "british_shorthair", "calico", "persian", "ragdoll", "white", "jellie", "all_black"),
                        "The coat."),
                choice("collar", DYES, "A collar of this colour, which shows the cat as tamed."), flag("sitting", "Sits."), flag("lying", "Lies down."));
        put(fields, "parrot", "minecraft:parrot", "minecraft:parrot bodies only.",
                choice("variant", List.of("red_blue", "blue", "green", "yellow_blue", "gray"), "The feathers. red_blue by default."), flag("sitting", "Sits."));
        put(fields, "fox", "minecraft:fox", "minecraft:fox bodies only.",
                choice("type", List.of("red", "snow"), "red by default."), flag("sitting", "Sits."), flag("crouching", "Crouches."),
                flag("interested", "Tilts its head."), flag("pouncing", "Leaps."), flag("sleeping", "Sleeps curled up."), flag("faceplanted", "Head stuck in the ground."));
        put(fields, "axolotl", "minecraft:axolotl", "minecraft:axolotl bodies only.",
                choice("variant", List.of("lucy", "wild", "gold", "cyan", "blue"), "lucy (pink) by default."), flag("playingDead", "Lies on its back."));
        put(fields, "rabbit", "minecraft:rabbit", "minecraft:rabbit bodies only.",
                choice("type", List.of("brown", "white", "black", "white_splotched", "gold", "salt", "evil"), "The fur. evil is the killer bunny."));
        Setting[] villager = {
                choice("type", List.of("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga"), "The biome clothes. plains by default."),
                choice("profession", List.of("none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher", "leatherworker",
                        "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith"), "The work clothes. none by default."),
                choice("level", List.of("novice", "apprentice", "journeyman", "expert", "master"), "The badge. novice by default.")};
        put(fields, "villager", "minecraft:villager", "minecraft:villager bodies only.", villager);
        put(fields, "zombieVillager", "minecraft:zombie_villager", "minecraft:zombie_villager bodies only.", villager);
        put(fields, "creeper", "minecraft:creeper", "minecraft:creeper bodies only.", flag("powered", "The charged glow."));
        put(fields, "slime", "minecraft:slime", "minecraft:slime bodies only.", number("size", 1, 127, "1 is the smallest. The client picks by default."));
        put(fields, "magmaCube", "minecraft:magma_cube", "minecraft:magma_cube bodies only.", number("size", 1, 127, "1 is the smallest. The client picks by default."));
        put(fields, "phantom", "minecraft:phantom", "minecraft:phantom bodies only.", number("size", 0, 64, "0 by default."));
        put(fields, "mooshroom", "minecraft:mooshroom", "minecraft:mooshroom bodies only.", choice("variant", List.of("red", "brown"), "red by default."));
        List<String> genes = List.of("normal", "lazy", "worried", "playful", "brown", "weak", "aggressive");
        put(fields, "panda", "minecraft:panda", "minecraft:panda bodies only.",
                choice("mainGene", genes, "normal by default."), choice("hiddenGene", genes, "normal by default."),
                flag("sitting", "Sits."), flag("rolling", "Rolls over."), flag("sneezing", "Sneezes."), flag("eating", "Eats."));
        put(fields, "goat", "minecraft:goat", "minecraft:goat bodies only.",
                choice("leftHorn", SHOWN, "shown by default."), choice("rightHorn", SHOWN, "shown by default."), flag("screaming", "A screaming goat."));
        List<String> climates = List.of("temperate", "warm", "cold");
        put(fields, "pig", "minecraft:pig", "minecraft:pig bodies only.", choice("variant", climates, "temperate by default."));
        put(fields, "cow", "minecraft:cow", "minecraft:cow bodies only.", choice("variant", climates, "temperate by default."));
        put(fields, "chicken", "minecraft:chicken", "minecraft:chicken bodies only.", choice("variant", climates, "temperate by default."));
        put(fields, "shulker", "minecraft:shulker", "minecraft:shulker bodies only.",
                choice("color", DYES, "Purple with no colour by default."), number("peek", 0, 100, "How far the shell is open. 0 by default."));
        put(fields, "pufferfish", "minecraft:pufferfish", "minecraft:pufferfish bodies only.",
                choice("puff", List.of("deflated", "half", "full"), "deflated by default."));
        put(fields, "tropicalFish", "minecraft:tropical_fish", "minecraft:tropical_fish bodies only.",
                choice("pattern", List.of("kob", "sunstreak", "snooper", "dasher", "brinely", "spotty", "flopper", "stripey", "glitter", "blockfish", "betty", "clayfish"),
                        "The shape and pattern. kob by default."),
                choice("baseColor", DYES, "white by default."), choice("patternColor", DYES, "white by default."));
        put(fields, "snowGolem", "minecraft:snow_golem", "minecraft:snow_golem bodies only.", choice("pumpkin", SHOWN, "shown by default."));
        put(fields, "polarBear", "minecraft:polar_bear", "minecraft:polar_bear bodies only.", flag("standing", "Rears up."));
        put(fields, "piglin", "minecraft:piglin", "minecraft:piglin bodies only.", flag("dancing", "Dances."));
        put(fields, "allay", "minecraft:allay", "minecraft:allay bodies only.", flag("dancing", "Dances."));
        put(fields, "bee", "minecraft:bee", "minecraft:bee bodies only.",
                flag("nectar", "Pollen on its body."), flag("stung", "Has lost its stinger."), flag("angry", "Red eyes."));
        put(fields, "vex", "minecraft:vex", "minecraft:vex bodies only.", flag("charging", "The charging look."));
        put(fields, "evoker", "minecraft:evoker", "minecraft:evoker bodies only.",
                choice("spell", List.of("none", "summon_vex", "fangs", "wololo", "disappear", "blindness"), "Raised arms and spell particles. none by default."));
        put(fields, "sniffer", "minecraft:sniffer", "minecraft:sniffer bodies only.",
                choice("state", List.of("idling", "feeling_happy", "scenting", "sniffing", "searching", "digging", "rising"), "idling by default."));
        put(fields, "bat", "minecraft:bat", "minecraft:bat bodies only.", flag("resting", "Hangs upside down."));
        put(fields, "copperGolem", "minecraft:copper_golem", "minecraft:copper_golem bodies only.",
                choice("weather", List.of("unaffected", "exposed", "weathered", "oxidized"), "How far the copper has oxidized. unaffected by default."));
        put(fields, "donkey", "minecraft:donkey", "minecraft:donkey bodies only. The saddle is the saddle equipment slot.", flag("chest", "Carry a chest."));
        put(fields, "mule", "minecraft:mule", "minecraft:mule bodies only. The saddle is the saddle equipment slot.", flag("chest", "Carry a chest."));
        put(fields, "enderman", "minecraft:enderman", "minecraft:enderman bodies only.", flag("angry", "The open-mouthed scream."));
        put(fields, "wither", "minecraft:wither", "minecraft:wither bodies only.",
                number("invulnerableTicks", 0, 1000, "The blue spawning glow while above 0. 0 by default."), flag("armored", "The arrow shield of a wither below half health."));
        put(fields, "camel", "minecraft:camel", "minecraft:camel bodies only.", flag("sitting", "Sits."), flag("dashing", "The dash pose."));
        List<String> faces = List.of("down", "up", "north", "south", "west", "east");
        put(fields, "painting", "minecraft:painting", "minecraft:painting bodies only. The painting hangs on the block side it faces away from.",
                new Setting("variant", Kind.ID, "The painting, such as minecraft:kebab."), choice("facing", List.of("north", "south", "west", "east"), "south by default."));
        Setting[] frame = {new Setting("item", Kind.ITEM, "The item in the frame."), number("rotation", 0, 7, "Eighth turns of the item. 0 by default."),
                choice("facing", faces, "The way the frame faces. south by default.")};
        put(fields, "itemFrame", "minecraft:item_frame", "minecraft:item_frame bodies only.", frame);
        put(fields, "glowItemFrame", "minecraft:glow_item_frame", "minecraft:glow_item_frame bodies only.", frame);
        put(fields, "fallingBlock", "minecraft:falling_block", "minecraft:falling_block bodies only.",
                new Setting("block", Kind.BLOCK, "The block state, such as minecraft:oak_stairs[facing=east]. Sand by default."));
        put(fields, "endCrystal", "minecraft:end_crystal", "minecraft:end_crystal bodies only.", choice("base", SHOWN, "The bedrock base. shown by default."));
        List<String> billboards = List.of("fixed", "vertical", "horizontal", "center");
        put(fields, "blockDisplay", "minecraft:block_display", "minecraft:block_display bodies only.",
                new Setting("block", Kind.BLOCK, "The block state shown."), choice("billboard", billboards, "How it turns toward the viewer. fixed by default."));
        put(fields, "itemDisplay", "minecraft:item_display", "minecraft:item_display bodies only.", new Setting("item", Kind.ITEM, "The item shown."),
                choice("transform", List.of("none", "thirdperson_lefthand", "thirdperson_righthand", "firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed"),
                        "How the item is held. none by default."),
                choice("billboard", billboards, "How it turns toward the viewer. fixed by default."));
        put(fields, "textDisplay", "minecraft:text_display", "minecraft:text_display bodies only.", new Setting("text", Kind.TEXT, "The text; & codes apply."),
                choice("billboard", billboards, "How it turns toward the viewer. fixed by default."), choice("alignment", List.of("center", "left", "right"), "center by default."),
                number("lineWidth", 1, 1024, "Pixels before a line wraps. 200 by default."), flag("shadowed", "A shadow under the text."), flag("seeThrough", "Shown through blocks."));
        put(fields, "droppedItem", "minecraft:item", "minecraft:item bodies only: an item lying on the ground.", new Setting("item", Kind.ITEM, "The item."));
        FIELDS = java.util.Collections.unmodifiableMap(fields);
    }

    private static void put(Map<String, Field> fields, String field, String type, String description, Setting... settings) {
        fields.put(field, new Field(field, type, List.of(settings), description));
    }
    private static Setting choice(String key, List<String> values, String description) { return new Setting(key, values, description); }
    private static Setting flag(String key, String description) { return new Setting(key, List.of(), description); }
    private static Setting number(String key, int minimum, int maximum, String description) { return new Setting(key, List.of(), description, minimum, maximum); }
    private static Setting layer(String key) { return new Setting(key, SHOWN, "shown by default."); }

    /** The body types that have settings of their own. */
    public static Set<String> types() {
        Set<String> types = new TreeSet<>();
        for (Field field : FIELDS.values()) types.add(field.type());
        return types;
    }

    /** The settings field of {@code type}, empty for bodies without one. */
    public static Optional<Field> fieldOf(String type) {
        return FIELDS.values().stream().filter((Field field) -> field.type().equals(type)).findFirst();
    }

    public BodyVariant { java.util.Objects.requireNonNull(field); choices = Map.copyOf(choices); flags = Set.copyOf(flags); }

    /** The wire index of a chosen value, -1 when the setting is left out. */
    public int index(String key) {
        String value = choices.get(key);
        Field owner = FIELDS.get(field);
        if (value == null || owner == null) return -1;
        return owner.setting(key).map((Setting setting) -> setting.values().indexOf(value)).orElse(-1);
    }

    /** A number setting's value, or {@code fallback} when it is left out. */
    public int number(String key, int fallback) {
        String value = choices.get(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    public boolean flag(String key) { return flags.contains(key); }

    /** An item setting's item, empty when it is left out. */
    public Optional<NpcItem> item(String key) {
        String value = choices.get(key);
        if (value == null) return Optional.empty();
        return NpcItem.parse(com.google.gson.JsonParser.parseString(value), "$", new java.util.ArrayList<>());
    }

    /** A block or id setting with its namespace, {@code minecraft:} when it names none. */
    public Optional<String> id(String key) {
        String value = choices.get(key);
        if (value == null) return Optional.empty();
        String name = value.contains("[") ? value.substring(0, value.indexOf('[')) : value;
        return Optional.of(name.contains(":") ? value : "minecraft:" + value);
    }

    /** A horse's packed {@code DATA_ID_TYPE_VARIANT}: the colour in the low byte, the markings in the next. */
    public static int horseVariant(BodyVariant variant) {
        return Math.max(0, variant.index("color")) | Math.max(0, variant.index("markings")) << 8;
    }

    static BodyVariant read(JsonObject definition, String type, List<String> errors) {
        BodyVariant result = NONE;
        for (Field field : FIELDS.values()) {
            if (!definition.has(field.field())) continue;
            String path = "$." + field.field();
            if (!field.type().equals(type)) { errors.add(path + ": available only for " + field.type() + " bodies"); continue; }
            JsonElement value = definition.get(field.field());
            if (!value.isJsonObject()) { errors.add(path + ": expected object"); continue; }
            Map<String, String> choices = new TreeMap<>();
            Set<String> flags = new TreeSet<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                Optional<Setting> setting = field.setting(entry.getKey());
                String at = path + "." + entry.getKey();
                if (setting.isEmpty()) { errors.add(at + ": unknown field"); continue; }
                JsonElement declared = entry.getValue();
                Setting known = setting.get();
                if (known.kind() == Kind.ITEM) {
                    List<String> problems = new java.util.ArrayList<>();
                    if (NpcItem.parse(declared, at, problems).isPresent() && problems.isEmpty()) choices.put(entry.getKey(), art.arcane.animus.core.storage.JsonDocuments.write(declared));
                    else errors.addAll(problems.isEmpty() ? List.of(at + ": expected an item") : problems);
                } else if (known.kind() == Kind.BLOCK || known.kind() == Kind.ID || known.kind() == Kind.TEXT) {
                    String pattern = known.kind() == Kind.BLOCK ? BLOCK_STATE : ID;
                    if (!declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isString()) errors.add(at + ": expected a string");
                    else if (known.kind() == Kind.TEXT ? !NpcAppearance.displayText(declared.getAsString()) : !declared.getAsString().matches(pattern))
                        errors.add(at + (known.kind() == Kind.TEXT ? ": expected at most " + NpcAppearance.MAX_TEXT + " characters without control characters"
                                : known.kind() == Kind.BLOCK ? ": expected a block state such as minecraft:stone or minecraft:oak_stairs[facing=east]" : ": expected a namespaced id"));
                    else choices.put(entry.getKey(), declared.getAsString());
                } else if (known.number()) {
                    if (!declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isNumber() || !whole(declared)
                            || declared.getAsDouble() < known.minimum() || declared.getAsDouble() > known.maximum())
                        errors.add(at + ": expected a whole number from " + known.minimum() + " to " + known.maximum());
                    else choices.put(entry.getKey(), Integer.toString(declared.getAsInt()));
                } else if (known.flag()) {
                    if (!declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isBoolean()) errors.add(at + ": expected boolean");
                    else if (declared.getAsBoolean()) flags.add(entry.getKey());
                } else if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isString() && known.values().contains(declared.getAsString())) {
                    choices.put(entry.getKey(), declared.getAsString());
                } else errors.add(at + ": expected " + either(known.values()));
            }
            result = new BodyVariant(field.field(), choices, flags);
        }
        return result;
    }

    private static boolean whole(JsonElement value) {
        double number = value.getAsDouble();
        return Double.isFinite(number) && number == Math.rint(number);
    }

    private static String either(List<String> values) {
        return values.size() == 1 ? values.getFirst() : String.join(", ", values.subList(0, values.size() - 1)) + " or " + values.getLast();
    }
}
