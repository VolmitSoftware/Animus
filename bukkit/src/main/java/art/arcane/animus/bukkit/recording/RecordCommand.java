package art.arcane.animus.bukkit.recording;

import art.arcane.animus.core.command.CommandActor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;

/** {@code /animus record}: start, stop, cancel, status, ghost and preview. {@code animus.admin}; players only, except status. */
public final class RecordCommand {
    private static final List<String> OPERATIONS = List.of("start", "stop", "cancel", "status", "ghost", "preview");
    private static final String USAGE = "record start <id> | stop | cancel | status | ghost on|off | preview <id> [stop]";
    private final RecordingSessions sessions;
    private final Function<UUID, Player> players;

    /** @param players the online player of a UUID, or null */
    public RecordCommand(RecordingSessions sessions, Function<UUID, Player> players) { this.sessions = sessions; this.players = players; }

    public void execute(CommandActor actor, String[] arguments) {
        String operation = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
        Player player = actor.player().map(players).orElse(null);
        if (operation.equals("status")) { sessions.status(Optional.ofNullable(player)).forEach(actor::tell); return; }
        if (!OPERATIONS.contains(operation)) { actor.tell("Usage: /animus " + USAGE); return; }
        if (player == null) { actor.tell("Only a player can " + operation + " a recording."); return; }
        switch (operation) {
            case "start" -> { if (arguments.length == 3) sessions.start(player, actor, arguments[2]); else actor.tell("Usage: /animus record start <id>"); }
            case "stop" -> sessions.stop(player, actor);
            case "cancel" -> sessions.cancel(player, actor);
            case "ghost" -> {
                if (arguments.length == 3 && List.of("on", "off").contains(arguments[2].toLowerCase(Locale.ROOT)))
                    sessions.ghost(player, actor, arguments[2].equalsIgnoreCase("on"));
                else actor.tell("Usage: /animus record ghost on|off");
            }
            default -> {
                if (arguments.length == 4 && arguments[3].equalsIgnoreCase("stop")) sessions.stopPreview(player, actor);
                else if (arguments.length == 3) sessions.preview(player, actor, arguments[2]);
                else actor.tell("Usage: /animus record preview <id> [stop]");
            }
        }
    }

    public List<String> complete(CommandActor actor, String[] arguments) {
        if (arguments.length == 2) return matching(OPERATIONS, arguments[1]);
        String operation = arguments[1].toLowerCase(Locale.ROOT);
        if (arguments.length == 3 && operation.equals("ghost")) return matching(List.of("on", "off"), arguments[2]);
        if (arguments.length == 3 && (operation.equals("start") || operation.equals("preview"))) return matching(sessions.ids(), arguments[2]);
        if (arguments.length == 4 && operation.equals("preview")) return matching(List.of("stop"), arguments[3]);
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter((String option) -> option.startsWith(lower)).limit(100).toList();
    }
}
