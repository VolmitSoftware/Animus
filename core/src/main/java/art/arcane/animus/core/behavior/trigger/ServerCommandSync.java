package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps a platform's Animus-managed commands equal to the enabled {@code command} triggers. A name another plugin, mod or
 * the server already owns is refused with a diagnostic and stays unregistered; a taken alias is left out. Runs only on
 * the platform's global owner (the main thread, or Folia's global region).
 */
public final class ServerCommandSync {
    /** The platform's command registry, touched only from the global owner. */
    public interface Host {
        /** True when the label is registered by someone other than this sync. */
        boolean taken(String label);
        /** Registers the command under {@code labels}: its name first, then the aliases that are free. */
        void register(ServerTriggerDispatcher.Command command, List<String> labels);
        void unregister(ServerTriggerDispatcher.Command command, List<String> labels);
        /** Called once after a batch of changes, for platforms that must resend their command tree to players. */
        default void changed() {}
    }

    private final Host host;
    private final Consumer<String> log;
    private final Map<String, Registered> registered = new LinkedHashMap<>();
    private volatile List<Diagnostic> refusals = List.of();
    private long generation = -1;

    private record Registered(ServerTriggerDispatcher.Command command, List<String> labels) {}

    public ServerCommandSync(Host host, Consumer<String> log) { this.host = Objects.requireNonNull(host); this.log = Objects.requireNonNull(log); }

    /** Global owner only. Applies the command list when its generation changed since the last call. */
    public void sync(ServerTriggerDispatcher.Commands commands) {
        if (commands.generation() == generation) return;
        generation = commands.generation();
        Map<String, ServerTriggerDispatcher.Command> wanted = new LinkedHashMap<>();
        for (ServerTriggerDispatcher.Command command : commands.commands()) wanted.put(command.name(), command);
        boolean changed = false;
        for (Registered current : List.copyOf(registered.values())) {
            if (current.command().equals(wanted.get(current.command().name()))) continue;
            host.unregister(current.command(), current.labels());
            registered.remove(current.command().name());
            changed = true;
        }
        List<Diagnostic> problems = new ArrayList<>();
        for (ServerTriggerDispatcher.Command command : wanted.values()) {
            if (registered.containsKey(command.name())) continue;
            String field = "trigger=" + command.behavior() + "#" + command.trigger();
            if (host.taken(command.name())) {
                problems.add(new Diagnostic(Diagnostic.Severity.ERROR, "server-command-collision", field, "/" + command.name() + " already belongs to the server or another plugin; it is not registered"));
                log.accept("Server behavior command /" + command.name() + " from " + command.behavior() + " is not registered: the name is already taken");
                continue;
            }
            List<String> labels = new ArrayList<>();
            labels.add(command.name());
            for (String alias : command.aliases()) {
                if (host.taken(alias)) problems.add(new Diagnostic(Diagnostic.Severity.WARNING, "server-command-alias-collision", field, "Alias /" + alias + " is already taken; it is left out"));
                else labels.add(alias);
            }
            host.register(command, List.copyOf(labels));
            registered.put(command.name(), new Registered(command, List.copyOf(labels)));
            changed = true;
        }
        refusals = List.copyOf(problems);
        if (changed) host.changed();
    }

    /** Global owner only. Unregisters every command, for disable and shutdown. */
    public void clear() {
        if (registered.isEmpty()) return;
        for (Registered current : registered.values()) host.unregister(current.command(), current.labels());
        registered.clear();
        generation = -1;
        host.changed();
    }

    /** The commands that were refused or lost aliases at the last sync. Thread-safe. */
    public List<Diagnostic> refusals() { return refusals; }

    /**
     * Fires the command's trigger after the platform's permission check. {@code permitted} is whether the sender holds the
     * command's permission; a missing permission is always permitted. Returns false when the sender is refused.
     */
    public static boolean fire(ServerTriggerDispatcher dispatcher, ServerTriggerDispatcher.Command command, Optional<UUID> player, boolean permitted, String alias, String rawArguments) {
        if (command.permission().isPresent() && !permitted) return false;
        dispatcher.publish(ServerEvent.command(player, command.name(), alias, rawArguments));
        return true;
    }
}
