package art.arcane.animus.bukkit;

import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * On Paper, the command tree each client receives spells out the {@code /animus} groups and commands the player may run,
 * so the chat box marks an unknown subcommand red while it is typed. Only the client's copy changes: the server still
 * hands every word to {@link AnimusDirector}, which refuses unknown words itself, and suggestions still come from the
 * server. Spigot has no such event and keeps its single free-text argument.
 */
final class CommandTreeHints {
    private static final String EVENT = "com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent";
    private static final Command<Object> RUNS = (com.mojang.brigadier.context.CommandContext<Object> context) -> 0;
    private static final SuggestionProvider<Object> ASK_SERVER =
            (com.mojang.brigadier.context.CommandContext<Object> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) -> builder.buildFuture();
    static final String HELP = "help", PAGE = "page", ARGUMENTS = "arguments";

    private CommandTreeHints() {}

    /** Listens for the commands packet on Paper; does nothing elsewhere. */
    static void install(JavaPlugin plugin, List<String> labels, Supplier<AnimusDirector> director, Function<Player, CommandActor> actor) {
        Class<? extends Event> type;
        Method tree, firedAsync;
        try {
            type = Class.forName(EVENT).asSubclass(Event.class);
            tree = type.getMethod("getCommandNode");
            firedAsync = type.getMethod("hasFiredAsync");
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException absent) { return; }
        Logger log = plugin.getLogger();
        plugin.getServer().getPluginManager().registerEvent(type, new Listener() {}, EventPriority.NORMAL, (Listener listener, Event event) -> {
            AnimusDirector active = director.get();
            if (active == null || !type.isInstance(event) || !(event instanceof PlayerEvent playerEvent)) return;
            try {
                if (!event.isAsynchronous() && (boolean) firedAsync.invoke(event)) return;
                rewrite((CommandNode<?>) tree.invoke(event), labels, active.engine().getRoot(), actor.apply(playerEvent.getPlayer()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                log.fine("Command hints skipped for " + playerEvent.getPlayer().getName() + ": " + failure);
            }
        }, plugin);
    }

    /** Replaces each label's free-text node under {@code root} with the actor's own tree. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void rewrite(CommandNode<?> root, List<String> labels, DirectorRuntimeNode commands, CommandActor actor) throws ReflectiveOperationException {
        for (String label : labels) {
            if (root.getChild(label) == null) continue;
            remove(root, label);
            ((CommandNode) root).addChild(tree(label, commands, actor));
        }
    }

    /** The literal tree of {@code node} as {@code name}, listing only what the actor may run. */
    static LiteralCommandNode<Object> tree(String name, DirectorRuntimeNode node, CommandActor actor) {
        LiteralArgumentBuilder<Object> literal = LiteralArgumentBuilder.literal(name);
        if (!node.isInvocable()) {
            literal.executes(RUNS);
            literal.then(LiteralArgumentBuilder.literal(HELP).executes(RUNS)
                    .then(RequiredArgumentBuilder.argument(PAGE, IntegerArgumentType.integer(0)).executes(RUNS)));
            for (DirectorRuntimeNode child : node.getChildren()) {
                if (!runnable(actor, child)) continue;
                for (String alias : child.allNames()) literal.then(tree(alias, child, actor));
            }
            return literal.build();
        }
        List<DirectorParameterDescriptor> parameters = node.getDescriptor().getParameters().stream().filter((DirectorParameterDescriptor parameter) -> !parameter.isContextual()).toList();
        if (parameters.stream().noneMatch(DirectorParameterDescriptor::isRequired)) literal.executes(RUNS);
        if (!parameters.isEmpty()) literal.then(RequiredArgumentBuilder.argument(ARGUMENTS, StringArgumentType.greedyString()).suggests(ASK_SERVER).executes(RUNS));
        return literal.build();
    }

    /** Listed or hidden, what the actor may run; a hidden command still answers the chat chips that run it. */
    private static boolean runnable(CommandActor actor, DirectorRuntimeNode node) {
        return AnimusDirector.visible(actor, node) || node.getDescriptor().isHidden() && AnimusDirector.allowed(actor, node);
    }

    /** Paper's Brigadier removes a child by name; plain Brigadier needs its maps edited. */
    private static void remove(CommandNode<?> root, String name) throws ReflectiveOperationException {
        try { root.getClass().getMethod("removeCommand", String.class).invoke(root, name); return; }
        catch (NoSuchMethodException plain) { /* edited below */ }
        for (String maps : List.of("children", "literals", "arguments")) {
            Field field = CommandNode.class.getDeclaredField(maps);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(root)).remove(name);
        }
    }
}
