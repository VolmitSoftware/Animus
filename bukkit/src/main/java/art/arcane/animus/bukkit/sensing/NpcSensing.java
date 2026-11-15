package art.arcane.animus.bukkit.sensing;

import art.arcane.animus.bukkit.event.VirtualNpcContactEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.event.VirtualNpcItemDropEvent;
import art.arcane.animus.bukkit.event.VirtualNpcLeashEvent;
import art.arcane.animus.bukkit.event.VirtualNpcProjectileHitEvent;
import art.arcane.animus.bukkit.event.VirtualNpcVehicleBumpEvent;
import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.core.behavior.trigger.EntitySensor;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import art.arcane.animus.core.combat.ProjectileFlight;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Sensed triggers on Paper, Spigot and Folia. NPC bodies are packets, so the server never tells anyone that an entity
 * walked into one, a projectile struck it or a cart ran it over: this looks. Every owner tick it gives a few awake NPCs
 * with sensed triggers a look around on their own owner (the main thread, or the NPC's region on Folia), follows real
 * projectiles launched near them on the projectile's owner, and checks leads on a click. An NPC no player is near sleeps.
 * While another plugin listens for the contact, vehicle, item or leash events, every awake NPC is looked at.
 */
public final class NpcSensing implements Listener, AutoCloseable {
    /** A projectile launched farther than this from every NPC that cares is not followed, nor for longer than a minute. */
    static final double FOLLOW_RADIUS = 64;
    static final int FLIGHT_TICKS = 1200;
    /** Leads reach ten blocks; a player's leashed mobs are looked for within that. */
    static final double LEAD_REACH = 10;
    private static final SpatialNpcIndex.Budget NEAR_LAUNCH = new SpatialNpcIndex.Budget(128, 256), NEIGHBOURS = new SpatialNpcIndex.Budget(16, 64);

    private final JavaPlugin plugin;
    private final NpcRuntime<Player, ItemStack> living;
    private final Supplier<TriggerDispatcher> triggers;
    private final Supplier<Map<String, NpcDefinition>> definitions;
    private final Function<Runnable, AutoCloseable> ownerTicks;
    private final FoliaRegions regions;
    private final BiPredicate<Projectile, UUID> stops;
    private final Predicate<Entity> standIns;
    private final EntitySightings.Owned owned;
    private final Map<UUID, EntitySensor> sensors = new ConcurrentHashMap<>();
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
    /** The warned key of a failing flight: one warning, whichever projectile it was. */
    private static final UUID FLIGHT_FAILED = new UUID(0, 0);
    /** Owner tick only. */
    private final Map<UUID, Long> due = new HashMap<>();
    private List<UUID> order = List.of();
    private Object orderedPlan, orderedDefinitions;
    private boolean orderedForPlugins;
    private long ticks;
    private int cursor;
    /** Projectiles followed now, by projectile; on Paper and Spigot the owner tick advances them. */
    private final AtomicInteger followed = new AtomicInteger();
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private AutoCloseable ticker;

    /**
     * {@code stops} answers, on the projectile's owner, whether combat follows the projectile and stops it in the NPC's
     * body; combat then reports that strike through {@link #struck}. {@code standIns} are entities that stand in for an
     * NPC body, such as combat's decoys, which are never sensed. {@code regions} is null off Folia.
     */
    public NpcSensing(JavaPlugin plugin, NpcRuntime<Player, ItemStack> living, Supplier<TriggerDispatcher> triggers, Supplier<Map<String, NpcDefinition>> definitions,
                      Function<Runnable, AutoCloseable> ownerTicks, FoliaRegions regions, BiPredicate<Projectile, UUID> stops, Predicate<Entity> standIns) {
        this.plugin = plugin; this.living = living; this.triggers = triggers; this.definitions = definitions; this.ownerTicks = ownerTicks;
        this.regions = regions; this.stops = stops; this.standIns = standIns;
        owned = regions == null ? (World world, int minX, int minZ, int maxX, int maxZ) -> true : regions::owns;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = ownerTicks.apply(this::tick);
    }

    private TriggerDispatcher.EntityPlan plan() {
        TriggerDispatcher dispatcher = triggers.get();
        return dispatcher == null ? TriggerDispatcher.EntityPlan.EMPTY : dispatcher.entityPlan();
    }
    private TriggerSettings budgets() {
        TriggerDispatcher dispatcher = triggers.get();
        return dispatcher == null ? TriggerSettings.DEFAULT : dispatcher.settings();
    }
    private static boolean listened(HandlerList handlers) { return handlers.getRegisteredListeners().length > 0; }
    /** Another plugin listens for something only a look around finds. */
    private static boolean pluginsLook() {
        return listened(VirtualNpcContactEvent.getHandlerList()) || listened(VirtualNpcVehicleBumpEvent.getHandlerList())
                || listened(VirtualNpcItemDropEvent.getHandlerList()) || listened(VirtualNpcLeashEvent.getHandlerList());
    }

    /** Owner tick: the main thread, or the global region on Folia. */
    private void tick() {
        if (closed) return;
        ticks++;
        if (regions == null) for (Flight flight : flights.values()) flight.run();
        TriggerSettings budgets = budgets();
        TriggerDispatcher.EntityPlan plan = plan();
        boolean plugins = pluginsLook();
        List<UUID> npcs = order(plan, plugins);
        if (npcs.isEmpty()) return;
        int looks = 0;
        for (int visited = 0; visited < npcs.size() && looks < budgets.entityScansPerTick(); visited++) {
            UUID npc = npcs.get(Math.floorMod(cursor++, npcs.size()));
            Long next = due.get(npc);
            if (next != null && ticks < next) continue;
            due.put(npc, ticks + budgets.entityIntervalTicks());
            if (!living.watched(npc)) continue;
            looks++;
            living.onOwner(npc, () -> safely(npc, budgets, plugins), (String reason) -> {});
        }
    }
    /** The NPCs to look at, rebuilt only when the plan, the plugin listeners or, for those listeners, the NPCs change. */
    private List<UUID> order(TriggerDispatcher.EntityPlan plan, boolean plugins) {
        Map<String, NpcDefinition> snapshot = plugins ? definitions.get() : null;
        if (plan == orderedPlan && plugins == orderedForPlugins && snapshot == orderedDefinitions) return order;
        Set<UUID> npcs = new LinkedHashSet<>();
        for (UUID npc : plan.npcs().keySet()) if (plan.scans(npc)) npcs.add(npc);
        if (snapshot != null) for (NpcDefinition definition : snapshot.values()) if (definition.spawned()) npcs.add(definition.uuid());
        order = List.copyOf(npcs);
        orderedPlan = plan; orderedForPlugins = plugins; orderedDefinitions = snapshot;
        sensors.keySet().retainAll(npcs);
        due.keySet().retainAll(npcs);
        return order;
    }

    /** One NPC's failing look is logged once and stops nothing else. */
    private void safely(UUID npc, TriggerSettings budgets, boolean plugins) {
        try { look(npc, budgets, plugins); }
        catch (RuntimeException failure) {
            if (warned.add(npc)) plugin.getLogger().log(java.util.logging.Level.WARNING, "Sensed triggers of NPC " + npc + " failed to look around", failure);
        }
    }
    /** On the NPC's owner. */
    private void look(UUID npc, TriggerSettings budgets, boolean plugins) {
        if (closed) return;
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc);
        if (active.isEmpty()) { sensors.remove(npc); return; }
        VirtualNpcState.Snapshot body = active.get().state();
        World world = world(body.position().world());
        if (world == null) return;
        TriggerDispatcher.EntityPlan plan = plan();
        List<EntitySensor.Watch> watches = plan.watches(npc);
        double reach = Math.max(plan.radius().getOrDefault(npc, 0.0), plugins ? EntitySensor.PLUGIN_RADIUS : 0) + 1.5;
        Vec3 low = body.bounds().min(), high = body.bounds().max(), center = body.position().point();
        BoundingBox box = new BoundingBox(low.x() - reach, low.y() - reach, low.z() - reach, high.x() + reach, high.y() + reach, high.z() + reach);
        EntitySightings.Look look = EntitySightings.nearby(world, box, center, budgets.entityCandidates(), owned, standIns, (Entity entity) -> true);
        Map<UUID, Entity> entities = new HashMap<>();
        List<EntitySensor.Seen> seen = new ArrayList<>();
        for (Entity entity : look.entities()) { entities.put(entity.getUniqueId(), entity); seen.add(EntitySightings.seen(entity, plugin.getServer())); }
        List<EntitySensor.Neighbour> neighbours = new ArrayList<>();
        if (plan.wants(npc, TriggerKind.NPC_PROXIMITY)) {
            for (VirtualNpcState.Snapshot other : living.nearbyNpcs(body.position(), reach, NEIGHBOURS).candidates())
                living.active(other.npcId()).ifPresent((NpcRuntime.ActiveNpc found) -> neighbours.add(new EntitySensor.Neighbour(found.state().npcId(), found.id(), found.state().position().point())));
        }
        EntitySensor sensor = sensors.computeIfAbsent(npc, EntitySensor::new);
        EntitySensor.Limits limits = new EntitySensor.Limits(budgets.entityTrackedPerNpc(), budgets.entityIntervalTicks());
        EntitySensor.Result result;
        synchronized (sensor) {
            result = plugins ? sensor.senseForPlugins(body, watches, seen, look.complete(), neighbours, this::holds, limits)
                    : sensor.sense(body, watches, seen, look.complete(), neighbours, this::holds, limits);
        }
        TriggerDispatcher dispatcher = triggers.get();
        if (dispatcher != null) for (TriggerEvent.Entity event : result.events()) dispatcher.publish(event);
        for (EntitySensor.Fact fact : result.facts()) announce(npc, active.get().id(), fact, entities.get(fact.entity()));
    }

    /** Tells other plugins, each only while one listens. */
    private void announce(UUID npc, String key, EntitySensor.Fact fact, Entity entity) {
        if (entity == null) return;
        switch (fact.kind()) {
            case CONTACT -> {
                if (listened(VirtualNpcContactEvent.getHandlerList())) plugin.getServer().getPluginManager().callEvent(new VirtualNpcContactEvent(npc, key, entity,
                        "top".equals(fact.context().get("contact")) ? VirtualNpcContactEvent.Contact.TOP : VirtualNpcContactEvent.Contact.SIDE));
            }
            case VEHICLE -> {
                if (entity instanceof Vehicle vehicle && listened(VirtualNpcVehicleBumpEvent.getHandlerList()))
                    plugin.getServer().getPluginManager().callEvent(new VirtualNpcVehicleBumpEvent(npc, key, vehicle, (double) fact.context().get("speed")));
            }
            case ITEM -> {
                if (entity instanceof Item item && listened(VirtualNpcItemDropEvent.getHandlerList()))
                    plugin.getServer().getPluginManager().callEvent(new VirtualNpcItemDropEvent(npc, key, item, Boolean.TRUE.equals(fact.context().get("thrown"))));
            }
            case LEASH -> {
                if (entity instanceof LivingEntity mob && mob.isLeashed() && listened(VirtualNpcLeashEvent.getHandlerList()))
                    plugin.getServer().getPluginManager().callEvent(new VirtualNpcLeashEvent(npc, key, mob, mob.getLeashHolder(), VirtualNpcLeashEvent.Kind.PASS));
            }
        }
    }

    /** A projectile launched near an NPC that cares is followed past NPC bodies on its own owner. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launched(ProjectileLaunchEvent event) {
        if (closed) return;
        Projectile projectile = event.getEntity();
        boolean plugins = listened(VirtualNpcProjectileHitEvent.getHandlerList());
        TriggerDispatcher.EntityPlan plan = plan();
        if (!plugins && !plan.kinds().contains(TriggerKind.HIT_BY_PROJECTILE)) return;
        if (followed.get() >= budgets().projectilesFollowed()) return;
        WorldPosition at = position(projectile.getLocation());
        boolean near = false;
        for (VirtualNpcState.Snapshot npc : living.nearbyNpcs(at, FOLLOW_RADIUS, NEAR_LAUNCH).candidates())
            if (plugins || plan.wants(npc.npcId(), TriggerKind.HIT_BY_PROJECTILE)) { near = true; break; }
        if (!near) return;
        followed.incrementAndGet();
        Flight flight = new Flight(projectile);
        flights.put(projectile.getUniqueId(), flight);
        if (regions != null) flight.task = regions.repeat(projectile, flight, flight::end, 1L, 1L);
    }

    /**
     * On the projectile's owner: combat stopped this real projectile in the NPC's body, as the strike lands and before it
     * can kill. The NPC's hit triggers fire with {@code stopped} true, once, whether or not the projectile was followed here.
     */
    public void struck(Projectile projectile, UUID npc, Vec3 point, boolean spent) {
        if (closed) return;
        boolean plugins = listened(VirtualNpcProjectileHitEvent.getHandlerList());
        TriggerDispatcher.EntityPlan plan = plan();
        Flight flight = flights.get(projectile.getUniqueId());
        Optional<FlightReports.Report> report = flight == null ? Optional.of(new FlightReports.Report(npc, point, true)) : flight.reports.struck(npc, point, spent);
        if (flight != null && flight.reports.spent()) flight.stop();
        if (report.isPresent() && (plugins || plan.wants(npc, TriggerKind.HIT_BY_PROJECTILE))) publish(projectile, report.get(), plan, plugins);
    }
    /** The NPC's hit triggers, and the plugin event while one listens. On the projectile's owner. */
    private void publish(Projectile projectile, FlightReports.Report report, TriggerDispatcher.EntityPlan plan, boolean plugins) {
        Optional<EntitySensor.Party> shooter = projectile.getShooter() instanceof Entity source
                ? Optional.of(EntitySightings.party(source, regions == null || regions.owns(source))) : Optional.empty();
        TriggerDispatcher dispatcher = triggers.get();
        if (dispatcher != null)
            for (TriggerEvent.Entity event : EntitySensor.projectile(report.npc(), plan.watches(report.npc()), shooter, projectile.getType().getKey().toString(),
                    projectile.getUniqueId(), report.point(), report.stopped(), this::holds))
                dispatcher.publish(event);
        if (plugins) living.active(report.npc()).ifPresent((NpcRuntime.ActiveNpc npc) -> plugin.getServer().getPluginManager().callEvent(
                new VirtualNpcProjectileHitEvent(report.npc(), npc.id(), projectile, new Location(projectile.getWorld(), report.point().x(), report.point().y(), report.point().z()), report.stopped())));
    }

    /** One followed projectile, advanced only on the thread that owns it. */
    private final class Flight implements Runnable {
        final Projectile projectile;
        final ProjectileFlight flight;
        final FlightReports reports;
        volatile AutoCloseable task;
        int age;
        volatile boolean ended;
        Flight(Projectile projectile) {
            this.projectile = projectile;
            reports = new FlightReports(projectile instanceof AbstractArrow arrow && !(projectile instanceof Trident) ? arrow.getPierceLevel() : 0);
            flight = new ProjectileFlight(0);
            flight.launched(position(projectile.getLocation()));
        }
        /** A failing flight stops alone, so it never holds up the other flights or the looks of the tick it runs in. */
        @Override public void run() {
            if (ended) return;
            try { advance(); }
            catch (RuntimeException failure) {
                if (warned.add(FLIGHT_FAILED)) plugin.getLogger().log(java.util.logging.Level.WARNING, "A followed " + projectile.getType().getKey() + " failed and is no longer followed", failure);
                stop();
            }
        }
        private void advance() {
            if (closed) { stop(); return; }
            boolean plugins = listened(VirtualNpcProjectileHitEvent.getHandlerList());
            TriggerDispatcher.EntityPlan plan = plan();
            Vector velocity = projectile.getVelocity();
            // A body combat stops the projectile in is crossed even when unwatched, so nothing behind it counts as flown through.
            List<ProjectileFlight.Hit> crossed = flight.crossings(position(projectile.getLocation()), new Vec3(velocity.getX(), velocity.getY(), velocity.getZ()),
                    (WorldPosition center, double radius, SpatialNpcIndex.Budget budget) -> living.nearbyNpcs(center, radius, budget),
                    (UUID npc) -> plugins || plan.wants(npc, TriggerKind.HIT_BY_PROJECTILE) || stops.test(projectile, npc));
            for (FlightReports.Report report : reports.crossed(crossed, (UUID npc) -> stops.test(projectile, npc))) publish(projectile, report, plan, plugins);
            if (reports.spent() || !projectile.isValid() || ++age > FLIGHT_TICKS || projectile instanceof AbstractArrow arrow && arrow.isInBlock()) stop();
        }
        void stop() {
            AutoCloseable running = task;
            try { if (running != null) running.close(); } catch (Exception ignored) { }
            end();
        }
        void end() {
            if (ended) return;
            ended = true;
            followed.decrementAndGet();
            flights.remove(projectile.getUniqueId(), this);
        }
    }

    /** On the player's owner: a player leading mobs clicked an NPC, which ties nothing, since a virtual NPC cannot hold a lead. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clicked(VirtualNpcInteractionEvent event) {
        if (closed || event.isPreview() || event.getAction() != VirtualNpcInteractionEvent.Action.INTERACT) return;
        boolean plugins = listened(VirtualNpcLeashEvent.getHandlerList());
        TriggerDispatcher.EntityPlan plan = plan();
        UUID npc = event.getNpcId();
        if (!plugins && !plan.wants(npc, TriggerKind.LEASH_TIE)) return;
        Player player = event.getViewer();
        Location at = player.getLocation();
        BoundingBox box = BoundingBox.of(at, LEAD_REACH, LEAD_REACH, LEAD_REACH);
        EntitySightings.Look leashed = EntitySightings.nearby(player.getWorld(), box, new Vec3(at.getX(), at.getY(), at.getZ()), 16, owned, standIns,
                (Entity entity) -> entity instanceof LivingEntity mob && !(entity instanceof Player) && mob.isLeashed() && player.equals(mob.getLeashHolder()));
        if (leashed.entities().isEmpty()) return;
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc);
        EntitySensor.Party holder = EntitySightings.party(player, true);
        TriggerDispatcher dispatcher = triggers.get();
        for (Entity entity : leashed.entities()) {
            LivingEntity mob = (LivingEntity) entity;
            Location where = mob.getLocation();
            if (dispatcher != null)
                for (TriggerEvent.Entity tie : EntitySensor.leashTie(npc, plan.watches(npc), EntitySightings.subject(mob, true), new Vec3(where.getX(), where.getY(), where.getZ()), holder, this::holds))
                    dispatcher.publish(tie);
            if (plugins && active.isPresent()) plugin.getServer().getPluginManager().callEvent(new VirtualNpcLeashEvent(npc, active.get().id(), mob, player, VirtualNpcLeashEvent.Kind.TIE));
        }
    }

    /** Any thread: permission checks read the player's attachments, which Bukkit permits off its owner. */
    private boolean holds(UUID player, String node) {
        Player online = plugin.getServer().getPlayer(player);
        return online != null && online.hasPermission(node);
    }
    private World world(String id) {
        try { return plugin.getServer().getWorld(UUID.fromString(id)); }
        catch (IllegalArgumentException notUuid) { return plugin.getServer().getWorld(id); }
    }
    private static WorldPosition position(Location location) {
        return new WorldPosition(location.getWorld().getUID().toString(), new Vec3(location.getX(), location.getY(), location.getZ()));
    }

    @Override public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        AutoCloseable running = ticker;
        ticker = null;
        try { if (running != null) running.close(); } catch (Exception ignored) { }
        for (Flight flight : List.copyOf(flights.values())) flight.end();
        sensors.clear();
    }
}
