package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.core.combat.FightKind;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.hire.HireTerms;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.navigation.MovementSettings;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.HologramStyle;
import art.arcane.animus.core.npc.NamedPoses;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcInventory;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The NPC definition schema, read from the same bounds {@link NpcDefinition#validate} enforces. Unknown root fields and
 * modules stay allowed because the definition keeps them; the modules Animus reads refuse unknown keys as their parsers do.
 */
public final class NpcSchemaGenerator {
    private static final String IDENTIFIER = "^[a-z0-9][a-z0-9_-]{0,63}$";
    private static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final String NAMESPACED = "^[a-z0-9_.-]+:[a-z0-9_./-]+$";
    private static final String NONBLANK = "\\S";
    private static final String COMBAT = "animus:combat";
    private NpcSchemaGenerator() {}

    /** Validation of NPC definitions as files and commands see it; unknown fields and modules are preserved. */
    public static JsonObject schema() {
        JsonObject root = new JsonObject();
        root.addProperty("$schema", BehaviorSchemaGenerator.DIALECT);
        root.addProperty("title", "Animus NPC definition");
        root.addProperty("description", "npcs/<folders>/<id>.json, in npcs/ itself or up to " + NpcPaths.MAX_FOLDERS + " folders deep. Unknown fields and modules are kept as written.");
        root.addProperty("type", "object");
        root.add("required", strings("schemaVersion", "revision", "uuid", "id", "group", "name", "type", "spawned", "position"));
        JsonObject properties = new JsonObject();
        properties.add("schemaVersion", constant(1));
        properties.add("revision", described(range("integer", 1, Long.MAX_VALUE), "Raise this with every edit."));
        properties.add("uuid", described(pattern(UUID), "A canonical lowercase UUID that never changes."));
        properties.add("id", described(pattern(IDENTIFIER), "Matches the file name."));
        properties.add("group", described(pattern("^" + NpcPaths.FOLDER + "$"), "The folder under npcs/, empty for npcs/ itself. Animus sets it from where the file is."));
        properties.add("name", described(text(NpcAppearance.MAX_TEXT), "The displayed name."));
        JsonObject type = described(pattern("^[a-z0-9_]+:[a-z0-9_/]+$"), "An entity type such as minecraft:player. The examples are the bodies every transport draws.");
        type.add("examples", strings(BodyRules.TYPES.stream().sorted().toArray(String[]::new)));
        properties.add("type", type);
        properties.add("spawned", described(type("boolean"), "Whether the NPC is presented."));
        JsonObject position = type("object");
        position.add("required", strings("world", "x", "y", "z", "yaw", "pitch"));
        JsonObject coordinates = new JsonObject();
        coordinates.add("world", pattern(NONBLANK));
        for (String axis : List.of("x", "y", "z", "yaw", "pitch")) coordinates.add(axis, range("number", -30_000_000, 30_000_000));
        position.add("properties", coordinates);
        properties.add("position", position);
        properties.add("owner", described(pattern(UUID), "The owning player's UUID, canonical and lowercase. Modules may use it for their own permission rules."));
        properties.add("skin", described(pattern("^[a-f0-9]{64}$"), "SHA-256 of a skin document under skins/."));
        properties.add("equipment", equipment());
        properties.add("pose", described(choices(Arrays.stream(NpcPose.values()).map(NpcPose::key).toList()), "The body pose. Only standing applies to bodies other than minecraft:player."));
        properties.add("hologram", hologram());
        JsonObject nameplate = described(closed(), "Whether the name shows above the body.");
        nameplate.add("properties", property("visible", described(type("boolean"), "True by default.")).getAsJsonObject("properties"));
        properties.add("nameplate", nameplate);
        properties.add("armorStand", armorStand());
        properties.add("glowing", described(type("boolean"), "An outline glow in the team colour."));
        properties.add("teamColor", described(choices(BodyTraits.TEAM_COLORS), "The colour of the glow and the name."));
        properties.add("baby", described(type("boolean"), "A baby body, for types that have one."));
        properties.add("usingItem", described(type("boolean"), "Holds the main-hand item raised."));
        properties.add("listed", described(type("boolean"), "Player bodies only: show an entry in the tab list."));
        properties.add("gravity", described(type("boolean"), "False keeps the body where it is placed. True by default."));
        properties.add("inventory", inventory());
        properties.add("poses", poses());
        properties.add("movement", movement());
        JsonObject behaviors = described(type("array"), "Behaviors whose triggers this NPC runs.");
        behaviors.add("items", pattern("^[a-z0-9_-]+:[a-z0-9_/-]+$"));
        properties.add("behaviors", behaviors);
        JsonObject tags = described(type("array"), "Labels for finding and grouping NPCs in the editor; at most 32.");
        tags.add("items", pattern("^[a-z0-9][a-z0-9_-]{0,31}$"));
        tags.addProperty("maxItems", NpcDefinition.MAX_TAGS);
        tags.addProperty("uniqueItems", true);
        properties.add("tags", tags);
        JsonObject groups = described(type("array"), "Trait groups under groups/ whose values this NPC takes, in merge order; the NPC's own fields win.");
        groups.add("items", pattern("^[a-z0-9][a-z0-9_-]{0,63}$"));
        groups.addProperty("minItems", 1);
        groups.addProperty("maxItems", NpcDefinition.MAX_GROUPS);
        groups.addProperty("uniqueItems", true);
        properties.add("groups", groups);
        JsonObject rig = described(closed(), "The rig under rigs/ this NPC's body shows.");
        rig.add("required", strings("id"));
        JsonObject rigId = pattern("^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$");
        rigId.addProperty("maxLength", 256);
        rig.add("properties", property("id", rigId).getAsJsonObject("properties"));
        properties.add("rig", rig);
        JsonObject modules = described(type("object"), "Per-NPC module settings, keyed by namespace:name. Unknown modules are kept.");
        JsonObject known = new JsonObject();
        known.add("animus:navigation", navigation());
        known.add(COMBAT, CombatSchema.module());
        known.add(ShopBinding.MODULE, shop());
        known.add(GuiBinding.MODULE, gui());
        known.add(HireTerms.MODULE, hire());
        known.add(MountDeclaration.MODULE, mount());
        known.add(RideSettings.MODULE, riding());
        known.add(LookClose.MODULE, lookClose());
        modules.add("properties", known);
        properties.add("modules", modules);
        root.add("properties", properties);
        JsonObject definitions = new JsonObject();
        definitions.add("item", item());
        definitions.add("location", CombatSchema.location());
        definitions.add("seekingRules", CombatSchema.rules(CombatSchema.Level.SEEKING));
        definitions.add("ignoringRules", CombatSchema.rules(CombatSchema.Level.IGNORING));
        definitions.add("nestedRules", CombatSchema.rules(CombatSchema.Level.NESTED));
        root.add("$defs", definitions);
        JsonArray rules = new JsonArray();
        JsonObject imported = new JsonObject();
        JsonObject migration = new JsonObject();
        migration.add("required", strings("migration"));
        imported.add("if", migration);
        JsonObject reviewed = new JsonObject();
        reviewed.add("animus:navigation", reviewRequired());
        reviewed.add(RideSettings.MODULE, reviewRequired());
        JsonObject reviewedModules = new JsonObject();
        reviewedModules.add("properties", reviewed);
        imported.add("then", property("modules", reviewedModules));
        rules.add(imported);
        rules.add(onlyForType(BodyRules.PLAYER, property("pose", choices(List.of(NpcPose.STANDING.key())))));
        JsonObject noArmorStand = new JsonObject();
        noArmorStand.add("not", required("armorStand"));
        rules.add(onlyForType("minecraft:armor_stand", noArmorStand));
        root.add("allOf", rules);
        return root;
    }

    /** Bodies other than {@code type} must also meet {@code otherwise}. */
    private static JsonObject onlyForType(String type, JsonObject otherwise) {
        JsonObject rule = new JsonObject();
        rule.add("if", property("type", constant(type)));
        rule.add("else", otherwise);
        return rule;
    }

    private static JsonObject item() {
        JsonObject item = described(closed(), "An item: its id, a count and the component text of a give command.");
        item.add("required", strings("id"));
        JsonObject fields = new JsonObject();
        JsonObject id = described(pattern(NAMESPACED), "namespace:item, such as minecraft:iron_sword.");
        id.addProperty("maxLength", 128);
        fields.add("id", id);
        fields.add("count", described(range("integer", 1, NpcItem.MAX_COUNT), "1 by default."));
        JsonObject components = described(pattern("^\\[.*\\]$"), "Text after the item id in a give command, such as [enchantment_glint_override=true].");
        components.addProperty("maxLength", NpcItem.MAX_COMPONENTS);
        fields.add("components", components);
        fields.add("imported", described(type("object"), "Source item data from an import, kept and never interpreted."));
        item.add("properties", fields);
        return item;
    }

    private static JsonObject equipment() {
        JsonObject equipment = described(closed(), "Items the body wears and holds, by slot.");
        JsonObject slots = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) slots.add(slot.key(), reference("item"));
        equipment.add("properties", slots);
        return equipment;
    }

    private static JsonObject hologram() {
        JsonObject hologram = described(closed(), "Text lines above the body. & colour codes such as &a are drawn in colour.");
        hologram.add("required", strings("lines"));
        JsonObject fields = new JsonObject();
        JsonObject line = described(closed(), "A line with extra space above or below it.");
        line.add("required", strings("text"));
        JsonObject lineFields = new JsonObject();
        lineFields.add("text", lineText());
        lineFields.add("marginTop", described(range("number", -HologramStyle.MAX_MARGIN, HologramStyle.MAX_MARGIN), "Blocks of space above the line."));
        lineFields.add("marginBottom", described(range("number", -HologramStyle.MAX_MARGIN, HologramStyle.MAX_MARGIN), "Blocks of space below the line."));
        line.add("properties", lineFields);
        JsonObject either = new JsonObject();
        JsonArray forms = new JsonArray();
        forms.add(lineText());
        forms.add(line);
        either.add("anyOf", forms);
        fields.add("lines", list(either, 0, NpcAppearance.MAX_LINES, "Top line first, at most " + NpcAppearance.MAX_LINES + "."));
        fields.add("lineHeight", described(range("number", 0, HologramStyle.MAX_LINE_HEIGHT), "Blocks between lines; gives each line its own display."));
        fields.add("viewRange", described(range("number", HologramStyle.MIN_VIEW_RANGE, HologramStyle.MAX_VIEW_RANGE), "Blocks from which the text is seen."));
        fields.add("billboard", described(choices(HologramStyle.BILLBOARDS.stream().sorted().toList()), "How the text turns toward the viewer. center by default."));
        fields.add("shadowed", described(type("boolean"), "Draw a shadow under the text."));
        fields.add("seeThrough", described(type("boolean"), "Show the text through blocks."));
        fields.add("interpolationDelay", described(range("integer", 0, HologramStyle.MAX_INTERPOLATION), "Ticks before a change starts to animate."));
        fields.add("interpolationDuration", described(range("integer", 0, HologramStyle.MAX_INTERPOLATION), "Ticks a change takes to animate."));
        hologram.add("properties", fields);
        return hologram;
    }

    private static JsonObject lineText() {
        JsonObject text = type("string");
        text.addProperty("maxLength", NpcAppearance.MAX_TEXT);
        return text;
    }

    private static JsonObject armorStand() {
        JsonObject stand = described(closed(), "minecraft:armor_stand bodies only: parts shown and pose angles in degrees.");
        JsonObject fields = new JsonObject();
        fields.add("small", described(type("boolean"), "A small stand."));
        fields.add("arms", described(type("boolean"), "Show arms."));
        fields.add("basePlate", described(type("boolean"), "Show the base plate. True by default."));
        fields.add("invisible", described(type("boolean"), "Hide the stand and show only what it wears."));
        for (ArmorStandLook.Part part : ArmorStandLook.Part.values()) {
            JsonObject angles = list(range("number", -360, 360), 3, 3, "[x, y, z] degrees; omit for the default pose.");
            fields.add(part.key(), angles);
        }
        stand.add("properties", fields);
        return stand;
    }

    private static JsonObject inventory() {
        JsonObject inventory = described(closed(), "A stored inventory for behaviors and modules to read. Nothing draws it.");
        inventory.add("required", strings("size", "slots"));
        JsonObject fields = new JsonObject();
        JsonArray sizes = new JsonArray();
        for (int size = 9; size <= NpcInventory.MAX_SIZE; size += 9) sizes.add(size);
        JsonObject size = described(new JsonObject(), "Slots: a multiple of 9 up to " + NpcInventory.MAX_SIZE + ".");
        size.add("enum", sizes);
        fields.add("size", size);
        JsonObject slots = described(type("object"), "Items by slot number from 0, each inside size.");
        slots.add("propertyNames", pattern("^(0|[1-9][0-9]?)$"));
        slots.add("additionalProperties", reference("item"));
        fields.add("slots", slots);
        inventory.add("properties", fields);
        return inventory;
    }

    private static JsonObject poses() {
        JsonObject poses = described(type("object"), "Named look directions the animus:pose action turns to. They change where the NPC looks, never its body pose.");
        poses.addProperty("maxProperties", NamedPoses.MAXIMUM_POSES);
        poses.add("propertyNames", pattern("^" + NamedPoses.NAME + "$"));
        JsonObject facing = closed();
        facing.add("required", strings("yaw"));
        JsonObject fields = new JsonObject();
        fields.add("yaw", range("number", -360, 360));
        fields.add("pitch", described(range("number", -90, 90), "0 by default."));
        facing.add("properties", fields);
        poses.add("additionalProperties", facing);
        return poses;
    }

    private static JsonObject movement() {
        JsonObject movement = described(closed(), "How this NPC walks wherever it walks. A route's own speed wins over speed.");
        JsonObject fields = new JsonObject();
        fields.add("speed", described(range("number", 1, 5), "Blocks per second."));
        fields.add("avoidWater", described(type("boolean"), "False lets a walking route cross water at the surface. True by default."));
        fields.add("fly", described(type("boolean"), "Routes fly."));
        fields.add("openDoors", described(type("boolean"), "Walk through wooden doors, fence gates and trapdoors, closing them behind."));
        fields.add("stepHeight", described(range("number", 0, MovementProfile.MAX_STEP), "Blocks the body climbs without jumping."));
        fields.add("maximumFall", described(range("integer", 0, 16), "Blocks the body drops."));
        fields.add("pathRange", described(range("number", WaypointSettings.MINIMUM_LINK_DISTANCE, WaypointSettings.MAXIMUM_LINK_DISTANCE), "Blocks one plan reaches; longer legs walk in segments."));
        fields.add("arrivalMargin", described(range("number", 0, MovementSettings.MAX_ARRIVAL_MARGIN), "Blocks from a point that count as reaching it."));
        JsonObject stuck = described(closed(), "What an NPC that has stopped getting closer does.");
        JsonObject stuckFields = new JsonObject();
        stuckFields.add("afterTicks", described(range("integer", 1, MovementSettings.MAX_STUCK_TICKS), "100 by default."));
        stuckFields.add("teleport", described(type("boolean"), "Teleport to the point; false stops instead. True by default."));
        stuck.add("properties", stuckFields);
        fields.add("stuck", stuck);
        movement.add("properties", fields);
        return movement;
    }

    private static JsonObject shop() {
        JsonObject shop = described(closed(), "The shop a click opens. A sneaking administrator selects the NPC instead.");
        shop.add("required", strings("shop"));
        JsonObject fields = new JsonObject();
        fields.add("shop", described(pattern("^" + ShopDocument.ID + "$"), "A shop id such as town:baker."));
        fields.add("openOn", described(choices(List.of("right", "left", "both")), "Which click opens it. right by default."));
        fields.add("imported", described(new JsonObject(), "Kept from an import."));
        fields.add("reviewAccepted", described(type("boolean"), "An imported binding opens nothing until this is true."));
        shop.add("properties", fields);
        return shop;
    }

    private static JsonObject gui() {
        JsonObject gui = described(closed(), "A chest GUI, menu, shop or Gloss menu a click opens, and a hologram that follows the NPC.");
        gui.addProperty("minProperties", 1);
        JsonObject fields = new JsonObject();
        JsonObject open = closed();
        open.add("required", strings("id"));
        JsonObject openFields = new JsonObject();
        openFields.add("kind", described(choices(List.of("inventory", "menu", "shop", "gloss")), "inventory by default."));
        openFields.add("id", described(type("string"), "An id such as town:menu, or a Gloss menu path such as lobby/main."));
        openFields.add("openOn", described(choices(List.of("right", "left", "both")), "Which click opens it. right by default."));
        open.add("properties", openFields);
        JsonArray openRules = new JsonArray();
        JsonObject glossId = new JsonObject();
        glossId.add("if", property("kind", constant("gloss")));
        glossId.getAsJsonObject("if").add("required", strings("kind"));
        glossId.add("then", property("id", pattern("^" + GuiBinding.GLOSS_ID + "$")));
        glossId.add("else", property("id", pattern("^" + GuiBinding.ID + "$")));
        openRules.add(glossId);
        open.add("allOf", openRules);
        fields.add("open", open);
        JsonObject hologram = closed();
        hologram.add("required", strings("id"));
        JsonObject hologramFields = new JsonObject();
        hologramFields.add("id", described(pattern("^" + GuiBinding.ID + "$"), "A hologram id such as town:sign."));
        hologramFields.add("offset", list(range("number", -16, 16), 3, 3, "[x, y, z] blocks from the NPC's feet; omit to sit above its head."));
        hologram.add("properties", hologramFields);
        fields.add("hologram", hologram);
        gui.add("properties", fields);
        return gui;
    }

    private static JsonObject hire() {
        JsonObject hire = described(closed(), "Hiring this NPC as a bodyguard: the price, the items and how long one hire lasts.");
        JsonObject fields = new JsonObject();
        fields.add("enabled", described(type("boolean"), "False turns hiring off and keeps the terms."));
        fields.add("price", described(range("number", 0, 1_000_000_000), "Economy money one hire costs."));
        fields.add("items", list(reference("item"), 0, HireTerms.MAXIMUM_ITEMS, "Items one hire costs, at most " + HireTerms.MAXIMUM_ITEMS + "."));
        fields.add("durationMinutes", described(range("integer", 1, HireTerms.MAXIMUM_MINUTES), "60 by default; a renewal adds the same again."));
        fields.add("permission", described(pattern("^[a-z0-9_.-]{1,128}$"), "A permission node the hirer must hold."));
        hire.add("properties", fields);
        return hire;
    }

    private static JsonObject reviewRequired() {
        JsonObject then = new JsonObject();
        then.add("required", strings("reviewAccepted"));
        then.add("properties", property("reviewAccepted", constant(true)).getAsJsonObject("properties"));
        return whenEnabled(then);
    }

    private static JsonObject whenEnabled(JsonObject then) {
        JsonObject rule = new JsonObject();
        JsonObject enabled = new JsonObject();
        enabled.add("properties", property("enabled", constant(true)).getAsJsonObject("properties"));
        enabled.add("required", strings("enabled"));
        rule.add("if", enabled);
        rule.add("then", then);
        return rule;
    }

    private static JsonObject navigation() {
        JsonObject navigation = described(type("object"), "Native waypoints, or a reusable route under routes/.");
        JsonObject properties = new JsonObject();
        properties.add("enabled", type("boolean"));
        properties.add("reviewAccepted", type("boolean"));
        properties.add("route", pattern(IDENTIFIER));
        JsonObject recording = pattern(IDENTIFIER);
        recording.addProperty("maxLength", 64);
        properties.add("recording", described(recording, "A recording under recordings/ to play instead of a route."));
        properties.add("playback", described(choices(List.of("once", "loop", "pingpong")), "Recording only: once, loop (the default) or pingpong."));
        routeFields(properties);
        navigation.add("properties", properties);
        JsonObject routed = new JsonObject();
        routed.add("if", required("route"));
        JsonObject exclusive = new JsonObject();
        List<String> routedExcludes = new ArrayList<>(WaypointSettings.ROUTE_FIELDS);
        routedExcludes.add("recording");
        exclusive.add("not", anyRequired(routedExcludes));
        routed.add("then", exclusive);
        JsonObject inline = new JsonObject();
        JsonArray inlineRules = new JsonArray();
        inlineRules.add(pointCount());
        inline.add("allOf", inlineRules);
        JsonObject played = new JsonObject();
        played.add("if", required("recording"));
        JsonObject recordingExclusive = new JsonObject();
        recordingExclusive.add("not", anyRequired(WaypointSettings.ROUTE_FIELDS));
        played.add("then", recordingExclusive);
        played.add("else", inline);
        routed.add("else", played);
        JsonArray rules = new JsonArray();
        rules.add(whenEnabled(routed));
        JsonObject playbackRule = new JsonObject();
        playbackRule.add("if", required("playback"));
        playbackRule.add("then", required("recording"));
        rules.add(whenEnabled(playbackRule));
        orderRules(rules);
        navigation.add("allOf", rules);
        return navigation;
    }

    /** A reusable route under routes/, read by {@code WaypointSettings.document}. Unknown fields are kept. */
    public static JsonObject routeSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("$schema", BehaviorSchemaGenerator.DIALECT);
        root.addProperty("title", "Animus reusable route");
        root.addProperty("description", "routes/<id>.json: points NPCs follow when their animus:navigation names this route. Unknown fields are kept as written.");
        root.addProperty("type", "object");
        root.add("required", strings("schemaVersion"));
        JsonObject properties = new JsonObject();
        properties.add("schemaVersion", constant(1));
        routeFields(properties);
        root.add("properties", properties);
        JsonArray rules = new JsonArray();
        rules.add(pointCount());
        orderRules(rules);
        JsonObject noReference = new JsonObject();
        noReference.add("not", required("route"));
        rules.add(noReference);
        root.add("allOf", rules);
        return root;
    }

    private static void routeFields(JsonObject properties) {
        properties.add("mode", described(choices(List.of("walk", "swim", "fly")), "walk by default. Walking points stand on whole-block heights."));
        properties.add("speed", described(range("number", 1, 5), "Blocks per second. 2 by default."));
        properties.add("order", described(choices(List.of("sequence", "pingpong", "random", "wander")), "How the next point is chosen. sequence by default."));
        properties.add("loop", described(type("boolean"), "Order sequence only: start again from the first point."));
        properties.add("waitTicks", described(waitTicks(), "Wait before the first leg and after each arrival: ticks, or [min, max] drawn per arrival."));
        properties.add("seed", described(choices(List.of("npc", "random")), "npc (default): choices stable per NPC and route, across restarts. random: mixed with the clock."));
        properties.add("startOffset", described(choices(List.of("first", "random")), "random: start at a random point after a random 0..waitTicks wait. first by default."));
        properties.add("jitter", described(range("integer", 0, WaypointSettings.MAXIMUM_JITTER), "Leg goals move by a random whole-block x/z offset within this radius."));
        JsonObject point = coordinate();
        JsonObject pointFields = point.getAsJsonObject("properties");
        pointFields.add("waitTicks", described(waitTicks(), "Wait after arriving here, instead of the route's."));
        pointFields.add("jitter", described(range("integer", 0, WaypointSettings.MAXIMUM_JITTER), "Jitter radius of this point, instead of the route's."));
        pointFields.add("weight", described(range("integer", 1, WaypointSettings.MAXIMUM_WEIGHT), "Order random only: how often this point is chosen."));
        JsonObject junction = closed();
        junction.add("required", strings("to"));
        JsonObject way = new JsonObject();
        way.add("to", range("integer", 0, WaypointSettings.MAXIMUM_POINTS - 1));
        way.add("weight", range("integer", 1, WaypointSettings.MAXIMUM_WEIGHT));
        junction.add("properties", way);
        pointFields.add("next", list(junction, 1, WaypointSettings.MAXIMUM_JUNCTIONS, "Order sequence only: after this point, the next point is drawn from these by weight."));
        properties.add("points", list(point, 0, WaypointSettings.MAXIMUM_POINTS, "Route points in the NPC's world; wander centres for order wander."));
        properties.add("guides", list(coordinate(), 0, WaypointSettings.MAXIMUM_GUIDES, "Order random only: helper nodes a path may pass through."));
        properties.add("linkDistance", described(range("number", WaypointSettings.MINIMUM_LINK_DISTANCE, WaypointSettings.MAXIMUM_LINK_DISTANCE), "Order random only: the longest link between two nodes."));
        JsonObject wander = described(closed(), "Order wander only.");
        JsonObject area = new JsonObject();
        area.add("radius", described(range("integer", 1, WaypointSettings.Wander.MAXIMUM_RADIUS), "Blocks from a centre."));
        area.add("height", described(range("integer", 0, WaypointSettings.Wander.MAXIMUM_HEIGHT), "Blocks up or down from a centre."));
        wander.add("properties", area);
        properties.add("wander", wander);
    }

    /** Wander takes at most its centres and may have none; every other order needs at least one point. */
    private static JsonObject pointCount() {
        JsonObject wandering = new JsonObject();
        wandering.add("if", orderIs("wander"));
        JsonObject centres = new JsonObject();
        JsonObject fewer = new JsonObject();
        fewer.addProperty("maxItems", WaypointSettings.MAXIMUM_WANDER_CENTRES);
        centres.add("properties", property("points", fewer).getAsJsonObject("properties"));
        wandering.add("then", centres);
        JsonObject listed = required("points");
        JsonObject some = new JsonObject();
        some.addProperty("minItems", 1);
        listed.add("properties", property("points", some).getAsJsonObject("properties"));
        wandering.add("else", listed);
        return wandering;
    }

    private static void orderRules(JsonArray rules) {
        rules.add(onlyWithOrder("loop", "sequence", true));
        rules.add(onlyWithOrder("guides", "random", false));
        rules.add(onlyWithOrder("linkDistance", "random", false));
        rules.add(onlyWithOrder("wander", "wander", false));
        rules.add(pointOnlyWithOrder("weight", "random", false));
        rules.add(pointOnlyWithOrder("next", "sequence", true));
        JsonObject noJitter = new JsonObject();
        noJitter.add("if", orderIs("wander"));
        JsonObject refused = new JsonObject();
        refused.add("not", anyRequired(List.of("jitter")));
        noJitter.add("then", refused);
        rules.add(noJitter);
    }

    private static JsonObject waitTicks() {
        JsonObject between = list(range("integer", 0, WaypointSettings.MAXIMUM_WAIT), 2, 2, "[min, max] in ticks, min not above max.");
        JsonArray either = new JsonArray();
        either.add(range("integer", 0, WaypointSettings.MAXIMUM_WAIT));
        either.add(between);
        JsonObject wait = new JsonObject();
        wait.add("oneOf", either);
        return wait;
    }

    /** A point field that only one order reads; the default order counts when it is the default. */
    private static JsonObject pointOnlyWithOrder(String field, String order, boolean isDefault) {
        JsonObject rule = new JsonObject();
        JsonObject other = new JsonObject();
        if (isDefault) {
            other.add("required", strings("order"));
            JsonObject same = new JsonObject();
            same.add("properties", property("order", constant(order)).getAsJsonObject("properties"));
            other.add("not", same);
        } else other.add("not", orderIs(order));
        rule.add("if", other);
        JsonObject without = new JsonObject();
        without.add("not", anyRequired(List.of(field)));
        JsonObject items = new JsonObject();
        items.add("items", without);
        rule.add("then", property("points", items));
        return rule;
    }

    private static JsonObject coordinate() {
        JsonObject point = type("object");
        point.add("required", strings("x", "y", "z"));
        JsonObject axes = new JsonObject();
        for (String axis : List.of("x", "y", "z")) axes.add(axis, range("number", -29_999_984, 29_999_984));
        point.add("properties", axes);
        return point;
    }

    private static JsonObject anyRequired(List<String> keys) {
        JsonObject conflicting = new JsonObject();
        JsonArray any = new JsonArray();
        for (String key : keys) any.add(required(key));
        conflicting.add("anyOf", any);
        return conflicting;
    }

    private static JsonObject orderIs(String order) {
        JsonObject condition = new JsonObject();
        condition.add("properties", property("order", constant(order)).getAsJsonObject("properties"));
        condition.add("required", strings("order"));
        return condition;
    }

    /** The field may appear only with the given order; the default order counts when it is the default. */
    private static JsonObject onlyWithOrder(String field, String order, boolean isDefault) {
        JsonObject rule = new JsonObject();
        rule.add("if", required(field));
        JsonObject then = new JsonObject();
        then.add("properties", property("order", constant(order)).getAsJsonObject("properties"));
        if (!isDefault) then.add("required", strings("order"));
        rule.add("then", then);
        return rule;
    }

    private static JsonObject lookClose() {
        JsonObject look = described(closed(), "Turns the NPC toward the nearest player in range while it is spawned.");
        JsonObject properties = new JsonObject();
        properties.add("enabled", described(type("boolean"), "True when the object is present."));
        properties.add("range", described(range("number", LookClose.MINIMUM_RANGE, LookClose.MAXIMUM_RANGE), "Blocks. 10 by default."));
        properties.add("lineOfSight", described(type("boolean"), "Only look at players it can see."));
        properties.add("whileNavigating", described(type("boolean"), "Also look while following a route."));
        properties.add("npcs", described(type("boolean"), "Also look at the nearest other NPC when no player is in range."));
        JsonObject random = described(closed(), "With nobody to look at, turn to a random facing every delayTicks.");
        JsonObject randomProperties = new JsonObject();
        randomProperties.add("enabled", described(type("boolean"), "True by default."));
        randomProperties.add("delayTicks", described(range("integer", 1, LookClose.MAXIMUM_RANDOM_DELAY), "Ticks between random turns. 60 by default."));
        randomProperties.add("yaw", described(list(range("number", -360, 360), 2, 2, ""), "[minimum, maximum] yaw in degrees. [0, 360] by default."));
        randomProperties.add("pitch", described(list(range("number", -90, 90), 2, 2, ""), "[minimum, maximum] pitch in degrees. [0, 0] by default."));
        random.add("properties", randomProperties);
        properties.add("randomLook", random);
        look.add("properties", properties);
        return look;
    }

    private static JsonObject mount() {
        JsonObject mount = described(closed(), "Rides another NPC.");
        JsonObject properties = new JsonObject();
        properties.add("enabled", type("boolean"));
        properties.add("vehicle", described(pattern(IDENTIFIER), "The vehicle NPC's id."));
        mount.add("properties", properties);
        JsonObject disabled = new JsonObject();
        disabled.add("properties", property("enabled", constant(false)).getAsJsonObject("properties"));
        disabled.add("required", strings("enabled"));
        JsonObject rule = new JsonObject();
        rule.add("if", disabled);
        rule.add("else", required("vehicle"));
        JsonArray rules = new JsonArray();
        rules.add(rule);
        mount.add("allOf", rules);
        return mount;
    }

    private static JsonObject riding() {
        JsonObject riding = described(closed(), "Lets a real player ride this NPC.");
        JsonObject properties = new JsonObject();
        properties.add("enabled", type("boolean"));
        properties.add("reviewAccepted", type("boolean"));
        properties.add("permission", type("string"));
        properties.add("speed", type("number"));
        properties.add("leash", type("number"));
        properties.add("lifetimeSeconds", type("number"));
        riding.add("properties", properties);
        JsonObject limits = new JsonObject();
        JsonObject bounded = new JsonObject();
        JsonObject permission = pattern("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$");
        permission.addProperty("maxLength", 128);
        bounded.add("permission", permission);
        bounded.add("speed", range("number", RideSettings.MINIMUM_SPEED, RideSettings.MAXIMUM_SPEED));
        bounded.add("leash", range("number", RideSettings.MINIMUM_LEASH, RideSettings.MAXIMUM_LEASH));
        bounded.add("lifetimeSeconds", range("integer", RideSettings.MINIMUM_LIFETIME_SECONDS, RideSettings.MAXIMUM_LIFETIME_SECONDS));
        limits.add("properties", bounded);
        JsonArray rules = new JsonArray();
        rules.add(whenEnabled(limits));
        riding.add("allOf", rules);
        return riding;
    }

    /** {@code modules["animus:combat"]}, with the bounds {@code CombatSettings} reads. */
    static final class CombatSchema {
        enum Level { SEEKING, IGNORING, NESTED }
        private static final int RULE_ENTRIES = 256, NESTED_RULES = 16, TEXT = 256;
        private CombatSchema() {}

        static JsonObject module() {
            JsonObject combat = described(closed(), "Fighting, guarding, health and death. An unknown setting is refused so a typo cannot weaken a rule.");
            JsonObject fields = new JsonObject();
            fields.add("enabled", described(new JsonObject(), "Literal true turns combat on; anything else leaves it off."));
            fields.add("reviewAccepted", described(new JsonObject(), "Imported NPCs fight only once this is literal true."));
            fields.add("targets", described(reference("seekingRules"), "Who the NPC attacks."));
            fields.add("ignores", described(reference("ignoringRules"), "Who the NPC never attacks, even when a target rule matches."));
            fields.add("avoids", described(reference("seekingRules"), "Who the NPC keeps away from."));
            fields.add("targeting", section(
                    "scanRange", described(range("number", 1, 256), "Blocks. 16 by default."),
                    "scanIntervalTicks", described(range("integer", 1, 200), "10 by default."),
                    "reactionTicks", described(range("integer", 0, 200), "0 by default."),
                    "memoryTicks", described(range("integer", 0, 1200), "60 by default."),
                    "retainTarget", type("boolean"),
                    "fightBack", described(type("boolean"), "True by default."),
                    "lineOfSight", described(choices(List.of("required", "ignored", "field_of_view")), "required by default.")));
            fields.add("avoidance", section(
                    "range", described(range("number", 1, 64), "Blocks. 10 by default."),
                    "fleeWhenAttacked", type("boolean"),
                    "returnPoint", reference("location")));
            fields.add("squad", section(
                    "name", described(text(TEXT), "NPCs of one squad alert each other."),
                    "alertRange", described(range("number", 1, 64), "Blocks. 16 by default.")));
            JsonObject guard = section(
                    "player", described(pattern(UUID), "Guard this player."),
                    "npc", described(pattern(UUID), "Guard this NPC, by its uuid."),
                    "followDistance", described(range("number", 1, 64), "7 by default."),
                    "pointRange", described(range("number", 0, 16), "4 by default."),
                    "assist", described(type("boolean"), "Attack whatever the guarded player or NPC attacks."),
                    "catchUpDistance", described(range("number", 0, 256), "Teleport near the charge beyond this; 0 never teleports. 24 by default."),
                    "replanDistance", described(range("number", 0.25, 16), "1.5 by default."));
            guard.add("not", requiredAll("player", "npc"));
            fields.add("guard", guard);
            JsonObject region = pattern("^[A-Za-z0-9_,'+/-]{1,64}$");
            fields.add("pursuit", section(
                    "closeChase", described(type("boolean"), "True by default."),
                    "rangedChase", type("boolean"),
                    "speed", described(range("number", 1, 10), "4 by default."),
                    "giveUpRange", described(range("number", 1, 256), "32 by default."),
                    "leashRange", described(range("number", 1, 256), "64 by default."),
                    "returnToPost", described(type("boolean"), "True by default."),
                    "teleportWhenStuck", type("boolean"),
                    "replanTicks", described(range("integer", 5, 200), "20 by default."),
                    "region", described(region, "A protection region the fight stays inside.")));
            JsonObject weaponDamage = described(type("object"), "Damage by held item id, at most 64.");
            weaponDamage.addProperty("maxProperties", 64);
            weaponDamage.add("propertyNames", pattern(NAMESPACED));
            weaponDamage.add("additionalProperties", range("number", 0, 100));
            fields.add("melee", section(
                    "enabled", described(type("boolean"), "True by default."),
                    "damage", described(range("number", 0.01, 100), "2 by default."),
                    "reach", described(range("number", 0.5, 6), "3 by default."),
                    "cooldownTicks", described(range("integer", 1, 1200), "20 by default."),
                    "knockback", described(range("number", 0, 4), "0.4 by default."),
                    "fromEquipment", type("boolean"),
                    "weaponDamage", weaponDamage));
            JsonObject effect = section(
                    "id", pattern(NAMESPACED),
                    "amplifier", described(range("integer", 0, 9), "0 by default."),
                    "durationTicks", described(range("integer", 1, 72000), "200 by default."));
            effect.add("required", strings("id"));
            JsonObject ranged = section(
                    "enabled", type("boolean"),
                    "weapon", described(choices(Arrays.stream(RangedWeapon.values()).map(RangedWeapon::jsonName).toList()), "arrow by default."),
                    "damage", described(range("number", 0.01, 100), "3 by default."),
                    "range", described(range("number", 1, 256), "24 by default."),
                    "minimumRange", range("number", 0, 256),
                    "cooldownTicks", described(range("integer", 1, 1200), "40 by default."),
                    "velocity", described(range("number", 0.5, 4), "1.6 by default."),
                    "inaccuracy", range("number", 0, 16),
                    "safeShot", described(type("boolean"), "True by default."),
                    "knockback", described(range("number", 0, 4), "0.2 by default."),
                    "effect", described(effect, "Potion only: the effect it applies."),
                    "fromEquipment", type("boolean"),
                    "useAmmo", type("boolean"));
            JsonObject potion = new JsonObject();
            JsonObject isPotion = property("weapon", constant("potion"));
            isPotion.add("required", strings("weapon"));
            potion.add("if", isPotion);
            potion.add("then", required("effect"));
            JsonObject noEffect = new JsonObject();
            noEffect.add("not", required("effect"));
            potion.add("else", noEffect);
            JsonArray rangedRules = new JsonArray();
            rangedRules.add(potion);
            ranged.add("allOf", rangedRules);
            fields.add("ranged", ranged);
            fields.add("defense", section(
                    "maximumHealth", described(range("number", 1, 2048), "20 by default."),
                    "armor", described(range("number", 0, 1), "Share of damage absorbed."),
                    "invincible", type("boolean"),
                    "knockback", described(type("boolean"), "True by default."),
                    "protectedFromIgnored", type("boolean"),
                    "protectFromRange", range("number", 0, 256),
                    "healRateTicks", described(range("integer", 0, 72000), "0 never heals."),
                    "invulnerableTicks", described(range("integer", 0, 100), "10 by default."),
                    "armorFromEquipment", type("boolean")));
            JsonObject drop = section(
                    "item", pattern(NAMESPACED),
                    "amount", described(range("integer", 1, 64), "1 by default."),
                    "chance", described(range("number", 0, 1), "1 by default."));
            drop.add("required", strings("item"));
            fields.add("death", section(
                    "drops", list(drop, 0, 32, "At most 32."),
                    "experience", range("integer", 0, 10000),
                    "victimDrops", described(type("boolean"), "True by default.")));
            JsonObject respawn = section(
                    "delayTicks", described(range("integer", -1, 72000), "-1 never respawns. 100 by default."),
                    "locations", list(reference("location"), 0, 16, "At most 16."),
                    "deleteOnDeath", described(type("boolean"), "Delete the definition on death; needs delayTicks -1."));
            JsonObject deletes = new JsonObject();
            JsonObject deleting = property("deleteOnDeath", constant(true));
            deleting.add("required", strings("deleteOnDeath"));
            deletes.add("if", deleting);
            JsonObject never = property("delayTicks", constant(-1));
            never.add("required", strings("delayTicks"));
            deletes.add("then", never);
            JsonArray respawnRules = new JsonArray();
            respawnRules.add(deletes);
            respawn.add("allOf", respawnRules);
            fields.add("respawn", respawn);
            fields.add("greetings", section(
                    "friendly", described(text(TEXT), "Said to players it would not target."),
                    "hostile", described(text(TEXT), "Said to players it would target."),
                    "range", described(range("number", 1, 64), "10 by default."),
                    "cooldownTicks", described(range("integer", 0, 72000), "100 by default.")));
            fields.add("targetableByMobs", described(type("boolean"), "Nearby hostile mobs may attack the NPC."));
            for (String kept : List.of("preservedSettings", "historicalStatistics", "migrationReport")) fields.add(kept, described(new JsonObject(), "Kept from an import."));
            combat.add("properties", fields);
            return combat;
        }

        static JsonObject location() {
            JsonObject location = section(
                    "world", text(TEXT),
                    "x", range("number", -30_000_000, 30_000_000),
                    "y", range("number", -30_000_000, 30_000_000),
                    "z", range("number", -30_000_000, 30_000_000));
            location.add("required", strings("world", "x", "y", "z"));
            return location;
        }

        static JsonObject rules(Level level) {
            JsonObject rules = closed();
            JsonObject fields = new JsonObject();
            JsonObject categories = names(choices(Arrays.stream(TargetCategory.values()).map(TargetCategory::jsonName).toList()));
            fields.add("categories", described(categories, "players, npcs, mobs, monsters or animals."));
            fields.add("entityTypes", names(type("string")));
            fields.add("players", described(names(type("string")), "Player names."));
            fields.add("npcs", described(names(type("string")), "NPC ids."));
            fields.add("names", described(names(type("string")), "Display names."));
            fields.add("squads", names(type("string")));
            fields.add("owner", described(type("boolean"), "The NPC's owner."));
            fields.add("patterns", section("players", names(type("string")), "npcs", names(type("string")), "names", names(type("string"))));
            fields.add("items", section("mainHand", names(type("string")), "offHand", names(type("string")), "worn", names(type("string"))));
            fields.add("healthAbove", described(range("number", 0, 100), "Percent of maximum health."));
            fields.add("healthBelow", described(range("number", 0, 100), "Percent of maximum health."));
            fields.add("permissions", names(pattern("^[A-Za-z0-9_.*-]{1,128}$")));
            fields.add("groups", names(pattern("^[A-Za-z0-9_.-]{1,64}$")));
            fields.add("effects", names(pattern(NAMESPACED)));
            if (level != Level.NESTED) fields.add("all", list(reference("nestedRules"), 0, NESTED_RULES, "Rule sets that must all match one entity."));
            if (level == Level.SEEKING) {
                fields.add("together", list(list(reference("nestedRules"), 2, 8, ""), 0, NESTED_RULES, "Groups of 2 to 8 rule sets matched by entities near each other."));
                JsonObject kinds = names(choices(Arrays.stream(FightKind.values()).map(FightKind::jsonName).toList()));
                fields.add("events", section("kinds", kinds, "playerAttacks", reference("nestedRules"), "anyAttacks", reference("nestedRules")));
                JsonObject line = text(128);
                fields.add("chat", described(names(line), "Words in chat that make the speaker a target."));
            }
            rules.add("properties", fields);
            if (level == Level.NESTED) rules.addProperty("minProperties", 1);
            return rules;
        }

        private static JsonObject names(JsonObject items) {
            JsonObject array = type("array");
            array.addProperty("maxItems", RULE_ENTRIES);
            array.add("items", items);
            return array;
        }

        /** A closed object from alternating names and schemas. */
        private static JsonObject section(Object... pairs) {
            JsonObject section = closed();
            JsonObject fields = new JsonObject();
            for (int i = 0; i < pairs.length; i += 2) fields.add((String) pairs[i], (JsonObject) pairs[i + 1]);
            section.add("properties", fields);
            return section;
        }
    }

    private static JsonObject closed() { JsonObject schema = type("object"); schema.addProperty("additionalProperties", false); return schema; }
    private static JsonObject type(String type) { JsonObject schema = new JsonObject(); schema.addProperty("type", type); return schema; }
    private static JsonObject pattern(String pattern) { JsonObject schema = type("string"); schema.addProperty("pattern", pattern); return schema; }
    private static JsonObject text(int maximum) { JsonObject schema = pattern(NONBLANK); schema.addProperty("maxLength", maximum); return schema; }
    private static JsonObject required(String key) { JsonObject schema = new JsonObject(); schema.add("required", strings(key)); return schema; }
    private static JsonObject requiredAll(String... keys) { JsonObject schema = new JsonObject(); schema.add("required", strings(keys)); return schema; }
    private static JsonObject constant(Object value) {
        JsonObject schema = new JsonObject();
        switch (value) {
            case Integer number -> schema.addProperty("const", number);
            case Boolean flag -> schema.addProperty("const", flag);
            default -> schema.addProperty("const", value.toString());
        }
        return schema;
    }
    private static JsonObject choices(List<String> values) { JsonObject schema = new JsonObject(); schema.add("enum", strings(values.toArray(String[]::new))); return schema; }
    private static JsonObject range(String type, Number minimum, Number maximum) {
        JsonObject schema = type(type);
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }
    private static JsonObject list(JsonObject items, int minimum, int maximum, String description) {
        JsonObject schema = type("array");
        if (!description.isEmpty()) schema.addProperty("description", description);
        schema.addProperty("minItems", minimum);
        schema.addProperty("maxItems", maximum);
        schema.add("items", items);
        return schema;
    }
    private static JsonObject reference(String definition) { JsonObject schema = new JsonObject(); schema.addProperty("$ref", "#/$defs/" + definition); return schema; }
    private static JsonObject described(JsonObject schema, String description) { schema.addProperty("description", description); return schema; }
    private static JsonObject property(String name, JsonObject schema) {
        JsonObject wrapper = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.add(name, schema);
        wrapper.add("properties", properties);
        return wrapper;
    }
    private static JsonArray strings(String... values) { JsonArray array = new JsonArray(); for (String value : values) array.add(value); return array; }
}
