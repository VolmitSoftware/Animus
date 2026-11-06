package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.virtual.Vec3;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

/**
 * Exact-version access to an EXISTING real hostile mob whose AI runs on goal selectors. No entity constructor or fake
 * attack target is used. Brain-driven mobs such as piglins and ranged mobs such as skeletons are not controlled.
 */
public final class NativeMobController {
    /** Whether the calling thread may read the chunk: loaded on Bukkit and Paper; also owned by this region on Folia. */
    @FunctionalInterface public interface ChunkAccess { boolean readable(World world, int chunkX, int chunkZ); }
    /** Melee hostile mobs whose AI is held entirely by their goal selectors, so suspending and restoring them is exact. */
    public static final Set<EntityType> TYPES = Set.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER, EntityType.VINDICATOR);
    private static final Map<UUID, Object> CLAIMS = new ConcurrentHashMap<>();
    private final Method handle, navigation, goals, unwrap, priority, addGoal, removeGoal, getPath, copyPath, nodeCount, nodePos;
    private final Method stop, createPath, movePath, getX, getZ, setMaxNodes;
    private final Constructor<?> blockPos;
    private final ChunkAccess chunks;
    private final Field goalSelector, targetSelector, speed, pathFinder, maxNodes, multiplier, targetPos, delayed, reachRange, lastRecompute;

    public NativeMobController(Plugin plugin, String version, ChunkAccess chunks) throws ReflectiveOperationException {
        this.chunks = Objects.requireNonNull(chunks);
        if (!Set.of("26.1.2", "26.2", "26.3").contains(version)) throw new IllegalArgumentException("Unsupported mob adapter version: " + version);
        Class<?> mob = type("net.minecraft.world.entity.Mob");
        Class<?> nav = type("net.minecraft.world.entity.ai.navigation.PathNavigation");
        Class<?> selector = type("net.minecraft.world.entity.ai.goal.GoalSelector");
        Class<?> goal = type("net.minecraft.world.entity.ai.goal.Goal");
        Class<?> wrapped = type("net.minecraft.world.entity.ai.goal.WrappedGoal");
        Class<?> path = type("net.minecraft.world.level.pathfinder.Path");
        Class<?> pos = type("net.minecraft.core.BlockPos");
        Class<?> finder = type("net.minecraft.world.level.pathfinder.PathFinder");
        handle = type(plugin.getServer().getClass().getPackageName() + ".entity.CraftMob").getMethod("getHandle");
        navigation = mob.getMethod("getNavigation");
        goalSelector = field(mob, "goalSelector"); targetSelector = field(mob, "targetSelector");
        goals = selector.getMethod("getAvailableGoals"); unwrap = wrapped.getMethod("getGoal"); priority = wrapped.getMethod("getPriority");
        addGoal = selector.getMethod("addGoal", int.class, goal); removeGoal = selector.getMethod("removeGoal", goal);
        getPath = nav.getMethod("getPath"); copyPath = path.getMethod("copy"); nodeCount = path.getMethod("getNodeCount"); nodePos = path.getMethod("getNodePos", int.class);
        stop = nav.getMethod("stop"); createPath = nav.getMethod("createPath", pos, int.class, int.class); movePath = nav.getMethod("moveTo", path, double.class);
        blockPos = pos.getConstructor(int.class, int.class, int.class); getX = pos.getMethod("getX"); getZ = pos.getMethod("getZ");
        speed = field(nav, "speedModifier"); pathFinder = field(nav, "pathFinder"); multiplier = field(nav, "maxVisitedNodesMultiplier");
        maxNodes = field(finder, "maxVisitedNodes"); setMaxNodes = finder.getMethod("setMaxVisitedNodes", int.class);
        targetPos = field(nav, "targetPos"); delayed = field(nav, "hasDelayedRecomputation");
        reachRange = field(nav, "reachRange"); lastRecompute = field(nav, "timeLastRecompute");
    }

    /** On the mob's owner. */
    public Lease acquire(Mob mob) throws ReflectiveOperationException {
        if (!TYPES.contains(mob.getType()) || !mob.isValid() || mob.isDead() || !mob.hasAI() || !mob.isAware())
            throw new IllegalArgumentException("Control requires a live, aware, AI-enabled zombie, husk, drowned, zombie villager or vindicator");
        if (CLAIMS.size() >= 128) throw new IllegalStateException("Global mob ownership limit reached");
        Object token = new Object();
        if (CLAIMS.putIfAbsent(mob.getUniqueId(), token) != null) throw new IllegalStateException("Mob is already controlled by Animus");
        Lease lease = null;
        try {
            lease = new Lease(mob, token);
            lease.take();
            return lease;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (lease != null) try { lease.close(); } catch (ReflectiveOperationException restore) { failure.addSuppressed(restore); }
            CLAIMS.remove(mob.getUniqueId(), token);
            throw failure;
        }
    }

    private record GoalEntry(Object goal, int priority) {}
    private List<GoalEntry> snapshotGoals(Object selector) throws ReflectiveOperationException {
        Collection<?> available = (Collection<?>) goals.invoke(selector);
        if (available.size() > 64) throw new IllegalStateException("Mob goal set exceeds the control snapshot limit");
        List<GoalEntry> result = new ArrayList<>();
        for (Object wrapped : available) result.add(new GoalEntry(unwrap.invoke(wrapped), (int) priority.invoke(wrapped)));
        return List.copyOf(result);
    }
    private void remove(Object selector, List<GoalEntry> entries) throws ReflectiveOperationException {
        for (GoalEntry entry : entries) removeGoal.invoke(selector, entry.goal);
    }
    private void restore(Object selector, List<GoalEntry> entries) throws ReflectiveOperationException {
        Set<Object> existing = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object wrapped : (Collection<?>) goals.invoke(selector)) existing.add(unwrap.invoke(wrapped));
        for (GoalEntry entry : entries) if (!existing.contains(entry.goal)) addGoal.invoke(selector, entry.priority, entry.goal);
    }

    public final class Lease implements AutoCloseable {
        private final Mob mob;
        private final Object token, nativeMob, nav, movementGoals, targetingGoals;
        private final List<GoalEntry> originalMovement, originalTargeting;
        private final LivingEntity originalTarget;
        private final UUID originalWorld;
        private final Object originalPath;
        private final double originalSpeed;
        private final Object originalTargetPos;
        private final boolean originalDelayed;
        private final int originalReachRange;
        private final long originalLastRecompute;
        private Object ownedPath;
        private boolean taken, acquired, closed, restorePath = true;

        private Lease(Mob mob, Object token) throws ReflectiveOperationException {
            this.mob = mob; this.token = token; originalWorld = mob.getWorld().getUID(); originalTarget = mob.getTarget();
            nativeMob = handle.invoke(mob); nav = navigation.invoke(nativeMob);
            movementGoals = goalSelector.get(nativeMob); targetingGoals = targetSelector.get(nativeMob);
            originalMovement = snapshotGoals(movementGoals); originalTargeting = snapshotGoals(targetingGoals);
            Object path = getPath.invoke(nav);
            if (path != null && (int) nodeCount.invoke(path) > 512) throw new IllegalStateException("Original path exceeds the restoration snapshot limit");
            originalPath = path == null ? null : copyPath.invoke(path); originalSpeed = speed.getDouble(nav);
            originalTargetPos = targetPos.get(nav); originalDelayed = delayed.getBoolean(nav);
            originalReachRange = reachRange.getInt(nav); originalLastRecompute = lastRecompute.getLong(nav);
        }
        private void take() throws ReflectiveOperationException {
            taken = true;
            remove(movementGoals, originalMovement); remove(targetingGoals, originalTargeting);
            stop.invoke(nav); ownedPath = null; suppressRecomputation(); mob.setTarget(null);
            if (!owned()) throw new IllegalStateException("Mob control was denied or another controller changed its goals/target");
            acquired = true;
        }
        public boolean owned() throws ReflectiveOperationException {
            Object currentPath = getPath.invoke(nav);
            return !closed && CLAIMS.get(mob.getUniqueId()) == token && navigation.invoke(nativeMob) == nav
                    && ((Collection<?>) goals.invoke(movementGoals)).isEmpty() && ((Collection<?>) goals.invoke(targetingGoals)).isEmpty()
                    && targetPos.get(nav) == null && (currentPath == null || currentPath == ownedPath)
                    && mob.getTarget() == null && mob.hasAI() && mob.isAware();
        }
        public boolean plan(Vec3 destination, int pathLength, int visitedNodeLimit, double speedModifier) throws ReflectiveOperationException {
            if (!owned()) throw new CancellationException("Mob ownership changed");
            Object finder = pathFinder.get(nav);
            int previousNodes = maxNodes.getInt(finder); float previousMultiplier = multiplier.getFloat(nav);
            Object path;
            try {
                setMaxNodes.invoke(finder, visitedNodeLimit); multiplier.setFloat(nav, 1.0f);
                stop.invoke(nav); ownedPath = null;
                Object goal = blockPos.newInstance((int) Math.floor(destination.x()), (int) Math.floor(destination.y()), (int) Math.floor(destination.z()));
                path = createPath.invoke(nav, goal, 0, pathLength);
            } finally {
                setMaxNodes.invoke(finder, previousNodes); multiplier.setFloat(nav, previousMultiplier);
                // Vanilla recomputePath uses targetPos even without goals. Keep every new route inside the bridge budget.
                if (!closed && CLAIMS.get(mob.getUniqueId()) == token && getPath.invoke(nav) == null
                        && mob.getTarget() == null && ((Collection<?>) goals.invoke(movementGoals)).isEmpty()
                        && ((Collection<?>) goals.invoke(targetingGoals)).isEmpty()) suppressRecomputation();
            }
            if (!owned()) throw new CancellationException("Mob ownership changed during pathfinding");
            boolean result = path != null && (boolean) movePath.invoke(nav, path, speedModifier);
            ownedPath = getPath.invoke(nav);
            return result;
        }
        public void halt() throws ReflectiveOperationException { if (owned()) { stop.invoke(nav); ownedPath = null; } }
        boolean usesChunk(int x, int z) throws ReflectiveOperationException {
            if (ownedPath == null) return false;
            for (int i = 0, count = (int) nodeCount.invoke(ownedPath); i < count; i++) {
                Object point = nodePos.invoke(ownedPath, i);
                if (((int) getX.invoke(point) >> 4) == x && ((int) getZ.invoke(point) >> 4) == z) return true;
            }
            return false;
        }
        private void suppressRecomputation() throws ReflectiveOperationException { targetPos.set(nav, null); delayed.setBoolean(nav, false); }
        void closeForUnavailableTerrain() throws ReflectiveOperationException { restorePath = false; close(); }
        /**
         * The mob is gone (removed, unloaded or retired by its scheduler): only the claim is released, without touching the
         * mob. A mob that loads again is built with its own goals.
         */
        public void forget() { closed = true; CLAIMS.remove(mob.getUniqueId(), token); }
        @Override public void close() throws ReflectiveOperationException {
            if (closed) return;
            List<Throwable> failures = new ArrayList<>();
            boolean retainedOwnership = false;
            try {
                retainedOwnership = owned() || (!acquired && navigation.invoke(nativeMob) == nav
                        && getPath.invoke(nav) == null && (mob.getTarget() == null || mob.getTarget() == originalTarget));
            } catch (ReflectiveOperationException | RuntimeException error) { failures.add(error); }
            closed = true;
            try {
                if (!taken) return;
                // Restore each owned piece independently so one failing callback cannot strand suspended goals.
                attempt(failures, () -> {
                    if (navigation.invoke(nativeMob) == nav && getPath.invoke(nav) == ownedPath) stop.invoke(nav);
                });
                attempt(failures, () -> restore(movementGoals, originalMovement));
                attempt(failures, () -> restore(targetingGoals, originalTargeting));
                boolean restoreNavigation = retainedOwnership;
                attempt(failures, () -> {
                    if (!restoreNavigation || !mob.isValid() || mob.isDead() || !mob.getWorld().getUID().equals(originalWorld)) return;
                    if (mob.getTarget() == null && originalTarget != null && originalTarget.isValid() && !originalTarget.isDead()
                            && originalTarget.getWorld().equals(mob.getWorld())) mob.setTarget(originalTarget);
                });
                attempt(failures, () -> {
                    if (restoreNavigation && navigation.invoke(nativeMob) == nav && mob.isValid() && !mob.isDead()
                            && mob.getWorld().getUID().equals(originalWorld) && mob.hasAI() && mob.isAware()
                            && getPath.invoke(nav) == null && targetPos.get(nav) == null
                            && (mob.getTarget() == null || mob.getTarget() == originalTarget)) {
                        if (restorePath && (originalPath == null || originalPathLoaded())) {
                            targetPos.set(nav, originalTargetPos); delayed.setBoolean(nav, originalDelayed);
                            reachRange.setInt(nav, originalReachRange); lastRecompute.setLong(nav, originalLastRecompute);
                            if (originalPath != null) movePath.invoke(nav, originalPath, originalSpeed);
                        } else suppressRecomputation();
                    }
                });
            } finally { CLAIMS.remove(mob.getUniqueId(), token); }
            if (!failures.isEmpty()) {
                ReflectiveOperationException error = new ReflectiveOperationException("Mob restoration had " + failures.size() + " failure(s)", failures.getFirst());
                failures.stream().skip(1).forEach(error::addSuppressed);
                throw error;
            }
        }
        private boolean originalPathLoaded() throws ReflectiveOperationException {
            for (int i = 0, count = (int) nodeCount.invoke(originalPath); i < count; i++) {
                Object pos = nodePos.invoke(originalPath, i);
                if (!chunks.readable(mob.getWorld(), ((int) getX.invoke(pos)) >> 4, ((int) getZ.invoke(pos)) >> 4)) return false;
            }
            return true;
        }
    }
    @FunctionalInterface private interface Restoration { void run() throws ReflectiveOperationException; }
    private static void attempt(List<Throwable> failures, Restoration action) {
        try { action.run(); } catch (ReflectiveOperationException | RuntimeException error) { failures.add(error); }
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException("Cannot access " + owner.getName() + "." + name);
        return field;
    }
}
