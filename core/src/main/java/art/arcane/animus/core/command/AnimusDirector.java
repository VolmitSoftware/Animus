package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Runs {@code /animus} through VolmLib's Director, the same on every platform. Bare words bind only to required
 * parameters; optional ones are {@code key=value}. Unknown keys and leftover words fail with the command's usage.
 * Commands carry their node in {@link AnimusPermission}; help and completion show only what the actor may run.
 * Call {@link #execute} and {@link #complete} on the actor's owner; commands read the actor with {@link #actor()}.
 */
public final class AnimusDirector {
    /** Every node without its own {@link AnimusPermission} needs this one. */
    public static final String ADMIN = "animus.admin";
    /** Help in Animus's pinks on grey. */
    public static final DirectorMiniMenu.Theme THEME = new DirectorMiniMenu.Theme("#FF5FA8", "#E0408C", "#E0408C", "#555555", "#AAAAAA", "#FF5FA8", "#FFB3D6", "#777777");
    private static final TextKey UNEXPECTED = TextKey.of(DirectorRuntimeMessages.UNEXPECTED_ARGUMENT.id(),
            "Unexpected \"{argument}\"; optional values are key=value, such as page=2");
    /** Director's English, with Animus's own example for an unkeyed optional value. */
    public static final DirectorTextResolver TEXT = (TextKey key, MessageArgs arguments) ->
            DirectorTextResolver.ENGLISH.resolve(key.id().equals(UNEXPECTED.id()) ? UNEXPECTED : key, arguments);
    private static final int SUGGESTIONS = 100;
    private static final ThreadLocal<CommandActor> CURRENT = new ThreadLocal<>();
    private final String label;
    private final DirectorRuntimeEngine engine;
    private final BiFunction<String, CommandActor, Collection<String>> values;

    /**
     * @param root   the {@code @Director} root command object
     * @param values completion values for a parameter name, such as NPC ids for {@code npc}
     */
    public AnimusDirector(Object root, BiFunction<String, CommandActor, Collection<String>> values) {
        this.values = Objects.requireNonNull(values);
        engine = DirectorEngineFactory.create(Objects.requireNonNull(root), DirectorEngineOptions.builder()
                .dispatcher((DirectorExecutionMode mode, Runnable task) -> task.run())
                .textResolver(TEXT)
                .build());
        label = engine.getRoot().getDescriptor().getName();
    }

    public DirectorRuntimeEngine engine() { return engine; }

    /** The actor of the command running on this thread. */
    public static CommandActor actor() {
        CommandActor actor = CURRENT.get();
        if (actor == null) throw new IllegalStateException("No Animus command is running on this thread");
        return actor;
    }

    /** Runs the words after {@code /animus}. Help words, a group name or an unknown command show help instead. */
    public void execute(CommandActor actor, String[] arguments) {
        List<String> tokens = ToolkitCommands.expand(CommandWords.tokens(arguments));
        Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(engine, tokens);
        if (help.isPresent()) { help(actor, help.get().node(), help.get().pageIndex()); return; }
        ActorSender sender = new ActorSender(actor);
        DirectorInvocation invocation = DirectorInvocation.pretokenized(sender, label, tokens);
        DirectorRoute route = engine.route(invocation);
        if (!route.invocable()) {
            DirectorRuntimeNode reached = route.node() == null ? engine.getRoot() : route.node();
            actor.tell(Palette.error("Unknown command /" + label + " " + String.join(" ", tokens)));
            help(actor, reached, 0);
            return;
        }
        DirectorRuntimeNode node = route.node();
        for (DirectorRuntimeNode gate = node; gate != null; gate = gate.getParent())
            if (!gate.getDescriptor().getOrigin().validFor(actor.player().isPresent())) {
                actor.tell(Palette.error(actor.player().isPresent() ? "Console only" : "Players only"));
                return;
            }
        if (!allowed(actor, node)) { actor.tell(Palette.error("No permission: " + permission(node))); return; }
        boolean hidden = hidden(node);
        sender.quiet = hidden;
        CommandActor previous = CURRENT.get();
        CURRENT.set(actor);
        DirectorExecutionResult result;
        try { result = engine.execute(invocation); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        if (result.isSuccess()) return;
        // A hidden command answers every refusal the same way, so a forged click learns nothing.
        if (hidden) actor.tell(Palette.error(ChoiceCommands.NOT_AVAILABLE));
        else actor.rich(ChatLine.of(Palette.PREFIX + "&7Usage: &f" + usage(node)).docs(DocsLink.ARGUMENTS));
    }

    /** {@code /animus path <required> [optional=default]}. */
    public static String usage(DirectorRuntimeNode node) {
        StringBuilder usage = new StringBuilder(node.path());
        for (DirectorParameterDescriptor parameter : node.getDescriptor().getParameters()) {
            if (parameter.isContextual()) continue;
            if (parameter.isRequired()) usage.append(" <").append(parameter.getName()).append('>');
            else usage.append(" [").append(parameter.getName()).append('=').append(parameter.getDefaultValue().isEmpty() ? "…" : parameter.getDefaultValue()).append(']');
        }
        return usage.toString();
    }

    /** One page of help for a group, listing what the actor may run. */
    public void help(CommandActor actor, DirectorRuntimeNode node, int page) {
        DirectorRuntimeNode group = node.isInvocable() && node.getParent() != null ? node.getParent() : node;
        if (!visible(actor, group)) { actor.tell(Palette.error("No permission: " + permission(group))); return; }
        List<DirectorRuntimeNode> entries = group.getChildren().stream().filter((DirectorRuntimeNode child) -> visible(actor, child))
                .sorted(Comparator.comparing((DirectorRuntimeNode child) -> child.getDescriptor().getName(), String.CASE_INSENSITIVE_ORDER)).toList();
        actor.menu(new DirectorMiniMenu.DirectorHelpPage(group, entries, page, DirectorMiniMenu.MAX_ENTRIES_PER_PAGE));
    }

    /**
     * Suggestions for the word being typed, the last of {@code arguments}. Group and command names come first; inside a
     * command, bare values only for the next required parameter, and {@code key=} for the optional ones.
     */
    public List<String> complete(CommandActor actor, String[] arguments) {
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
        Set<DirectorRuntimeParameter> keyed = new LinkedHashSet<>();
        int positional = 0;
        for (String word : before) {
            int equals = word.indexOf('=');
            DirectorRuntimeParameter parameter = equals > 0 ? parameter(node, word.substring(0, equals)) : null;
            if (parameter != null) keyed.add(parameter);
            else positional++;
        }
        List<DirectorRuntimeParameter> required = node.getParameters().stream().filter((DirectorRuntimeParameter parameter) -> parameter.getDescriptor().isRequired()
                && !parameter.getDescriptor().isContextual() && !keyed.contains(parameter)).toList();
        int equals = current.indexOf('=');
        if (equals > 0) {
            DirectorRuntimeParameter parameter = parameter(node, current.substring(0, equals));
            if (parameter == null || parameter.getDescriptor().isContextual()) return List.of();
            String key = current.substring(0, equals + 1), prefix = current.substring(equals + 1);
            return values(actor, parameter).stream().filter((String value) -> starts(value, prefix)).limit(SUGGESTIONS).map((String value) -> key + value).toList();
        }
        List<String> suggestions = new ArrayList<>();
        if (positional < required.size())
            values(actor, required.get(positional)).stream().filter((String value) -> starts(value, current)).limit(SUGGESTIONS).forEach(suggestions::add);
        for (DirectorRuntimeParameter parameter : node.getParameters()) {
            DirectorParameterDescriptor descriptor = parameter.getDescriptor();
            if (!descriptor.isRequired() && !descriptor.isContextual() && !keyed.contains(parameter) && starts(descriptor.getName() + "=", current))
                suggestions.add(descriptor.getName() + "=");
        }
        return suggestions;
    }

    private List<String> values(CommandActor actor, DirectorRuntimeParameter parameter) {
        Class<?> type = parameter.getDescriptor().getType();
        if (type.isEnum()) return java.util.Arrays.stream(type.getEnumConstants()).map((Object constant) -> ((Enum<?>) constant).name().toLowerCase(Locale.ROOT)).toList();
        if (type == boolean.class || type == Boolean.class) return List.of("true", "false");
        try {
            Collection<String> known = values.apply(parameter.getDescriptor().getName(), actor);
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
