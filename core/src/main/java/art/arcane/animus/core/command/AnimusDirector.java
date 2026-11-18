package art.arcane.animus.core.command;

import art.arcane.animus.core.owner.NpcOwnership;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs {@code /animus} through VolmLib's Director, the same on every platform. Bare words bind to required parameters,
 * then to the optional ones a command lists in {@link Positional}; any other optional one is {@code key=value}. Unknown
 * keys and leftover words fail with the command's usage and the key to write. A player who leaves out a command's NPC
 * gets their selection, and the reply says so.
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
        if (key.id().equals(DirectorHelpMessages.PARAMETER_TYPE.id())) return takes(String.valueOf(arguments.require("type").value()));
        if (key.id().equals(DirectorHelpMessages.NO_PARAMETERS.id())) return "Click to run it.";
        if (key.id().equals(DirectorRuntimeMessages.EXECUTION_FAILED.id())) return "";
        String text = DirectorTextResolver.ENGLISH.resolve(key.id().equals(UNEXPECTED.id()) ? UNEXPECTED : key, arguments);
        return key.id().equals(DirectorRuntimeMessages.MISSING_ARGUMENT.id()) ? text.replaceFirst(" \\([^()]*\\)$", "") : text;
    };

    /** What a parameter of a Java type takes, in words, for its hover in help. */
    private static String takes(String type) {
        return switch (type) {
            case "int", "Integer", "long", "Long", "short", "Short", "double", "Double", "float", "Float" -> "Takes a number.";
            case "boolean", "Boolean" -> "Takes true or false.";
            case "String" -> "Takes a word, or text in quotes.";
            default -> "Takes one of the words tab completion offers.";
        };
    }

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
    /** What the running handler threw, for the log; the actor reads only that the command failed. */
    private static final ThreadLocal<Throwable> THREW = new ThreadLocal<>();
    private final String label;
    private final DirectorRuntimeEngine engine;
    private final Completer values;
    private final Predicate<String> plugins;
    private final Function<NeedsFeature.Feature, Optional<String>> features;
    private final Predicate<UUID> simple;
    private final Supplier<RecentChanges> changes;
    private final Predicate<UUID> shopOwner;

    /**
     * Completion values for a parameter of a command, such as NPC ids for {@code npc}. {@code command} is the command's words
     * after the root, such as {@code gui create}, for a parameter whose values depend on the command; {@code given} holds
     * the values already typed, by name.
     */
    @FunctionalInterface public interface Completer { Collection<String> values(String command, String parameter, CommandActor actor, Map<String, String> given); }

    /**
     * @param root    the {@code @Director} root command object
     * @param values  completion values for a parameter name
     * @param plugins whether a server plugin a {@link NeedsPlugin} command names is running
     * @param features why this platform lacks a {@link NeedsFeature} feature, empty where it has it
     * @param simple  whether a player is in Simple mode
     * @param changes where each player's reported changes are kept for {@code /animus undo}, read for each command
     * @param shopOwner whether a player owns a chest shop, so the short page lists {@code shop manage}
     */
    AnimusDirector(Object root, Completer values, Predicate<String> plugins, Function<NeedsFeature.Feature, Optional<String>> features, Predicate<UUID> simple,
                   Supplier<RecentChanges> changes, Predicate<UUID> shopOwner) {
        this.values = Objects.requireNonNull(values);
        this.plugins = Objects.requireNonNull(plugins);
        this.features = Objects.requireNonNull(features);
        this.simple = Objects.requireNonNull(simple);
        this.changes = Objects.requireNonNull(changes);
        this.shopOwner = Objects.requireNonNull(shopOwner);
        engine = DirectorEngineFactory.create(Objects.requireNonNull(root), DirectorEngineOptions.builder()
                .dispatcher((DirectorExecutionMode mode, Runnable task) -> {
                    try { task.run(); }
                    catch (RuntimeException | Error failure) { THREW.set(failure); throw failure; }
                })
                .textResolver(TEXT)
                .build());
        label = engine.getRoot().getDescriptor().getName();
    }

    /** Every player reads the Advanced forms of help and replies. */
    public AnimusDirector(Object root, Completer values, Predicate<String> plugins) { this(root, values, plugins, EVERY_FEATURE); }

    /** As {@link #AnimusDirector(Object, Completer, Predicate)} on a platform that lacks the features {@code features} names a reason for. */
    public AnimusDirector(Object root, Completer values, Predicate<String> plugins, Function<NeedsFeature.Feature, Optional<String>> features) {
        this(root, values, plugins, features, (UUID player) -> false, kept(new RecentChanges()), (UUID player) -> false);
    }

    /** A platform with every {@link NeedsFeature} feature. */
    static final Function<NeedsFeature.Feature, Optional<String>> EVERY_FEATURE = (NeedsFeature.Feature feature) -> Optional.empty();

    private static Supplier<RecentChanges> kept(RecentChanges changes) { return () -> changes; }

    /** As {@link #AnimusDirector(Object, Completer, Predicate)}, for values that do not depend on what else was typed. */
    public AnimusDirector(Object root, BiFunction<String, CommandActor, Collection<String>> values, Predicate<String> plugins) {
        this(root, (String command, String parameter, CommandActor actor, Map<String, String> given) -> values.apply(parameter, actor), plugins);
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
        if (menuRefused(actor, typed)) return false;
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
        List<String> given = selected(actor, node, route.remainingArgs());
        words.addAll(positional(node, coordinates(node, given)));
        DirectorInvocation invocation = DirectorInvocation.pretokenized(sender, label, words);
        for (DirectorRuntimeNode gate = node; gate != null; gate = gate.getParent())
            if (!gate.getDescriptor().getOrigin().validFor(actor.player().isPresent())) {
                actor.tell(Palette.error(actor.player().isPresent() ? "Console only; run it from the server console: /" + label + " " + String.join(" ", tokens)
                        : "Players only; run it in game"));
                return false;
            }
        if (!allowed(actor, node)) { actor.tell(Palette.error(refused(actor, node))); return false; }
        boolean hidden = hidden(node);
        Optional<String> stray = stray(node, words.subList(words.size() - given.size(), words.size()));
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
        Throwable threw;
        try { result = engine.execute(invocation); }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            threw = THREW.get();
            THREW.remove();
        }
        if (result.isSuccess()) return true;
        if (threw != null) {
            System.getLogger("Animus").log(System.Logger.Level.ERROR, node.path() + " failed for " + sender.getName(), threw);
            actor.tell(Palette.error(hidden ? ChoiceCommands.NOT_AVAILABLE : failed(node.path())));
            return false;
        }
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

    /**
     * Whether {@code typed} is {@code gui} alone from a player who may neither open the menu nor run a gui command, after
     * naming the node that opens the menu, which is what the word asks for. Anyone with a gui command reads their help.
     */
    private boolean menuRefused(CommandActor actor, List<String> typed) {
        DirectorRuntimeNode gui = named(engine.getRoot(), ToolkitCommands.GUI), tools = named(engine.getRoot(), ToolkitCommands.GROUP);
        if (typed.size() != 1 || actor.player().isEmpty() || gui == null || tools == null || visible(actor, gui)) return false;
        if (gui.allNames().stream().noneMatch((String name) -> name.equalsIgnoreCase(typed.getFirst()))) return false;
        actor.tell(Palette.error(refused(tools)));
        return true;
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
            if (path.equals(AnimusHelp.SHOP_MANAGE) && !shopOwner.test(actor.player().get())) continue;
            path(List.of(path.split(" "))).filter((DirectorRuntimeNode node) -> visible(actor, node)).ifPresent(common::add);
        }
        actor.help(AnimusHelp.common(root, everyday(common) ? withRunnable(actor, common) : common));
        return true;
    }

    /** Whether the short page holds no staff command, only the shop entries a player who makes shops gets, or nothing. */
    private boolean everyday(List<DirectorRuntimeNode> common) {
        return common.stream().allMatch((DirectorRuntimeNode node) -> shopGroup(node));
    }

    private boolean shopGroup(DirectorRuntimeNode node) {
        return node.getParent() != null && node.getParent().getParent() == engine.getRoot() && node.getParent().getDescriptor().getName().equals("shop");
    }

    /** What the player may run outside the shop commands, then the shop entries the short page chose for them. */
    private List<DirectorRuntimeNode> withRunnable(CommandActor actor, List<DirectorRuntimeNode> shop) {
        List<DirectorRuntimeNode> shown = new ArrayList<>(runnable(actor).stream().filter((DirectorRuntimeNode node) -> !shopGroup(node)).toList());
        shown.addAll(shop);
        return shown;
    }

    /** The commands the actor may run, by root topic, for a player who may run none of the common ones; at most a short page. */
    private List<DirectorRuntimeNode> runnable(CommandActor actor) {
        List<DirectorRuntimeNode> found = new ArrayList<>();
        for (DirectorRuntimeNode top : AnimusHelp.page(engine.getRoot(), engine.getRoot().getChildren(), 0).entries()) commands(actor, top, found);
        return found.subList(0, Math.min(found.size(), AnimusHelp.SHORT));
    }

    private void commands(CommandActor actor, DirectorRuntimeNode node, List<DirectorRuntimeNode> found) {
        if (!visible(actor, node)) return;
        if (node.isInvocable()) { found.add(node); return; }
        for (DirectorRuntimeNode child : node.getChildren()) commands(actor, child, found);
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
        if (menuRefused(actor, words)) return true;
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

    /** A command whose plugin is not running, or whose feature this platform lacks: only why. */
    private boolean missing(CommandActor actor, DirectorRuntimeNode node) {
        actor.tell(Palette.error(needs(node).orElseThrow()));
        return false;
    }

    /**
     * Why the node cannot run here, in one sentence: a plugin it or a group above it needs is not running, this platform
     * lacks a feature it needs, or it is a group where nothing inside runs. Empty when it can run.
     */
    public Optional<String> needs(DirectorRuntimeNode node) {
        for (DirectorRuntimeNode cursor = node; cursor != null; cursor = cursor.getParent()) {
            NeedsPlugin plugin = annotation(cursor, NeedsPlugin.class);
            if (plugin != null && !plugins.test(plugin.value())) return Optional.of(needsPlugin(cursor.path(), plugin.value()));
            NeedsFeature feature = annotation(cursor, NeedsFeature.class);
            if (feature != null) {
                Optional<String> lacking = features.apply(feature.value());
                if (lacking.isPresent()) return lacking;
            }
        }
        return node.isInvocable() || node.getChildren().isEmpty() ? Optional.empty() : inside(node);
    }

    /** For a group: the first child's reason when no child runs here, else empty. */
    private Optional<String> inside(DirectorRuntimeNode group) {
        Optional<String> first = Optional.empty();
        for (DirectorRuntimeNode child : group.getChildren()) {
            if (child.getDescriptor().isHidden()) continue;
            Optional<String> reason = child.isInvocable() || child.getChildren().isEmpty() ? own(child) : inside(child).or(() -> own(child));
            if (reason.isEmpty()) return Optional.empty();
            if (first.isEmpty()) first = reason;
        }
        return first;
    }

    /** Why this node alone, not the groups above it, cannot run here. */
    private Optional<String> own(DirectorRuntimeNode node) {
        NeedsPlugin plugin = annotation(node, NeedsPlugin.class);
        if (plugin != null && !plugins.test(plugin.value())) return Optional.of(needsPlugin(node.path(), plugin.value()));
        NeedsFeature feature = annotation(node, NeedsFeature.class);
        return feature == null ? Optional.empty() : features.apply(feature.value());
    }

    private static <A extends java.lang.annotation.Annotation> A annotation(DirectorRuntimeNode node, Class<A> type) {
        return node.getMethod() != null ? node.getMethod().getAnnotation(type) : node.getInstance().getClass().getAnnotation(type);
    }

    static DirectorRuntimeNode named(DirectorRuntimeNode group, String word) {
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
     * A player's left-out NPC: where a command needs an {@code npc} that no word gives, {@code npc=@selected} goes in front,
     * and the actor notes it, so the command says the selection stood in. A word in the NPC's place that names an NPC is
     * the NPC, and what is missing is another value. The console has no selection and is told the value is missing.
     */
    private List<String> selected(ReplyActor actor, DirectorRuntimeNode node, List<String> words) {
        DirectorRuntimeParameter npc = parameter(node, NPC);
        if (actor.player().isEmpty() || npc == null || npc.getDescriptor().isContextual() || !npc.getDescriptor().isRequired()) return words;
        Set<DirectorRuntimeParameter> keyed = keyed(node, words);
        List<DirectorRuntimeParameter> required = required(node, keyed);
        int slot = required.indexOf(npc);
        List<String> bare = words.stream().filter((String word) -> bare(node, word)).toList();
        if (slot < 0 || bare.size() >= required.size()) return words;
        if (bare.size() > slot && names(actor, bare.get(slot))) return words;
        List<String> out = new ArrayList<>(words.size() + 1);
        out.add(NPC + "=" + CommandGroup.SELECTED);
        out.addAll(words);
        actor.selectionStandsIn();
        return out;
    }

    private static final String NPC = "npc";

    /** Whether {@code word} names an NPC the actor may use: an id completion offers, a folder path or {@code @selected}. */
    private boolean names(CommandActor actor, String word) {
        if (word.indexOf('/') >= 0 || word.equalsIgnoreCase(CommandGroup.SELECTED)) return true;
        try {
            Collection<String> ids = values.values("", NPC, actor, Map.of());
            return ids != null && ids.stream().anyMatch(word::equalsIgnoreCase);
        } catch (RuntimeException unavailable) { return false; }
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
                    .orElse(complete(node.path())));
        }
        return Optional.empty();
    }

    // These replies end a clause with the command they name, so the words after it never read as its values.

    /** The reply when a command's handler threw. */
    static String failed(String path) { return "Could not finish " + path + "; the server log has the cause"; }

    /** Why a command cannot run: the plugin it needs is not running. */
    static String needsPlugin(String path, String plugin) { return plugin + " is needed for " + path; }

    /** After {@code Unexpected "word"; }: the command has every value it takes. */
    static String complete(String path) { return "nothing more fits in " + path; }

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
        AnimusHelp.Page shown = AnimusHelp.page(group, entries, page);
        actor.help(shown);
        if (actor.player().isPresent()) CommandGroup.pastTheEnd(actor, page + 1, AnimusHelp.pageCount(shown));
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
            return values(actor, node, parameter, given).stream().filter((String value) -> starts(value, prefix)).limit(SUGGESTIONS).map((String value) -> key + value).toList();
        }
        List<String> suggestions = new ArrayList<>();
        List<DirectorRuntimeParameter> open = open(node, keyed);
        if (bare.size() < required.size())
            values(actor, node, required.get(bare.size()), given).stream().filter((String value) -> starts(value, current)).limit(SUGGESTIONS).forEach(suggestions::add);
        else if (!open.isEmpty())
            values(actor, node, open.getFirst(), given).stream().filter((String value) -> starts(value, current)).limit(SUGGESTIONS).forEach(suggestions::add);
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

    private List<String> values(CommandActor actor, DirectorRuntimeNode node, DirectorRuntimeParameter parameter, Map<String, String> given) {
        Class<?> type = parameter.getDescriptor().getType();
        if (type.isEnum()) return java.util.Arrays.stream(type.getEnumConstants()).map((Object constant) -> ((Enum<?>) constant).name().toLowerCase(Locale.ROOT)).toList();
        if (type == boolean.class || type == Boolean.class) return List.of("true", "false");
        try {
            Collection<String> known = values.values(node.path().substring(Math.min(node.path().length(), label.length() + 2)), parameter.getDescriptor().getName(), actor, given);
            return known == null ? List.of() : known.stream().filter(Objects::nonNull).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (RuntimeException unavailable) { return List.of(); }
    }

    static DirectorRuntimeParameter parameter(DirectorRuntimeNode node, String key) {
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
        if (PermissionNodes.EVERY_NPC.contains(permission) && !NpcOwnership.admin(actor.player(), actor::permitted)) return false;
        return permission.isEmpty() || actor.permitted(permission);
    }

    /** The refusal for {@code node}: the node to ask for, or that only an admin may, for a node that reaches every NPC. */
    static String refused(CommandActor actor, DirectorRuntimeNode node) {
        String permission = permission(node);
        if (PermissionNodes.EVERY_NPC.contains(permission) && actor.permitted(permission)) return "Only an admin can do that; it changes NPCs whoever owns them";
        return refused(node);
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
