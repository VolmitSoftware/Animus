package art.arcane.animus.core.editor;

import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code summary} of a catalog entry: what a list shows before the document is read, taken from the stored file only.
 * A field is present only when the document has it; a malformed field is left out, never refused.
 */
final class EditorSummaries {
    static final int TEXT = 64, TAGS = 16, TRIGGERS = 8;
    private static final Pattern LEFTOVER_TAG = Pattern.compile("</?[a-zA-Z_#!][^<>]{0,96}>"), GLOSS_TOKEN = Pattern.compile("\\|[a-z0-9_.:-]{1,64}\\|"),
            SPACES = Pattern.compile("\\s+"), TEXTURE = Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-f]{1,128}");
    private static final Set<String> SHOP_MODES = Set.of("trade", "buy", "sell");
    private static final Map<String, Integer> ROWS = Map.of("9x1", 1, "9x2", 2, "9x3", 3, "9x4", 4, "9x5", 5, "9x6", 6, "5x1", 1, "3x3", 3);

    private EditorSummaries() {}

    static JsonObject of(DocumentKind kind, String id, JsonObject source) {
        JsonObject summary = new JsonObject();
        switch (kind) {
            case NPC -> npc(summary, id, source);
            case SKIN -> skin(summary, source);
            case SHOP -> {
                name(summary, source, "title");
                JsonArray pages = array(source, "pages");
                if (pages != null) {
                    int offers = 0;
                    for (JsonElement page : pages) {
                        if (!page.isJsonObject()) continue;
                        for (Map.Entry<String, JsonElement> mode : page.getAsJsonObject().entrySet())
                            if (SHOP_MODES.contains(mode.getKey()) && mode.getValue().isJsonObject()) offers += mode.getValue().getAsJsonObject().size();
                    }
                    summary.addProperty("offers", offers);
                    summary.addProperty("pages", pages.size());
                }
            }
            case INVENTORY -> {
                name(summary, source, "title");
                String resolution = string(source, "resolution");
                JsonArray mask = array(source, "mask");
                if (resolution != null && ROWS.containsKey(resolution)) summary.addProperty("rows", ROWS.get(resolution));
                else if (mask != null) summary.addProperty("rows", mask.size());
            }
            case HOLOGRAM -> hologram(summary, source);
            case TEXT_ANIMATION -> count(summary, "frames", source, "frames");
            case BEHAVIOR -> {
                JsonArray triggers = array(source, "triggers");
                if (triggers != null) {
                    Set<String> events = new LinkedHashSet<>();
                    for (JsonElement trigger : triggers) {
                        String event = trigger.isJsonObject() ? string(trigger.getAsJsonObject(), "event") : null;
                        if (event != null && events.size() < TRIGGERS) events.add(bounded(event));
                    }
                    JsonArray list = new JsonArray();
                    events.forEach(list::add);
                    summary.add("triggers", list);
                }
            }
            case SERVER_BEHAVIORS -> count(summary, "behaviors", source, "behaviors");
            case PACK -> {
                name(summary, source, "name");
                String version = string(source, "version");
                if (version != null && !version.isBlank()) summary.addProperty("version", bounded(version));
            }
            case ROUTE -> count(summary, "points", source, "points");
            case RECORDING -> {
                name(summary, source, "description");
                seconds(summary, source);
                String world = string(source, "world");
                if (world != null && !world.isBlank()) summary.addProperty("world", bounded(world));
            }
            case GROUP -> {
                JsonObject values = object(source, "values");
                if (values != null) summary.addProperty("fields", values.size());
            }
            case QUEST -> {
                name(summary, source, "title");
                count(summary, "stages", source, "stages");
            }
            case RIG -> count(summary, "bones", source, "bones");
            case ANIMATION -> seconds(summary, source);
            case MENU, PRESETS -> { /* Gloss menus and the preset file have no display name or cheap figure. */ }
        }
        return summary;
    }

    private static void npc(JsonObject summary, String id, JsonObject source) {
        name(summary, source, "name");
        String type = string(source, "type");
        if (type != null && !type.isBlank()) summary.addProperty("type", bounded(type));
        JsonObject position = object(source, "position");
        if (position != null) {
            String world = string(position, "world");
            if (world != null && !world.isBlank()) summary.addProperty("world", bounded(world));
            for (String axis : new String[]{"x", "y", "z"}) {
                JsonElement value = position.get(axis);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) summary.addProperty(axis, value.getAsDouble());
            }
        }
        JsonElement spawned = source.get("spawned");
        if (spawned != null && spawned.isJsonPrimitive() && spawned.getAsJsonPrimitive().isBoolean()) summary.addProperty("spawned", spawned.getAsBoolean());
        summary.addProperty("folder", id.substring(0, Math.max(0, id.lastIndexOf('/'))));
        JsonArray tags = array(source, "tags");
        if (tags != null) {
            JsonArray kept = new JsonArray();
            for (JsonElement tag : tags) if (kept.size() < TAGS && tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()) kept.add(bounded(tag.getAsString()));
            summary.add("tags", kept);
        }
        summary.add("features", features(source));
        ownRoute(summary, source);
        String skin = string(source, "skin");
        if (skin != null && skin.matches("[a-f0-9]{64}")) summary.addProperty("skin", skin);
    }

    /** {@code points}: how many points the NPC's own route has, when its navigation has points and names no route or recording. */
    private static void ownRoute(JsonObject summary, JsonObject source) {
        JsonObject modules = object(source, "modules");
        JsonObject navigation = modules == null ? null : object(modules, "animus:navigation");
        JsonArray points = navigation == null ? null : array(navigation, "points");
        if (points != null && !points.isEmpty() && string(navigation, "route") == null && string(navigation, "recording") == null)
            summary.addProperty("points", points.size());
    }

    /** route, recording, schedule, combat, shop, behavior, rig, hologram and mount, in that order, as far as the document has them. */
    private static JsonArray features(JsonObject source) {
        JsonObject modules = object(source, "modules");
        JsonObject navigation = modules == null ? null : object(modules, "animus:navigation"), gui = modules == null ? null : object(modules, "animus:gui");
        JsonObject combat = modules == null ? null : object(modules, "animus:combat"), mount = modules == null ? null : object(modules, "animus:mount");
        JsonObject open = gui == null ? null : object(gui, "open"), hologram = object(source, "hologram");
        JsonArray behaviors = array(source, "behaviors"), lines = hologram == null ? null : array(hologram, "lines"), points = navigation == null ? null : array(navigation, "points");
        JsonArray features = new JsonArray();
        if (navigation != null && (string(navigation, "route") != null || points != null && !points.isEmpty())) features.add("route");
        if (navigation != null && string(navigation, "recording") != null) features.add("recording");
        if (modules != null && object(modules, "animus:schedule") != null) features.add("schedule");
        if (combat != null && combat.get("enabled") != null && combat.get("enabled").isJsonPrimitive() && combat.getAsJsonPrimitive("enabled").isBoolean()
                && combat.get("enabled").getAsBoolean()) features.add("combat");
        if (modules != null && object(modules, "animus:shop") != null || open != null && "shop".equals(string(open, "kind"))) features.add("shop");
        if (behaviors != null && !behaviors.isEmpty()) features.add("behavior");
        if (object(source, "rig") != null) features.add("rig");
        if (lines != null && !lines.isEmpty() || gui != null && object(gui, "hologram") != null) features.add("hologram");
        if (mount != null && !(mount.get("enabled") != null && mount.get("enabled").isJsonPrimitive() && !mount.get("enabled").getAsBoolean())) features.add("mount");
        return features;
    }

    /** The texture URL and arm model in {@code textureRaw}, the base64 textures property; only Mojang's texture host passes. */
    private static void skin(JsonObject summary, JsonObject source) {
        String raw = string(source, "textureRaw");
        if (raw == null) return;
        JsonObject textures;
        try { textures = JsonParser.parseString(new String(Base64.getDecoder().decode(raw.strip()), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException unreadable) { return; }
        JsonObject all = object(textures, "textures"), skin = all == null ? null : object(all, "SKIN");
        String url = skin == null ? null : string(skin, "url");
        if (url == null || !TEXTURE.matcher(url).matches()) return;
        summary.addProperty("texture", url.startsWith("http://") ? "https://" + url.substring(7) : url);
        JsonObject metadata = object(skin, "metadata");
        summary.addProperty("slim", metadata != null && "slim".equals(string(metadata, "model")));
    }

    private static void hologram(JsonObject summary, JsonObject source) {
        JsonArray lines = array(source, "lines");
        if (lines == null) {
            JsonArray pages = array(source, "pages");
            JsonObject first = pages == null || pages.isEmpty() || !pages.get(0).isJsonObject() ? null : pages.get(0).getAsJsonObject();
            lines = first == null ? null : array(first, "lines");
        }
        if (lines != null) {
            for (JsonElement line : lines) {
                String text = line.isJsonPrimitive() && line.getAsJsonPrimitive().isString() ? line.getAsString() : line.isJsonObject() ? string(line.getAsJsonObject(), "text") : null;
                String plain = text == null ? "" : plain(text);
                if (!plain.isEmpty()) { summary.addProperty("name", plain); break; }
            }
            summary.addProperty("lines", lines.size());
        }
        JsonObject anchor = object(source, "anchor");
        JsonArray position = anchor == null ? null : array(anchor, "position");
        String world = anchor == null ? null : string(anchor, "world");
        if (world != null && !world.isBlank()) summary.addProperty("world", bounded(world));
        if (position != null && position.size() == 3) {
            String[] axes = {"x", "y", "z"};
            for (int index = 0; index < 3; index++) {
                JsonElement value = position.get(index);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) summary.addProperty(axes[index], value.getAsDouble());
            }
        }
    }

    private static void seconds(JsonObject summary, JsonObject source) {
        JsonElement ticks = source.get("durationTicks");
        if (ticks != null && ticks.isJsonPrimitive() && ticks.getAsJsonPrimitive().isNumber()) summary.addProperty("seconds", Math.max(0, ticks.getAsDouble()) / 20.0);
    }

    private static void count(JsonObject summary, String field, JsonObject source, String key) {
        JsonArray array = array(source, key);
        if (array != null) summary.addProperty(field, array.size());
    }

    private static void name(JsonObject summary, JsonObject source, String key) {
        String text = string(source, key);
        String plain = text == null ? "" : plain(text);
        if (!plain.isEmpty()) summary.addProperty("name", plain);
    }

    /** Colour codes, hex colours, MiniMessage tags and Gloss animation tokens removed, whitespace collapsed, at most {@link #TEXT} characters. */
    static String plain(String text) {
        String stripped = RichText.plain(GLOSS_TOKEN.matcher(text).replaceAll(""));
        return bounded(SPACES.matcher(LEFTOVER_TAG.matcher(stripped).replaceAll("")).replaceAll(" ").strip());
    }

    private static String bounded(String text) { return text.length() > TEXT ? text.substring(0, TEXT) : text; }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }
}
