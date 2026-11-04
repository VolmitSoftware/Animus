package art.arcane.animus.qa;

import art.arcane.animus.bukkit.event.VirtualNpcDamageEvent;
import art.arcane.animus.bukkit.event.VirtualNpcDamagedByMobEvent;
import art.arcane.animus.bukkit.combat.*;
import art.arcane.animus.core.virtual.*;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only ordinary entities and event observers. This class is never in the distributed plugin. */
final class CombatFixture implements Listener, AutoCloseable {
    private static final String TAG = "AnimusCombatQA";
    private final JavaPlugin plugin;
    private final Map<UUID, Observation> entities = new HashMap<>();
    private final Map<UUID, Arm> outgoing = new HashMap<>(), real = new HashMap<>(), incoming = new HashMap<>();
    private final Map<UUID, UUID> sourceMarkers = new HashMap<>();
    private final Map<UUID, MobSnapshot> snapshots = new HashMap<>();
    private record Arm(UUID owner, String mode, long expires) {}
    private static final class Observation {
        final UUID owner;
        int outgoing, real, incoming;
        boolean nullAttacker = true;
        Observation(UUID owner) { this.owner = owner; }
    }
    CombatFixture(JavaPlugin plugin) { this.plugin = plugin; }

    boolean command(Player player, String[] args) {
        if (args.length == 0) return false;
        if (args[0].equals("cleanup") && args.length == 1) {
            cleanup(player.getUniqueId()); player.sendMessage("QA cleanup combat complete"); return true;
        }
        if (args[0].equals("spawn") && args.length == 5) {
            if (entities.size() >= 32) throw new IllegalStateException("Fixture entity limit reached");
            Location location = location(player, args, 2);
            Class<? extends LivingEntity> type = switch (args[1]) { case "cow" -> Cow.class; case "zombie" -> Zombie.class; default -> throw new IllegalArgumentException("Use cow or zombie"); };
            LivingEntity entity = player.getWorld().spawn(location, type);
            entity.addScoreboardTag(TAG); entity.addScoreboardTag("AnimusCombatOwner_" + player.getUniqueId());
            entity.setPersistent(false); entity.setRemoveWhenFarAway(false);
            if (entity instanceof Cow) entity.setAI(false);
            if (entity instanceof Zombie zombie) {
                zombie.setAdult(); zombie.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET)); zombie.getEquipment().setHelmetDropChance(0);
            }
            entities.put(entity.getUniqueId(), new Observation(player.getUniqueId()));
            player.sendMessage("QA spawned type=" + args[1] + " entity=" + entity.getUniqueId() + " health=" + entity.getHealth()); return true;
        }
        if (args[0].equals("move") && args.length == 5) {
            LivingEntity entity = entity(player, args[1]);
            if (!entity.teleport(location(player, args, 2))) throw new IllegalStateException("Fixture teleport cancelled");
            player.sendMessage("QA moved entity=" + entity.getUniqueId()); return true;
        }
        if (args[0].equals("observe") && args.length == 2) {
            LivingEntity entity = entity(player, args[1]); Observation record = entities.get(entity.getUniqueId()); Location p = entity.getLocation();
            player.sendMessage("QA observed entity=" + entity.getUniqueId() + " health=" + entity.getHealth() + " outgoing=" + record.outgoing
                    + " real=" + record.real + " incoming=" + record.incoming + " null-attacker=" + record.nullAttacker
                    + " x=" + p.getX() + " y=" + p.getY() + " z=" + p.getZ()); return true;
        }
        if (Set.of("combatcancel", "realcancel", "combatteleport", "mobcancel").contains(args[0]) && args.length == 2) {
            LivingEntity entity = entity(player, args[1]);
            Map<UUID, Arm> arms = args[0].equals("realcancel") ? real : args[0].equals("mobcancel") ? incoming : outgoing;
            arms.put(entity.getUniqueId(), new Arm(player.getUniqueId(), args[0], System.nanoTime() + 10_000_000_000L));
            player.sendMessage("QA armed mode=" + args[0] + " entity=" + entity.getUniqueId()); return true;
        }
        try {
            if (args[0].equals("bridgeclosecheck") && args.length == 3) {
                LivingEntity entity = entity(player, args[1]), target = entity(player, args[2]);
                if (!(entity instanceof Zombie zombie) || !(target instanceof Cow)) throw new IllegalArgumentException("Use fixture zombie and cow UUIDs");
                zombie.setTarget(target);
                Location at = zombie.getLocation();
                VirtualNpcState state = new VirtualNpcState(UUID.randomUUID(), new WorldPosition(at.getWorld().getUID().toString(),
                        new Vec3(at.getX(), at.getY(), at.getZ())), 0.6, 1.8, 20);
                state.spawn();
                BukkitCombatBridge bridge = new BukkitCombatBridge(plugin, exactMinecraftVersion(), false,
                        CombatLimits.DEFAULT, (action, npc, realEntity) -> true, BukkitCombatBridge.Defense.NONE);
                try {
                    bridge.register(state, () -> true, snapshot -> {}).control(zombie, MobProfile.DEFAULT);
                    bridge.close();
                    boolean rejected = false;
                    BukkitCombatBridge.Combatant unexpected = null;
                    try { unexpected = bridge.register(state, () -> true, snapshot -> {}); }
                    catch (IllegalStateException expected) { rejected = true; }
                    finally { if (unexpected != null) unexpected.close(); }
                    require(rejected && bridge.controlledMobCount() == 0 && bridge.combatantCount() == 0,
                            "Bridge close did not revoke registrations/counts: rejected=" + rejected
                                    + " mobs=" + bridge.controlledMobCount() + " npcs=" + bridge.combatantCount());
                    try (BukkitCombatBridge fresh = new BukkitCombatBridge(plugin, exactMinecraftVersion(), false,
                            CombatLimits.DEFAULT, (action, npc, realEntity) -> true, BukkitCombatBridge.Defense.NONE)) {
                        fresh.register(state, () -> true, snapshot -> {}).control(zombie, MobProfile.DEFAULT);
                        require(fresh.controlledMobCount() == 1, "Closed bridge retained the global mob claim");
                        fresh.close();
                        require(fresh.controlledMobCount() == 0 && fresh.combatantCount() == 0, "Fresh bridge did not release its probe lease");
                    }
                    player.sendMessage("QA bridge close registration-rejected=true reacquired=true controlled=0 registered=0");
                } finally { bridge.close(); }
                return true;
            }
            if (args[0].equals("replancheck") && args.length == 2) {
                LivingEntity entity = entity(player, args[1]);
                if (!(entity instanceof Zombie zombie)) throw new IllegalArgumentException("Use fixture zombie UUID");
                Object nav = invoke(handle(zombie), "getNavigation"); Object path = invoke(nav, "getPath");
                require(field(nav, "targetPos") == null, "Controlled navigation retained an autonomous replan target");
                for (int i = 0; i < 25; i++) invoke(nav, "recomputePath");
                require(invoke(nav, "getPath") == path && field(nav, "targetPos") == null, "Vanilla recomputation replaced the budgeted route");
                player.sendMessage("QA replan mob=" + zombie.getUniqueId() + " autonomous=false path-unchanged=true"); return true;
            }
            if (args[0].equals("controlprepare") && args.length == 4) {
                LivingEntity entity = entity(player, args[2]); LivingEntity target = entity(player, args[3]);
                if (!(entity instanceof Zombie zombie) || !(target instanceof Cow)) throw new IllegalArgumentException("Use fixture zombie and cow UUIDs");
                if (snapshots.containsKey(zombie.getUniqueId())) throw new IllegalStateException("Mob snapshot already active");
                zombie.setTarget(target);
                Object nativeMob = handle(zombie); Object nav = invoke(nativeMob, "getNavigation"); Location goal = target.getLocation();
                boolean planned = (boolean) nav.getClass().getMethod("moveTo", double.class, double.class, double.class, double.class)
                        .invoke(nav, goal.getX(), goal.getY(), goal.getZ(), 1.0);
                if (!planned) throw new IllegalStateException("Could not create original real-mob path for restoration proof");
                MobSnapshot snapshot = capture(zombie);
                if (snapshot.path.isEmpty()) throw new IllegalStateException("Restoration proof requires a nonempty original path");
                snapshots.put(zombie.getUniqueId(), snapshot);
                targetCommand(npcId(args[1]), zombie.getUniqueId().toString());
                MobSnapshot controlled = capture(zombie);
                boolean goals = controlled.movement.isEmpty() && controlled.targeting.isEmpty();
                boolean targetCleared = zombie.getTarget() == null; boolean pathCleared = controlled.path.isEmpty();
                require(goals && targetCleared && pathCleared, "Mob control did not suspend original state: goals=" + goals
                        + " target=" + targetCleared + " path=" + pathCleared);
                player.sendMessage("QA control prepared mob=" + zombie.getUniqueId() + " goals-suspended=" + goals
                        + " target-cleared=" + targetCleared + " path-suspended=" + pathCleared); return true;
            }
            if (args[0].equals("releasecheck") && args.length == 3) {
                LivingEntity entity = entity(player, args[2]);
                if (!(entity instanceof Zombie zombie)) throw new IllegalArgumentException("Use fixture zombie UUID");
                MobSnapshot original = snapshots.get(zombie.getUniqueId());
                if (original == null) throw new IllegalArgumentException("No original mob snapshot");
                targetCommand(npcId(args[1]), "stop");
                MobSnapshot restored = capture(zombie);
                boolean goals = original.movement.equals(restored.movement) && original.targeting.equals(restored.targeting);
                boolean target = Objects.equals(original.target, restored.target);
                boolean path = original.path.equals(restored.path) && Objects.equals(original.targetPos, restored.targetPos)
                        && original.reach == restored.reach && original.delayed == restored.delayed && original.lastRecompute == restored.lastRecompute;
                require(goals && target && path, "Mob restoration mismatch: goals=" + goals + " target=" + target + " path=" + path);
                snapshots.remove(zombie.getUniqueId());
                player.sendMessage("QA restore mob=" + zombie.getUniqueId() + " goals=" + goals + " target=" + target + " path=" + path); return true;
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native fixture inspection failed", error); }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOutgoing(VirtualNpcDamageEvent event) {
        Observation observation = entities.get(event.getTarget().getUniqueId()); if (observation == null) return;
        require(plugin.getServer().getEntity(event.getNpcId()) == null, "Combat NPC body is registered");
        observation.outgoing++;
        UUID id = event.getTarget().getUniqueId(); Arm arm = take(outgoing, id);
        if (arm != null && arm.mode.equals("combatcancel")) {
            event.setCancelled(true); tell(observation.owner, "QA combat cancelled entity=" + id); return;
        }
        if (arm != null && arm.mode.equals("combatteleport")) {
            Location moved = event.getTarget().getLocation().add(8, 0, 0);
            require(moved.getWorld().isChunkLoaded(moved.getBlockX() >> 4, moved.getBlockZ() >> 4), "Teleport fixture chunk must be loaded");
            require(event.getTarget().teleport(moved), "Reentrant target teleport cancelled");
            tell(observation.owner, "QA combat teleported entity=" + id); return;
        }
        sourceMarkers.put(id, event.getNpcId());
        plugin.getServer().getScheduler().runTask(plugin, () -> sourceMarkers.remove(id, event.getNpcId()));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void cancelReal(EntityDamageEvent event) {
        if (!sourceMarkers.containsKey(event.getEntity().getUniqueId())) return;
        Arm arm = take(real, event.getEntity().getUniqueId());
        if (arm != null) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void observeReal(EntityDamageEvent event) {
        UUID npcId = sourceMarkers.remove(event.getEntity().getUniqueId()); if (npcId == null) return;
        Observation observation = entities.get(event.getEntity().getUniqueId()); if (observation == null) return;
        observation.real++;
        boolean absent = event.getDamageSource().getCausingEntity() == null && event.getDamageSource().getDirectEntity() == null;
        observation.nullAttacker &= absent;
        require(absent && plugin.getServer().getEntity(npcId) == null, "Virtual damage fabricated a real attacker");
        tell(observation.owner, "QA real damage entity=" + event.getEntity().getUniqueId() + " cancelled=" + event.isCancelled() + " null-attacker=" + absent);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onIncoming(VirtualNpcDamagedByMobEvent event) {
        Observation observation = entities.get(event.getMob().getUniqueId()); if (observation == null) return;
        require(plugin.getServer().getEntity(event.getNpcId()) == null, "Virtual mob target was registered");
        observation.incoming++;
        Arm arm = take(incoming, event.getMob().getUniqueId());
        if (arm != null) event.setCancelled(true);
        tell(observation.owner, "QA mob hit npc=" + event.getNpcId() + " mob=" + event.getMob().getUniqueId() + " cancelled=" + event.isCancelled());
    }
    private static Arm take(Map<UUID, Arm> map, UUID id) {
        Arm arm = map.remove(id); return arm != null && System.nanoTime() < arm.expires ? arm : null;
    }
    private void tell(UUID owner, String text) { Player player = plugin.getServer().getPlayer(owner); if (player != null) player.sendMessage(text); }
    private LivingEntity entity(Player player, String id) {
        UUID uuid = UUID.fromString(id); Observation record = entities.get(uuid);
        if (record == null || !record.owner.equals(player.getUniqueId())) throw new IllegalArgumentException("Entity is not owned by this QA operator");
        Entity entity = plugin.getServer().getEntity(uuid);
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead() || !living.getScoreboardTags().contains(TAG))
            throw new IllegalArgumentException("Fixture entity is no longer alive");
        return living;
    }
    private static String npcId(String value) {
        if (!value.matches("[A-Za-z0-9_./:-]{1,100}")) throw new IllegalArgumentException("Invalid NPC command identifier"); return value;
    }
    private void targetCommand(String npc, String target) {
        JavaPlugin animus = (JavaPlugin) plugin.getServer().getPluginManager().getPlugin("Animus");
        // Paper can enqueue nested dispatchCommand calls until the current Brigadier command returns.
        // The fixture needs an immediate registered executor call to inspect before any vanilla tick runs.
        Objects.requireNonNull(Objects.requireNonNull(animus).getCommand("animus"))
                .execute(plugin.getServer().getConsoleSender(), "animus", new String[]{"target", npc, target});
    }
    private static Location location(Player player, String[] args, int start) {
        double x = Double.parseDouble(args[start]), y = Double.parseDouble(args[start + 1]), z = Double.parseDouble(args[start + 2]);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < player.getWorld().getMinHeight() || y >= player.getWorld().getMaxHeight()) throw new IllegalArgumentException("Invalid fixture location");
        Location location = new Location(player.getWorld(), x, y, z);
        if (!player.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) throw new IllegalArgumentException("Fixture chunk must already be loaded");
        return location;
    }
    private record MobSnapshot(Map<Object, Integer> movement, Map<Object, Integer> targeting, UUID target, List<Object> path,
                               Object targetPos, int reach, boolean delayed, long lastRecompute) {}
    private static MobSnapshot capture(Mob mob) throws ReflectiveOperationException {
        Object nativeMob = handle(mob), nav = invoke(nativeMob, "getNavigation");
        Object path = invoke(nav, "getPath"); List<Object> nodes = new ArrayList<>();
        if (path != null) {
            int count = (int) invoke(path, "getNodeCount"); require(count <= 512, "Fixture path bound exceeded");
            Method pos = path.getClass().getMethod("getNodePos", int.class);
            for (int i = 0; i < count; i++) nodes.add(pos.invoke(path, i));
        }
        return new MobSnapshot(goals(field(nativeMob, "goalSelector")), goals(field(nativeMob, "targetSelector")),
                mob.getTarget() == null ? null : mob.getTarget().getUniqueId(), List.copyOf(nodes), field(nav, "targetPos"),
                (int) field(nav, "reachRange"), (boolean) field(nav, "hasDelayedRecomputation"), (long) field(nav, "timeLastRecompute"));
    }
    private static Map<Object, Integer> goals(Object selector) throws ReflectiveOperationException {
        Map<Object, Integer> result = new IdentityHashMap<>();
        for (Object goal : (Collection<?>) invoke(selector, "getAvailableGoals")) result.put(invoke(goal, "getGoal"), (int) invoke(goal, "getPriority"));
        return result;
    }
    private static Object handle(Mob mob) throws ReflectiveOperationException { return invoke(mob, "getHandle"); }
    private static String exactMinecraftVersion() throws ReflectiveOperationException {
        Object version = Class.forName("net.minecraft.SharedConstants").getMethod("getCurrentVersion").invoke(null);
        return (String) Class.forName("net.minecraft.WorldVersion").getMethod("name").invoke(version);
    }
    private static Object invoke(Object value, String name) throws ReflectiveOperationException { return value.getClass().getMethod(name).invoke(value); }
    private static Object field(Object value, String name) throws ReflectiveOperationException {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void cleanup(UUID owner) {
        for (var entry : List.copyOf(entities.entrySet())) if (entry.getValue().owner.equals(owner)) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity != null && entity.getScoreboardTags().contains(TAG)) entity.remove();
            entities.remove(entry.getKey()); outgoing.remove(entry.getKey()); real.remove(entry.getKey()); incoming.remove(entry.getKey());
            sourceMarkers.remove(entry.getKey()); snapshots.remove(entry.getKey());
        }
    }
    @Override public void close() { for (UUID owner : entities.values().stream().map(value -> value.owner).distinct().toList()) cleanup(owner); }
}
