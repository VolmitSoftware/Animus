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
import java.util.function.BiFunction;

/**
 * Runs {@code /animus} through VolmLib's Director, the same on every platform. Bare words bind to required parameters,
 * then to the optional ones a command lists in {@link Positional}; any other optional one is {@code key=value}. Unknown
 * keys and leftover words fail with the command's usage and the key to write.
 * Commands carry their node in {@link AnimusPermission}; help and completion show only what the actor may run.
 * Call {@link #execute} and {@link #complete} on the actor's owner; commands read the actor with {@link #actor()}.
 */
public final class AnimusDirector {
    /** Every node without its own {@link AnimusPermission} needs this one. */
    public static final String ADMIN = PermissionNodes.ADMIN;
    /** Help in Animus's pinks on grey. */
    public static final DirectorMiniMenu.Theme THEME = new DirectorMiniMenu.Theme("#FF5FA8", "#E0408C", "#E0408C", "#555555", "#AAAAAA", "#FF5FA8", "#FFB3D6", "#777777");
    private static final TextKey UNEXPECTED = TextKey.of(DirectorRuntimeMessages.UNEXPECTED_ARGUMENT.id(),
            "Unexpected \"{argument}\"; optional values are key=value");
    /** Director's English, with Animus's own example for an unkeyed optional value and no Java type on a missing argument. */
    public static final DirectorTextResolver TEXT = (TextKey key, MessageArgs arguments) -> {
        String text = DirectorTextResolver.ENGLISH.resolve(key.id().equals(UNEXPECTED.id()) ? UNEXPECTED : key, arguments);
        return key.id().equals(DirectorRuntimeMessages.MISSING_ARGUMENT.id()) ? text.replaceFirst(" \\([^()]*\\)$", "") : text;
    };
    private static final int SUGGESTIONS = 100;
    private static final Set<String> HELP = Set.of("help", "?");
    private static final ThreadLocal<CommandActor> CURRENT = new ThreadLocal<>();
    private final String label;
    private final DirectorRuntimeEngine engine;
    private final Completer values;

    /** Completion values for a parameter name, such as NPC ids for {@code npc}; {@code given} holds the values already typed, by name. */
    @FunctionalInterface public interface Completer { Collection<String> values(String parameter, CommandActor actor, Map<String, String> given); }

    /**
     * @param root   the {@code @Director} root command object
     * @param values completion values for a parameter name
     */
    public AnimusDirector(Object root, Completer values) {
        this.values = Objects.requireNonNull(values);
        engine = DirectorEngineFactory.create(Objects.requireNonNull(root), DirectorEngineOptions.builder()
                .dispatcher((DirectorExecutionMode mode, Runnable task) -> task.run())
                .textResolver(TEXT)
                .build());
        label = engine.getRoot().getDescriptor().getName();
    }

    /** As {@link #AnimusDirector(Object, Completer)}, for values that do not depend on what else was typed. */
    public AnimusDirector(Object root, BiFunction<String, CommandActor, Collection<String>> values) {
        this(root, (String parameter, CommandActor actor, Map<String, String> given) -> values.apply(parameter, actor));
    }

    public DirectorRuntimeEngine engine() { return engine; }

    /** The actor of the command running on this thread. */
    public static CommandActor actor() {
        CommandActor actor = CURRENT.get();
        if (actor == null) throw new IllegalStateException("No Animus command is running on this thread");
        return actor;
    }

    /**
     * Runs the words after {@code /animus}. Help words, a group name or an unknown command show help instead. The player
     * hears one outcome sound once the command is done, decided as Iris's {@code CommandSVC.executeCommand} decides it: a
     * help page or a handler that returns dings; an unknown command, a refusal, a value that does not map or convert, or a
     * handler that throws plays the failure tone. What the handler answers does not change it.
     */
    public void execute(CommandActor actor, String[] arguments) {
        CommandSound outcome = CommandSound.FAILURE;
        try { if (run(actor, arguments)) outcome = CommandSound.SUCCESS; }
        finally { actor.play(outcome); }
    }

    /** Runs one command; true when it showed help or its handler returned. */
    private boolean run(CommandActor actor, String[] arguments) {
        List<String> tokens = pages(actor, ToolkitCommands.expand(CommandWords.tokens(arguments)));
        DirectorRuntimeNode group = engine.getRoot();
        for (String token : tokens) {
            if (group.isInvocable() || token.contains("=") || HELP.contains(token.toLowerCase(Locale.ROOT))) break;
            DirectorRuntimeNode next = named(group, token);
            if (next == null) return unknown(actor, group, token);
            group = next;
        }
        Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(engine, tokens);
        if (help.isPresent()) { help(actor, help.get().node(), help.get().pageIndex()); return true; }
        ActorSender sender = new ActorSender(actor);
        DirectorRoute route = engine.route(DirectorInvocation.pretokenized(sender, label, tokens));
        if (!route.invocable()) return unknown(actor, route.node() == null ? engine.getRoot() : route.node(), String.join(" ", tokens));
        DirectorRuntimeNode node = route.node();
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

    /** A word that names nothing in {@code group}: the reason in red, then the group's usage line. */
    private boolean unknown(CommandActor actor, DirectorRuntimeNode group, String word) {
        DirectorRuntimeNode shown = group.isInvocable() && group.getParent() != null ? group.getParent() : group;
        actor.tell(Palette.error("Unknown command \"" + word + "\" in " + shown.path()));
        actor.rich(ChatLine.of(Palette.PREFIX + "&7Usage: &f" + shown.path() + " <command>").docs(DocsLink.COMMANDS));
        return false;
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

    /** One page of help for a group, listing what the actor may run, by topic at the root. */
    public void help(CommandActor actor, DirectorRuntimeNode node, int page) {
        DirectorRuntimeNode group = node.isInvocable() && node.getParent() != null ? node.getParent() : node;
        if (!visible(actor, group)) { actor.tell(Palette.error(refused(group))); return; }
        List<DirectorRuntimeNode> entries = group.getChildren().stream().filter((DirectorRuntimeNode child) -> visible(actor, child)).toList();
        actor.help(AnimusHelp.page(group, entries, page));
    }

    /**
     * Suggestions for the word being typed, the last of {@code arguments}. Group and command names come first; inside a
     * command, bare values only for the next required parameter, then vanilla-style coordinates where it takes them, and
     * {@code key=} for the optional ones. A player hears the typing chime on every request, as in every Volmit plugin.
     */
    public List<String> complete(CommandActor actor, String[] arguments) {
        if (actor.player().isPresent()) actor.play(CommandSound.TYPING);
        List<String> words = new ArrayList<>(List.of(arguments));
        String current = words.isEmpty() ? "" : words.removeLast();
        words.removeIf(String::isEmpty);
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
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
        if (!allowed(actor, cursor)) return List.of();
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

    /** Whether help and completion list the node: the actor may run it, or for a group, something inside it. */
    public static boolean visible(CommandActor actor, DirectorRuntimeNode node) {
        if (node.getDescriptor().isHidden()) return false;
        if (allowed(actor, node)) return true;
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
