package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.BodyVariant;
import art.arcane.animus.core.npc.BossBarSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.*;

/**
 * Maps Citizens' mob look traits (wool, coats, professions, sizes and the rest), skin layers, boss bars, player
 * filters and click redirects to definition fields. A trait for another body type changes nothing in Citizens either
 * and is kept with that reason; a value with no Animus equivalent is kept with its own.
 */
final class CitizensLooks {
    record Result(JsonObject fields, Set<String> converted, Map<String, CitizensImporter.Note> notes, List<String> warnings) {
        Result { fields = fields.deepCopy(); converted = Set.copyOf(converted); notes = Map.copyOf(notes); warnings = List.copyOf(warnings); }
        @Override public JsonObject fields() { return fields.deepCopy(); }
    }

    private enum Kind { CHOICE, FLAG, SHOWN, NUMBER, ID, TEXT }

    /**
     * One Citizens value and the setting it becomes. {@code aliases} maps Citizens' names, lowercased and without a
     * namespace, to Animus values; a name already an Animus value needs no alias. {@code fallback} is the value
     * Citizens applies when the trait is present but the key is not saved, or null for none.
     */
    private record Rule(String source, String setting, Kind kind, Map<String, String> aliases, String fallback) {}

    /** A trait and the body types it applies to, with the field each type's settings go in. */
    private record Trait(String key, Map<String, String> fields, List<Rule> rules) {}

    private static final Map<String, String> CHEST = Map.of("minecraft:donkey", "donkey", "minecraft:mule", "mule", "minecraft:llama", "llama");
    private static final List<Trait> TRAITS = List.of(
            trait("sheeptrait", Map.of("minecraft:sheep", "sheep"), choice("color", "color", "white"), flag("sheared", "sheared")),
            trait("woolcolor", Map.of("minecraft:sheep", "sheep"), choice("", "color", null)),
            trait("cattrait", Map.of("minecraft:cat", "cat"), choice("type", "variant", "black"), choice("collarColor", "collar", null),
                    flag("sitting", "sitting"), flag("lying", "lying")),
            trait("parrottrait", Map.of("minecraft:parrot", "parrot"),
                    choice("variant", "variant", "blue", Map.of("red", "red_blue", "cyan", "yellow_blue"))),
            trait("foxtrait", Map.of("minecraft:fox", "fox"), choice("type", "type", "red"), flag("sitting", "sitting"), flag("crouching", "crouching"),
                    flag("interested", "interested"), flag("pouncing", "pouncing"), flag("sleeping", "sleeping"), flag("faceplanted", "faceplanted")),
            trait("axolotltrait", Map.of("minecraft:axolotl", "axolotl"), choice("variant", "variant", null), flag("playingDead", "playingDead")),
            trait("rabbittype", Map.of("minecraft:rabbit", "rabbit"),
                    choice("type", "type", "brown", Map.of("black_and_white", "white_splotched", "salt_and_pepper", "salt", "the_killer_bunny", "evil"))),
            trait("profession", Map.of("minecraft:villager", "villager", "minecraft:zombie_villager", "zombieVillager"), choice("", "profession", null)),
            trait("villagertrait", Map.of("minecraft:villager", "villager", "minecraft:zombie_villager", "zombieVillager"), choice("type", "type", null),
                    choice("level", "level", null, Map.of("1", "novice", "2", "apprentice", "3", "journeyman", "4", "expert", "5", "master"))),
            trait("powered", Map.of("minecraft:creeper", "creeper"), flag("", "powered")),
            trait("slimesize", Map.of("minecraft:slime", "slime", "minecraft:magma_cube", "magmaCube"), number("size", "size", "3")),
            trait("phantomtrait", Map.of("minecraft:phantom", "phantom"), number("size", "size", "1")),
            trait("mushroomcowtrait", Map.of("minecraft:mooshroom", "mooshroom"), choice("variant", "variant", null)),
            trait("pandatrait", Map.of("minecraft:panda", "panda"), choice("mainGene", "mainGene", "normal"), choice("hiddenGene", "hiddenGene", null),
                    flag("sitting", "sitting"), flag("rolling", "rolling"), flag("sneezing", "sneezing"), flag("eating", "eating")),
            trait("goattrait", Map.of("minecraft:goat", "goat"), shown("leftHorn", "leftHorn"), shown("rightHorn", "rightHorn")),
            trait("pigtrait", Map.of("minecraft:pig", "pig"), choice("variant", "variant", null)),
            trait("cowtrait", Map.of("minecraft:cow", "cow"), choice("variant", "variant", null)),
            trait("chickentrait", Map.of("minecraft:chicken", "chicken"), choice("variant", "variant", null)),
            trait("shulkertrait", Map.of("minecraft:shulker", "shulker"), choice("color", "color", null), number("peek", "peek", null)),
            trait("pufferfishtrait", Map.of("minecraft:pufferfish", "pufferfish"),
                    choice("puffState", "puff", null, Map.of("0", "deflated", "1", "half", "2", "full"))),
            trait("tropicalfishtrait", Map.of("minecraft:tropical_fish", "tropicalFish"), choice("pattern", "pattern", "brinely"),
                    choice("bodyColor", "baseColor", "blue"), choice("patternColor", "patternColor", "blue")),
            trait("polarbeartrait", Map.of("minecraft:polar_bear", "polarBear"), flag("rearing", "standing")),
            trait("piglintrait", Map.of("minecraft:piglin", "piglin"), flag("dancing", "dancing")),
            trait("allaytrait", Map.of("minecraft:allay", "allay"), flag("dancing", "dancing")),
            trait("beetrait", Map.of("minecraft:bee", "bee"), flag("nectar", "nectar"), flag("stung", "stung")),
            trait("vextrait", Map.of("minecraft:vex", "vex"), flag("charging", "charging")),
            trait("spellcastertrait", Map.of("minecraft:evoker", "evoker"), choice("spell", "spell", null)),
            trait("sniffertrait", Map.of("minecraft:sniffer", "sniffer"), choice("state", "state", null)),
            trait("coppergolemtrait", Map.of("minecraft:copper_golem", "copperGolem"), choice("weather", "weather", null)),
            trait("endermantrait", Map.of("minecraft:enderman", "enderman"), flag("angry", "angry")),
            trait("llamatrait", Map.of("minecraft:llama", "llama"), choice("color", "variant", "brown")),
            trait("frogtrait", Map.of("minecraft:frog", "frog"), choice("variant", "variant", null)),
            trait("armadillotrait", Map.of("minecraft:armadillo", "armadillo"), choice("state", "state", null)),
            trait("gamemodetrait", Map.of("minecraft:player", "player"), choice("mode", "gameMode", null)),
            trait("paintingtrait", Map.of("minecraft:painting", "painting"), new Rule("art", "variant", Kind.ID, Map.of(), null)),
            trait("itemdisplaytrait", Map.of("minecraft:item_display", "itemDisplay"), choice("transform", "transform", null)),
            trait("displaytrait", Map.of("minecraft:block_display", "blockDisplay", "minecraft:item_display", "itemDisplay", "minecraft:text_display", "textDisplay"),
                    choice("billboard", "billboard", null)),
            trait("textdisplaytrait", Map.of("minecraft:text_display", "textDisplay"), new Rule("text", "text", Kind.TEXT, Map.of(), null),
                    choice("alignment", "alignment", null), number("lineWidth", "lineWidth", null), flag("shadowed", "shadowed"), flag("seeThrough", "seeThrough")),
            trait("skinlayers", Map.of("minecraft:player", "player"), shown("cape", "cape"), shown("hat", "hat"), shown("jacket", "jacket"),
                    shown("left-sleeve", "leftSleeve"), shown("right-sleeve", "rightSleeve"), shown("left-pants", "leftPants"), shown("right-pants", "rightPants")));
    /** Look traits whose settings have no effect Animus shows, with why. */
    private static final Map<String, String> SETTLED = Map.of("/traits/llamatrait/strength", "a llama's strength changes only what it carries",
            "/traits/horsemodifiers/tamed", "taming changes nothing a horse body shows", "/traits/snowmantrait/formSnow", "NPCs leave no snow trail");
    /** Traits of bodies Animus does not present. */
    private static final Map<String, String> UNPRESENTED = Map.of("areaeffectcloudtrait", "area effect clouds", "boattrait", "boats", "interactiontrait", "interaction entities");

    private final String type;
    private final JsonObject traits;
    private final String self;
    private final JsonObject fields = new JsonObject();
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private final boolean protectedNpc;

    private CitizensLooks(String type, JsonObject traits, String self, boolean protectedNpc) { this.type = type; this.traits = traits; this.self = self; this.protectedNpc = protectedNpc; }

    /**
     * @param self the NPC's own UUID, which a click redirect may not name
     * @param protectedNpc whether Citizens protected the NPC, which it is unless its metadata says otherwise
     */
    static Result map(String type, JsonObject traits, String self, boolean protectedNpc) {
        CitizensLooks mapping = new CitizensLooks(type, traits, self, protectedNpc);
        for (Trait trait : TRAITS) mapping.apply(trait);
        mapping.horse(); mapping.snowman(); mapping.bee(); mapping.wolf(); mapping.bat(); mapping.wither(); mapping.camel(); mapping.endCrystal(); mapping.bossBar(); mapping.playerFilter(); mapping.clickRedirect();
        for (Map.Entry<String, String> settled : SETTLED.entrySet())
            if (mapping.pointer(settled.getKey()) != null) mapping.notes.put(settled.getKey(), new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, settled.getValue()));
        for (Map.Entry<String, String> body : UNPRESENTED.entrySet())
            if (traits.has(body.getKey())) mapping.kept("/traits/" + body.getKey(), "Animus does not present " + body.getValue());
        List<String> sorted = new ArrayList<>(mapping.warnings);
        Collections.sort(sorted);
        return new Result(mapping.fields, mapping.converted, mapping.notes, sorted);
    }

    private JsonElement pointer(String path) {
        JsonElement at = traits;
        for (String part : path.substring("/traits/".length()).split("/")) {
            if (at == null || !at.isJsonObject()) return null;
            at = at.getAsJsonObject().get(part);
        }
        return at;
    }

    private static Trait trait(String key, Map<String, String> fields, Rule... rules) { return new Trait(key, fields, List.of(rules)); }
    private static Rule choice(String source, String setting, String fallback) { return new Rule(source, setting, Kind.CHOICE, Map.of(), fallback); }
    private static Rule choice(String source, String setting, String fallback, Map<String, String> aliases) { return new Rule(source, setting, Kind.CHOICE, aliases, fallback); }
    private static Rule flag(String source, String setting) { return new Rule(source, setting, Kind.FLAG, Map.of(), null); }
    private static Rule shown(String source, String setting) { return new Rule(source, setting, Kind.SHOWN, Map.of(), null); }
    private static Rule number(String source, String setting, String fallback) { return new Rule(source, setting, Kind.NUMBER, Map.of(), fallback); }

    private void kept(String path, String reason) { notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, reason)); }

    private JsonObject settings(String field) {
        if (!fields.has(field)) fields.add(field, new JsonObject());
        return fields.getAsJsonObject(field);
    }

    private void apply(Trait trait) {
        if (!traits.has(trait.key())) return;
        String base = "/traits/" + trait.key();
        String field = trait.fields().get(type);
        if (field == null) {
            kept(base, "applies only to " + String.join(" and ", new TreeSet<>(trait.fields().keySet())) + " bodies, so Citizens did not show it either");
            return;
        }
        JsonElement value = traits.get(trait.key());
        BodyVariant.Field known = BodyVariant.FIELDS.get(field);
        for (Rule rule : trait.rules()) {
            String path = rule.source().isEmpty() ? base : base + "/" + rule.source();
            JsonElement declared = rule.source().isEmpty() ? value : value.isJsonObject() ? value.getAsJsonObject().get(rule.source()) : null;
            if (declared != null && declared.isJsonNull()) declared = null;
            if (declared == null && rule.fallback() == null) continue;
            BodyVariant.Setting setting = known.setting(rule.setting()).orElseThrow();
            String text = declared == null ? rule.fallback() : declared.isJsonPrimitive() ? declared.getAsString() : null;
            switch (rule.kind()) {
                case FLAG -> {
                    Boolean on = bool(declared);
                    if (on == null) { if (declared != null) kept(path, "not true or false"); continue; }
                    if (on) settings(field).addProperty(rule.setting(), true);
                }
                case SHOWN -> {
                    Boolean on = bool(declared);
                    if (on == null) { if (declared != null) kept(path, "not true or false"); continue; }
                    if (!on) settings(field).addProperty(rule.setting(), "hidden");
                }
                case NUMBER -> {
                    Double number = text == null ? null : decimal(text);
                    if (number == null) { kept(path, "not a number"); continue; }
                    long whole = Math.round(number), clamped = Math.max(setting.minimum(), Math.min(setting.maximum(), whole));
                    if (clamped != whole) warnings.add(path + ": " + whole + " is outside " + setting.minimum() + ".." + setting.maximum() + " and became " + clamped);
                    settings(field).addProperty(rule.setting(), clamped);
                }
                case ID -> {
                    String id = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
                    if (!id.contains(":")) id = "minecraft:" + id;
                    if (!id.matches(BodyVariant.ID)) { kept(path, text + " is not an id Animus knows"); continue; }
                    settings(field).addProperty(rule.setting(), id);
                }
                case TEXT -> {
                    if (text == null) { kept(path, "not text"); continue; }
                    String shown = text.replaceAll("\\p{Cntrl}", " ");
                    if (shown.length() > 256) { shown = shown.substring(0, 256); warnings.add(path + ": cut to 256 characters"); }
                    settings(field).addProperty(rule.setting(), shown);
                }
                case CHOICE -> {
                    String name = text == null ? null : normalize(text);
                    String mapped = name == null ? null : rule.aliases().getOrDefault(name, name);
                    if (mapped == null || !setting.values().contains(mapped)) { kept(path, (text == null ? "this value" : text) + " is not a " + rule.setting() + " Animus knows"); continue; }
                    settings(field).addProperty(rule.setting(), mapped);
                }
            }
            if (declared != null) converted.add(path);
        }
        if (fields.has(field) && fields.getAsJsonObject(field).isEmpty()) fields.remove(field);
    }

    /** {@code RED}, {@code minecraft:red} and {@code Red} all read as {@code red}. */
    private static String normalize(String text) {
        String name = text.toLowerCase(Locale.ROOT).trim();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** HorseModifiers: a horse's coat and markings, and a chest on donkeys, mules and llamas. */
    private void horse() {
        JsonObject horse = object(traits.get("horsemodifiers"));
        if (horse == null) return;
        String base = "/traits/horsemodifiers";
        if (type.equals("minecraft:horse")) {
            String color = normalize(text(horse.get("color"), "creamy")), style = normalize(text(horse.get("style"), "none"));
            if (style.equals("whitefield")) style = "white_field";
            BodyVariant.Field known = BodyVariant.FIELDS.get("horse");
            if (known.setting("color").orElseThrow().values().contains(color)) { settings("horse").addProperty("color", color); if (horse.has("color")) converted.add(base + "/color"); }
            else kept(base + "/color", color + " is not a horse colour Animus knows");
            if (known.setting("markings").orElseThrow().values().contains(style)) {
                if (!style.equals("none")) settings("horse").addProperty("markings", style);
                if (horse.has("style")) converted.add(base + "/style");
            } else kept(base + "/style", style + " is not a horse marking Animus knows");
        } else for (String key : List.of("color", "style")) if (horse.has(key)) kept(base + "/" + key, "horse coats show only on minecraft:horse bodies, so Citizens did not show it either");
        Boolean chest = bool(horse.get("carryingChest"));
        if (chest == null) return;
        String field = CHEST.get(type);
        if (field == null) { if (chest) kept(base + "/carryingChest", "only donkeys, mules and llamas carry a chest"); else converted.add(base + "/carryingChest"); return; }
        if (chest) settings(field).addProperty("chest", true);
        converted.add(base + "/carryingChest");
    }

    /** A snow golem with Citizens' derp setting has no pumpkin. */
    private void snowman() {
        JsonObject snowman = object(traits.get("snowmantrait"));
        if (snowman == null || !snowman.has("derp")) return;
        if (!type.equals("minecraft:snow_golem")) { kept("/traits/snowmantrait/derp", "applies only to minecraft:snow_golem bodies, so Citizens did not show it either"); return; }
        if (Boolean.TRUE.equals(bool(snowman.get("derp")))) settings("snowGolem").addProperty("pumpkin", "hidden");
        converted.add("/traits/snowmantrait/derp");
    }

    /** A bee with any anger looks angry. */
    private void bee() {
        JsonObject bee = object(traits.get("beetrait"));
        if (bee == null || !bee.has("anger") || !type.equals("minecraft:bee")) return;
        Double anger = bee.get("anger").isJsonPrimitive() ? decimal(bee.get("anger").getAsString()) : null;
        if (anger == null) { kept("/traits/beetrait/anger", "not a number"); return; }
        if (anger > 0) settings("bee").addProperty("angry", true);
        converted.add("/traits/beetrait/anger");
    }

    /** WolfModifiers: the coat, and a collar only on a tamed wolf, as the client shows it. */
    private void wolf() {
        JsonObject wolf = object(traits.get("wolfmodifiers"));
        if (wolf == null) return;
        String base = "/traits/wolfmodifiers";
        if (!type.equals("minecraft:wolf")) { kept(base, "applies only to minecraft:wolf bodies, so Citizens did not show it either"); return; }
        if (wolf.has("variant")) {
            String variant = normalize(text(wolf.get("variant"), ""));
            if (BodyVariant.FIELDS.get("wolf").setting("variant").orElseThrow().values().contains(variant)) { settings("wolf").addProperty("variant", variant); converted.add(base + "/variant"); }
            else kept(base + "/variant", variant + " is not a wolf coat Animus knows");
        }
        boolean tamed = Boolean.TRUE.equals(bool(wolf.get("tamed")));
        if (wolf.has("tamed")) converted.add(base + "/tamed");
        String collar = normalize(text(wolf.get("collarColor"), "red"));
        if (tamed && BodyVariant.DYES.contains(collar)) settings("wolf").addProperty("collar", collar);
        if (wolf.has("collarColor")) {
            if (tamed && !BodyVariant.DYES.contains(collar)) kept(base + "/collarColor", collar + " is not a dye colour");
            else notes.put(base + "/collarColor", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, tamed ? "" : "a collar shows only on a tamed wolf"));
        }
        for (String key : List.of("sitting", "angry", "interested")) {
            if (!wolf.has(key)) continue;
            if (Boolean.TRUE.equals(bool(wolf.get(key)))) settings("wolf").addProperty(key, true);
            converted.add(base + "/" + key);
        }
        if (fields.has("wolf") && fields.getAsJsonObject("wolf").isEmpty()) fields.remove("wolf");
    }

    /** Citizens keeps a bat awake only when told to; otherwise it hangs resting. */
    private void bat() {
        JsonObject bat = object(traits.get("battrait"));
        if (bat == null) return;
        if (!type.equals("minecraft:bat")) { kept("/traits/battrait", "applies only to minecraft:bat bodies, so Citizens did not show it either"); return; }
        if (!Boolean.TRUE.equals(bool(bat.get("awake")))) settings("bat").addProperty("resting", true);
        if (bat.has("awake")) converted.add("/traits/battrait/awake");
    }

    /** Citizens shows a wither's blue charge for 20 ticks while it is charged, or while the NPC is protected when nothing says. */
    private void wither() {
        JsonObject wither = object(traits.get("withertrait"));
        if (wither == null) return;
        String base = "/traits/withertrait";
        if (!type.equals("minecraft:wither")) { kept(base, "applies only to minecraft:wither bodies, so Citizens did not show it either"); return; }
        Boolean charged = bool(wither.get("charged"));
        Double ticks = wither.has("invulnerableticks") && wither.get("invulnerableticks").isJsonPrimitive() ? decimal(wither.get("invulnerableticks").getAsString()) : null;
        long shown = charged != null ? (charged ? 20 : 0) : ticks != null ? Math.max(0, Math.min(1000, Math.round(ticks))) : protectedNpc ? 20 : 0;
        if (shown > 0) settings("wither").addProperty("invulnerableTicks", shown);
        if (charged != null) converted.add(base + "/charged");
        if (ticks != null) converted.add(base + "/invulnerableticks");
        if (Boolean.TRUE.equals(bool(wither.get("arrowshield")))) settings("wither").addProperty("armored", true);
        if (wither.has("arrowshield")) converted.add(base + "/arrowshield");
    }

    private void camel() {
        JsonObject camel = object(traits.get("cameltrait"));
        if (camel == null || !camel.has("pose")) return;
        String path = "/traits/cameltrait/pose";
        if (!type.equals("minecraft:camel")) { kept("/traits/cameltrait", "applies only to minecraft:camel bodies, so Citizens did not show it either"); return; }
        String pose = normalize(text(camel.get("pose"), ""));
        if (pose.equals("sitting")) settings("camel").addProperty("sitting", true);
        else if (pose.equals("panic")) settings("camel").addProperty("dashing", true);
        else if (!pose.equals("standing")) { kept(path, pose + " is not a camel pose Animus knows"); return; }
        converted.add(path);
    }

    /** Citizens hides an end crystal's base unless showBase is on. */
    private void endCrystal() {
        JsonObject crystal = object(traits.get("endercrystaltrait"));
        if (crystal == null) return;
        if (!type.equals("minecraft:end_crystal")) { kept("/traits/endercrystaltrait", "applies only to minecraft:end_crystal bodies, so Citizens did not show it either"); return; }
        if (!Boolean.TRUE.equals(bool(crystal.get("showBase")))) settings("endCrystal").addProperty("base", "hidden");
        if (crystal.has("showBase")) converted.add("/traits/endercrystaltrait/showBase");
    }

    private void bossBar() {
        JsonObject bar = object(traits.get("bossbar"));
        if (bar == null) return;
        String base = "/traits/bossbar";
        if (Boolean.FALSE.equals(bool(bar.get("visible")))) {
            notes.put(base, new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "the bar was hidden, so the NPC shows none"));
            return;
        }
        JsonObject result = new JsonObject();
        if (bar.has("visible")) converted.add(base + "/visible");
        String title = text(bar.get("title"), "");
        if (!title.isEmpty()) {
            if (title.length() > 256) { title = title.substring(0, 256); warnings.add(base + "/title: cut to 256 characters"); }
            result.addProperty("title", title.replaceAll("\\p{Cntrl}", " "));
        }
        if (bar.has("title")) converted.add(base + "/title");
        choose(bar, base, "color", BossBarSettings.COLORS, "purple", result);
        choose(bar, base, "style", BossBarSettings.STYLES, "solid", result);
        JsonArray flags = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : CitizensBehaviors.entries(bar.get("flags"))) {
            String flag = entry.getValue().isJsonPrimitive() ? normalize(entry.getValue().getAsString()) : "";
            if (BossBarSettings.FLAGS.contains(flag)) { if (!flags.contains(new JsonPrimitive(flag))) flags.add(flag); converted.add(base + "/flags/" + entry.getKey()); }
            else kept(base + "/flags/" + entry.getKey(), "not a boss bar flag Animus knows");
        }
        if (!flags.isEmpty()) result.add("flags", flags);
        Double range = bar.has("range") && bar.get("range").isJsonPrimitive() ? decimal(bar.get("range").getAsString()) : null;
        if (range != null) {
            if (range > 0) result.addProperty("range", Math.max(1, Math.min(BossBarSettings.MAX_RANGE, range)));
            if (range > BossBarSettings.MAX_RANGE) warnings.add(base + "/range: ranges above 256 blocks became 256");
            converted.add(base + "/range");
        }
        String track = text(bar.get("track"), "");
        if (track.equalsIgnoreCase("health")) { result.addProperty("progress", "health"); converted.add(base + "/track"); }
        else if (!track.isEmpty()) notes.put(base + "/track", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "a placeholder fill is not converted; the bar is full"));
        String permission = text(bar.get("viewPermission"), "");
        if (!permission.isEmpty()) {
            if (permission.matches("[A-Za-z0-9_.*-]{1,128}")) { result.addProperty("permission", permission); converted.add(base + "/viewPermission"); }
            else kept(base + "/viewPermission", "not a permission node Animus accepts");
        }
        fields.add(BossBarSettings.FIELD, result);
    }

    private void choose(JsonObject bar, String base, String key, List<String> values, String fallback, JsonObject result) {
        if (!bar.has(key)) return;
        String name = normalize(text(bar.get(key), ""));
        if (!values.contains(name)) { kept(base + "/" + key, name + " is not a boss bar " + key + " Animus knows"); return; }
        if (!name.equals(fallback)) result.addProperty(key, name);
        converted.add(base + "/" + key);
    }

    private void playerFilter() {
        JsonObject filter = object(traits.get("playerfilter"));
        if (filter == null) return;
        String base = "/traits/playerfilter";
        JsonObject result = new JsonObject();
        String mode = normalize(text(filter.get("mode"), "denylist"));
        if (mode.equals("allowlist")) result.addProperty("mode", "allow");
        if (filter.has("mode")) converted.add(base + "/mode");
        list(filter, base, "players", "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", true, result);
        list(filter, base, "groups", "[A-Za-z0-9_.*-]{1,128}", false, result);
        list(filter, base, "permissions", "[A-Za-z0-9_.*-]{1,128}", false, result);
        Double range = filter.has("applyRange") && filter.get("applyRange").isJsonPrimitive() ? decimal(filter.get("applyRange").getAsString()) : null;
        if (range != null) {
            if (range >= 1) result.addProperty("range", Math.min(512, range));
            converted.add(base + "/applyRange");
        }
        if (!result.has("players") && !result.has("groups") && !result.has("permissions")) return;
        fields.add("viewers", result);
    }

    private void list(JsonObject filter, String base, String key, String pattern, boolean lower, JsonObject result) {
        JsonArray values = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : CitizensBehaviors.entries(filter.get(key))) {
            String path = base + "/" + key + "/" + entry.getKey();
            String value = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "";
            if (lower) value = value.toLowerCase(Locale.ROOT);
            if (!value.matches(pattern)) { kept(path, "not a value Animus accepts"); continue; }
            if (values.size() == 256) { kept(path, "a filter keeps at most 256 entries in each list"); continue; }
            if (!values.contains(new JsonPrimitive(value))) values.add(value);
            converted.add(path);
        }
        if (!values.isEmpty()) result.add(key, values);
    }

    private void clickRedirect() {
        JsonObject redirect = object(traits.get("clickredirecttrait"));
        if (redirect == null || !redirect.has("uuid")) return;
        String target = text(redirect.get("uuid"), "").toLowerCase(Locale.ROOT);
        String path = "/traits/clickredirecttrait/uuid";
        try {
            if (!UUID.fromString(target).toString().equals(target)) throw new IllegalArgumentException();
            if (target.equals(self)) { kept(path, "the NPC redirects clicks to itself"); return; }
            fields.addProperty("clickRedirect", target);
            converted.add(path);
        } catch (IllegalArgumentException invalid) { kept(path, "not an NPC UUID"); }
    }

    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private static String text(JsonElement value, String fallback) { return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback; }
    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        String text = value.getAsString();
        return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
    }
    private static Double decimal(String text) {
        try { double number = Double.parseDouble(text); return Double.isFinite(number) ? number : null; }
        catch (NumberFormatException invalid) { return null; }
    }
}
