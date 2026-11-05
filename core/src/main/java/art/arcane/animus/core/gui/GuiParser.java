package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.GuiModel.Common;
import art.arcane.animus.core.gui.GuiModel.Component;
import art.arcane.animus.core.gui.GuiModel.Condition;
import art.arcane.animus.core.gui.GuiModel.Icon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads Gloss components, icons and actions with exact field diagnostics. Problems are collected as
 * {@code $.path: message}; a value with a problem is replaced by a harmless stand-in so parsing continues and every
 * problem in the document is reported at once.
 */
final class GuiParser {
    /** Gloss action types that Animus runs. */
    static final Set<String> SUPPORTED = Set.of("command", "sound", "message", "title", "actionbar", "close", "navigate", "inventory",
            "setSession", "give", "take", "economy", "delay", "sequence", "if", "switch", "chance", "cooldown", "stop", "call",
            "teleport", "bossbar", "broadcast", "effect", "particle", "parallel", "repeat");
    /** Gloss action types with no Animus meaning. */
    static final Set<String> GLOSS_ONLY = Set.of("connect", "surface", "prompt", "dialog", "book", "emit", "setState", "addState",
            "clearState", "sky", "camera", "glow");
    static final Set<String> ANIMUS = Set.of("animus:open-shop");
    private static final Set<String> COMMON_FIELDS = Set.of("type", "trigger", "when", "cooldownTicks");
    private static final Map<String, Set<String>> ACTION_FIELDS = Map.ofEntries(
            Map.entry("command", Set.of("command", "source")), Map.entry("sound", Set.of("sound", "source", "volume", "pitch")),
            Map.entry("message", Set.of("message")), Map.entry("title", Set.of("title", "subtitle", "fadeInTicks", "stayTicks", "fadeOutTicks", "priority")),
            Map.entry("actionbar", Set.of("text", "ticks", "slots", "priority")), Map.entry("close", Set.of()),
            Map.entry("navigate", Set.of("target", "mode", "list")), Map.entry("inventory", Set.of("id", "args")),
            Map.entry("setSession", Set.of("var", "value")), Map.entry("give", Set.of("item", "amount", "dropIfFull")),
            Map.entry("take", Set.of("item", "amount", "denyMessage")), Map.entry("economy", Set.of("op", "amount", "denyMessage")),
            Map.entry("delay", Set.of("ticks")), Map.entry("sequence", Set.of("steps", "skippable", "once", "onSkip")),
            Map.entry("if", Set.of("when", "then", "else")), Map.entry("switch", Set.of("on", "cases", "default")),
            Map.entry("chance", Set.of("percent", "then", "else")), Map.entry("cooldown", Set.of("key", "ticks", "then", "else")),
            Map.entry("stop", Set.of()), Map.entry("call", Set.of("action")), Map.entry("animus:open-shop", Set.of("shop")),
            Map.entry("teleport", Set.of("world", "x", "y", "z", "yaw", "pitch")),
            Map.entry("bossbar", Set.of("id", "title", "progress", "color", "style", "ticks", "priority")),
            Map.entry("broadcast", Set.of("message", "scope", "radius", "permission")), Map.entry("effect", Set.of("effect", "ticks", "amplifier", "target")),
            Map.entry("particle", Set.of("particle", "count", "offset", "at")), Map.entry("parallel", Set.of("branches")),
            Map.entry("repeat", Set.of("times", "everyTicks", "steps", "while")));
    /** Holographic-menu presentation fields Gloss accepts on components; a chest window has nothing to draw them with. */
    private static final Set<String> MENU_ONLY = Set.of("highlightModifier", "hoverDurationTicks", "hoverEasing", "hitbox", "tooltip");
    private static final Set<String> ICON_FIELDS = Set.of("type", "style", "item", "count", "customModelValue", "name", "lore", "countFormat", "player", "refreshTicks");

    private final List<String> problems;
    private final Set<String> roots;
    private final boolean located;

    GuiParser(List<String> problems, Set<String> roots) { this(problems, roots, false); }
    /** {@code located}: the document has a place in the world, so particles may spawn {@code at} it. */
    GuiParser(List<String> problems, Set<String> roots, boolean located) { this.problems = problems; this.roots = roots; this.located = located; }

    void problem(String path, String message) { problems.add(path + ": " + message); }

    Component component(JsonElement value, String path, Set<String> scopeRoots) {
        if (value == null || !value.isJsonObject()) { problem(path, "expected component object"); return placeholder(); }
        JsonObject object = value.getAsJsonObject();
        String type = string(object, "type", path).orElse("");
        Condition show = condition(object.get("show"), path + ".show", scopeRoots);
        Set<String> allowed = new java.util.HashSet<>(Set.of("type", "show"));
        allowed.addAll(MENU_ONLY);
        Component result;
        switch (type) {
            case "button" -> {
                allowed.addAll(Set.of("icon", "actions"));
                result = new GuiModel.Button(icon(object.get("icon"), path + ".icon", scopeRoots), actions(object.get("actions"), path + ".actions", scopeRoots, true), show);
            }
            case "decoration" -> {
                allowed.add("icon");
                result = new GuiModel.Decoration(icon(object.get("icon"), path + ".icon", scopeRoots), show);
            }
            case "toggle" -> {
                allowed.addAll(Set.of("condition", "expectedValue", "trueActions", "falseActions", "trueIcon", "falseIcon"));
                GuiExpression condition = expression(object, "condition", path, scopeRoots).orElse(GuiExpression.parse("false", Set.of()));
                String expected = string(object, "expectedValue", path).orElse("true");
                result = new GuiModel.Toggle(condition, expected, actions(object.get("trueActions"), path + ".trueActions", scopeRoots, true),
                        actions(object.get("falseActions"), path + ".falseActions", scopeRoots, true),
                        icon(object.get("trueIcon"), path + ".trueIcon", scopeRoots), icon(object.get("falseIcon"), path + ".falseIcon", scopeRoots), show);
            }
            case "list", "slider", "field", "tabs" -> { problem(path + ".type", "component '" + type + "' is a holographic menu component and has no meaning in a chest window"); return placeholder(); }
            case "" -> { return placeholder(); }
            default -> { problem(path + ".type", "unknown component '" + type + "'"); return placeholder(); }
        }
        unknownFields(object, allowed, path);
        return result;
    }

    private static Component placeholder() {
        return new GuiModel.Decoration(new Icon(GuiModel.IconKind.ITEM, GuiTemplate.literal("minecraft:barrier"), 1, Optional.empty(), Optional.empty(), List.of()), Condition.NEVER);
    }

    Icon icon(JsonElement value, String path, Set<String> scopeRoots) {
        Icon fallback = new Icon(GuiModel.IconKind.ITEM, GuiTemplate.literal("minecraft:barrier"), 1, Optional.empty(), Optional.empty(), List.of());
        if (value == null || !value.isJsonObject()) { problem(path, "expected icon object"); return fallback; }
        JsonObject object = value.getAsJsonObject();
        unknownFields(object, ICON_FIELDS, path);
        String type = string(object, "type", path).orElse("item");
        Optional<GuiTemplate> name = object.has("name") ? template(object, "name", path, scopeRoots) : Optional.empty();
        List<GuiTemplate> lore = new ArrayList<>();
        if (object.has("lore")) {
            JsonElement lines = object.get("lore");
            if (!lines.isJsonArray()) problem(path + ".lore", "expected array of strings");
            else for (int index = 0; index < lines.getAsJsonArray().size(); index++) {
                JsonElement line = lines.getAsJsonArray().get(index);
                if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) { problem(path + ".lore[" + index + "]", "expected string"); continue; }
                parseTemplate(line.getAsString(), path + ".lore[" + index + "]", scopeRoots).ifPresent(lore::add);
            }
        }
        switch (type) {
            case "item" -> {
                GuiTemplate item = template(object, "item", path, scopeRoots).orElse(GuiTemplate.literal("minecraft:barrier"));
                if (item.constant() && !item.source().matches("(minecraft:)?[a-z0-9_.-]+|[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    problem(path + ".item", "expected an item id such as minecraft:stone");
                int count = object.has("count") ? integer(object, "count", path, 1, 99).orElse(1) : 1;
                Optional<Integer> model = object.has("customModelValue") ? integer(object, "customModelValue", path, Integer.MIN_VALUE, Integer.MAX_VALUE) : Optional.empty();
                return new Icon(GuiModel.IconKind.ITEM, item, count, model, name, lore);
            }
            case "playerHead" -> {
                GuiTemplate player = template(object, "player", path, scopeRoots).orElse(GuiTemplate.literal("{{ viewer.name }}"));
                return new Icon(GuiModel.IconKind.PLAYER_HEAD, player, 1, Optional.empty(), name, lore);
            }
            case "text", "textImage", "animatedTextImage", "block", "entity" -> {
                problem(path + ".type", "icon '" + type + "' is a holographic menu icon and cannot be drawn in a chest window");
                return fallback;
            }
            case "customItem" -> { problem(path + ".type", "icon 'customItem' is not available in Animus"); return fallback; }
            default -> { problem(path + ".type", "unknown icon '" + type + "'"); return fallback; }
        }
    }

    List<Action> actions(JsonElement value, String path, Set<String> scopeRoots, boolean required) {
        if (value == null) { if (required) problem(path, "expected array of actions"); return List.of(); }
        if (!value.isJsonArray()) { problem(path, "expected array of actions"); return List.of(); }
        List<Action> actions = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) action(array.get(index), path + "[" + index + "]", scopeRoots).ifPresent(actions::add);
        return List.copyOf(actions);
    }

    Optional<Action> action(JsonElement value, String path, Set<String> scopeRoots) {
        if (value == null || !value.isJsonObject()) { problem(path, "expected action object"); return Optional.empty(); }
        JsonObject object = value.getAsJsonObject();
        Optional<String> typed = string(object, "type", path);
        if (typed.isEmpty()) return Optional.empty();
        String type = typed.get();
        if (GLOSS_ONLY.contains(type)) { problem(path + ".type", "action '" + type + "' is not available in Animus"); return Optional.empty(); }
        if (type.startsWith("animus:") && !ANIMUS.contains(type)) { problem(path + ".type", "unknown Animus action '" + type + "'"); return Optional.empty(); }
        if (!SUPPORTED.contains(type) && !ANIMUS.contains(type)) { problem(path + ".type", "unknown action '" + type + "'"); return Optional.empty(); }
        Set<String> allowed = new java.util.HashSet<>(COMMON_FIELDS);
        allowed.addAll(ACTION_FIELDS.get(type));
        unknownFields(object, allowed, path);
        Common common = common(object, path, scopeRoots);
        return Optional.ofNullable(switch (type) {
            case "command" -> {
                Optional<GuiTemplate> command = template(object, "command", path, scopeRoots);
                if (command.isPresent() && command.get().source().startsWith("/")) problem(path + ".command", "write the command without a leading slash");
                String source = object.has("source") ? choice(object, "source", path, Set.of("server", "player")).orElse("server") : "server";
                yield command.map((GuiTemplate text) -> new GuiModel.Command(common, text,
                        source.equals("player") ? GuiModel.CommandSource.PLAYER : GuiModel.CommandSource.SERVER)).orElse(null);
            }
            case "sound" -> {
                Optional<String> sound = string(object, "sound", path);
                if (sound.isPresent() && !sound.get().matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")) problem(path + ".sound", "expected a sound key such as minecraft:ui.button.click");
                String source = object.has("source") ? choice(object, "source", path, Set.of("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice")).orElse("master") : "master";
                float volume = (float) (object.has("volume") ? decimal(object, "volume", path, 0, 4).orElse(1.0) : 1.0);
                float pitch = (float) (object.has("pitch") ? decimal(object, "pitch", path, 0, 2).orElse(1.0) : 1.0);
                yield sound.map((String key) -> new GuiModel.Sound(common, key, source, volume, pitch)).orElse(null);
            }
            case "message" -> template(object, "message", path, scopeRoots).map((GuiTemplate text) -> new GuiModel.Message(common, text)).orElse(null);
            case "title" -> {
                Optional<GuiTemplate> title = template(object, "title", path, scopeRoots);
                Optional<GuiTemplate> subtitle = object.has("subtitle") ? template(object, "subtitle", path, scopeRoots) : Optional.empty();
                int fadeIn = object.has("fadeInTicks") ? integer(object, "fadeInTicks", path, 0, 1200).orElse(10) : 10;
                int stay = object.has("stayTicks") ? integer(object, "stayTicks", path, 0, 1200).orElse(70) : 70;
                int fadeOut = object.has("fadeOutTicks") ? integer(object, "fadeOutTicks", path, 0, 1200).orElse(20) : 20;
                yield title.map((GuiTemplate text) -> new GuiModel.Title(common, text, subtitle, fadeIn, stay, fadeOut)).orElse(null);
            }
            case "actionbar" -> template(object, "text", path, scopeRoots).map((GuiTemplate text) -> new GuiModel.ActionBar(common, text)).orElse(null);
            case "close" -> new GuiModel.Close(common);
            case "navigate" -> {
                String mode = object.has("mode") ? choice(object, "mode", path, Set.of("page", "push", "replace", "back", "home", "close")).orElse("push") : "push";
                Optional<GuiTemplate> target = object.has("target") ? template(object, "target", path, scopeRoots) : Optional.empty();
                if (Set.of("page", "push", "replace").contains(mode) && !object.has("target")) problem(path + ".target", "mode " + mode + " needs a target");
                yield new GuiModel.Navigate(common, GuiModel.NavigateMode.valueOf(mode.toUpperCase(Locale.ROOT)), target);
            }
            case "inventory" -> {
                Optional<GuiTemplate> id = template(object, "id", path, scopeRoots);
                Map<String, GuiTemplate> args = new LinkedHashMap<>();
                if (object.has("args")) {
                    if (!object.get("args").isJsonObject()) problem(path + ".args", "expected object");
                    else for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("args").entrySet()) {
                        if (!entry.getKey().matches("[a-z][a-zA-Z0-9_]*")) problem(path + ".args." + entry.getKey(), "argument names start with a lowercase letter");
                        JsonElement argument = entry.getValue();
                        String text = argument.isJsonPrimitive() ? argument.getAsString() : argument.toString();
                        parseTemplate(text, path + ".args." + entry.getKey(), scopeRoots).ifPresent((GuiTemplate parsed) -> args.put(entry.getKey(), parsed));
                    }
                }
                yield id.map((GuiTemplate target) -> new GuiModel.Inventory(common, target, args)).orElse(null);
            }
            case "setSession" -> {
                Optional<String> variable = string(object, "var", path);
                if (variable.isPresent() && !variable.get().matches("[a-z][a-z0-9_]*")) problem(path + ".var", "expected [a-z][a-z0-9_]*");
                Optional<GuiTemplate> stored = template(object, "value", path, scopeRoots);
                yield variable.isPresent() && stored.isPresent() ? new GuiModel.SetSession(common, variable.get(), stored.get()) : null;
            }
            case "give" -> new GuiModel.Give(common, icon(object.get("item"), path + ".item", scopeRoots), amount(object, path, scopeRoots),
                    object.has("dropIfFull") && bool(object, "dropIfFull", path));
            case "take" -> new GuiModel.Take(common, icon(object.get("item"), path + ".item", scopeRoots), amount(object, path, scopeRoots),
                    object.has("denyMessage") ? template(object, "denyMessage", path, scopeRoots) : Optional.empty());
            case "economy" -> {
                String operation = choice(object, "op", path, Set.of("withdraw", "deposit", "has")).orElse("has");
                yield new GuiModel.Economy(common, GuiModel.EconomyOperation.valueOf(operation.toUpperCase(Locale.ROOT)), amount(object, path, scopeRoots),
                        object.has("denyMessage") ? template(object, "denyMessage", path, scopeRoots) : Optional.empty());
            }
            case "delay" -> new GuiModel.Delay(common, integer(object, "ticks", path, 1, 1_728_000).orElse(1));
            case "sequence" -> {
                for (String field : List.of("skippable", "once", "onSkip"))
                    if (object.has(field)) problem(path + "." + field, "sequence field '" + field + "' belongs to Gloss cutscenes and is not available in Animus");
                List<GuiModel.Step> steps = new ArrayList<>();
                JsonElement list = object.get("steps");
                if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty()) problem(path + ".steps", "expected a non-empty array");
                else for (int index = 0; index < list.getAsJsonArray().size(); index++) {
                    JsonElement step = list.getAsJsonArray().get(index);
                    String stepPath = path + ".steps[" + index + "]";
                    int at = 0;
                    if (step.isJsonObject() && step.getAsJsonObject().has("atTicks")) {
                        at = integer(step.getAsJsonObject(), "atTicks", stepPath, 0, 1_728_000).orElse(0);
                        JsonObject copy = step.getAsJsonObject().deepCopy();
                        copy.remove("atTicks");
                        step = copy;
                    }
                    int at2 = at;
                    action(step, stepPath, scopeRoots).ifPresent((Action action) -> steps.add(new GuiModel.Step(at2, action)));
                }
                yield new GuiModel.Sequence(common, steps);
            }
            case "if" -> {
                GuiExpression condition = expression(object, "when", path, scopeRoots).orElse(GuiExpression.parse("false", Set.of()));
                yield new GuiModel.If(new Common(common.triggers(), Optional.empty(), common.cooldownTicks()), condition,
                        actions(object.get("then"), path + ".then", scopeRoots, false), actions(object.get("else"), path + ".else", scopeRoots, false));
            }
            case "switch" -> {
                GuiExpression on = expression(object, "on", path, scopeRoots).orElse(GuiExpression.parse("''", Set.of()));
                Map<String, List<Action>> cases = new LinkedHashMap<>();
                if (!object.has("cases") || !object.get("cases").isJsonObject() || object.getAsJsonObject("cases").isEmpty()) problem(path + ".cases", "expected a non-empty object");
                else for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("cases").entrySet())
                    cases.put(entry.getKey(), actions(entry.getValue(), path + ".cases." + entry.getKey(), scopeRoots, true));
                yield new GuiModel.Switch(common, on, cases, actions(object.get("default"), path + ".default", scopeRoots, false));
            }
            case "chance" -> new GuiModel.Chance(common, decimal(object, "percent", path, 0, 100).orElse(0.0),
                    actions(object.get("then"), path + ".then", scopeRoots, false), actions(object.get("else"), path + ".else", scopeRoots, false));
            case "cooldown" -> {
                Optional<String> key = string(object, "key", path);
                int ticks = integer(object, "ticks", path, 1, 1_728_000).orElse(1);
                yield new GuiModel.Cooldown(common, key.orElse("cooldown"), ticks, actions(object.get("then"), path + ".then", scopeRoots, false),
                        actions(object.get("else"), path + ".else", scopeRoots, false));
            }
            case "stop" -> new GuiModel.Stop(common);
            case "call" -> {
                Optional<String> name = string(object, "action", path);
                if (name.isPresent() && !name.get().matches("[A-Za-z0-9_-]{1,64}")) problem(path + ".action", "expected [A-Za-z0-9_-]{1,64}");
                yield name.map((String called) -> new GuiModel.Call(common, called)).orElse(null);
            }
            case "teleport" -> {
                Optional<String> world = string(object, "world", path);
                if (world.isPresent() && !world.get().matches("[a-z0-9._-]+:[a-z0-9/._-]+")) problem(path + ".world", "expected a world key such as minecraft:overworld");
                Optional<Double> x = decimal(object, "x", path, -3.0E7, 3.0E7), y = decimal(object, "y", path, -2048, 4096), z = decimal(object, "z", path, -3.0E7, 3.0E7);
                Optional<Double> yaw = decimal(object, "yaw", path, -360, 360), pitch = decimal(object, "pitch", path, -90, 90);
                yield world.isPresent() && x.isPresent() && y.isPresent() && z.isPresent() && yaw.isPresent() && pitch.isPresent()
                        ? new GuiModel.Teleport(common, world.get(), x.get(), y.get(), z.get(), yaw.get().floatValue(), pitch.get().floatValue()) : null;
            }
            case "bossbar" -> {
                Optional<String> lane = string(object, "id", path);
                if (lane.isPresent() && lane.get().isBlank()) problem(path + ".id", "expected a non-blank lane id");
                Optional<GuiTemplate> title = template(object, "title", path, scopeRoots);
                Optional<GuiTemplate> progress = object.has("progress") ? template(object, "progress", path, scopeRoots) : Optional.empty();
                progress.filter(GuiTemplate::constant).ifPresent((GuiTemplate constant) -> {
                    try { double number = Double.parseDouble(constant.source().trim()); if (!(number >= 0 && number <= 1)) throw new NumberFormatException(); }
                    catch (NumberFormatException invalid) { problem(path + ".progress", "expected a number from 0 to 1"); }
                });
                String color = object.has("color") ? choice(object, "color", path, Set.of("pink", "blue", "red", "green", "yellow", "purple", "white")).orElse("white") : "white";
                String style = object.has("style") ? choice(object, "style", path, Set.of("solid", "segmented_6", "segmented_10", "segmented_12", "segmented_20")).orElse("solid") : "solid";
                int ticks = object.has("ticks") ? integer(object, "ticks", path, 0, 1200).orElse(100) : 100;
                if (object.has("priority")) choice(object, "priority", path, Set.of("ambient", "notice", "status", "progress", "interactive", "modal", "pinned"));
                yield lane.isPresent() && title.isPresent() ? new GuiModel.BossBar(common, lane.get(), title.get(), progress, color, style, ticks) : null;
            }
            case "broadcast" -> {
                Optional<GuiTemplate> message = template(object, "message", path, scopeRoots);
                String scope = object.has("scope") ? choice(object, "scope", path, Set.of("server", "world", "radius")).orElse("server") : "server";
                double radius = 0;
                if (scope.equals("radius")) radius = decimal(object, "radius", path, Double.MIN_VALUE, 1024).orElse(16.0);
                else if (object.has("radius")) problem(path + ".radius", "radius needs scope radius");
                Optional<String> permission = object.has("permission") ? string(object, "permission", path) : Optional.empty();
                double range = radius;
                yield message.map((GuiTemplate text) -> new GuiModel.Broadcast(common, text, scope, range, permission)).orElse(null);
            }
            case "effect" -> {
                Optional<String> effect = string(object, "effect", path);
                if (effect.isPresent() && !effect.get().matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) problem(path + ".effect", "expected an effect key such as minecraft:speed");
                Optional<Integer> ticks = integer(object, "ticks", path, 1, 1_728_000);
                int amplifier = object.has("amplifier") ? integer(object, "amplifier", path, 0, 255).orElse(0) : 0;
                if (object.has("target")) choice(object, "target", path, Set.of("viewer", "subject", "source"))
                        .filter((String target) -> !target.equals("viewer"))
                        .ifPresent((String target) -> problem(path + ".target", "target '" + target + "' belongs to Gloss behaviors; Animus applies effects to the viewer"));
                yield effect.isPresent() && ticks.isPresent() ? new GuiModel.Effect(common, effect.get(), ticks.get(), amplifier) : null;
            }
            case "particle" -> {
                Optional<String> particle = string(object, "particle", path);
                if (particle.isPresent() && !particle.get().matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) problem(path + ".particle", "expected a particle key such as minecraft:flame");
                int count = object.has("count") ? integer(object, "count", path, 1, 1000).orElse(1) : 1;
                double offset = object.has("offset") ? decimal(object, "offset", path, 0, 64).orElse(0.0) : 0;
                String at = object.has("at") ? choice(object, "at", path, Set.of("viewer", "subject", "source", "location")).orElse("viewer") : "viewer";
                if (at.equals("subject") || at.equals("source")) problem(path + ".at", "at '" + at + "' belongs to Gloss behaviors; use viewer or location");
                if (at.equals("location") && !located) problem(path + ".at", "a chest window has no location; use viewer");
                yield particle.map((String key) -> new GuiModel.Particle(common, key, count, offset, at.equals("location"))).orElse(null);
            }
            case "parallel" -> {
                List<List<Action>> branches = new ArrayList<>();
                JsonElement list = object.get("branches");
                if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty()) problem(path + ".branches", "expected a non-empty array of action lists");
                else for (int index = 0; index < list.getAsJsonArray().size(); index++)
                    branches.add(actions(list.getAsJsonArray().get(index), path + ".branches[" + index + "]", scopeRoots, true));
                yield new GuiModel.Parallel(common, branches);
            }
            case "repeat" -> {
                int times = integer(object, "times", path, 1, 100_000).orElse(1);
                int every = object.has("everyTicks") ? integer(object, "everyTicks", path, 0, 1_728_000).orElse(0) : 0;
                Optional<GuiExpression> condition = object.has("while") ? expression(object, "while", path, scopeRoots) : Optional.empty();
                yield new GuiModel.Repeat(common, times, every, actions(object.get("steps"), path + ".steps", scopeRoots, true), condition);
            }
            case "animus:open-shop" -> {
                Optional<String> shop = string(object, "shop", path);
                if (shop.isPresent() && !shop.get().matches("[a-z0-9_-]+:[a-z0-9_/-]+")) problem(path + ".shop", "expected a shop id such as town:baker");
                yield shop.map((String id) -> new GuiModel.OpenShop(common, id)).orElse(null);
            }
            default -> null;
        });
    }

    private Optional<GuiExpression> amount(JsonObject object, String path, Set<String> scopeRoots) {
        if (!object.has("amount")) return Optional.empty();
        JsonElement value = object.get("amount");
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return Optional.of(GuiExpression.parse(value.getAsString(), Set.of()));
        return expression(object, "amount", path, scopeRoots);
    }

    private Common common(JsonObject object, String path, Set<String> scopeRoots) {
        Set<Click> triggers = EnumSet.noneOf(Click.class);
        if (object.has("trigger") && !object.get("trigger").isJsonNull()) {
            String trigger = choice(object, "trigger", path, Set.of("any", "left_click", "right_click", "shift_left_click", "shift_right_click")).orElse("any");
            for (Click click : Click.values()) if (click.key().equals(trigger)) triggers.add(click);
        }
        Optional<GuiExpression> when = object.has("when") && !object.get("type").getAsString().equals("if") ? expression(object, "when", path, scopeRoots) : Optional.empty();
        int cooldown = object.has("cooldownTicks") ? integer(object, "cooldownTicks", path, 0, 1_728_000).orElse(0) : 0;
        return new Common(triggers, when, cooldown);
    }

    Condition condition(JsonElement value, String path, Set<String> scopeRoots) {
        if (value == null || value.isJsonNull()) return Condition.ALWAYS;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean() ? Condition.ALWAYS : Condition.NEVER;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            try { return new Condition(Optional.of(GuiExpression.parse(value.getAsString(), scopeRoots)), false); }
            catch (GuiExpression.Invalid invalid) { problem(path, invalid.getMessage()); return Condition.NEVER; }
        }
        problem(path, "expected boolean or expression string");
        return Condition.NEVER;
    }

    Optional<GuiExpression> expression(JsonObject object, String field, String path, Set<String> scopeRoots) {
        Optional<String> text = string(object, field, path);
        if (text.isEmpty()) return Optional.empty();
        try { return Optional.of(GuiExpression.parse(text.get(), scopeRoots)); }
        catch (GuiExpression.Invalid invalid) { problem(path + "." + field, invalid.getMessage()); return Optional.empty(); }
    }

    Optional<GuiTemplate> template(JsonObject object, String field, String path, Set<String> scopeRoots) {
        return string(object, field, path).flatMap((String text) -> parseTemplate(text, path + "." + field, scopeRoots));
    }

    Optional<GuiTemplate> parseTemplate(String text, String path, Set<String> scopeRoots) {
        if (text.length() > 4096) { problem(path, "exceeds 4096 characters"); return Optional.empty(); }
        try { return Optional.of(GuiTemplate.parse(text, scopeRoots)); }
        catch (GuiExpression.Invalid invalid) { problem(path, invalid.getMessage()); return Optional.empty(); }
    }

    Optional<String> string(JsonObject object, String field, String path) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) { problem(path + "." + field, "expected string"); return Optional.empty(); }
        return Optional.of(value.getAsString());
    }

    Optional<String> choice(JsonObject object, String field, String path, Set<String> options) {
        Optional<String> value = string(object, field, path);
        if (value.isPresent() && !options.contains(value.get())) { problem(path + "." + field, "expected one of " + new java.util.TreeSet<>(options)); return Optional.empty(); }
        return value;
    }

    Optional<Integer> integer(JsonObject object, String field, String path, long minimum, long maximum) {
        JsonElement value = object.get(field);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long number = value.getAsBigDecimal().longValueExact();
            if (number < minimum || number > maximum) throw new ArithmeticException();
            return Optional.of((int) number);
        } catch (ArithmeticException | NumberFormatException invalid) {
            problem(path + "." + field, "expected integer " + minimum + ".." + maximum);
            return Optional.empty();
        }
    }

    Optional<Double> decimal(JsonObject object, String field, String path, double minimum, double maximum) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < minimum || value.getAsDouble() > maximum) {
            problem(path + "." + field, "expected number " + minimum + ".." + maximum);
            return Optional.empty();
        }
        return Optional.of(value.getAsDouble());
    }

    boolean bool(JsonObject object, String field, String path) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { problem(path + "." + field, "expected boolean"); return false; }
        return value.getAsBoolean();
    }

    void unknownFields(JsonObject object, Set<String> allowed, String path) {
        for (String key : object.keySet()) if (!allowed.contains(key)) problem(path + "." + key, "unknown field");
    }

    Set<String> roots() { return roots; }
    /** The same problem list and location with other expression roots, for list templates. */
    GuiParser with(Set<String> otherRoots) { return new GuiParser(problems, otherRoots, located); }
}
