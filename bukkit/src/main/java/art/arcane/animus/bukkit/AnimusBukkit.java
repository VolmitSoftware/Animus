package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.metrics.AnimusBStats;
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
import art.arcane.animus.core.schedule.RuntimeScheduleWorld;
import art.arcane.animus.core.schedule.ScheduleService;
import art.arcane.animus.bukkit.schedule.BukkitScheduleHost;
import java.util.random.RandomGenerator;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Platform;
import art.arcane.animus.core.rig.AnimationEventRunner;
import art.arcane.animus.bukkit.event.VirtualNpcDialogueChoiceEvent;
import art.arcane.animus.bukkit.integration.libsdisguises.LibsDisguisesIntegration;
import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.bukkit.folia.FoliaStationaryProofService;
import art.arcane.animus.bukkit.gui.BukkitGlossBridge;
import art.arcane.animus.bukkit.gui.BukkitHoloEvents;
import art.arcane.animus.bukkit.gui.BukkitScreens;
import art.arcane.animus.bukkit.packet.NativeDisplayAdapter;
import art.arcane.animus.bukkit.recording.BukkitRecordings;
import art.arcane.animus.bukkit.toolkit.BukkitToolkit;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.bukkit.runtime.BukkitRuntimeHost;
import art.arcane.animus.bukkit.runtime.NpcKeyPresses;
import art.arcane.animus.bukkit.runtime.RegionBuildNpcs;
import art.arcane.animus.bukkit.runtime.RegionNpcRuntime;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.volmlib.util.plugin.ComponentLog;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.SplashScreen;
import art.arcane.animus.core.StartupCheck;
import art.arcane.animus.bukkit.building.BukkitBuildPlatform;
import art.arcane.animus.bukkit.protection.RegionBuildProtection;
import art.arcane.animus.bukkit.protection.RegionFlags;
import art.arcane.animus.core.text.CachedPlaceholders;
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
import art.arcane.animus.core.editor.EditorApi;
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
import java.nio.file.Path;
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
import art.arcane.animus.bukkit.integration.mythic.LookHooks;
import art.arcane.animus.bukkit.integration.mythic.MythicSupport;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Everything the plugin runs; {@link AnimusPlugin} stays a thin shell so the packed jar keeps few classes outside its compressed runtime. */
final class AnimusBukkit {
    // bstats.org plugin id
    private static final int BSTATS_PLUGIN_ID = 35204;
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
    private BukkitToolkit toolkit;
    private volatile ShopService<Player, ItemStack> shops;
    private volatile HoloService<Player, ItemStack> guis;
    private volatile art.arcane.animus.bukkit.quest.BukkitQuests quests;
    private volatile art.arcane.animus.bukkit.integration.quests.QuestIntegrations questPlugins;
    private AutoCloseable screenTicks, hireTicks, scheduleTicks, triggerTicks, bossBarTicks;
    private art.arcane.animus.bukkit.runtime.BukkitBossBars bossBars;
    private final DialogueTriggers dialogueTriggers = new DialogueTriggers(() -> {
        BehaviorCommands current = behaviors;
        return current == null ? null : current.triggers();
    });
    private volatile boolean stopping;
    /** Why the last start failed, for /animus replies, or null; the plugin stays enabled so its command can say so. */
    private volatile String startupFailure;
    private boolean released;
    private volatile EditorService editor;
    private volatile AnimusMetrics metrics;
    private final DebugWatch debugWatch = new DebugWatch();
    private AutoCloseable metricsTicks;
    private volatile ReactBridge react;
    // AnimusBStats owns all bStats types; never reference them from this class or AnimusPlugin (link trap)
    private AnimusBStats bStats;
    private HotloadService hotload;
    private BukkitServerBehaviors serverBehaviors;
    private AutoCloseable mythic;
    private final JavaPlugin host;

    AnimusBukkit(JavaPlugin host) {
        this.host = host;
    }

    void enable() {
        long started = System.nanoTime();
        startupFailure = null;
        released = false;
        stopping = false;
        try {
            AnimusMetrics measured = AnimusMetrics.restart();
            metrics = measured;
            Path data = RuntimeEnvironment.resolveDataDirectory(host.getDataFolder().toPath());
            RuntimeEnvironment environment = RuntimeEnvironment.bukkit(host.getServer().getBukkitVersion(), data, serverName());
            runtime = new AnimusRuntime(environment, failure -> host.getLogger().log(Level.SEVERE, "Animus file cleanup failed", failure));
            LibsDisguisesIntegration.install(host.getServer().getPluginManager(), host.getLogger());
            runtime.start();
            art.arcane.animus.bukkit.api.AnimusNpcs lookup = new art.arcane.animus.bukkit.runtime.BukkitNpcLookup(runtime.npcs()::snapshot);
            host.getServer().getServicesManager().register(art.arcane.animus.bukkit.api.AnimusNpcs.class, lookup, host, ServicePriority.Normal);
            CachedPlaceholders placeholders = BukkitPlaceholders.install(host);
            placeholders.refreshTicks(runtime.placeholderSettings().refreshTicks());
            runtime.onSettings((art.arcane.animus.core.config.PluginSettings next) -> placeholders.refreshTicks(next.placeholders().refreshTicks()));
            runtime.placeholders(placeholders);
            java.util.function.BooleanSupplier citizens = () -> {
                org.bukkit.plugin.Plugin plugin = host.getServer().getPluginManager().getPlugin("Citizens");
                return plugin != null && plugin.isEnabled();
            };
            java.util.function.BooleanSupplier shopkeepers = () -> {
                org.bukkit.plugin.Plugin plugin = host.getServer().getPluginManager().getPlugin("Shopkeepers");
                return plugin != null && plugin.isEnabled();
            };
            java.util.function.BooleanSupplier denizen = () -> {
                org.bukkit.plugin.Plugin plugin = host.getServer().getPluginManager().getPlugin("Denizen");
                return plugin != null && plugin.isEnabled();
            };
            runtime.importHost(new CitizensDropIn.Host(Optional.ofNullable(host.getDataFolder().getAbsoluteFile().getParentFile()).map(java.io.File::toPath), citizens, shopkeepers, denizen));
            CitizensGuard.install(citizens);
            replies = new ReplyScheduler(host);
            LibsDisguisesIntegration.serverThread((Runnable task) -> {
                if (!replies.reply(host.getServer().getConsoleSender(), task)) throw new RejectedExecutionException("the server refused console work");
            });
            commands = runtime.fileExecutor((task, event, failure) -> {
                // Services retire their pending callbacks before this lane is closed.
                if (event == MutationLane.EngineTaskEvent.FAILED)
                    host.getLogger().log(Level.WARNING, "File task failed", failure);
            });
            proof = new PacketProofService(host, replies, environment.minecraftVersion(), runtime);
            if (replies.isFolia()) {
                foliaProof = new FoliaStationaryProofService(host, environment.minecraftVersion(), runtime, runtime.documents(), commands);
            }
            NpcRuntimeSettings npcSettings = runtime.npcSettings();
            PersistedNpcService.Settings settings = PersistedNpcService.Settings.from(npcSettings);
            FoliaRegions regions = replies.isFolia() ? new FoliaRegions(host) : null;
            PersistedNpcService<Player, ItemStack> mainThread = regions == null ? BukkitRuntimeHost.start(host, runtime, environment.minecraftVersion(), commands, settings, (Player player, Runnable task) -> replies.reply(player, task)) : null;
            living = regions == null ? mainThread : new RegionNpcRuntime(host, runtime, environment.minecraftVersion(), commands, settings, regions);
            if (!living.available()) host.getLogger().warning(living.status());
            combat = new RuntimeCombatService(host, runtime, mainThread, environment.minecraftVersion(), replies.isFolia(), living instanceof RegionNpcRuntime regionized ? regionized : null);
            if (combat.status().startsWith("Combat unavailable")) host.getLogger().warning(combat.status());
            Platform modulePlatform = modulePlatform();
            Function<Runnable, AutoCloseable> ownerTicks = regions != null ? regions::globalTicks : tick -> host.getServer().getScheduler().runTaskTimer(host, tick, 1, 1)::cancel;
            Economy vault = runtime.moduleHooks().economy(BukkitScreens.vault(host.getServer()));
            screens = new BukkitScreens(host, (Player player, Runnable task) -> replies.reply(player == null ? host.getServer().getConsoleSender() : player, task),
                    () -> ShopService.economy(runtime.shopSettings(), vault));
            shops = new ShopService<>(runtime, screens, living, commands,
                    (String name) -> Optional.ofNullable(host.getServer().getPlayerExact(name)).map(Player::getUniqueId), message -> host.getLogger().info(message));
            screens.bind(shops.screens());
            DisplayTransport<Player> displays = NativeDisplayAdapter.create(host, environment.minecraftVersion(), (Player player, Runnable task) -> replies.reply(player, task));
            guis = new HoloService<>(runtime, shops, displays, living, commands, BukkitGlossBridge.load(host), message -> host.getLogger().info(message));
            host.getServer().getPluginManager().registerEvents(new BukkitHoloEvents(guis), host);
            host.getServer().getPluginManager().registerEvents(new NpcKeyPresses(living), host);
            quests = new art.arcane.animus.bukkit.quest.BukkitQuests(host, runtime, shops.screens(), living, displays, guis::watch, commands,
                    () -> { BehaviorCommands current = behaviors; return current == null ? null : current.servers(); }, message -> host.getLogger().info(message));
            art.arcane.animus.core.quest.QuestService<Player, ItemStack> questService = quests.service();
            HireService<Player, ItemStack> hireDesk = new HireService<>(runtime, shops.screens(), living, commands, combat.hireGuarding(), message -> host.getLogger().info(message));
            hireTicks = ownerTicks.apply(measured.timed(Subsystem.BEHAVIORS, hireDesk::tick));
            AnimusRuntime scheduled = runtime;
            ScheduleService schedules = new ScheduleService(() -> scheduled.npcs().snapshot(), new RuntimeScheduleWorld<>(living, new BukkitScheduleHost(host.getServer(), living)),
                    () -> scheduled.behaviorSettings().actions().commandNamespaces(), RandomGenerator.getDefault(), message -> host.getLogger().info(message));
            scheduleTicks = ownerTicks.apply(measured.timed(Subsystem.BEHAVIORS, schedules::tick));
            bossBars = new art.arcane.animus.bukkit.runtime.BukkitBossBars(runtime::npcs, living);
            bossBarTicks = ownerTicks.apply(measured.timed(Subsystem.PRESENTATION, bossBars::tick));
            ShopService<Player, ItemStack> screenService = shops;
            HoloService<Player, ItemStack> holoService = guis;
            toolkit = new BukkitToolkit(host, runtime, shops.screens(), living, commands, (Player player) -> { BukkitRecordings current = recordings; return current == null ? Optional.empty() : current.skin(player); },
                    mainThread != null ? mainThread::relocate : ((RegionNpcRuntime) living)::relocate, ownerTicks);
            BukkitToolkit tools = toolkit;
            living.interactions((Player player, UUID npc, boolean attack, boolean sneaking) -> tools.interacted(player, npc, attack, sneaking) ||
                    hireDesk.interacted(player, npc, attack, sneaking) || questService.interacted(player, npc, attack, sneaking)
                    || screenService.interacted(player, npc, attack, sneaking) || holoService.interacted(player, npc, attack, sneaking));
            NpcRuntime<Player, ItemStack> owners = living;
            screenTicks = ownerTicks.apply(measured.timed(Subsystem.GUI, () -> {
                for (UUID viewer : screenService.screens().viewing()) owners.players().deliver(viewer, screenService.screens()::tick);
                if (!holoService.idle()) for (Player online : host.getServer().getOnlinePlayers()) owners.players().deliver(online.getUniqueId(), holoService::tick);
            }));
            shops.reload(catalog -> {});
            guis.reload(catalog -> {});
            quests.start(ownerTicks);
            startBuilds(mainThread, living instanceof RegionNpcRuntime regionized ? regionized : null, regions);
            BukkitOwners scheduling = new BukkitOwners(host, regions);
            builtins = new BuiltinCapabilities(new BukkitBehaviorPlatform(host, living, combat, builds, this::buildsRefusal,
                    (Runnable tick) -> ownerTicks.apply(measured.timed(Subsystem.BEHAVIORS, tick)), () -> guis, scheduling, mainThread, runtime.placeholders(), () -> ShopService.economy(runtime.shopSettings(), vault)),
                    runtime.behaviorState(), this::publishChoice,
                    runtime.behaviorSettings().actions(), message -> host.getLogger().info(message));
            builtins.quests(questService);
            extensions = new ExtensionRuntime(runtime.documents().resolve("modules"),
                    modulePlatform, runtime.definitionGateway(), runtime.moduleSettings(), message -> host.getLogger().info(message), initialized -> {
                        BehaviorCommands current = behaviors;
                        if (current != null) current.modulesInitialized(initialized);
                    }, builtins);
            extensions.connectHooks(runtime.moduleHooks());
            extensions.follow(runtime);
            mythic = MythicSupport.start(new MythicSupport.Parts(host, runtime, extensions, living::state, MythicSupport.owners(host, regions), ownerTicks,
                    LookHooks.DEFAULT, (String message) -> host.getLogger().info(message))).orElse(null);
            ExtensionRuntime modules = extensions;
            living.observeInteractions(modules::interacted);
            art.arcane.animus.bukkit.integration.quests.RuntimeQuestNpcHost questNpcs = new art.arcane.animus.bukkit.integration.quests.RuntimeQuestNpcHost(runtime, living, lookup, commands, host.getLogger()::warning);
            host.getServer().getPluginManager().registerEvents(questNpcs, host);
            questPlugins = art.arcane.animus.bukkit.integration.quests.QuestIntegrations.start(host, questNpcs);
            living.animationEvents(new AnimationEventRunner((Capabilities.Key key, NpcHandle npc, String arguments) -> modules.action(key, npc, Optional.empty(), arguments),
                    (String message) -> host.getLogger().info(message)));
            proof.observeInteractions(modules::interacted);
            if (foliaProof != null) foliaProof.observeInteractions(modules::interacted);
            behaviors = new BehaviorCommands(data, runtime, extensions, (String message) -> {
                if (message.contains(": FAILED")) host.getLogger().warning(message); else host.getLogger().info(message);
            });
            runtime.catalogs(new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, owners::refresh, () -> behaviors,
                    () -> screenService.reload((art.arcane.animus.core.shop.ShopCatalog catalog) -> {}),
                    () -> holoService.reload((art.arcane.animus.core.gui.HoloCatalog catalog) -> {}),
                    () -> questService.reload((art.arcane.animus.core.quest.QuestCatalog catalog) -> {}), (String message) -> host.getLogger().info(message))));
            LibsDisguisesIntegration.reloadWith(() -> commands.execute(() -> runtime.catalogs().reload(
                    new art.arcane.animus.core.CatalogChange(java.util.Set.of(art.arcane.animus.core.editor.DocumentKind.NPC), java.util.Set.of(), false), (String problem) -> {})));
            BuiltinCapabilities actions = builtins;
            runtime.onSettings((PluginSettings next) -> actions.settings(next.behaviors().actions()));
            hotload = new HotloadService(new HotloadService.Parts(runtime, commands, (Runnable task) -> {
                        if (!replies.reply(host.getServer().getConsoleSender(), task)) throw new RejectedExecutionException("the server refused console work");
                    }, living, new BukkitOperators(host, replies), screenService::refreshOpen,
                    (String message) -> host.getLogger().info(message), (String message) -> host.getLogger().warning(message)));
            hotload.start();
            TriggerDispatcher triggers = behaviors.triggers();
            ServerTriggerDispatcher servers = behaviors.servers();
            if (triggers != null) triggerTicks = ownerTicks.apply(new art.arcane.animus.core.behavior.trigger.TriggerLink(triggers, servers, living, (String message) -> host.getLogger().info(message)));
            Function<UUID, Optional<VirtualNpcState.Snapshot>> states = owners::state;
            AnimusRuntime definitions = runtime;
            SnapshotFacts facts = new SnapshotFacts(SnapshotFacts.npcs(() -> definitions.npcs().snapshot(), states));
            serverBehaviors = new BukkitServerBehaviors(host, scheduling, servers, facts, (String message) -> host.getLogger().info(message));
            behaviors.facts(facts);
            quests.facts(facts);
            serverBehaviors.start();
            recordings = new BukkitRecordings(host, runtime, commands, regions, environment.minecraftVersion());
            AnimusRuntime measuredRuntime = runtime;
            DebugCommands debugCommands = new DebugCommands(() -> metrics, debugWatch,
                    (String id) -> measuredRuntime.isRunning() ? measuredRuntime.npcs().find(id).map(NpcDefinition::uuid) : Optional.empty(),
                    () -> { EditorService active = editor; return active == null ? List.of("Editor: off") : active.status(); });
            CommandServices commandServices = new CommandServices(runtime, commands, living, () -> behaviors, extensions, builtins, combat.control(), shops, guis, questService,
                    new BuildCommands(() -> builds, () -> Optional.ofNullable(buildsRefusal())), new EditorCommands(() -> editor, commands), debugCommands,
                    new BukkitProof(), recordings.sessions(), () -> host.getServer().getOnlinePlayers().stream().map(Player::getName).toList(), () -> stopping,
                    this::integrationRunning);
            measured.extra("combat.waiting", combat::waiting);
            measured.extra("combat.load", combat::load);
            measured.extra("editor.streams", () -> { EditorService active = editor; return active == null ? 0 : active.eventStreams(); });
            measured.listen((MetricsSnapshot snapshot) -> debugWatch.publish(snapshot, this::actionBar));
            measured.listen((MetricsSnapshot snapshot) -> { ReactBridge bridge = react; if (bridge != null) bridge.publish(snapshot); });
            metricsTicks = ownerTicks.apply(measured::serverTick);
            host.getServer().getPluginManager().registerEvents(new ReactWatcher(), host);
            bindReact();
            director = new AnimusCommandTree(commandServices).director();
            CommandTreeHints.install(host, List.of(BuildIdentity.ID, host.getName().toLowerCase(java.util.Locale.ROOT) + ":" + BuildIdentity.ID), () -> director,
                    (Player player) -> new BukkitActor(player, replies));
            extensions.start();
            startEditor(modulePlatform, regions);
            updateMetrics(runtime.metrics());
            runtime.onSettings((PluginSettings next) -> updateMetrics(next.metrics()));
            SplashScreen.Startup health = StartupCheck.check(runtime, living.available(), shops.stateProblem(), editor, (String warning) -> host.getLogger().warning(warning));
            if (runtime.splashScreen()) splash(health);
            else if (!health.ready()) host.getLogger().warning("Startup: " + health.label());
            host.getLogger().info("Enabled " + BuildIdentity.VERSION + " on " + host.getServer().getName() + " " + environment.minecraftVersion() + (replies.isFolia() ? " (Folia regions)" : "")
                    + " in " + (System.nanoTime() - started) / 1_000_000 + "ms | " + runtime.npcs().all().size() + " NPCs | editor " + editorState());
            BukkitOnboarding.start(host, replies, runtime, commands, () -> stopping);
        } catch (IOException exception) {
            // A bad config.toml, an unreadable data folder or a damaged file: the message names it, so no stack trace.
            failed(exception.getMessage(), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            failed(exception.getClass().getSimpleName() + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * Logs why the start failed as one SEVERE line, releases what had started, and keeps the plugin enabled so
     * {@code /animus} can tell players and the console what happened instead of the server's "plugin is disabled" error.
     */
    private void failed(String reason, Throwable trace) {
        startupFailure = reason;
        String line = "Animus did not start: " + reason + ". Fix it and restart the server.";
        if (trace == null) host.getLogger().severe(line); else host.getLogger().log(Level.SEVERE, line, trace);
        splash(SplashScreen.Startup.FAILED);
        disable();
    }

    void disable() {
        if (released) return;
        released = true;
        stopping = true;
        // Stop bStats first so no chart callable reads a runtime that is shutting down.
        updateMetrics(false);
        if (hotload != null) { hotload.close(); hotload = null; }
        LibsDisguisesIntegration.uninstall();
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
        if (mythic != null) {
            try { mythic.close(); }
            catch (Exception ignored) { /* Its listeners and ticks stop with the plugin. */ }
            mythic = null;
        }
        if (questPlugins != null) { questPlugins.close(); questPlugins = null; }
        if (serverBehaviors != null) { serverBehaviors.close(); serverBehaviors = null; }
        if (behaviors != null) behaviors.close();
        if (builtins != null) builtins.close();
        for (AutoCloseable ticks : java.util.Arrays.asList(hireTicks, scheduleTicks, screenTicks, triggerTicks, metricsTicks, bossBarTicks)) {
            if (ticks == null) continue;
            try { ticks.close(); }
            catch (Exception ignored) { /* The scheduler stops with the plugin. */ }
        }
        if (bossBars != null) { bossBars.close(); bossBars = null; }
        if (toolkit != null) { toolkit.close(); toolkit = null; }
        if (screens != null) screens.closeAll();
        if (quests != null) { quests.close(); quests = null; }
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
        if (activeRuntime != null) awaitClosed(activeRuntime);
    }

    /**
     * Waits, bounded, for queued writes, the final flushes and the data folder lock, so a reload can lock it again and a
     * stop does not exit while daemon file threads still write.
     */
    private void awaitClosed(AnimusRuntime closing) {
        try {
            if (!closing.closeAndWait(AnimusRuntime.SHUTDOWN_WAIT))
                host.getLogger().warning("File writes were still running " + AnimusRuntime.SHUTDOWN_WAIT.toSeconds() + "s after stop; they keep going and the data folder stays locked until they end");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            host.getLogger().warning("Stop was interrupted while file writes were finishing");
        }
    }

    boolean command(CommandSender sender, String[] arguments) {
        AnimusDirector active = director;
        String failure = startupFailure;
        if (failure != null) { sender.sendMessage(Palette.error("Animus did not start: " + failure + ". Fix it and restart; the server log has the details")); return true; }
        BukkitActor actor = new BukkitActor(sender, replies);
        if (stopping || active == null || runtime == null || !runtime.isRunning()) { actor.tell(Palette.error("Not running; the server log has the startup error")); return true; }
        active.execute(actor, arguments);
        return true;
    }

    List<String> complete(CommandSender sender, String[] arguments) {
        AnimusDirector active = director;
        if (stopping || active == null) return List.of();
        return active.complete(new BukkitActor(sender, replies), arguments);
    }

    /** One action-bar line for a debug watcher, on the player's owner; false once the player is gone. */
    private boolean actionBar(UUID id, String line) {
        Player player = host.getServer().getPlayer(id);
        if (player == null || stopping) return false;
        String coloured = RichText.colorize(line);
        return replies.reply(player, () -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloured)));
    }

    /** Registers Animus as a React metric source when React is enabled; React's API is reached only by reflection. */
    private synchronized void bindReact() {
        if (react != null || stopping) return;
        org.bukkit.plugin.Plugin plugin = host.getServer().getPluginManager().getPlugin("React");
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            react = ReactBridge.bind(plugin.getClass().getClassLoader(), new ReactBridge.Registrar() {
                @SuppressWarnings({"unchecked", "rawtypes"})
                @Override public void register(Class<?> service, Object provider) {
                    host.getServer().getServicesManager().register((Class) service, provider, host, ServicePriority.Normal);
                }
                @Override public void unregister(Object provider) { host.getServer().getServicesManager().unregister(provider); }
            });
            host.getLogger().info("React metrics on: source " + ReactMetricCatalog.SOURCE_ID + ", " + ReactMetricCatalog.SPECS.size() + " metrics");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            host.getLogger().warning("React metrics off; its API is unavailable: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /** Starts or stops bStats to follow {@code metrics} in config.toml; bStats' own global opt-out still applies. */
    private synchronized void updateMetrics(boolean enabled) {
        if (!enabled || stopping) {
            if (bStats != null) { bStats.shutdown(); bStats = null; }
            return;
        }
        if (bStats != null) return;
        try { bStats = AnimusBStats.start(host, BSTATS_PLUGIN_ID, new UsageSource(modulePlatform())); }
        catch (RuntimeException | LinkageError failure) { host.getLogger().log(Level.WARNING, "bStats metrics could not start", failure); }
    }

    /** What the bStats charts read on bStats' own thread: volatile fields and immutable snapshots only, null while stopping. */
    private final class UsageSource implements AnimusBStats.Source {
        private final String platform;

        private UsageSource(Platform platform) {
            this.platform = switch (platform) { case FOLIA -> "Folia"; case PAPER -> "Paper"; default -> "Spigot"; };
        }

        @Override public String platform() { return platform; }

        @Override public MetricsSnapshot snapshot() {
            AnimusMetrics measured = metrics;
            return stopping || measured == null ? null : measured.snapshot();
        }

        @Override public Map<String, NpcDefinition> definitions() {
            AnimusRuntime active = runtime;
            if (stopping || active == null || !active.isRunning()) return null;
            try { return active.npcs().snapshot(); }
            catch (IllegalStateException stopped) { return null; }
        }

        @Override public String editor() {
            EditorService active = editor;
            if (stopping) return null;
            Optional<EditorApi> api = active == null ? Optional.empty() : active.api();
            if (api.isEmpty()) return "Off";
            return api.get().parts().tokens().list().isEmpty() ? "On" : "Paired";
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
    private void startEditor(Platform modulePlatform, FoliaRegions regions) {
        NpcRuntime<Player, ItemStack> owners = living;
        java.util.function.Supplier<Map<String, EditorHost.Feature>> features = () -> {
            Map<String, EditorHost.Feature> available = new HashMap<>();
            available.put("combat", combat.status().toLowerCase(Locale.ROOT).contains("unavailable") ? EditorHost.Feature.no(combat.status()) : EditorHost.Feature.yes());
            String buildsRefused = buildsRefusal();
            available.put("builds", buildsRefused == null ? EditorHost.Feature.yes() : EditorHost.Feature.no(buildsRefused));
            available.put("gloss", host.getServer().getPluginManager().isPluginEnabled("Gloss") ? EditorHost.Feature.yes() : EditorHost.Feature.no("Gloss is not installed"));
            available.put("placeholderapi", host.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") ? EditorHost.Feature.yes() : EditorHost.Feature.no("PlaceholderAPI is not installed"));
            // The web editor offers the model, disguise and Mythic fields only while their plugin runs.
            for (Map.Entry<String, String> plugin : Map.of("modelengine", "ModelEngine", "libsdisguises", "LibsDisguises", "mythicmobs", "MythicMobs").entrySet())
                available.put(plugin.getKey(), integrationRunning(plugin.getValue()) ? EditorHost.Feature.yes() : EditorHost.Feature.no(plugin.getValue() + " is not installed"));
            return available;
        };
        EditorService service = new EditorService(runtime, new RuntimeEditorHost<>(new RuntimeEditorHost.Parts<>(runtime, commands, owners, extensions,
                modulePlatform.name().toLowerCase(Locale.ROOT), art.arcane.animus.core.editor.EditorServerName.fallback(host.getServer().getMotd(), host.getServer().getWorldContainer().toPath()),
                features, new BukkitLookups(host.getServer())::read,
                (Player player, String node) -> player.hasPermission(node), new BukkitEditorNotices(host, replies), (String message) -> host.getLogger().info(message),
                new BukkitEditorTerrain(host, regions), (java.util.UUID player, String node) -> BukkitOfflinePermission.check(host.getServer(), player, node))), (String message) -> host.getLogger().info(message),
                (String message) -> host.getLogger().warning(message));
        try {
            service.start();
            editor = service;
            if (!service.problem().isEmpty()) host.getLogger().warning("Editor " + service.problem());
        } catch (IOException failure) {
            host.getLogger().warning("Editor could not start: " + failure.getMessage());
            service.close();
        } catch (RuntimeException failure) {
            host.getLogger().log(Level.WARNING, "Editor could not start", failure);
            service.close();
        }
    }

    /**
     * Builds run on the main thread on Bukkit and Paper. On Folia the global region keeps the build records and sends
     * each build's turns to the region that owns where it works ({@link RegionBuildNpcs}).
     */
    private void startBuilds(PersistedNpcService<Player, ItemStack> mainThread, RegionNpcRuntime regionized, FoliaRegions folia) {
        RegionFlags.Binding flags = RegionFlags.bind(host.getServer());
        BuildProtection protection = flags == null ? BuildProtection.NONE : new RegionBuildProtection(flags.flags());
        String name = flags == null ? "none installed" : flags.describe("every block change is denied");
        Supplier<TriggerSink> triggers = () -> { BehaviorCommands current = behaviors; return current == null ? null : current.triggers(); };
        if (mainThread != null) {
            builds = new BuildService<>(runtime, mainThread, commands, new BukkitBuildPlatform(host.getServer(), null), runtime.moduleHooks().buildProtection(protection), name,
                    tick -> host.getServer().getScheduler().runTaskTimer(host, tick, 1, 1)::cancel, triggers, (String message) -> host.getLogger().info(message), null, null);
        } else {
            RegionBuildNpcs npcs = new RegionBuildNpcs(regionized);
            builds = new BuildService<>(runtime, npcs, commands, new BukkitBuildPlatform(host.getServer(), npcs.ownership()), runtime.moduleHooks().buildProtection(protection), name,
                    folia::globalTicks, triggers, (String message) -> host.getLogger().info(message), npcs.turns(), null);
        }
        if (builds.status().startsWith("Builds unavailable") && runtime.buildSettings().enabled()) host.getLogger().warning(builds.status());
    }

    private String editorState() {
        EditorService active = editor;
        return active == null ? "off" : active.reach();
    }

    /** Why builds cannot run now, or null; follows {@code builds.enabled} in config.toml live. */
    /** Whether the plugin an integration command needs runs; a plugin named Quests counts only when it is Quests by PikaMug. */
    private boolean integrationRunning(String name) {
        org.bukkit.plugin.Plugin plugin = host.getServer().getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled()
                && (!name.equals("Quests") || art.arcane.animus.bukkit.integration.quests.QuestIntegrations.bridge(name, plugin.getClass().getName()) != null);
    }

    private String buildsRefusal() {
        BuildService<Player, ItemStack> service = builds;
        return service != null ? service.unavailable().orElse(null) : "not started";
    }

    private void publishChoice(DialogueChoice choice) {
        dialogueTriggers.publish(choice);
        Player player = host.getServer().getPlayer(choice.player());
        if (player != null) host.getServer().getPluginManager().callEvent(new VirtualNpcDialogueChoiceEvent(
                player, choice.npc().uuid(), choice.behavior(), choice.prompt(), choice.option()));
    }

    /** Paper and Folia take the banner through their component logger; Spigot gets it as plain text. */
    private void splash(SplashScreen.Startup startup) {
        try {
            String server = host.getServer().getName() + " " + SplashScreenSupport.serverVersionWithoutMcSuffix(host.getServer().getVersion());
            ComponentLog.logMarkup(host, host.getLogger(), null, Level.INFO, SplashScreen.render(SplashScreen.Details.current(server, startup, SplashScreen.editorUrl(runtime)), SplashScreen.Colors.MINIMESSAGE), null);
        } catch (RuntimeException | LinkageError failure) {
            host.getLogger().log(Level.WARNING, "Splash screen failed to render", failure);
        }
    }

    /** Paper, Folia or Spigot, as {@code /animus status} names the server. */
    private String serverName() {
        return switch (modulePlatform()) { case FOLIA -> "Folia"; case PAPER -> "Paper"; default -> "Spigot"; };
    }

    private Platform modulePlatform() {
        if (FoliaRegions.present()) return Platform.FOLIA;
        try {
            // Public API presence also recognizes servers retaining Paper APIs under another brand.
            Class.forName("io.papermc.paper.ServerBuildInfo", false, host.getServer().getClass().getClassLoader());
            return Platform.PAPER;
        } catch (ClassNotFoundException absent) { return Platform.SPIGOT; }
    }

    private static CommandSender sender(CommandActor actor) { return ((BukkitActor) actor).sender(); }

    /** The packet previews: {@code /animus proof} reaches persisted NPCs never, {@code proof folia} runs on Folia only. */
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
        @Override public List<Integer> bodies(CommandActor actor) { return sender(actor) instanceof Player player ? proof.bodyIds(player) : List.of(); }
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
            actor.tell(Palette.error(foliaProof == null ? "Folia only" : foliaProof.status()));
            return false;
        }

        @Override public void foliaStatus(CommandActor actor) { if (folia(actor)) actor.tell(foliaProof.status()); }
        @Override public void foliaSpawn(CommandActor actor, Optional<String> skin) { folia(actor, "spawn", skin.orElse(null)); }
        @Override public void foliaClear(CommandActor actor) { folia(actor, "clear", null); }

        private void folia(CommandActor actor, String operation, String skin) {
            if (!folia(actor)) return;
            if (!(sender(actor) instanceof Player player)) { actor.tell(Palette.error("Players only; run it in game")); return; }
            if (!foliaProof.execute(player, operation, skin)) actor.tell(Palette.error("Folia proof queue full or unavailable; try again in a moment"));
        }
    }
}
