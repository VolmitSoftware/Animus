package art.arcane.animus.core.migration;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Maps Citizens NPC metadata, navigator settings and the small state traits (age, gravity, glow, team colour, tags,
 * attributes, rotation, protection, targeting, spawn state, mounts and riding) to definition fields. Fields with no
 * Animus equivalent get a one-line reason; fields that change nothing in Citizens either count as converted.
 */
final class CitizensTraits {
    record Result(JsonObject fields, JsonObject migration, Set<String> converted, Map<String, CitizensImporter.Note> notes, List<String> warnings) {
        Result {
            fields = fields.deepCopy(); migration = migration.deepCopy(); converted = Set.copyOf(converted); notes = Map.copyOf(notes); warnings = List.copyOf(warnings);
        }
        @Override public JsonObject fields() { return fields.deepCopy(); }
        @Override public JsonObject migration() { return migration.deepCopy(); }
    }
    /** The scoreboard tag Citizens puts on every NPC it spawns. */
    static final String CITIZENS_TAG = "CITIZENS_NPC";
    private static final Map<String, String> ROTATION_DEFAULTS = Map.of("headOnly", "false", "immediate", "false", "linkedBody", "false", "lockPitch", "false",
            "maxPitchPerTick", "10.0", "maxYawPerTick", "40.0");

    private final String type;
    private final JsonObject record, traits, fields = new JsonObject(), migration = new JsonObject();
    private final boolean combat;
    private final JsonArray references;
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private CitizensTraits(String type, JsonObject record, JsonObject traits, boolean combat, JsonArray references) {
        this.type = type; this.record = record; this.traits = traits; this.combat = combat; this.references = references;
    }

    /**
     * @param type the definition's body type
     * @param traits the review copy of the NPC's traits
     * @param combat whether the NPC converts with an {@code animus:combat} module
     * @param references the resolved NPC references
     */
    static Result map(String type, JsonObject record, JsonObject traits, boolean combat, JsonArray references) {
        CitizensTraits mapping = new CitizensTraits(type, record, traits, combat, references);
        mapping.metadata(); mapping.movement(); mapping.age(); mapping.gravity(); mapping.scoreboard(); mapping.attributes(); mapping.rotation();
        mapping.targetable(); mapping.spawned(); mapping.mount(); mapping.controllable(); mapping.poses(); mapping.horse();
        mapping.note("/traitnames", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "Citizens bookkeeping of the traits the NPC had; nothing to convert");
        List<String> sorted = new ArrayList<>(mapping.warnings);
        Collections.sort(sorted);
        return new Result(mapping.fields, mapping.migration, mapping.converted, mapping.notes, sorted);
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
                    if (Boolean.FALSE.equals(flag)) converted.add(path);
                    else if (Boolean.TRUE.equals(flag)) kept(path, "Animus bodies have no vanilla mob AI; use routes, look-close or combat instead");
                }
                case "protected" -> {
                    if (Boolean.TRUE.equals(flag) || Boolean.FALSE.equals(flag) && combat) converted.add(path);
                    else if (Boolean.FALSE.equals(flag)) kept(path, "Animus NPCs take damage only through an enabled animus:combat module, which this NPC does not have");
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
                case "pathfinder-open-doors" -> {
                    if (Boolean.FALSE.equals(flag)) converted.add(path);
                    else if (Boolean.TRUE.equals(flag)) review(path, "Animus routes do not open doors; leave the doors on this NPC's route open or add points around them");
                }
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
        if (metadata != null && Boolean.TRUE.equals(bool(metadata.get("flyable")))) { movement.addProperty("fly", true); any = true; }
        JsonObject attributes = object(object(traits.get("attributetrait")) == null ? null : traits.getAsJsonObject("attributetrait").get("attributes"));
        JsonElement step = attributes == null ? null : attributes.get("minecraft:step_height");
        if (number(step) && step.getAsDouble() >= 0) {
            double height = Math.min(MovementProfile.MAX_STEP, step.getAsDouble());
            movement.addProperty("stepHeight", height);
            String path = "/traits/attributetrait/attributes/minecraft:step_height";
            if (step.getAsDouble() > MovementProfile.MAX_STEP)
                note(path, CitizensImporter.Status.CONVERTED, "Animus steps up at most 1.5 blocks without a jump, so higher steps are jumped");
            else converted.add(path);
            any = true;
        }
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
        for (String key : attributes.keySet()) {
            if (key.equals("minecraft:step_height")) continue;
            kept("/traits/attributetrait/attributes/" + CitizensBehaviors.pointer(key), "attribute " + key + " has no Animus setting");
        }
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
        if (Boolean.FALSE.equals(value)) converted.add("/traits/targetable/targetable");
        else if (Boolean.TRUE.equals(value)) kept("/traits/targetable/targetable", "Animus bodies are packet-only, so hostile mobs never target them");
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

    private void poses() {
        JsonObject poses = object(traits.get("poses"));
        if (poses == null || !poses.has("list")) return;
        note("/traits/poses/list", CitizensImporter.Status.UNSUPPORTED_PRESERVED, "named poses are used by Citizens pose commands; Animus has no named poses, only the default facing");
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
