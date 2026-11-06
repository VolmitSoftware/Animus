package art.arcane.animus.core.building;

import art.arcane.animus.core.command.AnimusCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.navigation.BlockPos;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /animus build ...}, the same on every platform. Runs on the build service's owner (see {@link #execute}).
 * {@code donate} needs only {@code animus.build.donate}; everything else needs
 * {@code animus.admin}, which the shared command set checks first.
 */
public final class BuildCommands implements AnimusCommands.Extra {
    public static final String DONATE_PERMISSION = "animus.build.donate";
    public static final String USAGE = "build [status] | build list | build schematics | build inspect <schematic> | build start <npc-id> [schematic] [at=<x>,<y>,<z>] [option=value...]"
            + " | build <build-or-npc> status|pause|resume|cancel|undo|survey|donate | build <build-or-npc> team add|remove <npc-id>";
    private static final List<String> KEYWORDS = List.of("status", "list", "schematics", "inspect", "start");
    private static final List<String> OPERATIONS = List.of("status", "pause", "resume", "cancel", "undo", "survey", "donate", "team");
    private final Supplier<BuildService<?, ?>> service;
    private final Supplier<Optional<String>> unavailable;

    /** {@code unavailable} explains why building cannot run on this platform; then {@code service} is never called. */
    public BuildCommands(Supplier<BuildService<?, ?>> service, Supplier<Optional<String>> unavailable) {
        this.service = service;
        this.unavailable = unavailable;
    }

    /** Whether these words are a donation, which ordinary players may send. */
    public static boolean donation(String[] arguments) {
        return arguments.length == 3 && arguments[0].equalsIgnoreCase("build") && arguments[2].equalsIgnoreCase("donate");
    }

    /**
     * Donations run on the player's own owner. Every other form runs on the build service's owner: at once on a
     * single-owner server, or queued for the global region on Folia, which answers through the actor's own owner.
     */
    @Override public void execute(CommandActor actor, String[] arguments) {
        Optional<String> refused = unavailable.get();
        if (refused.isPresent()) { actor.tell("Builds unavailable: " + refused.get()); return; }
        BuildService<?, ?> builds = service.get();
        Consumer<String> reply = (String line) -> { if (!actor.reply(() -> actor.tell(line))) actor.tell(line); };
        if (donation(arguments)) {
            try {
                if (actor.player().isEmpty()) throw new IllegalArgumentException("Only a player can donate items");
                builds.donateHeld(arguments[1], actor.player().get(), actor::tell);
            } catch (RuntimeException failure) { actor.tell("Animus build: " + failure.getMessage()); }
            return;
        }
        CommandActor owner = builds.regionized() ? new Answering(actor, reply) : actor;
        if (!builds.command(() -> run(owner, builds, arguments, reply), (String why) -> reply.accept("Animus build: " + why)))
            actor.tell("Animus build: build commands are busy; try again");
    }

    /** The actor as the service's owner may use it: every answer goes through the actor's own owner. */
    private record Answering(CommandActor actor, Consumer<String> reply) implements CommandActor {
        @Override public boolean permitted(String node) { return actor.permitted(node); }
        @Override public void tell(String message) { reply.accept(message); }
        @Override public Optional<java.util.UUID> player() { return actor.player(); }
        @Override public boolean reply(Runnable action) { return actor.reply(action); }
    }

    private static void run(CommandActor actor, BuildService<?, ?> builds, String[] arguments, Consumer<String> reply) {
        try {
            String first = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
            switch (first) {
                case "status" -> {
                    if (arguments.length > 2) throw new IllegalArgumentException(USAGE);
                    actor.tell(builds.status());
                }
                case "list" -> builds.list().stream().limit(30).forEach(actor::tell);
                case "schematics" -> {
                    List<String> names = builds.schematics();
                    actor.tell(names.isEmpty() ? "No schematics. Put .schem or .nbt files in the " + BuildService.SCHEMATICS + " folder."
                            : "Schematics (" + names.size() + "): " + String.join(", ", names));
                }
                case "inspect" -> {
                    if (arguments.length != 3) throw new IllegalArgumentException("build inspect <schematic>");
                    builds.inspect(arguments[2], (List<String> lines) -> lines.forEach(reply));
                    actor.tell("Reading " + arguments[2] + "...");
                }
                case "start" -> start(actor, builds, arguments, reply);
                default -> operate(actor, builds, arguments);
            }
        } catch (BuildService.Busy busy) {
            throw busy;
        } catch (IOException | RuntimeException failure) {
            actor.tell("Animus build: " + failure.getMessage());
        }
    }

    private static void start(CommandActor actor, BuildService<?, ?> builds, String[] arguments, Consumer<String> reply) {
        if (arguments.length < 3) throw new IllegalArgumentException("build start <npc-id> [schematic] [at=<x>,<y>,<z>] [option=value...]");
        String npc = arguments[2];
        if (arguments.length == 3) {
            builds.startFromDefinition(npc, actor.player(), reply);
            actor.tell("Loading the saved build of " + npc + "...");
            return;
        }
        String schematic = arguments[3];
        Map<String, String> words = new LinkedHashMap<>();
        for (int index = 4; index < arguments.length; index++) {
            int equals = arguments[index].indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Options are written option=value, such as rotation=90; got " + arguments[index]);
            String key = arguments[index].substring(0, equals).toLowerCase(Locale.ROOT);
            if (words.put(key, arguments[index].substring(equals + 1)) != null) throw new IllegalArgumentException("Option " + key + " is given twice");
        }
        Optional<BlockPos> origin = Optional.empty();
        String at = words.remove("at");
        if (at != null) {
            String[] parts = at.split(",", -1);
            if (parts.length != 3) throw new IllegalArgumentException("at must be <x>,<y>,<z>");
            try { origin = Optional.of(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("at must be whole block coordinates"); }
        }
        BuildOptions options = BuildOptions.DEFAULT.with(words);
        builds.start(new BuildService.Start(npc, schematic, origin, options, actor.player()), reply);
        actor.tell("Loading " + schematic + " for " + npc + "...");
    }

    private static void operate(CommandActor actor, BuildService<?, ?> builds, String[] arguments) {
        if (arguments.length < 3) throw new IllegalArgumentException(USAGE);
        String reference = arguments[1], operation = arguments[2].toLowerCase(Locale.ROOT);
        if (!operation.equals("team") && arguments.length != 3) throw new IllegalArgumentException(USAGE);
        switch (operation) {
            case "status" -> actor.tell(builds.session(reference).status());
            case "pause" -> actor.tell(builds.pause(reference));
            case "resume" -> actor.tell(builds.resume(reference));
            case "cancel" -> actor.tell(builds.cancel(reference));
            case "undo" -> actor.tell(builds.undo(reference));
            case "survey" -> builds.survey(reference).stream().limit(40).forEach(actor::tell);
            case "team" -> {
                if (arguments.length != 5 || !List.of("add", "remove").contains(arguments[3].toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("build <build-or-npc> team add|remove <npc-id>");
                actor.tell(builds.team(reference, arguments[3].equalsIgnoreCase("add"), arguments[4]));
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    @Override public List<String> complete(CommandActor actor, String[] arguments) {
        if (unavailable.get().isPresent()) return List.of();
        if (arguments.length == 2) return matching(KEYWORDS, arguments[1]);
        if (arguments.length == 3 && !KEYWORDS.contains(arguments[1].toLowerCase(Locale.ROOT))) return matching(OPERATIONS, arguments[2]);
        if (arguments.length == 4 && arguments[2].equalsIgnoreCase("team")) return matching(List.of("add", "remove"), arguments[3]);
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("inspect") || arguments.length == 4 && arguments[1].equalsIgnoreCase("start")) {
            try { return matching(service.get().schematics(), arguments[arguments.length - 1]); }
            catch (IOException | RuntimeException unreadable) { return List.of(); }
        }
        if (arguments.length >= 5 && arguments[1].equalsIgnoreCase("start")) {
            List<String> options = new ArrayList<>(List.of("at="));
            for (String word : BuildOptions.words()) options.add(word + "=");
            return matching(options, arguments[arguments.length - 1]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter((String option) -> option.toLowerCase(Locale.ROOT).startsWith(lowered)).limit(100).toList();
    }
}
