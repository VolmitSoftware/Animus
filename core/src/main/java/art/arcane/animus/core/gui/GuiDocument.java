package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Component;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A chest GUI document in Gloss's inventory format ({@code schemaVersion} 1), run by Animus. Every problem is reported
 * with its JSON path and the document is refused as a whole; nothing is skipped silently.
 */
public record GuiDocument(String id, long revision, GuiTemplate title, String resolution, int width, int rows, List<String> mask,
                         Map<String, Component> keys, Map<Integer, Component> slots, Optional<GuiModel.ListSection> list,
                         GuiModel.Refresh refresh, boolean closeOnTeleport, GuiModel.Condition show, List<GuiModel.Variant> variants,
                         Map<String, List<Action>> actions) {
    public static final int SCHEMA_VERSION = 1;
    public static final List<String> RESOLUTIONS = List.of("9x1", "9x2", "9x3", "9x4", "9x5", "9x6", "5x1", "3x3");
    /** Expression roots available in every chest GUI. */
    public static final Set<String> ROOTS = Set.of("viewer", "player", "server", "time", "args", "session", "npc", "page", "pages");
    private static final Set<String> FIELDS = Set.of("preset", "schemaVersion", "revision", "refresh", "title", "resolution", "mask", "keys",
            "slots", "list", "closeOnTeleport", "show", "select", "variants", "actions");
    private static final int MAXIMUM_NAMED_ACTIONS = 256;

    public GuiDocument {
        mask = List.copyOf(mask); keys = Map.copyOf(keys); slots = Map.copyOf(slots); variants = List.copyOf(variants); actions = Map.copyOf(actions);
    }

    public int size() { return width * rows; }

    /** Whether any action, at any depth, runs a command. Such a document needs the command allow-list. */
    public boolean hasCommands() {
        List<Component> components = new ArrayList<>(keys.values());
        components.addAll(slots.values());
        list.ifPresent((GuiModel.ListSection section) -> components.add(section.template()));
        for (GuiModel.Variant variant : variants) {
            variant.presentation().keys().ifPresent((Map<String, Component> map) -> components.addAll(map.values()));
            variant.presentation().slots().ifPresent((Map<Integer, Component> map) -> components.addAll(map.values()));
        }
        List<Action> all = new ArrayList<>();
        actions.values().forEach(all::addAll);
        for (Component component : components) {
            switch (component) {
                case GuiModel.Button button -> all.addAll(button.actions());
                case GuiModel.Toggle toggle -> { all.addAll(toggle.trueActions()); all.addAll(toggle.falseActions()); }
                case GuiModel.Decoration ignored -> {}
            }
        }
        return GuiActions.commands(all);
    }

    public record Result(Optional<GuiDocument> document, List<String> problems) {
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
        GuiParser parser = new GuiParser(problems, ROOTS);
        parser.unknownFields(object, FIELDS, "$");
        int schema = parser.integer(object, "schemaVersion", "$", Integer.MIN_VALUE, Integer.MAX_VALUE).orElse(SCHEMA_VERSION);
        if (object.has("schemaVersion") && schema != SCHEMA_VERSION)
            parser.problem("$.schemaVersion", "chest GUI schemaVersion " + schema + " is not supported; Animus reads version " + SCHEMA_VERSION);
        long revision = parser.integer(object, "revision", "$", 1, Integer.MAX_VALUE).orElse(1);
        if (object.has("preset") && !object.get("preset").isJsonNull())
            parser.problem("$.preset", "Gloss presets are not available in Animus; inline the preset's fields");
        GuiTemplate title = object.has("title") ? parser.template(object, "title", "$", ROOTS).orElse(GuiTemplate.literal("")) : GuiTemplate.literal("");
        String resolution = object.has("resolution") ? parser.choice(object, "resolution", "$", Set.copyOf(RESOLUTIONS)).orElse("9x3") : "9x3";
        int width = Integer.parseInt(resolution.substring(0, resolution.indexOf('x')));
        int rows = Integer.parseInt(resolution.substring(resolution.indexOf('x') + 1));
        List<String> mask = mask(object.get("mask"), "$.mask", width, rows, parser);
        Map<String, Component> keys = keys(object.get("keys"), "$.keys", parser, ROOTS);
        Map<Integer, Component> slots = slots(object.get("slots"), "$.slots", width * rows, parser, ROOTS);
        Optional<GuiModel.ListSection> list = Optional.empty();
        if (object.has("list")) list = list(object.get("list"), mask, parser);
        GuiModel.Refresh refresh = GuiModel.Refresh.DEFAULT;
        if (object.has("refresh")) {
            JsonElement value = object.get("refresh");
            if (!value.isJsonObject()) parser.problem("$.refresh", "expected object");
            else {
                JsonObject section = value.getAsJsonObject();
                parser.unknownFields(section, Set.of("mode", "titleTicks", "slotsTicks", "conditionsTicks", "listTicks"), "$.refresh");
                boolean always = section.has("mode") && parser.choice(section, "mode", "$.refresh", Set.of("dynamic", "always")).orElse("dynamic").equals("always");
                int titleTicks = section.has("titleTicks") ? parser.integer(section, "titleTicks", "$.refresh", 0, 1200).orElse(20) : 20;
                int slotsTicks = section.has("slotsTicks") ? parser.integer(section, "slotsTicks", "$.refresh", 0, 1200).orElse(20) : 20;
                if (section.has("conditionsTicks")) slotsTicks = Math.min(slotsTicks == 0 ? Integer.MAX_VALUE : slotsTicks,
                        Math.max(1, parser.integer(section, "conditionsTicks", "$.refresh", 0, 1200).orElse(20)));
                refresh = new GuiModel.Refresh(always, titleTicks, slotsTicks);
            }
        }
        boolean closeOnTeleport = !object.has("closeOnTeleport") || parser.bool(object, "closeOnTeleport", "$");
        GuiModel.Condition show = parser.condition(object.get("show"), "$.show", ROOTS);
        if (object.has("select")) {
            JsonElement select = object.get("select");
            if (!select.isJsonObject()) parser.problem("$.select", "expected object");
            else {
                parser.unknownFields(select.getAsJsonObject(), Set.of("priority", "when"), "$.select");
                if (select.getAsJsonObject().has("when")) parser.expression(select.getAsJsonObject(), "when", "$.select", ROOTS);
            }
        }
        List<GuiModel.Variant> variants = variants(object.get("variants"), width, rows, parser);
        Map<String, List<Action>> named = new LinkedHashMap<>();
        if (object.has("actions")) {
            JsonElement value = object.get("actions");
            if (!value.isJsonObject()) parser.problem("$.actions", "expected object of action lists");
            else if (value.getAsJsonObject().size() > MAXIMUM_NAMED_ACTIONS) parser.problem("$.actions", "at most " + MAXIMUM_NAMED_ACTIONS + " named action lists");
            else for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}")) parser.problem("$.actions." + entry.getKey(), "names use [A-Za-z0-9_-]{1,64}");
                named.put(entry.getKey(), parser.actions(entry.getValue(), "$.actions." + entry.getKey(), ROOTS, true));
            }
        }
        checkCalls(named, keys, slots, list, variants, parser);
        if (!problems.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new GuiDocument(id, revision, title, resolution, width, rows, mask, keys, slots, list, refresh,
                closeOnTeleport, show, variants, named)), List.of());
    }

    /** Every filled slot for a mask, key map and slot overrides; explicit slots win over the mask. */
    public static Map<Integer, Component> resolve(int width, List<String> mask, Map<String, Component> keys, Map<Integer, Component> slots) {
        Map<Integer, Component> resolved = new HashMap<>();
        for (int row = 0; row < mask.size(); row++) {
            String line = mask.get(row);
            for (int column = 0; column < line.length(); column++) {
                char cell = line.charAt(column);
                if (cell == ' ') continue;
                Component component = keys.get(String.valueOf(cell));
                if (component != null) resolved.put(row * width + column, component);
            }
        }
        resolved.putAll(slots);
        return resolved;
    }

    /** The slots a mask character covers, in reading order. */
    public static List<Integer> area(int width, List<String> mask, char area) {
        List<Integer> result = new ArrayList<>();
        for (int row = 0; row < mask.size(); row++) {
            String line = mask.get(row);
            for (int column = 0; column < line.length(); column++) if (line.charAt(column) == area) result.add(row * width + column);
        }
        return List.copyOf(result);
    }

    private static List<String> mask(JsonElement value, String path, int width, int rows, GuiParser parser) {
        if (value == null) return List.of();
        if (!value.isJsonArray()) { parser.problem(path, "expected array of rows"); return List.of(); }
        List<String> mask = new ArrayList<>();
        for (JsonElement row : value.getAsJsonArray()) {
            if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) { parser.problem(path, "rows must be strings"); return List.of(); }
            mask.add(row.getAsString());
        }
        if (mask.size() != rows) parser.problem(path, "declares " + mask.size() + " rows but the resolution has " + rows);
        for (int index = 0; index < mask.size(); index++)
            if (mask.get(index).length() != width) parser.problem(path + "[" + index + "]", "row \"" + mask.get(index) + "\" must be exactly " + width + " characters wide");
        return List.copyOf(mask);
    }

    private static Map<String, Component> keys(JsonElement value, String path, GuiParser parser, Set<String> roots) {
        Map<String, Component> keys = new LinkedHashMap<>();
        if (value == null) return keys;
        if (!value.isJsonObject()) { parser.problem(path, "expected object"); return keys; }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (entry.getKey().length() != 1 || entry.getKey().equals(" ")) parser.problem(path + "." + entry.getKey(), "keys are single non-space mask characters");
            keys.put(entry.getKey(), parser.component(entry.getValue(), path + "." + entry.getKey(), roots));
        }
        return keys;
    }

    private static Map<Integer, Component> slots(JsonElement value, String path, int size, GuiParser parser, Set<String> roots) {
        Map<Integer, Component> slots = new LinkedHashMap<>();
        if (value == null) return slots;
        if (!value.isJsonObject()) { parser.problem(path, "expected object"); return slots; }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey().trim();
            if (!key.matches("[0-9]{1,3}")) { parser.problem(path + "." + entry.getKey(), "slot key must be a slot index"); continue; }
            int index = Integer.parseInt(key);
            if (index >= size) { parser.problem(path + "." + entry.getKey(), "slot " + index + " is outside the window (0.." + (size - 1) + ")"); continue; }
            slots.put(index, parser.component(entry.getValue(), path + "." + entry.getKey(), roots));
        }
        return slots;
    }

    private static Optional<GuiModel.ListSection> list(JsonElement value, List<String> mask, GuiParser parser) {
        if (!value.isJsonObject()) { parser.problem("$.list", "expected object"); return Optional.empty(); }
        JsonObject object = value.getAsJsonObject();
        parser.unknownFields(object, Set.of("area", "var", "source", "pageSize", "template", "refreshTicks"), "$.list");
        String area = parser.string(object, "area", "$.list").orElse("");
        if (area.length() != 1) { parser.problem("$.list.area", "must be exactly one mask character"); return Optional.empty(); }
        if (mask.stream().noneMatch((String row) -> row.indexOf(area.charAt(0)) >= 0)) parser.problem("$.list.area", "area '" + area + "' does not appear in the mask");
        String variable = parser.string(object, "var", "$.list").orElse("entry");
        if (!variable.matches("[a-z][a-z0-9_]*")) parser.problem("$.list.var", "must match [a-z][a-z0-9_]*");
        if (ROOTS.contains(variable)) parser.problem("$.list.var", "'" + variable + "' shadows a built-in namespace");
        Set<String> roots = new HashSet<>(ROOTS);
        roots.add(variable);
        Optional<GuiExpression> source = parser.expression(object, "source", "$.list", ROOTS);
        Optional<Integer> pageSize = object.has("pageSize") ? parser.integer(object, "pageSize", "$.list", 1, 54) : Optional.empty();
        Component template = parser.component(object.get("template"), "$.list.template", roots);
        int refreshTicks = object.has("refreshTicks") ? parser.integer(object, "refreshTicks", "$.list", 0, 1200).orElse(20) : 20;
        if (source.isEmpty()) return Optional.empty();
        return Optional.of(new GuiModel.ListSection(area.charAt(0), variable, source.get(), pageSize, template, refreshTicks));
    }

    private static List<GuiModel.Variant> variants(JsonElement value, int width, int rows, GuiParser parser) {
        List<GuiModel.Variant> variants = new ArrayList<>();
        if (value == null) return variants;
        if (!value.isJsonArray()) { parser.problem("$.variants", "expected array"); return variants; }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < value.getAsJsonArray().size(); index++) {
            String path = "$.variants[" + index + "]";
            JsonElement element = value.getAsJsonArray().get(index);
            if (!element.isJsonObject()) { parser.problem(path, "expected object"); continue; }
            JsonObject object = element.getAsJsonObject();
            parser.unknownFields(object, Set.of("id", "priority", "when", "presentation"), path);
            String id = parser.string(object, "id", path).orElse("");
            if (!id.isEmpty() && !seen.add(id)) parser.problem(path + ".id", "variant id is duplicated: " + id);
            int priority = object.has("priority") ? parser.integer(object, "priority", path, Integer.MIN_VALUE, Integer.MAX_VALUE).orElse(0) : 0;
            GuiExpression when = object.has("when") ? parser.expression(object, "when", path, ROOTS).orElse(GuiExpression.parse("false", Set.of()))
                    : GuiExpression.parse("false", Set.of());
            JsonElement presentation = object.get("presentation");
            if (presentation == null || !presentation.isJsonObject()) { parser.problem(path + ".presentation", "expected object"); continue; }
            JsonObject view = presentation.getAsJsonObject();
            String viewPath = path + ".presentation";
            parser.unknownFields(view, Set.of("title", "mask", "keys", "slots"), viewPath);
            Optional<GuiTemplate> title = view.has("title") ? parser.template(view, "title", viewPath, ROOTS) : Optional.empty();
            Optional<List<String>> mask = view.has("mask") ? Optional.of(mask(view.get("mask"), viewPath + ".mask", width, rows, parser)) : Optional.empty();
            Optional<Map<String, Component>> keys = view.has("keys") ? Optional.of(keys(view.get("keys"), viewPath + ".keys", parser, ROOTS)) : Optional.empty();
            Optional<Map<Integer, Component>> slots = view.has("slots") ? Optional.of(slots(view.get("slots"), viewPath + ".slots", width * rows, parser, ROOTS)) : Optional.empty();
            variants.add(new GuiModel.Variant(id, priority, when, new GuiModel.Presentation(title, mask, keys, slots)));
        }
        return variants;
    }

    /** {@code call} targets must exist, and calls may not form a cycle. */
    private static void checkCalls(Map<String, List<Action>> named, Map<String, Component> keys, Map<Integer, Component> slots,
                                   Optional<GuiModel.ListSection> list, List<GuiModel.Variant> variants, GuiParser parser) {
        Map<String, Set<String>> edges = new HashMap<>();
        for (Map.Entry<String, List<Action>> entry : named.entrySet()) {
            Set<String> calls = new HashSet<>();
            calls(entry.getValue(), calls);
            edges.put(entry.getKey(), calls);
        }
        Set<String> used = new HashSet<>();
        for (Set<String> calls : edges.values()) used.addAll(calls);
        List<Component> components = new ArrayList<>(keys.values());
        components.addAll(slots.values());
        list.ifPresent((GuiModel.ListSection section) -> components.add(section.template()));
        for (GuiModel.Variant variant : variants) {
            variant.presentation().keys().ifPresent((Map<String, Component> map) -> components.addAll(map.values()));
            variant.presentation().slots().ifPresent((Map<Integer, Component> map) -> components.addAll(map.values()));
        }
        for (Component component : components) {
            switch (component) {
                case GuiModel.Button button -> calls(button.actions(), used);
                case GuiModel.Toggle toggle -> { calls(toggle.trueActions(), used); calls(toggle.falseActions(), used); }
                case GuiModel.Decoration ignored -> {}
            }
        }
        for (String name : used) if (!named.containsKey(name)) parser.problem("$.actions", "call target '" + name + "' is not declared");
        for (String start : named.keySet()) if (cycle(start, start, edges, new HashSet<>())) { parser.problem("$.actions." + start, "call cycle"); return; }
    }

    private static boolean cycle(String start, String current, Map<String, Set<String>> edges, Set<String> visited) {
        for (String next : edges.getOrDefault(current, Set.of())) {
            if (next.equals(start)) return true;
            if (visited.add(next) && cycle(start, next, edges, visited)) return true;
        }
        return false;
    }

    private static void calls(List<Action> actions, Set<String> into) {
        for (Action action : actions) {
            switch (action) {
                case GuiModel.Call call -> into.add(call.action());
                case GuiModel.Sequence sequence -> sequence.steps().forEach((GuiModel.Step step) -> calls(List.of(step.action()), into));
                case GuiModel.If branch -> { calls(branch.then(), into); calls(branch.otherwise(), into); }
                case GuiModel.Switch choice -> { choice.cases().values().forEach((List<Action> list) -> calls(list, into)); calls(choice.fallback(), into); }
                case GuiModel.Chance chance -> { calls(chance.then(), into); calls(chance.otherwise(), into); }
                case GuiModel.Cooldown cooldown -> { calls(cooldown.then(), into); calls(cooldown.otherwise(), into); }
                default -> {}
            }
        }
    }
}
