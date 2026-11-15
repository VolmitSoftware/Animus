package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.DenizenArgs.Arg;
import art.arcane.animus.core.migration.DenizenArgs.Command;
import art.arcane.animus.core.migration.DenizenArgs.Part;
import art.arcane.animus.core.migration.DenizenConverter.Interact;
import art.arcane.animus.core.migration.DenizenConverter.Scope;
import art.arcane.animus.core.migration.DenizenExpressions.Cond;
import art.arcane.animus.core.migration.DenizenExpressions.Expr;
import art.arcane.animus.core.migration.DenizenExpressions.Pending;
import art.arcane.animus.core.migration.DenizenExpressions.Result;
import art.arcane.animus.core.migration.DenizenExpressions.Text;
import art.arcane.animus.core.migration.DenizenScripts.Container;
import art.arcane.animus.core.migration.DenizenScripts.Item;
import art.arcane.animus.core.migration.DenizenScripts.Node;
import art.arcane.animus.core.migration.DenizenScripts.Sequence;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * Denizen command lists as behavior nodes: blocks, if/else and choose chains, loops and their stop/next, and each
 * command with an Animus action. A command that cannot convert is kept in the report with its reason, and so is every
 * line inside a block that is kept. The browser converter has a line-for-line copy.
 */
final class DenizenCommands {
    private final DenizenConverter converter;
    private final DenizenExpressions expressions;

    DenizenCommands(DenizenConverter converter, DenizenExpressions expressions) { this.converter = converter; this.expressions = expressions; }

    /** The click-time condition of an NPC event and the {@code determine cancelled} line it reaches. */
    record Consume(Item item, String condition) {}

    /** Converted steps, or the reason the command is kept. */
    record Converted(List<JsonObject> steps, String reason) {
        static Converted of(JsonObject... steps) { return new Converted(List.of(steps), ""); }
        static Converted kept(String reason) { return new Converted(List.of(), reason); }
    }

    /** An action under construction: literal arguments, and expressions and templates that fill null placeholders. */
    static final class Action {
        final JsonObject arguments = new JsonObject(), expressions = new JsonObject(), templates = new JsonObject();

        void put(String key, JsonElement value) { arguments.add(key, value); }

        void put(String key, String value) { arguments.addProperty(key, value); }

        void put(String key, Number value) { arguments.addProperty(key, value); }

        void text(String key, Text text) {
            if (!text.dynamic()) { arguments.addProperty(key, text.literal()); return; }
            arguments.add(key, JsonNull.INSTANCE);
            templates.addProperty("/" + key, text.template());
        }

        void expression(String key, String expression) {
            arguments.add(key, JsonNull.INSTANCE);
            expressions.addProperty("/" + key, expression);
        }

        JsonObject node(String capability) {
            JsonObject node = new JsonObject();
            node.addProperty("type", "action");
            node.addProperty("capability", capability);
            node.add("arguments", arguments);
            if (!expressions.isEmpty()) node.add("expressions", expressions);
            if (!templates.isEmpty()) node.add("templates", templates);
            return node;
        }
    }

    static JsonObject action(String capability, JsonObject arguments) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "action");
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        return node;
    }

    private static JsonObject plain(String type) {
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        return node;
    }

    // ---- Blocks ----

    List<JsonObject> block(List<Item> items, int from, Scope scope) {
        List<JsonObject> steps = new ArrayList<>();
        for (int index = from; index < items.size(); index++) {
            Item item = items.get(index);
            Command command = DenizenArgs.command(item.text());
            switch (command.name()) {
                case "stop" -> {
                    converter.outcome(scope, item, "command", "stop", true, "");
                    steps.add(plain("return"));
                    preserveItems(scope, items, index + 1, "never runs: after stop");
                    return steps;
                }
                case "determine" -> {
                    boolean passive = false;
                    for (Arg arg : command.args()) if (arg.is("passively")) passive = true;
                    if (item == scope.cancel) {
                        converter.outcome(scope, item, "command", "determine", true, "");
                        if (passive) continue;
                        steps.add(plain("return"));
                        preserveItems(scope, items, index + 1, "never runs: after determine");
                        return steps;
                    }
                    if (passive) {
                        converter.outcome(scope, item, "command", "determine", false, "determine passively sets a value and goes on; a routine returns only by ending");
                        hold(scope, item);
                        continue;
                    }
                    steps.addAll(determine(item, command, scope));
                    preserveItems(scope, items, index + 1, "never runs: after determine");
                    return steps;
                }
                case "if", "choose" -> {
                    int end = index + 1;
                    if (command.name().equals("if")) while (end < items.size() && DenizenArgs.command(items.get(end).text()).name().equals("else")) end++;
                    Optional<List<JsonObject>> chained = chain(items, index, end, scope);
                    if (chained.isPresent()) steps.addAll(chained.get());
                    else if (held(scope, item, items.subList(index, end))) return close(scope, items, end, steps);
                    index = end - 1;
                }
                case "else" -> {
                    preserveTree(scope, item, "else without a matching if");
                    if (held(scope, item, List.of(item))) return close(scope, items, index + 1, steps);
                }
                case "ratelimit" -> {
                    if (item.children().isPresent()) preserveTree(scope, item, "no Animus equivalent for the ratelimit block");
                    else {
                        Converted converted = ratelimit(item, command, scope);
                        converter.outcome(scope, item, "command", "ratelimit", converted.reason().isEmpty(), converted.reason());
                        if (converted.reason().isEmpty()) { steps.addAll(converted.steps()); continue; }
                    }
                    hold(scope, item);
                    return close(scope, items, index + 1, steps);
                }
                case "random" -> {
                    if (item.children().isEmpty() || !command.args().isEmpty() || item.children().get().items().isEmpty()) {
                        preserveTree(scope, item, "random needs a block of lines to choose from and no arguments");
                        if (item.children().isPresent() && stops(item.children().get().items(), Set.of())) { hold(scope, item); return close(scope, items, index + 1, steps); }
                        continue;
                    }
                    steps.addAll(random(item, scope));
                }
                case "repeat", "foreach", "while" -> {
                    Optional<List<JsonObject>> converted = item.children().isEmpty() ? control(item, command, scope) : loop(item, command, scope);
                    if (converted.isPresent()) steps.addAll(converted.get());
                    else if (held(scope, item, List.of(item))) return close(scope, items, index + 1, steps);
                }
                default -> {
                    if (item.children().isPresent()) {
                        preserveTree(scope, item, "no Animus equivalent for the " + command.name() + " block");
                        if (stops(item.children().get().items(), Set.of())) { hold(scope, item); return close(scope, items, index + 1, steps); }
                        continue;
                    }
                    Pending pending = new Pending();
                    Converted converted = simple(command, scope, pending);
                    converter.outcome(scope, item, "command", command.name(), converted.reason().isEmpty(), converted.reason());
                    if (converted.reason().isEmpty()) {
                        steps.addAll(pending.steps);
                        steps.addAll(converted.steps());
                        continue;
                    }
                    if (GUARDS.contains(command.name())) hold(scope, item);
                    if (stops(List.of(item), Set.of())) return close(scope, items, index + 1, steps);
                }
            }
        }
        return steps;
    }

    /** Commands that limit or end a run; when one does not convert, the behavior is held back from binding. */
    private static final Set<String> GUARDS = Set.of("stop", "determine", "queue", "inject", "ratelimit", "cooldown");

    /** Records a guard or stop that did not convert, which holds its behavior back from binding until an admin reviews it. */
    private static void hold(Scope scope, Item item) { scope.doc.held.add(scope.container.file() + ":" + item.line()); }

    /** Holds the behavior for a kept control block; true when the block may end the script, so the routine must end too. */
    private static boolean held(Scope scope, Item item, List<Item> trees) {
        hold(scope, item);
        return stops(trees, Set.of());
    }

    /**
     * Fails closed after a kept line that may end the script: the routine returns there, as if the guard had stopped
     * it, and every later line is kept, so nothing a guard protected runs unchecked.
     */
    private List<JsonObject> close(Scope scope, List<Item> items, int from, List<JsonObject> steps) {
        steps.add(plain("return"));
        preserveItems(scope, items, from, "never runs: after a line that did not convert and may stop the script; the routine ends there");
        return steps;
    }

    /**
     * Whether running these lines may end the script or the loop around them: a {@code stop}, a {@code determine}
     * that is not passive, a {@code queue} command, an {@code inject} (its task may stop), a {@code ratelimit}, or a
     * loop's stop or next that no loop inside these lines takes. {@code loops} are the loop kinds already inside.
     */
    static boolean stops(List<Item> items, Set<String> loops) {
        for (Item item : items) {
            Command command = DenizenArgs.command(item.text());
            String name = command.name();
            switch (name) {
                case "stop", "queue", "inject", "ratelimit" -> { return true; }
                case "determine" -> {
                    boolean passive = false;
                    for (Arg arg : command.args()) if (arg.is("passively")) passive = true;
                    if (!passive) return true;
                }
                case "foreach", "repeat", "while" -> {
                    if (item.children().isEmpty()) { if (!loops.contains(name)) return true; continue; }
                    Set<String> inner = new java.util.HashSet<>(loops);
                    inner.add(name);
                    if (stops(item.children().get().items(), inner)) return true;
                    continue;
                }
                default -> { }
            }
            if (item.children().isPresent() && stops(item.children().get().items(), loops)) return true;
        }
        return false;
    }

    void preserveTree(Scope scope, Item item, String reason) {
        converter.outcome(scope, item, "command", DenizenArgs.command(item.text()).name(), false, reason);
        if (item.children().isPresent()) preserveItems(scope, item.children().get().items(), 0, reason.startsWith("inside ") ? reason : "inside a block that is kept: " + reason);
    }

    void preserveItems(Scope scope, List<Item> items, int from, String reason) {
        for (int index = from; index < items.size(); index++) preserveTree(scope, items.get(index), reason);
    }

    /**
     * The {@code consume} condition of an NPC click event, or the {@code cancel} condition of a cancellable server event:
     * the event's switch guards, the top-level {@code if ...: stop} guards and the top-level {@code ratelimit} windows
     * before a top-level {@code determine cancelled}, all of which hold when the event reaches it. A rate-limited event
     * is therefore left alone, as in Denizen, where the script stops before it cancels. Other lines in between run
     * without deciding anything. Empty when the event never cancels or when something before the cancel cannot be decided
     * as the event happens: a block, a wait, or a guard that reads definitions, runs a procedure or checks a permission.
     */
    Optional<Consume> consume(List<Item> items, List<Cond> guards, Scope scope) {
        List<String> parts = new ArrayList<>();
        for (Cond guard : guards) {
            if (DenizenExpressions.capabilities(guard)) return Optional.empty();
            parts.add(DenizenExpressions.render(guard));
        }
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            Command command = DenizenArgs.command(item.text());
            if (command.name().equals("determine")) {
                boolean cancelled = false;
                for (Arg arg : command.args()) {
                    if (arg.is("cancelled")) cancelled = true;
                    else if (!arg.is("passively")) return Optional.empty();
                }
                if (!cancelled || item.children().isPresent()) return Optional.empty();
                return Optional.of(new Consume(item, parts.isEmpty() ? "true" : String.join(" && ", parts)));
            }
            if (command.name().equals("wait")) return Optional.empty();
            if (command.name().equals("ratelimit") && item.children().isEmpty()) {
                Optional<String> window = ratelimitFlag(item, command, scope);
                if (window.isEmpty()) return Optional.empty();
                parts.add("!has_flag('player', " + DenizenExpressions.quote(window.get()) + ")");
                continue;
            }
            if (item.children().isEmpty()) continue;
            if (!command.name().equals("if") || index + 1 < items.size() && DenizenArgs.command(items.get(index + 1).text()).name().equals("else")) return Optional.empty();
            List<Item> body = item.children().get().items();
            if (body.isEmpty() || !DenizenArgs.command(body.getLast().text()).name().equals("stop") || body.getLast().children().isPresent()) return Optional.empty();
            List<String> tokens = new ArrayList<>();
            DenizenArgs.split(item.text(), tokens, new ArrayList<>());
            Scope probe = Scope.of(new DenizenConverter.Doc(false), false, scope.container, Optional.empty(), -1, scope.context);
            Pending pending = new Pending();
            Result<Cond> condition = expressions.condition(tokens.subList(1, tokens.size()), probe, pending);
            if (condition.failed() || !pending.steps.isEmpty() || !probe.doc.variables.isEmpty() || !probe.doc.routines.isEmpty() || DenizenExpressions.capabilities(condition.value())) return Optional.empty();
            Cond stops = condition.value();
            String rendered = DenizenExpressions.render(stops);
            if (rendered.equals("true")) return Optional.empty();
            if (rendered.equals("false") || rendered.equals("!(true)")) continue;
            parts.add(stops instanceof DenizenExpressions.Not not ? DenizenExpressions.render(not.inner()) : "!(" + rendered + ")");
        }
        return Optional.empty();
    }

    /** {@code ratelimit <player> 2s}: a per-player flag that expires after the window, checked and set where the line runs. Expiry counts whole seconds. */
    private Converted ratelimit(Item item, Command command, Scope scope) {
        if (command.args().size() != 2 || !command.args().getFirst().prefix().isEmpty() || !playerTarget(command.args().getFirst().value()))
            return Converted.kept("ratelimit keyed on anything but the player has no Animus equivalent");
        OptionalLong ticks = DenizenArgs.ticks(raw(command.args().get(1)));
        if (ticks.isEmpty()) return Converted.kept("ratelimit needs a literal duration");
        long seconds = Math.max(1, (ticks.getAsLong() + 19) / 20);
        if (seconds > 31_536_000) return Converted.kept("ratelimit longer than a year");
        String flag = ratelimitFlag(item, command, scope).orElseThrow();
        JsonObject guard = new JsonObject();
        guard.addProperty("type", "condition");
        guard.addProperty("expression", "has_flag('player', " + DenizenExpressions.quote(flag) + ")");
        guard.add("then", plain("return"));
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", flag);
        arguments.addProperty("scope", "player");
        arguments.addProperty("expireSeconds", seconds);
        return Converted.of(guard, action("animus:set-flag", arguments));
    }

    /** The flag of a {@code ratelimit <player> <duration>} line that converts; empty for any other ratelimit. */
    private Optional<String> ratelimitFlag(Item item, Command command, Scope scope) {
        if (command.args().size() != 2 || !command.args().getFirst().prefix().isEmpty() || !playerTarget(command.args().getFirst().value())) return Optional.empty();
        OptionalLong ticks = DenizenArgs.ticks(raw(command.args().get(1)));
        if (ticks.isEmpty() || Math.max(1, (ticks.getAsLong() + 19) / 20) > 31_536_000) return Optional.empty();
        return Optional.of(converter.ratelimitFlag(scope.container, item.line()));
    }

    /** {@code determine} ends a task or procedure with its value; elsewhere it would change an event, so it is kept and the routine still ends. */
    private List<JsonObject> determine(Item item, Command command, Scope scope) {
        JsonObject node = plain("return");
        if (!List.of("task", "procedure").contains(scope.container.type())) {
            hold(scope, item);
            converter.outcome(scope, item, "command", "determine", false, scope.cancellable
                    ? "only a top-level determine cancelled after plain guards and rate limits cancels the event in Animus; this one is not reached that way; the routine still ends here"
                    : "determine changes the event's outcome, and Animus cannot cancel or change this event; the routine still ends here");
            return List.of(node);
        }
        if (command.args().size() > 1) {
            hold(scope, item);
            converter.outcome(scope, item, "command", "determine", false, "determine takes one value");
            return List.of(node);
        }
        Pending pending = new Pending();
        if (command.args().size() == 1) {
            Result<JsonElement> value = typedOperand(raw(command.args().getFirst()), scope, pending, (Character kind) -> {
                if (scope.container.type().equals("procedure")) converter.returned(scope.container, kind);
            });
            if (value.failed()) {
                hold(scope, item);
                converter.outcome(scope, item, "command", "determine", false, value.reason());
                return List.of(node);
            }
            node.add("value", value.value());
        }
        converter.outcome(scope, item, "command", "determine", true, "");
        List<JsonObject> steps = new ArrayList<>(pending.steps);
        steps.add(node);
        return steps;
    }

    /**
     * {@link DenizenExpressions#operand}, telling {@code kind} what a single tag gives, or {@code a} for anything else,
     * so a definition or a procedure keeps holding NPCs.
     */
    private Result<JsonElement> typedOperand(String raw, Scope scope, Pending pending, java.util.function.Consumer<Character> kind) {
        List<DenizenArgs.Segment> segments = DenizenArgs.segments(raw);
        if (segments.size() == 1 && segments.getFirst().tag() && DenizenExpressions.literalTag(DenizenArgs.tag(segments.getFirst().text()), false).isEmpty()) {
            Result<Expr> value = expressions.value(raw, scope, pending);
            if (value.failed()) return Result.fail(value.reason());
            if (value.value().text().length() > DenizenExpressions.MAXIMUM_EXPRESSION) return Result.fail("expression longer than " + DenizenExpressions.MAXIMUM_EXPRESSION + " characters");
            kind.accept(value.value().kind());
            JsonObject operand = new JsonObject();
            operand.addProperty("expression", value.value().text());
            return Result.ok(operand);
        }
        kind.accept('a');
        return expressions.operand(raw, scope, pending);
    }

    // ---- Chains ----

    /** An if/else-if/else chain or a choose block as nested condition nodes. */
    private Optional<List<JsonObject>> chain(List<Item> items, int start, int end, Scope scope) {
        Item head = items.get(start);
        Command command = DenizenArgs.command(head.text());
        List<Cond> conditions = new ArrayList<>();
        List<Item> bodies = new ArrayList<>();
        Optional<Item> otherwise = Optional.empty();
        Pending pending = new Pending();
        String reason = null;
        if (command.name().equals("choose")) {
            Result<String> subject = command.args().size() == 1 ? choice(raw(command.args().getFirst()), scope, pending) : Result.fail("choose takes one value");
            if (subject.failed()) reason = subject.reason();
            else if (head.children().isEmpty()) reason = "choose without cases";
            else {
                for (Item option : head.children().get().items()) {
                    Command when = DenizenArgs.command(option.text());
                    if (option.children().isEmpty()) { reason = "case without commands"; break; }
                    if (when.name().equals("default") && when.args().isEmpty()) { otherwise = Optional.of(option); continue; }
                    if (!when.name().equals("case") || when.args().isEmpty()) { reason = "choose holds a line that is not case or default"; break; }
                    Cond any = null;
                    for (Arg value : when.args()) {
                        Result<Expr> expr = expressions.value(raw(value), scope, pending);
                        if (expr.failed()) { reason = expr.reason(); break; }
                        Cond test = new DenizenExpressions.Atom("same(" + subject.value() + ", " + expr.value().text() + ")");
                        any = any == null ? test : new DenizenExpressions.Or(any, test);
                    }
                    if (reason != null) break;
                    conditions.add(any);
                    bodies.add(option);
                }
            }
        } else {
            for (int index = start; index < end; index++) {
                Item branch = items.get(index);
                List<String> tokens = new ArrayList<>();
                DenizenArgs.split(branch.text(), tokens, new ArrayList<>());
                boolean isElse = index > start;
                if (branch.children().isEmpty()) { reason = "if without a block"; break; }
                if (isElse && tokens.size() == 1) {
                    if (index != end - 1) { reason = "else before else if"; break; }
                    otherwise = Optional.of(branch);
                    continue;
                }
                if (isElse && !tokens.get(1).equalsIgnoreCase("if")) { reason = "unknown else form"; break; }
                Result<Cond> condition = expressions.condition(tokens.subList(isElse ? 2 : 1, tokens.size()), scope, pending);
                if (condition.failed()) { reason = condition.reason(); break; }
                conditions.add(condition.value());
                bodies.add(branch);
            }
        }
        if (reason != null || conditions.isEmpty()) {
            String why = reason == null ? "nothing to choose between" : reason;
            for (int index = start; index < end; index++) preserveTree(scope, items.get(index), why);
            return Optional.empty();
        }
        for (int index = start; index < end; index++) converter.outcome(scope, items.get(index), "command", DenizenArgs.command(items.get(index).text()).name(), true, "");
        if (command.name().equals("choose")) for (Item option : head.children().get().items()) converter.outcome(scope, option, "command", DenizenArgs.command(option.text()).name(), true, "");
        List<JsonObject> blocks = new ArrayList<>();
        for (Item body : bodies) blocks.add(DenizenConverter.sequence(block(body.children().get().items(), 0, scope)));
        Optional<JsonObject> tail = otherwise.map((Item body) -> DenizenConverter.sequence(block(body.children().get().items(), 0, scope)));
        for (int index = conditions.size() - 1; index >= 0; index--) tail = Optional.of(DenizenExpressions.build(conditions.get(index), blocks.get(index), tail));
        List<JsonObject> steps = new ArrayList<>(pending.steps);
        steps.add(tail.orElseThrow());
        return Optional.of(steps);
    }

    /**
     * A {@code random} block: one of its lines, each with any block it opens, picked evenly as the block runs, by a
     * number drawn into a temporary and a condition chain on it.
     */
    private List<JsonObject> random(Item item, Scope scope) {
        List<Item> options = item.children().get().items();
        converter.outcome(scope, item, "command", "random", true, "");
        String drawn = converter.temporary(scope);
        JsonObject set = plain("set");
        set.addProperty("variable", drawn);
        JsonObject operand = new JsonObject();
        operand.addProperty("expression", "random(1, " + options.size() + ")");
        set.add("value", operand);
        Optional<JsonObject> chain = Optional.empty();
        List<JsonObject> bodies = new ArrayList<>();
        for (Item option : options) bodies.add(DenizenConverter.sequence(block(List.of(option), 0, scope)));
        for (int index = options.size() - 1; index >= 0; index--)
            chain = Optional.of(DenizenExpressions.build(new DenizenExpressions.Atom("(" + drawn + " == " + (index + 1) + ")"), bodies.get(index), chain));
        return List.of(set, chain.orElseThrow());
    }

    /** The value a choose compares: a variable as it is, anything else read once into a temporary. */
    private Result<String> choice(String raw, Scope scope, Pending pending) {
        Result<Expr> subject = expressions.value(raw, scope, pending);
        if (subject.failed()) return Result.fail(subject.reason());
        if (subject.value().text().matches("[a-z][a-z0-9_]*")) return Result.ok(subject.value().text());
        String temporary = converter.temporary(scope);
        JsonObject set = plain("set");
        set.addProperty("variable", temporary);
        JsonObject operand = new JsonObject();
        operand.addProperty("expression", subject.value().text());
        set.add("value", operand);
        pending.steps.add(set);
        return Result.ok(temporary);
    }

    // ---- Loops ----

    private static boolean mentions(Item item, String text) {
        if (item.text().toLowerCase(Locale.ROOT).contains(text)) return true;
        if (item.children().isPresent()) for (Item child : item.children().get().items()) if (mentions(child, text)) return true;
        return false;
    }

    private static boolean mentionsBody(Item item, String text) {
        for (Item child : item.children().get().items()) if (mentions(child, text)) return true;
        return false;
    }

    private Optional<List<JsonObject>> loop(Item item, Command command, Scope scope) {
        Pending pending = new Pending();
        String kind = command.name();
        TreeMap<String, String> definitions = new TreeMap<>(scope.definitions);
        JsonObject node = plain(kind);
        String reason = null;
        switch (kind) {
            case "foreach" -> {
                String as = "value", key = "key";
                Optional<String> list = Optional.empty();
                boolean keyed = false;
                for (Arg arg : command.args()) {
                    if (arg.prefix().equals("as")) as = arg.value().toLowerCase(Locale.ROOT);
                    else if (arg.prefix().equals("key")) { key = arg.value().toLowerCase(Locale.ROOT); keyed = true; }
                    else if (list.isEmpty()) list = Optional.of(raw(arg));
                    else { reason = "foreach argument " + raw(arg) + " has no Animus equivalent"; break; }
                }
                if (reason != null) break;
                if (list.isEmpty()) { reason = "foreach without a list"; break; }
                Result<Expr> source = list.get().contains("<") && DenizenArgs.segments(list.get()).size() == 1 ? expressions.value(list.get(), scope, pending) : literalList(list.get(), scope, pending);
                if (source.failed()) { reason = source.reason(); break; }
                JsonObject operand = new JsonObject();
                operand.addProperty("expression", source.value().text());
                node.add("list", operand);
                String variable = DenizenConverter.definitionVariable(as);
                converter.declare(scope, variable, "any");
                node.addProperty("as", variable);
                definitions.remove(as);
                definitions.remove(key);
                // Each item of a list of NPCs is an NPC, so its attributes read NPC facts and flags.
                if (source.value().kind() == 'N') {
                    definitions.put(as, "@npc:" + variable);
                    node.addProperty("maximum", 1024);
                }
                // Each item of an inventory is an item, whose attributes read its material, amount and name.
                if (source.value().kind() == 'I') definitions.put(as, "@item:" + variable);
                if (mentionsBody(item, "[loop_index]")) {
                    String index = converter.loopIndex(scope);
                    node.addProperty("index", index);
                    definitions.put("loop_index", "(" + index + " + 1)");
                }
                if (keyed || mentionsBody(item, "[" + key + "]") || mentionsBody(item, "[" + key + ".")) {
                    String keyVariable = DenizenConverter.definitionVariable(key);
                    converter.declare(scope, keyVariable, "any");
                    node.addProperty("key", keyVariable);
                }
            }
            case "repeat" -> {
                String as = "value";
                Optional<String> count = Optional.empty();
                for (Arg arg : command.args()) {
                    if (arg.prefix().equals("as")) as = arg.value().toLowerCase(Locale.ROOT);
                    else if (arg.prefix().isEmpty() && count.isEmpty()) count = Optional.of(arg.value());
                    else { reason = "repeat argument " + raw(arg) + " has no Animus equivalent"; break; }
                }
                if (reason != null) break;
                if (count.isEmpty()) { reason = "repeat without a count"; break; }
                if (count.get().matches("[0-9]{1,4}")) {
                    int literal = Integer.parseInt(count.get());
                    if (literal > 1024) { reason = "repeat more than 1024 times"; break; }
                    if (literal <= 128) node.addProperty("count", literal);
                    else {
                        JsonObject operand = new JsonObject();
                        operand.addProperty("expression", Integer.toString(literal));
                        node.add("count", operand);
                        node.addProperty("maximum", literal);
                    }
                } else {
                    Result<Expr> expr = expressions.value(count.get(), scope, pending);
                    if (expr.failed()) { reason = expr.reason(); break; }
                    JsonObject operand = new JsonObject();
                    operand.addProperty("expression", "int(" + expr.value().text() + ")");
                    node.add("count", operand);
                    node.addProperty("maximum", 1024);
                }
                definitions.remove(as);
                if (mentionsBody(item, "[" + as + "]") || mentionsBody(item, "[loop_index]")) {
                    String index = converter.loopIndex(scope);
                    node.addProperty("index", index);
                    definitions.put(as, "(" + index + " + 1)");
                    definitions.put("loop_index", "(" + index + " + 1)");
                }
            }
            default -> {
                if (mentionsBody(item, "[loop_index]")) { reason = "while gives no pass number for <[loop_index]>"; break; }
                List<String> tokens = new ArrayList<>();
                DenizenArgs.split(item.text(), tokens, new ArrayList<>());
                Result<Cond> condition = expressions.condition(tokens.subList(1, tokens.size()), scope, pending);
                if (condition.failed()) { reason = condition.reason(); break; }
                if (DenizenExpressions.capabilities(condition.value())) { reason = "a while condition cannot check permissions; it must be one expression"; break; }
                if (!pending.steps.isEmpty()) { reason = "a while condition with procedure tags or map constants would be read only once"; break; }
                node.addProperty("expression", DenizenExpressions.render(condition.value()));
                node.addProperty("maximum", 1024);
            }
        }
        if (reason != null) { preserveTree(scope, item, reason); return Optional.empty(); }
        converter.outcome(scope, item, "command", kind, true, "");
        List<String> loops = new ArrayList<>(scope.loops);
        loops.add(kind);
        Scope inner = scope.loop(definitions, loops);
        node.add("body", DenizenConverter.sequence(block(item.children().get().items(), 0, inner)));
        List<JsonObject> steps = new ArrayList<>(pending.steps);
        steps.add(node);
        return Optional.of(steps);
    }

    /** A literal list such as {@code a|b|c}, items with tags allowed. */
    private Result<Expr> literalList(String raw, Scope scope, Pending pending) {
        List<String> items = new ArrayList<>();
        for (String piece : DenizenExpressions.pipes(raw)) {
            if (piece.isEmpty()) continue;
            Result<Expr> item = expressions.value(piece, scope, pending);
            if (item.failed()) return item;
            items.add(item.value().text());
        }
        return Result.ok(Expr.of("[" + String.join(", ", items) + "]", 'l'));
    }

    /** {@code foreach stop}, {@code repeat next} and so on: break or continue the innermost loop of that kind. */
    private Optional<List<JsonObject>> control(Item item, Command command, Scope scope) {
        String reason = null;
        String word = command.args().size() == 1 ? command.args().getFirst().value().toLowerCase(Locale.ROOT) : "";
        if (!word.equals("stop") && !word.equals("next")) reason = command.name() + " without a block or stop/next";
        else if (scope.loops.isEmpty()) reason = command.name() + " " + word + " outside a converted loop";
        else if (!scope.loops.getLast().equals(command.name())) reason = command.name() + " " + word + " would end an outer loop; Animus breaks only the innermost";
        if (reason != null) { preserveTree(scope, item, reason); return Optional.empty(); }
        converter.outcome(scope, item, "command", command.name(), true, "");
        return Optional.of(List.of(plain(word.equals("stop") ? "break" : "continue")));
    }

    // ---- Commands ----

    private Converted simple(Command command, Scope scope, Pending pending) {
        Optional<Converted> fanned = fanout(command, scope, pending);
        if (fanned.isPresent()) return fanned.get();
        return switch (command.name()) {
            case "note" -> DenizenConverter.noteOf(command).isPresent() ? Converted.of()
                    : Converted.kept("only a literal cuboid or ellipsoid noted as:name has an Animus equivalent, as the bounds of area triggers");
            case "narrate" -> narrate(command, scope, pending);
            // An interaction trigger already drops a player's clicks while their run of it is going, which is what engaging guards against.
            case "engage", "disengage" -> scope.server ? Converted.kept(command.name() + " needs an NPC") : Converted.of();
            case "cooldown" -> cooldown(command, scope);
            case "announce" -> announce(command, scope, pending);
            case "chat" -> chat(command, scope, pending);
            case "wait" -> waitFor(command, scope, pending);
            case "flag" -> flag(command, scope, pending);
            case "define" -> define(command, scope, pending);
            case "zap" -> zap(command, scope);
            case "run", "inject" -> call(command, scope, pending);
            case "execute" -> execute(command, scope, pending);
            case "give" -> give(command, scope, pending);
            case "take" -> take(command, scope, pending);
            case "look" -> look(command, scope, pending);
            case "walk" -> scope.server ? Converted.kept(DenizenConverter.NO_NPC) : walk(command);
            case "equip" -> scope.server ? Converted.kept(DenizenConverter.NO_NPC) : equip(command);
            case "trait" -> scope.server ? Converted.kept(DenizenConverter.NO_NPC) : trait(command);
            case "lookclose" -> lookclose(command, scope, pending);
            case "explode" -> explode(command, scope, pending);
            case "playsound" -> sound(command, scope, pending, false);
            case "animate" -> animate(command, scope, pending);
            case "playeffect" -> particle(command, scope, false);
            case "actionbar" -> actionbar(command, scope, pending);
            case "title" -> title(command, scope, pending);
            case "teleport" -> teleport(command, scope, pending);
            case "adjust" -> adjust(command, scope, pending);
            case "bossbar" -> bossbar(command, scope, pending);
            case "sidebar" -> sidebar(command, scope, pending);
            case "inventory" -> inventory(command);
            case "debug" -> Converted.of();
            case "trigger" -> Converted.kept("trigger settings change only in on assignment; the NPC's saved triggers are used");
            case "" -> Converted.kept("empty command line");
            default -> Converted.kept("no Animus equivalent for " + command.name());
        };
    }

    /** Commands that show something to players, and the actions they become, which take a {@code player} argument. */
    private static final Set<String> FANNED = Set.of("narrate", "actionbar", "title", "playsound", "playeffect");
    private static final Set<String> PLAYER_ACTIONS = Set.of("animus:message", "animus:actionbar", "animus:title", "animus:play-sound", "animus:spawn-particle");
    /** The definition a fan-out's player goes by inside its filters; {@code filter_value} is Denizen's own name in filter_tag. */
    private static final String FAN = "__targets";

    /**
     * A command for players other than the triggering one ({@code targets:<server.online_players>}, a filtered list, a
     * list in a flag or definition, or a sound played at {@code <[someone]>}): the same command for one player, run once
     * for each listed player in a {@code foreach} over {@code players(...)}, which keeps at most 100 of them. Trailing
     * {@code filter[...]}, {@code filter_tag[...]} and {@code exclude[...]} become a condition on each player.
     */
    private Optional<Converted> fanout(Command command, Scope scope, Pending pending) {
        if (!FANNED.contains(command.name())) return Optional.empty();
        List<Arg> args = command.args();
        int at = -1;
        String replacement = "<player>";
        boolean anchored = false;
        for (int index = 0; index < args.size() && at < 0; index++) {
            Arg arg = args.get(index);
            boolean targets = arg.prefix().equals("targets") && !playerTarget(arg.value()) && !(command.name().equals("narrate") && everyone(arg.value()));
            boolean place = command.name().equals("playsound") && arg.prefix().isEmpty() && arg.value().startsWith("<") && at(arg.value()).isEmpty()
                    && !arg.value().toLowerCase(Locale.ROOT).contains("location") && location(arg.value()).isEmpty();
            Optional<String[]> held = command.name().equals("playeffect") && arg.prefix().equals("at") || command.name().equals("playsound") && arg.prefix().isEmpty() ? heldPlace(arg.value()) : Optional.empty();
            if (targets || place) at = index;
            else if (held.isPresent()) {
                at = index;
                replacement = command.name().equals("playsound") ? "<player>" : "<player" + held.get()[1] + ">";
                // A particle, or a sound at a location, placed at a held player is still for the players the command
                // names, or everyone near when it names none, as in Denizen; only targets naming the same players make it theirs.
                String holder = held.get()[0];
                anchored = (command.name().equals("playeffect") || !held.get()[1].isEmpty())
                        && args.stream().noneMatch((Arg other) -> other.prefix().equals("targets") && other.value().equalsIgnoreCase(holder));
            }
        }
        if (at < 0) return Optional.empty();
        String value = heldPlace(args.get(at).value()).map((String[] held) -> held[0]).orElse(args.get(at).value());
        List<Part> filters = new ArrayList<>();
        List<DenizenArgs.Segment> segments = DenizenArgs.segments(value);
        Result<Expr> list;
        if (segments.size() == 1 && segments.getFirst().tag()) {
            DenizenArgs.Tag tag = DenizenArgs.tag(segments.getFirst().text());
            List<Part> parts = new ArrayList<>(tag.parts());
            while (tag.fallback().isEmpty() && parts.size() > 1 && parts.getLast().parameter().isPresent()
                    && List.of("filter", "filter_tag", "exclude").contains(parts.getLast().name().toLowerCase(Locale.ROOT))) filters.addFirst(parts.removeLast());
            List<String> written = new ArrayList<>();
            for (Part part : parts) written.add(part.name() + part.parameter().map((String parameter) -> "[" + parameter + "]").orElse(""));
            list = expressions.value("<" + String.join(".", written) + tag.fallback().map((String fallback) -> "||" + fallback).orElse("") + ">", scope, pending);
        } else {
            List<String> items = new ArrayList<>();
            list = null;
            for (String piece : DenizenExpressions.pipes(value)) {
                if (piece.isEmpty()) continue;
                Result<Expr> item = expressions.value(piece, scope, pending);
                if (item.failed()) { list = item; break; }
                items.add(item.value().text());
            }
            if (list == null) list = Result.ok(Expr.of("[" + String.join(", ", items) + "]", 'l'));
        }
        if (list.failed()) return Optional.of(Converted.kept("targets " + list.reason()));
        if (list.value().kind() == 'e' || list.value().kind() == 'N') {
            if (heldPlace(args.get(at).value()).isEmpty()) return Optional.of(Converted.kept(command.name() + " for NPCs has no viewer; only players see particles and hear sounds"));
            return Optional.of(atNpcs(command, at, list.value(), scope, pending));
        }
        String variable = converter.temporary(scope);
        TreeMap<String, String> definitions = new TreeMap<>(scope.definitions);
        definitions.put(FAN, "@" + variable);
        definitions.put("filter_value", "@" + variable);
        Scope inner = scope.loop(definitions, scope.loops);
        Pending body = new Pending();
        List<String> conditions = new ArrayList<>();
        for (Part filter : filters) {
            String name = filter.name().toLowerCase(Locale.ROOT), parameter = filter.parameter().orElseThrow();
            Result<Expr> test = expressions.value(name.equals("filter") ? "<[" + FAN + "]." + parameter + ">" : parameter, inner, body);
            if (test.failed()) return Optional.of(Converted.kept("targets " + name + ": " + test.reason()));
            conditions.add(name.equals("exclude") ? "!contains(players(" + test.value().text() + "), " + variable + ")" : DenizenExpressions.truth(test.value()));
        }
        List<Arg> replaced = new ArrayList<>(args);
        replaced.set(at, new Arg(args.get(at).prefix(), replacement, false));
        // Shown to the same players it is shown at: the player of each pass.
        for (int index = 0; index < args.size(); index++)
            if (index != at && args.get(index).prefix().equals("targets") && args.get(index).value().equalsIgnoreCase(value)) replaced.set(index, new Arg("targets", "<player>", false));
        Converted single = simple(new Command(command.name(), replaced), inner, body);
        if (!single.reason().isEmpty()) return Optional.of(single);
        List<JsonObject> actions = new ArrayList<>();
        for (JsonObject step : single.steps())
            if (!playerActions(step, actions, anchored ? 1 : 0, false)) return Optional.of(Converted.kept(command.name() + " for other players has no Animus equivalent"));
        boolean named = replaced.stream().anyMatch((Arg arg) -> arg.prefix().equals("targets"));
        for (JsonObject step : actions) {
            String argument = anchored ? "atPlayer" : "player";
            step.getAsJsonObject("arguments").add(argument, JsonNull.INSTANCE);
            if (anchored && !named) step.getAsJsonObject("arguments").addProperty("audience", "nearby");
            JsonObject filled = step.has("expressions") ? step.getAsJsonObject("expressions") : new JsonObject();
            filled.addProperty("/" + argument, variable);
            step.add("expressions", filled);
        }
        List<JsonObject> steps = new ArrayList<>(body.steps);
        steps.addAll(single.steps());
        JsonObject each = DenizenConverter.sequence(steps);
        if (!conditions.isEmpty()) each = DenizenExpressions.build(new DenizenExpressions.Atom(String.join(" && ", conditions)), each, Optional.empty());
        JsonObject loop = plain("foreach");
        JsonObject source = new JsonObject();
        source.addProperty("expression", "players(" + list.value().text() + ")");
        loop.add("list", source);
        loop.addProperty("as", variable);
        loop.addProperty("maximum", 100);
        loop.add("body", each);
        return Optional.of(Converted.of(loop));
    }

    /**
     * A particle or sound placed at an NPC held in a definition, or at each NPC of a list: the action at the NPC with
     * the {@code npc} argument, for the triggering player with {@code targets:<player>} or for everyone near.
     */
    private Converted atNpcs(Command command, int at, Expr npcs, Scope scope, Pending pending) {
        List<Arg> args = command.args();
        for (Arg arg : args) if (arg.prefix().equals("targets") && !playerTarget(arg.value())) return Converted.kept(command.name() + " for NPCs has no viewer; only players see particles and hear sounds");
        String[] held = heldPlace(args.get(at).value()).orElseThrow();
        List<Arg> replaced = new ArrayList<>(args);
        replaced.set(at, new Arg(args.get(at).prefix(), command.name().equals("playsound") ? "<npc>" : "<npc" + held[1] + ">", false));
        Command placed = new Command(command.name(), replaced);
        Converted single = command.name().equals("playeffect") ? particle(placed, scope, true) : sound(placed, scope, pending, true);
        if (!single.reason().isEmpty()) return single;
        JsonObject node = single.steps().getFirst();
        String each = npcs.kind() == 'e' ? npcs.text() : converter.temporary(scope);
        node.getAsJsonObject("arguments").add("npc", JsonNull.INSTANCE);
        JsonObject filled = node.has("expressions") ? node.getAsJsonObject("expressions") : new JsonObject();
        filled.addProperty("/npc", each);
        node.add("expressions", filled);
        if (npcs.kind() == 'e') return Converted.of(node);
        JsonObject loop = plain("foreach");
        JsonObject source = new JsonObject();
        source.addProperty("expression", npcs.text());
        loop.add("list", source);
        loop.addProperty("as", each);
        loop.addProperty("maximum", 1024);
        loop.add("body", node);
        return Converted.of(loop);
    }

    /**
     * Gathers the player actions of a converted command into {@code actions}, through at most {@code loops} fan-outs
     * over the players it is for and the filters inside them; false when anything else is there.
     */
    private static boolean playerActions(JsonObject node, List<JsonObject> actions, int loops, boolean looped) {
        switch (node.get("type").getAsString()) {
            case "action" -> {
                if (!PLAYER_ACTIONS.contains(node.get("capability").getAsString())) return false;
                actions.add(node);
                return true;
            }
            case "set" -> { return true; }
            case "sequence" -> {
                for (JsonElement step : node.getAsJsonArray("steps")) if (!playerActions(step.getAsJsonObject(), actions, loops, looped)) return false;
                return true;
            }
            case "condition" -> { return looped && !node.has("else") && playerActions(node.getAsJsonObject("then"), actions, loops, true); }
            case "foreach" -> { return loops > 0 && playerActions(node.getAsJsonObject("body"), actions, loops - 1, true); }
            default -> { return false; }
        }
    }

    static String raw(Arg arg) { return arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":" + arg.value(); }

    private static final java.util.regex.Pattern HELD_PLACE = java.util.regex.Pattern.compile("(<\\[[A-Za-z0-9_]+])((?:\\.location(?:\\.above(?:\\[[0-9.]{1,6}])?)?)?)>"),
            ABOVE = java.util.regex.Pattern.compile("<(?:player|npc)\\.location\\.above(?:\\[([0-9]{1,2}(?:\\.[0-9]{1,4})?)])?>");

    /**
     * A place held in a definition: {@code <[someone]>}, {@code <[someone].location>} or
     * {@code <[someone].location.above[2]>}, as the definition's tag and what follows it. The definition is read as
     * players, so a fan-out over it shows the effect at each player and skips anything that is no player.
     */
    private static Optional<String[]> heldPlace(String value) {
        java.util.regex.Matcher matcher = HELD_PLACE.matcher(value);
        return matcher.matches() ? Optional.of(new String[] {matcher.group(1) + ">", matcher.group(2)}) : Optional.empty();
    }

    private static boolean playerTarget(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.equals("<player>") || lowered.equals("player");
    }

    private static boolean everyone(String value) { return value.equalsIgnoreCase("<server.online_players>"); }

    private static boolean npcTarget(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.equals("<npc>") || lowered.equals("npc");
    }

    /** Text of an argument with a length limit on the literal and the template. */
    private Result<Text> text(String raw, Scope scope, Pending pending, boolean command, int limit) {
        Result<Text> text = expressions.text(raw, scope, pending, command, 0);
        if (text.failed()) return text;
        if (text.value().dynamic() ? text.value().template().length() > DenizenExpressions.MAXIMUM_EXPRESSION : text.value().literal().length() > limit)
            return Result.fail("text longer than " + (text.value().dynamic() ? DenizenExpressions.MAXIMUM_EXPRESSION : limit) + " characters");
        return text;
    }

    private Converted narrate(Command command, Scope scope, Pending pending) {
        List<String> words = new ArrayList<>();
        String to = "trigger";
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) { if (!arg.is("per_player")) words.add(arg.value()); continue; }
            if (arg.prefix().equals("targets") && playerTarget(arg.value())) continue;
            if (arg.prefix().equals("targets") && everyone(arg.value())) { to = "all"; continue; }
            if (arg.prefix().equals("targets")) return Converted.kept("narrate targets other than the player or every online player have no Animus equivalent");
            return Converted.kept("narrate argument " + arg.prefix() + ": has no Animus equivalent");
        }
        return message(words, to, scope, pending);
    }

    private Converted message(List<String> words, String to, Scope scope, Pending pending) {
        if (words.isEmpty()) return Converted.kept("nothing to say");
        Result<Text> text = text(String.join(" ", words), scope, pending, false, DenizenConverter.MAXIMUM_TEXT);
        if (text.failed()) return Converted.kept(text.reason());
        Action action = new Action();
        // A blank narrate is an empty chat line; a lone reset code sends one.
        if (!text.value().dynamic() && text.value().literal().isBlank()) action.put("text", "&r");
        else action.text("text", text.value());
        if (!to.equals("trigger")) action.put("to", to);
        return Converted.of(action.node("animus:message"));
    }

    private Converted announce(Command command, Scope scope, Pending pending) {
        List<String> words = new ArrayList<>();
        String to = "all";
        for (Arg arg : command.args()) {
            if (arg.is("to_console")) { to = "console"; continue; }
            if (arg.prefix().isEmpty() && !arg.quoted() && List.of("to_ops", "to_flagged", "to_permission").contains(arg.value().toLowerCase(Locale.ROOT)))
                return Converted.kept("announce " + arg.value() + " has no Animus audience");
            if (!arg.prefix().isEmpty()) return Converted.kept("announce argument " + arg.prefix() + ": has no Animus equivalent");
            words.add(arg.value());
        }
        return message(words, to, scope, pending);
    }

    private Converted chat(Command command, Scope scope, Pending pending) {
        if (scope.server) return Converted.kept("chat makes the NPC speak; " + DenizenConverter.NO_NPC);
        List<String> words = new ArrayList<>();
        boolean trigger = false;
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) { words.add(arg.value()); continue; }
            if (arg.prefix().equals("targets") && playerTarget(arg.value())) { trigger = true; continue; }
            if (arg.prefix().equals("talkers") && npcTarget(arg.value())) continue;
            return Converted.kept("chat argument " + arg.prefix() + ": has no Animus equivalent");
        }
        if (words.isEmpty()) return Converted.kept("nothing to say");
        Result<Text> text = text(String.join(" ", words), scope, pending, false, DenizenConverter.MAXIMUM_TEXT);
        if (text.failed()) return Converted.kept(text.reason());
        Action action = new Action();
        action.text("text", text.value());
        if (!trigger) action.put("audience", "nearby");
        return Converted.of(action.node("animus:say"));
    }

    /** {@code cooldown <duration> (global)} in an interact script becomes its click trigger's player or global cooldown. */
    private static Converted cooldown(Command command, Scope scope) {
        if (scope.interact.isEmpty()) return Converted.kept("cooldown belongs to an interact script");
        boolean global = false;
        OptionalLong ticks = OptionalLong.empty();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty() && arg.value().equalsIgnoreCase("global")) global = true;
            else if (arg.prefix().isEmpty() || arg.prefix().equals("duration")) ticks = DenizenArgs.ticks(arg.value());
            else return Converted.kept("cooldown " + arg.prefix() + ": has no Animus equivalent");
        }
        if (ticks.isEmpty()) return Converted.kept("cooldown needs a literal duration");
        if (ticks.getAsLong() > 72_000) return Converted.kept("cooldown longer than an hour");
        if (global) scope.doc.globalCooldown = Math.max(scope.doc.globalCooldown, ticks.getAsLong());
        else scope.doc.playerCooldown = Math.max(scope.doc.playerCooldown, ticks.getAsLong());
        return Converted.of();
    }

    private Converted waitFor(Command command, Scope scope, Pending pending) {
        if (command.args().size() > 1 || command.args().size() == 1 && !command.args().getFirst().prefix().isEmpty()) return Converted.kept("wait argument has no Animus equivalent");
        OptionalLong ticks = command.args().isEmpty() ? OptionalLong.of(60) : DenizenArgs.ticks(command.args().getFirst().value());
        Result<String> computed = ticks.isEmpty() ? ticks(command.args().getFirst().value(), scope, pending) : Result.ok("");
        if (computed.failed()) return Converted.kept("wait " + computed.reason());
        if (computed.value().matches("[0-9]{1,8}")) ticks = OptionalLong.of(Long.parseLong(computed.value()));
        if (ticks.isEmpty()) {
            JsonObject node = plain("wait");
            JsonObject operand = new JsonObject();
            operand.addProperty("expression", "clamp(" + computed.value() + ", 0, 72000)");
            node.add("ticks", operand);
            return Converted.of(node);
        }
        if (ticks.getAsLong() == 0) return Converted.of();
        if (ticks.getAsLong() > 72_000) return Converted.kept("wait longer than an hour");
        JsonObject node = plain("wait");
        node.addProperty("ticks", ticks.getAsLong());
        return Converted.of(node);
    }

    private static final Map<Character, Long> UNITS = Map.of('t', 1L, 's', 20L, 'm', 1200L, 'h', 72_000L, 'd', 1_728_000L);

    /**
     * A duration that is not written out, as an expression of ticks: a tag with a unit after it ({@code <[delay]>s}), a
     * tag giving a number of seconds, or a data value holding a written duration. Otherwise the reason.
     */
    private Result<String> ticks(String raw, Scope scope, Pending pending) {
        String value = raw.trim();
        long factor = 20;
        if (value.length() > 1 && UNITS.containsKey(Character.toLowerCase(value.charAt(value.length() - 1))) && value.charAt(value.length() - 2) == '>') {
            factor = UNITS.get(Character.toLowerCase(value.charAt(value.length() - 1)));
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("<") || DenizenArgs.tagEnd(value, 0) != value.length() - 1) return Result.fail("needs a literal duration");
        Result<Expr> expr = expressions.value(value, scope, pending);
        if (expr.failed()) return Result.fail(expr.reason());
        String text = expr.value().text();
        if (expr.value().kind() == 's' && text.matches("'[^'\\\\]*'")) {
            OptionalLong written = DenizenArgs.ticks(text.substring(1, text.length() - 1));
            if (written.isEmpty() || raw.trim().length() != value.length()) return Result.fail("needs a literal duration");
            return Result.ok(Long.toString(written.getAsLong()));
        }
        // A value without a unit after it is a duration as Denizen reads one: seconds, or text such as 5m.
        if (raw.trim().length() == value.length() && expr.value().kind() != 'n' && expr.value().kind() != 'd') return Result.ok("round(" + DenizenExpressions.seconds(expr.value()) + " * 20)");
        return Result.ok("round(" + DenizenExpressions.numeric(expr.value()) + " * " + factor + ")");
    }

    /** The new value of a Denizen data action ({@code ++}, {@code +:n}, {@code ->:v}, ...) on the current value. Null when it is a plain value. */
    private Result<String> dataAction(String action, String current, String currentNumber, Scope scope, Pending pending) {
        if (action.equals("++")) return Result.ok("(" + currentNumber + " + 1)");
        if (action.equals("--")) return Result.ok("(" + currentNumber + " - 1)");
        String[] operators = {"+:", "-:", "*:", "/:"};
        for (String operator : operators) {
            if (!action.startsWith(operator)) continue;
            Result<Expr> amount = expressions.value(action.substring(2), scope, pending);
            if (amount.failed()) return Result.fail(amount.reason());
            return Result.ok("(" + currentNumber + " " + operator.charAt(0) + " " + DenizenExpressions.numeric(amount.value()) + ")");
        }
        if (action.startsWith("->:")) {
            Result<Expr> item = expressions.value(action.substring(3), scope, pending);
            if (item.failed()) return Result.fail(item.reason());
            return Result.ok("append(" + current + ", " + item.value().text() + ")");
        }
        if (action.startsWith("<-:")) {
            Result<Expr> item = expressions.value(action.substring(3), scope, pending);
            if (item.failed()) return Result.fail(item.reason());
            String found = "index_of(" + current + ", " + item.value().text() + ")";
            return Result.ok("(" + found + " >= 0 ? remove(" + current + ", " + found + ") : " + current + ")");
        }
        if (action.startsWith("|:")) {
            String list = current;
            for (String piece : DenizenExpressions.pipes(action.substring(2))) {
                if (piece.isEmpty()) continue;
                if (piece.contains("<")) return Result.fail("adding a computed list with |: has no Animus expression; list items must be written out");
                list = "append(" + list + ", " + DenizenExpressions.literal(piece).text() + ")";
            }
            return Result.ok(list);
        }
        return Result.ok(null);
    }

    /**
     * {@code flag player|npc|server name:value}, and a flag on players held in a definition or listed online
     * ({@code flag <[target]> ...}, {@code flag <server.online_players> ...}): the same flag set for each player in a
     * {@code foreach} over {@code players(...)}, which holds one player or a list and skips anything that is no player.
     */
    private Converted flag(Command command, Scope scope, Pending pending) {
        if (command.args().size() < 2) return Converted.kept("flag needs a target and a name");
        Arg first = command.args().getFirst();
        String target = first.value().toLowerCase(Locale.ROOT).replace("<", "").replace(">", "");
        if (!first.prefix().isEmpty()) return Converted.kept("flags on other objects have no Animus equivalent");
        if (List.of("player", "npc", "server").contains(target)) {
            if (target.equals("npc") && scope.server) return Converted.kept("flag npc needs an NPC; " + DenizenConverter.NO_NPC);
            return flagOf(command, DenizenConverter.scopeOf(target), Optional.empty(), scope, pending);
        }
        if (!target.matches("\\[[a-z0-9_]+]|server\\.online_players")) return Converted.kept("flags on other objects have no Animus equivalent");
        if (target.startsWith("[") && scope.doc.kinds.getOrDefault(target.substring(1, target.length() - 1), 'a') == UNCONVERTED)
            return Converted.kept("flag on definition " + target.substring(1, target.length() - 1) + ", whose value did not convert");
        Result<Expr> who = expressions.value(first.value(), scope, pending);
        if (who.failed()) return Converted.kept(who.reason());
        if (who.value().kind() == 'L') return locationFlag(command, who.value().text(), scope, pending);
        if (who.value().kind() == 'e') return flagOf(command, "npc", Optional.of(who.value().text()), scope, pending);
        String each = converter.temporary(scope);
        Converted single = flagOf(command, who.value().kind() == 'N' ? "npc" : "player", Optional.of(each), scope, pending);
        if (!single.reason().isEmpty()) return single;
        JsonObject loop = plain("foreach");
        JsonObject source = new JsonObject();
        source.addProperty("expression", who.value().kind() == 'N' ? who.value().text() : "players(" + who.value().text() + ")");
        loop.add("list", source);
        loop.addProperty("as", each);
        loop.addProperty("maximum", who.value().kind() == 'N' ? 1024 : 100);
        loop.add("body", DenizenConverter.sequence(single.steps()));
        return Converted.of(loop);
    }

    /** The kind a definition has when its define did not convert. */
    static final char UNCONVERTED = '?';

    /**
     * A flag on a location held in a definition: Denizen keeps it on the block there, and Animus in a global flag named
     * {@code locations-<name>} that maps each block, written {@code x,y,z,world}, to its value. Expiry has no per-block form.
     */
    private Converted locationFlag(Command command, String location, Scope scope, Pending pending) {
        if (command.args().size() > 2) return Converted.kept("a flag on a location takes no expire");
        String rawName = raw(command.args().get(1));
        int colon = rawName.indexOf(':');
        String denizen = (colon < 0 ? rawName : rawName.substring(0, colon)).toLowerCase(Locale.ROOT);
        String value = colon < 0 ? "" : rawName.substring(colon + 1);
        Result<String> name = expressions.locationFlagName(denizen, scope, pending);
        if (name.failed()) return Converted.kept(name.reason());
        String key = DenizenExpressions.blockKey(location);
        String blocks = expressions.locationBlocks(name.value(), scope, pending);
        String written;
        if (colon >= 0 && value.equals("!")) written = "remove(" + blocks + ", " + key + ")";
        else {
            String held = "get(" + blocks + ", " + key + ", ";
            Result<String> computed = colon < 0 ? Result.ok(null) : dataAction(value, held + "[])", "num(" + held + "0))", scope, pending);
            if (computed.failed()) return Converted.kept(computed.reason());
            String stored = computed.value();
            if (stored == null && colon < 0) stored = "true";
            if (stored == null) {
                Result<Expr> operand = expressions.value(value, scope, pending);
                if (operand.failed()) return Converted.kept(operand.reason());
                stored = operand.value().text();
            }
            written = "put(" + blocks + ", " + key + ", " + stored + ")";
        }
        if (written.length() > DenizenExpressions.MAXIMUM_EXPRESSION) return Converted.kept("expression longer than 4096 characters");
        Action action = new Action();
        action.expression("name", name.value());
        action.put("scope", "global");
        action.expression("value", written);
        return Converted.of(action.node("animus:set-flag"));
    }

    /** One flag write of {@code scopeName}; {@code other} holds another player, or NPC for an NPC flag, whose flag it is. */
    private Converted flagOf(Command command, String scopeName, Optional<String> other, Scope scope, Pending pending) {
        String argument = scopeName.equals("npc") ? "npc" : "player";
        Optional<Long> expire = Optional.empty();
        Optional<String> computedExpire = Optional.empty();
        for (int index = 2; index < command.args().size(); index++) {
            Arg arg = command.args().get(index);
            if (!arg.prefix().equals("expire") && !arg.prefix().equals("duration")) return Converted.kept("flag argument " + raw(arg) + " has no Animus equivalent");
            OptionalLong ticks = DenizenArgs.ticks(arg.value());
            Result<String> computed = ticks.isEmpty() ? ticks(arg.value(), scope, pending) : Result.ok("");
            if (computed.failed()) return Converted.kept("flag expire " + computed.reason());
            if (computed.value().matches("[0-9]{1,8}")) ticks = OptionalLong.of(Long.parseLong(computed.value()));
            if (ticks.isEmpty()) {
                computedExpire = Optional.of("clamp(ceil(" + computed.value() + " / 20), 1, 31536000)");
                continue;
            }
            long seconds = Math.max(1, (ticks.getAsLong() + 19) / 20);
            if (seconds > 31_536_000) return Converted.kept("flag expire longer than a year");
            expire = Optional.of(seconds);
        }
        String rawName = raw(command.args().get(1));
        int colon = rawName.indexOf(':');
        String denizen = (colon < 0 ? rawName : rawName.substring(0, colon)).toLowerCase(Locale.ROOT);
        String value = colon < 0 ? "" : rawName.substring(colon + 1);
        // A name joined with tags, such as npc_done_<npc.id>, is computed as the run writes it, like the reads of it.
        Result<String> named = DenizenArgs.segments(denizen).stream().anyMatch(DenizenArgs.Segment::tag)
                ? expressions.flagNameExpression(denizen, scope, pending) : Result.fail("");
        Optional<String> name = DenizenConverter.flagName(denizen);
        if (named.failed() && (denizen.contains("<") || denizen.contains("[") || name.isEmpty())) return Converted.kept("flag name " + denizen + " cannot be an Animus flag name");
        String nameExpression = named.failed() ? DenizenExpressions.quote(name.orElseThrow()) : named.value();
        Action action = new Action();
        if (named.failed()) action.put("name", name.get());
        else action.expression("name", nameExpression);
        action.put("scope", scopeName);
        if (colon >= 0 && value.equals("!")) {
            if (expire.isPresent() || computedExpire.isPresent()) return Converted.kept("clearing a flag takes no expire");
            action.put("clear", new JsonPrimitive(true));
            other.ifPresent((String who) -> action.expression(argument, who));
            return Converted.of(action.node("animus:set-flag"));
        }
        if (colon >= 0) {
            String read = other.isPresent() ? argument + "_flag(" + other.get() + ", " + nameExpression + ", " : "flag(" + DenizenExpressions.quote(scopeName) + ", " + nameExpression + ", ";
            Result<String> computed = dataAction(value, read + "[])", "num(" + read + "0))", scope, pending);
            if (computed.failed()) return Converted.kept(computed.reason());
            if (computed.value() != null) {
                if (computed.value().length() > DenizenExpressions.MAXIMUM_EXPRESSION) return Converted.kept("expression longer than 4096 characters");
                action.expression("value", computed.value());
            } else {
                Result<JsonElement> operand = expressions.operand(value, scope, pending);
                if (operand.failed()) return Converted.kept(operand.reason());
                if (operand.value().isJsonObject() && operand.value().getAsJsonObject().has("expression")) action.expression("value", operand.value().getAsJsonObject().get("expression").getAsString());
                else if (operand.value().isJsonObject()) {
                    action.put("value", JsonNull.INSTANCE);
                    action.templates.addProperty("/value", operand.value().getAsJsonObject().get("template").getAsString());
                } else action.put("value", operand.value());
            }
        }
        expire.ifPresent((Long seconds) -> action.put("expireSeconds", seconds));
        computedExpire.ifPresent((String seconds) -> action.expression("expireSeconds", seconds));
        other.ifPresent((String who) -> action.expression(argument, who));
        return Converted.of(action.node("animus:set-flag"));
    }

    private Converted define(Command command, Scope scope, Pending pending) {
        if (command.args().isEmpty()) return Converted.kept("define without a name");
        Arg first = command.args().size() == 1 ? DenizenArgs.prefixed(command.args().getFirst()) : command.args().getFirst();
        String name, value;
        boolean action = false;
        if (!first.prefix().isEmpty() && command.args().size() == 1) {
            name = first.prefix();
            value = first.value();
            action = true;
        } else {
            if (!first.prefix().isEmpty() || command.args().size() != 2) return Converted.kept("define takes a name and one value");
            name = first.value().toLowerCase(Locale.ROOT);
            value = raw(command.args().get(1));
        }
        if (name.contains("<") || name.contains(".") || name.contains("[")) return Converted.kept("defining a computed name or a map key has no Animus equivalent");
        String variable = DenizenConverter.definitionVariable(name);
        converter.declare(scope, variable, "any");
        JsonObject set = plain("set");
        set.addProperty("variable", variable);
        if (action) {
            if (value.equals("!")) { set.addProperty("value", ""); return Converted.of(set); }
            // An unset definition is empty text: a list action starts from an empty list and a number from zero, as in Denizen.
            String unset = "same(text(" + variable + "), '')";
            Result<String> computed = dataAction(value, "(" + unset + " ? [] : " + variable + ")", "num((" + unset + " ? 0 : " + variable + "))", scope, pending);
            if (computed.failed()) return Converted.kept(computed.reason());
            if (computed.value() != null) {
                JsonObject operand = new JsonObject();
                operand.addProperty("expression", computed.value());
                set.add("value", operand);
                return Converted.of(set);
            }
        }
        Result<JsonElement> operand = typedOperand(value, scope, pending, (Character kind) -> {
            if (kind == 'e' || kind == 'N' || kind == 'L' || kind == 'i' || kind == 'I') scope.doc.kinds.put(name, kind);
            else scope.doc.kinds.remove(name);
        });
        // A definition whose value did not convert holds nothing, so a flag on it is kept rather than set on nobody.
        if (operand.failed()) { scope.doc.kinds.put(name, UNCONVERTED); return Converted.kept(operand.reason()); }
        set.add("value", operand.value());
        return Converted.of(set);
    }

    private Converted zap(Command command, Scope scope) {
        if (scope.server) return Converted.kept("zap moves an interact step with an NPC; " + DenizenConverter.NO_NPC);
        Optional<Interact> target = scope.interact;
        String step = null;
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("script")) { target = Optional.ofNullable(converter.interacts.get(arg.value().toLowerCase(Locale.ROOT))); continue; }
            if (arg.prefix().equals("step")) { step = arg.value(); continue; }
            if (!arg.prefix().isEmpty()) return Converted.kept("zap argument " + arg.prefix() + ": has no Animus equivalent");
            if (converter.interacts.containsKey(arg.value().toLowerCase(Locale.ROOT)) && step == null && target.equals(scope.interact)) { target = Optional.of(converter.interacts.get(arg.value().toLowerCase(Locale.ROOT))); continue; }
            if (step != null) return Converted.kept("zap with a duration has no Animus equivalent");
            step = arg.value();
        }
        if (target.isEmpty()) return Converted.kept("zap outside an interact script needs the script's name");
        Interact interact = target.get();
        int stage;
        if (step == null) {
            if (scope.interact.isEmpty() || scope.interact.get() != interact || scope.step < 0 || scope.step + 1 >= interact.order().size()) return Converted.kept("zap to the next step needs a following step");
            stage = interact.stage(interact.order().get(scope.step + 1)).orElseThrow();
        } else {
            if (step.contains("<")) return Converted.kept("zap to a computed step has no Animus equivalent");
            Optional<Integer> found = interact.stage(step);
            if (found.isEmpty()) return Converted.kept("no step " + step + " in " + interact.container().name());
            stage = found.get();
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", interact.quest());
        arguments.addProperty("scope", "relationship");
        arguments.addProperty("value", stage);
        return Converted.of(action("animus:set-quest-state", arguments));
    }

    /** {@code run} and {@code inject}: a call of the task's routine, with definitions as arguments and a saved determination as its result. */
    private Converted call(Command command, Scope scope, Pending pending) {
        Optional<String> name = Optional.empty();
        Optional<String> path = Optional.empty();
        Optional<String> save = Optional.empty();
        List<String> positional = new ArrayList<>();
        LinkedHashMap<String, String> named = new LinkedHashMap<>();
        Optional<String> npcFor = Optional.empty(), playerFor = Optional.empty();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) {
                if (arg.is("instantly") || arg.is("local") || arg.is("locally")) continue;
                if (!arg.quoted() && arg.value().matches("(?i)def\\.[a-z0-9_]+:.*")) {
                    int colon = arg.value().indexOf(':');
                    named.put(arg.value().substring(4, colon).toLowerCase(Locale.ROOT), arg.value().substring(colon + 1));
                    continue;
                }
                if (name.isPresent()) return Converted.kept("argument " + arg.value() + " has no Animus equivalent");
                name = Optional.of(arg.value());
                continue;
            }
            switch (arg.prefix()) {
                case "path" -> path = Optional.of(arg.value());
                case "npc" -> { if (!arg.value().equalsIgnoreCase("<npc>")) npcFor = Optional.of(arg.value()); }
                case "player" -> { if (!arg.value().equalsIgnoreCase("<player>")) playerFor = Optional.of(arg.value()); }
                case "def", "definitions", "context" -> positional.addAll(DenizenExpressions.pipes(arg.value()));
                case "defmap" -> {
                    String map = arg.value();
                    if (!map.toLowerCase(Locale.ROOT).startsWith("<map[") || !map.endsWith("]>") || map.substring(5, map.length() - 2).contains("<"))
                        return Converted.kept("defmap needs a literal <map[...]>");
                    for (String entry : map.substring(5, map.length() - 2).split(";", -1)) {
                        int equals = entry.indexOf('=');
                        if (equals <= 0) return Converted.kept("defmap entry without a key");
                        named.put(entry.substring(0, equals).toLowerCase(Locale.ROOT), entry.substring(equals + 1));
                    }
                }
                case "save" -> save = Optional.of(arg.value().toLowerCase(Locale.ROOT));
                default -> { return Converted.kept("argument " + arg.prefix() + ": has no Animus equivalent"); }
            }
        }
        JsonObject subject = new JsonObject();
        if (playerFor.isPresent() || npcFor.isPresent()) {
            if (command.name().equals("inject")) return Converted.kept("inject runs in place for the same player and NPC");
            Optional<String> problem = subject(playerFor, npcFor, subject, scope, pending);
            if (problem.isPresent()) return Converted.kept(problem.get());
        }
        if (name.isPresent() && name.get().contains("<")) return dynamic(name.get(), path, save, positional, named, subject, scope, pending);
        Container task;
        if (name.isEmpty() && path.isPresent() && scope.container.type().equals("task")) task = scope.container;
        else {
            if (name.isEmpty()) return Converted.kept("running a computed script has no Animus equivalent");
            task = converter.containers.get(name.get().toLowerCase(Locale.ROOT));
        }
        if (task == null) return Converted.kept("no script named " + name.orElse(""));
        if (command.name().equals("inject") && task.type().equals("task")) {
            Optional<Node> injected = task.body().get(path.map((String value) -> value.toLowerCase(Locale.ROOT)).orElse("script"));
            if (injected.isPresent() && injected.get() instanceof Sequence lines && stops(lines.items(), Set.of())) {
                // A stop there ends this script too, so the lines run in place, sharing this script's definitions.
                if (save.isPresent() || !positional.isEmpty() || !named.isEmpty())
                    return Converted.kept("inject runs " + task.name() + " inside this script, so a stop there ends this script too; an Animus call ends only the task");
                String routine = converter.routineFor(task, path);
                if (scope.calling.contains(routine)) return Converted.kept("recursive inject has no Animus equivalent");
                scope.calling.add(routine);
                Scope inside = scope.injected(task);
                List<JsonObject> steps = block(lines.items(), 0, inside);
                scope.calling.remove(routine);
                return new Converted(steps, "");
            }
        }
        return callTask(task, path, save, positional, named, subject, scope, pending);
    }

    /**
     * The player and NPC a run for someone else is for, as the call node's {@code player} and {@code npc}: a player or
     * NPC held in a definition or read by a tag. Empty when both convert, otherwise the reason.
     */
    private Optional<String> subject(Optional<String> player, Optional<String> npc, JsonObject subject, Scope scope, Pending pending) {
        if (player.isPresent()) {
            Result<Expr> who = expressions.value(player.get(), scope, pending);
            if (who.failed()) return Optional.of(who.reason());
            if (who.value().kind() == 'e' || who.value().kind() == 'N') return Optional.of("player: names an NPC");
            JsonObject operand = new JsonObject();
            operand.addProperty("expression", "get(players(" + who.value().text() + "), 0, '')");
            subject.add("player", operand);
        }
        if (npc.isPresent()) {
            Result<Expr> which = expressions.value(npc.get(), scope, pending);
            if (which.failed()) return Optional.of(which.reason());
            if (which.value().kind() == 'N' || which.value().kind() == 'l') return Optional.of("npc: names a list; run the task for each NPC in a foreach");
            JsonObject operand = new JsonObject();
            operand.addProperty("expression", which.value().kind() == 'e' ? which.value().text() : "replace(text(" + which.value().text() + "), 'n@', '')");
            subject.add("npc", operand);
        }
        return Optional.empty();
    }

    /**
     * {@code run <npc.flag[name]>}: the script the flag names, chosen when the line runs from the task scripts that a
     * {@code flag} command or an NPC's saved flags give that flag. A value naming none of them runs nothing.
     */
    private Converted dynamic(String raw, Optional<String> path, Optional<String> save, List<String> positional, Map<String, String> named, JsonObject subject, Scope scope, Pending pending) {
        if (!raw.startsWith("<") || !raw.endsWith(">") || DenizenArgs.tagEnd(raw, 0) != raw.length() - 1) return Converted.kept("running a computed script has no Animus equivalent");
        DenizenArgs.Tag tag = DenizenArgs.tag(raw.substring(1, raw.length() - 1));
        String base = tag.base().toLowerCase(Locale.ROOT);
        String flagScope = base.equals("npc") ? "npc" : base.equals("player") ? "player" : base.equals("server") ? "global" : "";
        if (flagScope.isEmpty() || tag.parts().size() != 2 || tag.parts().getFirst().parameter().isPresent() || !tag.parts().get(1).name().equalsIgnoreCase("flag")
                || tag.parts().get(1).parameter().isEmpty() || tag.parts().get(1).parameter().get().contains("<"))
            return Converted.kept("running a computed script has no Animus equivalent");
        if (flagScope.equals("npc") && scope.server) return Converted.kept(DenizenConverter.NO_NPC);
        if (save.isPresent() || path.isPresent()) return Converted.kept("save: and path: of a script chosen by a flag have no Animus equivalent");
        Optional<String> flag = DenizenConverter.flagName(tag.parts().get(1).parameter().get());
        if (flag.isEmpty()) return Converted.kept("flag name " + tag.parts().get(1).parameter().get() + " cannot be an Animus flag name");
        List<Container> tasks = new ArrayList<>();
        for (String value : converter.flagValues(flagScope, flag.get())) {
            Container task = converter.containers.get(value);
            if (task != null && task.type().equals("task")) tasks.add(task);
        }
        if (tasks.isEmpty()) return Converted.kept("no script gives the flag " + flag.get() + " the name of a task script");
        String read = "lower(text(flag(" + DenizenExpressions.quote(flagScope) + ", " + DenizenExpressions.quote(flag.get()) + ", '')))";
        List<JsonObject> calls = new ArrayList<>();
        for (Container task : tasks) {
            Converted call = callTask(task, Optional.empty(), Optional.empty(), positional, named, subject, scope, pending);
            if (!call.reason().isEmpty()) return call;
            calls.add(DenizenConverter.sequence(call.steps()));
        }
        Optional<JsonObject> chain = Optional.empty();
        for (int index = tasks.size() - 1; index >= 0; index--) {
            Cond chosen = new DenizenExpressions.Atom("same(" + read + ", " + DenizenExpressions.quote(tasks.get(index).name().toLowerCase(Locale.ROOT)) + ")");
            chain = Optional.of(DenizenExpressions.build(chosen, calls.get(index), chain));
        }
        return Converted.of(chain.orElseThrow());
    }

    private Converted callTask(Container task, Optional<String> path, Optional<String> save, List<String> positional, Map<String, String> named, JsonObject subject, Scope scope, Pending pending) {
        if (!task.type().equals("task")) return Converted.kept("only task scripts can become routines");
        String key = path.map((String value) -> value.toLowerCase(Locale.ROOT)).orElse("script");
        Optional<Node> body = task.body().get(key);
        if (body.isEmpty() || !(body.get() instanceof Sequence sequence)) return Converted.kept("task " + task.name() + " has no " + key + " list");
        List<String> names = DenizenConverter.definitionNames(task);
        if (positional.size() > names.size()) return Converted.kept("task " + task.name() + " takes " + names.size() + " definitions");
        JsonObject arguments = new JsonObject();
        for (int index = 0; index < positional.size(); index++) {
            Converted problem = argument(arguments, names.get(index), positional.get(index), scope, pending);
            if (problem != null) return problem;
        }
        for (Map.Entry<String, String> entry : named.entrySet()) {
            Converted problem = argument(arguments, entry.getKey(), entry.getValue(), scope, pending);
            if (problem != null) return problem;
        }
        // A server behavior running a task for an NPC converts it again as an NPC's routine, since it then has an NPC.
        boolean asNpc = scope.server && subject.has("npc");
        String routine = converter.routineFor(task, path);
        if (asNpc) routine = (routine.length() > 60 ? routine.substring(0, 60) : routine) + "-npc";
        if (scope.calling.contains(routine)) return Converted.kept("recursive task calls have no Animus equivalent");
        JsonObject call = plain("call");
        call.addProperty("routine", routine);
        if (!arguments.isEmpty()) call.add("arguments", arguments);
        for (Map.Entry<String, JsonElement> entry : subject.entrySet()) call.add(entry.getKey(), entry.getValue().deepCopy());
        if (save.isPresent()) {
            String result = DenizenConverter.variable("r_", save.get());
            converter.declare(scope, result, "any");
            call.addProperty("result", result);
        }
        if (!scope.doc.routines.containsKey(routine)) {
            if (scope.doc.routines.size() >= BehaviorLimits.ROUTINES) return Converted.kept("more than 32 routines in one behavior");
            scope.doc.routines.put(routine, new JsonObject());
            scope.calling.add(routine);
            List<JsonObject> steps = block(sequence.items(), 0, asNpc ? scope.in(task).withNpc() : scope.in(task));
            scope.calling.remove(routine);
            scope.doc.routines.put(routine, DenizenConverter.sequence(steps));
        }
        save.ifPresent((String saved) -> scope.saves.put(saved, call.get("result").getAsString()));
        return Converted.of(call);
    }

    private Converted argument(JsonObject arguments, String name, String raw, Scope scope, Pending pending) {
        Result<JsonElement> operand = expressions.operand(raw, scope, pending);
        if (operand.failed()) return Converted.kept(operand.reason());
        String variable = DenizenConverter.definitionVariable(name);
        converter.declare(scope, variable, "any");
        arguments.add(variable, operand.value());
        return null;
    }

    private Converted execute(Command command, Scope scope, Pending pending) {
        String as = null;
        boolean operator = false;
        List<String> words = new ArrayList<>();
        for (Arg arg : command.args()) {
            if (arg.is("as_server")) { as = "console"; continue; }
            if (arg.is("as_player")) { as = "player"; continue; }
            // as_op makes the player an operator for the one line, as asOperator does.
            if (arg.is("as_op")) { as = "player"; operator = true; continue; }
            if (arg.is("as_npc")) return Converted.kept("commands run as the NPC have no Animus equivalent");
            if (arg.is("silent")) continue;
            words.add(raw(arg));
        }
        if (as == null) return Converted.kept("execute needs as_server, as_op or as_player");
        String line = String.join(" ", words).trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] split = line.split("\\s+");
        if (split[0].equalsIgnoreCase("sentinel")) return sentinel(List.of(split).subList(1, split.length), scope, pending);
        Result<Text> text = text(line, scope, pending, true, 1024);
        if (text.failed()) return Converted.kept(text.reason());
        if (!text.value().dynamic() && text.value().literal().isBlank()) return Converted.kept("command is empty");
        Action action = new Action();
        action.text("command", text.value());
        if (as.equals("player")) action.put("as", "player");
        if (operator) action.put("asOperator", new JsonPrimitive(true));
        return Converted.of(action.node("animus:command"));
    }

    /**
     * A {@code /sentinel} command. Sentinel is replaced by animus:combat, so the forms that act on the NPC now become
     * combat-control on the behavior's own NPC: attacking, forgiving and guarding the triggering player. Target types and
     * Sentinel settings are animus:combat settings, so those lines are kept with the reason.
     */
    private Converted sentinel(List<String> words, Scope scope, Pending pending) {
        if (scope.server) return Converted.kept("sentinel acts on an NPC, and " + DenizenConverter.NO_NPC);
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            String id = word.equalsIgnoreCase("--id") && i + 1 < words.size() ? words.get(++i)
                    : word.toLowerCase(Locale.ROOT).startsWith("--id=") ? word.substring(5) : null;
            if (id == null) { rest.add(word); continue; }
            if (!id.equalsIgnoreCase("<npc.id>") && !id.equalsIgnoreCase("<npc>")) return Converted.kept("sentinel names another NPC with --id; animus:combat acts on the behavior's own NPC");
        }
        String operation = rest.isEmpty() ? "" : rest.getFirst().toLowerCase(Locale.ROOT);
        List<String> arguments = rest.isEmpty() ? List.of() : rest.subList(1, rest.size());
        boolean player = arguments.size() == 1 && triggeringPlayer(arguments.getFirst());
        Optional<String> held = arguments.size() == 1 ? heldPlayer(arguments.getFirst()) : Optional.empty();
        Action action = new Action();
        switch (operation) {
            case "addtarget", "removetarget" -> {
                if (!player && held.isEmpty()) return Converted.kept("sentinel " + String.join(" ", rest) + ": target types are animus:combat settings; set them in the NPC's animus:combat targets");
                // Taking the player off the target list stops the attack on them; combat-control releases the current target.
                action.put("operation", operation.equals("addtarget") ? "target" : "release");
                if (operation.equals("addtarget") && player) action.put("target", "player");
                else if (operation.equals("addtarget")) {
                    Result<Expr> who = expressions.value(held.get(), scope, pending);
                    if (who.failed()) return Converted.kept(who.reason());
                    action.expression("target", "get(players(" + who.value().text() + "), 0, '')");
                }
            }
            case "forgive", "forgiveall" -> {
                if (!arguments.isEmpty()) return Converted.kept("sentinel " + operation + " with arguments has no combat action");
                action.put("operation", "release");
            }
            case "guard" -> {
                if (!arguments.isEmpty() && !player) return Converted.kept("sentinel guard guards only the triggering player in Animus");
                action.put("operation", "guard");
                if (player) action.put("target", "player");
            }
            case "" -> { return Converted.kept("sentinel without a subcommand"); }
            default -> { return Converted.kept("sentinel " + operation + " has no combat action; set it in the NPC's animus:combat settings"); }
        }
        return Converted.of(action.node("animus:combat-control"));
    }

    /** A player held in a definition, as Sentinel's target syntax names them: {@code player:<[target].name>} reads {@code <[target]>}. */
    private static Optional<String> heldPlayer(String value) {
        java.util.regex.Matcher matcher = HELD_PLAYER.matcher(value);
        return matcher.matches() ? Optional.of("<[" + matcher.group(1) + "]>") : Optional.empty();
    }

    private static final java.util.regex.Pattern HELD_PLAYER = java.util.regex.Pattern.compile("(?i)(?:player:)?<\\[([a-z0-9_]{1,64})](?:\\.(?:name|uuid))?>");

    /** The player who triggered the behavior, as Sentinel's target syntax names them. */
    private static boolean triggeringPlayer(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("player:")) lowered = lowered.substring(7);
        return lowered.equals("<player>") || lowered.equals("<player.name>") || lowered.equals("<player.uuid>");
    }

    /** {@code trait state:true|false sentinel}: Sentinel on or off becomes the NPC's combat on or off. */
    private Converted trait(Command command) {
        String state = "toggle", name = null;
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("state")) state = arg.value().toLowerCase(Locale.ROOT);
            else if (arg.prefix().equals("to") && npcTarget(arg.value())) continue;
            else if (arg.prefix().isEmpty() && name == null) name = arg.value().toLowerCase(Locale.ROOT);
            else return Converted.kept("trait argument " + raw(arg) + " has no Animus equivalent");
        }
        if (!"sentinel".equals(name)) return Converted.kept("no Animus equivalent for the " + (name == null ? "unnamed" : name) + " trait");
        if (!state.equals("true") && !state.equals("false")) return Converted.kept("trait toggles Sentinel without saying on or off; use state:true or state:false");
        Action action = new Action();
        action.put("operation", state.equals("true") ? "enable" : "disable");
        return Converted.of(action.node("animus:combat-control"));
    }

    Optional<String> material(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("minecraft:")) lowered = lowered.substring(10);
        if (!lowered.matches("[a-z0-9_]{1,64}") || converter.containers.containsKey(lowered)) return Optional.empty();
        return Optional.of("minecraft:" + lowered);
    }

    /** An amount: a literal from 1 to 6400, or a tag read as a whole number. */
    private Converted amount(Action action, String value, Scope scope, Pending pending) {
        if (value.matches("[0-9]{1,4}")) {
            long amount = Long.parseLong(value);
            if (amount < 1 || amount > 6400) return Converted.kept("quantity must be from 1 to 6400");
            action.put("amount", amount);
            return null;
        }
        if (!value.contains("<")) return Converted.kept("quantity must be a number");
        Result<Expr> expr = expressions.value(value, scope, pending);
        if (expr.failed()) return Converted.kept(expr.reason());
        action.expression("amount", "int(" + expr.value().text() + ")");
        return null;
    }

    private Converted give(Command command, Scope scope, Pending pending) {
        Optional<String> item = Optional.empty();
        Optional<String> quantity = Optional.empty();
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("quantity")) quantity = Optional.of(arg.value());
            else if (arg.prefix().isEmpty() && item.isEmpty()) item = Optional.of(arg.value());
            else if (arg.prefix().equals("to") && arg.value().equalsIgnoreCase("<player.inventory>")) continue;
            else return Converted.kept("give argument " + raw(arg) + " has no Animus equivalent");
        }
        if (item.isEmpty()) return Converted.kept("nothing to give");
        String what = item.get();
        if (what.startsWith("<") && DenizenArgs.tagEnd(what, 0) == what.length() - 1) return giveValue(what, quantity, scope, pending);
        if (what.equalsIgnoreCase("xp") || what.equalsIgnoreCase("money")) return Converted.kept("giving " + what + " has no Animus equivalent");
        Container script = converter.containers.get(what.toLowerCase(Locale.ROOT));
        if (script != null && script.type().equals("book")) {
            if (quantity.isPresent()) return Converted.kept("a book is given one at a time");
            Optional<JsonObject> book = converter.book(script, scope, pending);
            return book.map(Converted::of).orElseGet(() -> Converted.kept("book script " + script.name() + " did not convert"));
        }
        Action action = new Action();
        if (script != null && script.type().equals("item")) {
            Optional<String> problem = converter.itemScript(script, action, scope, pending);
            if (problem.isPresent()) return Converted.kept(problem.get());
        } else {
            Optional<String> material = material(what);
            if (material.isEmpty()) return Converted.kept("only plain materials, simple item scripts and book scripts can be given");
            action.put("item", material.get());
        }
        if (quantity.isPresent()) {
            Converted problem = amount(action, quantity.get(), scope, pending);
            if (problem != null) return problem;
        }
        return Converted.of(reorder(action, List.of("item", "amount", "name", "lore")).node("animus:give-item"));
    }

    /**
     * {@code give <[item]>}: an item value, a list of them, or a material name read as the command runs, each given as
     * one stack; a value that may be a list is given item by item, up to 100.
     */
    private Converted giveValue(String raw, Optional<String> quantity, Scope scope, Pending pending) {
        Result<Expr> value = expressions.value(raw, scope, pending);
        if (value.failed()) return Converted.kept(value.reason());
        char kind = value.value().kind();
        if (List.of('L', 'e', 'N', 'b', 'n', 't', 'd').contains(kind)) return Converted.kept("only items and materials can be given");
        Action action = new Action();
        if (quantity.isPresent()) {
            Converted problem = amount(action, quantity.get(), scope, pending);
            if (problem != null) return problem;
        }
        if (kind == 'i') {
            action.expression("stack", value.value().text());
            return Converted.of(reorder(action, List.of("stack", "amount")).node("animus:give-item"));
        }
        String each = converter.temporary(scope);
        action.expression("stack", expressions.stack(Expr.of(each, 'a'), scope, pending));
        JsonObject loop = plain("foreach");
        JsonObject list = new JsonObject();
        list.addProperty("expression", kind == 'l' || kind == 'I' ? value.value().text() : "(same(type_of(" + value.value().text() + "), 'list') ? " + value.value().text() + " : [" + value.value().text() + "])");
        loop.add("list", list);
        loop.addProperty("as", each);
        loop.addProperty("maximum", 100);
        loop.add("body", reorder(action, List.of("stack", "amount")).node("animus:give-item"));
        return Converted.of(loop);
    }

    private static Action reorder(Action action, List<String> order) {
        Action sorted = new Action();
        for (String key : order) if (action.arguments.has(key)) sorted.arguments.add(key, action.arguments.get(key));
        for (Map.Entry<String, JsonElement> entry : action.arguments.entrySet()) if (!sorted.arguments.has(entry.getKey())) sorted.arguments.add(entry.getKey(), entry.getValue());
        for (Map.Entry<String, JsonElement> entry : action.expressions.entrySet()) sorted.expressions.add(entry.getKey(), entry.getValue());
        for (Map.Entry<String, JsonElement> entry : action.templates.entrySet()) sorted.templates.add(entry.getKey(), entry.getValue());
        return sorted;
    }

    private Converted take(Command command, Scope scope, Pending pending) {
        Optional<String> item = Optional.empty();
        Optional<String> quantity = Optional.empty();
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("quantity")) quantity = Optional.of(arg.value());
            else if ((arg.prefix().equals("item") || arg.prefix().isEmpty()) && item.isEmpty()) {
                if (arg.prefix().isEmpty() && List.of("money", "xp", "iteminhand", "cursoritem", "bydisplay", "scriptname", "slot", "flagged").contains(arg.value().toLowerCase(Locale.ROOT)))
                    return Converted.kept("take " + arg.value() + " has no Animus equivalent");
                item = material(arg.value());
                if (item.isEmpty()) return Converted.kept("only plain materials can be taken; item scripts, flags and tags have no Animus equivalent");
            } else return Converted.kept("take argument " + raw(arg) + " has no Animus equivalent");
        }
        if (item.isEmpty()) return Converted.kept("nothing to take");
        Action action = new Action();
        action.put("item", item.get());
        Converted problem = amount(action, quantity.orElse("1"), scope, pending);
        if (problem != null) return problem;
        return Converted.of(action.node("animus:take-item"));
    }

    static Optional<double[]> location(String value) {
        String text = value;
        if (text.toLowerCase(Locale.ROOT).startsWith("l@")) text = text.substring(2);
        if (text.toLowerCase(Locale.ROOT).startsWith("<location[") && text.endsWith("]>")) text = text.substring(10, text.length() - 2);
        String[] parts = text.split(",", -1);
        if (parts.length < 3 || parts.length > 6) return Optional.empty();
        double[] coordinates = new double[3];
        for (int index = 0; index < 3; index++) {
            if (!parts[index].matches("-?[0-9]{1,8}(?:\\.[0-9]{1,6})?")) return Optional.empty();
            coordinates[index] = Double.parseDouble(parts[index]);
        }
        return Optional.of(coordinates);
    }

    /** The world, yaw and pitch of a literal location: {@code x,y,z,world} or {@code x,y,z,pitch,yaw,world}. */
    static Optional<String[]> locationExtras(String value) {
        String text = value;
        if (text.toLowerCase(Locale.ROOT).startsWith("l@")) text = text.substring(2);
        if (text.toLowerCase(Locale.ROOT).startsWith("<location[") && text.endsWith("]>")) text = text.substring(10, text.length() - 2);
        String[] parts = text.split(",", -1);
        if (parts.length == 4) return parts[3].matches("[A-Za-z0-9_./-]{1,64}") ? Optional.of(new String[] {parts[3], null, null}) : Optional.empty();
        if (parts.length == 5 || parts.length == 6) {
            if (!parts[3].matches("-?[0-9]{1,3}(?:\\.[0-9]{1,6})?") || !parts[4].matches("-?[0-9]{1,3}(?:\\.[0-9]{1,6})?")) return Optional.empty();
            String world = parts.length == 6 ? parts[5] : null;
            if (world != null && !world.matches("[A-Za-z0-9_./-]{1,64}")) return Optional.empty();
            return Optional.of(new String[] {world, parts[4], parts[3]});
        }
        return Optional.of(new String[] {null, null, null});
    }

    /**
     * {@code look (<entity>) <target> (duration:..)}: the NPC, or another NPC held in a definition, turns to the player, a
     * literal location or a location value. A duration is not held; the look-close module turns the NPC again as usual.
     */
    private Converted look(Command command, Scope scope, Pending pending) {
        List<Arg> plain = command.plain();
        for (Arg arg : command.args()) if (!arg.prefix().isEmpty() && !arg.prefix().equals("duration")) return Converted.kept("look argument " + arg.prefix() + ": has no Animus equivalent");
        Action action = new Action();
        if (plain.size() == 2) {
            Optional<Converted> other = subjectNpc(plain.getFirst().value(), action, scope, pending);
            if (other.isPresent()) return other.get();
            plain = plain.subList(1, 2);
        } else if (scope.server) return Converted.kept(DenizenConverter.NO_NPC);
        if (plain.size() != 1) return Converted.kept("look at more than one target has no Animus equivalent");
        String target = plain.getFirst().value().toLowerCase(Locale.ROOT);
        if (target.equals("<player>") || target.equals("<player.location>") || target.equals("<player.eye_location>")) action.put("target", "player");
        else {
            Optional<double[]> at = location(plain.getFirst().value());
            if (at.isPresent()) {
                action.put("x", at.get()[0]);
                action.put("y", at.get()[1]);
                action.put("z", at.get()[2]);
            } else {
                Optional<String> problem = coordinates(plain.getFirst().value(), action, scope, pending, false);
                if (problem.isPresent()) return Converted.kept(problem.get());
            }
        }
        return Converted.of(reorder(action, List.of("target", "x", "y", "z", "npc")).node("animus:look"));
    }

    /**
     * The entity a command acts on, written first: the NPC itself, or another NPC held in a definition as the {@code npc}
     * argument. A reason when it is anything else, or the NPC itself in a server behavior.
     */
    private Optional<Converted> subjectNpc(String raw, Action action, Scope scope, Pending pending) {
        if (npcTarget(raw)) return scope.server ? Optional.of(Converted.kept(DenizenConverter.NO_NPC)) : Optional.empty();
        if (!raw.matches("<\\[[A-Za-z0-9_]{1,64}]>")) return Optional.of(Converted.kept("only NPCs can be turned or animated"));
        Result<Expr> held = expressions.value(raw, scope, pending);
        if (held.failed()) return Optional.of(Converted.kept(held.reason()));
        // Only a value known to hold an NPC, such as an item of an NPC list, may be one; a player held there could not be turned.
        if (held.value().kind() != 'e') return Optional.of(Converted.kept("only NPCs held in a definition, such as an item of an NPC list, can be turned or animated"));
        action.expression("npc", held.value().text());
        return Optional.empty();
    }

    /** x, y and z (and world when {@code world}) from a location value, such as a definition holding one. Empty when it converts. */
    private Optional<String> coordinates(String raw, Action action, Scope scope, Pending pending, boolean world) {
        return coordinates(raw, action, scope, pending, world, false);
    }

    /** As above; {@code anything} also reads a value of unknown kind as location text, written with or without {@code l@}. */
    private Optional<String> coordinates(String raw, Action action, Scope scope, Pending pending, boolean world, boolean anything) {
        Result<Expr> place = expressions.value(raw, scope, pending);
        if (place.failed()) return Optional.of(place.reason());
        boolean unknown = anything && place.value().kind() == 'a';
        if (place.value().kind() != 'L' && !unknown) return Optional.of("the target must be the player, the NPC, a literal location or a location value");
        String parts = unknown ? "split(replace(text(" + place.value().text() + "), 'l@', ''), ',')" : "split(" + place.value().text() + ", ',')";
        for (int axis = 0; axis < 3; axis++) action.expression(List.of("x", "y", "z").get(axis), "num(get(" + parts + ", " + axis + ", 0))");
        if (world) action.expression("world", "get(" + parts + ", 3, '')");
        return Optional.empty();
    }

    private static Converted walk(Command command) {
        List<Arg> plain = command.plain();
        if (plain.size() != command.args().size()) return Converted.kept("walk argument has no Animus equivalent");
        if (plain.size() == 2 && npcTarget(plain.getFirst().value())) plain = plain.subList(1, 2);
        if (plain.size() != 1) return Converted.kept("walk needs one literal location");
        Optional<double[]> at = location(plain.getFirst().value());
        if (at.isEmpty()) return Converted.kept("walk needs a literal location");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("x", at.get()[0]);
        arguments.addProperty("y", at.get()[1]);
        arguments.addProperty("z", at.get()[2]);
        return Converted.of(action("animus:navigate", arguments));
    }

    private static final Map<String, String> SLOTS = Map.of("hand", "main_hand", "offhand", "off_hand", "head", "head", "chest", "chest", "legs", "legs", "boots", "feet");
    private static final List<String> SLOT_ORDER = List.of("hand", "offhand", "head", "chest", "legs", "boots");

    private Converted equip(Command command) {
        TreeMap<Integer, JsonObject> slots = new TreeMap<>();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) {
                if (npcTarget(arg.value())) continue;
                return Converted.kept("equipping another entity has no Animus equivalent");
            }
            if (!SLOTS.containsKey(arg.prefix())) return Converted.kept("equipment slot " + arg.prefix() + " has no Animus equivalent");
            Optional<JsonObject> equipment = equipment(SLOTS.get(arg.prefix()), arg.value());
            if (equipment.isEmpty()) return Converted.kept("only plain materials can be equipped");
            slots.put(SLOT_ORDER.indexOf(arg.prefix()), equipment.get());
        }
        if (slots.isEmpty()) return Converted.kept("nothing to equip");
        return new Converted(new ArrayList<>(slots.values()), "");
    }

    private Optional<JsonObject> equipment(String slot, String value) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("slot", slot);
        if (!value.equalsIgnoreCase("air")) {
            Optional<String> item = material(value);
            if (item.isEmpty()) return Optional.empty();
            arguments.addProperty("item", item.get());
        }
        return Optional.of(action("animus:set-equipment", arguments));
    }

    /** Where a sound or particle plays: {@code player} or {@code npc}, or empty when the place has no Animus equivalent. */
    private static Optional<String> at(String value) {
        String target = value.toLowerCase(Locale.ROOT);
        if (target.equals("<player>") || target.equals("<player.location>") || target.equals("<player.eye_location>") || target.matches("<player\\.location\\.above(\\[[0-9.]{1,6}])?>")) return Optional.of("player");
        if (target.equals("<npc>") || target.equals("<npc.location>") || target.equals("<npc.eye_location>") || target.matches("<npc\\.location\\.above(\\[[0-9.]{1,6}])?>")) return Optional.of("npc");
        return Optional.empty();
    }

    /** How far above the feet {@code <player.location.above[n]>} or {@code <npc.location.above[n]>} is: n, or 1 without it. */
    private static Optional<Double> above(String value) {
        java.util.regex.Matcher matcher = ABOVE.matcher(value.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return Optional.empty();
        double height = matcher.group(1) == null ? 1 : Double.parseDouble(matcher.group(1));
        return height <= 16 ? Optional.of(height) : Optional.empty();
    }

    /** {@code npcGiven} says the caller names the NPC with the npc argument, which a server behavior may do too. */
    private Converted sound(Command command, Scope scope, Pending pending, boolean npcGiven) {
        Optional<String> sound = Optional.empty();
        String place = null;
        boolean custom = false;
        Action levels = new Action();
        for (Arg arg : command.args()) {
            if (arg.is("custom")) { custom = true; continue; }
            switch (arg.prefix()) {
                case "sound" -> sound = Optional.of(arg.value());
                // The sound category picks the player's volume slider; Animus plays every sound in the master category.
                case "sound_category" -> { }
                case "volume", "pitch" -> {
                    String reason = level(levels, arg.prefix(), arg.value(), arg.prefix().equals("volume") ? 0 : 0.5, arg.prefix().equals("volume") ? 4 : 2, scope, pending);
                    if (reason != null) return Converted.kept(reason);
                }
                case "" -> {
                    if (place != null) return Converted.kept("sound for several targets has no Animus equivalent");
                    Optional<String> where = at(arg.value());
                    if (where.isPresent()) place = where.get();
                    else if (sound.isEmpty() && !arg.value().startsWith("<")) sound = Optional.of(arg.value());
                    else return Converted.kept("sound location must be the NPC or the player");
                }
                default -> { return Converted.kept("playsound argument " + arg.prefix() + ": has no Animus equivalent"); }
            }
        }
        if (sound.isEmpty()) return Converted.kept("playsound needs a sound");
        if ("npc".equals(place) && scope.server && !npcGiven) return Converted.kept("a sound at the NPC needs an NPC; " + DenizenConverter.NO_NPC);
        Action action = new Action();
        if (sound.get().contains("<")) {
            Result<Expr> named = expressions.value(sound.get(), scope, pending);
            if (named.failed()) return Converted.kept(named.reason());
            String held = converter.temporary(scope);
            JsonObject set = plain("set");
            set.addProperty("variable", held);
            JsonObject operand = new JsonObject();
            operand.addProperty("expression", "lower(" + DenizenExpressions.textual(named.value()) + ")");
            set.add("value", operand);
            pending.steps.add(set);
            action.expression("sound", soundKey(held, custom));
        } else {
            Optional<String> key = DenizenConverter.soundKey(sound.get(), custom);
            if (key.isEmpty()) return Converted.kept("sound " + sound.get() + " has no resource key");
            converter.sounds.put(sound.get(), key.get());
            action.put("sound", key.get());
        }
        action = merged(action, levels, List.of("volume", "pitch"));
        if ("player".equals(place) || scope.server && !npcGiven) action.put("at", "player");
        else if ("npc".equals(place)) action.put("audience", "nearby");
        return Converted.of(action.node("animus:play-sound"));
    }

    /**
     * A sound's volume or pitch: a literal number held to the range, as the game holds pitch to 0.5-2, or a tag read
     * as a number and held there when it plays. Null when it converts, otherwise the reason.
     */
    private String level(Action action, String name, String value, double minimum, double maximum, Scope scope, Pending pending) {
        if (value.matches("[0-9]{1,4}(?:\\.[0-9]{1,6})?")) {
            action.put(name, Math.max(minimum, Math.min(maximum, Double.parseDouble(value))));
            return null;
        }
        if (!value.contains("<")) return name + " must be a number";
        Result<Expr> expr = expressions.value(value, scope, pending);
        if (expr.failed()) return expr.reason();
        action.expression(name, "clamp(" + DenizenExpressions.numeric(expr.value()) + ", " + DenizenExpressions.literal(trim(minimum)).text() + ", " + DenizenExpressions.literal(trim(maximum)).text() + ")");
        return null;
    }

    /**
     * The resource key of a sound name read as the sound plays, as {@link DenizenConverter#soundKey} makes it from a
     * written name: a key stays, a dotted name gets the namespace, and a Bukkit name has its first and last
     * underscores turned into dots.
     */
    private static String soundKey(String name, boolean custom) {
        String words = "split(" + name + ", '_')";
        String bukkit = custom ? name : "(contains(" + name + ", '.') ? " + name + " : first(" + words + ") + '.' + join(slice(" + words + ", 1, len(" + words + ") - 1), '_') + '.' + last(" + words + "))";
        return "(contains(" + name + ", ':') ? " + name + " : 'minecraft:' + " + bukkit + ")";
    }

    /** A number as Denizen writes it: without a trailing {@code .0}. */
    private static String trim(double number) { return number == Math.rint(number) ? Long.toString((long) number) : Double.toString(number); }

    /** {@code action} followed by the named arguments of {@code from}, with their expressions. */
    private static Action merged(Action action, Action from, List<String> names) {
        for (String name : names) {
            if (!from.arguments.has(name)) continue;
            action.arguments.add(name, from.arguments.get(name));
            if (from.expressions.has("/" + name)) action.expressions.add("/" + name, from.expressions.get("/" + name));
        }
        return action;
    }

    private static final Map<String, String> ANIMATIONS = Map.ofEntries(
            Map.entry("arm_swing", "swing"), Map.entry("swing_main_hand", "swing"), Map.entry("arm_swing_offhand", "swing_offhand"), Map.entry("swing_off_hand", "swing_offhand"),
            Map.entry("hurt", "hurt"), Map.entry("crit", "crit"), Map.entry("magic_crit", "magic_crit"), Map.entry("sit", "sit"), Map.entry("stop_sitting", "stand"),
            Map.entry("sleep", "sleep"), Map.entry("stop_sleeping", "wake"), Map.entry("sneak", "sneak"), Map.entry("stop_sneaking", "stand"),
            Map.entry("start_use_mainhand_item", "use_item"), Map.entry("stop_use_item", "stop_using_item"));

    /** {@code animate <npc> animation:arm_swing}: the vanilla animation every viewer of the NPC sees. */
    private Converted animate(Command command, Scope scope, Pending pending) {
        Optional<String> animation = Optional.empty();
        boolean npc = false;
        Action subject = new Action();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) {
                if (npc) return Converted.kept("animating several entities has no Animus equivalent");
                Optional<Converted> other = subjectNpc(arg.value(), subject, scope, pending);
                if (other.isPresent()) return other.get();
                npc = true;
                continue;
            }
            if (arg.prefix().equals("animation")) animation = Optional.of(arg.value().toLowerCase(Locale.ROOT));
            else if (arg.prefix().equals("for")) return Converted.kept("animate for: has no Animus equivalent; every viewer sees the animation");
            else return Converted.kept("animate argument " + arg.prefix() + ": has no Animus equivalent");
        }
        if (!npc) return Converted.kept("animate needs the NPC");
        if (animation.isEmpty() || animation.get().contains("<")) return Converted.kept("animate needs a literal animation");
        String mapped = ANIMATIONS.get(animation.get());
        if (mapped == null) return Converted.kept("animation " + animation.get() + " has no Animus equivalent");
        Action action = new Action();
        action.put("animation", mapped);
        if (subject.expressions.has("/npc")) action.expression("npc", subject.expressions.get("/npc").getAsString());
        return Converted.of(action.node("animus:animate"));
    }

    private static Double number(String value, double minimum, double maximum) {
        if (!value.matches("[0-9]{1,4}(?:\\.[0-9]{1,6})?")) return null;
        double number = Double.parseDouble(value);
        return number < minimum || number > maximum ? null : number;
    }

    private static final Map<String, String> PARTICLE_NAMES = Map.ofEntries(
            Map.entry("villager_happy", "happy_villager"), Map.entry("villager_angry", "angry_villager"), Map.entry("spell_witch", "witch"),
            Map.entry("smoke_normal", "smoke"), Map.entry("smoke_large", "large_smoke"), Map.entry("explosion_normal", "poof"),
            Map.entry("explosion_large", "explosion"), Map.entry("explosion_huge", "explosion_emitter"), Map.entry("water_splash", "splash"),
            Map.entry("water_bubble", "bubble"), Map.entry("water_wake", "fishing"), Map.entry("suspended", "underwater"),
            Map.entry("suspended_depth", "underwater"), Map.entry("crit_magic", "enchanted_hit"), Map.entry("spell", "effect"),
            Map.entry("spell_instant", "instant_effect"), Map.entry("drip_water", "dripping_water"), Map.entry("drip_lava", "dripping_lava"),
            Map.entry("enchantment_table", "enchant"), Map.entry("town_aura", "mycelium"), Map.entry("snowball", "item_snowball"),
            Map.entry("snow_shovel", "item_snowball"), Map.entry("slime", "item_slime"), Map.entry("fireworks_spark", "firework"),
            Map.entry("totem", "totem_of_undying"), Map.entry("mob_appearance", "elder_guardian"), Map.entry("water_drop", "rain"));
    private static final Set<String> DATA_PARTICLES = Set.of("dust", "redstone", "block", "block_crack", "block_dust", "item", "item_crack", "falling_dust", "dust_color_transition",
            "vibration", "entity_effect", "spell_mob", "spell_mob_ambient", "block_marker", "sculk_charge", "shriek", "dust_pillar", "trail", "legacy_block_marker");

    /** {@code npcGiven} says the caller names the NPC with the npc argument, which a server behavior may do too. */
    private static Converted particle(Command command, Scope scope, boolean npcGiven) {
        Optional<String> effect = Optional.empty();
        String place = scope.server ? "player" : "npc";
        boolean trigger = false;
        Long count = null;
        Double spread = null, height = null;
        for (Arg arg : command.args()) {
            switch (arg.prefix()) {
                case "effect" -> effect = Optional.of(arg.value().toLowerCase(Locale.ROOT));
                case "at" -> {
                    Optional<String> where = at(arg.value());
                    if (where.isEmpty()) return Converted.kept("particles show only at the NPC or a player in Animus");
                    place = where.get();
                    height = above(arg.value()).orElse(null);
                }
                case "quantity" -> {
                    if (!arg.value().matches("[0-9]{1,2}") || Long.parseLong(arg.value()) < 1 || Long.parseLong(arg.value()) > 64) return Converted.kept("quantity must be from 1 to 64");
                    count = Long.parseLong(arg.value());
                }
                case "offset" -> {
                    double widest = 0;
                    for (String part : arg.value().split(",", -1)) {
                        Double value = number(part, 0, 8);
                        if (value == null) return Converted.kept("offset must be from 0 to 8 blocks");
                        widest = Math.max(widest, value);
                    }
                    spread = widest;
                }
                case "targets" -> { if (!playerTarget(arg.value())) return Converted.kept("particles for other targets have no Animus equivalent"); trigger = true; }
                case "visibility", "data" -> { /* range and particle speed are not carried; the docs list this */ }
                default -> { return Converted.kept("playeffect argument " + (arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":") + " has no Animus equivalent"); }
            }
        }
        if (place.equals("npc") && scope.server && !npcGiven) return Converted.kept("particles at the NPC need an NPC; " + DenizenConverter.NO_NPC);
        if (effect.isEmpty() || effect.get().contains("<")) return Converted.kept("playeffect needs a literal effect");
        String name = effect.get().startsWith("minecraft:") ? effect.get().substring(10) : effect.get();
        if (DATA_PARTICLES.contains(name)) return Converted.kept("particles that need extra data have no Animus equivalent");
        name = PARTICLE_NAMES.getOrDefault(name, name);
        if (!name.matches("[a-z0-9_]{1,64}")) return Converted.kept("effect " + effect.get() + " has no particle key");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("particle", "minecraft:" + name);
        if (count != null) arguments.addProperty("count", count);
        if (spread != null) arguments.addProperty("spread", spread);
        if (height != null) arguments.addProperty("height", height);
        if (place.equals("player")) arguments.addProperty("at", "player");
        else if (!trigger) arguments.addProperty("audience", "nearby");
        return Converted.of(action("animus:spawn-particle", arguments));
    }

    private Converted actionbar(Command command, Scope scope, Pending pending) {
        List<String> words = new ArrayList<>();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) { words.add(arg.value()); continue; }
            if (arg.prefix().equals("targets") && playerTarget(arg.value())) continue;
            return Converted.kept("actionbar argument " + arg.prefix() + ": has no Animus equivalent");
        }
        if (words.isEmpty()) return Converted.kept("actionbar without text");
        Result<Text> text = text(String.join(" ", words), scope, pending, false, 256);
        if (text.failed()) return Converted.kept(text.reason());
        Action action = new Action();
        action.text("text", text.value());
        return Converted.of(action.node("animus:actionbar"));
    }

    private Converted title(Command command, Scope scope, Pending pending) {
        Action action = new Action();
        boolean any = false;
        TreeMap<Integer, Long> times = new TreeMap<>();
        for (Arg written : command.args()) {
            Arg arg = quotedPrefix(written, List.of("title", "subtitle", "fade_in", "stay", "fade_out", "targets"));
            switch (arg.prefix()) {
                case "title", "subtitle" -> {
                    Result<Text> text = text(arg.value(), scope, pending, false, 256);
                    if (text.failed()) return Converted.kept(text.reason());
                    action.text(arg.prefix(), text.value());
                    any = true;
                }
                case "fade_in", "stay", "fade_out" -> {
                    OptionalLong ticks = DenizenArgs.ticks(arg.value());
                    if (ticks.isEmpty() || ticks.getAsLong() > 72_000) return Converted.kept("title timing needs a literal duration up to an hour");
                    times.put(arg.prefix().equals("fade_in") ? 0 : arg.prefix().equals("stay") ? 1 : 2, ticks.getAsLong());
                }
                case "targets" -> { if (!playerTarget(arg.value())) return Converted.kept("titles for other targets have no Animus equivalent"); }
                default -> { return Converted.kept("title argument " + (arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":") + " has no Animus equivalent"); }
            }
        }
        if (!any) return Converted.kept("title without text");
        String[] names = {"fadeIn", "stay", "fadeOut"};
        for (Map.Entry<Integer, Long> time : times.entrySet()) action.put(names[time.getKey()], time.getValue());
        return Converted.of(reorder(action, List.of("title", "subtitle", "fadeIn", "stay", "fadeOut")).node("animus:title"));
    }

    /** Denizen reads a prefix inside quotes too: {@code "title:Town gate"} is the title argument. */
    private static Arg quotedPrefix(Arg arg, List<String> names) {
        if (!arg.quoted() || !arg.prefix().isEmpty()) return arg;
        int colon = arg.value().indexOf(':');
        if (colon <= 0 || !names.contains(arg.value().substring(0, colon).toLowerCase(Locale.ROOT))) return arg;
        return new Arg(arg.value().substring(0, colon).toLowerCase(Locale.ROOT), arg.value().substring(colon + 1), true);
    }

    private Converted teleport(Command command, Scope scope, Pending pending) {
        List<Arg> plain = command.plain();
        if (plain.size() != command.args().size()) return Converted.kept("teleport argument has no Animus equivalent");
        if (plain.isEmpty() || plain.size() > 2) return Converted.kept("teleport needs a target and a destination");
        String target = "player";
        if (plain.size() == 2) {
            String who = plain.getFirst().value();
            if (npcTarget(who)) target = "npc";
            else if (!playerTarget(who)) return Converted.kept("teleporting other entities has no Animus equivalent");
        }
        if (target.equals("npc") && scope.server) return Converted.kept("teleport of the NPC needs an NPC; " + DenizenConverter.NO_NPC);
        String destination = plain.getLast().value();
        String lowered = destination.toLowerCase(Locale.ROOT);
        JsonObject arguments = new JsonObject();
        if (target.equals("npc")) arguments.addProperty("target", "npc");
        if (lowered.equals("<npc>") || lowered.equals("<npc.location>")) {
            if (scope.server) return Converted.kept("teleport to the NPC needs an NPC; " + DenizenConverter.NO_NPC);
            arguments.addProperty("to", "npc");
        } else if (lowered.equals("<player>") || lowered.equals("<player.location>")) arguments.addProperty("to", "player");
        else {
            Optional<double[]> at = location(destination);
            Optional<String[]> extras = locationExtras(destination);
            if (at.isEmpty() || extras.isEmpty()) {
                Action computed = new Action();
                for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) computed.put(entry.getKey(), entry.getValue());
                Optional<String> problem = destination.startsWith("<") ? coordinates(destination, computed, scope, pending, true) : Optional.of("teleport destination must be the player, the NPC, a literal location or a location value");
                if (problem.isPresent()) return Converted.kept(problem.get());
                return Converted.of(computed.node("animus:teleport"));
            }
            arguments.addProperty("x", at.get()[0]);
            arguments.addProperty("y", at.get()[1]);
            arguments.addProperty("z", at.get()[2]);
            if (extras.get()[0] != null) arguments.addProperty("world", extras.get()[0]);
            if (extras.get()[1] != null) arguments.addProperty("yaw", Double.parseDouble(extras.get()[1]));
            if (extras.get()[2] != null) arguments.addProperty("pitch", Double.parseDouble(extras.get()[2]));
        }
        if (target.equals("npc") && arguments.has("to") && arguments.get("to").getAsString().equals("npc")) return Converted.kept("teleporting the NPC to itself does nothing");
        if (target.equals("player") && arguments.has("to") && arguments.get("to").getAsString().equals("player")) return Converted.kept("teleporting the player to itself does nothing");
        return Converted.of(action("animus:teleport", arguments));
    }

    /** Mechanisms with an Animus action: the NPC's name and held or worn items, and showing a book script to the player. */
    private Converted adjust(Command command, Scope scope, Pending pending) {
        if (command.args().size() == 2 && command.args().getFirst().prefix().equals("def")) return adjustItem(command.args().getFirst().value(), command.args().get(1), scope, pending);
        if (command.args().size() != 2 || !command.args().getFirst().prefix().isEmpty()) return Converted.kept("adjust takes one object and one mechanism");
        String object = command.args().getFirst().value();
        Arg mechanism = command.args().get(1);
        String name = mechanism.prefix().isEmpty() ? mechanism.value().toLowerCase(Locale.ROOT) : mechanism.prefix();
        if (npcTarget(object)) {
            if (scope.server) return Converted.kept("adjust of the NPC needs an NPC; " + DenizenConverter.NO_NPC);
            switch (name) {
                case "name" -> {
                    Result<Text> text = text(mechanism.value(), scope, pending, false, 16);
                    if (text.failed()) return Converted.kept(text.reason());
                    Action action = new Action();
                    action.text("name", text.value());
                    return Converted.of(action.node("animus:set-appearance"));
                }
                case "item_in_hand", "item_in_offhand" -> {
                    Optional<JsonObject> equipment = equipment(name.equals("item_in_hand") ? "main_hand" : "off_hand", mechanism.value());
                    return equipment.map(Converted::of).orElseGet(() -> Converted.kept("only plain materials can be equipped"));
                }
                default -> { return Converted.kept("NPC mechanism " + name + " has no Animus equivalent"); }
            }
        }
        if (playerTarget(object) && name.equals("show_book")) {
            Container book = converter.containers.get(mechanism.value().toLowerCase(Locale.ROOT));
            if (book == null || !book.type().equals("book")) return Converted.kept("show_book needs a book script");
            Optional<JsonObject> given = converter.book(book, scope, pending);
            return given.map(Converted::of).orElseGet(() -> Converted.kept("book script " + book.name() + " did not convert"));
        }
        return Converted.kept("mechanism " + name + " of " + object + " has no Animus equivalent");
    }

    private static final java.util.regex.Pattern BAR_ID = java.util.regex.Pattern.compile("([A-Za-z0-9_.-]{1,64})(?:<(player|\\[[A-Za-z0-9_]{1,64}])\\.uuid>)?");

    /**
     * {@code bossbar create|update|auto|remove id ...}: a boss bar for each player, by the written part of its id. An id
     * that ends with a player's uuid, as scripts write bars meant for one player, is that player's bar.
     */
    private Converted bossbar(Command command, Scope scope, Pending pending) {
        List<String> named = List.of("title", "progress", "color", "style", "players");
        List<Arg> plain = command.args().stream().filter((Arg arg) -> quotedPrefix(arg, named).prefix().isEmpty()).toList();
        if (plain.size() != 2 || !List.of("create", "update", "auto", "remove").contains(plain.getFirst().value().toLowerCase(Locale.ROOT)))
            return Converted.kept("bossbar needs create, update, auto or remove and one id");
        boolean remove = plain.getFirst().value().equalsIgnoreCase("remove");
        java.util.regex.Matcher id = BAR_ID.matcher(plain.get(1).value());
        if (!id.matches()) return Converted.kept("a bossbar id must be written out, with at most a player's uuid after it");
        Optional<String> players = id.group(2) == null || id.group(2).equals("player") ? Optional.empty() : Optional.of("<" + id.group(2) + ">");
        Action action = new Action();
        action.put("id", id.group(1).toLowerCase(Locale.ROOT));
        if (remove) action.put("operation", "remove");
        for (Arg written : command.args()) {
            if (plain.contains(written)) continue;
            Arg arg = quotedPrefix(written, named);
            switch (arg.prefix()) {
                case "players" -> {
                    if (players.isPresent() && !players.get().equalsIgnoreCase(arg.value())) return Converted.kept("a bossbar for players its id does not name has no Animus equivalent");
                    if (!playerTarget(arg.value())) players = Optional.of(arg.value());
                }
                case "title" -> {
                    if (remove) return Converted.kept("a removed bossbar takes no title");
                    Result<Text> text = text(arg.value(), scope, pending, false, 256);
                    if (text.failed()) return Converted.kept(text.reason());
                    action.text("title", text.value());
                }
                case "progress" -> {
                    if (remove) return Converted.kept("a removed bossbar takes no progress");
                    if (arg.value().matches("(?:0|1)(?:\\.[0-9]{1,6})?") && Double.parseDouble(arg.value()) <= 1) action.put("progress", Double.parseDouble(arg.value()));
                    else {
                        Result<Expr> progress = expressions.value(arg.value(), scope, pending);
                        if (progress.failed()) return Converted.kept(progress.reason());
                        action.expression("progress", "clamp(" + DenizenExpressions.numeric(progress.value()) + ", 0, 1)");
                    }
                }
                case "color", "style" -> {
                    String value = arg.value().toLowerCase(Locale.ROOT);
                    List<String> known = arg.prefix().equals("color") ? List.of("pink", "blue", "red", "green", "yellow", "purple", "white")
                            : List.of("solid", "segmented_6", "segmented_10", "segmented_12", "segmented_20");
                    if (remove || !known.contains(value)) return Converted.kept("bossbar " + arg.prefix() + " " + value + " has no Animus equivalent");
                    action.put(arg.prefix(), value);
                }
                default -> { return Converted.kept("bossbar argument " + (arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":") + " has no Animus equivalent"); }
            }
        }
        return forPlayers(action.node("animus:bossbar"), players, scope, pending);
    }

    /**
     * {@code sidebar set|add|set_line|remove (title:..) (values:a|b) (scores:n) (players:..)}: the players' sidebar, its
     * lines top first, up to 15. add puts lines below the last ones, and set_line writes the line its one score names.
     */
    private Converted sidebar(Command command, Scope scope, Pending pending) {
        List<String> named = List.of("title", "values", "players", "scores");
        List<Arg> plain = command.args().stream().filter((Arg arg) -> quotedPrefix(arg, named).prefix().isEmpty()).toList();
        String operation = plain.isEmpty() ? "set" : plain.getFirst().value().toLowerCase(Locale.ROOT);
        if (plain.size() > 1 || !List.of("set", "add", "set_line", "remove").contains(operation)) return Converted.kept("only sidebar set, add, set_line and remove have an Animus equivalent");
        Action action = new Action();
        Optional<String> players = Optional.empty();
        boolean titled = false, lined = false;
        if (!operation.equals("set")) action.put("operation", operation);
        for (Arg written : command.args()) {
            Arg arg = quotedPrefix(written, named);
            if (!arg.prefix().equals("scores")) continue;
            if (!operation.equals("set_line") || !arg.value().matches("[0-9]{1,2}") || Integer.parseInt(arg.value()) < 1 || Integer.parseInt(arg.value()) > 15)
                return Converted.kept("sidebar scores: takes one written line from 1 to 15, with set_line only");
            action.put("line", Long.parseLong(arg.value()));
            lined = true;
        }
        if (operation.equals("set_line") && !lined) return Converted.kept("sidebar set_line needs scores: naming its line");
        for (Arg written : command.args()) {
            if (plain.contains(written)) continue;
            Arg arg = quotedPrefix(written, named);
            switch (arg.prefix()) {
                case "scores" -> { }
                case "players" -> { if (!playerTarget(arg.value())) players = Optional.of(arg.value()); }
                case "title" -> {
                    if (operation.equals("remove")) return Converted.kept("a removed sidebar takes no title");
                    Result<Text> text = text(arg.value(), scope, pending, false, 128);
                    if (text.failed()) return Converted.kept(text.reason());
                    action.text("title", text.value());
                    titled = true;
                }
                case "values" -> {
                    if (operation.equals("remove")) return Converted.kept("a removed sidebar takes no values");
                    Result<Expr> lines = DenizenArgs.segments(arg.value()).size() == 1 && arg.value().startsWith("<") ? expressions.value(arg.value(), scope, pending) : literalList(arg.value(), scope, pending);
                    if (lines.failed()) return Converted.kept(lines.reason());
                    String text = lines.value().kind() == 'l' ? "join(" + lines.value().text() + ", '|')" : "(same(type_of(" + lines.value().text() + "), 'list') ? join(" + lines.value().text() + ", '|') : text(" + lines.value().text() + "))";
                    action.expression("lines", "slice(split(" + text + ", '|'), 0, " + (operation.equals("set_line") ? 1 : 15) + ")");
                }
                default -> { return Converted.kept("sidebar argument " + (arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":") + " has no Animus equivalent"); }
            }
        }
        if (operation.equals("set") && !titled) return Converted.kept("a sidebar needs a title in Animus");
        return forPlayers(action.node("animus:sidebar"), players, scope, pending);
    }

    /**
     * {@code lookclose (<npc>) state:true|false (range:n) (realistic)}: the NPC's look-close until a restart or a
     * definition change, with line of sight for realistic. Without a state it toggles, which has no Animus equivalent.
     */
    private Converted lookclose(Command command, Scope scope, Pending pending) {
        Action action = new Action();
        Optional<Boolean> state = Optional.empty();
        for (Arg arg : command.args()) {
            switch (arg.prefix()) {
                case "state" -> {
                    if (!arg.value().equalsIgnoreCase("true") && !arg.value().equalsIgnoreCase("false")) return Converted.kept("lookclose state must be true or false");
                    state = Optional.of(arg.value().equalsIgnoreCase("true"));
                }
                case "range" -> {
                    Double range = number(arg.value(), 1, 64);
                    if (range == null) return Converted.kept("lookclose range must be a written number from 1 to 64");
                    action.put("range", range);
                }
                case "" -> {
                    if (arg.is("realistic")) action.put("lineOfSight", new JsonPrimitive(true));
                    else {
                        Optional<Converted> other = subjectNpc(arg.value(), action, scope, pending);
                        if (other.isPresent()) return other.get();
                    }
                }
                default -> { return Converted.kept("lookclose argument " + arg.prefix() + ": has no Animus equivalent"); }
            }
        }
        if (scope.server && !action.expressions.has("/npc")) return Converted.kept(DenizenConverter.NO_NPC);
        if (state.isEmpty()) return Converted.kept("lookclose without state: toggles, which has no Animus equivalent; write state:true or state:false");
        Action ordered = new Action();
        ordered.put("enabled", new JsonPrimitive(state.get()));
        for (Map.Entry<String, JsonElement> entry : action.arguments.entrySet()) ordered.arguments.add(entry.getKey(), entry.getValue());
        for (Map.Entry<String, JsonElement> entry : action.expressions.entrySet()) ordered.expressions.add(entry.getKey(), entry.getValue());
        return Converted.of(ordered.node("animus:look-close"));
    }

    /**
     * {@code explode (power:n) (<location>) (fire) (breakblocks)}: an explosion at the location, or at the NPC. Fire and
     * broken blocks need {@code explosionsDamageBlocks}, which is off by default.
     */
    private Converted explode(Command command, Scope scope, Pending pending) {
        if (scope.server) return Converted.kept("explode happens at the NPC's place; " + DenizenConverter.NO_NPC);
        Action action = new Action();
        for (Arg arg : command.args()) {
            if (arg.is("fire")) { action.put("fire", new JsonPrimitive(true)); continue; }
            if (arg.is("breakblocks")) { action.put("breakBlocks", new JsonPrimitive(true)); continue; }
            switch (arg.prefix()) {
                case "power" -> {
                    Double power = number(arg.value(), 0, 16);
                    if (power != null) action.put("power", power);
                    else {
                        if (!arg.value().contains("<")) return Converted.kept("explode power must be from 0 to 16");
                        Result<Expr> computed = expressions.value(arg.value(), scope, pending);
                        if (computed.failed()) return Converted.kept(computed.reason());
                        action.expression("power", "clamp(" + DenizenExpressions.numeric(computed.value()) + ", 0, 16)");
                    }
                }
                case "" -> {
                    if (npcTarget(arg.value())) continue;
                    Optional<double[]> at = location(arg.value());
                    if (at.isPresent()) {
                        action.put("x", at.get()[0]);
                        action.put("y", at.get()[1]);
                        action.put("z", at.get()[2]);
                        continue;
                    }
                    // A definition given to explode holds a place, which Denizen writes as location text.
                    Optional<String> problem = arg.value().startsWith("<") ? coordinates(arg.value(), action, scope, pending, true, true) : Optional.of("explode argument " + arg.value() + " has no Animus equivalent");
                    if (problem.isPresent()) return Converted.kept(problem.get());
                }
                default -> { return Converted.kept("explode argument " + arg.prefix() + ": has no Animus equivalent"); }
            }
        }
        return Converted.of(reorder(action, List.of("x", "y", "z", "world", "power", "fire", "breakBlocks")).node("animus:explode"));
    }

    /** One action for the triggering player, or for each player a list names, up to 100, as the player argument. */
    private Converted forPlayers(JsonObject node, Optional<String> players, Scope scope, Pending pending) {
        if (players.isEmpty()) return Converted.of(node);
        Result<Expr> list = expressions.value(players.get(), scope, pending);
        if (list.failed()) return Converted.kept("players " + list.reason());
        if (list.value().kind() == 'e' || list.value().kind() == 'N') return Converted.kept("only players can be shown bars and sidebars");
        String each = converter.temporary(scope);
        node.getAsJsonObject("arguments").add("player", JsonNull.INSTANCE);
        JsonObject filled = node.has("expressions") ? node.getAsJsonObject("expressions") : new JsonObject();
        filled.addProperty("/player", each);
        node.add("expressions", filled);
        JsonObject loop = plain("foreach");
        JsonObject source = new JsonObject();
        source.addProperty("expression", "players(" + list.value().text() + ")");
        loop.add("list", source);
        loop.addProperty("as", each);
        loop.addProperty("maximum", 100);
        loop.add("body", node);
        return Converted.of(loop);
    }

    private static final java.util.regex.Pattern ENCHANTMENT = java.util.regex.Pattern.compile("(?:minecraft:)?([a-z0-9_]{1,64})[=,]([0-9]{1,3})");

    /**
     * {@code adjust def:sword enchantments:[sharpness=5]}: an item held in a definition takes its enchantments, display
     * name, lore or quantity into its map. Other mechanisms have no item value form.
     */
    private Converted adjustItem(String name, Arg written, Scope scope, Pending pending) {
        if (!name.matches("[A-Za-z0-9_]{1,64}")) return Converted.kept("adjust def: needs a definition name");
        Arg mechanism = quotedPrefix(written, List.of("enchantments", "display", "lore", "quantity"));
        if (!List.of("enchantments", "display", "lore", "quantity").contains(mechanism.prefix()))
            return Converted.kept("item mechanism " + (mechanism.prefix().isEmpty() ? mechanism.value().split(":", 2)[0].toLowerCase(Locale.ROOT) : mechanism.prefix()) + " has no Animus equivalent");
        String variable = DenizenConverter.definitionVariable(name.toLowerCase(Locale.ROOT));
        converter.declare(scope, variable, "any");
        String value;
        switch (mechanism.prefix()) {
            case "enchantments" -> {
                String raw = mechanism.value().toLowerCase(Locale.ROOT);
                if (raw.startsWith("[") && raw.endsWith("]")) raw = raw.substring(1, raw.length() - 1);
                JsonObject levels = new JsonObject();
                for (String entry : raw.split("[;|]", -1)) {
                    java.util.regex.Matcher matcher = ENCHANTMENT.matcher(entry.trim());
                    if (!matcher.matches()) return Converted.kept("enchantments need written names and levels");
                    int level = Integer.parseInt(matcher.group(2));
                    if (level < 1 || level > 255) return Converted.kept("an enchantment level is 1 to 255");
                    levels.addProperty("minecraft:" + matcher.group(1), level);
                }
                if (levels.size() > 32) return Converted.kept("an item takes up to 32 enchantments");
                value = "put(" + variable + ", 'enchantments', " + expressions.constant(levels, scope, pending) + ")";
            }
            case "display" -> {
                Result<Expr> named = expressions.value(mechanism.value(), scope, pending);
                if (named.failed()) return Converted.kept(named.reason());
                value = "put(" + variable + ", 'name', " + DenizenExpressions.textual(named.value()) + ")";
            }
            case "lore" -> {
                Result<Expr> lines = expressions.value(mechanism.value(), scope, pending);
                if (lines.failed()) return Converted.kept(lines.reason());
                value = "put(" + variable + ", 'lore', " + (lines.value().kind() == 'l' ? lines.value().text() : "split(" + DenizenExpressions.textual(lines.value()) + ", '|')") + ")";
            }
            default -> {
                Result<Expr> amount = expressions.value(mechanism.value(), scope, pending);
                if (amount.failed()) return Converted.kept(amount.reason());
                value = "put(" + variable + ", 'amount', int(" + DenizenExpressions.numeric(amount.value()) + "))";
            }
        }
        if (value.length() > DenizenExpressions.MAXIMUM_EXPRESSION) return Converted.kept("expression longer than 4096 characters");
        JsonObject set = plain("set");
        set.addProperty("variable", variable);
        JsonObject operand = new JsonObject();
        operand.addProperty("expression", value);
        set.add("value", operand);
        return Converted.of(set);
    }

    private Converted inventory(Command command) {
        if (command.args().isEmpty() || !command.args().getFirst().is("open")) return Converted.kept("only inventory open has an Animus equivalent");
        Optional<String> destination = Optional.empty();
        for (Arg arg : command.args().subList(1, command.args().size())) {
            if (arg.prefix().equals("d") || arg.prefix().equals("destination")) destination = Optional.of(arg.value());
            else if (arg.prefix().equals("o") || arg.prefix().equals("origin") || arg.prefix().equals("slot")) return Converted.kept("inventory open with " + arg.prefix() + ": has no Animus equivalent");
            else if (!(arg.prefix().equals("player") && playerTarget(arg.value()))) return Converted.kept("inventory argument " + raw(arg) + " has no Animus equivalent");
        }
        if (destination.isEmpty()) return Converted.kept("inventory open without a destination");
        String name = destination.get();
        if (name.toLowerCase(Locale.ROOT).startsWith("<inventory[") && name.endsWith("]>")) name = name.substring(11, name.length() - 2);
        Container script = converter.containers.get(name.toLowerCase(Locale.ROOT));
        if (script == null || !script.type().equals("inventory")) return Converted.kept("inventory open needs an inventory script by name");
        Optional<String> gui = converter.guiId(script);
        if (gui.isEmpty()) return Converted.kept("inventory script " + script.name() + " did not convert to a GUI");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("gui", gui.get());
        return Converted.of(action("animus:open-gui", arguments));
    }
}
