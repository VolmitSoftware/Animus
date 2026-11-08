package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic world for driving the combat engine without a server. Moves are applied by the test. */
class MockCombatWorld implements CombatWorld {
    static final String WORLD = "world";
    final Map<UUID, VirtualNpcState> npcs = new HashMap<>();
    final SpatialNpcIndex index = new SpatialNpcIndex(256);
    final Map<UUID, CombatTarget> real = new HashMap<>();
    final Map<UUID, Double> realDamage = new HashMap<>();
    final Map<UUID, Vec3> helpers = new HashMap<>();
    final Set<UUID> removedHelpers = new HashSet<>();
    final List<WorldPosition> moves = new ArrayList<>();
    final List<Double> speeds = new ArrayList<>();
    final List<Drop> drops = new ArrayList<>();
    final List<UUID> revived = new ArrayList<>();
    final Set<UUID> released = new HashSet<>();
    final Map<UUID, Double> knocked = new HashMap<>();
    final Map<UUID, List<String>> told = new HashMap<>();
    final Map<UUID, List<CombatSettings.Effect>> effects = new HashMap<>();
    final Map<UUID, RangedWeapon> launched = new HashMap<>();
    final Set<String> permissions = new HashSet<>();
    final Map<UUID, Integer> swings = new HashMap<>();
    final Map<UUID, Boolean> drawn = new HashMap<>();
    /** What each NPC's body touches; missing means nothing. */
    final Map<UUID, Contact> contacts = new HashMap<>();
    final Map<UUID, Boolean> burning = new HashMap<>();
    int drawChanges;
    int experience, realScans, npcScans, helperLaunches, stops, teleports, lineChecks;
    boolean blocked, unavailableTerrain, walkInstantly, refuseHelpers, playersAway;
    int playerChecks;
    /** Where each NPC with a post stands guard; an NPC missing here has none, as a patrolling NPC. */
    final Map<UUID, WorldPosition> posts = new HashMap<>();
    /** Each NPC's definition position, where a health NPC with no respawn points comes back. */
    final Map<UUID, WorldPosition> homes = new HashMap<>();
    final Map<UUID, List<String>> actionBars = new HashMap<>();
    final List<TargetRef> credits = new ArrayList<>();
    final Map<UUID, Integer> poseRestores = new HashMap<>();
    /** An instant walk ends this far short of its goal across, as a path that stops beside a goal it cannot reach. */
    double stopShort;
    boolean refuseSteps;
    /** A teleport is accepted and applied later, as a Folia teleport into another region is; {@link #flush} applies it. */
    boolean deferTeleports;
    final Map<UUID, WorldPosition> deferred = new HashMap<>();
    int steps;
    MoveStatus moveStatus = MoveStatus.ACCEPTED;
    DamageStatus realStatus = DamageStatus.APPLIED;
    double yaw;

    static WorldPosition at(double x, double y, double z) { return new WorldPosition(WORLD, new Vec3(x, y, z)); }

    UUID npc(double x, double z, double health) {
        UUID id = UUID.randomUUID();
        VirtualNpcState state = new VirtualNpcState(id, at(x, 64, z), .6, 1.8, health);
        state.spawn();
        npcs.put(id, state);
        index.upsert(state.snapshot());
        return id;
    }
    UUID player(String name, double x, double z) { return real(TargetKind.PLAYER, "minecraft:player", name, x, z, Set.of(TargetCategory.PLAYERS)); }
    UUID monster(String type, double x, double z) { return real(TargetKind.MOB, type, type, x, z, Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS)); }
    UUID animal(String type, double x, double z) { return real(TargetKind.MOB, type, type, x, z, Set.of(TargetCategory.MOBS, TargetCategory.ANIMALS)); }
    UUID real(TargetKind kind, String type, String name, double x, double z, Set<TargetCategory> categories) {
        UUID id = UUID.randomUUID();
        real.put(id, target(new TargetRef(kind, id), type, name, x, z, categories));
        return id;
    }
    void moveReal(UUID id, double x, double z) {
        CombatTarget old = real.get(id);
        real.put(id, target(old.ref(), old.type(), old.name(), x, z, old.categories()));
    }
    void moveNpc(UUID id, double x, double z) {
        VirtualNpcState state = npcs.get(id);
        index.upsert(state.move(at(x, 64, z)));
    }
    /** Replaces what item, effect and health rules see on a real entity. */
    void details(UUID id, double health, CombatTarget.Details details) {
        CombatTarget old = real.get(id);
        real.put(id, new CombatTarget(old.ref(), old.type(), old.name(), old.position(), old.bounds(), health, old.categories(), "", Optional.empty(), details));
    }
    private CombatTarget target(TargetRef ref, String type, String name, double x, double z, Set<TargetCategory> categories) {
        CombatTarget.Details details = real.containsKey(ref.id()) ? real.get(ref.id()).details() : new CombatTarget.Details(20, CombatLoadout.EMPTY, CombatLoadout.EMPTY, List.of(), Set.of());
        double health = real.containsKey(ref.id()) ? real.get(ref.id()).health() : 20;
        return new CombatTarget(ref, type, name, at(x, 64, z), Hitbox.atFeet(new Vec3(x, 64, z), .6, 1.8), health, categories, "", Optional.empty(), details);
    }

    @Override public Optional<VirtualNpcState.Snapshot> npc(UUID npc) {
        VirtualNpcState state = npcs.get(npc);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }
    @Override public Optional<CombatTarget> real(TargetRef ref) { return Optional.ofNullable(real.get(ref.id())); }
    @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) {
        realScans++;
        List<CombatTarget> found = new ArrayList<>();
        for (CombatTarget target : real.values()) {
            if (found.size() < limit && target.position().world().equals(center.world())
                    && target.position().point().distance(center.point()) <= radius) found.add(target);
        }
        return found;
    }
    @Override public boolean playersNear(WorldPosition center, double radius) { playerChecks++; return !playersAway; }
    @Override public SpatialNpcIndex.Scan nearbyNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget) {
        npcScans++;
        return index.scan(new SpatialNpcIndex.Query(center, radius), cursor, budget, chunk -> true);
    }
    @Override public Visibility lineOfSight(String world, Vec3 from, Vec3 to) {
        lineChecks++;
        if (unavailableTerrain) return Visibility.UNAVAILABLE;
        return blocked ? Visibility.BLOCKED : Visibility.CLEAR;
    }
    @Override public MoveStatus move(UUID npc, WorldPosition goal, double speed) {
        if (moveStatus == MoveStatus.ACCEPTED) {
            moves.add(goal);
            speeds.add(speed);
            if (walkInstantly) index.upsert(npcs.get(npc).move(shortOf(npcs.get(npc).snapshot().position(), goal)));
        }
        return moveStatus;
    }
    private WorldPosition shortOf(WorldPosition from, WorldPosition goal) {
        Vec3 delta = goal.point().subtract(from.point());
        double across = Math.hypot(delta.x(), delta.z());
        if (stopShort <= 0) return goal;
        if (across <= stopShort) return from;
        double kept = (across - stopShort) / across;
        return new WorldPosition(goal.world(), new Vec3(from.point().x() + delta.x() * kept, goal.point().y(), from.point().z() + delta.z() * kept));
    }
    @Override public Optional<WorldPosition> post(UUID npc) { return Optional.ofNullable(posts.get(npc)); }
    @Override public Optional<WorldPosition> home(UUID npc) { return Optional.ofNullable(homes.get(npc)); }
    @Override public void actionBar(UUID player, String text) { actionBars.computeIfAbsent(player, (UUID key) -> new ArrayList<>()).add(text); }
    @Override public void credit(TargetRef killer, UUID npc) { credits.add(killer); }
    @Override public boolean step(UUID npc, WorldPosition to) {
        if (refuseSteps) return false;
        steps++;
        index.upsert(npcs.get(npc).move(to));
        return true;
    }
    @Override public void restorePose(UUID npc) { poseRestores.merge(npc, 1, Integer::sum); }
    @Override public void stop(UUID npc) { stops++; }
    @Override public void release(UUID npc) { released.add(npc); }
    void flush() {
        deferred.forEach((UUID npc, WorldPosition to) -> index.upsert(npcs.get(npc).move(to)));
        deferred.clear();
    }
    @Override public boolean teleport(UUID npc, WorldPosition destination) {
        teleports++;
        if (deferTeleports) { deferred.put(npc, destination); return true; }
        index.upsert(npcs.get(npc).move(destination));
        return true;
    }
    @Override public void face(UUID npc, Vec3 point) {}
    @Override public void swing(UUID npc) { swings.merge(npc, 1, Integer::sum); }
    @Override public void drawing(UUID npc, boolean drawing) { drawn.put(npc, drawing); drawChanges++; }
    @Override public double yaw(UUID npc) { return yaw; }
    @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
        if (realStatus != DamageStatus.APPLIED) return new DamageOutcome(realStatus, 0, false);
        realDamage.merge(target.ref().id(), amount, Double::sum);
        return new DamageOutcome(DamageStatus.APPLIED, amount, false);
    }
    @Override public DamageOutcome damageVirtual(UUID npc, TargetRef attacker, double amount) {
        VirtualNpcState.Snapshot after = npcs.get(npc).damage(amount);
        if (after.active()) index.upsert(after);
        else index.remove(npc);
        return new DamageOutcome(DamageStatus.APPLIED, amount, !after.active());
    }
    @Override public Contact contact(UUID npc) { return contacts.getOrDefault(npc, Contact.NONE); }
    @Override public void burning(UUID npc, boolean shown) { burning.put(npc, shown); }
    @Override public void knockback(UUID npc, Vec3 direction, double strength) { knocked.merge(npc, strength, Double::sum); }
    @Override public boolean applyEffect(UUID attacker, CombatTarget target, CombatSettings.Effect effect) {
        effects.computeIfAbsent(target.ref().id(), (UUID key) -> new ArrayList<>()).add(effect);
        return true;
    }
    @Override public boolean permitted(TargetRef player, String node) { return player.kind() == TargetKind.PLAYER && permissions.contains(player.id() + " " + node); }
    @Override public void tell(UUID player, String speaker, String message) { told.computeIfAbsent(player, (UUID key) -> new ArrayList<>()).add("[" + speaker + "]: " + message); }
    @Override public Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity, RangedWeapon weapon) {
        if (refuseHelpers) return Optional.empty();
        UUID helper = UUID.randomUUID();
        helpers.put(helper, from.point());
        launched.put(helper, weapon);
        helperLaunches++;
        return Optional.of(helper);
    }
    @Override public Optional<Vec3> helperPosition(UUID helper) { return Optional.ofNullable(helpers.get(helper)); }
    @Override public void removeHelper(UUID helper) { helpers.remove(helper); removedHelpers.add(helper); }
    @Override public boolean revive(UUID npc, WorldPosition at) {
        revived.add(npc);
        VirtualNpcState.Snapshot after = npcs.get(npc).respawn(at);
        index.upsert(after);
        return true;
    }
    @Override public void heal(UUID npc, double amount) { index.upsert(npcs.get(npc).heal(amount)); }
    @Override public Optional<WorldPosition> resolve(CombatSettings.Location location) {
        return Optional.of(new WorldPosition(location.world(), new Vec3(location.x(), location.y(), location.z())));
    }
    @Override public void drop(WorldPosition at, List<Drop> items, int xp) { drops.addAll(items); experience += xp; }
}
