package art.arcane.animus.core.command;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.schedule.ScheduleSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** {@code /animus schedule}: the places an NPC goes at set times of the Minecraft day. */
@Director(name = "schedule", description = "Give NPCs places to be at set times of day")
@AnimusPermission(PermissionNodes.NPC_EDIT)
final class ScheduleCommands extends CommandGroup {
    /** Times {@code /time set} also names. */
    static final Map<String, Integer> TIMES = Map.of("day", 1000, "noon", 6000, "sunset", 12000, "night", 13000, "midnight", 18000, "sunrise", 23000);

    ScheduleCommands(CommandServices services) { super(services); }

    @Director(description = "List an NPC's schedule locations by time")
    void list(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> {
            Optional<NpcDefinition> definition = runtime().npcs().find(id);
            JsonArray locations = definition.map((NpcDefinition found) -> locations(found.json())).orElse(new JsonArray());
            if (locations.isEmpty()) { actor.tell(Palette.error(id + " has no schedule; /animus schedule add " + id + " <time> adds a location where you stand")); return; }
            List<Integer> order = new ArrayList<>();
            for (int index = 0; index < locations.size(); index++) order.add(index);
            order.sort(Comparator.comparingInt((Integer index) -> integer(locations.get(index).getAsJsonObject(), "time")));
            List<String> rows = new ArrayList<>();
            for (int index : order) rows.add(row(index, locations.get(index).getAsJsonObject()));
            listing(actor, "Schedule of " + id + (enabled(definition.get().json()) ? "" : " (off)"), "/animus schedule list " + id, rows, 1, "No locations");
        });
    }

    @Director(description = "Add a location where you stand, or at x y z, from a world time such as 1000 or night")
    void add(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
             @Param(name = "time", description = "World time 0-23999, or day, noon, sunset, night, midnight, sunrise") String time,
             @Param(name = "x", defaultValue = "", description = "Block X: a number, ~ or ~N; yours when left out") String x,
             @Param(name = "y", defaultValue = "", description = "Block Y: a number, ~ or ~N; yours when left out") String y,
             @Param(name = "z", defaultValue = "", description = "Block Z: a number, ~ or ~N; yours when left out") String z,
             @Param(name = "name", defaultValue = "", description = "A name for the location") String name,
             @Param(name = "chance", defaultValue = "100", description = "Draw weight among locations with the same time, 1-100") int chance,
             @Param(name = "stay", defaultValue = "", description = "Seconds before drawing again among the same time: 60 or 60-120") String stay,
             @Param(name = "wander", defaultValue = "0", description = "Wander radius in blocks, 0-32") int wander,
             @Param(name = "pose", defaultValue = "", description = "Pose on arrival: standing, sneaking, sleeping or sitting") String pose) {
        CommandActor actor = actor();
        int tick;
        Vec3 point;
        try {
            tick = time(time);
            point = Coordinates.point(x, y, z, Coordinates.origin(actor));
        } catch (IllegalArgumentException unreadable) { actor.tell(Palette.error(unreadable.getMessage())); return; }
        Optional<String> world = actor.position().map(WorldPosition::world);
        Optional<Double> yaw = actor.position().isPresent() && x.isEmpty() ? actor.facing().map((Coordinates.Facing facing) -> (double) facing.yaw()) : Optional.empty();
        JsonObject location = new JsonObject();
        if (!name.isEmpty()) location.addProperty("name", name);
        location.addProperty("time", tick);
        location.addProperty("x", (int) Math.floor(point.x()));
        location.addProperty("y", (int) Math.floor(point.y()));
        location.addProperty("z", (int) Math.floor(point.z()));
        yaw.ifPresent((Double facing) -> location.addProperty("yaw", Math.round(facing * 10) / 10.0));
        if (chance != 100) location.addProperty("chance", chance);
        if (!stay.isEmpty()) {
            try { location.add("staySeconds", seconds(stay)); }
            catch (IllegalArgumentException unreadable) { actor.tell(Palette.error(unreadable.getMessage())); return; }
        }
        if (wander > 0) { JsonObject around = new JsonObject(); around.addProperty("radius", wander); location.add("wander", around); }
        if (!pose.isEmpty()) location.addProperty("pose", pose.toLowerCase(Locale.ROOT));
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> change(id, (JsonObject document) -> {
            String npcWorld = document.getAsJsonObject("position").get("world").getAsString();
            if (world.isPresent() && !world.get().equals(npcWorld))
                throw new IllegalArgumentException(id + " lives in " + npcWorld + "; schedule locations lie in the NPC's own world");
            JsonObject module = module(document);
            JsonArray locations = module.has("locations") && module.get("locations").isJsonArray() ? module.getAsJsonArray("locations") : new JsonArray();
            locations.add(location);
            module.add("locations", locations);
            return "Added " + label(locations.size() - 1, location) + " to " + id + "'s schedule at " + location.get("x") + " " + location.get("y") + " " + location.get("z");
        })));
    }

    @Director(description = "Remove a location by its name or number")
    void remove(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "location", description = "The location's name, or its number from list") String location) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> change(id, (JsonObject document) -> {
            JsonArray locations = locations(document);
            int index = find(locations, location);
            if (index < 0) throw new IllegalArgumentException(id + "'s schedule has no location " + location + "; /animus schedule list " + id + " shows them");
            String label = label(index, locations.get(index).getAsJsonObject());
            locations.remove(index);
            if (locations.isEmpty()) document.getAsJsonObject("modules").remove(ScheduleSettings.MODULE);
            return "Removed " + label + " from " + id + "'s schedule" + (locations.isEmpty() ? "; it has no schedule now" : "");
        })));
    }

    @Director(description = "Turn an NPC's schedule on")
    void on(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { turn(npc, true); }

    @Director(description = "Turn an NPC's schedule off; its locations stay")
    void off(@Param(name = "npc", description = "The NPC id, or @selected") String npc) { turn(npc, false); }

    private void turn(String npc, boolean on) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> change(id, (JsonObject document) -> {
            if (locations(document).isEmpty()) throw new IllegalArgumentException(id + " has no schedule; /animus schedule add " + id + " <time> starts one");
            JsonObject module = module(document);
            module.addProperty("enabled", on);
            return id + "'s schedule is " + (on ? "on" : "off");
        })));
    }

    /** Edits the stored definition, checks it and saves it as a new revision. */
    private List<String> change(String id, Function<JsonObject, String> edit) throws IOException {
        NpcRepository.Draft draft = runtime().npcs().draft(id);
        JsonObject document = draft.document();
        String line;
        try { line = edit.apply(document); }
        catch (IllegalArgumentException refused) { return List.of(Palette.error(refused.getMessage())); }
        NpcDefinition.parse(JsonDocuments.write(document));
        String tx = runtime().npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        living().refresh();
        return List.of(Palette.success(line + "; tx " + tx));
    }

    /** World time in ticks from a number or a {@link #TIMES} word. */
    static int time(String text) {
        Integer named = TIMES.get(text.toLowerCase(Locale.ROOT));
        if (named != null) return named;
        try {
            int tick = Integer.parseInt(text);
            if (tick >= 0 && tick < ScheduleSettings.DAY_TICKS) return tick;
        } catch (NumberFormatException unreadable) { /* answered below */ }
        throw new IllegalArgumentException("Give the time as 0-23999, or day, noon, sunset, night, midnight or sunrise");
    }

    /** {@code 60} or {@code 60-120} seconds. */
    static JsonElement seconds(String text) {
        String[] parts = text.split("-", 2);
        try {
            int min = Integer.parseInt(parts[0].strip()), max = parts.length == 2 ? Integer.parseInt(parts[1].strip()) : min;
            if (min < 1 || max < min || max > ScheduleSettings.MAXIMUM_SECONDS) throw new NumberFormatException();
            if (min == max) return new JsonPrimitive(min);
            JsonArray range = new JsonArray();
            range.add(min); range.add(max);
            return range;
        } catch (NumberFormatException unreadable) {
            throw new IllegalArgumentException("Give the stay as seconds, such as 60, or a range such as 60-120");
        }
    }

    /** What {@code remove} takes for each location of a definition: its name, else its number. */
    static List<String> references(JsonObject document) {
        JsonArray locations = locations(document);
        List<String> references = new ArrayList<>();
        for (int index = 0; index < locations.size(); index++) {
            JsonElement name = locations.get(index).isJsonObject() ? locations.get(index).getAsJsonObject().get("name") : null;
            references.add(name != null && name.isJsonPrimitive() ? name.getAsString() : Integer.toString(index));
        }
        return references;
    }

    private static JsonObject module(JsonObject document) {
        if (!document.has("modules") || !document.get("modules").isJsonObject()) document.add("modules", new JsonObject());
        JsonObject modules = document.getAsJsonObject("modules");
        if (!modules.has(ScheduleSettings.MODULE) || !modules.get(ScheduleSettings.MODULE).isJsonObject()) modules.add(ScheduleSettings.MODULE, new JsonObject());
        return modules.getAsJsonObject(ScheduleSettings.MODULE);
    }

    private static JsonArray locations(JsonObject document) {
        JsonElement modules = document.get("modules");
        JsonElement module = modules != null && modules.isJsonObject() ? modules.getAsJsonObject().get(ScheduleSettings.MODULE) : null;
        JsonElement locations = module != null && module.isJsonObject() ? module.getAsJsonObject().get("locations") : null;
        return locations != null && locations.isJsonArray() ? locations.getAsJsonArray() : new JsonArray();
    }

    private static boolean enabled(JsonObject document) {
        JsonElement module = document.getAsJsonObject("modules").get(ScheduleSettings.MODULE);
        JsonElement enabled = module.getAsJsonObject().get("enabled");
        return enabled == null || (enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && enabled.getAsBoolean());
    }

    private static int find(JsonArray locations, String reference) {
        for (int index = 0; index < locations.size(); index++) {
            JsonElement name = locations.get(index).isJsonObject() ? locations.get(index).getAsJsonObject().get("name") : null;
            if (name != null && name.isJsonPrimitive() && name.getAsString().equals(reference)) return index;
        }
        String number = reference.startsWith("#") ? reference.substring(1) : reference;
        try { int index = Integer.parseInt(number); return index >= 0 && index < locations.size() ? index : -1; }
        catch (NumberFormatException notANumber) { return -1; }
    }

    private static String label(int index, JsonObject location) {
        return (location.has("name") ? location.get("name").getAsString() : "#" + index) + " at " + location.get("time").getAsInt();
    }

    private static String row(int index, JsonObject location) {
        List<String> parts = new ArrayList<>();
        parts.add("time " + location.get("time") + " at " + location.get("x") + " " + location.get("y") + " " + location.get("z"));
        if (location.has("chance")) parts.add("chance " + location.get("chance"));
        if (location.has("weather")) parts.add(location.get("weather").getAsString() + " weather");
        if (location.has("staySeconds")) parts.add("stays " + location.get("staySeconds") + "s");
        if (location.has("wander")) parts.add("wanders");
        if (location.has("pose")) parts.add(location.get("pose").getAsString());
        if (location.has("commands")) parts.add(location.getAsJsonArray("commands").size() + " commands");
        return "#" + index + (location.has("name") ? " " + location.get("name").getAsString() : "") + " | " + String.join(", ", parts);
    }

    private static int integer(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0; }
}
