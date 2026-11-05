package art.arcane.animus.bukkit;

import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.migration.CitizensGuard;
import art.arcane.animus.core.hire.HireService;
import art.arcane.animus.api.Platform;
import art.arcane.animus.bukkit.event.VirtualNpcDialogueChoiceEvent;
import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.bukkit.folia.FoliaStationaryProofService;
import art.arcane.animus.bukkit.gui.BukkitGlossBridge;
import art.arcane.animus.bukkit.gui.BukkitHoloEvents;
import art.arcane.animus.bukkit.gui.BukkitScreens;
import art.arcane.animus.bukkit.packet.NativeDisplayAdapter;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.bukkit.runtime.BukkitRuntimeHost;
import art.arcane.animus.bukkit.runtime.RegionNpcRuntime;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.bukkit.building.BukkitBuildPlatform;
import art.arcane.animus.bukkit.building.WorldGuardBuildProtection;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.behavior.builtin.DialogueChoice;
import art.arcane.animus.core.behavior.builtin.DialogueTriggers;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.command.AnimusCommands;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.editor.EditorCommands;
import art.arcane.animus.core.editor.EditorHost;
import art.arcane.animus.core.editor.EditorService;
import art.arcane.animus.core.editor.RuntimeEditorHost;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.shop.ShopService;
import java.util.Optional;
import java.util.UUID;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class AnimusPlugin extends JavaPlugin {
    private volatile AnimusRuntime runtime;
    private Executor commands;
    private ReplyScheduler replies;
    private PacketProofService proof;
    private FoliaStationaryProofService foliaProof;
    private NpcRuntime<Player, ItemStack> living;
    private RuntimeCombatService combat;
    private BuildService<Player, ItemStack> builds;
    private String buildsUnavailable;
    private ExtensionRuntime extensions;
    private volatile BehaviorCommands behaviors;
    private BuiltinCapabilities builtins;
    private volatile AnimusCommands animusCommands;
    private BukkitScreens screens;
    private volatile ShopService<Player, ItemStack> shops;
    private volatile HoloService<Player, ItemStack> guis;
    private AutoCloseable screenTicks, hireTicks;
    private final DialogueTriggers dialogueTriggers = new DialogueTriggers(() -> {
        BehaviorCommands current = behaviors;
        return current == null ? null : current.triggers();
    });
    private volatile boolean stopping;
    private volatile EditorService editor;


    @Override
    public void onEnable() {
        try {
            RuntimeEnvironment environment = RuntimeEnvironment.bukkit(
                    getServer().getBukkitVersion(), getDataFolder().toPath());
            runtime = new AnimusRuntime(environment, failure -> getLogger().log(Level.SEVERE, "Animus file cleanup failed", failure));
            runtime.start();
            java.util.function.BooleanSupplier citizens = () -> {
                org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin("Citizens");
                return plugin != null && plugin.isEnabled();
            };
            runtime.importHost(new CitizensDropIn.Host(Optional.ofNullable(getDataFolder().getAbsoluteFile().getParentFile()).map(java.io.File::toPath), citizens));
            CitizensGuard.install(citizens);
            runtime.npcs().diagnostics().stream().limit(20).forEach(problem -> getLogger().warning("NPC definition recovery: " + problem));
            replies = new ReplyScheduler(this);
            commands = runtime.fileExecutor((task, event, failure) -> {
                // Services retire their pending callbacks before this lane is closed.
                if (event == MutationLane.EngineTaskEvent.FAILED)
                    getLogger().log(Level.WARNING, "Animus file task failed", failure);
            });
            proof = new PacketProofService(this, replies, environment.minecraftVersion(), runtime.packetProofEnabled());
            if (replies.isFolia()) {
                foliaProof = new FoliaStationaryProofService(this, environment.minecraftVersion(), runtime.packetProofEnabled(), runtime.documents(), commands);
                getLogger().info(foliaProof.status());
            }
            NpcRuntimeSettings npcSettings = runtime.npcSettings();
            PersistedNpcService.Settings settings = new PersistedNpcService.Settings(npcSettings.enabled(), npcSettings.viewDistance(), npcSettings.maximumVisible(),
                    npcSettings.maximumViewers(), npcSettings.updatesPerTick());
            FoliaRegions regions = replies.isFolia() ? new FoliaRegions(this) : null;
            PersistedNpcService<Player, ItemStack> mainThread = regions == null ? BukkitRuntimeHost.start(this, runtime, environment.minecraftVersion(), commands, settings, (Player player, Runnable task) -> replies.reply(player, task)) : null;
            living = regions == null ? mainThread : new RegionNpcRuntime(this, runtime, environment.minecraftVersion(), commands, settings, regions);
            getLogger().info(living.status());
            combat = new RuntimeCombatService(this, runtime, mainThread, environment.minecraftVersion(), replies.isFolia());
            getLogger().info(combat.status());
            getLogger().info(proof.status(null));
            Platform modulePlatform = modulePlatform();
            Function<Runnable, AutoCloseable> ownerTicks = regions != null ? regions::globalTicks : tick -> getServer().getScheduler().runTaskTimer(this, tick, 1, 1)::cancel;
            Economy vault = BukkitScreens.vault(getServer());
            screens = new BukkitScreens(this, (Player player, Runnable task) -> replies.reply(player == null ? getServer().getConsoleSender() : player, task),
                    () -> ShopService.economy(runtime.shopSettings(), vault));
            shops = new ShopService<>(runtime, screens, living, commands,
                    (String name) -> Optional.ofNullable(getServer().getPlayerExact(name)).map(Player::getUniqueId), message -> getLogger().info(message));
            screens.bind(shops.screens());
            DisplayTransport<Player> displays = NativeDisplayAdapter.create(this, environment.minecraftVersion(), (Player player, Runnable task) -> replies.reply(player, task));
            guis = new HoloService<>(runtime, shops, displays, living, commands, BukkitGlossBridge.load(this), message -> getLogger().info(message));
            getServer().getPluginManager().registerEvents(new BukkitHoloEvents(guis), this);
            HireService<Player, ItemStack> hireDesk = new HireService<>(runtime, shops.screens(), living, commands, combat.hireGuarding(), message -> getLogger().info(message));
            hireTicks = ownerTicks.apply(hireDesk::tick);
            getLogger().info(hireDesk.status());
            ShopService<Player, ItemStack> screenService = shops;
            HoloService<Player, ItemStack> holoService = guis;
            living.interactions((Player player, UUID npc, boolean attack, boolean sneaking) ->
                    hireDesk.interacted(player, npc, attack, sneaking) || screenService.interacted(player, npc, attack, sneaking) || holoService.interacted(player, npc, attack, sneaking));
            NpcRuntime<Player, ItemStack> owners = living;
            screenTicks = ownerTicks.apply(() -> {
                for (UUID viewer : screenService.screens().viewing()) owners.players().deliver(viewer, screenService.screens()::tick);
                for (Player online : getServer().getOnlinePlayers()) owners.players().deliver(online.getUniqueId(), holoService::tick);
            });
            shops.reload(catalog -> {});
            guis.reload(catalog -> {});
            getLogger().info(shops.status());
            getLogger().info(guis.status());
            startBuilds(mainThread);
            builtins = new BuiltinCapabilities(new BukkitBehaviorPlatform(this, living, combat, builds, () -> buildsUnavailable, ownerTicks, () -> guis),
                    runtime.behaviorState(), this::publishChoice,
                    runtime.behaviorSettings().actions(), message -> getLogger().info(message));
            extensions = new ExtensionRuntime(runtime.documents().resolve("modules"),
                    modulePlatform, runtime.definitionGateway(), runtime.moduleSettings(), message -> getLogger().info(message), initialized -> {
                        BehaviorCommands current = behaviors;
                        if (current != null) current.modulesInitialized(initialized);
                    }, builtins);
            behaviors = new BehaviorCommands(getDataFolder().toPath(), runtime, extensions, message -> getLogger().info(message));
            TriggerDispatcher triggers = behaviors.triggers();
            if (triggers != null) {
                if (living.available()) { triggers.connect(); living.bindTriggers(triggers); }
                else {
                    triggers.unavailable(living.status());
                    getLogger().info("Behavior triggers unavailable: " + living.status());
                }
            }
            Map<String, AnimusCommands.Extra> extras = new HashMap<>();
            extras.put("proof", new ProofCommand());
            extras.put("shop", shops.shopCommand());
            extras.put("gui", guis.command());
            if (foliaProof != null) extras.put("foliaproof", new FoliaProofCommand());
            extras.put("build", new BuildCommands(() -> builds, () -> Optional.ofNullable(buildsUnavailable)));
            extras.put("editor", new EditorCommands(() -> editor, commands));
            animusCommands = new AnimusCommands(new AnimusCommands.Services(runtime, commands, living, () -> behaviors, extensions, builtins,
                    combat::execute, extras, () -> stopping));
            extensions.start();
            startEditor(modulePlatform);
            printSplash(AnimusSplash.render(environment, getServer().getVersion(), true));
            announceCitizensMigration();
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "Failed to start " + BuildIdentity.NAME, exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** After every plugin has enabled, so a Citizens plugin that loads after Animus is seen. Runs the file check off the owner. */
    private void announceCitizensMigration() {
        AnimusRuntime active = runtime;
        java.util.concurrent.Executor files = commands;
        replies.reply(getServer().getConsoleSender(), () -> {
            try {
                files.execute(() -> {
                    try {
                        if (!stopping && active.isRunning())
                            new CitizensDropIn(active.documents(), active.npcs(), active.importHost()).startupNotice().ifPresent(getLogger()::info);
                    } catch (IOException | RuntimeException failure) { getLogger().warning("Citizens migration check failed: " + failure.getMessage()); }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) { getLogger().warning("Citizens migration check skipped: file queue full"); }
        });
    }

    @Override
    public void onDisable() {
        stopping = true;
        CitizensGuard.clear();
        EditorService activeEditor = editor;
        editor = null;
        if (activeEditor != null) activeEditor.close();
        animusCommands = null;
        if (behaviors != null) behaviors.close();
        if (builtins != null) builtins.close();
        for (AutoCloseable ticks : java.util.Arrays.asList(hireTicks, screenTicks)) {
            if (ticks == null) continue;
            try { ticks.close(); }
            catch (Exception ignored) { /* The scheduler stops with the plugin. */ }
        }
        if (screens != null) screens.closeAll();
        if (guis != null) guis.close();
        if (shops != null) shops.close();
        if (extensions != null) extensions.close();
        if (builds != null) builds.close();
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
        AnimusCommands active = animusCommands;
        if (stopping || active == null) { sender.sendMessage("Animus is not running."); return true; }
        active.execute(new BukkitActor(sender, replies), arguments);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        AnimusCommands active = animusCommands;
        if (active == null) return List.of();
        return active.complete(new BukkitActor(sender, replies), arguments);
    }

    /** The web editor API: the embedded listener and the relay client, both off until config.toml enables them. */
    private void startEditor(Platform modulePlatform) {
        NpcRuntime<Player, ItemStack> owners = living;
        ShopService<Player, ItemStack> guiService = shops;
        java.util.function.Supplier<Map<String, EditorHost.Feature>> features = () -> {
            Map<String, EditorHost.Feature> available = new HashMap<>();
            available.put("combat", combat.status().toLowerCase(Locale.ROOT).contains("unavailable") ? EditorHost.Feature.no(combat.status()) : EditorHost.Feature.yes());
            available.put("builds", buildsUnavailable == null ? EditorHost.Feature.yes() : EditorHost.Feature.no(buildsUnavailable));
            available.put("gloss", getServer().getPluginManager().isPluginEnabled("Gloss") ? EditorHost.Feature.yes() : EditorHost.Feature.no("Gloss is not installed"));
            available.put("placeholderapi", getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") ? EditorHost.Feature.yes() : EditorHost.Feature.no("PlaceholderAPI is not installed"));
            return available;
        };
        EditorService service = new EditorService(runtime, new RuntimeEditorHost<>(new RuntimeEditorHost.Parts<>(runtime, commands, owners, () -> behaviors, extensions,
                () -> guiService.reload(catalog -> {}), modulePlatform.name().toLowerCase(Locale.ROOT), "", features,
                (Player player) -> player.hasPermission("animus.admin"), Optional.empty(), "viewer-private NPC presentation is not built for Bukkit yet",
                (String message) -> getLogger().info(message))), (String message) -> getLogger().info(message));
        try {
            service.start();
            editor = service;
            service.status().forEach(getLogger()::info);
        } catch (IOException | RuntimeException failure) {
            getLogger().log(Level.WARNING, "The web editor API could not start", failure);
            service.close();
        }
    }

    /** Builds run on the main thread only; Folia gives every NPC its own region owner, so they are unavailable there. */
    private void startBuilds(PersistedNpcService<Player, ItemStack> mainThread) {
        if (mainThread == null) {
            buildsUnavailable = "unavailable on Folia: a build site, its NPC team and its material container have no shared region owner, and no handoff for a build exists yet";
            getLogger().info("Builds " + buildsUnavailable);
            return;
        }
        BuildProtection protection = BuildProtection.NONE;
        String name = "none installed";
        if (getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                protection = WorldGuardBuildProtection.create(getServer());
                name = "WorldGuard region flags";
            } catch (RuntimeException | LinkageError failure) {
                String reason = "WorldGuard is installed but its API could not be used (" + failure.getClass().getSimpleName() + "); every block change is denied";
                protection = request -> { throw new IllegalStateException(reason); };
                name = reason;
            }
        }
        builds = new BuildService<>(runtime, mainThread, commands, new BukkitBuildPlatform(getServer()), protection, name,
                tick -> getServer().getScheduler().runTaskTimer(this, tick, 1, 1)::cancel,
                () -> { BehaviorCommands current = behaviors; return current == null ? null : current.triggers(); },
                (String message) -> getLogger().info(message), null);
        buildsUnavailable = builds.unavailable().orElse(null);
        getLogger().info(builds.status());
    }

    private void publishChoice(DialogueChoice choice) {
        dialogueTriggers.publish(choice);
        Player player = getServer().getPlayer(choice.player());
        if (player != null) getServer().getPluginManager().callEvent(new VirtualNpcDialogueChoiceEvent(
                player, choice.npc().uuid(), choice.behavior(), choice.prompt(), choice.option()));
    }

    private Platform modulePlatform() {
        if (replies.isFolia()) return Platform.FOLIA;
        try {
            // Public API presence also recognizes servers retaining Paper APIs under another brand.
            Class.forName("io.papermc.paper.ServerBuildInfo", false, getServer().getClass().getClassLoader());
            return Platform.PAPER;
        } catch (ClassNotFoundException absent) { return Platform.SPIGOT; }
    }

    private static CommandSender sender(CommandActor actor) { return ((BukkitActor) actor).sender(); }

    /** Preview packet tools for administrators; never reaches persisted NPCs. */
    private final class ProofCommand implements AnimusCommands.Extra {
        @Override public List<String> complete(CommandActor actor, String[] arguments) {
            if (arguments.length != 2) return List.of();
            return List.of("status", "spawn", "move", "path", "control", "mount", "clear").stream().filter((String option) -> option.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        @Override public void execute(CommandActor actor, String[] arguments) {
            CommandSender sender = sender(actor);
            try {
                String operation = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
                if (operation.equals("status")) { sender.sendMessage(proof.status(sender instanceof Player player ? player : null)); return; }
                if (!(sender instanceof Player player)) { sender.sendMessage("Proof presentation requires a player viewer."); return; }
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
                    case "spawn" -> spawn(player, arguments);
                    default -> throw new IllegalArgumentException("proof status | spawn <type> [skin-hash] | move <id> <dx> <dy> <dz> | path <id> <dx> <dy> <dz> | control <id>|stop|status | mount <vehicle-id> [rider-id...] | clear");
                }
            } catch (RuntimeException e) { sender.sendMessage("Animus proof: " + e.getMessage()); }
        }
        private void spawn(Player player, String[] arguments) {
            if (arguments.length < 3 || arguments.length > 4) throw new IllegalArgumentException("proof spawn <namespace:type> [skin-hash]");
            if (arguments.length == 3) { proof.spawn(player, arguments[2], null, null); return; }
            if (!arguments[3].matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Skin reference must be a SHA-256 hash");
            AnimusRuntime active = runtime;
            PacketProofService.SkinRequest request = proof.beginSkinLoad(player);
            String skinHash = arguments[3], type = arguments[2];
            try { commands.execute(() -> {
                try {
                    String content = active.documents().read("skins/" + skinHash + ".json");
                    if (content == null || !JsonDocuments.hash(content).equals(skinHash)) throw new IOException("Skin is missing or its content hash changed");
                    JsonObject skin = JsonDocuments.object(content);
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

    /** Folia's stationary packet preview. */
    private final class FoliaProofCommand implements AnimusCommands.Extra {
        @Override public List<String> complete(CommandActor actor, String[] arguments) {
            if (arguments.length != 2) return List.of();
            return List.of("status", "spawn", "clear").stream().filter((String option) -> option.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        @Override public void execute(CommandActor actor, String[] arguments) {
            CommandSender sender = sender(actor);
            if (foliaProof == null || !foliaProof.available()) {
                sender.sendMessage(foliaProof == null ? "Folia stationary proof requires Folia." : foliaProof.status());
                return;
            }
            String operation = arguments.length == 1 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
            if (!List.of("status", "spawn", "clear").contains(operation)
                    || arguments.length > (operation.equals("spawn") ? 3 : 2)) {
                sender.sendMessage("foliaproof status | spawn [skin-hash] | clear");
                return;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(operation.equals("status") ? foliaProof.status() : "This proof belongs to a player viewer.");
                return;
            }
            if (!foliaProof.execute(player, operation, arguments.length == 3 ? arguments[2] : null))
                sender.sendMessage("Folia proof admission queue is full or unavailable; retry shortly.");
        }
    }

    private void printSplash(String splash) {
        StringBuilder colored = new StringBuilder();
        ChatColor previous = null;
        for (int index = 0; index < splash.length(); index++) {
            char glyph = splash.charAt(index);
            ChatColor color;
            if (glyph == '█') {
                color = ChatColor.DARK_GRAY;
            } else if (glyph >= '─' && glyph <= '╿') {
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
