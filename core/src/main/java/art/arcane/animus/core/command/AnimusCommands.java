package art.arcane.animus.core.command;

import art.arcane.animus.core.extension.loader.StartupModules;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.ModuleHost;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The one {@code /animus} command set every platform runs, so operators see the same names and arguments everywhere.
 * Call {@link #execute} and {@link #complete} on the actor's owner. Platform-only subcommands come in as extras.
 */
public final class AnimusCommands {
    /** A platform-only subcommand such as a preview tool. */
    public interface Extra {
        void execute(CommandActor actor, String[] arguments);
        List<String> complete(CommandActor actor, String[] arguments);
    }
    /** What the commands reach. {@code combat} answers the combat, attack and target subcommands. */
    public record Services(AnimusRuntime runtime, Executor files, NpcRuntime<?, ?> living, Supplier<BehaviorCommands> behaviors,
                           ExtensionRuntime extensions, BuiltinCapabilities builtins, Function<String[], String> combat,
                           Map<String, Extra> extras, BooleanSupplier stopping) {
        public Services {
            Objects.requireNonNull(runtime); Objects.requireNonNull(files); Objects.requireNonNull(living); Objects.requireNonNull(behaviors);
            Objects.requireNonNull(combat); extras = Map.copyOf(extras); Objects.requireNonNull(stopping);
        }
    }
    public static final String NOT_AVAILABLE = "That choice is no longer available.";
    private static final List<String> REFRESHING = List.of("create", "clone", "delete", "despawn", "reload", "import", "rollback");
    private final Services services;

    public AnimusCommands(Services services) { this.services = Objects.requireNonNull(services); }

    public void execute(CommandActor sender, String[] arguments) {
        AnimusRuntime activeRuntime = services.runtime();
        if (services.stopping().getAsBoolean() || !activeRuntime.isRunning()) { sender.tell("Animus is not running."); return; }
        // A dialogue click comes from ordinary players. Only its single-use token grants anything.
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("choice") && services.builtins() != null) {
            choice(sender, arguments, services.builtins()::selectChoice);
            return;
        }
        // Donating to a build is open to players with the donate node; the build itself decides what it accepts.
        AnimusCommands.Extra builds = services.extras().get("build");
        if (builds != null && BuildCommands.donation(arguments) && sender.permitted(BuildCommands.DONATE_PERMISSION)) {
            builds.execute(sender, arguments);
            return;
        }
        boolean status = arguments.length == 0 || arguments[0].equalsIgnoreCase("status");
        if (!sender.permitted(status ? "animus.status" : "animus.admin")) { sender.tell("You do not have permission to use this command."); return; }
        String operation = arguments.length == 0 ? "status" : arguments[0].toLowerCase(Locale.ROOT);
        if (operation.equals("modules")) { modules(sender, activeRuntime, arguments); return; }
        if (operation.equals("rigs")) { rigs(sender, activeRuntime, arguments); return; }
        if (operation.equals("pack")) { pack(sender, activeRuntime, arguments); return; }
        if (operation.equals("behaviors")) {
            BehaviorCommands behaviors = services.behaviors().get();
            if (behaviors != null) behaviors.execute(sender, arguments);
            else if (!sender.reply(() -> sender.tell("Behaviors are not initialized."))) sender.tell("Behaviors are not initialized.");
            return;
        }
        Extra extra = services.extras().get(operation);
        if (extra != null) { extra.execute(sender, arguments); return; }
        if (List.of("navigate", "navigation", "look").contains(operation)) {
            try { navigation(sender, arguments); }
            catch (RuntimeException e) { sender.tell("Animus navigation: " + e.getMessage()); }
            return;
        }
        if (List.of("attack", "target", "combat").contains(operation)) {
            String[] combatArgs = arguments.clone(); combatArgs[0] = operation;
            try { sender.tell(services.combat().apply(combatArgs)); }
            catch (RuntimeException e) { sender.tell("Animus combat: " + e.getMessage()); }
            return;
        }
        NpcRuntime<?, ?> living = services.living();
        if (operation.equals("ride")) {
            try {
                if (arguments.length == 3 && arguments[1].equalsIgnoreCase("stop")) sender.tell(living.stopRider(arguments[2]) ? "Dismounted " + arguments[2] : arguments[2] + " is not riding");
                else if (arguments.length == 1 || (arguments.length == 2 && arguments[1].equalsIgnoreCase("status"))) sender.tell(living.rideStatus());
                else sender.tell("ride [status] | ride stop <player>");
            } catch (RuntimeException e) { sender.tell("Animus ride: " + e.getMessage()); }
            return;
        }
        if (operation.equals("select")) {
            Optional<UUID> player = sender.player();
            if (player.isEmpty()) { sender.tell("Selection belongs to a player; use an explicit NPC id from the console."); return; }
            try {
                if (arguments.length > 2) throw new IllegalArgumentException("select [id]");
                if (arguments.length == 2) living.select(player.get(), arguments[1]);
                sender.tell(living.selection(player.get()).map((String id) -> "Selected " + id).orElse("No NPC selected. Use /animus select <id> or interact with a visible NPC."));
            } catch (RuntimeException e) { sender.tell("Animus: " + e.getMessage()); }
            return;
        }
        String[] args = arguments.clone();
        if (args.length > 0) args[0] = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1 && sender.player().isPresent() && List.of("spawn", "despawn", "inspect", "delete").contains(args[0])) {
            Optional<String> selected = living.selection(sender.player().get());
            if (selected.isEmpty()) { sender.tell("No NPC selected; provide its id or use /animus select <id>."); return; }
            args = new String[]{args[0], selected.get()};
        }
        if (args.length > 0 && (args[0].equals("spawn") || args[0].equals("edit"))) {
            submitDefinitionChange(sender, activeRuntime, args);
            return;
        }
        String[] commandArgs = args;
        try {
            services.files().execute(() -> {
                List<String> result;
                try {
                    result = new Administration(activeRuntime).execute(commandArgs);
                    if (commandArgs.length > 0 && commandArgs[0].equals("reload")) {
                        List<String> reloaded = new ArrayList<>(result);
                        reloaded.add(rigSummary(activeRuntime.rigs().reload()));
                        result = reloaded;
                    }
                    if (commandArgs.length > 0 && REFRESHING.contains(commandArgs[0])) living.refresh();
                }
                catch (IOException | RuntimeException e) { result = List.of("Animus: " + e.getMessage()); }
                List<String> messages = result;
                if (!services.stopping().getAsBoolean()) sender.reply(() -> {
                    if (services.stopping().getAsBoolean()) return;
                    messages.stream().limit(30).map((String message) -> message.length() > 2048 ? message.substring(0, 2048) + " … (truncated; inspect the JSON file for full details)" : message).forEach(sender::tell);
                    if (messages.size() > 30) sender.tell("Showing 30 of " + messages.size() + " results; inspect the files for the complete report.");
                    if (status) sender.tell(living.status());
                    if (commandArgs.length == 2 && commandArgs[0].equals("inspect")) sender.tell(living.inspect(commandArgs[1]));
                });
            });
        } catch (RejectedExecutionException e) { sender.tell("Animus file queue is full; retry shortly."); }
    }

    public List<String> complete(CommandActor sender, String[] arguments) {
        boolean admin = sender.permitted("animus.admin");
        if (arguments.length == 1) {
            List<String> available = admin ? new ArrayList<>(Administration.COMMANDS) : new ArrayList<>(List.of("status"));
            if (admin) available.addAll(List.of("select", "attack", "target", "combat", "navigate", "navigation", "look", "ride", "modules", "behaviors", "rigs", "pack"));
            if (admin) available.addAll(services.extras().keySet().stream().sorted().toList());
            return matching(available, arguments[0]);
        }
        AnimusRuntime activeRuntime = services.runtime();
        if (!admin || !activeRuntime.isRunning()) return List.of();
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 3 && operation.equals("build") && arguments[1].equalsIgnoreCase("start"))
            return activeRuntime.npcs().all().stream().map(NpcDefinition::id).filter((String id) -> id.startsWith(arguments[2])).limit(100).toList();
        if (arguments.length == 2 && List.of("inspect", "edit", "clone", "delete", "select", "spawn", "despawn", "attack", "target", "navigate", "navigation", "look").contains(operation))
            return activeRuntime.npcs().all().stream().map(NpcDefinition::id).filter((String id) -> id.startsWith(arguments[1])).limit(100).toList();
        if (arguments.length == 2 && operation.equals("import")) return matching(List.of("preview", "stage", "apply", "apply-plan"), arguments[1]);
        if (arguments.length == 4 && operation.equals("import") && arguments[1].equals("stage")) return matching(List.of("reject", "replace", "duplicate"), arguments[3]);
        if (arguments.length >= 2 && operation.equals("behaviors")) {
            BehaviorCommands behaviors = services.behaviors().get();
            return behaviors == null ? List.of() : behaviors.complete(arguments);
        }
        if (arguments.length == 2 && operation.equals("modules")) return matching(List.of("status", "list", "capabilities", "action", "condition"), arguments[1]);
        if (arguments.length == 3 && operation.equals("modules") && services.extensions() != null)
            return services.extensions().capabilities().stream().filter((Capabilities.Availability capability) -> capability.descriptor().kind().name().equalsIgnoreCase(arguments[1]))
                    .map((Capabilities.Availability capability) -> capability.descriptor().key().toString()).filter((String key) -> key.startsWith(arguments[2])).limit(100).toList();
        if (arguments.length == 6 && operation.equals("navigate")) return matching(List.of("walk", "swim", "fly"), arguments[5]);
        if (arguments.length == 2 && operation.equals("ride")) return matching(List.of("status", "stop"), arguments[1]);
        if (arguments.length == 2 && operation.equals("rigs")) return matching(List.of("status", "reload", "problems", "play", "stop"), arguments[1]);
        if (arguments.length == 3 && operation.equals("rigs") && List.of("play", "stop").contains(arguments[1].toLowerCase(Locale.ROOT)))
            return activeRuntime.npcs().all().stream().map(NpcDefinition::id).filter((String id) -> id.startsWith(arguments[2])).limit(100).toList();
        if (arguments.length == 4 && operation.equals("rigs") && arguments[1].equalsIgnoreCase("play"))
            return activeRuntime.rigs().catalog().animations().keySet().stream().filter((String id) -> id.startsWith(arguments[3])).limit(100).toList();
        if (arguments.length == 2 && operation.equals("pack")) return matching(List.of("build"), arguments[1]);
        if (arguments.length == 3 && operation.equals("navigation")) return matching(List.of("status", "stop"), arguments[2]);
        Extra extra = services.extras().get(operation);
        return extra == null ? List.of() : extra.complete(sender, arguments);
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter((String option) -> option.startsWith(lowered)).toList();
    }

    /** Every rejection reads the same, so a failed guess reveals nothing about a prompt. */
    static void choice(CommandActor sender, String[] arguments, ChoiceSelector selector) {
        boolean wellFormed = sender.player().isPresent() && arguments.length == 3
                && arguments[1].matches("[a-f0-9]{32}") && arguments[2].matches("[a-z0-9][a-z0-9_-]{0,31}");
        if (!wellFormed || selector.select(sender.player().get(), arguments[1], arguments[2]) != PendingChoices.Selection.ACCEPTED)
            sender.tell(NOT_AVAILABLE);
    }
    @FunctionalInterface interface ChoiceSelector { PendingChoices.Selection select(UUID player, String token, String option); }

    private void modules(CommandActor sender, AnimusRuntime active, String[] args) {
        ExtensionRuntime extensions = services.extensions();
        if (extensions == null) { sender.tell("Modules are not initialized."); return; }
        try {
            String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
            if (operation.equals("status")) {
                if (args.length > 2) throw new IllegalArgumentException("modules status");
                ExtensionRuntime.Status snapshot = extensions.status();
                sender.tell("Modules " + snapshot.state() + " | startup=" + extensions.startupStatuses().size()
                        + " | " + ModuleCapabilityListing.of(extensions.capabilities()).counts() + " | failure=" + snapshot.failure());
                sender.tell("Module invocations: " + snapshot.invocations());
                if (snapshot.lifecycle() != null) sender.tell("Module lifecycle: " + snapshot.lifecycle());
                if (snapshot.cleanup() != null) sender.tell("Module cleanup: " + snapshot.cleanup());
                sender.tell("File work: " + active.fileStatus());
            } else if (operation.equals("list") || operation.equals("capabilities")) {
                if (args.length > 3) throw new IllegalArgumentException("modules " + operation + " [page]");
                int page = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (page < 1 || page > 1000) throw new IllegalArgumentException("Page must be 1..1000");
                List<String> rows;
                if (operation.equals("list")) {
                    Map<String, ModuleHost.ModuleStatus> live = extensions.modules().stream()
                            .collect(Collectors.toMap((ModuleHost.ModuleStatus module) -> module.manifest().id(), (ModuleHost.ModuleStatus module) -> module));
                    rows = extensions.startupStatuses().stream().map((StartupModules.Status startup) -> {
                        ModuleHost.ModuleStatus current = live.get(startup.id());
                        return startup.id() + " [" + startup.archive() + "] | " + (current == null ? startup.state() : current.state())
                                + " | " + (current == null ? startup.reason() : current.reason());
                    }).toList();
                    sender.tell("Module list: " + rows.size() + "; page " + page);
                } else {
                    ModuleCapabilityListing listing = ModuleCapabilityListing.of(extensions.capabilities());
                    rows = listing.rows();
                    sender.tell(listing.header(page));
                }
                rows.stream().skip((page - 1L) * 20).limit(20).forEach(sender::tell);
            } else if (operation.equals("action") || operation.equals("condition")) {
                if (args.length < 5) throw new IllegalArgumentException("modules " + operation + " <namespace:name> <npc-id> <JSON object>");
                String[] parts = args[2].split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Capability must be namespace:name");
                Capabilities.Key key = new Capabilities.Key(parts[0], parts[1]);
                NpcDefinition npc = active.npcs().find(args[3]).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + args[3]));
                NpcHandle handle = new NpcHandle(npc.uuid());
                String json = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                CompletionStage<? extends Outcome<?>> result = operation.equals("action")
                        ? extensions.action(key, handle, sender.player(), json) : extensions.condition(key, handle, sender.player(), json);
                result.whenComplete((Outcome<?> outcome, Throwable failure) -> {
                    if (failure != null) reply(sender, "Module " + key + ": FAILED; result delivery failed");
                    else reply(sender, "Module " + key + ": " + outcome.status() + outcome.value().map((Object value) -> "; result " + value).orElseGet(() ->
                            "; " + outcome.diagnostics().stream().map(Diagnostic::code).collect(Collectors.joining(", "))));
                });
            } else throw new IllegalArgumentException("modules status|list|capabilities|action|condition");
        } catch (RuntimeException failure) { sender.tell("Animus modules: " + failure.getMessage()); }
    }

    private static String rigSummary(art.arcane.animus.core.rig.RigCatalog catalog) {
        return "Rigs reloaded: rigs=" + catalog.rigs().size() + " animations=" + catalog.animations().size() + " problems=" + catalog.problems().size()
                + (catalog.problems().isEmpty() ? "" : " (see /animus rigs problems)");
    }

    /** {@code rigs status|reload|problems [page]|play <npc> <animation> [layer] [speed] [loop]|stop <npc> [layer] [fadeTicks]}. */
    private void rigs(CommandActor sender, AnimusRuntime active, String[] args) {
        String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        try {
            switch (operation) {
                case "status" -> sender.tell(active.rigs().status());
                case "reload" -> services.files().execute(() -> {
                    art.arcane.animus.core.rig.RigCatalog catalog = active.rigs().reload();
                    reply(sender, rigSummary(catalog));
                    services.living().refresh();
                });
                case "problems" -> {
                    int page = args.length > 2 ? Integer.parseInt(args[2]) : 1;
                    if (page < 1 || page > 1000) throw new IllegalArgumentException("Page must be 1..1000");
                    List<String> problems = active.rigs().catalog().problems();
                    sender.tell("Rig problems: " + problems.size() + "; page " + page);
                    problems.stream().skip((page - 1L) * 20).limit(20).forEach(sender::tell);
                }
                case "play" -> {
                    if (args.length < 4 || args.length > 7) throw new IllegalArgumentException("rigs play <npc> <animation> [layer] [speed] [once|loop|pingpong|hold]");
                    int layer = args.length > 4 ? Integer.parseInt(args[4]) : 0;
                    double speed = args.length > 5 ? Double.parseDouble(args[5]) : 1;
                    Optional<String> loop = args.length > 6 ? Optional.of(args[6].toLowerCase(Locale.ROOT)) : Optional.empty();
                    onNpcOwner(sender, args[2], "Animus rigs", () -> { services.living().playAnimation(args[2], args[3], speed, loop, layer); return "Playing " + args[3] + " on " + args[2] + " layer " + layer; });
                }
                case "stop" -> {
                    if (args.length < 3 || args.length > 5) throw new IllegalArgumentException("rigs stop <npc> [layer] [fadeTicks]");
                    java.util.OptionalInt layer = args.length > 3 ? java.util.OptionalInt.of(Integer.parseInt(args[3])) : java.util.OptionalInt.empty();
                    int fade = args.length > 4 ? Integer.parseInt(args[4]) : 0;
                    onNpcOwner(sender, args[2], "Animus rigs", () -> { services.living().stopAnimation(args[2], layer, fade); return "Stopped animation on " + args[2]; });
                }
                default -> throw new IllegalArgumentException("rigs status|reload|problems [page]|play <npc> <animation> [layer] [speed] [loop]|stop <npc> [layer] [fadeTicks]");
            }
        } catch (RejectedExecutionException full) { sender.tell("Animus file queue is full; retry shortly."); }
        catch (RuntimeException failure) { sender.tell("Animus rigs: " + failure.getMessage()); }
    }

    /** {@code pack build}: models/ Blockbench files into pack/out/. */
    private void pack(CommandActor sender, AnimusRuntime active, String[] args) {
        if (args.length != 2 || !args[1].equalsIgnoreCase("build")) { sender.tell("pack build"); return; }
        try {
            services.files().execute(() -> {
                java.nio.file.Path data = active.rigs().data();
                try {
                    art.arcane.animus.core.rig.pack.RigPackBuilder.Result result = art.arcane.animus.core.rig.pack.RigPackBuilder.build(
                            data.resolve("models"), data.resolve("pack/out"), art.arcane.animus.core.rig.pack.RigPackBuilder.Limits.DEFAULT);
                    reply(sender, "Pack built: models=" + result.models() + " bones=" + result.bones() + " sha1=" + result.sha1() + " -> pack/out/" + result.zip().getFileName());
                    reply(sender, "Rig and animation documents for the models are in pack/out/documents; copy them into rigs/ and animations/ to use them.");
                    result.problems().stream().limit(20).forEach((String problem) -> reply(sender, "Pack: " + problem));
                    if (result.problems().size() > 20) reply(sender, "Pack: " + (result.problems().size() - 20) + " more problems not shown");
                } catch (IOException | RuntimeException failure) { reply(sender, "Animus pack: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { sender.tell("Animus file queue is full; retry shortly."); }
    }

    /** Prepare immutable drafts off-thread, validate platform capabilities on the owner, then commit with the original revision/hash. */
    private void submitDefinitionChange(CommandActor sender, AnimusRuntime active, String[] args) {
        Executor files = services.files();
        try {
            files.execute(() -> {
                try {
                    boolean spawn = args[0].equals("spawn");
                    if (spawn ? args.length != 2 : args.length < 3) throw new IOException(spawn ? "spawn <id>" : "edit <id> <JSON object patch>");
                    NpcRepository.Draft before = active.npcs().draft(args[1]);
                    JsonObject document = before.document();
                    if (spawn) document.addProperty("spawned", true);
                    else JsonDocuments.object(String.join(" ", Arrays.copyOfRange(args, 2, args.length)))
                            .entrySet().forEach((Map.Entry<String, com.google.gson.JsonElement> entry) -> document.add(entry.getKey(), entry.getValue()));
                    NpcDefinition candidate = NpcDefinition.parse(JsonDocuments.write(document));
                    NpcRepository.Draft draft = new NpcRepository.Draft(before.path(), before.expectedHash(), before.expectedRevision(), document);
                    if (!services.stopping().getAsBoolean()) sender.reply(() -> {
                        if (services.stopping().getAsBoolean() || !sender.permitted("animus.admin")) return;
                        List<String> errors = document.get("spawned").getAsBoolean() ? services.living().validateActivation(candidate) : List.of();
                        if (!errors.isEmpty()) { errors.stream().limit(12).forEach((String error) -> sender.tell("Cannot activate: " + error)); return; }
                        try {
                            files.execute(() -> {
                                try {
                                    String tx = active.npcs().apply(draft);
                                    services.living().refresh();
                                    reply(sender, (spawn ? "Spawn requested for " + candidate.id() : "Saved") + "; transaction " + tx);
                                } catch (IOException | RuntimeException e) { reply(sender, "Animus: " + e.getMessage()); }
                            });
                        } catch (RejectedExecutionException e) { sender.tell("Animus file queue is full; no edit was applied. Retry shortly."); }
                    });
                } catch (IOException | RuntimeException e) { reply(sender, "Animus: " + e.getMessage()); }
            });
        } catch (RejectedExecutionException e) { sender.tell("Animus file queue is full; retry shortly."); }
    }

    private void reply(CommandActor sender, String message) {
        if (!services.stopping().getAsBoolean()) sender.reply(() -> {
            if (!services.stopping().getAsBoolean()) sender.tell(message.length() > 2048 ? message.substring(0, 2048) + " …" : message);
        });
    }

    private void navigation(CommandActor sender, String[] args) {
        NpcRuntime<?, ?> living = services.living();
        String operation = args[0].toLowerCase(Locale.ROOT);
        if (operation.equals("navigation")) {
            if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("navigation <id> [status|stop]");
            String action = args.length == 2 ? "status" : args[2].toLowerCase(Locale.ROOT);
            if (action.equals("status")) { sender.tell(living.navigationStatus(args[1])); return; }
            if (!action.equals("stop")) throw new IllegalArgumentException("navigation <id> [status|stop]");
            onNpcOwner(sender, args[1], () -> "Navigation stop requested for " + args[1] + "; stopped=" + living.stopNavigation(args[1]) + "\n" + living.navigationStatus(args[1]));
            return;
        }
        if (args.length < 5 || args.length > (operation.equals("navigate") ? 7 : 5))
            throw new IllegalArgumentException(operation.equals("navigate") ? "navigate <id> <x> <y> <z> [walk|swim|fly] [speed]" : "look <id> <x> <y> <z>");
        Vec3 point = new Vec3(Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
        MovementProfile.Mode mode = args.length > 5 ? MovementProfile.Mode.valueOf(args[5].toUpperCase(Locale.ROOT)) : MovementProfile.Mode.WALK;
        double speed = args.length > 6 ? Double.parseDouble(args[6]) : 2.0;
        onNpcOwner(sender, args[1], () -> {
            WorldPosition destination = new WorldPosition(living.navigationOrigin(args[1]).world(), point);
            if (operation.equals("look")) { living.lookAt(args[1], destination); return "Look target set for " + args[1]; }
            return "Navigation requested for " + args[1] + "; route=" + living.navigate(args[1], destination, mode, speed);
        });
    }

    /** Runs NPC work on the NPC's owner, which on a regionized server is its region, and replies on the sender's own owner. */
    private void onNpcOwner(CommandActor sender, String id, Supplier<String> work) { onNpcOwner(sender, id, "Animus navigation", work); }
    private void onNpcOwner(CommandActor sender, String id, String label, Supplier<String> work) {
        NpcRuntime<?, ?> living = services.living();
        boolean accepted = living.onOwner(id, () -> {
            String message;
            try { message = work.get(); }
            catch (RuntimeException e) { message = label + ": " + e.getMessage(); }
            for (String line : message.split("\n")) reply(sender, line);
        }, (String reason) -> reply(sender, label + ": " + reason));
        if (!accepted) sender.tell(label + ": " + (living.available() ? "NPC " + id + " is not active; it starts when its chunk loads or a player is near" : living.status()));
    }
}
