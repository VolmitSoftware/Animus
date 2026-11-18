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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * On Paper, the command tree each client receives spells out the {@code /animus} groups and commands the player may run,
 * each under its first name, so the chat box marks an unknown subcommand red while it is typed and completion offers each
 * command once. Only the client's copy changes: the server still hands every word to {@link AnimusDirector}, which
 * refuses unknown words itself and takes the other names too, and suggestions still come from the server. The hidden
 * commands chat buttons run reach a player's tree only while a button waits for them, so a click runs a command the client
 * knows and completion never offers it otherwise; {@link #refresh} sends the tree again when that changes. Spigot has no
 * such event and keeps its single free-text argument.
 */
final class CommandTreeHints {
    private static final String EVENT = "com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent";
    private static final Command<Object> RUNS = (com.mojang.brigadier.context.CommandContext<Object> context) -> 0;
    private static final SuggestionProvider<Object> ASK_SERVER =
            (com.mojang.brigadier.context.CommandContext<Object> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) -> builder.buildFuture();
    static final String HELP = "help", PAGE = "page", TOPIC = "topic", ARGUMENTS = "arguments";
    private final Buttons buttons;
    private volatile boolean installed;

    /** {@code waiting} answers whether a chat button, a dialogue choice or a quest offer, waits for a player's click. */
    CommandTreeHints(Predicate<UUID> waiting) { buttons = new Buttons(waiting); }

    /** Listens for the commands packet on Paper; does nothing elsewhere. */
    void install(JavaPlugin plugin, List<String> labels, Supplier<AnimusDirector> director, Function<Player, CommandActor> actor) {
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
                Player player = playerEvent.getPlayer();
                rewrite((CommandNode<?>) tree.invoke(event), labels, active, actor.apply(player), buttons.include(player.getUniqueId()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                log.fine("Command hints skipped for " + playerEvent.getPlayer().getName() + ": " + failure);
            }
        }, plugin);
        plugin.getServer().getPluginManager().registerEvent(PlayerQuitEvent.class, new Listener() {}, EventPriority.MONITOR,
                (Listener listener, Event event) -> { if (event instanceof PlayerQuitEvent quit) buttons.forget(quit.getPlayer().getUniqueId()); }, plugin);
        installed = true;
    }

    /**
     * On the player's own thread, after chat buttons went to them or a command of theirs ran: sends their command tree
     * again when it no longer matches whether a button waits for them. Nothing on Spigot, whose tree takes free text.
     */
    void refresh(Player player) {
        if (installed && buttons.stale(player.getUniqueId())) player.updateCommands();
    }

    /** Replaces each label's free-text node under {@code root} with the actor's own tree; {@code buttons} adds the hidden button commands. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void rewrite(CommandNode<?> root, List<String> labels, AnimusDirector director, CommandActor actor, boolean buttons) throws ReflectiveOperationException {
        for (String label : labels) {
            if (root.getChild(label) == null) continue;
            remove(root, label);
            ((CommandNode) root).addChild(tree(label, director.engine().getRoot(), director, actor, buttons));
        }
    }

    /** The literal tree of {@code node} as {@code name}, listing only what the actor may run, each child under its first name. */
    static LiteralCommandNode<Object> tree(String name, DirectorRuntimeNode node, AnimusDirector director, CommandActor actor, boolean buttons) {
        LiteralArgumentBuilder<Object> literal = LiteralArgumentBuilder.literal(name);
        if (!node.isInvocable()) {
            literal.executes(RUNS);
            LiteralArgumentBuilder<Object> help = LiteralArgumentBuilder.<Object>literal(HELP).executes(RUNS)
                    .then(RequiredArgumentBuilder.argument(PAGE, IntegerArgumentType.integer(0)).executes(RUNS));
            if (node.getParent() == null) help.then(RequiredArgumentBuilder.argument(TOPIC, StringArgumentType.greedyString()).suggests(ASK_SERVER).executes(RUNS));
            literal.then(help);
            for (DirectorRuntimeNode child : node.getChildren())
                if (runnable(director, actor, child, buttons)) literal.then(tree(child.getDescriptor().getName(), child, director, actor, buttons));
            return literal.build();
        }
        List<DirectorParameterDescriptor> parameters = node.getDescriptor().getParameters().stream().filter((DirectorParameterDescriptor parameter) -> !parameter.isContextual()).toList();
        if (parameters.stream().noneMatch(DirectorParameterDescriptor::isRequired)) literal.executes(RUNS);
        if (!parameters.isEmpty()) literal.then(RequiredArgumentBuilder.argument(ARGUMENTS, StringArgumentType.greedyString()).suggests(ASK_SERVER).executes(RUNS));
        return literal.build();
    }

    /** Listed, or hidden while a button waits, what the actor may run with the plugins running. */
    private static boolean runnable(AnimusDirector director, CommandActor actor, DirectorRuntimeNode node, boolean buttons) {
        if (director.visible(actor, node)) return true;
        if (!buttons || !AnimusDirector.allowed(actor, node) || director.needs(node).isPresent()) return false;
        if (node.getDescriptor().isHidden()) return true;
        return !node.isInvocable() && node.getChildren().stream().anyMatch((DirectorRuntimeNode child) -> runnable(director, actor, child, true));
    }

    /** Which players' trees carry the hidden button commands; a tree carries them while a button waits for its player. */
    static final class Buttons {
        private final Predicate<UUID> waiting;
        private final Set<UUID> carrying = ConcurrentHashMap.newKeySet();

        Buttons(Predicate<UUID> waiting) { this.waiting = waiting; }

        /** As a tree is sent to {@code player}: whether it carries the button commands. */
        boolean include(UUID player) {
            boolean waits = waiting.test(player);
            if (waits) carrying.add(player);
            else carrying.remove(player);
            return waits;
        }

        /** Whether the player's tree disagrees with whether a button waits for them. */
        boolean stale(UUID player) { return waiting.test(player) != carrying.contains(player); }

        void forget(UUID player) { carrying.remove(player); }
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
