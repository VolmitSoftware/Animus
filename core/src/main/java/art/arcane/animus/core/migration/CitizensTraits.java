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
    private final boolean combat, routed, patrolled;
    private boolean raisedFall;
    private final JsonArray references;
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private CitizensTraits(String type, JsonObject record, JsonObject traits, boolean combat, boolean routed, boolean patrolled, JsonArray references) {
        this.type = type; this.record = record; this.traits = traits; this.combat = combat; this.routed = routed; this.patrolled = patrolled;
        this.references = references;
    }

    /**
     * @param type the definition's body type
     * @param traits the review copy of the NPC's traits
     * @param combat whether the NPC converts with an {@code animus:combat} module
     * @param routed whether the NPC converts with an {@code animus:navigation} route
     * @param patrolled whether that route comes from Citizens waypoints; it then opens doors and drops at least {@link #ROUTE_FALL} blocks
     * @param references the resolved NPC references
     */
    static Result map(String type, JsonObject record, JsonObject traits, boolean combat, boolean routed, boolean patrolled, JsonArray references) {
        CitizensTraits mapping = new CitizensTraits(type, record, traits, combat, routed, patrolled, references);
        mapping.metadata(); mapping.movement(); mapping.age(); mapping.gravity(); mapping.scoreboard(); mapping.attributes(); mapping.rotation();
        mapping.targetable(); mapping.follow(); mapping.home(); mapping.pause(); mapping.kept(); mapping.spawned(); mapping.mount(); mapping.controllable(); mapping.poses();
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
                case "protected" -> { if (flag != null) converted.add(path); }
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
        JsonObject attributes = trait == null ? new JsonObject() : Objects.requireNonNullElseGet(object(trait.get("attributes")), JsonObject::new);
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
        scaledHealth(values);
        if (!values.isEmpty()) fields.add(NpcAttributes.FIELD, values);
    }

    /** ScaledMaxHealthTrait's maximum health becomes the max_health attribute, unless the attribute trait sets one. */
    private void scaledHealth(JsonObject values) {
        JsonObject scaled = object(traits.get("scaledhealthtrait"));
        if (scaled == null || !scaled.has("maxHealth")) return;
        String path = "/traits/scaledhealthtrait/maxHealth";
        if (!number(scaled.get("maxHealth"))) { kept(path, "the value is not a number"); return; }
        if (values.has("max_health")) { note(path, CitizensImporter.Status.CONVERTED, "the attribute trait's max_health is used"); return; }
        VanillaAttributes.Attribute known = NpcAttributes.attribute("max_health").orElseThrow();
        double value = scaled.get("maxHealth").getAsDouble(), clamped = Math.max(known.minimum(), Math.min(known.maximum(), value));
        if (clamped != value) warnings.add(path + ": " + NpcAttributes.format(value) + " is outside " + NpcAttributes.format(known.minimum()) + ".."
                + NpcAttributes.format(known.maximum()) + " for max_health and became " + NpcAttributes.format(clamped));
        values.add("max_health", clamped == value ? scaled.get("maxHealth") : new JsonPrimitive(clamped));
        converted.add(path);
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
        boolean looks = traits.has("lookclose");
        for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
            String path = "/traits/rotationtrait/globalParameters/" + CitizensBehaviors.pointer(entry.getKey());
            String value = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "";
            if (entry.getKey().equals("immediate") || value.equals(ROTATION_DEFAULTS.get(entry.getKey()))) converted.add(path);
            // Look-close sets head-only and linked body itself each time it turns the NPC.
            else if (entry.getKey().equals("headOnly") || entry.getKey().equals("linkedBody"))
                note(path, CitizensImporter.Status.CONVERTED, "look-close's own head-only and linked-body settings apply");
            else if (!ROTATION_DEFAULTS.containsKey(entry.getKey())) kept(path, "rotation setting this converter does not know");
            else if (looks) note(path, CitizensImporter.Status.CONVERTED, "became turnRate in animus:look-close");
            else note(path, CitizensImporter.Status.CONVERTED, "only look-close turns gradually, and this NPC has no look-close");
        }
    }

    /** Citizens' {@code name;world;x;y;z} anchors become the definition's named {@code anchors}; one in the NPC's world names no world. */
    private void anchors() {
        JsonObject anchors = object(traits.get("anchors"));
        if (anchors == null) return;
        JsonObject location = object(traits.get("location"));
        String own = location == null ? "" : text(location.get("world"));
        JsonObject named = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : CitizensBehaviors.entries(anchors.get("list"))) {
            String path = "/traits/anchors/list/" + entry.getKey();
            String[] parts = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString().split(";") : new String[0];
            Double x = parts.length == 5 ? decimal(parts[2]) : null, y = parts.length == 5 ? decimal(parts[3]) : null, z = parts.length == 5 ? decimal(parts[4]) : null;
            if (x == null || y == null || z == null || parts[1].isBlank() || Math.abs(x) > 29_999_984 || Math.abs(y) > 29_999_984 || Math.abs(z) > 29_999_984) {
                kept(path, "not a name;world;x;y;z anchor"); continue;
            }
            String name = parts[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[^a-z0-9]+", "");
            if (name.length() > 32) name = name.substring(0, 32);
            if (name.isEmpty()) { kept(path, "the anchor name has no letters or digits to keep"); continue; }
            if (named.has(name)) { kept(path, "another anchor already uses the name " + name); continue; }
            if (named.size() == art.arcane.animus.core.npc.NpcAnchors.MAXIMUM_ANCHORS) { kept(path, "an NPC keeps at most 64 anchors"); continue; }
            JsonObject anchor = new JsonObject();
            anchor.addProperty("x", x); anchor.addProperty("y", y); anchor.addProperty("z", z);
            if (!parts[1].equals(own)) anchor.addProperty("world", parts[1]);
            named.add(name, anchor);
            converted.add(path);
        }
        if (!named.isEmpty()) fields.add("anchors", named);
    }

    /** Citizens' mirror trait as {@code animus:mirror}: each viewer sees their own skin, and with its options their name and equipment. */
    static Optional<JsonObject> mirror(JsonObject traits) {
        JsonObject trait = object(traits.get("mirrortrait"));
        if (trait == null || !Boolean.TRUE.equals(bool(trait.get("enabled")))) return Optional.empty();
        JsonObject mirror = new JsonObject();
        mirror.addProperty("skin", true);
        if (Boolean.TRUE.equals(bool(trait.get("mirrorName")))) mirror.addProperty("name", true);
        if (Boolean.TRUE.equals(bool(trait.get("mirrorEquipment")))) mirror.addProperty("equipment", true);
        return Optional.of(mirror);
    }

    private void kept() {
        anchors();
        if (traits.has("mirrortrait")) note("/traits/mirrortrait", CitizensImporter.Status.CONVERTED, mirror(traits).isPresent() ? "became animus:mirror" : "");
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

    /** The combat module of a targetable NPC without Sentinel: it never attacks or fights back. */
    static JsonObject targetableCombat() {
        JsonObject module = new JsonObject();
        module.addProperty("enabled", true);
        JsonObject targeting = new JsonObject();
        targeting.addProperty("fightBack", false);
        module.add("targeting", targeting);
        return module;
    }

    /** The health module of an NPC without Sentinel: killable unless Citizens protected it, which is the default. */
    static JsonObject health(JsonObject record) {
        JsonObject metadata = object(record.get("metadata"));
        JsonObject module = new JsonObject();
        module.addProperty("killable", Boolean.FALSE.equals(metadata == null ? null : bool(metadata.get("protected"))));
        return module;
    }

    /**
     * Citizens' follow trait as an {@code animus:combat} guard: the NPC walks after the player or NPC it follows and, with
     * {@code protect}, fights whatever hurts it. Empty when the trait follows nobody.
     */
    static Optional<JsonObject> follow(JsonObject traits, Set<String> npcUuids) {
        JsonObject follow = object(traits.get("followtrait"));
        UUID target = follow == null ? null : uuid(follow.get("followingUUID"));
        if (target == null) return Optional.empty();
        JsonObject guard = new JsonObject();
        guard.addProperty(npcUuids.contains(target.toString()) ? "npc" : "player", target.toString());
        JsonElement margin = follow.get("margin");
        double distance = positive(margin) ? Math.max(1, Math.min(64, margin.getAsDouble())) : FOLLOW_DISTANCE;
        guard.addProperty("followDistance", distance);
        guard.addProperty("pointRange", Math.min(FOLLOW_POINT, distance));
        guard.addProperty("protect", Boolean.TRUE.equals(bool(follow.get("protect"))));
        return Optional.of(guard);
    }
    /** How close a Citizens follower stays when it sets no margin, and how far behind its charge it aims. */
    static final double FOLLOW_DISTANCE = 2, FOLLOW_POINT = 1;

    private void follow() {
        JsonObject follow = object(traits.get("followtrait"));
        if (follow == null) return;
        if (uuid(follow.get("followingUUID")) == null) {
            if (follow.has("followingUUID")) kept("/traits/followtrait/followingUUID", "not a UUID, so the NPC follows nobody");
            for (String field : List.of("margin", "protect")) if (follow.has(field)) converted.add("/traits/followtrait/" + field);
            return;
        }
        for (String field : List.of("followingUUID", "margin", "protect")) if (follow.has(field)) converted.add("/traits/followtrait/" + field);
        if (follow.has("followingUUID")) note("/traits/followtrait/followingUUID", CitizensImporter.Status.CONVERTED,
                "became the guard of animus:combat");
    }

    private static UUID uuid(JsonElement value) {
        String text = text(value);
        return text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? UUID.fromString(text) : null;
    }

    /**
     * Citizens' home trait as {@code animus:home} when its home is in the NPC's world. A delay or distance of -1, Citizens'
     * "none", becomes 0; {@code PATHFIND} walks home and {@code TELEPORT} teleports.
     */
    static Optional<JsonObject> home(JsonObject traits, String world) {
        JsonObject trait = object(traits.get("hometrait"));
        JsonObject location = trait == null ? null : object(trait.get("location"));
        if (location == null || world.isEmpty() || !world.equals(text(location.get("world")))
                || !number(location.get("x")) || !number(location.get("y")) || !number(location.get("z"))) return Optional.empty();
        JsonObject home = new JsonObject();
        for (String axis : List.of("x", "y", "z")) {
            if (Math.abs(location.get(axis).getAsDouble()) > 29_999_984) return Optional.empty();
            home.addProperty(axis, location.get(axis).getAsDouble());
        }
        if (number(location.get("yaw"))) home.addProperty("yaw", degrees(location.get("yaw").getAsDouble() % 360));
        if (number(location.get("pitch"))) home.addProperty("pitch", degrees(Math.max(-90, Math.min(90, location.get("pitch").getAsDouble()))));
        JsonElement delay = trait.get("delay"), distance = trait.get("distance");
        if (number(delay) && delay.getAsDouble() > 0) home.addProperty("delayTicks", Math.min(72_000, (long) Math.floor(delay.getAsDouble())));
        if (number(distance) && distance.getAsDouble() > 0) home.addProperty("distance", Math.min(256, distance.getAsDouble()));
        home.addProperty("return", text(trait.get("strategy")).equals("PATHFIND") ? "walk" : "teleport");
        return Optional.of(home);
    }

    private void home() {
        JsonObject trait = object(traits.get("hometrait"));
        if (trait == null) return;
        JsonObject location = object(traits.get("location"));
        if (home(traits, location == null ? "" : text(location.get("world"))).isPresent()) note("/traits/hometrait", CitizensImporter.Status.CONVERTED, "");
        else kept("/traits/hometrait", "the home is missing or in another world than the NPC; set animus:home by hand");
    }

    /** Citizens' pause-pathfinding trait as the {@code pause} of the NPC's route. */
    static Optional<JsonObject> pause(JsonObject traits) {
        JsonObject trait = object(traits.get("pausepathfinding"));
        if (trait == null) return Optional.empty();
        JsonObject pause = new JsonObject();
        JsonElement range = trait.get("playerrange"), ticks = trait.get("pauseticks"), lockout = trait.get("lockoutduration");
        if (positive(range)) pause.addProperty("playerRange", Math.min(64, range.getAsDouble()));
        if (Boolean.TRUE.equals(bool(trait.get("rightclick")))) pause.addProperty("onClick", true);
        if (!pause.has("playerRange") && !pause.has("onClick")) return Optional.empty();
        pause.addProperty("ticks", positive(ticks) ? Math.min(12_000, Math.max(1, (long) Math.floor(ticks.getAsDouble()))) : 20);
        if (positive(lockout)) pause.addProperty("lockoutTicks", Math.min(72_000, (long) Math.floor(lockout.getAsDouble())));
        return Optional.of(pause);
    }

    private void pause() {
        JsonObject trait = object(traits.get("pausepathfinding"));
        if (trait == null) return;
        Optional<JsonObject> pause = pause(traits);
        // A trait that pauses for nothing changes nothing.
        if (routed || pause.isEmpty()) note("/traits/pausepathfinding", CitizensImporter.Status.CONVERTED, "");
        else kept("/traits/pausepathfinding", "the NPC has no route to pause");
        if (routed && pause.isPresent() && positive(trait.get("playerrange")) && trait.get("playerrange").getAsDouble() > 64)
            warnings.add("/traits/pausepathfinding/playerrange: pause ranges above 64 blocks became 64");
    }

    /** A JSON string's text, or empty for anything else. */
    private static String text(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : ""; }
    private static double degrees(double value) { return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue(); }

    /** Whether the NPC's metadata turns Citizens' protection off. */
    static boolean unprotected(JsonObject record) {
        JsonObject metadata = object(record.get("metadata"));
        return metadata != null && Boolean.FALSE.equals(bool(metadata.get("protected")));
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
        module.addProperty("enabled", true);
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
