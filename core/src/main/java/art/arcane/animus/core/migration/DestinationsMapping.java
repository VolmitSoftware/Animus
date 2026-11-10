package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.schedule.ScheduleSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts the Citizens trait of NPC Destinations ({@code traits.npcdestinations}, written by nuNPCDestinations and its
 * forks) into an {@code animus:schedule} module. Each saved destination under {@code Destinations.<n>} becomes a location:
 * its time of day, chance, timed stay, wander distance and wait, weather, arrival items, skin and commands, and the
 * Animations add-on's sleep and sit. The module is installed off and unreviewed, as imported patrols are.
 */
public final class DestinationsMapping {
    public static final String TRAIT = "npcdestinations";
    /** The Citizens waypoint provider NPC Destinations registers under its own name. */
    public static final String PROVIDER = "npcdestinations";
    private static final String BASE = "/traits/" + TRAIT;
    private static final Map<String, String> ITEM_SLOTS = Map.of("Head", "head", "Chest", "chest", "Legs", "legs", "Boots", "feet", "Hand", "main_hand", "OffHand", "off_hand");
    private static final Set<String> PATHFINDER = Set.of("citizens_Swim", "citizens_NewPathFinder", "citizens_AvoidWater", "citizens_DistanceMargin",
            "citizens_PathDistanceMargin", "maxprocessingtime");

    /** A skin file to install as {@code skins/<hash>.json}. */
    public record Skin(String hash, String content) {
        public String path() { return "skins/" + hash + ".json"; }
    }
    /**
     * {@code module} is empty when the trait has no usable destination. {@code movement} holds the door and stuck
     * settings the trait carried, merged into the definition's movement.
     */
    public record Result(Optional<JsonObject> module, JsonObject movement, List<Skin> skins, Set<String> converted, Map<String, CitizensImporter.Note> notes,
                         List<String> warnings) {}

    private final String type, world;
    private final Map<String, String> worlds;
    private final JsonObject movement = new JsonObject();
    private final List<Skin> skins = new ArrayList<>();
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private DestinationsMapping(String type, String world, Map<String, String> worlds) { this.type = type; this.world = world; this.worlds = worlds; }

    /**
     * @param trait  the trait as saved
     * @param type   the NPC's body type, such as {@code minecraft:player}
     * @param world  the NPC's world after world mapping; every location must lie in it
     * @param worlds the import's world mapping
     */
    public static Result convert(JsonElement trait, String type, String world, Map<String, String> worlds) {
        DestinationsMapping mapping = new DestinationsMapping(type, world, worlds);
        JsonArray locations = trait != null && trait.isJsonObject() ? mapping.read(trait.getAsJsonObject()) : new JsonArray();
        if (trait == null || !trait.isJsonObject()) mapping.warnings.add(BASE + ": the trait is not a mapping; kept for review");
        Optional<JsonObject> module = Optional.empty();
        if (!locations.isEmpty()) {
            JsonObject schedule = new JsonObject();
            schedule.addProperty("enabled", false);
            schedule.addProperty("reviewAccepted", false);
            schedule.add("locations", locations);
            module = Optional.of(schedule);
        }
        return new Result(module, mapping.movement, List.copyOf(mapping.skins), Set.copyOf(mapping.converted), Map.copyOf(mapping.notes), List.copyOf(mapping.warnings));
    }

    private JsonArray read(JsonObject trait) {
        JsonArray locations = new JsonArray();
        JsonObject destinations = object(trait.get("Destinations"));
        if (destinations != null) {
            TreeMap<Integer, String> ordered = new TreeMap<>();
            for (String key : destinations.keySet()) {
                if (key.matches("0|[1-9][0-9]{0,3}")) ordered.put(Integer.parseInt(key), key);
                else if (PATHFINDER.contains(key)) note(BASE + "/Destinations/" + key, CitizensImporter.Status.UNSUPPORTED_PRESERVED,
                        "Citizens pathfinder setting; Animus plans its own paths");
            }
            for (String key : ordered.values()) {
                if (locations.size() >= ScheduleSettings.MAXIMUM_LOCATIONS) {
                    note(BASE + "/Destinations/" + key, CitizensImporter.Status.REQUIRES_REVIEW, "a schedule holds at most " + ScheduleSettings.MAXIMUM_LOCATIONS + " locations");
                    continue;
                }
                destination(destinations.get(key), BASE + "/Destinations/" + key).ifPresent(locations::add);
            }
        }
        legacy(trait.get("Locations"), locations);
        doors(trait);
        if (trait.has("TeleportOnNoPath")) {
            JsonObject stuck = new JsonObject();
            stuck.addProperty("teleport", truthy(trait.get("TeleportOnNoPath")));
            movement.add("stuck", stuck);
            converted.add(BASE + "/TeleportOnNoPath");
        }
        note(BASE + "/TeleportOnFailedStartLoc", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "movement.stuck.teleport decides what a blocked leg does");
        note(BASE + "/PauseForPlayers", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus NPCs do not stop for nearby players");
        note(BASE + "/PauseTimeout", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus NPCs do not stop for nearby players");
        note(BASE + "/blocksUnderSurface", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus plans its own paths");
        note(BASE + "/AllowedBlocks", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus plans its own paths over any walkable block");
        note(BASE + "/MaxDistFromDestination", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "movement.arrivalMargin decides when a location is reached");
        note(BASE + "/enabledplugins", CitizensImporter.Status.CONVERTED, "the add-on settings of each location are reported with that location");
        return locations;
    }

    /** Gates and wooden doors open as the NPC walks through; iron doors never do. */
    private void doors(JsonObject trait) {
        boolean gates = truthy(trait.get("OpensGates")), wood = truthy(trait.get("OpensWoodDoors"));
        if (gates || wood) movement.addProperty("openDoors", true);
        for (String key : List.of("OpensGates", "OpensWoodDoors")) if (trait.has(key)) converted.add(BASE + "/" + key);
        if (truthy(trait.get("OpensMetalDoors"))) note(BASE + "/OpensMetalDoors", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus NPCs never open iron doors");
        else if (trait.has("OpensMetalDoors")) converted.add(BASE + "/OpensMetalDoors");
    }

    /** The plugin's oldest format: {@code x:y:z:time} strings in the NPC's own world. */
    private void legacy(JsonElement value, JsonArray locations) {
        if (value == null || !value.isJsonArray()) return;
        JsonArray list = value.getAsJsonArray();
        for (int index = 0; index < list.size(); index++) {
            String path = BASE + "/Locations/" + index;
            String[] parts = list.get(index).isJsonPrimitive() ? list.get(index).getAsString().split(":") : new String[0];
            try {
                if (parts.length < 4 || locations.size() >= ScheduleSettings.MAXIMUM_LOCATIONS) throw new NumberFormatException();
                JsonObject location = new JsonObject();
                location.addProperty("time", Math.floorMod(Integer.parseInt(parts[3].trim()), ScheduleSettings.DAY_TICKS));
                location.addProperty("x", Integer.parseInt(parts[0].trim()));
                location.addProperty("y", Integer.parseInt(parts[1].trim()));
                location.addProperty("z", Integer.parseInt(parts[2].trim()));
                locations.add(location);
                converted.add(path);
            } catch (NumberFormatException unreadable) {
                note(path, CitizensImporter.Status.REQUIRES_REVIEW, "not an x:y:z:time location, or more than " + ScheduleSettings.MAXIMUM_LOCATIONS + " locations");
            }
        }
    }

    private Optional<JsonObject> destination(JsonElement value, String path) {
        JsonObject source = object(value);
        if (source == null) { note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the destination is not a mapping"); return Optional.empty(); }
        JsonObject place = object(source.get("Location"));
        String from = place == null ? null : text(place.get("world"));
        String mapped = from == null ? null : worlds.getOrDefault(from, from);
        if (place == null || !whole(place.get("x")) || !whole(place.get("y")) || !whole(place.get("z"))) {
            note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the destination has no block location");
            warnings.add(path + ": a destination without a block location was left out");
            return Optional.empty();
        }
        if (mapped == null || !mapped.equals(world)) {
            note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the destination lies in " + from + ", not the NPC's world; Animus schedules stay in one world");
            warnings.add(path + ": a destination in " + from + " was left out; schedule locations lie in the NPC's own world");
            return Optional.empty();
        }
        long time = integer(source.get("TimeOfDay"), 0);
        if (time < 0) {
            note(path, CitizensImporter.Status.UNSUPPORTED_PRESERVED, "a destination with time -1 is only chosen by other plugins");
            return Optional.empty();
        }
        JsonObject location = new JsonObject();
        alias(text(source.get("AliasName")), path + "/AliasName").ifPresent((String name) -> location.addProperty("name", name));
        location.addProperty("time", Math.min(time, ScheduleSettings.DAY_TICKS - 1));
        if (time >= ScheduleSettings.DAY_TICKS) warnings.add(path + "/TimeOfDay: " + time + " is past the end of the day; it became " + (ScheduleSettings.DAY_TICKS - 1));
        converted.add(path + "/TimeOfDay");
        for (String axis : List.of("x", "y", "z")) location.addProperty(axis, place.get(axis).getAsBigDecimal().intValue());
        for (String axis : List.of("world", "x", "y", "z")) converted.add(path + "/Location/" + axis);
        facing(place, location, path + "/Location");
        converted.add(path + "/locationid");
        note(path + "/MaxDistance", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "movement.arrivalMargin decides when a location is reached");
        probability(object(source.get("Probability")), location, path + "/Probability");
        long weather = integer(source.get("WeatherFlag"), 0);
        if (weather == 1 || weather == 2) location.addProperty("weather", weather == 1 ? "clear" : "storm");
        if (source.has("WeatherFlag")) converted.add(path + "/WeatherFlag");
        wander(object(source.get("WanderSettings")), location, path + "/WanderSettings");
        for (String key : List.of("UseBlockSetting", "Use_Blocks")) blocks(source, key, path + "/" + key);
        items(object(source.get("Items")), location, path + "/Items");
        skin(object(source.get("Skin")), location, path + "/Skin");
        commands(object(source.get("Commands")), location, path + "/Commands");
        note(path + "/Pause", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus NPCs do not stop for nearby players");
        note(path + "/Citizens", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Citizens pathfinder setting; Animus plans its own paths");
        addons(source, location, path);
        return Optional.of(location);
    }

    private Optional<String> alias(String alias, String path) {
        if (alias == null || alias.isBlank()) { converted.add(path); return Optional.empty(); }
        String name = alias.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[-_]+", "");
        if (name.length() > 32) name = name.substring(0, 32);
        if (!name.matches(ScheduleSettings.NAME)) { note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the alias has no letters or digits to name the location with"); return Optional.empty(); }
        converted.add(path);
        if (!name.equals(alias)) notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "named " + name + ": lowercase letters, digits, _ and -"));
        return Optional.of(name);
    }

    /** NPC Destinations turns the NPC to the saved yaw and pitch on arrival. A yaw of 0 with pitch 0 is a location set without facing. */
    private void facing(JsonObject place, JsonObject location, String path) {
        Double yaw = decimal(place.get("yaw")), pitch = decimal(place.get("pitch"));
        if (yaw != null && (yaw != 0 || (pitch != null && pitch != 0))) {
            double normalized = yaw % 360;
            location.addProperty("yaw", normalized);
            if (pitch != null && pitch != 0) location.addProperty("pitch", Math.clamp(pitch, -90, 90));
        }
        for (String axis : List.of("yaw", "pitch")) if (place.has(axis)) converted.add(path + "/" + axis);
    }

    private void probability(JsonObject probability, JsonObject location, String path) {
        if (probability == null) return;
        long chance = integer(probability.get("ChancePercent"), 0);
        if (chance > 0 && chance < 100) location.addProperty("chance", chance);
        long min = integer(probability.get("Min_Time"), 0), max = integer(probability.get("Max_Time"), 0);
        if (min > 0) {
            JsonArray stay = new JsonArray();
            stay.add(Math.min(min, ScheduleSettings.MAXIMUM_SECONDS));
            stay.add(Math.clamp(max, Math.min(min, ScheduleSettings.MAXIMUM_SECONDS), ScheduleSettings.MAXIMUM_SECONDS));
            location.add("staySeconds", stay);
        }
        for (String key : probability.keySet()) converted.add(path + "/" + key);
    }

    private void wander(JsonObject wander, JsonObject location, String path) {
        if (wander == null) return;
        Double distance = decimal(wander.get("Wandering_Distance"));
        if (distance != null && distance > 0) {
            JsonObject settings = new JsonObject();
            long radius = Math.clamp(Math.round(distance), 1, ScheduleSettings.MAXIMUM_WANDER);
            if (distance > ScheduleSettings.MAXIMUM_WANDER) warnings.add(path + "/Wandering_Distance: wandering farther than " + ScheduleSettings.MAXIMUM_WANDER + " blocks became " + ScheduleSettings.MAXIMUM_WANDER);
            settings.addProperty("radius", radius);
            long min = integer(wander.get("Wait_Minimum"), 0), max = integer(wander.get("Wait_Maximum"), 0);
            if (min > 0 && max > 0) {
                JsonArray wait = new JsonArray();
                wait.add(Math.min(min, max));
                wait.add(Math.min(Math.max(min, max), ScheduleSettings.MAXIMUM_SECONDS));
                settings.add("waitSeconds", wait);
            }
            location.add("wander", settings);
        }
        for (String key : List.of("Wandering_Distance", "Wait_Minimum", "Wait_Maximum")) if (wander.has(key)) converted.add(path + "/" + key);
        String region = text(wander.get("Wandering_Region"));
        if (region != null && !region.isBlank()) {
            note(path + "/Wandering_Region", CitizensImporter.Status.REQUIRES_REVIEW, "a WorldGuard region; give the location wander.area with the region's corners");
            warnings.add(path + "/Wandering_Region: the NPC wandered in WorldGuard region " + region + "; set wander.area to its corners");
        } else if (wander.has("Wandering_Region")) converted.add(path + "/Wandering_Region");
        blocks(wander, "Wandering_UseBlocks", path + "/Wandering_UseBlocks");
    }

    /** Wandering only onto the trait's allowed blocks has no equivalent; turned off it changes nothing. */
    private void blocks(JsonObject parent, String key, String path) {
        if (!parent.has(key)) return;
        if (truthy(parent.get(key))) note(path, CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Animus wanders over any walkable block");
        else converted.add(path);
    }

    private void items(JsonObject items, JsonObject location, String path) {
        if (items == null) return;
        JsonObject equipment = new JsonObject();
        for (Map.Entry<String, String> slot : ITEM_SLOTS.entrySet()) {
            JsonElement value = items.get(slot.getKey());
            if (value == null) continue;
            if (value.isJsonNull()) { converted.add(path + "/" + slot.getKey()); continue; }
            item(value, path + "/" + slot.getKey()).ifPresent((JsonObject item) -> equipment.add(slot.getValue(), item));
        }
        if (!equipment.isEmpty()) location.add("equipment", sorted(equipment));
        if (truthy(items.get("Clear"))) location.addProperty("clearEquipment", true);
        if (items.has("Clear")) converted.add(path + "/Clear");
    }

    /**
     * A Bukkit item as Citizens' storage writes it: {@code type} and {@code amount} with an optional {@code meta} map, or
     * on newer servers {@code id}, {@code count} and a {@code components} map of SNBT values.
     */
    private Optional<JsonObject> item(JsonElement value, String path) {
        JsonObject source = object(value);
        String raw = source == null ? null : text(source.get("id")) != null ? text(source.get("id")) : text(source.get("type"));
        if (raw == null) { note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the item has no type"); return Optional.empty(); }
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (id.equals("minecraft:air")) { converted.add(path); return Optional.empty(); }
        if (!NpcItem.validId(id)) { note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the item type is not a namespaced id"); return Optional.empty(); }
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        long count = integer(source.has("count") ? source.get("count") : source.get("amount"), 1);
        item.addProperty("count", Math.clamp(count, 1, NpcItem.MAX_COUNT));
        for (String key : List.of("==", "v", "DataVersion", "schema_version", "id", "type", "count", "amount")) if (source.has(key)) converted.add(path + "/" + CitizensBehaviors.pointer(key));
        JsonObject components = object(source.get("components"));
        if (components != null) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonElement> component : new TreeMap<>(components.asMap()).entrySet())
                if (component.getValue().isJsonPrimitive()) parts.add(component.getKey() + "=" + component.getValue().getAsString());
            String text = "[" + String.join(",", parts) + "]";
            if (!parts.isEmpty() && parts.size() == components.size() && text.length() <= NpcItem.MAX_COMPONENTS) {
                item.addProperty("components", text);
                for (String key : components.keySet()) converted.add(path + "/components/" + CitizensBehaviors.pointer(key));
            } else note(path + "/components", CitizensImporter.Status.REQUIRES_REVIEW, "item components that do not fit an item text; kept in the item's imported data");
        }
        if (source.has("meta")) note(path + "/meta", CitizensImporter.Status.REQUIRES_REVIEW, "Bukkit item meta has no item text here; kept in the item's imported data");
        if ((source.has("meta") || (components != null && !item.has("components")))
                && JsonDocuments.write(source).getBytes(StandardCharsets.UTF_8).length <= NpcItem.MAX_IMPORTED_BYTES) item.add("imported", source.deepCopy());
        return Optional.of(item);
    }

    /** NPC Destinations draws a location's skin only on a player NPC, on arrival or, without ApplyOnArrival, as it sets out. */
    private void skin(JsonObject skin, JsonObject location, String path) {
        if (skin == null) return;
        String name = text(skin.get("Name")), texture = text(skin.get("MetaData")), signature = text(skin.get("Signature"));
        boolean named = name != null && !name.isBlank();
        if (!named) { for (String key : skin.keySet()) converted.add(path + "/" + key); return; }
        if (!BodyRules.PLAYER.equals(type)) { note(path, CitizensImporter.Status.UNSUPPORTED_PRESERVED, CitizensImporter.unusedSkin(type)); return; }
        if (texture == null || texture.isBlank()) {
            note(path, CitizensImporter.Status.REQUIRES_REVIEW, "the skin " + name + " was saved without its texture; set the location's skin by hand");
            return;
        }
        JsonObject document = new JsonObject();
        document.addProperty("skinName", name);
        document.addProperty("textureRaw", texture);
        if (signature != null && !signature.isBlank()) document.addProperty("signature", signature);
        String content = JsonDocuments.write(document), hash = JsonDocuments.hash(content);
        skins.add(new Skin(hash, content));
        location.addProperty("skin", hash);
        if (!truthy(skin.get("ApplyOnArrival"))) location.addProperty("skinOnDeparture", true);
        for (String key : skin.keySet()) converted.add(path + "/" + key);
    }

    private void commands(JsonObject commands, JsonObject location, String path) {
        if (commands == null) return;
        JsonElement arrival = commands.get("arrival");
        if (arrival == null || !arrival.isJsonArray()) { if (arrival != null) note(path + "/arrival", CitizensImporter.Status.REQUIRES_REVIEW, "not a list of commands"); return; }
        JsonArray kept = new JsonArray();
        JsonArray list = arrival.getAsJsonArray();
        for (int index = 0; index < list.size(); index++) {
            String at = path + "/arrival/" + index;
            String command = list.get(index).isJsonPrimitive() ? list.get(index).getAsString().strip() : "";
            while (command.startsWith("/")) command = command.substring(1);
            if (command.isEmpty() || command.length() > ScheduleSettings.MAXIMUM_COMMAND || command.codePoints().anyMatch(Character::isISOControl)
                    || kept.size() >= ScheduleSettings.MAXIMUM_COMMANDS) {
                note(at, CitizensImporter.Status.REQUIRES_REVIEW, "empty, longer than " + ScheduleSettings.MAXIMUM_COMMAND + " characters or past "
                        + ScheduleSettings.MAXIMUM_COMMANDS + " commands");
                continue;
            }
            kept.add(command);
            converted.add(at);
        }
        if (kept.isEmpty()) return;
        location.add("commands", kept);
        warnings.add(path + ": arrival commands run from the console once citizens is in [behaviors.actions] commandNamespaces (accept commands); read them first");
    }

    /** The add-ons' per-location data: the Animations add-on's sleep and sit become the arrival pose. */
    private void addons(JsonObject source, JsonObject location, String path) {
        JsonElement settings = source.get("PluginSettings");
        if (settings != null && settings.isJsonPrimitive() && settings.getAsString().isEmpty()) converted.add(path + "/PluginSettings");
        JsonObject addons = object(settings);
        if (addons != null) for (String addon : addons.keySet()) {
            String at = path + "/PluginSettings/" + CitizensBehaviors.pointer(addon);
            if (addon.equals("Animations")) animation(object(addons.get(addon)), location, at);
            else note(at, CitizensImporter.Status.REQUIRES_INTEGRATION, "settings of the NPC Destinations " + addon + " add-on, which Animus does not run");
        }
        for (String old : List.of("Sentinel", "JobsReborn"))
            if (source.has(old)) note(path + "/" + old, CitizensImporter.Status.REQUIRES_INTEGRATION, "settings of the NPC Destinations " + old + " add-on, which Animus does not run");
    }

    private void animation(JsonObject animation, JsonObject location, String path) {
        if (animation == null) { note(path, CitizensImporter.Status.REQUIRES_REVIEW, "not a mapping"); return; }
        String setting = text(animation.get("Setting"));
        String pose = "SLEEP".equals(setting) ? "sleeping" : "SIT".equals(setting) ? "sitting" : null;
        if (pose != null && BodyRules.PLAYER.equals(type)) { location.addProperty("pose", pose); converted.add(path + "/Setting"); }
        else if (pose != null) note(path + "/Setting", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "only player bodies lie down or sit");
        else if (setting == null || setting.equals("NONE")) converted.add(path + "/Setting");
        else note(path + "/Setting", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "the " + setting.toLowerCase(Locale.ROOT) + " animation has no Animus equivalent");
        note(path, CitizensImporter.Status.UNSUPPORTED_PRESERVED, "animation sounds and timing have no Animus equivalent");
    }

    /** Adds the trait's door and stuck settings to the definition's movement, keeping what the other traits set. */
    public static void movement(JsonObject definition, JsonObject movement) {
        if (movement.isEmpty()) return;
        JsonObject target = definition.has("movement") && definition.get("movement").isJsonObject() ? definition.getAsJsonObject("movement") : new JsonObject();
        for (Map.Entry<String, JsonElement> field : movement.entrySet()) {
            JsonElement known = target.get(field.getKey());
            if (known != null && known.isJsonObject() && field.getValue().isJsonObject())
                field.getValue().getAsJsonObject().entrySet().forEach((Map.Entry<String, JsonElement> inner) -> known.getAsJsonObject().add(inner.getKey(), inner.getValue().deepCopy()));
            else target.add(field.getKey(), field.getValue().deepCopy());
        }
        definition.add("movement", target);
    }

    private void note(String path, CitizensImporter.Status status, String reason) { notes.put(path, new CitizensImporter.Note(status, reason)); }

    private static JsonObject sorted(JsonObject object) {
        JsonObject result = new JsonObject();
        new TreeMap<>(object.asMap()).forEach(result::add);
        return result;
    }
    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private static String text(JsonElement value) { return value != null && value.isJsonPrimitive() ? value.getAsString() : null; }
    private static boolean whole(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try { value.getAsBigDecimal().intValueExact(); return true; } catch (ArithmeticException fraction) { return false; }
    }
    private static boolean truthy(JsonElement value) {
        return value != null && value.isJsonPrimitive() && (value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : "true".equalsIgnoreCase(value.getAsString()));
    }
    private static long integer(JsonElement value, long fallback) {
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try { return new java.math.BigDecimal(value.getAsString().trim()).longValue(); } catch (NumberFormatException unreadable) { return fallback; }
    }
    private static Double decimal(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        try { double parsed = Double.parseDouble(value.getAsString().trim()); return Double.isFinite(parsed) ? parsed : null; }
        catch (NumberFormatException unreadable) { return null; }
    }
}
