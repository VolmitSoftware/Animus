package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Turns Citizens waypoints into an {@code animus:navigation} route, look-close into {@code animus:look-close}, and
 * text and commands into generated behaviors under {@code behaviors/citizens/}. The original traits stay in the
 * review copy; every option that has no Animus equivalent is reported with its source path.
 */
final class CitizensBehaviors {
    /** Blocks per second a Citizens NPC walks at speed modifier 1. */
    static final double CITIZENS_WALK_SPEED = 4.3;
    static final int LEG_HORIZONTAL = 32, LEG_VERTICAL = 8;
    static final int MAX_POINTS = 256, MAX_CENTRES = 64, MAX_GUIDES = 1024, MAX_WAIT = 12_000, MAX_COOLDOWN = 72_000;
    static final int MAX_CHOICES = 125, MAX_TEXT = 1024, MAX_PROXIMITY = 32, MAX_LOOK_RANGE = 64;
    static final String NAMESPACE = "citizens";
    /** Citizens' default pathfinding range, used when a guided route has no distance of its own. */
    static final double DEFAULT_LINK_DISTANCE = 75;
    /** Ticks a wander route rests between moves when Citizens left its delay at -1. */
    static final int DEFAULT_WANDER_WAIT = 60;
    /** Lookclose booleans Citizens writes only when they differ from the server default, with Citizens' own defaults. */
    static final Map<String, Boolean> LOOK_DEFAULTS = new LinkedHashMap<>(Map.of("enabled", false));
    static {
        LOOK_DEFAULTS.put("realisticlooking", false);
        LOOK_DEFAULTS.put("disablewhilenavigating", true);
    }
    /** Citizens' rotation defaults: 40 degrees of yaw and 10 of pitch a tick. */
    static final double ROTATION_YAW = 40, ROTATION_PITCH = 10;
    /** Citizens' default random look delay in ticks. */
    static final int DEFAULT_RANDOM_LOOK_DELAY = 60;

    record Document(String path, String content) {}
    record Result(Optional<JsonObject> navigation, Optional<JsonObject> lookClose, List<String> behaviors, List<Document> documents,
                  List<String> warnings, Set<String> converted, Map<String, CitizensImporter.Note> notes, int points, int guides) {
        Result {
            navigation = navigation.map(JsonObject::deepCopy); lookClose = lookClose.map(JsonObject::deepCopy);
            behaviors = List.copyOf(behaviors); documents = List.copyOf(documents); warnings = List.copyOf(warnings); converted = Set.copyOf(converted);
            notes = Map.copyOf(notes);
        }
    }

    private final String key, name, world;
    private final JsonObject traits, record, defaults;
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private final List<String> behaviors = new ArrayList<>();
    private final List<Document> documents = new ArrayList<>();
    private int points, guides;
    /** What each converted route point runs on arrival, by its index: chat and command waypoint triggers. */
    private final SortedMap<Integer, JsonObject> arrivals = new TreeMap<>();
    private boolean waypointCommands;

    private CitizensBehaviors(String key, String name, String world, JsonObject traits, JsonObject record, JsonObject defaults) {
        this.key = key; this.name = name; this.world = world; this.traits = traits; this.record = record; this.defaults = defaults;
    }

    /**
     * Maps one NPC. {@code traits} is the review copy with world mappings applied, {@code world} the NPC's world after
     * mapping and {@code defaults} the lookclose defaults from {@link #defaults}.
     */
    static Result map(String key, String name, String world, JsonObject traits, JsonObject record, JsonObject defaults) {
        return map(key, name, world, traits, record, defaults, CitizensCommands.Addons.NONE);
    }

    /** {@code addons} are the CitizensCMD and CommandNPC commands saved for this NPC; they join its command behavior. */
    static Result map(String key, String name, String world, JsonObject traits, JsonObject record, JsonObject defaults, CitizensCommands.Addons addons) {
        CitizensBehaviors mapping = new CitizensBehaviors(key, name, world, traits, record, defaults);
        Optional<JsonObject> navigation = Optional.ofNullable(mapping.navigation());
        Optional<JsonObject> look = Optional.ofNullable(mapping.lookClose());
        mapping.text();
        mapping.commands(addons);
        List<String> sorted = new ArrayList<>(mapping.warnings);
        Collections.sort(sorted);
        return new Result(navigation, look, mapping.behaviors, mapping.documents, sorted, mapping.converted, mapping.notes, mapping.points, mapping.guides);
    }

    /**
     * The value an unset lookclose boolean had on the server that wrote the save. Citizens writes a field only when it
     * differs from the server default, so when every written value is the same, an unset field means the other value.
     * Otherwise the Citizens default applies. Returns {@code {"values": {...}, "inferred": [...]}}.
     */
    static JsonObject defaults(JsonObject npcs) {
        Map<String, Set<Boolean>> written = new LinkedHashMap<>();
        LOOK_DEFAULTS.keySet().forEach((String field) -> written.put(field, new TreeSet<>()));
        for (Map.Entry<String, JsonElement> npc : npcs.entrySet()) {
            JsonObject record = object(npc.getValue());
            JsonObject look = record == null ? null : object(object(record.get("traits")) == null ? null : record.getAsJsonObject("traits").get("lookclose"));
            if (look == null) continue;
            for (String field : LOOK_DEFAULTS.keySet()) {
                JsonElement value = look.get(field);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) written.get(field).add(value.getAsBoolean());
            }
        }
        JsonObject values = new JsonObject();
        JsonArray inferred = new JsonArray();
        for (Map.Entry<String, Boolean> field : LOOK_DEFAULTS.entrySet()) {
            Set<Boolean> seen = written.get(field.getKey());
            if (seen.size() == 1) {
                values.addProperty(field.getKey(), !seen.iterator().next());
                inferred.add(field.getKey());
            } else values.addProperty(field.getKey(), field.getValue());
        }
        JsonObject result = new JsonObject();
        result.add("lookclose", values);
        result.add("inferred", inferred);
        return result;
    }

    // ---- Waypoints ----

    private JsonObject navigation() {
        JsonObject waypoints = object(traits.get("waypoints"));
        if (waypoints == null) return null;
        String base = "/traits/waypoints";
        String provider = string(waypoints.get("provider"), "linear");
        if (waypoints.has("provider")) converted.add(base + "/provider");
        JsonObject data = object(waypoints.get(provider));
        if (data == null) data = new JsonObject();
        String at = base + "/" + pointer(provider);
        for (String other : waypoints.keySet())
            if (!other.equals("provider") && !other.equals(provider))
                notes.put(base + "/" + pointer(other), new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED,
                        "settings of the " + other + " waypoint provider, which this NPC does not use"));
        if (data.isEmpty()) converted.add(at);
        String order;
        JsonArray route = new JsonArray(), helpers = new JsonArray();
        int dropped = 0;
        List<String> unsupported = new ArrayList<>();
        JsonObject wander = null;
        int waitTicks = 0;
        Double link = null;
        switch (provider) {
            case "linear" -> {
                order = bool(data.get("cycle"), false) ? "pingpong" : "sequence";
                if (data.has("cycle")) converted.add(at + "/cycle");
                if (!bool(data.get("pathfind"), true)) warnings.add(at + "/pathfind: straight-line movement is not supported; the NPC plans paths");
                dropped += points(data.get("points"), at + "/points", MAX_POINTS, route, true, true, unsupported);
            }
            case "guided" -> {
                order = "random";
                String destinations = data.has("availablewaypoints") ? "availablewaypoints" : "destinations";
                String helper = data.has("helperwaypoints") ? "helperwaypoints" : "guides";
                dropped += points(data.get(destinations), at + "/" + destinations, MAX_POINTS, route, true, true, unsupported);
                dropped += points(data.get(helper), at + "/" + helper, MAX_GUIDES, helpers, true, false, unsupported);
                JsonElement distance = data.get("distance");
                JsonObject navigator = object(record.get("navigator"));
                JsonElement range = navigator == null ? null : navigator.get("pathfindingrange");
                if (positive(distance)) { link = distance.getAsDouble(); converted.add(at + "/distance"); }
                else if (positive(range)) { link = range.getAsDouble(); converted.add("/navigator/pathfindingrange"); }
                else link = DEFAULT_LINK_DISTANCE;
                if (link > 256) { warnings.add(at + "/distance: guide links longer than 256 blocks became 256"); link = 256.0; }
                if (link < 1) link = 1.0;
            }
            case "wander" -> {
                order = "wander";
                dropped += points(data.get("regionCentres"), at + "/regionCentres", MAX_CENTRES, route, false, false, unsupported);
                JsonElement xrange = data.get("xrange"), yrange = data.get("yrange"), delay = data.get("delay");
                long radius = number(xrange) ? clamp(round(xrange.getAsDouble()), 1, LEG_HORIZONTAL) : 25;
                long height = number(yrange) ? clamp(round(yrange.getAsDouble()), 0, LEG_VERTICAL) : 3;
                if (number(xrange)) { converted.add(at + "/xrange"); if (xrange.getAsDouble() > LEG_HORIZONTAL) warnings.add(at + "/xrange: wander ranges above 32 blocks became 32"); }
                if (number(yrange)) { converted.add(at + "/yrange"); if (yrange.getAsDouble() > LEG_VERTICAL) warnings.add(at + "/yrange: wander heights above 8 blocks became 8"); }
                if (number(delay)) converted.add(at + "/delay");
                waitTicks = number(delay) && delay.getAsDouble() >= 0 ? (int) clamp((long) Math.floor(delay.getAsDouble()), 0, MAX_WAIT) : DEFAULT_WANDER_WAIT;
                if (!bool(data.get("pathfind"), true)) warnings.add(at + "/pathfind: straight-line movement is not supported; the NPC plans paths");
                if (data.has("worldguardRegion") && !data.get("worldguardRegion").isJsonNull()) warnings.add(at + "/worldguardRegion: WorldGuard wander regions are not supported; the NPC wanders around its centres");
                wander = new JsonObject();
                wander.addProperty("radius", radius);
                wander.addProperty("height", height);
            }
            case DestinationsMapping.PROVIDER -> {
                // NPC Destinations walks the NPC itself; its trait converts to animus:schedule.
                return null;
            }
            default -> {
                warnings.add(base + "/provider: waypoint provider " + provider + " is not supported; kept in citizens:review");
                return null;
            }
        }
        for (String trigger : new TreeSet<>(unsupported)) warnings.add(at + ": " + trigger);
        if (dropped > 0) warnings.add(at + ": " + dropped + " points in another world or without usable coordinates were left out");
        if (world.isEmpty()) {
            if (!route.isEmpty()) warnings.add(base + ": the NPC has no world, so its route was not converted");
            return null;
        }
        if (route.isEmpty() && !order.equals("wander")) return null;
        points = route.size(); guides = helpers.size();
        arrivals();
        JsonObject module = new JsonObject();
        module.addProperty("enabled", false);
        module.addProperty("reviewAccepted", false);
        module.addProperty("mode", "walk");
        module.addProperty("speed", speed());
        module.addProperty("order", order);
        if (order.equals("sequence")) module.addProperty("loop", route.size() > 1);
        module.addProperty("waitTicks", waitTicks);
        module.add("points", route);
        if (order.equals("random")) {
            module.add("guides", helpers);
            module.addProperty("linkDistance", link);
        } else if (order.equals("wander")) module.add("wander", wander);
        return module;
    }

    /** Appends converted points; returns how many were left out. Delay triggers become the point's waitTicks. */
    private int points(JsonElement list, String path, int limit, JsonArray out, boolean waypoint, boolean triggers, List<String> unsupported) {
        int dropped = 0;
        for (Map.Entry<String, JsonElement> entry : entries(list)) {
            String at = path + "/" + entry.getKey();
            JsonObject item = object(entry.getValue());
            JsonObject location = waypoint ? object(item == null ? null : item.get("location")) : item;
            String locationPath = location == item ? at : at + "/location";
            if (location == null || !number(location.get("x")) || !number(location.get("y")) || !number(location.get("z"))
                    || !world.equals(string(location.get("world"), ""))) { dropped++; continue; }
            double x = location.get("x").getAsDouble(), y = location.get("y").getAsDouble(), z = location.get("z").getAsDouble();
            if (Math.abs(x) > 29_999_984 || Math.abs(y) > 29_999_984 || Math.abs(z) > 29_999_984) { dropped++; continue; }
            if (out.size() == limit) { warnings.add(path + ": only the first " + limit + " points were converted"); break; }
            JsonObject point = new JsonObject();
            point.addProperty("x", Math.floor(x) + 0.5);
            point.addProperty("y", (long) Math.floor(y));
            point.addProperty("z", Math.floor(z) + 0.5);
            for (String axis : List.of("world", "x", "y", "z")) converted.add(locationPath + "/" + axis);
            // Citizens walks to the block centre and never turns to a waypoint's facing; the world UUID only names the world.
            for (String unused : List.of("yaw", "pitch", "worldid")) if (location.has(unused)) converted.add(locationPath + "/" + unused);
            if (!triggers && item != null && item.has("triggers"))
                notes.put(at + "/triggers", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "Citizens never runs triggers on guide waypoints"));
            if (triggers && item != null && item.has("triggers")) {
                long delay = 0;
                List<JsonObject> steps = new ArrayList<>();
                for (Map.Entry<String, JsonElement> trigger : entries(item.get("triggers"))) {
                    JsonObject data = object(trigger.getValue());
                    String type = data == null ? "" : string(data.get("type"), "");
                    String triggerPath = at + "/triggers/" + trigger.getKey();
                    if (type.equals("delay") && (number(data.get("delay")) || !data.has("delay"))) {
                        // Citizens leaves out a delay of 0, its default.
                        if (data.has("delay")) delay = Math.min(MAX_WAIT + 1, delay + clamp((long) Math.floor(data.get("delay").getAsDouble()), 0, MAX_WAIT + 1));
                        converted.add(triggerPath + "/type"); converted.add(triggerPath + "/delay");
                    } else if (type.equals("speed")) {
                        // Citizens sets the navigator's speed for every later leg; a SpeedTrigger saves 1 when left at its default.
                        point.addProperty("speed", speed(positive(data.get("speed")) ? data.get("speed").getAsDouble() : 1));
                        converted.add(triggerPath + "/type"); converted.add(triggerPath + "/speed");
                    } else if (type.equals("teleport")) teleportTrigger(data, triggerPath, point);
                    else if (type.equals("chat")) chatTrigger(data, triggerPath, steps);
                    else if (type.equals("command")) commandTrigger(data, triggerPath, steps);
                    else if (type.equals("animation")) animationTrigger(data, triggerPath, point, steps);
                    else unsupported.add((type.isEmpty() ? "unknown" : type) + " waypoint triggers are not supported; kept in citizens:review");
                }
                if (delay > MAX_WAIT) warnings.add(at + "/triggers: delays above 12000 ticks became 12000");
                if (delay > 0) point.addProperty("waitTicks", clamp(delay, 0, MAX_WAIT));
                if (!steps.isEmpty()) arrivals.put(out.size(), sequence(steps));
            }
            out.add(point);
        }
        return dropped;
    }

    /**
     * A teleport trigger moves the NPC on arrival to the trigger's exact position and facing; one in another world names
     * that world, and the route goes on there.
     */
    private void teleportTrigger(JsonObject data, String path, JsonObject point) {
        if (teleportTo(object(data.get("location")), path + "/location", point)) converted.add(path + "/type");
    }

    /** Writes the point's teleport from a Citizens location; false, with a warning, when it has no usable position. */
    private boolean teleportTo(JsonObject location, String path, JsonObject point) {
        if (location == null || !number(location.get("x")) || !number(location.get("y")) || !number(location.get("z"))) {
            warnings.add(path + ": a teleport without a location was left out"); return false;
        }
        String target = string(location.get("world"), "");
        if (target.isEmpty()) { warnings.add(path + ": a teleport without a world was left out"); return false; }
        JsonObject teleport = new JsonObject();
        for (String axis : List.of("x", "y", "z")) {
            double value = location.get(axis).getAsDouble();
            if (Math.abs(value) > 29_999_984) { warnings.add(path + ": the teleport lies outside the world and was left out"); return false; }
            teleport.addProperty(axis, value);
        }
        if (number(location.get("yaw"))) teleport.addProperty("yaw", degrees(location.get("yaw").getAsDouble() % 360));
        if (number(location.get("pitch"))) teleport.addProperty("pitch", degrees(Math.max(-90, Math.min(90, location.get("pitch").getAsDouble()))));
        if (!target.equals(world)) teleport.addProperty("world", target);
        point.add("teleport", teleport);
        for (String field : List.of("world", "x", "y", "z", "yaw", "pitch", "worldid")) if (location.has(field)) converted.add(path + "/" + field);
        return true;
    }

    private static double degrees(double value) { return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue(); }

    /** Chat trigger lines go to players within its radius, or to the NPC's world when Citizens' radius is unset. */
    private void chatTrigger(JsonObject data, String path, List<JsonObject> steps) {
        double radius = number(data.get("radius")) ? data.get("radius").getAsDouble() : -1;
        if (radius > MAX_LOOK_RANGE) { warnings.add(path + "/radius: chat radii above 64 blocks became 64"); radius = MAX_LOOK_RANGE; }
        int before = steps.size();
        for (Map.Entry<String, JsonElement> line : entries(data.get("lines"))) {
            if (!line.getValue().isJsonPrimitive()) continue;
            String text = line.getValue().getAsString();
            if (text.contains("<p>") || text.contains("<player>")) { warnings.add(path + "/lines/" + line.getKey() + ": a waypoint has no player to name; left out"); continue; }
            text = placeholders(text, path + "/lines/" + line.getKey());
            if (text.isEmpty() || text.length() > MAX_TEXT) { warnings.add(path + "/lines/" + line.getKey() + ": empty or longer than 1024 characters; left out"); continue; }
            JsonObject arguments = arguments("text", text);
            arguments.addProperty("to", radius > 0 ? "nearby" : "world");
            if (radius > 0) arguments.addProperty("radius", Math.max(1, radius));
            steps.add(action("animus:message", arguments));
            converted.add(path + "/lines/" + line.getKey());
        }
        if (steps.size() > before) { converted.add(path + "/type"); if (data.has("radius")) converted.add(path + "/radius"); }
    }

    /** Citizens' player animations as {@code animus:animate}; elytra flight has no Animus animation. */
    private static final Map<String, String> ANIMATIONS = Map.ofEntries(Map.entry("ARM_SWING", "swing"), Map.entry("ARM_SWING_OFFHAND", "swing_offhand"),
            Map.entry("HURT", "hurt"), Map.entry("CRIT", "crit"), Map.entry("MAGIC_CRIT", "magic_crit"), Map.entry("LEAVE_BED", "wake"), Map.entry("STOP_SLEEPING", "wake"),
            Map.entry("SLEEP", "sleep"), Map.entry("SIT", "sit"), Map.entry("STOP_SITTING", "stand"), Map.entry("SNEAK", "sneak"), Map.entry("STOP_SNEAKING", "stand"),
            Map.entry("EAT_FOOD", "use_item"), Map.entry("START_USE_MAINHAND_ITEM", "use_item"), Map.entry("START_USE_OFFHAND_ITEM", "use_item"),
            Map.entry("STOP_USE_ITEM", "stop_using_item"));

    /**
     * Animation trigger animations run in order on arrival. Citizens first moves the NPC to the trigger's {@code at}, such
     * as a bed to sleep in; that becomes the point's teleport when the point has none.
     */
    private void animationTrigger(JsonObject data, String path, JsonObject point, List<JsonObject> steps) {
        int before = steps.size();
        for (Map.Entry<String, JsonElement> line : entries(data.get("animations"))) {
            String name = line.getValue().isJsonPrimitive() ? line.getValue().getAsString().toUpperCase(Locale.ROOT) : "";
            String animation = ANIMATIONS.get(name);
            if (animation == null) { warnings.add(path + "/animations/" + line.getKey() + ": the " + name + " animation has no Animus animation; left out"); continue; }
            steps.add(action("animus:animate", arguments("animation", animation)));
            converted.add(path + "/animations/" + line.getKey());
        }
        JsonObject at = object(data.get("at"));
        if (at != null && !point.has("teleport")) teleportTo(at, path + "/at", point);
        else if (at != null) warnings.add(path + "/at: the point already teleports; the animation's own position was left out");
        if (steps.size() > before) converted.add(path + "/type");
    }

    /** Command trigger commands run from the console, as Citizens runs them with no player. */
    private void commandTrigger(JsonObject data, String path, List<JsonObject> steps) {
        int before = steps.size();
        for (Map.Entry<String, JsonElement> line : entries(data.get("commands"))) {
            if (!line.getValue().isJsonPrimitive()) continue;
            String command = line.getValue().getAsString();
            if (command.startsWith("/")) command = command.substring(1);
            if (command.contains("<p>") || command.contains("<player>")) { warnings.add(path + "/commands/" + line.getKey() + ": a waypoint has no player to name; left out"); continue; }
            command = placeholders(command, path + "/commands/" + line.getKey());
            if (command.isEmpty() || command.length() > MAX_TEXT) { warnings.add(path + "/commands/" + line.getKey() + ": empty or longer than 1024 characters; left out"); continue; }
            JsonObject arguments = arguments("command", command);
            arguments.addProperty("as", "console");
            steps.add(action("animus:command", arguments));
            waypointCommands = true;
            converted.add(path + "/commands/" + line.getKey());
        }
        if (steps.size() > before) converted.add(path + "/type");
    }

    /** The behavior {@code citizens:waypoints-<id>}: one routine per point with chat or command triggers, run on arrival. */
    private void arrivals() {
        if (arrivals.isEmpty()) return;
        JsonObject routines = new JsonObject();
        JsonArray triggers = new JsonArray();
        for (Map.Entry<Integer, JsonObject> arrival : arrivals.entrySet()) {
            String routine = "point-" + arrival.getKey();
            routines.add(routine, arrival.getValue());
            JsonObject trigger = new JsonObject();
            trigger.addProperty("id", routine);
            trigger.addProperty("event", "waypoint");
            trigger.addProperty("waypoint", arrival.getKey());
            trigger.addProperty("routine", routine);
            triggers.add(trigger);
        }
        String id = "waypoints-" + key;
        if (waypointCommands) warnings.add("/traits/waypoints: waypoint commands stay denied until " + NAMESPACE + ":" + id + " is on the [behaviors.actions] commandBehaviors list in config.toml");
        behavior(id, routines.keySet().iterator().next(), routines, triggers);
    }

    private double speed() {
        JsonObject navigator = object(record.get("navigator"));
        JsonElement modifier = navigator == null ? null : navigator.get("speedmodifier");
        double factor = 1;
        if (positive(modifier)) { factor = modifier.getAsDouble(); converted.add("/navigator/speedmodifier"); }
        return speed(factor);
    }

    /** Blocks per second for a Citizens speed modifier, within Animus' 1..5. */
    static double speed(double factor) {
        double speed = Math.round(CITIZENS_WALK_SPEED * factor * 100) / 100.0;
        return Math.min(5.0, Math.max(1.0, speed));
    }

    // ---- Look-close ----

    private JsonObject lookClose() {
        JsonObject look = object(traits.get("lookclose"));
        if (look == null) return null;
        String base = "/traits/lookclose";
        JsonObject values = defaults.getAsJsonObject("lookclose");
        boolean enabled = flag(look, "enabled", values.get("enabled").getAsBoolean(), base);
        // A posed stand that turns to players breaks the scene it was posed for; only a save that turns it on explicitly does.
        if (enabled && armorStand() && !look.has("enabled")) {
            enabled = false;
            warnings.add(base + "/enabled: an armor stand keeps its own facing and pose, so the server's look-close default is not applied; "
                    + "set enabled in animus:look-close to turn it toward players");
        }
        boolean realistic = flag(look, "realisticlooking", values.get("realisticlooking").getAsBoolean(), base);
        boolean disabled = flag(look, "disablewhilenavigating", values.get("disablewhilenavigating").getAsBoolean(), base);
        double range = 10;
        if (positive(look.get("range"))) { range = look.get("range").getAsDouble(); converted.add(base + "/range"); }
        if (range > MAX_LOOK_RANGE) { warnings.add(base + "/range: look ranges above 64 blocks became 64; lower it in animus:look-close if that is too far"); range = MAX_LOOK_RANGE; }
        if (range < 1) range = 1;
        boolean npcs = flag(look, "targetnpcs", false, base);
        // A linked body turns with the head, as Animus turns every NPC; head-only looking leaves the body where it stands.
        boolean linked = flag(look, "linkedbody", false, base), headOnly = flag(look, "headonly", false, base) && !linked;
        boolean perPlayer = flag(look, "perplayer", false, base);
        JsonObject module = new JsonObject();
        module.addProperty("enabled", enabled);
        module.addProperty("range", range);
        module.addProperty("lineOfSight", realistic);
        module.addProperty("whileNavigating", !disabled);
        if (npcs) module.addProperty("npcs", true);
        if (headOnly) module.addProperty("headOnly", true);
        if (perPlayer) module.addProperty("perPlayer", true);
        JsonArray permissions = filter(look, base);
        if (permissions == null) { module.addProperty("enabled", false); warnings.add(base + "/filter: the filter admits no players, so look-close is off"); }
        else if (!permissions.isEmpty()) module.add("permissions", permissions);
        JsonObject turn = turnRate();
        if (turn != null) module.add("turnRate", turn);
        // Citizens switches to a random player in range every random look delay.
        if (flag(look, "randomSwitchTargets", false, base)) {
            String delayKey = look.has("randomlookdelay") ? "randomlookdelay" : "randomLookDelay";
            JsonElement delay = look.get(delayKey);
            module.addProperty("switchTicks", number(delay) ? clamp((long) Math.floor(delay.getAsDouble()), 1, MAX_WAIT) : DEFAULT_RANDOM_LOOK_DELAY);
            if (number(delay)) converted.add(base + "/" + delayKey);
        }
        JsonObject random = randomLook(look, base);
        if (random != null) module.add("randomLook", random);
        return module;
    }

    /**
     * Citizens' look-close filter, {@code type=...}, {@code permission=a,b} and {@code group=g} separated by spaces, as the
     * permissions a player must hold; a group becomes the {@code group.<name>} permission. Null when no player passes.
     */
    private JsonArray filter(JsonObject look, String base) {
        JsonArray permissions = new JsonArray();
        JsonElement value = look.get("filter");
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) return permissions;
        String path = base + "/filter";
        boolean groups = false, players = true;
        for (String argument : value.getAsString().trim().split(" +")) {
            String[] parts = argument.split("=", 2);
            String[] values = parts.length < 2 ? new String[0] : parts[1].split(",");
            switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "type" -> {
                    boolean player = false;
                    for (String type : values) player |= type.trim().equalsIgnoreCase("player") || type.trim().equalsIgnoreCase("minecraft:player");
                    players &= player;
                }
                case "permission", "perm" -> { for (String node : values) if (!node.isBlank() && permissions.size() < 8) permissions.add(node.trim()); }
                case "group" -> {
                    for (String group : values)
                        if (!group.isBlank() && permissions.size() < 8) { permissions.add("group." + group.trim().toLowerCase(Locale.ROOT)); groups = true; }
                }
                default -> { notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "filter part " + parts[0] + " is not known; the NPC looks at any player")); return permissions; }
            }
        }
        if (!players) { notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "the filter admits no players, so look-close is off")); return null; }
        notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, groups
                ? "groups became group.<name> permissions, which LuckPerms and most permission plugins grant to group members" : ""));
        return permissions;
    }

    /** Citizens' gradual rotation as the most a look turns each tick; null for its defaults or immediate turning. */
    private JsonObject turnRate() {
        JsonObject rotation = object(traits.get("rotationtrait"));
        JsonObject parameters = rotation == null ? null : object(rotation.get("globalParameters"));
        if (parameters == null || bool(parameters.get("immediate"), false)) return null;
        double yaw = number(parameters.get("maxYawPerTick")) ? Math.max(0, Math.min(360, parameters.get("maxYawPerTick").getAsDouble())) : ROTATION_YAW;
        double pitch = number(parameters.get("maxPitchPerTick")) ? Math.max(0, Math.min(180, parameters.get("maxPitchPerTick").getAsDouble())) : ROTATION_PITCH;
        if (bool(parameters.get("lockPitch"), false)) pitch = 0;
        if (yaw == ROTATION_YAW && pitch == ROTATION_PITCH) return null;
        JsonObject rate = new JsonObject();
        rate.addProperty("yaw", yaw);
        rate.addProperty("pitch", pitch);
        return rate;
    }

    /** Citizens' idle random looking, kept with its delay and ranges even while it is off. */
    private JsonObject randomLook(JsonObject look, String base) {
        boolean declared = false;
        for (String key : List.of("enableRandomLook", "randomlookdelay", "randomLookDelay", "randomYawRange", "randomPitchRange")) declared |= look.has(key);
        if (!declared) return null;
        JsonObject random = new JsonObject();
        random.addProperty("enabled", flag(look, "enableRandomLook", false, base));
        String delayKey = look.has("randomlookdelay") ? "randomlookdelay" : "randomLookDelay";
        JsonElement delay = look.get(delayKey);
        long ticks = DEFAULT_RANDOM_LOOK_DELAY;
        if (number(delay)) {
            ticks = clamp((long) Math.floor(delay.getAsDouble()), 1, MAX_WAIT);
            converted.add(base + "/" + delayKey);
        }
        random.addProperty("delayTicks", ticks);
        random.add("yaw", angles(look, "randomYawRange", -360, 360, 0, 360, base));
        random.add("pitch", angles(look, "randomPitchRange", -90, 90, 0, 0, base));
        return random;
    }

    private JsonArray angles(JsonObject look, String key, double minimum, double maximum, double low, double high, String base) {
        List<Map.Entry<String, JsonElement>> bounds = entries(look.get(key));
        if (bounds.size() == 2 && number(bounds.get(0).getValue()) && number(bounds.get(1).getValue())) {
            double a = bounds.get(0).getValue().getAsDouble(), b = bounds.get(1).getValue().getAsDouble();
            low = Math.max(minimum, Math.min(a, b));
            high = Math.min(maximum, Math.max(a, b));
            for (Map.Entry<String, JsonElement> bound : bounds) converted.add(base + "/" + key + "/" + bound.getKey());
        }
        JsonArray range = new JsonArray();
        range.add(low);
        range.add(high);
        return range;
    }

    private boolean flag(JsonObject object, String field, boolean fallback, String base) {
        JsonElement value = object.get(field);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) { converted.add(base + "/" + field); return value.getAsBoolean(); }
        return fallback;
    }

    /** The NPC is an armor stand: Citizens' {@code type} trait, with or without its namespace. */
    private boolean armorStand() {
        JsonElement type = traits.get("type");
        if (type == null || !type.isJsonPrimitive()) return false;
        String key = type.getAsString().toLowerCase(Locale.ROOT);
        return key.equals("armor_stand") || key.equals("minecraft:armor_stand");
    }

    // ---- Text ----

    private void text() {
        JsonObject text = object(traits.get("text"));
        if (text == null) return;
        String base = "/traits/text";
        List<List<String>> lines = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : entries(text.get("text"))) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            if (lines.size() == MAX_CHOICES) { warnings.add(base + "/text: only the first " + MAX_CHOICES + " lines were converted"); break; }
            List<String> parts = new ArrayList<>();
            for (String part : value.getAsString().split("<br>|\\n", -1)) {
                if (part.isEmpty()) continue;
                String spoken = placeholders(part, base + "/text/" + entry.getKey());
                if (spoken.length() > MAX_TEXT) { warnings.add(base + "/text/" + entry.getKey() + ": cut to 1024 characters"); spoken = spoken.substring(0, MAX_TEXT); }
                parts.add(spoken);
            }
            if (parts.isEmpty()) continue;
            converted.add(base + "/text/" + entry.getKey());
            lines.add(parts);
        }
        if (lines.isEmpty()) return;
        boolean close = bool(text.get("talk-close"), false), random = bool(text.get("random-talker"), false);
        for (String field : List.of("talk-close", "random-talker", "range", "delay")) if (text.has(field)) converted.add(base + "/" + field);
        double range = positive(text.get("range")) ? text.get("range").getAsDouble() : 5;
        if (range > MAX_PROXIMITY) { warnings.add(base + "/range: talk ranges above 32 blocks became 32"); range = MAX_PROXIMITY; }
        long cooldown = number(text.get("delay")) && text.get("delay").getAsDouble() > 0 ? clamp((long) Math.floor(text.get("delay").getAsDouble()), 0, MAX_COOLDOWN) : 100;
        String item = string(text.get("talkitem"), "default");
        Optional<String> held = close || item.equals("default") || item.contains("*") || item.isEmpty() ? Optional.empty() : heldItem(item);
        if (text.has("talkitem")) converted.add(base + "/talkitem");
        // Speech bubbles show each line over the NPC for Citizens' bubble duration, 50 ticks by default.
        boolean bubbles = bool(text.get("speech-bubbles"), false), chat = !bubbles || bool(text.get("send-text-to-chat"), true);
        long bubbleTicks = number(text.get("speech-bubble-duration")) && text.get("speech-bubble-duration").getAsDouble() > 0
                ? clamp((long) Math.floor(text.get("speech-bubble-duration").getAsDouble()), 1, 1200) : 50;
        for (String field : List.of("speech-bubbles", "send-text-to-chat", "speech-bubble-duration")) if (text.has(field)) converted.add(base + "/" + field);
        String quest = "text-" + key;
        List<JsonObject> choices = new ArrayList<>();
        for (List<String> parts : lines) {
            List<JsonObject> says = new ArrayList<>();
            for (String part : parts) {
                JsonObject spoken = arguments("text", part);
                if (bubbles) spoken.addProperty("bubbleTicks", bubbleTicks);
                if (!chat) spoken.addProperty("chat", false);
                says.add(action("animus:say", spoken));
            }
            choices.add(sequence(says));
        }
        JsonObject main = select(choices, quest, "npc", random);
        if (held.isPresent()) {
            JsonObject guard = new JsonObject();
            guard.addProperty("type", "condition");
            guard.addProperty("expression", held.get());
            guard.add("then", main);
            main = guard;
        }
        JsonObject trigger = new JsonObject();
        if (close) {
            trigger.addProperty("id", "near");
            trigger.addProperty("event", "proximity");
            trigger.addProperty("phase", "enter");
            trigger.addProperty("radius", range);
        } else {
            trigger.addProperty("id", "click");
            trigger.addProperty("event", "interaction");
            trigger.addProperty("action", "interact");
        }
        // Citizens spaces each player's lines on their own, so one player never holds back another.
        trigger.addProperty("playerCooldownTicks", cooldown);
        JsonObject routines = new JsonObject();
        routines.add("main", main);
        JsonArray triggers = new JsonArray();
        triggers.add(trigger);
        behavior("text-" + key, "main", routines, triggers);
    }

    /** Citizens' talk item, a comma list of material names, as a check of the clicking player's held item. */
    private static Optional<String> heldItem(String pattern) {
        List<String> checks = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String material = part.trim().toLowerCase(Locale.ROOT);
            if (material.startsWith("minecraft:")) material = material.substring("minecraft:".length());
            if (!material.matches("[a-z0-9_]+")) continue;
            checks.add("same(player.item_in_hand, 'minecraft:" + material + "')");
        }
        return checks.isEmpty() ? Optional.empty() : Optional.of(String.join(" || ", checks));
    }

    // ---- Commands ----

    private void commands(CitizensCommands.Addons addons) {
        CitizensCommands.Result result = CitizensCommands.map(key, name, traits, addons, warnings, converted);
        result.entry().ifPresent((String entry) -> behavior("commands-" + key, entry, result.routines(), result.triggers()));
    }

    // ---- Behavior documents ----

    /**
     * One of {@code choices} per run: chosen at random, or each in turn. The turn is a quest stage of the citizens
     * namespace, kept per NPC or per NPC and player.
     */
    static JsonObject select(List<JsonObject> choices, String quest, String scope, boolean random) {
        if (choices.size() == 1) return choices.getFirst();
        List<JsonObject> steps = new ArrayList<>();
        if (random) {
            JsonObject arguments = quest(quest, scope);
            arguments.addProperty("random", choices.size());
            steps.add(action("animus:set-quest-state", arguments));
        }
        for (int index = 0; index < choices.size(); index++) {
            JsonObject arguments = quest(quest, scope);
            arguments.addProperty("equals", index);
            steps.add(condition("animus:quest-state", arguments, choices.get(index)));
        }
        if (!random) {
            JsonObject advance = quest(quest, scope);
            advance.addProperty("maximum", choices.size());
            steps.add(action("animus:advance-quest-state", advance));
            JsonObject wrapped = quest(quest, scope);
            wrapped.addProperty("atLeast", choices.size());
            JsonObject reset = quest(quest, scope);
            reset.addProperty("value", 0);
            steps.add(condition("animus:quest-state", wrapped, action("animus:set-quest-state", reset)));
        }
        return sequence(steps);
    }

    private void behavior(String path, String entry, JsonObject routines, JsonArray triggers) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", 1);
        document.addProperty("id", NAMESPACE + ":" + path);
        document.addProperty("entry", entry);
        document.add("routines", routines);
        document.add("triggers", triggers);
        behaviors.add(NAMESPACE + ":" + path);
        documents.add(new Document("behaviors/" + NAMESPACE + "/" + path + ".json", JsonDocuments.write(document)));
    }

    static JsonObject quest(String name, String scope) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", name);
        arguments.addProperty("scope", scope);
        return arguments;
    }

    static JsonObject action(String capability, JsonObject arguments) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "action");
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        return node;
    }

    static JsonObject condition(String capability, JsonObject arguments, JsonObject then) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "condition");
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        node.add("then", then);
        return node;
    }

    static JsonObject sequence(List<JsonObject> steps) {
        if (steps.size() == 1) return steps.getFirst();
        JsonObject node = new JsonObject();
        node.addProperty("type", "sequence");
        JsonArray array = new JsonArray();
        steps.forEach(array::add);
        node.add("steps", array);
        return node;
    }

    static JsonObject arguments(String field, String value) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty(field, value);
        return arguments;
    }

    /** Citizens' clicking-player placeholders become {player_name}; the NPC's own name and id are written in. */
    private String placeholders(String text, String path) {
        String result = text.replace("<player>", "{player_name}").replace("<p>", "{player_name}").replace("<npc>", name).replace("<id>", key);
        if (result.matches("(?s).*(<[a-z_]+>|%[a-z_]+%).*")) warnings.add(path + ": placeholders other than <p>, <player>, <npc> and <id> are not filled in");
        return result;
    }

    // ---- JSON helpers ----

    /** List items of a YAML sequence, or of a mapping with integer keys in numeric order, with their pointer segments. */
    static List<Map.Entry<String, JsonElement>> entries(JsonElement value) {
        List<Map.Entry<String, JsonElement>> result = new ArrayList<>();
        if (value == null) return result;
        if (value.isJsonArray()) {
            for (int index = 0; index < value.getAsJsonArray().size(); index++) result.add(Map.entry(Integer.toString(index), value.getAsJsonArray().get(index)));
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet())
                if (entry.getKey().matches("0|[1-9][0-9]{0,8}")) result.add(Map.entry(entry.getKey(), entry.getValue()));
            result.sort(Comparator.comparingInt((Map.Entry<String, JsonElement> entry) -> Integer.parseInt(entry.getKey())));
        }
        return result;
    }

    static String pointer(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    static String string(JsonElement value, String fallback) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : fallback; }
    static boolean bool(JsonElement value, boolean fallback) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback; }
    static boolean number(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble());
    }
    static boolean positive(JsonElement value) { return number(value) && value.getAsDouble() > 0; }
    static long clamp(long value, long minimum, long maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static long round(double value) { return Math.round(value); }
}
