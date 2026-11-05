package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Condition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A Gloss holographic menu (unversioned {@code menus/} format) run by Animus: components placed around the viewer
 * when it opens, drawn with packet-only displays and clicked through interaction boxes. Gloss menus carry no
 * revision; the document is identified by its id and its content hash. Every problem is reported with its JSON path
 * and the document is refused as a whole.
 */
public record MenuDocument(String id, String hash, double[] offset, boolean lockPosition, boolean followPlayer, double maxDistance,
                           boolean closeOnDeath, boolean closeOnTeleport, List<Component> components, Condition show,
                           Map<String, String> vars, Map<String, List<Action>> actions) {
    public static final Set<String> ROOTS = Set.of("viewer", "player", "server", "time", "args", "session", "npc", "page", "pages", GuiTemplate.ANIMATION);
    public static final double MAXIMUM_DISTANCE = 6.0E7;
    private static final Set<String> FIELDS = Set.of("offset", "lockPosition", "followPlayer", "maxDistance", "closeOnDeath", "closeOnTeleport",
            "particleLayers", "components", "show", "vars", "actions");
    private static final int MAXIMUM_COMPONENTS = 256, MAXIMUM_NAMED_ACTIONS = 256, MAXIMUM_TABS = 32;

    public MenuDocument { offset = offset.clone(); components = List.copyOf(components); vars = Map.copyOf(vars); actions = Map.copyOf(actions); }

    public double[] offset() { return offset.clone(); }

    public record Component(String id, double[] offset, Condition show, Motion motion, Data data) {
        public Component { offset = offset.clone(); }
        public double[] offset() { return offset.clone(); }
    }

    public sealed interface Data permits Button, Decoration, Toggle, ListData, Tabs, Slider {}
    public enum Easing { LINEAR, EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC, BACK_OUT;
        public double apply(double t) {
            return switch (this) {
                case LINEAR -> t;
                case EASE_OUT_CUBIC -> 1 - Math.pow(1 - t, 3);
                case EASE_IN_OUT_CUBIC -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
                case BACK_OUT -> { double c1 = 1.70158, c3 = c1 + 1; yield 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }
            };
        }
    }
    /** {@code hitbox}: sizes override the icon's automatic click area; {@code menuAnchor} measures the offset from the menu centre. */
    public record Hitbox(Optional<Double> width, Optional<Double> height, double[] offset, boolean menuAnchor) {
        public Hitbox { offset = offset.clone(); }
        public double[] offset() { return offset.clone(); }
    }
    public record Hover(double highlight, int ticks, Easing easing, Optional<Hitbox> hitbox) {
        public static final Hover NONE = new Hover(0, 0, Easing.LINEAR, Optional.empty());
    }
    public record Tooltip(int delayTicks, List<GuiTemplate> lines, HoloParts.Style style) {
        public Tooltip { lines = List.copyOf(lines); }
    }
    public record Button(Icon icon, List<Action> actions, Hover hover, Optional<Tooltip> tooltip) implements Data {
        public Button { actions = List.copyOf(actions); }
    }
    public record Decoration(Icon icon) implements Data {}
    public record Toggle(GuiExpression condition, GuiTemplate expected, List<Action> trueActions, List<Action> falseActions, Icon trueIcon, Icon falseIcon,
                         Hover hover) implements Data {
        public Toggle { trueActions = List.copyOf(trueActions); falseActions = List.copyOf(falseActions); }
    }
    public record Flow(int columns, double spacingX, double spacingY, double x, double y) {
        public static final Flow DEFAULT = new Flow(3, 1.0, 0.5, 0, 0);
    }
    public record ListData(String variable, GuiExpression source, int pageSize, Flow flow, Data template) implements Data {}
    public record Tab(String id, GuiTemplate label) {}
    public record Tabs(String variable, List<Tab> tabs, double spacing, HoloParts.Style style) implements Data {
        public Tabs { tabs = List.copyOf(tabs); }
    }
    public record Slider(String variable, double min, double max, double step, double width, GuiTemplate label, HoloParts.Style style) implements Data {}

    public sealed interface Icon permits TextIcon, ItemIcon, BlockIcon, MobIcon {}
    public record TextIcon(GuiTemplate text, int refreshTicks, HoloParts.Style style) implements Icon {}
    public record ItemIcon(GuiModel.Icon icon, Optional<GuiTemplate> countFormat, HoloParts.Style style) implements Icon {}
    public record BlockIcon(String block, HoloParts.Style style) implements Icon {}
    public record MobIcon(String type, double width, double height) implements Icon {}

    /** A number or an expression read every tick. */
    public record MotionValue(double constant, Optional<GuiExpression> expression) {
        public static final MotionValue ZERO = new MotionValue(0, Optional.empty()), ONE = new MotionValue(1, Optional.empty());
        public double value(GuiExpression.Scope scope) {
            if (expression.isEmpty()) return constant;
            try { double value = expression.get().number(scope); return Double.isFinite(value) ? value : constant; }
            catch (GuiExpression.Invalid failed) { return constant; }
        }
        public boolean moving() { return expression.isPresent(); }
    }
    public record Motion(MotionValue x, MotionValue y, MotionValue z, MotionValue scale, MotionValue ticks) {
        public static final Motion NONE = new Motion(MotionValue.ZERO, MotionValue.ZERO, MotionValue.ZERO, MotionValue.ONE, new MotionValue(3, Optional.empty()));
        public boolean moving() { return x.moving() || y.moving() || z.moving() || scale.moving() || this != NONE && ticks.moving(); }
    }

    public boolean hasCommands() {
        List<Action> all = new ArrayList<>();
        actions.values().forEach(all::addAll);
        for (Component component : components) collect(component.data(), all);
        return GuiActions.commands(all);
    }

    private static void collect(Data data, List<Action> into) {
        switch (data) {
            case Button button -> into.addAll(button.actions());
            case Toggle toggle -> { into.addAll(toggle.trueActions()); into.addAll(toggle.falseActions()); }
            case ListData list -> collect(list.template(), into);
            default -> {}
        }
    }

    /** Every text animation the menu's text shows. */
    public Set<String> animations() {
        Set<String> found = new HashSet<>();
        for (Component component : components) animations(component.data(), found);
        return found;
    }

    private static void animations(Data data, Set<String> found) {
        switch (data) {
            case Button button -> { animations(button.icon(), found); button.tooltip().ifPresent((Tooltip tip) -> tip.lines().forEach((GuiTemplate line) -> found.addAll(line.animations()))); }
            case Decoration decoration -> animations(decoration.icon(), found);
            case Toggle toggle -> { animations(toggle.trueIcon(), found); animations(toggle.falseIcon(), found); }
            case ListData list -> animations(list.template(), found);
            case Tabs tabs -> tabs.tabs().forEach((Tab tab) -> found.addAll(tab.label().animations()));
            case Slider slider -> found.addAll(slider.label().animations());
        }
    }

    private static void animations(Icon icon, Set<String> found) { if (icon instanceof TextIcon text) found.addAll(text.text().animations()); }

    public record Result(Optional<MenuDocument> document, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    public static Result parse(String id, String json) {
        JsonObject object;
        try { object = JsonDocuments.object(json); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
        return parse(id, object, hash(json));
    }

    public static String hash(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Result parse(String id, JsonObject object, String hash) {
        List<String> problems = new ArrayList<>();
        GuiParser parser = new GuiParser(problems, ROOTS, true);
        parser.unknownFields(object, FIELDS, "$");
        double[] offset = HoloParts.vector(object.get("offset"), "$.offset", parser, 64).orElse(new double[3]);
        boolean lock = object.has("lockPosition") && parser.bool(object, "lockPosition", "$");
        boolean follow = object.has("followPlayer") && parser.bool(object, "followPlayer", "$");
        double maxDistance = object.has("maxDistance") ? parser.decimal(object, "maxDistance", "$", 0, MAXIMUM_DISTANCE).orElse(MAXIMUM_DISTANCE) : MAXIMUM_DISTANCE;
        boolean closeOnDeath = object.has("closeOnDeath") && parser.bool(object, "closeOnDeath", "$");
        boolean closeOnTeleport = object.has("closeOnTeleport") && parser.bool(object, "closeOnTeleport", "$");
        HoloParts.particleLayers(object.get("particleLayers"), "$.particleLayers", parser);
        Condition show = parser.condition(object.get("show"), "$.show", ROOTS);
        Map<String, String> vars = new LinkedHashMap<>();
        if (object.has("vars")) {
            JsonElement value = object.get("vars");
            if (!value.isJsonObject()) parser.problem("$.vars", "expected object");
            else for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                String path = "$.vars." + entry.getKey();
                if (!entry.getKey().matches("[a-z][a-z0-9_]*")) parser.problem(path, "expected [a-z][a-z0-9_]*");
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) { parser.problem(path, "expected a constant expression string"); continue; }
                try { vars.put(entry.getKey(), GuiExpression.text(GuiExpression.parse(entry.getValue().getAsString(), Set.of()).evaluate((String name) -> null))); }
                catch (GuiExpression.Invalid invalid) { parser.problem(path, "expected a constant expression: " + invalid.getMessage()); }
            }
        }
        Map<String, List<Action>> named = new LinkedHashMap<>();
        if (object.has("actions")) {
            JsonElement value = object.get("actions");
            if (!value.isJsonObject() || value.getAsJsonObject().size() > MAXIMUM_NAMED_ACTIONS) parser.problem("$.actions", "expected an object of at most " + MAXIMUM_NAMED_ACTIONS + " named action lists");
            else for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}")) parser.problem("$.actions." + entry.getKey(), "expected [A-Za-z0-9_-]{1,64}");
                named.put(entry.getKey(), parser.actions(entry.getValue(), "$.actions." + entry.getKey(), ROOTS, true));
            }
        }
        List<Component> components = new ArrayList<>();
        JsonElement list = object.get("components");
        if (list == null || !list.isJsonArray()) parser.problem("$.components", "expected array of components");
        else if (list.getAsJsonArray().size() > MAXIMUM_COMPONENTS) parser.problem("$.components", "at most " + MAXIMUM_COMPONENTS + " components");
        else {
            Set<String> ids = new HashSet<>();
            JsonArray array = list.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String path = "$.components[" + index + "]";
                JsonElement element = array.get(index);
                if (!element.isJsonObject()) { parser.problem(path, "expected component object"); continue; }
                JsonObject component = element.getAsJsonObject();
                parser.unknownFields(component, Set.of("id", "offset", "data", "show", "motion"), path);
                String componentId = parser.string(component, "id", path).orElse("");
                if (!componentId.isEmpty() && !ids.add(componentId)) parser.problem(path + ".id", "duplicate component id '" + componentId + "'");
                double[] at = HoloParts.vector(component.get("offset"), path + ".offset", parser, 64).orElse(new double[3]);
                Condition componentShow = parser.condition(component.get("show"), path + ".show", ROOTS);
                Motion motion = component.has("motion") ? motion(component.get("motion"), path + ".motion", parser) : Motion.NONE;
                Optional<Data> data = data(component.get("data"), path + ".data", parser, true);
                data.ifPresent((Data parsed) -> components.add(new Component(componentId, at, componentShow, motion, parsed)));
            }
        }
        calls(named, components, parser);
        if (!problems.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new MenuDocument(id, hash, offset, lock, follow, maxDistance, closeOnDeath, closeOnTeleport, components, show, vars, named)), List.of());
    }

    private static Optional<Data> data(JsonElement value, String path, GuiParser parser, boolean listAllowed) {
        if (value == null || !value.isJsonObject()) { parser.problem(path, "expected component data object"); return Optional.empty(); }
        JsonObject data = value.getAsJsonObject();
        String type = parser.string(data, "type", path).orElse("");
        Set<String> hover = Set.of("highlightModifier", "hoverDurationTicks", "hoverEasing", "hitbox");
        Set<String> allowed = new HashSet<>(Set.of("type"));
        Optional<Data> result = switch (type) {
            case "button" -> {
                allowed.addAll(hover); allowed.addAll(Set.of("actions", "icon", "tooltip"));
                yield Optional.of(new Button(icon(data.get("icon"), path + ".icon", parser), parser.actions(data.get("actions"), path + ".actions", parser.roots(), true),
                        hover(data, path, parser), data.has("tooltip") ? tooltip(data.get("tooltip"), path + ".tooltip", parser) : Optional.empty()));
            }
            case "decoration" -> { allowed.add("icon"); yield Optional.of(new Decoration(icon(data.get("icon"), path + ".icon", parser))); }
            case "toggle" -> {
                allowed.addAll(hover); allowed.addAll(Set.of("condition", "expectedValue", "trueActions", "falseActions", "trueIcon", "falseIcon"));
                GuiExpression condition = parser.expression(data, "condition", path, parser.roots()).orElse(GuiExpression.parse("false", Set.of()));
                GuiTemplate expected = parser.template(data, "expectedValue", path, parser.roots()).orElse(GuiTemplate.literal("true"));
                yield Optional.of(new Toggle(condition, expected, parser.actions(data.get("trueActions"), path + ".trueActions", parser.roots(), true),
                        parser.actions(data.get("falseActions"), path + ".falseActions", parser.roots(), true), icon(data.get("trueIcon"), path + ".trueIcon", parser),
                        icon(data.get("falseIcon"), path + ".falseIcon", parser), hover(data, path, parser)));
            }
            case "list" -> {
                allowed.addAll(Set.of("var", "source", "pageSize", "flow", "template"));
                if (!listAllowed) { parser.problem(path + ".type", "a list template cannot be another list"); yield Optional.empty(); }
                String variable = parser.string(data, "var", path).orElse("entry");
                if (!variable.matches("[a-z][a-zA-Z0-9_]*")) parser.problem(path + ".var", "expected [a-z][a-zA-Z0-9_]*");
                Set<String> roots = new HashSet<>(parser.roots());
                roots.add(variable);
                GuiExpression source = parser.expression(data, "source", path, parser.roots()).orElse(GuiExpression.parse("[]", Set.of()));
                int pageSize = data.has("pageSize") ? parser.integer(data, "pageSize", path, 1, 64).orElse(6) : 6;
                Flow flow = Flow.DEFAULT;
                if (data.has("flow")) {
                    JsonElement grid = data.get("flow");
                    if (!grid.isJsonObject()) parser.problem(path + ".flow", "expected object");
                    else {
                        JsonObject f = grid.getAsJsonObject();
                        parser.unknownFields(f, Set.of("columns", "spacingX", "spacingY", "x", "y"), path + ".flow");
                        flow = new Flow(f.has("columns") ? parser.integer(f, "columns", path + ".flow", 1, 64).orElse(3) : 3,
                                f.has("spacingX") ? parser.decimal(f, "spacingX", path + ".flow", -64, 64).orElse(1.0) : 1.0,
                                f.has("spacingY") ? parser.decimal(f, "spacingY", path + ".flow", -64, 64).orElse(0.5) : 0.5,
                                f.has("x") ? parser.decimal(f, "x", path + ".flow", -64, 64).orElse(0.0) : 0,
                                f.has("y") ? parser.decimal(f, "y", path + ".flow", -64, 64).orElse(0.0) : 0);
                    }
                }
                GuiParser entries = parser.with(roots);
                Optional<Data> template = data(data.get("template"), path + ".template", entries, false);
                Flow chosen = flow;
                yield template.map((Data parsed) -> new ListData(variable, source, pageSize, chosen, parsed));
            }
            case "tabs" -> {
                allowed.addAll(Set.of("var", "tabs", "spacing", "style"));
                String variable = parser.string(data, "var", path).orElse("tab");
                if (!variable.matches("[a-z][a-z0-9_]*")) parser.problem(path + ".var", "expected [a-z][a-z0-9_]*");
                List<Tab> tabs = new ArrayList<>();
                JsonElement entries = data.get("tabs");
                if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty() || entries.getAsJsonArray().size() > MAXIMUM_TABS)
                    parser.problem(path + ".tabs", "expected 1 to " + MAXIMUM_TABS + " tabs");
                else for (int index = 0; index < entries.getAsJsonArray().size(); index++) {
                    String at = path + ".tabs[" + index + "]";
                    JsonElement tab = entries.getAsJsonArray().get(index);
                    if (!tab.isJsonObject()) { parser.problem(at, "expected tab object"); continue; }
                    parser.unknownFields(tab.getAsJsonObject(), Set.of("id", "label"), at);
                    String tabId = parser.string(tab.getAsJsonObject(), "id", at).orElse("");
                    GuiTemplate label = tab.getAsJsonObject().has("label") ? parser.template(tab.getAsJsonObject(), "label", at, parser.roots()).orElse(GuiTemplate.literal(tabId)) : GuiTemplate.literal(tabId);
                    tabs.add(new Tab(tabId, label));
                }
                double spacing = data.has("spacing") ? parser.decimal(data, "spacing", path, 0, 64).orElse(1.0) : 1.0;
                yield Optional.of(new Tabs(variable, tabs, spacing, HoloParts.style(data.get("style"), path + ".style", HoloParts.Style.MENU, parser)));
            }
            case "slider" -> {
                allowed.addAll(Set.of("var", "min", "max", "step", "width", "label", "style"));
                String variable = parser.string(data, "var", path).orElse("value");
                if (!variable.matches("[a-z][a-z0-9_]*")) parser.problem(path + ".var", "expected [a-z][a-z0-9_]*");
                double min = data.has("min") ? parser.decimal(data, "min", path, -1.0E9, 1.0E9).orElse(0.0) : 0;
                double max = data.has("max") ? parser.decimal(data, "max", path, -1.0E9, 1.0E9).orElse(100.0) : 100;
                if (max <= min) parser.problem(path + ".max", "max must be greater than min");
                double step = data.has("step") ? parser.decimal(data, "step", path, 1.0E-9, 1.0E9).orElse(1.0) : 1;
                double width = data.has("width") ? parser.decimal(data, "width", path, 0.01, 64).orElse(2.0) : 2;
                GuiTemplate label = data.has("label") ? parser.template(data, "label", path, parser.roots()).orElse(GuiTemplate.literal("")) : GuiTemplate.parse("{{ session." + variable + " }}", parser.roots());
                yield Optional.of(new Slider(variable, min, max, step, width, label, HoloParts.style(data.get("style"), path + ".style", HoloParts.Style.MENU, parser)));
            }
            case "field" -> { parser.problem(path + ".type", "component 'field' opens a sign, anvil or chat prompt, which is not available in Animus"); yield Optional.empty(); }
            case "" -> Optional.empty();
            default -> { parser.problem(path + ".type", "unknown component '" + type + "'"); yield Optional.empty(); }
        };
        parser.unknownFields(data, allowed, path);
        return result;
    }

    private static Hover hover(JsonObject data, String path, GuiParser parser) {
        double highlight = data.has("highlightModifier") ? parser.decimal(data, "highlightModifier", path, -64, 64).orElse(0.0) : 0;
        int ticks = data.has("hoverDurationTicks") ? parser.integer(data, "hoverDurationTicks", path, 0, 40).orElse(4) : 4;
        Easing easing = data.has("hoverEasing") ? parser.choice(data, "hoverEasing", path, Set.of("linear", "ease_out_cubic", "ease_in_out_cubic", "back_out"))
                .map((String text) -> Easing.valueOf(text.toUpperCase(Locale.ROOT))).orElse(Easing.EASE_OUT_CUBIC) : Easing.EASE_OUT_CUBIC;
        Optional<Hitbox> hitbox = Optional.empty();
        if (data.has("hitbox")) {
            JsonElement value = data.get("hitbox");
            String at = path + ".hitbox";
            if (!value.isJsonObject()) parser.problem(at, "expected object");
            else {
                JsonObject box = value.getAsJsonObject();
                parser.unknownFields(box, Set.of("width", "height", "offset", "anchor"), at);
                Optional<Double> width = box.has("width") ? parser.decimal(box, "width", at, 0.01, 64) : Optional.empty();
                Optional<Double> height = box.has("height") ? parser.decimal(box, "height", at, 0.01, 64) : Optional.empty();
                double[] offset = box.has("offset") ? HoloParts.vector(box.get("offset"), at + ".offset", parser, 64).orElse(new double[3]) : new double[3];
                boolean menu = box.has("anchor") && parser.choice(box, "anchor", at, Set.of("button", "menu")).orElse("button").equals("menu");
                hitbox = Optional.of(new Hitbox(width, height, offset, menu));
            }
        }
        return new Hover(highlight, ticks, easing, hitbox);
    }

    private static Optional<Tooltip> tooltip(JsonElement value, String path, GuiParser parser) {
        if (!value.isJsonObject()) { parser.problem(path, "expected object"); return Optional.empty(); }
        JsonObject tip = value.getAsJsonObject();
        parser.unknownFields(tip, Set.of("delayTicks", "lines", "style", "box"), path);
        int delay = tip.has("delayTicks") ? parser.integer(tip, "delayTicks", path, 0, 200).orElse(10) : 10;
        List<GuiTemplate> lines = new ArrayList<>();
        JsonElement list = tip.get("lines");
        if (list != null && list.isJsonArray()) for (int index = 0; index < list.getAsJsonArray().size(); index++) {
            JsonElement line = list.getAsJsonArray().get(index);
            if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) { parser.problem(path + ".lines[" + index + "]", "expected string"); continue; }
            parser.parseTemplate(line.getAsString(), path + ".lines[" + index + "]", parser.roots()).ifPresent(lines::add);
        } else if (list != null) parser.problem(path + ".lines", "expected array of strings");
        HoloParts.box(tip.get("box"), path + ".box", parser);
        return Optional.of(new Tooltip(delay, lines, HoloParts.style(tip.get("style"), path + ".style", HoloParts.Style.MENU, parser)));
    }

    private static Icon icon(JsonElement value, String path, GuiParser parser) {
        Icon fallback = new TextIcon(GuiTemplate.literal("?"), 10, HoloParts.Style.MENU);
        if (value == null || !value.isJsonObject()) { parser.problem(path, "expected icon object"); return fallback; }
        JsonObject icon = value.getAsJsonObject();
        String type = parser.string(icon, "type", path).orElse("");
        HoloParts.Style style = HoloParts.style(icon.get("style"), path + ".style", HoloParts.Style.MENU, parser);
        switch (type) {
            case "text" -> {
                parser.unknownFields(icon, Set.of("type", "style", "text", "refreshTicks", "box"), path);
                HoloParts.box(icon.get("box"), path + ".box", parser);
                int refresh = icon.has("refreshTicks") ? parser.integer(icon, "refreshTicks", path, 0, 1200).orElse(10) : 10;
                return new TextIcon(parser.template(icon, "text", path, parser.roots()).orElse(GuiTemplate.literal("")), refresh, style);
            }
            case "item", "playerHead" -> {
                JsonObject copy = icon.deepCopy();
                Optional<GuiTemplate> count = Optional.empty();
                if (copy.has("countFormat")) { count = parser.template(copy, "countFormat", path, parser.roots()); copy.remove("countFormat"); }
                copy.remove("style");
                return new ItemIcon(parser.icon(copy, path, parser.roots()), count, style);
            }
            case "block" -> {
                parser.unknownFields(icon, Set.of("type", "style", "block", "name", "lore"), path);
                String block = parser.string(icon, "block", path).orElse("minecraft:barrier");
                if (!HoloParts.entityType(block)) parser.problem(path + ".block", "expected a block key such as minecraft:stone");
                return new BlockIcon(HoloParts.key(block), style);
            }
            case "entity" -> {
                parser.unknownFields(icon, Set.of("type", "entity", "width", "height"), path);
                String entity = parser.string(icon, "entity", path).orElse("minecraft:pig");
                if (!HoloParts.entityType(entity)) parser.problem(path + ".entity", "expected an entity type such as minecraft:fox");
                return new MobIcon(HoloParts.key(entity), icon.has("width") ? parser.decimal(icon, "width", path, 0.01, 64).orElse(1.0) : 1,
                        icon.has("height") ? parser.decimal(icon, "height", path, 0.01, 64).orElse(1.0) : 1);
            }
            case "textImage", "animatedTextImage" -> { parser.problem(path + ".type", "icon '" + type + "' needs Gloss's image glyphs and is not available in Animus"); return fallback; }
            case "customItem" -> { parser.problem(path + ".type", "icon 'customItem' is not available in Animus"); return fallback; }
            case "" -> { return fallback; }
            default -> { parser.problem(path + ".type", "unknown icon '" + type + "'"); return fallback; }
        }
    }

    private static Motion motion(JsonElement value, String path, GuiParser parser) {
        if (!value.isJsonObject()) { parser.problem(path, "expected object"); return Motion.NONE; }
        JsonObject motion = value.getAsJsonObject();
        parser.unknownFields(motion, Set.of("x", "y", "z", "scale", "ticks"), path);
        return new Motion(motionValue(motion, "x", path, parser, MotionValue.ZERO), motionValue(motion, "y", path, parser, MotionValue.ZERO),
                motionValue(motion, "z", path, parser, MotionValue.ZERO), motionValue(motion, "scale", path, parser, MotionValue.ONE),
                motionValue(motion, "ticks", path, parser, new MotionValue(3, Optional.empty())));
    }

    private static MotionValue motionValue(JsonObject object, String field, String path, GuiParser parser, MotionValue fallback) {
        if (!object.has(field)) return fallback;
        JsonElement value = object.get(field);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble())) {
            if (field.equals("ticks") && (value.getAsDouble() < 0 || value.getAsDouble() > 59)) parser.problem(path + ".ticks", "expected 0..59");
            return new MotionValue(value.getAsDouble(), Optional.empty());
        }
        return parser.expression(object, field, path, parser.roots()).map((GuiExpression expression) -> new MotionValue(fallback.constant(), Optional.of(expression))).orElse(fallback);
    }

    /** Every {@code call} names an existing list, and named lists do not call each other in a loop. */
    private static void calls(Map<String, List<Action>> named, List<Component> components, GuiParser parser) {
        List<Action> all = new ArrayList<>();
        named.values().forEach(all::addAll);
        for (Component component : components) collect(component.data(), all);
        for (String called : called(all)) if (!named.containsKey(called)) parser.problem("$.actions", "call names missing action list '" + called + "'");
        for (String start : named.keySet()) {
            Set<String> seen = new HashSet<>();
            List<String> frontier = new ArrayList<>(List.of(start));
            while (!frontier.isEmpty()) {
                String next = frontier.removeLast();
                for (String called : called(named.getOrDefault(next, List.of()))) {
                    if (called.equals(start)) { parser.problem("$.actions." + start, "calls itself through '" + next + "'"); frontier.clear(); break; }
                    if (seen.add(called)) frontier.add(called);
                }
            }
        }
    }

    private static Set<String> called(List<Action> actions) {
        Set<String> names = new HashSet<>();
        for (Action action : actions) switch (action) {
            case GuiModel.Call call -> names.add(call.action());
            case GuiModel.Sequence sequence -> names.addAll(called(sequence.steps().stream().map(GuiModel.Step::action).toList()));
            case GuiModel.If branch -> { names.addAll(called(branch.then())); names.addAll(called(branch.otherwise())); }
            case GuiModel.Switch choice -> { choice.cases().values().forEach((List<Action> list) -> names.addAll(called(list))); names.addAll(called(choice.fallback())); }
            case GuiModel.Chance chance -> { names.addAll(called(chance.then())); names.addAll(called(chance.otherwise())); }
            case GuiModel.Cooldown cooldown -> { names.addAll(called(cooldown.then())); names.addAll(called(cooldown.otherwise())); }
            case GuiModel.Parallel parallel -> parallel.branches().forEach((List<Action> list) -> names.addAll(called(list)));
            case GuiModel.Repeat repeat -> names.addAll(called(repeat.steps()));
            default -> {}
        }
        return names;
    }
}
