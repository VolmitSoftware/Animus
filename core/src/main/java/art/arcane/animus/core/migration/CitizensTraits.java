package art.arcane.animus.core.migration;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.VanillaAttributes;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Maps Citizens NPC metadata, navigator settings and the small state traits (age, gravity, glow, team colour, tags,
 * attributes, rotation, protection, targeting, spawn state, mounts, riding and named poses) to definition fields.
 * Fields with no Animus equivalent get a one-line reason; fields that change nothing in Citizens either count as converted.
 */
final class CitizensTraits {
    /** @param raisedFall a Citizens route's fall distance below {@link #ROUTE_FALL} was raised to it */
    record Result(JsonObject fields, JsonObject migration, Set<String> converted, Map<String, CitizensImporter.Note> notes, List<String> warnings, boolean raisedFall) {
        Result {
            fields = fields.deepCopy(); migration = migration.deepCopy(); converted = Set.copyOf(converted); notes = Map.copyOf(notes); warnings = List.copyOf(warnings);
        }
        @Override public JsonObject fields() { return fields.deepCopy(); }
        @Override public JsonObject migration() { return migration.deepCopy(); }
    }
    /** The scoreboard tag Citizens puts on every NPC it spawns. */
    static final String CITIZENS_TAG = "CITIZENS_NPC";
    /** Vanilla random strolling: a pick within 10 blocks across and 7 up or down, about every six seconds. */
    static final int WANDER_RADIUS = 10, WANDER_HEIGHT = 7, WANDER_WAIT = 120;
    /** The least drop a Citizens route walks: Citizens' common 1 blocks most legs that step off a ledge. */
    static final int ROUTE_FALL = 3;
    private static final Map<String, String> ROTATION_DEFAULTS = Map.of("headOnly", "false", "immediate", "false", "linkedBody", "false", "lockPitch", "false",
            "maxPitchPerTick", "10.0", "maxYawPerTick", "40.0");

    private final String type;
    private final JsonObject record, traits, fields = new JsonObject(), migration = new JsonObject();
    private final boolean combat, health, routed, patrolled;
    private boolean raisedFall;
    private final JsonArray references;
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private CitizensTraits(String type, JsonObject record, JsonObject traits, boolean combat, boolean health, boolean routed, boolean patrolled, JsonArray references) {
        this.type = type; this.record = record; this.traits = traits; this.combat = combat; this.health = health; this.routed = routed; this.patrolled = patrolled;
        this.references = references;
    }

    /**
     * @param type the definition's body type
     * @param traits the review copy of the NPC's traits
     * @param combat whether the NPC converts with an {@code animus:combat} module
     * @param health whether the NPC converts with an {@code animus:health} module
     * @param routed whether the NPC converts with an {@code animus:navigation} route
     * @param patrolled whether that route comes from Citizens waypoints; it then opens doors and drops at least {@link #ROUTE_FALL} blocks
     * @param references the resolved NPC references
     */
    static Result map(String type, JsonObject record, JsonObject traits, boolean combat, boolean health, boolean routed, boolean patrolled, JsonArray references) {
        CitizensTraits mapping = new CitizensTraits(type, record, traits, combat, health, routed, patrolled, references);
        mapping.metadata(); mapping.movement(); mapping.age(); mapping.gravity(); mapping.scoreboard(); mapping.attributes(); mapping.rotation();
        mapping.targetable(); mapping.spawned(); mapping.mount(); mapping.controllable(); mapping.poses(); mapping.horse();
        mapping.note("/traitnames", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Citizens bookkeeping of the traits the NPC had; nothing to convert");
        List<String> sorted = new ArrayList<>(mapping.warnings);
        Collections.sort(sorted);
        return new Result(mapping.fields, mapping.migration, mapping.converted, mapping.notes, sorted, mapping.raisedFall);
    }

    private void note(String path, CitizensImporter.Status status, String reason) { notes.put(path, new CitizensImporter.Note(status, reason)); }
    private void kept(String path, String reason) { note(path, CitizensImporter.Status.UNSUPPORTED_PRESERVED, reason); }
    private void review(String path, String reason) { note(path, CitizensImporter.Status.REQUIRES_REVIEW, reason); }

    // ---- Metadata ----

    private void metadata() {
        JsonObject metadata = object(record.get("metadata"));
        if (metadata == null) return;
        for (Map.Entry<String, JsonElement> entry : metadata.entrySet()) {
            String key = entry.getKey(), path = "/metadata/" + CitizensBehaviors.pointer(key);
            Boolean flag = bool(entry.getValue());
            switch (key) {
                case "cached-skin-uuid", "cached-skin-uuid-name" -> {
                    if (!entry.getValue().isJsonPrimitive()) break;
                    migration.addProperty(key.equals("cached-skin-uuid") ? "skinUuid" : "skinName", entry.getValue().getAsString());
                    converted.add(path);
                }
                case "collidable" -> {
                    if (Boolean.FALSE.equals(flag)) converted.add(path);
                    else if (Boolean.TRUE.equals(flag)) kept(path, "Animus bodies never push players or mobs, so a collidable NPC cannot be shown");
                }
                case "glowing" -> {
                    if (flag == null) break;
                    if (flag) fields.addProperty("glowing", true);
                    converted.add(path);
                }
                case "minecraft-ai" -> {
                    if (Boolean.FALSE.equals(flag) || Boolean.TRUE.equals(flag) && type.equals(BodyRules.PLAYER)) converted.add(path);
                    else if (Boolean.TRUE.equals(flag) && routed) note(path, CitizensImporter.Status.CONVERTED,
                            "vanilla AI becomes walking around within " + WANDER_RADIUS + " blocks, or the NPC's own route; vanilla attacking and fleeing do not run");
                    else if (Boolean.TRUE.equals(flag)) kept(path, "the NPC has no world to wander in");
                }
                case "protected" -> {
                    if (Boolean.TRUE.equals(flag) || Boolean.FALSE.equals(flag) && health) converted.add(path);
                    else if (Boolean.FALSE.equals(flag)) kept(path, "Animus NPCs take damage only through an animus:health module, which this NPC does not have");
                }
                case "using-held-item" -> {
                    if (flag == null) break;
                    if (flag) fields.addProperty("usingItem", true);
                    converted.add(path);
                }
                case "removefromplayerlist" -> {
                    if (flag == null) break;
                    if (!flag && type.equals(BodyRules.PLAYER)) fields.addProperty("listed", true);
                    converted.add(path);
                }
                case "flyable" -> { if (flag != null) converted.add(path); }
                case "pathfinder-open-doors" -> { if (flag != null) converted.add(path); }
                case "disable-default-stuck-action" -> { if (flag != null) converted.add(path); }
                default -> { }
            }
        }
    }

    // ---- Navigator ----

    private void movement() {
        JsonObject navigator = object(record.get("navigator"));
        JsonObject metadata = object(record.get("metadata"));
        JsonObject movement = new JsonObject();
        boolean any = false;
        if (navigator != null) {
            JsonElement modifier = navigator.get("speedmodifier");
            if (positive(modifier)) { movement.addProperty("speed", CitizensBehaviors.speed(modifier.getAsDouble())); converted.add("/navigator/speedmodifier"); any = true; }
            Boolean avoid = bool(navigator.get("avoidwater"));
            if (avoid != null) { movement.addProperty("avoidWater", avoid); converted.add("/navigator/avoidwater"); any = true; }
            JsonElement fall = navigator.get("falldistance");
            if (number(fall)) {
                long blocks = Math.max(0, Math.min(16, (long) Math.floor(fall.getAsDouble())));
                if (fall.getAsDouble() > 16) warnings.add("/navigator/falldistance: falls longer than 16 blocks became 16");
                if (patrolled && blocks < ROUTE_FALL) {
                    blocks = ROUTE_FALL;
                    raisedFall = true;
                }
                movement.addProperty("maximumFall", blocks);
                converted.add("/navigator/falldistance");
                any = true;
            }
            JsonElement range = navigator.get("pathfindingrange");
            if (positive(range)) {
                movement.addProperty("pathRange", Math.max(1, Math.min(256, range.getAsDouble())));
                converted.add("/navigator/pathfindingrange");
                any = true;
            }
            JsonElement margin = navigator.get("distancemargin");
            if (number(margin) && margin.getAsDouble() >= 0) {
                movement.addProperty("arrivalMargin", Math.min(4, margin.getAsDouble()));
                converted.add("/navigator/distancemargin");
                any = true;
            }
            JsonElement teleport = navigator.get("destinationteleportmargin");
            if (number(teleport) && teleport.getAsDouble() <= 0) converted.add("/navigator/destinationteleportmargin");
            else if (number(teleport)) review("/navigator/destinationteleportmargin", "Animus walks the whole way instead of teleporting the last " + teleport.getAsDouble() + " blocks");
            Boolean useDefault = bool(navigator.get("usedefaultstuckaction"));
            JsonElement ticks = navigator.get("stationaryticks");
            Boolean disabled = metadata == null ? null : bool(metadata.get("disable-default-stuck-action"));
            if (useDefault != null || number(ticks)) {
                JsonObject stuck = new JsonObject();
                stuck.addProperty("afterTicks", number(ticks) && ticks.getAsDouble() >= 1 ? Math.min(12_000, (long) Math.floor(ticks.getAsDouble())) : 100);
                stuck.addProperty("teleport", !Boolean.FALSE.equals(useDefault) && !Boolean.TRUE.equals(disabled));
                movement.add("stuck", stuck);
                if (useDefault != null) converted.add("/navigator/usedefaultstuckaction");
                if (number(ticks)) converted.add("/navigator/stationaryticks");
                any = true;
            }
        }
        // Citizens' navigator swims by default, so a route whose save sets no avoidwater crosses water at the surface.
        if (routed && !movement.has("avoidWater")) { movement.addProperty("avoidWater", false); any = true; }
        if (metadata != null && Boolean.TRUE.equals(bool(metadata.get("flyable")))) { movement.addProperty("style", "fly"); any = true; }
        if (patrolled || metadata != null && Boolean.TRUE.equals(bool(metadata.get("pathfinder-open-doors")))) { movement.addProperty("openDoors", true); any = true; }
        if (any) fields.add("movement", movement);
    }

    // ---- State traits ----

    private void age() {
        JsonObject age = object(traits.get("age"));
        if (age == null) return;
        JsonElement value = age.get("age");
        if (age.has("locked")) converted.add("/traits/age/locked");
        if (!number(value)) return;
        if (value.getAsDouble() >= 0) { converted.add("/traits/age/age"); return; }
        if (!BodyRules.canBeBaby(type)) { kept("/traits/age/age", type + " bodies have no baby form"); return; }
        fields.addProperty("baby", true);
        converted.add("/traits/age/age");
    }

    private void gravity() {
        JsonObject gravity = object(traits.get("gravity"));
        if (gravity == null) return;
        Boolean noGravity = bool(gravity.get("enabled"));
        if (noGravity == null) return;
        if (noGravity) fields.addProperty("gravity", false);
        converted.add("/traits/gravity/enabled");
    }

    private void scoreboard() {
        JsonObject scoreboard = object(traits.get("scoreboardtrait"));
        if (scoreboard == null) return;
        JsonElement color = scoreboard.get("color");
        if (color != null && color.isJsonPrimitive()) {
            String name = color.getAsString().toLowerCase(Locale.ROOT);
            if (BodyTraits.TEAM_COLORS.contains(name)) { fields.addProperty("teamColor", name); converted.add("/traits/scoreboardtrait/color"); }
            else if (name.equals("reset")) converted.add("/traits/scoreboardtrait/color");
            else kept("/traits/scoreboardtrait/color", "team colour " + color.getAsString() + " is a format, not a colour");
        }
        List<Map.Entry<String, JsonElement>> tags = CitizensBehaviors.entries(scoreboard.get("tags"));
        JsonArray kept = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : tags) {
            String path = "/traits/scoreboardtrait/tags/" + entry.getKey();
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) continue;
            String tag = value.getAsString();
            if (tag.equals(CITIZENS_TAG)) { converted.add(path); continue; }
            String slug = tag.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[^a-z0-9]+", "");
            if (slug.length() > 32) slug = slug.substring(0, 32);
            if (slug.isEmpty()) { kept(path, "the tag has no letters or digits to keep"); continue; }
            if (kept.size() == art.arcane.animus.core.npc.NpcDefinition.MAX_TAGS) { kept(path, "an NPC keeps at most 32 tags"); continue; }
            if (seen.add(slug)) kept.add(slug);
            converted.add(path);
        }
        if (!kept.isEmpty()) fields.add("tags", kept);
    }

    private void attributes() {
        JsonObject trait = object(traits.get("attributetrait"));
        JsonObject attributes = trait == null ? null : object(trait.get("attributes"));
        if (attributes == null) return;
        JsonObject values = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : attributes.entrySet()) {
            String path = "/traits/attributetrait/attributes/" + CitizensBehaviors.pointer(entry.getKey());
            Optional<VanillaAttributes.Attribute> attribute = NpcAttributes.attribute(attributeId(entry.getKey()));
            if (attribute.isEmpty()) { kept(path, "attribute " + entry.getKey() + " is not a vanilla attribute on any supported Minecraft version"); continue; }
            if (!number(entry.getValue())) { kept(path, "the value is not a number"); continue; }
            VanillaAttributes.Attribute known = attribute.get();
            double value = entry.getValue().getAsDouble(), clamped = Math.max(known.minimum(), Math.min(known.maximum(), value));
            if (clamped != value) warnings.add(path + ": " + NpcAttributes.format(value) + " is outside " + NpcAttributes.format(known.minimum()) + ".."
                    + NpcAttributes.format(known.maximum()) + " for " + known.id() + " and became " + NpcAttributes.format(clamped));
            values.add(known.id(), clamped == value ? entry.getValue() : new JsonPrimitive(clamped));
            if (known.id().equals("step_height") && clamped > MovementProfile.MAX_STEP)
                note(path, CitizensImporter.Status.CONVERTED, "Animus steps up at most 1.5 blocks without a jump, so higher steps are jumped");
            else converted.add(path);
        }
        if (!values.isEmpty()) fields.add(NpcAttributes.FIELD, values);
    }

    /** A Citizens attribute key as a vanilla id: {@code minecraft:scale}, and the older {@code generic_scale}, {@code generic.scale} and {@code zombie_spawn_reinforcements} forms. */
    private static String attributeId(String key) {
        String id = key.toLowerCase(Locale.ROOT);
        if (id.startsWith(NpcAttributes.NAMESPACE)) id = id.substring(NpcAttributes.NAMESPACE.length());
        id = id.replace('.', '_');
        for (String prefix : List.of("generic_", "player_", "zombie_", "horse_")) if (id.startsWith(prefix)) return id.substring(prefix.length());
        return id;
    }

    private void rotation() {
        JsonObject rotation = object(traits.get("rotationtrait"));
        JsonObject parameters = rotation == null ? null : object(rotation.get("globalParameters"));
        if (parameters == null) return;
        for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
            String path = "/traits/rotationtrait/globalParameters/" + CitizensBehaviors.pointer(entry.getKey());
            String value = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "";
            // Animus turns heads and bodies at once, the way Citizens' immediate rotation does.
            if (entry.getKey().equals("immediate") || value.equals(ROTATION_DEFAULTS.get(entry.getKey()))) converted.add(path);
            else review(path, "Animus turns NPCs at once; gradual or head-only rotation is not available");
        }
    }

    private void targetable() {
        JsonObject targetable = object(traits.get("targetable"));
        if (targetable == null) return;
        Boolean value = bool(targetable.get("targetable"));
        if (Boolean.FALSE.equals(value) || Boolean.TRUE.equals(value) && combat) converted.add("/traits/targetable/targetable");
        else if (Boolean.TRUE.equals(value)) kept("/traits/targetable/targetable", "hostile mobs target only NPCs with an animus:combat module");
    }

    /** Whether hostile mobs may target the NPC in Citizens. */
    static boolean targetable(JsonObject traits) {
        JsonObject targetable = object(traits.get("targetable"));
        return targetable != null && Boolean.TRUE.equals(bool(targetable.get("targetable")));
    }

    /**
     * The combat module of a targetable NPC without Sentinel: it never attacks or fights back. Like every imported combat
     * module it stays disabled until its review is accepted.
     */
    static JsonObject targetableCombat() {
        JsonObject module = new JsonObject();
        module.addProperty("enabled", false);
        module.addProperty("reviewAccepted", false);
        JsonObject targeting = new JsonObject();
        targeting.addProperty("fightBack", false);
        module.add("targeting", targeting);
        return module;
    }

    /** The health module of a targetable NPC without Sentinel: it takes mob hits unless Citizens protected it. */
    static JsonObject targetableHealth(JsonObject record) {
        JsonObject metadata = object(record.get("metadata"));
        JsonObject module = new JsonObject();
        module.addProperty("killable", Boolean.FALSE.equals(metadata == null ? null : bool(metadata.get("protected"))));
        return module;
    }

    /** Whether Citizens ran vanilla AI on this NPC's body: player bodies have none. */
    static boolean wanders(String type, JsonObject record) {
        JsonObject metadata = object(record.get("metadata"));
        return !type.equals(BodyRules.PLAYER) && metadata != null && Boolean.TRUE.equals(bool(metadata.get("minecraft-ai")));
    }

    /** The wander route that stands in for vanilla random strolling around the NPC's spawn point. */
    static JsonObject vanillaWander(JsonObject record) {
        JsonObject navigator = object(record.get("navigator"));
        JsonElement modifier = navigator == null ? null : navigator.get("speedmodifier");
        JsonObject module = new JsonObject();
        module.addProperty("enabled", false);
        module.addProperty("reviewAccepted", false);
        module.addProperty("mode", "walk");
        module.addProperty("speed", CitizensBehaviors.speed(positive(modifier) ? modifier.getAsDouble() : 1));
        module.addProperty("order", "wander");
        module.addProperty("waitTicks", WANDER_WAIT);
        module.add("points", new JsonArray());
        JsonObject wander = new JsonObject();
        wander.addProperty("radius", WANDER_RADIUS);
        wander.addProperty("height", WANDER_HEIGHT);
        module.add("wander", wander);
        return module;
    }

    private void spawned() {
        JsonElement spawned = traits.get("spawned");
        if (spawned != null && spawned.isJsonPrimitive() && spawned.getAsJsonPrimitive().isBoolean()) converted.add("/traits/spawned");
    }

    private void mount() {
        for (JsonElement reference : references) {
            JsonObject entry = reference.getAsJsonObject();
            if (!entry.get("kind").getAsString().equals("mount")) continue;
            String status = entry.get("status").getAsString();
            if (status.equals("resolved")) converted.add("/traits/mounttrait/mountedon");
            else review("/traits/mounttrait/mountedon", "the NPC this one rides is " + status + " in the save; set animus:mount by hand");
        }
    }

    private void controllable() {
        JsonObject controllable = object(traits.get("controllable"));
        if (controllable == null) return;
        if (Boolean.FALSE.equals(bool(controllable.get("enabled")))) converted.add("/traits/controllable/enabled");
    }

    /** Citizens' {@code name;yaw;pitch} pose list becomes the definition's named {@code poses}, for {@code animus:pose}. */
    private void poses() {
        JsonObject poses = object(traits.get("poses"));
        if (poses == null || !poses.has("list")) return;
        JsonObject named = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : CitizensBehaviors.entries(poses.get("list"))) {
            String path = "/traits/poses/list/" + entry.getKey();
            String[] parts = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString().split(";") : new String[0];
            Double yaw = parts.length >= 3 ? decimal(parts[1]) : null, pitch = parts.length >= 3 ? decimal(parts[2]) : null;
            if (yaw == null || pitch == null) { kept(path, "not a name;yaw;pitch pose"); continue; }
            String name = parts[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[^a-z0-9]+", "");
            if (name.length() > 32) name = name.substring(0, 32);
            if (name.isEmpty()) { kept(path, "the pose name has no letters or digits to keep"); continue; }
            if (named.has(name)) { kept(path, "another pose already uses the name " + name); continue; }
            if (named.size() == art.arcane.animus.core.npc.NamedPoses.MAXIMUM_POSES) { kept(path, "an NPC keeps at most 64 poses"); continue; }
            JsonObject facing = new JsonObject();
            facing.addProperty("yaw", BigDecimal.valueOf(yaw % 360).setScale(3, RoundingMode.HALF_UP).doubleValue());
            facing.addProperty("pitch", BigDecimal.valueOf(Math.max(-90, Math.min(90, pitch))).setScale(3, RoundingMode.HALF_UP).doubleValue());
            named.add(name, facing);
            converted.add(path);
        }
        if (!named.isEmpty()) fields.add("poses", named);
    }

    private static Double decimal(String text) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    private void horse() {
        JsonObject horse = object(traits.get("horsemodifiers"));
        if (horse == null) return;
        for (String key : List.of("color", "style", "carryingChest", "tamed"))
            if (horse.has(key)) review("/traits/horsemodifiers/" + key, "horse colours, markings and chests are not shown");
    }

    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        String text = value.getAsString();
        return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
    }
    private static boolean number(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble());
    }
    private static boolean positive(JsonElement value) { return number(value) && value.getAsDouble() > 0; }
}
