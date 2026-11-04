package art.arcane.animus.bukkit;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.bukkit.runtime.PersistedNpcService;
import art.arcane.animus.bukkit.folia.FoliaStationaryProofService;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.concurrent.*;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.RuntimeEnvironment;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import org.bukkit.entity.Player;
import art.arcane.animus.core.storage.JsonDocuments;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class AnimusPlugin extends JavaPlugin {
    private volatile AnimusRuntime runtime;
    private Executor commands;
    private ReplyScheduler replies;
    private PacketProofService proof;
    private FoliaStationaryProofService foliaProof;
    private PersistedNpcService living;
    private RuntimeCombatService combat;
    private art.arcane.animus.core.extension.ExtensionRuntime extensions;
    private volatile BukkitBehaviorCommands behaviors;
    private art.arcane.animus.core.behavior.builtin.BuiltinCapabilities builtins;
    private volatile boolean stopping;


    @Override
    public void onEnable() {
        try {
            RuntimeEnvironment environment = RuntimeEnvironment.bukkit(
                    getServer().getBukkitVersion(), getDataFolder().toPath());
            runtime = new AnimusRuntime(environment, failure -> getLogger().log(Level.SEVERE, "Animus file cleanup failed", failure));
            runtime.start();
            runtime.npcs().diagnostics().stream().limit(20).forEach(problem -> getLogger().warning("NPC definition recovery: " + problem));
            replies = new ReplyScheduler(this);
            commands = runtime.fileExecutor((task, event, failure) -> {
                // Services retire their pending callbacks before this lane is closed.
                if (event == art.arcane.animus.core.extension.MutationLane.EngineTaskEvent.FAILED)
                    getLogger().log(Level.WARNING, "Animus file task failed", failure);
            });
            proof = new PacketProofService(this, replies, environment.minecraftVersion(), runtime.packetProofEnabled());
            if (replies.isFolia()) {
                foliaProof = new FoliaStationaryProofService(this, environment.minecraftVersion(), runtime.packetProofEnabled(), runtime.documents(), commands);
                getLogger().info(foliaProof.status());
            }
            var npcSettings = runtime.npcSettings();
            living = new PersistedNpcService(this, runtime, environment.minecraftVersion(), commands,
                    new PersistedNpcService.Settings(npcSettings.enabled(), npcSettings.viewDistance(), npcSettings.maximumVisible(),
                            npcSettings.maximumViewers(), npcSettings.updatesPerTick()), (player, task) -> replies.reply(player, task));
            getLogger().info(living.status());
            combat = new RuntimeCombatService(this, runtime, living, environment.minecraftVersion(), replies.isFolia());
            getLogger().info(combat.status());
            getLogger().info(proof.status(null));
            var modulePlatform = modulePlatform();
            // Flag and quest values are in memory until the durable state store backs this contract.
            builtins = new art.arcane.animus.core.behavior.builtin.BuiltinCapabilities(new BukkitBehaviorPlatform(this, living, combat, replies.isFolia()),
                    new art.arcane.animus.core.behavior.builtin.InMemoryQuestStateStore(4096), this::publishChoice,
                    runtime.behaviorSettings().actions(), message -> getLogger().info(message));
            extensions = new art.arcane.animus.core.extension.ExtensionRuntime(runtime.documents().resolve("modules"),
                    modulePlatform, runtime.definitionGateway(), runtime.moduleSettings(), message -> getLogger().info(message), initialized -> {
                        BukkitBehaviorCommands current = behaviors;
                        if (current != null) current.modulesInitialized(initialized);
                    }, builtins);
            behaviors = new BukkitBehaviorCommands(getDataFolder().toPath(), runtime, extensions, replies, message -> getLogger().info(message));
            extensions.start();
            printSplash(AnimusSplash.render(environment, getServer().getVersion(), true));
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "Failed to start " + BuildIdentity.NAME, exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (behaviors != null) behaviors.close();
        if (builtins != null) builtins.close();
        if (extensions != null) extensions.close();
        if (combat != null) combat.close();
        if (living != null) living.close();
        if (foliaProof != null) foliaProof.close();
        if (proof != null) proof.close();
        AnimusRuntime activeRuntime = runtime;
        runtime = null;
        if (activeRuntime != null) activeRuntime.closeAsync();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        AnimusRuntime activeRuntime = runtime;
        if (stopping || activeRuntime == null) { sender.sendMessage("Animus is not running."); return true; }
        // A dialogue click comes from ordinary players. Only its single-use token grants anything.
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("choice") && builtins != null) {
            BukkitChoiceCommand.execute(sender, arguments, builtins::selectChoice);
            return true;
        }
        boolean status = arguments.length == 0 || arguments[0].equalsIgnoreCase("status");
        if (!sender.hasPermission(status ? "animus.status" : "animus.admin")) {
            sender.sendMessage("You do not have permission to use this command."); return true;
        }
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("modules")) return onModuleCommand(sender, activeRuntime, arguments);
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("behaviors")) {
            if (behaviors != null) return behaviors.execute(sender, arguments);
            replies.reply(sender, () -> sender.sendMessage("Behaviors are not initialized."));
            return true;
        }
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("foliaproof")) return onFoliaProofCommand(sender, arguments);
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("proof")) return onProofCommand(sender, arguments);
        if (arguments.length > 0 && List.of("navigate", "navigation", "look").contains(arguments[0].toLowerCase(Locale.ROOT))) {
            try { onNavigationCommand(sender, arguments); }
            catch (RuntimeException e) { sender.sendMessage("Animus navigation: " + e.getMessage()); }
            return true;
        }
        if (arguments.length > 0 && List.of("attack", "target", "combat").contains(arguments[0].toLowerCase(Locale.ROOT))) {
            String[] combatArgs = arguments.clone(); combatArgs[0] = combatArgs[0].toLowerCase(Locale.ROOT);
            try { sender.sendMessage(combat.execute(combatArgs)); }
            catch (RuntimeException e) { sender.sendMessage("Animus combat: " + e.getMessage()); }
            return true;
        }
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("select")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Selection belongs to a player; use an explicit NPC id from the console."); return true; }
            try {
                if (arguments.length > 2) throw new IllegalArgumentException("select [id]");
                if (arguments.length == 2) living.select(player.getUniqueId(), arguments[1]);
                sender.sendMessage(living.selection(player.getUniqueId()).map(id -> "Selected " + id).orElse("No NPC selected. Use /animus select <id> or interact with a visible NPC."));
            } catch (RuntimeException e) { sender.sendMessage("Animus: " + e.getMessage()); }
            return true;
        }
        String[] args = arguments.clone();
        if (args.length > 0) args[0] = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1 && sender instanceof Player player && List.of("spawn", "despawn", "inspect", "delete").contains(args[0])) {
            var selected = living.selection(player.getUniqueId());
            if (selected.isEmpty()) { sender.sendMessage("No NPC selected; provide its id or use /animus select <id>."); return true; }
            args = new String[]{args[0], selected.get()};
        }
        if (args.length > 0 && (args[0].equals("spawn") || args[0].equals("edit"))) {
            submitDefinitionChange(sender, activeRuntime, args);
            return true;
        }
        String[] commandArgs = args;
        try {
            commands.execute(() -> {
                List<String> result;
                try {
                    result = new Administration(activeRuntime).execute(commandArgs);
                    if (commandArgs.length > 0 && List.of("create", "clone", "delete", "despawn", "reload", "import", "rollback").contains(commandArgs[0])) living.refresh();
                }
                catch (IOException | RuntimeException e) { result = List.of("Animus: " + e.getMessage()); }
                List<String> messages = result;
                if (!stopping) replies.reply(sender, () -> {
                    if (!stopping) {
                        messages.stream().limit(30).map(message -> message.length() > 2048 ? message.substring(0, 2048) + " … (truncated; inspect the JSON file for full details)" : message).forEach(sender::sendMessage);
                        if (messages.size() > 30) sender.sendMessage("Showing 30 of " + messages.size() + " results; inspect the files for the complete report.");
                        if (status) sender.sendMessage(living.status());
                        if (commandArgs.length == 2 && commandArgs[0].equals("inspect")) sender.sendMessage(living.inspect(commandArgs[1]));
                    }
                });
            });
        } catch (RejectedExecutionException e) { sender.sendMessage("Animus file queue is full; retry shortly."); }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        if (arguments.length == 1) {
            List<String> available = sender.hasPermission("animus.admin") ? new ArrayList<>(Administration.COMMANDS) : new ArrayList<>(List.of("status"));
            if (sender.hasPermission("animus.admin")) available.addAll(List.of("proof", "select", "attack", "target", "combat", "navigate", "navigation", "look", "modules", "behaviors"));
            if (sender.hasPermission("animus.admin") && foliaProof != null) available.add("foliaproof");
            return available.stream().filter(c -> c.startsWith(arguments[0].toLowerCase(Locale.ROOT))).toList();
        }
        AnimusRuntime activeRuntime = runtime;
        if (arguments.length == 2 && activeRuntime != null && sender.hasPermission("animus.admin")
                && List.of("inspect", "edit", "clone", "delete", "select", "spawn", "despawn", "attack", "target", "navigate", "navigation", "look").contains(arguments[0]))
            return activeRuntime.npcs().all().stream().map(d -> d.id()).filter(id -> id.startsWith(arguments[1])).limit(100).toList();
        if (arguments.length == 2 && arguments[0].equals("import") && sender.hasPermission("animus.admin"))
            return List.of("preview", "stage", "apply", "apply-plan").stream().filter(s -> s.startsWith(arguments[1])).toList();
        if (arguments.length == 4 && arguments[0].equals("import") && arguments[1].equals("stage") && sender.hasPermission("animus.admin"))
            return List.of("reject", "replace", "duplicate").stream().filter(s -> s.startsWith(arguments[3].toLowerCase(Locale.ROOT))).toList();
        if (sender.hasPermission("animus.admin")) {
            if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("behaviors") && behaviors != null)
                return behaviors.complete(arguments);
            if (arguments.length == 2 && arguments[0].equalsIgnoreCase("modules"))
                return List.of("status", "list", "capabilities", "action", "condition").stream().filter(s -> s.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
            if (arguments.length == 3 && arguments[0].equalsIgnoreCase("modules") && extensions != null)
                return extensions.capabilities().stream().filter(c -> c.descriptor().kind().name().equalsIgnoreCase(arguments[1]))
                        .map(c -> c.descriptor().key().toString()).filter(s -> s.startsWith(arguments[2])).limit(100).toList();
            if (arguments.length == 6 && arguments[0].equalsIgnoreCase("navigate"))
                return List.of("walk", "swim", "fly").stream().filter(s -> s.startsWith(arguments[5].toLowerCase(Locale.ROOT))).toList();
            if (arguments.length == 3 && arguments[0].equalsIgnoreCase("navigation"))
                return List.of("status", "stop").stream().filter(s -> s.startsWith(arguments[2].toLowerCase(Locale.ROOT))).toList();
            if (arguments.length == 2 && arguments[0].equalsIgnoreCase("foliaproof"))
                return List.of("status", "spawn", "clear").stream().filter(s -> s.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
            if (arguments.length == 2 && arguments[0].equalsIgnoreCase("proof"))
                return List.of("status", "spawn", "move", "path", "control", "mount", "clear").stream().filter(s -> s.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private boolean onModuleCommand(CommandSender sender, AnimusRuntime active, String[] args) {
        if (extensions == null) { sender.sendMessage("Modules are not initialized."); return true; }
        try {
            String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
            if (operation.equals("status")) {
                if (args.length > 2) throw new IllegalArgumentException("modules status");
                var snapshot = extensions.status();
                sender.sendMessage("Modules " + snapshot.state() + " | startup=" + extensions.startupStatuses().size()
                        + " | capabilities=" + extensions.capabilities().size() + " | failure=" + snapshot.failure());
                sender.sendMessage("Module invocations: " + snapshot.invocations());
                if (snapshot.lifecycle() != null) sender.sendMessage("Module lifecycle: " + snapshot.lifecycle());
                if (snapshot.cleanup() != null) sender.sendMessage("Module cleanup: " + snapshot.cleanup());
                sender.sendMessage("File work: " + active.fileStatus());
            } else if (operation.equals("list") || operation.equals("capabilities")) {
                if (args.length > 3) throw new IllegalArgumentException("modules " + operation + " [page]");
                int page = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (page < 1 || page > 1000) throw new IllegalArgumentException("Page must be 1..1000");
                List<String> rows;
                if (operation.equals("list")) {
                    var live = extensions.modules().stream().collect(java.util.stream.Collectors.toMap(s -> s.manifest().id(), s -> s));
                    rows = extensions.startupStatuses().stream().map(s -> {
                        var current = live.get(s.id());
                        return s.id() + " [" + s.archive() + "] | " + (current == null ? s.state() : current.state())
                                + " | " + (current == null ? s.reason() : current.reason());
                    }).toList();
                } else rows = extensions.capabilities().stream().map(c -> c.descriptor().key() + " | " + c.descriptor().kind()
                        + " | available=" + c.available() + " | " + c.reason()).toList();
                sender.sendMessage("Module " + operation + ": " + rows.size() + "; page " + page);
                rows.stream().skip((page - 1L) * 20).limit(20).forEach(sender::sendMessage);
            } else if (operation.equals("action") || operation.equals("condition")) {
                if (args.length < 5) throw new IllegalArgumentException("modules " + operation + " <namespace:name> <npc-id> <JSON object>");
                String[] parts = args[2].split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Capability must be namespace:name");
                var key = new art.arcane.animus.api.Capabilities.Key(parts[0], parts[1]);
                var npc = active.npcs().find(args[3]).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + args[3]));
                var handle = new art.arcane.animus.api.NpcHandle(npc.uuid());
                java.util.Optional<java.util.UUID> viewer = sender instanceof Player player ? java.util.Optional.of(player.getUniqueId()) : java.util.Optional.empty();
                String json = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                CompletionStage<? extends art.arcane.animus.api.Outcome<?>> result = operation.equals("action")
                        ? extensions.action(key, handle, viewer, json) : extensions.condition(key, handle, viewer, json);
                result.whenComplete((outcome, failure) -> {
                    if (failure != null) reply(sender, "Module " + key + ": FAILED; result delivery failed");
                    else reply(sender, "Module " + key + ": " + outcome.status() + outcome.value().map(value -> "; result " + value).orElseGet(() ->
                            "; " + outcome.diagnostics().stream().map(art.arcane.animus.api.Diagnostic::code).collect(java.util.stream.Collectors.joining(", "))));
                });
            } else throw new IllegalArgumentException("modules status|list|capabilities|action|condition");
        } catch (RuntimeException failure) { sender.sendMessage("Animus modules: " + failure.getMessage()); }
        return true;
    }

    private void publishChoice(art.arcane.animus.core.behavior.builtin.DialogueChoice choice) {
        Player player = getServer().getPlayer(choice.player());
        if (player != null) getServer().getPluginManager().callEvent(new art.arcane.animus.bukkit.event.VirtualNpcDialogueChoiceEvent(
                player, choice.npc().uuid(), choice.behavior(), choice.prompt(), choice.option()));
    }

    private art.arcane.animus.api.Platform modulePlatform() {
        if (replies.isFolia()) return art.arcane.animus.api.Platform.FOLIA;
        try {
            // Public API presence also recognizes servers retaining Paper APIs under another brand.
            Class.forName("io.papermc.paper.ServerBuildInfo", false, getServer().getClass().getClassLoader());
            return art.arcane.animus.api.Platform.PAPER;
        } catch (ClassNotFoundException absent) { return art.arcane.animus.api.Platform.SPIGOT; }
    }

    /** Prepare immutable drafts off-thread, validate platform capabilities on the owner, then commit with the original revision/hash. */
    private void submitDefinitionChange(CommandSender sender, AnimusRuntime active, String[] args) {
        try {
            commands.execute(() -> {
                try {
                    boolean spawn = args[0].equals("spawn");
                    if (spawn ? args.length != 2 : args.length < 3) throw new IOException(spawn ? "spawn <id>" : "edit <id> <JSON object patch>");
                    NpcRepository.Draft before = active.npcs().draft(args[1]);
                    var document = before.document();
                    if (spawn) document.addProperty("spawned", true);
                    else JsonDocuments.object(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)))
                            .entrySet().forEach(entry -> document.add(entry.getKey(), entry.getValue()));
                    NpcDefinition candidate = NpcDefinition.parse(JsonDocuments.write(document));
                    NpcRepository.Draft draft = new NpcRepository.Draft(before.path(), before.expectedHash(), before.expectedRevision(), document);
                    if (!stopping) replies.reply(sender, () -> {
                        if (stopping || !sender.hasPermission("animus.admin")) return;
                        List<String> errors = document.get("spawned").getAsBoolean() ? living.validateActivation(candidate) : List.of();
                        if (!errors.isEmpty()) { errors.stream().limit(12).forEach(error -> sender.sendMessage("Cannot activate: " + error)); return; }
                        try {
                            commands.execute(() -> {
                                try {
                                    String tx = active.npcs().apply(draft);
                                    living.refresh();
                                    reply(sender, (spawn ? "Spawn requested for " + candidate.id() : "Saved") + "; transaction " + tx);
                                } catch (IOException | RuntimeException e) { reply(sender, "Animus: " + e.getMessage()); }
                            });
                        } catch (RejectedExecutionException e) { sender.sendMessage("Animus file queue is full; no edit was applied. Retry shortly."); }
                    });
                } catch (IOException | RuntimeException e) { reply(sender, "Animus: " + e.getMessage()); }
            });
        } catch (RejectedExecutionException e) { sender.sendMessage("Animus file queue is full; retry shortly."); }
    }

    private void reply(CommandSender sender, String message) {
        if (!stopping) replies.reply(sender, () -> {
            if (!stopping) sender.sendMessage(message.length() > 2048 ? message.substring(0, 2048) + " …" : message);
        });
    }

    private void onNavigationCommand(CommandSender sender, String[] args) {
        String operation = args[0].toLowerCase(Locale.ROOT);
        if (operation.equals("navigation")) {
            if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("navigation <id> [status|stop]");
            String action = args.length == 2 ? "status" : args[2].toLowerCase(Locale.ROOT);
            if (action.equals("stop")) {
                boolean stopped = living.stopNavigation(args[1]);
                sender.sendMessage("Navigation stop requested for " + args[1] + "; stopped=" + stopped);
            } else if (!action.equals("status")) throw new IllegalArgumentException("navigation <id> [status|stop]");
            sender.sendMessage(living.navigationStatus(args[1]));
            return;
        }
        if (args.length < 5 || args.length > (operation.equals("navigate") ? 7 : 5))
            throw new IllegalArgumentException(operation.equals("navigate") ? "navigate <id> <x> <y> <z> [walk|swim|fly] [speed]" : "look <id> <x> <y> <z>");
        WorldPosition origin = living.navigationOrigin(args[1]);
        WorldPosition destination = new WorldPosition(origin.world(), new Vec3(Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4])));
        if (operation.equals("look")) {
            living.lookAt(args[1], destination); sender.sendMessage("Look target set for " + args[1]); return;
        }
        MovementProfile.Mode mode = args.length > 5 ? MovementProfile.Mode.valueOf(args[5].toUpperCase(Locale.ROOT)) : MovementProfile.Mode.WALK;
        double speed = args.length > 6 ? Double.parseDouble(args[6]) : 2.0;
        var route = living.navigate(args[1], destination, mode, speed);
        sender.sendMessage("Navigation requested for " + args[1] + "; route=" + route);
    }

    private boolean onProofCommand(CommandSender sender, String[] arguments) {
        try {
            String operation = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
            if (operation.equals("status")) { sender.sendMessage(proof.status(sender instanceof Player player ? player : null)); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Proof presentation requires a player viewer."); return true; }
            switch (operation) {
                case "clear" -> { proof.clear(player); sender.sendMessage("Proof cleared"); }
                case "control" -> {
                    if (arguments.length != 3) throw new IllegalArgumentException("proof control <entity-id>|stop|status");
                    if (arguments[2].equalsIgnoreCase("stop")) proof.stopControl(player);
                    else if (arguments[2].equalsIgnoreCase("status")) player.sendMessage(proof.controlStatus(player));
                    else proof.control(player, Integer.parseInt(arguments[2]));
                }
                case "mount" -> {
                    if (arguments.length < 3 || arguments.length > 11) throw new IllegalArgumentException("proof mount <vehicle-id> [rider-id...]");
                    List<Integer> riders = new ArrayList<>();
                    for (int i = 3; i < arguments.length; i++) riders.add(Integer.parseInt(arguments[i]));
                    proof.mount(player, Integer.parseInt(arguments[2]), riders);
                }
                case "move" -> {
                    if (arguments.length != 6) throw new IllegalArgumentException("proof move <entity-id> <dx> <dy> <dz>");
                    proof.move(player, Integer.parseInt(arguments[2]), Double.parseDouble(arguments[3]), Double.parseDouble(arguments[4]), Double.parseDouble(arguments[5]));
                }
                case "path" -> {
                    if (arguments.length != 6) throw new IllegalArgumentException("proof path <entity-id> <dx> <dy> <dz>");
                    proof.path(player, Integer.parseInt(arguments[2]), Integer.parseInt(arguments[3]), Integer.parseInt(arguments[4]), Integer.parseInt(arguments[5]));
                }
                case "spawn" -> {
                    if (arguments.length < 3 || arguments.length > 4) throw new IllegalArgumentException("proof spawn <namespace:type> [skin-hash]");
                    if (arguments.length == 3) proof.spawn(player, arguments[2], null, null);
                    else {
                        if (!arguments[3].matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Skin reference must be a SHA-256 hash");
                        AnimusRuntime active = runtime;
                        var request = proof.beginSkinLoad(player);
                        String skinHash = arguments[3], type = arguments[2];
                        try { commands.execute(() -> {
                            try {
                                String content = active.documents().read("skins/" + skinHash + ".json");
                                if (content == null || !JsonDocuments.hash(content).equals(skinHash)) throw new IOException("Skin is missing or its content hash changed");
                                var skin = JsonDocuments.object(content);
                                String texture = skin.get("textureRaw").getAsString();
                                String signature = skin.has("signature") && !skin.get("signature").isJsonNull() ? skin.get("signature").getAsString() : null;
                                if (!stopping) replies.reply(player, () -> {
                                    try { if (!stopping && proof.finishSkinLoad(player, request)) proof.spawn(player, type, texture, signature); }
                                    catch (RuntimeException e) { player.sendMessage("Animus proof: " + e.getMessage()); }
                                });
                            } catch (IOException | RuntimeException e) {
                                if (!stopping) replies.reply(player, () -> {
                                    if (proof.finishSkinLoad(player, request)) player.sendMessage("Cannot load proof skin: " + e.getMessage());
                                });
                            }
                        }); } catch (RejectedExecutionException e) { proof.finishSkinLoad(player, request); throw e; }
                    }
                }
                default -> throw new IllegalArgumentException("proof status | spawn <type> [skin-hash] | move <id> <dx> <dy> <dz> | path <id> <dx> <dy> <dz> | control <id>|stop|status | mount <vehicle-id> [rider-id...] | clear");
            }
        } catch (RuntimeException e) { sender.sendMessage("Animus proof: " + e.getMessage()); }
        return true;
    }

    private boolean onFoliaProofCommand(CommandSender sender, String[] arguments) {
        if (foliaProof == null || !foliaProof.available()) {
            sender.sendMessage(foliaProof == null ? "Folia stationary proof requires Folia." : foliaProof.status());
            return true;
        }
        String operation = arguments.length == 1 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
        if (!List.of("status", "spawn", "clear").contains(operation)
                || arguments.length > (operation.equals("spawn") ? 3 : 2)) {
            sender.sendMessage("foliaproof status | spawn [skin-hash] | clear");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(operation.equals("status") ? foliaProof.status() : "This proof belongs to a player viewer.");
            return true;
        }
        if (!foliaProof.execute(player, operation, arguments.length == 3 ? arguments[2] : null))
            sender.sendMessage("Folia proof admission queue is full or unavailable; retry shortly.");
        return true;
    }

    private void printSplash(String splash) {
        StringBuilder colored = new StringBuilder();
        ChatColor previous = null;
        for (int index = 0; index < splash.length(); index++) {
            char glyph = splash.charAt(index);
            ChatColor color;
            if (glyph == '█') {
                color = ChatColor.DARK_GRAY;
            } else if (glyph >= '\u2500' && glyph <= '\u257f') {
                color = ChatColor.GOLD;
            } else {
                color = ChatColor.GRAY;
            }
            if (color != previous) {
                colored.append(color);
                previous = color;
            }
            colored.append(glyph);
        }
        getServer().getConsoleSender().sendMessage(colored.toString()
                .replace("Startup: READY", "Startup: " + ChatColor.GREEN + "READY" + ChatColor.GRAY)
                + ChatColor.RESET);
    }
}
