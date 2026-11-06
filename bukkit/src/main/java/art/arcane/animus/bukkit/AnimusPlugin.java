package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.metrics.ReactBridge;
import art.arcane.animus.core.command.DebugCommands;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.DebugWatch;
import art.arcane.animus.core.metrics.MetricsSnapshot;
import art.arcane.animus.core.metrics.ReactMetricCatalog;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.RichText;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.ServicePriority;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.migration.CitizensGuard;
import art.arcane.animus.core.hire.HireService;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.rig.AnimationEventRunner;
import art.arcane.animus.bukkit.event.VirtualNpcDialogueChoiceEvent;
import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.bukkit.folia.FoliaStationaryProofService;
import art.arcane.animus.bukkit.gui.BukkitGlossBridge;
import art.arcane.animus.bukkit.gui.BukkitHoloEvents;
import art.arcane.animus.bukkit.gui.BukkitScreens;
import art.arcane.animus.bukkit.packet.NativeDisplayAdapter;
import art.arcane.animus.bukkit.recording.BukkitRecordings;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.bukkit.runtime.BukkitRuntimeHost;
import art.arcane.animus.bukkit.runtime.RegionBuildNpcs;
import art.arcane.animus.bukkit.runtime.RegionNpcRuntime;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.bukkit.building.BukkitBuildPlatform;
import art.arcane.animus.bukkit.protection.RegionBuildProtection;
import art.arcane.animus.bukkit.protection.RegionFlags;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.behavior.builtin.DialogueChoice;
import art.arcane.animus.core.behavior.builtin.DialogueTriggers;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerSink;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.command.AnimusCommandTree;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandServices;
import art.arcane.animus.core.command.ProofControl;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.editor.EditorCommands;
import art.arcane.animus.core.editor.EditorHost;
import art.arcane.animus.core.editor.EditorService;
import art.arcane.animus.core.editor.RuntimeEditorHost;
import art.arcane.animus.core.RuntimeCatalogs;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.hotload.HotloadService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
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
    private BukkitRecordings recordings;
    private FoliaStationaryProofService foliaProof;
    private NpcRuntime<Player, ItemStack> living;
    private RuntimeCombatService combat;
    private BuildService<Player, ItemStack> builds;
    private ExtensionRuntime extensions;
    private volatile BehaviorCommands behaviors;
    private BuiltinCapabilities builtins;
    private volatile AnimusDirector director;
    private BukkitScreens screens;
    private volatile ShopService<Player, ItemStack> shops;
    private volatile HoloService<Player, ItemStack> guis;
    private AutoCloseable screenTicks, hireTicks, triggerTicks;
    private final DialogueTriggers dialogueTriggers = new DialogueTriggers(() -> {
        BehaviorCommands current = behaviors;
        return current == null ? null : current.triggers();
    });
    private volatile boolean stopping;
    private volatile EditorService editor;
    private volatile AnimusMetrics metrics;
    private final DebugWatch debugWatch = new DebugWatch();
    private AutoCloseable metricsTicks;
    private volatile ReactBridge react;
    private HotloadService hotload;
    private BukkitServerBehaviors serverBehaviors;


    @Override
    public void onEnable() {
        try {
            AnimusMetrics measured = AnimusMetrics.restart();
            metrics = measured;
            RuntimeEnvironment environment = RuntimeEnvironment.bukkit(
                    getServer().getBukkitVersion(), getDataFolder().toPath());
            runtime = new AnimusRuntime(environment, failure -> getLogger().log(Level.SEVERE, "Animus file cleanup failed", failure));
            runtime.start();
            java.util.function.BooleanSupplier citizens = () -> {
                org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin("Citizens");
                return plugin != null && plugin.isEnabled();
            };
            java.util.function.BooleanSupplier shopkeepers = () -> {
                org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin("Shopkeepers");
                return plugin != null && plugin.isEnabled();
            };
            java.util.function.BooleanSupplier denizen = () -> {
                org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin("Denizen");
                return plugin != null && plugin.isEnabled();
            };
            runtime.importHost(new CitizensDropIn.Host(Optional.ofNullable(getDataFolder().getAbsoluteFile().getParentFile()).map(java.io.File::toPath), citizens, shopkeepers, denizen));
            CitizensGuard.install(citizens);
            runtime.npcs().diagnostics().stream().limit(20).forEach(problem -> getLogger().warning("NPC definition recovery: " + problem));
            replies = new ReplyScheduler(this);
            commands = runtime.fileExecutor((task, event, failure) -> {
                // Services retire their pending callbacks before this lane is closed.
                if (event == MutationLane.EngineTaskEvent.FAILED)
                    getLogger().log(Level.WARNING, "Animus file task failed", failure);
            });
            proof = new PacketProofService(this, replies, environment.minecraftVersion(), runtime);
            if (replies.isFolia()) {
                foliaProof = new FoliaStationaryProofService(this, environment.minecraftVersion(), runtime, runtime.documents(), commands);
                getLogger().info(foliaProof.status());
            }
            NpcRuntimeSettings npcSettings = runtime.npcSettings();
            PersistedNpcService.Settings settings = PersistedNpcService.Settings.from(npcSettings);
            FoliaRegions regions = replies.isFolia() ? new FoliaRegions(this) : null;
            PersistedNpcService<Player, ItemStack> mainThread = regions == null ? BukkitRuntimeHost.start(this, runtime, environment.minecraftVersion(), commands, settings, (Player player, Runnable task) -> replies.reply(player, task)) : null;
            living = regions == null ? mainThread : new RegionNpcRuntime(this, runtime, environment.minecraftVersion(), commands, settings, regions);
            getLogger().info(living.status());
            combat = new RuntimeCombatService(this, runtime, mainThread, environment.minecraftVersion(), replies.isFolia(), living instanceof RegionNpcRuntime regionized ? regionized : null);
            getLogger().info(combat.status());
            getLogger().info(proof.status(null));
            Platform modulePlatform = modulePlatform();
            Function<Runnable, AutoCloseable> ownerTicks = regions != null ? regions::globalTicks : tick -> getServer().getScheduler().runTaskTimer(this, tick, 1, 1)::cancel;
            Economy vault = runtime.moduleHooks().economy(BukkitScreens.vault(getServer()));
            screens = new BukkitScreens(this, (Player player, Runnable task) -> replies.reply(player == null ? getServer().getConsoleSender() : player, task),
                    () -> ShopService.economy(runtime.shopSettings(), vault));
            shops = new ShopService<>(runtime, screens, living, commands,
                    (String name) -> Optional.ofNullable(getServer().getPlayerExact(name)).map(Player::getUniqueId), message -> getLogger().info(message));
            screens.bind(shops.screens());
            DisplayTransport<Player> displays = NativeDisplayAdapter.create(this, environment.minecraftVersion(), (Player player, Runnable task) -> replies.reply(player, task));
            guis = new HoloService<>(runtime, shops, displays, living, commands, BukkitGlossBridge.load(this), message -> getLogger().info(message));
            getServer().getPluginManager().registerEvents(new BukkitHoloEvents(guis), this);
            HireService<Player, ItemStack> hireDesk = new HireService<>(runtime, shops.screens(), living, commands, combat.hireGuarding(), message -> getLogger().info(message));
            hireTicks = ownerTicks.apply(measured.timed(Subsystem.BEHAVIORS, hireDesk::tick));
            getLogger().info(hireDesk.status());
            ShopService<Player, ItemStack> screenService = shops;
            HoloService<Player, ItemStack> holoService = guis;
            living.interactions((Player player, UUID npc, boolean attack, boolean sneaking) ->
                    hireDesk.interacted(player, npc, attack, sneaking) || screenService.interacted(player, npc, attack, sneaking) || holoService.interacted(player, npc, attack, sneaking));
            NpcRuntime<Player, ItemStack> owners = living;
            screenTicks = ownerTicks.apply(measured.timed(Subsystem.GUI, () -> {
                for (UUID viewer : screenService.screens().viewing()) owners.players().deliver(viewer, screenService.screens()::tick);
                for (Player online : getServer().getOnlinePlayers()) owners.players().deliver(online.getUniqueId(), holoService::tick);
            }));
            shops.reload(catalog -> {});
            guis.reload(catalog -> {});
            getLogger().info(shops.status());
            getLogger().info(guis.status());
            startBuilds(mainThread, living instanceof RegionNpcRuntime regionized ? regionized : null, regions);
            BukkitOwners scheduling = new BukkitOwners(this, regions);
            builtins = new BuiltinCapabilities(new BukkitBehaviorPlatform(this, living, combat, builds, this::buildsRefusal,
                    (Runnable tick) -> ownerTicks.apply(measured.timed(Subsystem.BEHAVIORS, tick)), () -> guis, scheduling, mainThread),
                    runtime.behaviorState(), this::publishChoice,
                    runtime.behaviorSettings().actions(), message -> getLogger().info(message));
            extensions = new ExtensionRuntime(runtime.documents().resolve("modules"),
                    modulePlatform, runtime.definitionGateway(), runtime.moduleSettings(), message -> getLogger().info(message), initialized -> {
                        BehaviorCommands current = behaviors;
                        if (current != null) current.modulesInitialized(initialized);
                    }, builtins);
            extensions.connectHooks(runtime.moduleHooks());
            extensions.follow(runtime);
            ExtensionRuntime modules = extensions;
            living.observeInteractions(modules::interacted);
            living.animationEvents(new AnimationEventRunner((Capabilities.Key key, NpcHandle npc, String arguments) -> modules.action(key, npc, Optional.empty(), arguments),
                    (String message) -> getLogger().info(message)));
            proof.observeInteractions(modules::interacted);
            if (foliaProof != null) foliaProof.observeInteractions(modules::interacted);
            behaviors = new BehaviorCommands(getDataFolder().toPath(), runtime, extensions, message -> getLogger().info(message));
            runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, owners::refresh, () -> behaviors,
                    () -> screenService.reload((art.arcane.animus.core.shop.ShopCatalog catalog) -> {}),
                    () -> holoService.reload((art.arcane.animus.core.gui.HoloCatalog catalog) -> {}), (String message) -> getLogger().info(message))));
            BuiltinCapabilities actions = builtins;
            runtime.onSettings((PluginSettings next) -> actions.settings(next.behaviors().actions()));
            hotload = new HotloadService(new HotloadService.Parts(runtime, commands, (Runnable task) -> {
                        if (!replies.reply(getServer().getConsoleSender(), task)) throw new RejectedExecutionException("the server refused console work");
                    }, living, new BukkitOperators(this, replies), screenService::refreshOpen,
                    (String message) -> getLogger().info(message)));
            hotload.start();
            TriggerDispatcher triggers = behaviors.triggers();
            ServerTriggerDispatcher servers = behaviors.servers();
            if (triggers != null) triggerTicks = ownerTicks.apply(new art.arcane.animus.core.behavior.trigger.TriggerLink(triggers, servers, living, (String message) -> getLogger().info(message)));
            Function<UUID, Optional<VirtualNpcState.Snapshot>> states = owners::state;
            AnimusRuntime definitions = runtime;
            SnapshotFacts facts = new SnapshotFacts(SnapshotFacts.npcs(() -> definitions.npcs().snapshot(), states));
            serverBehaviors = new BukkitServerBehaviors(this, scheduling, servers, facts, (String message) -> getLogger().info(message));
            behaviors.facts(facts);
            serverBehaviors.start();
            recordings = new BukkitRecordings(this, runtime, commands, regions, environment.minecraftVersion());
            AnimusRuntime measuredRuntime = runtime;
            DebugCommands debugCommands = new DebugCommands(() -> metrics, debugWatch,
                    (String id) -> measuredRuntime.isRunning() ? measuredRuntime.npcs().find(id).map(NpcDefinition::uuid) : Optional.empty(),
                    () -> { EditorService active = editor; return active == null ? List.of("Editor: off") : active.status(); });
            CommandServices commandServices = new CommandServices(runtime, commands, living, () -> behaviors, extensions, builtins, combat.control(), shops, guis,
                    new BuildCommands(() -> builds, () -> Optional.ofNullable(buildsRefusal())), new EditorCommands(() -> editor, commands), debugCommands,
                    new BukkitProof(), recordings.sessions(), () -> getServer().getOnlinePlayers().stream().map(Player::getName).toList(), () -> stopping);
            measured.extra("combat.waiting", combat::waiting);
            measured.extra("combat.load", combat::load);
            measured.extra("editor.streams", () -> { EditorService active = editor; return active == null ? 0 : active.eventStreams(); });
            measured.listen((MetricsSnapshot snapshot) -> debugWatch.publish(snapshot, this::actionBar));
            measured.listen((MetricsSnapshot snapshot) -> { ReactBridge bridge = react; if (bridge != null) bridge.publish(snapshot); });
            metricsTicks = ownerTicks.apply(measured::serverTick);
            getServer().getPluginManager().registerEvents(new ReactWatcher(), this);
            bindReact();
            director = new AnimusCommandTree(commandServices).director();
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
        if (hotload != null) { hotload.close(); hotload = null; }
        CitizensGuard.clear();
        EditorService activeEditor = editor;
        editor = null;
        if (activeEditor != null) activeEditor.close();
        director = null;
        if (recordings != null) { recordings.close(); recordings = null; }
        ReactBridge bridge = react;
        react = null;
        if (bridge != null) bridge.close();
        debugWatch.clear();
        if (serverBehaviors != null) { serverBehaviors.close(); serverBehaviors = null; }
        if (behaviors != null) behaviors.close();
        if (builtins != null) builtins.close();
        for (AutoCloseable ticks : java.util.Arrays.asList(hireTicks, screenTicks, triggerTicks, metricsTicks)) {
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
        AnimusDirector active = director;
        BukkitActor actor = new BukkitActor(sender, replies);
        if (stopping || active == null || runtime == null || !runtime.isRunning()) { actor.tell(Palette.error("Animus is not running.")); return true; }
        active.execute(actor, arguments);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        AnimusDirector active = director;
        if (stopping || active == null) return List.of();
        return active.complete(new BukkitActor(sender, replies), arguments);
    }

    /** One action-bar line for a debug watcher, on the player's owner; false once the player is gone. */
    private boolean actionBar(UUID id, String line) {
        Player player = getServer().getPlayer(id);
        if (player == null || stopping) return false;
        String coloured = RichText.colorize(line);
        return replies.reply(player, () -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloured)));
    }

    /** Registers Animus as a React metric source when React is enabled; React's API is reached only by reflection. */
    private synchronized void bindReact() {
        if (react != null || stopping) return;
        org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin("React");
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            react = ReactBridge.bind(plugin.getClass().getClassLoader(), new ReactBridge.Registrar() {
                @SuppressWarnings({"unchecked", "rawtypes"})
                @Override public void register(Class<?> service, Object provider) {
                    getServer().getServicesManager().register((Class) service, provider, AnimusPlugin.this, ServicePriority.Normal);
                }
                @Override public void unregister(Object provider) { getServer().getServicesManager().unregister(provider); }
            });
            getLogger().info("React metrics: published as source " + ReactMetricCatalog.SOURCE_ID + " (" + ReactMetricCatalog.SPECS.size() + " metrics; see the Animus debug and React metrics docs)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            getLogger().warning("React is installed but its metric API is unavailable: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /** React may enable after Animus or be reloaded; bind when it enables and drop the source when it disables. */
    private final class ReactWatcher implements Listener {
        @EventHandler public void enabled(PluginEnableEvent event) { if (event.getPlugin().getName().equals("React")) bindReact(); }
        @EventHandler public void disabled(PluginDisableEvent event) {
            if (!event.getPlugin().getName().equals("React")) return;
            ReactBridge bridge = react;
            react = null;
            if (bridge != null) bridge.close();
        }
    }

    /** The web editor API: the embedded listener and the relay client, both off until config.toml enables them. */
    private void startEditor(Platform modulePlatform) {
        NpcRuntime<Player, ItemStack> owners = living;
        java.util.function.Supplier<Map<String, EditorHost.Feature>> features = () -> {
            Map<String, EditorHost.Feature> available = new HashMap<>();
            available.put("combat", combat.status().toLowerCase(Locale.ROOT).contains("unavailable") ? EditorHost.Feature.no(combat.status()) : EditorHost.Feature.yes());
            String buildsRefused = buildsRefusal();
            available.put("builds", buildsRefused == null ? EditorHost.Feature.yes() : EditorHost.Feature.no(buildsRefused));
            available.put("gloss", getServer().getPluginManager().isPluginEnabled("Gloss") ? EditorHost.Feature.yes() : EditorHost.Feature.no("Gloss is not installed"));
            available.put("placeholderapi", getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") ? EditorHost.Feature.yes() : EditorHost.Feature.no("PlaceholderAPI is not installed"));
            return available;
        };
        EditorService service = new EditorService(runtime, new RuntimeEditorHost<>(new RuntimeEditorHost.Parts<>(runtime, commands, owners, extensions,
                modulePlatform.name().toLowerCase(Locale.ROOT), "", features, new BukkitLookups(getServer())::read,
                (Player player) -> player.hasPermission("animus.admin"), (String message) -> getLogger().info(message))), (String message) -> getLogger().info(message));
        try {
            service.start();
            editor = service;
            service.status().forEach(getLogger()::info);
        } catch (IOException | RuntimeException failure) {
            getLogger().log(Level.WARNING, "The web editor API could not start", failure);
            service.close();
        }
    }

    /**
     * Builds run on the main thread on Bukkit and Paper. On Folia the global region keeps the build records and sends
     * each build's turns to the region that owns where it works ({@link RegionBuildNpcs}).
     */
    private void startBuilds(PersistedNpcService<Player, ItemStack> mainThread, RegionNpcRuntime regionized, FoliaRegions folia) {
        RegionFlags.Binding flags = RegionFlags.bind(getServer());
        BuildProtection protection = flags == null ? BuildProtection.NONE : new RegionBuildProtection(flags.flags());
        String name = flags == null ? "none installed" : flags.describe("every block change is denied");
        Supplier<TriggerSink> triggers = () -> { BehaviorCommands current = behaviors; return current == null ? null : current.triggers(); };
        if (mainThread != null) {
            builds = new BuildService<>(runtime, mainThread, commands, new BukkitBuildPlatform(getServer(), null), runtime.moduleHooks().buildProtection(protection), name,
                    tick -> getServer().getScheduler().runTaskTimer(this, tick, 1, 1)::cancel, triggers, (String message) -> getLogger().info(message), null, null);
        } else {
            RegionBuildNpcs npcs = new RegionBuildNpcs(regionized);
            builds = new BuildService<>(runtime, npcs, commands, new BukkitBuildPlatform(getServer(), npcs.ownership()), runtime.moduleHooks().buildProtection(protection), name,
                    folia::globalTicks, triggers, (String message) -> getLogger().info(message), npcs.turns(), null);
        }
        getLogger().info(builds.status());
    }

    /** Why builds cannot run now, or null; follows {@code builds.enabled} in config.toml live. */
    private String buildsRefusal() {
        BuildService<Player, ItemStack> service = builds;
        return service != null ? service.unavailable().orElse(null) : "not started";
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

    /** The packet previews: {@code /animus proof} reaches persisted NPCs never, {@code foliaproof} runs on Folia only. */
    private final class BukkitProof implements ProofControl {
        /** The player viewer, or empty after telling the console that previews belong to a player. */
        private Optional<Player> viewer(CommandActor actor) {
            if (sender(actor) instanceof Player player) return Optional.of(player);
            actor.tell(Palette.error("Proof presentation requires a player viewer."));
            return Optional.empty();
        }

        private void run(CommandActor actor, Runnable work) {
            try { work.run(); }
            catch (RuntimeException e) { actor.tell(Palette.error("Proof: " + e.getMessage())); }
        }

        @Override public void status(CommandActor actor) { actor.tell(proof.status(sender(actor) instanceof Player player ? player : null)); }
        @Override public void spawn(CommandActor actor, String type, Optional<String> skin) {
            viewer(actor).ifPresent((Player player) -> run(actor, () -> {
                if (skin.isEmpty()) proof.spawn(player, type, null, null);
                else spawnSkinned(player, type, skin.get());
            }));
        }
        @Override public void move(CommandActor actor, int entity, double dx, double dy, double dz) { viewer(actor).ifPresent((Player player) -> run(actor, () -> proof.move(player, entity, dx, dy, dz))); }
        @Override public void path(CommandActor actor, int entity, int dx, int dy, int dz) { viewer(actor).ifPresent((Player player) -> run(actor, () -> proof.path(player, entity, dx, dy, dz))); }
        @Override public void control(CommandActor actor, int entity) { viewer(actor).ifPresent((Player player) -> run(actor, () -> proof.control(player, entity))); }
        @Override public void stopControl(CommandActor actor) { viewer(actor).ifPresent((Player player) -> run(actor, () -> proof.stopControl(player))); }
        @Override public void controlStatus(CommandActor actor) { viewer(actor).ifPresent((Player player) -> run(actor, () -> actor.tell(proof.controlStatus(player)))); }
        @Override public void mount(CommandActor actor, int vehicle, List<Integer> riders) { viewer(actor).ifPresent((Player player) -> run(actor, () -> proof.mount(player, vehicle, riders))); }
        @Override public void clear(CommandActor actor) { viewer(actor).ifPresent((Player player) -> run(actor, () -> { proof.clear(player); actor.tell(Palette.success("Proof cleared")); })); }

        private void spawnSkinned(Player player, String type, String skinHash) {
            AnimusRuntime active = runtime;
            PacketProofService.SkinRequest request = proof.beginSkinLoad(player);
            try { commands.execute(() -> {
                try {
                    String content = active.documents().read("skins/" + skinHash + ".json");
                    if (content == null || !JsonDocuments.hash(content).equals(skinHash)) throw new IOException("Skin is missing or its content hash changed");
                    JsonObject skin = JsonDocuments.object(content);
                    String texture = skin.get("textureRaw").getAsString();
                    String signature = skin.has("signature") && !skin.get("signature").isJsonNull() ? skin.get("signature").getAsString() : null;
                    if (!stopping) replies.reply(player, () -> {
                        try { if (!stopping && proof.finishSkinLoad(player, request)) proof.spawn(player, type, texture, signature); }
                        catch (RuntimeException e) { player.sendMessage(Palette.error("Proof: " + e.getMessage())); }
                    });
                } catch (IOException | RuntimeException e) {
                    if (!stopping) replies.reply(player, () -> {
                        if (proof.finishSkinLoad(player, request)) player.sendMessage(Palette.error("Cannot load proof skin: " + e.getMessage()));
                    });
                }
            }); } catch (RejectedExecutionException e) { proof.finishSkinLoad(player, request); throw e; }
        }

        private boolean folia(CommandActor actor) {
            if (foliaProof != null && foliaProof.available()) return true;
            actor.tell(foliaProof == null ? "Folia stationary proof requires Folia." : foliaProof.status());
            return false;
        }

        @Override public void foliaStatus(CommandActor actor) { if (folia(actor)) actor.tell(foliaProof.status()); }
        @Override public void foliaSpawn(CommandActor actor, Optional<String> skin) { folia(actor, "spawn", skin.orElse(null)); }
        @Override public void foliaClear(CommandActor actor) { folia(actor, "clear", null); }

        private void folia(CommandActor actor, String operation, String skin) {
            if (!folia(actor)) return;
            if (!(sender(actor) instanceof Player player)) { actor.tell(Palette.error("This proof belongs to a player viewer.")); return; }
            if (!foliaProof.execute(player, operation, skin)) actor.tell(Palette.error("Folia proof admission queue is full or unavailable; retry shortly."));
        }
    }

    private void printSplash(String splash) {
        getServer().getConsoleSender().sendMessage(AnimusSplash.colored(splash));
    }
}
