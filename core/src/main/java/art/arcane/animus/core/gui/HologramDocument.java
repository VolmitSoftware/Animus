package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Condition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A Gloss hologram ({@code schemaVersion} 3) run by Animus: text rows and item, head, block and entity lines at an
 * anchor, optional pages and variants chosen per viewer, and actions on a click of its interaction box. Every
 * problem is reported with its JSON path and the document is refused as a whole.
 */
public record HologramDocument(String id, long revision, String world, double x, double y, double z, float yaw, float pitch,
                               List<Line> lines, List<Page> pages, List<Action> actions, Hitbox hitbox, HoloParts.Style style,
                               Condition show, double viewDistance, int contentTicks, int visibilityTicks, List<Variant> variants) {
    public static final int SCHEMA_VERSION = 3;
    /** Text rows are 10 pixels of a text display at scale 1. */
    public static final double ROW_HEIGHT = 0.25;
    public static final Set<String> ROOTS = Set.of("viewer", "player", "server", "time", "session", "npc", "page", "pages", GuiTemplate.ANIMATION);
    private static final Set<String> FIELDS = Set.of("preset", "schemaVersion", "revision", "anchor", "lines", "pages", "actions", "hitbox", "style", "box",
            "yaw", "pitch", "show", "particleLayers", "viewDistance", "refreshTicks", "variants", "refresh");
    private static final int MAXIMUM_LINES = 128, MAXIMUM_PAGES = 64, MAXIMUM_VARIANTS = 64, MAXIMUM_ACTIONS = 32;

    public HologramDocument {
        lines = List.copyOf(lines); pages = List.copyOf(pages); actions = List.copyOf(actions); variants = List.copyOf(variants);
    }

    public sealed interface Line permits TextLine, ItemLine, HeadLine, BlockLine, MobLine { Condition show(); }
    public record TextLine(GuiTemplate text, Condition show) implements Line {}
    public record ItemLine(GuiModel.Icon icon, double scale, Condition show) implements Line {}
    public record HeadLine(GuiTemplate player, double scale, Condition show) implements Line {}
    public record BlockLine(String block, double scale, Condition show) implements Line {}
    public record MobLine(String type, double scale, Condition show) implements Line {}
    public record Page(String id, List<Line> lines, Condition show) {
        public Page { lines = List.copyOf(lines); }
    }
    public record Variant(String id, int priority, GuiExpression when, Optional<List<Line>> lines, Optional<HoloParts.Style> style) {}
    public record Hitbox(double width, double height, boolean perLine) {
        public static final Hitbox DEFAULT = new Hitbox(1.2, 0.35, false);
    }

    /** Variants in the order Gloss tries them: highest priority first, then id. */
    public List<Variant> orderedVariants() {
        return variants.stream().sorted(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id)).toList();
    }

    public boolean hasCommands() { return GuiActions.commands(actions); }

    /** Every text animation the hologram's text shows. */
    public Set<String> animations() {
        Set<String> found = new HashSet<>();
        List<List<Line>> all = new ArrayList<>();
        all.add(lines);
        pages.forEach((Page page) -> all.add(page.lines()));
        variants.forEach((Variant variant) -> variant.lines().ifPresent(all::add));
        for (List<Line> group : all) for (Line line : group) if (line instanceof TextLine text) found.addAll(text.text().animations());
        return found;
    }

    public record Result(Optional<HologramDocument> document, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    public static Result parse(String id, String json) {
        JsonObject object;
        try { object = JsonDocuments.object(json); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
        return parse(id, object);
    }

    public static Result parse(String id, JsonObject object) {
        List<String> problems = new ArrayList<>();
        GuiParser parser = new GuiParser(problems, ROOTS, true);
        parser.unknownFields(object, FIELDS, "$");
        int schema = parser.integer(object, "schemaVersion", "$", Integer.MIN_VALUE, Integer.MAX_VALUE).orElse(SCHEMA_VERSION);
        if (object.has("schemaVersion") && schema != SCHEMA_VERSION)
            parser.problem("$.schemaVersion", "hologram schemaVersion " + schema + " is not supported; Animus reads version " + SCHEMA_VERSION);
        long revision = parser.integer(object, "revision", "$", 1, Integer.MAX_VALUE).orElse(1);
        if (object.has("preset") && !object.get("preset").isJsonNull()) parser.problem("$.preset", "Gloss presets are not available in Animus; inline the preset's fields");
        String world = "";
        double[] position = {0, 0, 0};
        JsonElement anchor = object.get("anchor");
        if (anchor == null || !anchor.isJsonObject()) parser.problem("$.anchor", "expected {world, position}");
        else {
            JsonObject at = anchor.getAsJsonObject();
            parser.unknownFields(at, Set.of("world", "position"), "$.anchor");
            world = parser.string(at, "world", "$.anchor").orElse("");
            if (world.isBlank() && at.has("world")) parser.problem("$.anchor.world", "expected a world name");
            position = HoloParts.vector(at.get("position"), "$.anchor.position", parser, 3.0E7).orElse(position);
        }
        float yaw = object.has("yaw") ? parser.decimal(object, "yaw", "$", -180, 180).orElse(0.0).floatValue() : 0;
        float pitch = object.has("pitch") ? parser.decimal(object, "pitch", "$", -90, 90).orElse(0.0).floatValue() : 0;
        if (object.has("lines") && object.has("pages")) parser.problem("$.pages", "declare lines or pages, not both");
        List<Line> lines = object.has("lines") ? lines(object.get("lines"), "$.lines", parser) : List.of();
        List<Page> pages = new ArrayList<>();
        if (object.has("pages")) {
            JsonElement value = object.get("pages");
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAXIMUM_PAGES)
                parser.problem("$.pages", "expected 1 to " + MAXIMUM_PAGES + " pages");
            else {
                Set<String> ids = new HashSet<>();
                for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                    String path = "$.pages[" + index + "]";
                    JsonElement element = value.getAsJsonArray().get(index);
                    if (!element.isJsonObject()) { parser.problem(path, "expected page object"); continue; }
                    JsonObject page = element.getAsJsonObject();
                    parser.unknownFields(page, Set.of("id", "lines", "show"), path);
                    String pageId = parser.string(page, "id", path).orElse("");
                    if (!pageId.isEmpty() && !ids.add(pageId)) parser.problem(path + ".id", "duplicate page id '" + pageId + "'");
                    List<Line> pageLines = lines(page.get("lines"), path + ".lines", parser);
                    if (pageLines.isEmpty()) parser.problem(path + ".lines", "a page needs at least one line");
                    pages.add(new Page(pageId, pageLines, parser.condition(page.get("show"), path + ".show", ROOTS)));
                }
            }
        }
        List<Action> actions = List.of();
        if (object.has("actions")) {
            JsonElement value = object.get("actions");
            if (value.isJsonArray() && value.getAsJsonArray().size() > MAXIMUM_ACTIONS) parser.problem("$.actions", "at most " + MAXIMUM_ACTIONS + " actions");
            actions = parser.actions(value, "$.actions", ROOTS, false);
            restrict(value, "$.actions", parser);
        }
        Hitbox hitbox = Hitbox.DEFAULT;
        if (object.has("hitbox")) {
            JsonElement value = object.get("hitbox");
            if (!value.isJsonObject()) parser.problem("$.hitbox", "expected object");
            else {
                JsonObject box = value.getAsJsonObject();
                parser.unknownFields(box, Set.of("width", "height", "perLine"), "$.hitbox");
                hitbox = new Hitbox(box.has("width") ? parser.decimal(box, "width", "$.hitbox", 0.01, 64).orElse(1.2) : 1.2,
                        box.has("height") ? parser.decimal(box, "height", "$.hitbox", 0.01, 64).orElse(0.35) : 0.35,
                        box.has("perLine") && parser.bool(box, "perLine", "$.hitbox"));
            }
        }
        HoloParts.Style style = HoloParts.style(object.get("style"), "$.style", HoloParts.Style.HOLOGRAM, parser);
        HoloParts.box(object.get("box"), "$.box", parser);
        HoloParts.particleLayers(object.get("particleLayers"), "$.particleLayers", parser);
        Condition show = parser.condition(object.get("show"), "$.show", ROOTS);
        double viewDistance = object.has("viewDistance") ? parser.decimal(object, "viewDistance", "$", 4, 128).orElse(48.0) : 48;
        int refreshTicks = object.has("refreshTicks") ? parser.integer(object, "refreshTicks", "$", 1, 200).orElse(10) : 10;
        int contentTicks = refreshTicks, visibilityTicks = refreshTicks;
        if (object.has("refresh")) {
            JsonElement value = object.get("refresh");
            if (!value.isJsonObject()) parser.problem("$.refresh", "expected object");
            else {
                JsonObject refresh = value.getAsJsonObject();
                parser.unknownFields(refresh, Set.of("contentTicks", "visibilityTicks", "motionTicks"), "$.refresh");
                if (refresh.has("contentTicks")) contentTicks = parser.integer(refresh, "contentTicks", "$.refresh", 1, 1200).orElse(refreshTicks);
                if (refresh.has("visibilityTicks")) visibilityTicks = parser.integer(refresh, "visibilityTicks", "$.refresh", 1, 1200).orElse(refreshTicks);
                if (refresh.has("motionTicks")) parser.integer(refresh, "motionTicks", "$.refresh", 1, 1200);
            }
        }
        List<Variant> variants = new ArrayList<>();
        if (object.has("variants")) {
            JsonElement value = object.get("variants");
            if (!value.isJsonArray() || value.getAsJsonArray().size() > MAXIMUM_VARIANTS) parser.problem("$.variants", "expected at most " + MAXIMUM_VARIANTS + " variants");
            else {
                Set<String> ids = new HashSet<>();
                for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                    String path = "$.variants[" + index + "]";
                    JsonElement element = value.getAsJsonArray().get(index);
                    if (!element.isJsonObject()) { parser.problem(path, "expected variant object"); continue; }
                    JsonObject variant = element.getAsJsonObject();
                    parser.unknownFields(variant, Set.of("id", "priority", "when", "presentation"), path);
                    String variantId = parser.string(variant, "id", path).orElse("");
                    if (!variantId.isEmpty() && !variantId.matches("[a-z0-9][a-z0-9._-]{0,63}")) parser.problem(path + ".id", "expected [a-z0-9][a-z0-9._-]{0,63}");
                    if (!variantId.isEmpty() && !ids.add(variantId)) parser.problem(path + ".id", "duplicate variant id '" + variantId + "'");
                    int priority = variant.has("priority") ? parser.integer(variant, "priority", path, -1000, 1000).orElse(0) : 0;
                    GuiExpression when = parser.expression(variant, "when", path, ROOTS).orElse(GuiExpression.parse("false", Set.of()));
                    Optional<List<Line>> variantLines = Optional.empty();
                    Optional<HoloParts.Style> variantStyle = Optional.empty();
                    JsonElement presentation = variant.get("presentation");
                    if (presentation == null || !presentation.isJsonObject()) parser.problem(path + ".presentation", "expected object");
                    else {
                        JsonObject shown = presentation.getAsJsonObject();
                        parser.unknownFields(shown, Set.of("lines", "style", "box", "particleLayers"), path + ".presentation");
                        if (shown.has("lines")) variantLines = Optional.of(lines(shown.get("lines"), path + ".presentation.lines", parser));
                        if (shown.has("style")) variantStyle = Optional.of(HoloParts.style(shown.get("style"), path + ".presentation.style", style, parser));
                        HoloParts.box(shown.get("box"), path + ".presentation.box", parser);
                        HoloParts.particleLayers(shown.get("particleLayers"), path + ".presentation.particleLayers", parser);
                    }
                    variants.add(new Variant(variantId, priority, when, variantLines, variantStyle));
                }
            }
        }
        if (!problems.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new HologramDocument(id, revision, world, position[0], position[1], position[2], yaw, pitch, lines, pages, actions,
                hitbox, style, show, viewDistance, contentTicks, visibilityTicks, variants)), List.of());
    }

    private static List<Line> lines(JsonElement value, String path, GuiParser parser) {
        if (value == null || !value.isJsonArray()) { parser.problem(path, "expected array of lines"); return List.of(); }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > MAXIMUM_LINES) { parser.problem(path, "at most " + MAXIMUM_LINES + " lines"); return List.of(); }
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String at = path + "[" + index + "]";
            if (element.isJsonNull()) { lines.add(new TextLine(GuiTemplate.literal(""), Condition.ALWAYS)); continue; }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                parser.parseTemplate(element.getAsString(), at, ROOTS).ifPresent((GuiTemplate text) -> lines.add(new TextLine(text, Condition.ALWAYS)));
                continue;
            }
            if (!element.isJsonObject()) { parser.problem(at, "expected a string or a line object"); continue; }
            JsonObject line = element.getAsJsonObject();
            parser.unknownFields(line, Set.of("text", "item", "head", "block", "entity", "show", "scale"), at);
            List<String> kinds = new ArrayList<>();
            for (String kind : List.of("text", "item", "head", "block", "entity")) if (line.has(kind)) kinds.add(kind);
            if (kinds.size() != 1) { parser.problem(at, "a line object has exactly one of text, item, head, block or entity"); continue; }
            Condition show = parser.condition(line.get("show"), at + ".show", ROOTS);
            double scale = line.has("scale") ? parser.decimal(line, "scale", at, 0.01, 64).orElse(1.0) : 1;
            switch (kinds.getFirst()) {
                case "text" -> parser.template(line, "text", at, ROOTS).ifPresent((GuiTemplate text) -> lines.add(new TextLine(text, show)));
                case "item" -> {
                    JsonElement icon = line.get("item");
                    if (icon.isJsonObject() && icon.getAsJsonObject().has("type") && !"item".equals(icon.getAsJsonObject().get("type").getAsString()))
                        parser.problem(at + ".item.type", "only item icons draw on hologram lines in Animus");
                    else {
                        JsonObject copy = icon.isJsonObject() ? icon.getAsJsonObject().deepCopy() : new JsonObject();
                        if (!copy.has("type")) copy.addProperty("type", "item");
                        lines.add(new ItemLine(parser.icon(icon.isJsonObject() ? copy : icon, at + ".item", ROOTS), scale, show));
                    }
                }
                case "head" -> parser.template(line, "head", at, ROOTS).ifPresent((GuiTemplate player) -> lines.add(new HeadLine(player, scale, show)));
                case "block" -> parser.string(line, "block", at).ifPresent((String block) -> {
                    if (!HoloParts.entityType(block)) parser.problem(at + ".block", "expected a block key such as minecraft:stone");
                    lines.add(new BlockLine(HoloParts.key(block), scale, show));
                });
                case "entity" -> parser.string(line, "entity", at).ifPresent((String type) -> {
                    if (!HoloParts.entityType(type)) parser.problem(at + ".entity", "expected an entity type such as minecraft:fox");
                    lines.add(new MobLine(HoloParts.key(type), scale, show));
                });
                default -> {}
            }
        }
        return List.copyOf(lines);
    }

    /** Holograms have pages but no window, history or named action lists. */
    private static void restrict(JsonElement actions, String path, GuiParser parser) {
        if (actions == null || !actions.isJsonArray()) return;
        for (int index = 0; index < actions.getAsJsonArray().size(); index++) {
            JsonElement element = actions.getAsJsonArray().get(index);
            String at = path + "[" + index + "]";
            if (!element.isJsonObject()) continue;
            JsonObject action = element.getAsJsonObject();
            String type = action.has("type") && action.get("type").isJsonPrimitive() ? action.get("type").getAsString() : "";
            switch (type) {
                case "navigate" -> {
                    String mode = action.has("mode") && action.get("mode").isJsonPrimitive() ? action.get("mode").getAsString() : "push";
                    if (!mode.equals("page")) parser.problem(at + ".mode", "holograms only move between pages; navigate mode '" + mode + "' needs a menu or window");
                }
                case "close" -> parser.problem(at + ".type", "a hologram has nothing to close");
                case "call" -> parser.problem(at + ".type", "holograms have no named action lists to call");
                default -> {}
            }
            for (String key : List.of("then", "else", "default", "steps")) restrict(action.get(key), at + "." + key, parser);
            if (action.has("cases") && action.get("cases").isJsonObject())
                for (Map.Entry<String, JsonElement> entry : action.getAsJsonObject("cases").entrySet()) restrict(entry.getValue(), at + ".cases." + entry.getKey(), parser);
            if (action.has("branches") && action.get("branches").isJsonArray())
                for (int branch = 0; branch < action.getAsJsonArray("branches").size(); branch++)
                    restrict(action.getAsJsonArray("branches").get(branch), at + ".branches[" + branch + "]", parser);
        }
    }
}
