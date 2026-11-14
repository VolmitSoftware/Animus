package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionMode;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRoute;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeParameter;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs {@code /animus} through VolmLib's Director, the same on every platform. Bare words bind to required parameters,
 * then to the optional ones a command lists in {@link Positional}; any other optional one is {@code key=value}. Unknown
 * keys and leftover words fail with the command's usage and the key to write.
 * Commands carry their node in {@link AnimusPermission}; help and completion show only what the actor may run, and only
 * the {@link NeedsPlugin} commands whose plugin is running. A player in Simple mode gets the short help page and replies
 * without transaction ids; see {@link ReplyActor}.
 * Call {@link #execute} and {@link #complete} on the actor's owner; commands read the actor with {@link #actor()}.
 */
public final class AnimusDirector {
    /** Every node without its own {@link AnimusPermission} needs this one. */
    public static final String ADMIN = PermissionNodes.ADMIN;
    /** Help in Animus's pinks on grey. */
    public static final DirectorMiniMenu.Theme THEME = new DirectorMiniMenu.Theme("#FF5FA8", "#E0408C", "#E0408C", "#555555", "#AAAAAA", "#FF5FA8", "#FFB3D6", "#777777");
    private static final TextKey UNEXPECTED = TextKey.of(DirectorRuntimeMessages.UNEXPECTED_ARGUMENT.id(),
            "Unexpected \"{argument}\"; optional values are key=value");
    /**
     * Director's English, with Animus's own example for an unkeyed optional value, and no Java type on a missing argument
     * or on a value that does not convert: {@code page takes a number}.
     */
    public static final DirectorTextResolver TEXT = (TextKey key, MessageArgs arguments) -> {
        if (key.id().equals(DirectorRuntimeMessages.CONVERSION_FAILED.id())) return converted(arguments);
        String text = DirectorTextResolver.ENGLISH.resolve(key.id().equals(UNEXPECTED.id()) ? UNEXPECTED : key, arguments);
        return key.id().equals(DirectorRuntimeMessages.MISSING_ARGUMENT.id()) ? text.replaceFirst(" \\([^()]*\\)$", "") : text;
    };

    private static String converted(MessageArgs arguments) {
        String parameter = String.valueOf(arguments.require("parameter").value()), type = String.valueOf(arguments.require("type").value());
        return switch (type) {
            case "int", "Integer", "long", "Long", "short", "Short", "double", "Double", "float", "Float" -> parameter + " takes a number";
            case "boolean", "Boolean" -> parameter + " takes true or false";
            default -> parameter + " cannot be \"" + arguments.require("value").value() + "\"";
        };
    }
    private static final int SUGGESTIONS = 100;
    private static final Set<String> HELP = Set.of("help", "?");
    /** After {@code help}: every command, not the short page. */
    private static final String ALL = "all";
    private static final ThreadLocal<CommandActor> CURRENT = new ThreadLocal<>();
    private final String label;
    private final DirectorRuntimeEngine engine;
    private final Completer values;
    private final Predicate<String> plugins;
    private final Predicate<UUID> simple;
    private final Supplier<RecentChanges> changes;

    /** Completion values for a parameter name, such as NPC ids for {@code npc}; {@code given} holds the values already typed, by name. */
    @FunctionalInterface public interface Completer { Collection<String> values(String parameter, CommandActor actor, Map<String, String> given); }

    /**
     * @param root    the {@code @Director} root command object
     * @param values  completion values for a parameter name
     * @param plugins whether a server plugin a {@link NeedsPlugin} command names is running
     * @param simple  whether a player is in Simple mode
     * @param changes where each player's reported changes are kept for {@code /animus undo}, read for each command
     */
    AnimusDirector(Object root, Completer values, Predicate<String> plugins, Predicate<UUID> simple, Supplier<RecentChanges> changes) {
        this.values = Objects.requireNonNull(values);
        this.plugins = Objects.requireNonNull(plugins);
        this.simple = Objects.requireNonNull(simple);
        this.changes = Objects.requireNonNull(changes);
        engine = DirectorEngineFactory.create(Objects.requireNonNull(root), DirectorEngineOptions.builder()
                .dispatcher((DirectorExecutionMode mode, Runnable task) -> task.run())
                .textResolver(TEXT)
                .build());
        label = engine.getRoot().getDescriptor().getName();
    }

    /** Every player reads the Advanced forms of help and replies. */
    public AnimusDirector(Object root, Completer values, Predicate<String> plugins) { this(root, values, plugins, (UUID player) -> false, kept(new RecentChanges())); }

    private static Supplier<RecentChanges> kept(RecentChanges changes) { return () -> changes; }

    /** As {@link #AnimusDirector(Object, Completer, Predicate)}, for values that do not depend on what else was typed. */
    public AnimusDirector(Object root, BiFunction<String, CommandActor, Collection<String>> values, Predicate<String> plugins) {
        this(root, (String parameter, CommandActor actor, Map<String, String> given) -> values.apply(parameter, actor), plugins);
    }

    /** As {@link #AnimusDirector(Object, BiFunction, Predicate)} on a server running every plugin a command needs. */
    public AnimusDirector(Object root, BiFunction<String, CommandActor, Collection<String>> values) { this(root, values, (String plugin) -> true); }

    public DirectorRuntimeEngine engine() { return engine; }

    /** The actor of the command running on this thread. */
    public static CommandActor actor() {
        CommandActor actor = CURRENT.get();
        if (actor == null) throw new IllegalStateException("No Animus command is running on this thread");
        return actor;
    }

    /**
     * Runs the words after {@code /animus}. Nothing at all, help words or a group name show help; {@code gui} alone opens
     * the Animus menu at Home for a player who may use it, and shows the gui commands otherwise. The player hears one
     * outcome sound: an unknown command, a value that does not map or convert, a refusal or a handler that throws plays
     * the failure tone; a help page or a handler that answers without a refusal dings. See {@link ReplyActor}.
     */
    public void execute(CommandActor actor, String[] arguments) {
        ReplyActor replies = new ReplyActor(actor, actor.player().map(simple::test).orElse(false), changes.get());
        boolean ran = false;
        try { ran = run(replies, CommandWords.tokens(arguments)); }
        finally { replies.finish(ran); }
    }

    /** Runs one command; true when it showed help or its handler returned. */
    private boolean run(ReplyActor actor, List<String> typed) {
        if (opensMenu(actor, typed)) return run(actor, List.of(ToolkitCommands.GROUP, ToolkitCommands.HOME));
        List<String> tokens = pages(actor, ToolkitCommands.expand(typed));
        if (tokens.isEmpty()) return root(actor, false, 0);
        if (HELP.contains(tokens.getFirst().toLowerCase(Locale.ROOT))) return topic(actor, tokens.subList(1, tokens.size()));
        DirectorRuntimeNode group = engine.getRoot();
        for (String token : tokens) {
            if (group.isInvocable() || token.contains("=") || HELP.contains(token.toLowerCase(Locale.ROOT))) break;
            DirectorRuntimeNode next = named(group, token);
            if (next == null) return unknown(actor, group, token);
            if (needs(next).isPresent()) return missing(actor, next);
            group = next;
        }
        Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(engine, tokens);
        if (help.isPresent()) return help.get().node() == engine.getRoot() ? root(actor, true, help.get().pageIndex()) : help(actor, help.get().node(), help.get().pageIndex());
        ActorSender sender = new ActorSender(actor);
        DirectorRoute route = engine.route(DirectorInvocation.pretokenized(sender, label, tokens));
        if (!route.invocable()) return unknown(actor, route.node() == null ? engine.getRoot() : route.node(), String.join(" ", tokens));
        DirectorRuntimeNode node = route.node();
        if (needs(node).isPresent()) return missing(actor, node);
        List<String> words = new ArrayList<>(tokens.subList(0, tokens.size() - route.remainingArgs().size()));
        words.addAll(positional(node, coordinates(node, route.remainingArgs())));
        DirectorInvocation invocation = DirectorInvocation.pretokenized(sender, label, words);
        for (DirectorRuntimeNode gate = node; gate != null; gate = gate.getParent())
            if (!gate.getDescriptor().getOrigin().validFor(actor.player().isPresent())) {
                actor.tell(Palette.error(actor.player().isPresent() ? "Console only; run /" + label + " " + String.join(" ", tokens) + " from the server console"
                        : "Players only; run it in game"));
                return false;
            }
        if (!allowed(actor, node)) { actor.tell(Palette.error(refused(node))); return false; }
        boolean hidden = hidden(node);
        Optional<String> stray = stray(node, words.subList(words.size() - route.remainingArgs().size(), words.size()));
        if (stray.isPresent()) {
            actor.tell(Palette.error(hidden ? ChoiceCommands.NOT_AVAILABLE : stray.get()));
            if (!hidden) actor.rich(ChatLine.of(Palette.PREFIX + "&7Usage: &f" + usage(node)).docs(DocsLink.ARGUMENTS));
            return false;
        }
        sender.quiet = hidden;
        actor.needs(permission(node));
        CommandActor previous = CURRENT.get();
        CURRENT.set(actor);
        DirectorExecutionResult result;
        try { result = engine.execute(invocation); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        if (result.isSuccess()) return true;
        // A hidden command answers every refusal the same way, so a forged click learns nothing.
        if (hidden) actor.tell(Palette.error(ChoiceCommands.NOT_AVAILABLE));
        else actor.rich(ChatLine.of(Palette.PREFIX + "&7Usage: &f" + usage(node)).docs(DocsLink.ARGUMENTS));
        return false;
    }

    /**
     * Whether {@code typed} is {@code gui} alone, or its alias {@code guis}, which a client suggests beside it, from a
     * player who may open the Animus menu.
     */
    private boolean opensMenu(CommandActor actor, List<String> typed) {
        if (typed.size() != 1 || !menu(actor)) return false;
        DirectorRuntimeNode gui = named(engine.getRoot(), ToolkitCommands.GUI);
        return gui != null && gui.allNames().stream().anyMatch((String name) -> name.equalsIgnoreCase(typed.getFirst()));
    }

    /** Whether {@code /animus gui} opens the menu for the actor: a player who may run {@code tools}. */
    private boolean menu(CommandActor actor) {
        DirectorRuntimeNode tools = named(engine.getRoot(), ToolkitCommands.GROUP);
        return actor.player().isPresent() && tools != null && allowed(actor, tools) && needs(tools).isEmpty();
    }

    /** Whether {@code node} is the root's {@code gui} group, which a player who may open the menu runs alone without {@code animus.gui}. */
    private boolean menuWord(CommandActor actor, DirectorRuntimeNode node) {
        return node.getParent() == engine.getRoot() && node.getDescriptor().getName().equalsIgnoreCase(ToolkitCommands.GUI) && menu(actor);
    }

    /** The root help: the short page for a player in Simple mode unless {@code all}, else page {@code index} of every command. */
    private boolean root(CommandActor actor, boolean all, int index) {
        DirectorRuntimeNode root = engine.getRoot();
        if (all || actor.player().isEmpty() || !simple.test(actor.player().get())) return help(actor, root, index);
        if (!visible(actor, root)) { actor.tell(Palette.error(refused(root))); return true; }
        List<DirectorRuntimeNode> common = new ArrayList<>();
        for (String path : AnimusHelp.COMMON) {
            if (path.equals(ToolkitCommands.GUI) && !menu(actor)) continue;
            path(List.of(path.split(" "))).filter((DirectorRuntimeNode node) -> visible(actor, node)).ifPresent(common::add);
        }
        actor.help(AnimusHelp.common(root, common));
        return true;
    }

    /**
     * {@code /animus help} and what follows it: nothing is the root help, {@code all} every command, a group its page, a
     * command its description and usage line; anything else says there is no such help. For a player who may open the
     * menu, {@code gui} answers as a command does: what {@code /animus gui} does.
     */
    private boolean topic(CommandActor actor, List<String> words) {
        if (words.isEmpty()) return root(actor, false, 0);
        if (words.size() == 1 && words.getFirst().equalsIgnoreCase(ALL)) return root(actor, true, 0);
        if (opensMenu(actor, words)) return menuHelp(actor);
        Optional<DirectorRuntimeNode> found = path(words);
        if (found.isEmpty() || hidden(found.get())) {
            actor.rich(ChatLine.error("No help for \"" + String.join(" ", words) + "\"").run("Help", "/" + label + " help", "Show the commands"));
            return true;
        }
        DirectorRuntimeNode node = found.get();
        if (needs(node).isPresent()) return missing(actor, node);
        if (!node.isInvocable()) return help(actor, node, 0);
        if (!allowed(actor, node)) { actor.tell(Palette.error(refused(node))); return true; }
        actor.tell(node.path().substring(label.length() + 2) + ": " + AnimusHelp.description(node));
        actor.rich(ChatLine.of(Palette.PREFIX + "&7Usage: &f" + usage(node)).docs(AnimusHelp.topic(node).docs()));
        return true;
    }

    /** {@code /animus help gui}: its description and usage, then {@code [GUI commands]} for a player who may run them. */
    private boolean menuHelp(CommandActor actor) {
        String gui = "/" + label + " " + ToolkitCommands.GUI;
        actor.tell(ToolkitCommands.GUI + ": " + AnimusHelp.MENU);
        ChatLine usage = ChatLine.of(Palette.PREFIX + "&7Usage: &f" + gui).docs(DocsLink.TOOLS);
        DirectorRuntimeNode group = named(engine.getRoot(), ToolkitCommands.GUI);
        if (group != null && allowed(actor, group)) usage.run("GUI commands", gui + " help", "Show the chest GUI commands");
        actor.rich(usage);
        return true;
    }

    /** The node {@code words} name from the root, by name or alias; the words after a command are its values and are ignored. */
    private Optional<DirectorRuntimeNode> path(List<String> words) {
        DirectorRuntimeNode cursor = engine.getRoot();
        for (String word : words) {
            if (cursor.isInvocable()) break;
            cursor = named(cursor, word);
            if (cursor == null) return Optional.empty();
        }
        return cursor == engine.getRoot() ? Optional.empty() : Optional.of(cursor);
    }

    /**
     * A word that names nothing in {@code group}: the reason in red with the nearest command when one is close, as a chip
     * that fills it in, then {@code [Help]} for the group.
     */
    private boolean unknown(CommandActor actor, DirectorRuntimeNode group, String word) {
        DirectorRuntimeNode shown = group.isInvocable() && group.getParent() != null ? group.getParent() : group;
        ChatLine line = ChatLine.error("Unknown command \"" + word + "\"" + (shown.getParent() == null ? "" : " in " + shown.path()));
        List<String> names = shown.getChildren().stream().filter((DirectorRuntimeNode child) -> visible(actor, child))
                .map((DirectorRuntimeNode child) -> child.getDescriptor().getName()).toList();
        nearest(names, word).ifPresent((String name) -> line.text("&c. Did you mean ")
                .command(name, Palette.PINK, shown.path() + " " + name + " ", false, "Click to put it in your chat box").text("&c?"));
        actor.rich(line.run("Help", shown.path() + " help", "Show the commands"));
        return false;
    }

    /**
     * The name closest to {@code typed}: within two edits and closer than the word is long, else the shortest name it
     * begins, for two letters or more; empty when none is close.
     */
    static Optional<String> nearest(List<String> names, String typed) {
        String word = typed.toLowerCase(Locale.ROOT);
        Optional<String> close = names.stream().filter((String name) -> { int edits = edits(name, word); return edits <= 2 && edits < word.length(); })
                .min(java.util.Comparator.comparingInt((String name) -> edits(name, word)).thenComparing(java.util.Comparator.naturalOrder()));
        if (close.isPresent() || word.length() < 2) return close;
        return names.stream().filter((String name) -> name.startsWith(word))
                .min(java.util.Comparator.comparingInt(String::length).thenComparing(java.util.Comparator.naturalOrder()));
    }

    /** Levenshtein distance. */
    private static int edits(String from, String to) {
        int[] previous = new int[to.length() + 1], current = new int[to.length() + 1];
        for (int column = 0; column <= to.length(); column++) previous[column] = column;
        for (int row = 1; row <= from.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= to.length(); column++)
                current[column] = Math.min(Math.min(current[column - 1], previous[column]) + 1, previous[column - 1] + (from.charAt(row - 1) == to.charAt(column - 1) ? 0 : 1));
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[to.length()];
    }

    /** A command whose plugin is not running: only that it needs the plugin. */
    private boolean missing(CommandActor actor, DirectorRuntimeNode node) {
        actor.tell(Palette.error(node.path() + " needs " + needs(node).orElseThrow()));
        return false;
    }

    /** The plugin the node or a group above it needs while that plugin is not running. */
    public Optional<String> needs(DirectorRuntimeNode node) {
        for (DirectorRuntimeNode cursor = node; cursor != null; cursor = cursor.getParent()) {
            NeedsPlugin annotation = cursor.getMethod() != null ? cursor.getMethod().getAnnotation(NeedsPlugin.class)
                    : cursor.getInstance().getClass().getAnnotation(NeedsPlugin.class);
            if (annotation != null && !plugins.test(annotation.value())) return Optional.of(annotation.value());
        }
        return Optional.empty();
    }

    private static DirectorRuntimeNode named(DirectorRuntimeNode group, String word) {
        for (DirectorRuntimeNode child : group.getChildren())
            if (child.allNames().stream().anyMatch((String name) -> name.equalsIgnoreCase(word))) return child;
        return null;
    }

    /**
     * A page bar's {@code page=N} after a group, such as {@code /animus page=2}, asks for that page of its help, as
     * {@code help N} does; help's own links write {@code help N} so a client's command tree can parse them.
     */
    private List<String> pages(CommandActor actor, List<String> tokens) {
        if (tokens.size() >= 2 && tokens.get(tokens.size() - 2).equalsIgnoreCase("help") && tokens.getLast().matches("\\d{1,4}")) {
            List<String> asked = new ArrayList<>(tokens.subList(0, tokens.size() - 2));
            asked.add("help=" + tokens.getLast());
            return asked;
        }
        if (tokens.isEmpty() || !tokens.getLast().toLowerCase(Locale.ROOT).startsWith("page=")) return tokens;
        if (engine.route(DirectorInvocation.pretokenized(new ActorSender(actor), label, tokens)).invocable()) return tokens;
        List<String> asked = new ArrayList<>(tokens);
        asked.set(asked.size() - 1, "help=" + tokens.getLast().substring("page=".length()));
        return asked;
    }

    /**
     * Vanilla-style coordinates without keys: after a command's required values, three bare coordinate words, such as
     * {@code ~ ~1 ~} or {@code 12 64 -30}, fill its optional {@code x}, {@code y} and {@code z}.
     */
    static List<String> coordinates(DirectorRuntimeNode node, List<String> words) {
        List<DirectorRuntimeParameter> axes = Coordinates.AXES.stream().map((String axis) -> parameter(node, axis)).toList();
        if (axes.stream().anyMatch((DirectorRuntimeParameter axis) -> axis == null || axis.getDescriptor().isRequired())) return words;
        Set<DirectorRuntimeParameter> keyed = keyed(node, words);
        if (axes.stream().anyMatch(keyed::contains)) return words;
        long required = required(node, keyed).size();
        List<String> out = new ArrayList<>(words);
        int bare = 0;
        for (int index = 0; index < out.size(); index++) {
            if (keyed(node, out.get(index)) != null || bare++ < required) continue;
            if (index + 2 < out.size() && Coordinates.coordinate(out.get(index)) && Coordinates.coordinate(out.get(index + 1)) && Coordinates.coordinate(out.get(index + 2)))
                for (int axis = 0; axis < 3; axis++) out.set(index + axis, Coordinates.AXES.get(axis) + "=" + out.get(index + axis));
            break;
        }
        return out;
    }

    /**
     * After a command's required values, bare words fill the optional parameters its {@link Positional} lists, in that
     * order, skipping any already given by key: {@code select guard} reads as {@code select npc=guard}.
     */
    static List<String> positional(DirectorRuntimeNode node, List<String> words) {
        List<DirectorRuntimeParameter> open = open(node, keyed(node, words));
        if (open.isEmpty()) return words;
        long required = required(node, keyed(node, words)).size();
        List<String> out = new ArrayList<>(words);
        int bare = 0, next = 0;
        for (int index = 0; index < out.size() && next < open.size(); index++) {
            if (!bare(node, out.get(index)) || bare++ < required) continue;
            out.set(index, open.get(next++).getDescriptor().getName() + "=" + out.get(index));
        }
        return out;
    }

    /** The optional parameters {@link Positional} lists that {@code keyed} does not give yet, in order. */
    private static List<DirectorRuntimeParameter> open(DirectorRuntimeNode node, Set<DirectorRuntimeParameter> keyed) {
        Positional positional = node.getMethod() == null ? null : node.getMethod().getAnnotation(Positional.class);
        if (positional == null) return List.of();
        List<DirectorRuntimeParameter> open = new ArrayList<>();
        for (String name : positional.value()) {
            DirectorRuntimeParameter parameter = parameter(node, name);
            if (parameter != null && !keyed.contains(parameter)) open.add(parameter);
        }
        return open;
    }

    /**
     * The refusal for the first bare word no parameter takes, naming the key to write it with, such as
     * {@code Unexpected "1.5"; write value=1.5}, or that the command takes nothing more.
     */
    static Optional<String> stray(DirectorRuntimeNode node, List<String> words) {
        Set<DirectorRuntimeParameter> keyed = keyed(node, words);
        long required = required(node, keyed).size();
        int bare = 0;
        for (String word : words) {
            if (!bare(node, word) || bare++ < required) continue;
            Optional<DirectorRuntimeParameter> free = node.getParameters().stream().filter((DirectorRuntimeParameter parameter) -> !parameter.getDescriptor().isRequired()
                    && !parameter.getDescriptor().isContextual() && !keyed.contains(parameter)).findFirst();
            return Optional.of("Unexpected \"" + word + "\"; " + free.map((DirectorRuntimeParameter parameter) -> "write " + parameter.getDescriptor().getName() + "=" + word)
                    .orElse(node.path() + " takes no more values"));
        }
        return Optional.empty();
    }

    /** A word that gives no key: not {@code key=value} for this command, nor anything else shaped like a key Director refuses. */
    private static boolean bare(DirectorRuntimeNode node, String word) { return keyed(node, word) == null && !KEY.matcher(word).matches(); }

    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_-]*=.*", java.util.regex.Pattern.DOTALL);

    /** {@code /animus path <required> [optional=default]}; an empty default reads {@code [key=]}. */
    public static String usage(DirectorRuntimeNode node) {
        StringBuilder usage = new StringBuilder(node.path());
        for (DirectorParameterDescriptor parameter : node.getDescriptor().getParameters())
            if (!parameter.isContextual()) usage.append(' ').append(AnimusHelp.chip(parameter));
        return usage.toString();
    }

    /** The refusal for a node the actor may not use, naming the permission an operator grants. */
    static String refused(DirectorRuntimeNode node) {
        return "No permission: " + permission(node) + "; ask an operator to grant it";
    }

    /** One page of help for a group, listing what the actor may run, by topic at the root. True, after a refusal too. */
    private boolean help(CommandActor actor, DirectorRuntimeNode node, int page) {
        DirectorRuntimeNode group = node.isInvocable() && node.getParent() != null ? node.getParent() : node;
        if (!visible(actor, group)) { actor.tell(Palette.error(refused(group))); return true; }
        List<DirectorRuntimeNode> entries = group.getChildren().stream().filter((DirectorRuntimeNode child) -> visible(actor, child)).toList();
        actor.help(AnimusHelp.page(group, entries, page));
        return true;
    }

    /**
     * Suggestions for the word being typed, the last of {@code arguments}. Group and command names come first; inside a
     * command, bare values only for the next required parameter, then vanilla-style coordinates where it takes them, and
     * {@code key=} for the optional ones. After {@code help}, names only, and {@code all}. A player hears the typing chime
     * on every request, as in every Volmit plugin.
     */
    public List<String> complete(CommandActor actor, String[] arguments) {
        if (actor.player().isPresent()) actor.play(CommandSound.TYPING);
        List<String> words = new ArrayList<>(List.of(arguments));
        String current = words.isEmpty() ? "" : words.removeLast();
        words.removeIf(String::isEmpty);
        boolean help = !words.isEmpty() && HELP.contains(words.getFirst().toLowerCase(Locale.ROOT));
        if (help) words.removeFirst();
        DirectorRuntimeNode cursor = engine.getRoot();
        int index = 0;
        while (index < words.size() && !cursor.isInvocable()) {
            cursor = child(actor, cursor, words.get(index));
            if (cursor == null) return List.of();
            index++;
        }
        if (!cursor.isInvocable()) {
            List<String> names = new ArrayList<>();
            for (DirectorRuntimeNode child : cursor.getChildren())
                if (visible(actor, child) && starts(child.getDescriptor().getName(), current)) names.add(child.getDescriptor().getName());
            if (help && cursor == engine.getRoot() && starts(ALL, current)) names.add(ALL);
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
        if (help || !allowed(actor, cursor)) return List.of();
        return parameters(actor, cursor, words.subList(index, words.size()), current);
    }

    private List<String> parameters(CommandActor actor, DirectorRuntimeNode node, List<String> before, String current) {
        List<String> typed = positional(node, before);
        Set<DirectorRuntimeParameter> keyed = keyed(node, typed);
        List<String> bare = typed.stream().filter((String word) -> keyed(node, word) == null).toList();
        List<DirectorRuntimeParameter> required = required(node, keyed);
        Map<String, String> given = given(node, typed, required);
        int equals = current.indexOf('=');
        if (equals > 0) {
            DirectorRuntimeParameter parameter = parameter(node, current.substring(0, equals));
            if (parameter == null || parameter.getDescriptor().isContextual()) return List.of();
            String key = current.substring(0, equals + 1), prefix = current.substring(equals + 1);
            return values(actor, parameter, given).stream().filter((String value) -> starts(value, prefix)).limit(SUGGESTIONS).map((String value) -> key + value).toList();
        }
        List<String> suggestions = new ArrayList<>();
        List<DirectorRuntimeParameter> open = open(node, keyed);
        if (bare.size() < required.size())
            values(actor, required.get(bare.size()), given).stream().filter((String value) -> starts(value, current)).limit(SUGGESTIONS).forEach(suggestions::add);
        else if (!open.isEmpty())
            values(actor, open.getFirst(), given).stream().filter((String value) -> starts(value, current)).limit(SUGGESTIONS).forEach(suggestions::add);
        else suggestions.addAll(positions(actor, node, keyed, bare.subList(required.size(), bare.size()), current));
        for (DirectorRuntimeParameter parameter : node.getParameters()) {
            DirectorParameterDescriptor descriptor = parameter.getDescriptor();
            if (!descriptor.isRequired() && !descriptor.isContextual() && !keyed.contains(parameter) && starts(descriptor.getName() + "=", current))
                suggestions.add(descriptor.getName() + "=");
        }
        return suggestions;
    }

    /** The values already typed, by parameter name: keyed ones, and bare ones in the order the required parameters take them. */
    private static Map<String, String> given(DirectorRuntimeNode node, List<String> typed, List<DirectorRuntimeParameter> required) {
        Map<String, String> given = new LinkedHashMap<>();
        int bare = 0;
        for (String word : typed) {
            DirectorRuntimeParameter parameter = keyed(node, word);
            if (parameter != null) given.put(parameter.getDescriptor().getName(), word.substring(word.indexOf('=') + 1));
            else if (bare < required.size()) given.put(required.get(bare++).getDescriptor().getName(), word);
        }
        return given;
    }

    /**
     * As vanilla offers them: {@code ~ ~ ~}, then the block the player looks at, each cut to the axes not yet typed. Only
     * for a player, after a command's required values, where the command takes {@code x}, {@code y} and {@code z}.
     */
    private static List<String> positions(CommandActor actor, DirectorRuntimeNode node, Set<DirectorRuntimeParameter> keyed, List<String> typed, String current) {
        if (actor.position().isEmpty() || typed.size() >= 3 || !typed.stream().allMatch(Coordinates::coordinate)) return List.of();
        for (String axis : Coordinates.AXES) {
            DirectorRuntimeParameter parameter = parameter(node, axis);
            if (parameter == null || parameter.getDescriptor().isRequired() || keyed.contains(parameter)) return List.of();
        }
        List<String> options = new ArrayList<>();
        options.add(String.join(" ", Collections.nCopies(3 - typed.size(), "~")));
        actor.target().ifPresent((Vec3 block) -> {
            List<String> values = List.of(String.valueOf((long) Math.floor(block.x())), String.valueOf((long) Math.floor(block.y())), String.valueOf((long) Math.floor(block.z())));
            options.add(String.join(" ", values.subList(typed.size(), 3)));
        });
        return options.stream().filter((String option) -> starts(option, current)).toList();
    }

    /** The parameters {@code words} give by key. */
    private static Set<DirectorRuntimeParameter> keyed(DirectorRuntimeNode node, List<String> words) {
        Set<DirectorRuntimeParameter> keyed = new LinkedHashSet<>();
        for (String word : words) { DirectorRuntimeParameter parameter = keyed(node, word); if (parameter != null) keyed.add(parameter); }
        return keyed;
    }

    /** The parameter a {@code key=value} word names, or null for a bare word. */
    private static DirectorRuntimeParameter keyed(DirectorRuntimeNode node, String word) {
        int equals = word.indexOf('=');
        return equals > 0 ? parameter(node, word.substring(0, equals)) : null;
    }

    /** The required parameters bare words still fill, in order. */
    private static List<DirectorRuntimeParameter> required(DirectorRuntimeNode node, Set<DirectorRuntimeParameter> keyed) {
        return node.getParameters().stream().filter((DirectorRuntimeParameter parameter) -> parameter.getDescriptor().isRequired()
                && !parameter.getDescriptor().isContextual() && !keyed.contains(parameter)).toList();
    }

    private List<String> values(CommandActor actor, DirectorRuntimeParameter parameter, Map<String, String> given) {
        Class<?> type = parameter.getDescriptor().getType();
        if (type.isEnum()) return java.util.Arrays.stream(type.getEnumConstants()).map((Object constant) -> ((Enum<?>) constant).name().toLowerCase(Locale.ROOT)).toList();
        if (type == boolean.class || type == Boolean.class) return List.of("true", "false");
        try {
            Collection<String> known = values.values(parameter.getDescriptor().getName(), actor, given);
            return known == null ? List.of() : known.stream().filter(Objects::nonNull).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (RuntimeException unavailable) { return List.of(); }
    }

    private static DirectorRuntimeParameter parameter(DirectorRuntimeNode node, String key) {
        for (DirectorRuntimeParameter parameter : node.getParameters()) {
            if (parameter.getDescriptor().getName().equalsIgnoreCase(key)) return parameter;
            for (String alias : parameter.getDescriptor().getAliases()) if (alias.equalsIgnoreCase(key)) return parameter;
        }
        return null;
    }

    private DirectorRuntimeNode child(CommandActor actor, DirectorRuntimeNode group, String word) {
        for (DirectorRuntimeNode child : group.getChildren())
            if (visible(actor, child) && child.allNames().stream().anyMatch((String name) -> name.equalsIgnoreCase(word))) return child;
        return null;
    }

    private static boolean starts(String value, String prefix) { return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)); }

    /** The node this command needs: the nearest {@link AnimusPermission} on it or a group above it, else {@link #ADMIN}. */
    public static String permission(DirectorRuntimeNode node) {
        for (DirectorRuntimeNode cursor = node; cursor != null; cursor = cursor.getParent()) {
            AnimusPermission annotation = cursor.getMethod() != null ? cursor.getMethod().getAnnotation(AnimusPermission.class)
                    : cursor.getInstance().getClass().getAnnotation(AnimusPermission.class);
            if (annotation != null) return annotation.value();
        }
        return ADMIN;
    }

    /** Whether the actor may run the node: its own {@link AnimusPermission}, else the nearest group's, else {@link #ADMIN}. */
    public static boolean allowed(CommandActor actor, DirectorRuntimeNode node) {
        String permission = permission(node);
        return permission.isEmpty() || actor.permitted(permission);
    }

    /**
     * Whether help and completion list the node: its plugin runs, and the actor may run it, or for a group, something
     * inside it; {@code gui} for every player who may open the menu with it.
     */
    public boolean visible(CommandActor actor, DirectorRuntimeNode node) {
        if (node.getDescriptor().isHidden() || needs(node).isPresent()) return false;
        if (allowed(actor, node) || menuWord(actor, node)) return true;
        if (node.isInvocable()) return false;
        for (DirectorRuntimeNode child : node.getChildren()) if (visible(actor, child)) return true;
        return false;
    }

    private static boolean hidden(DirectorRuntimeNode node) {
        for (DirectorRuntimeNode cursor = node; cursor != null; cursor = cursor.getParent()) if (cursor.getDescriptor().isHidden()) return true;
        return false;
    }

    /** Director's own refusals, such as a missing argument, reach the actor in semantic red. */
    private static final class ActorSender implements DirectorSender {
        private final CommandActor actor;
        private boolean quiet;

        private ActorSender(CommandActor actor) { this.actor = actor; }

        @Override public String getName() { return actor.player().map(Object::toString).orElse("Console"); }
        @Override public boolean isPlayer() { return actor.player().isPresent(); }
        @Override public void sendMessage(String message) {
            if (!quiet && message != null && !message.isBlank()) actor.tell(Palette.error(message));
        }
    }
}
