package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.trigger.ServerCommandSync;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;

/**
 * Server-behavior commands in the server's command map, under the fallback prefix {@code animus}. Global owner only.
 * Spigot exposes neither the map nor its known commands, so both are bound once by reflection.
 */
final class BukkitServerCommands implements ServerCommandSync.Host {
    static final String PREFIX = "animus";
    static final String NO_PERMISSION = ChatColor.RED + "You do not have permission to use this command";
    private final CommandMap map;
    private final Map<String, Command> known;
    private final ServerTriggerDispatcher dispatcher;
    private final Runnable changed;
    private final Map<String, Managed> registered = new HashMap<>();

    BukkitServerCommands(CommandMap map, Map<String, Command> known, ServerTriggerDispatcher dispatcher, Runnable changed) {
        this.map = map; this.known = known; this.dispatcher = dispatcher; this.changed = changed;
    }

    /** Paper's {@code Server#getCommandMap} and {@code getKnownCommands}, or CraftBukkit's method and Spigot's protected field. */
    static BukkitServerCommands bind(Server server, ServerTriggerDispatcher dispatcher, Runnable changed) throws ReflectiveOperationException {
        CommandMap map = (CommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
        return new BukkitServerCommands(map, knownCommands(map), dispatcher, changed);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Command> knownCommands(CommandMap map) throws ReflectiveOperationException {
        try {
            Method paper = map.getClass().getMethod("getKnownCommands");
            return (Map<String, Command>) paper.invoke(map);
        } catch (NoSuchMethodException spigot) {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            return (Map<String, Command>) field.get(map);
        }
    }

    /** Taken by the server or another plugin; this sync's own commands, from this load, never count. */
    @Override public boolean taken(String label) {
        Command owner = known.get(label.toLowerCase(Locale.ROOT));
        return owner != null && !(owner instanceof Managed managed && registered.get(managed.spec.name()) == managed);
    }

    @Override public void register(ServerTriggerDispatcher.Command command, List<String> labels) {
        Managed managed = new Managed(command, labels, dispatcher);
        map.register(PREFIX, managed);
        registered.put(command.name(), managed);
    }

    @Override public void unregister(ServerTriggerDispatcher.Command command, List<String> labels) {
        Managed managed = registered.remove(command.name());
        if (managed == null) return;
        for (String label : labels) {
            String plain = label.toLowerCase(Locale.ROOT);
            known.remove(plain, managed);
            known.remove(PREFIX + ":" + plain, managed);
        }
        managed.unregister(map);
    }

    @Override public void changed() { changed.run(); }

    /** The alias a sender typed, without the {@code animus:} prefix. */
    static String alias(String label) {
        String plain = label.toLowerCase(Locale.ROOT);
        return plain.startsWith(PREFIX + ":") ? plain.substring(PREFIX.length() + 1) : plain;
    }

    /** One Animus-managed command. Running it only publishes the command event; the behavior runs on its own owner. */
    static final class Managed extends Command {
        private final ServerTriggerDispatcher.Command spec;
        private final ServerTriggerDispatcher dispatcher;

        Managed(ServerTriggerDispatcher.Command spec, List<String> labels, ServerTriggerDispatcher dispatcher) {
            super(labels.getFirst(), spec.description().orElse("An Animus server behavior command"), spec.usage().orElse("/" + labels.getFirst()), List.copyOf(labels.subList(1, labels.size())));
            this.spec = spec;
            this.dispatcher = dispatcher;
            spec.permission().ifPresent(this::setPermission);
        }

        @Override public boolean execute(CommandSender sender, String label, String[] arguments) {
            boolean permitted = spec.permission().map(sender::hasPermission).orElse(true);
            Optional<UUID> player = sender instanceof Player online ? Optional.of(online.getUniqueId()) : Optional.empty();
            if (!ServerCommandSync.fire(dispatcher, spec, player, permitted, alias(label), String.join(" ", arguments))) sender.sendMessage(NO_PERMISSION);
            return true;
        }
    }
}
