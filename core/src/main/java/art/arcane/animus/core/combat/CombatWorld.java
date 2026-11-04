package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Everything the combat engine asks of a platform. Called only on the platform's owner thread. Never loads terrain. */
public interface CombatWorld {
    enum Visibility { CLEAR, BLOCKED, UNAVAILABLE }
    enum MoveStatus { ACCEPTED, BUSY, REJECTED }
    enum DamageKind { MELEE, PROJECTILE }
    enum DamageStatus { APPLIED, NO_CHANGE, CANCELLED, PROTECTED, INVALID, FAILED }

    /** @param applied health and absorption actually removed; killed whether the target died from this hit */
    record DamageOutcome(DamageStatus status, double applied, boolean killed) {}
    record Drop(String item, int amount) {}

    /** The authoritative NPC state, including a dead NPC, or empty when it has no runtime. */
    Optional<VirtualNpcState.Snapshot> npc(UUID npc);
    /** The NPC's display name for name rules. */
    default String npcName(UUID npc) { return ""; }
    /** A current real entity observation; empty when it is gone, dead, unloaded, or not attackable. */
    Optional<CombatTarget> real(TargetRef ref);
    /** At most limit attackable real entities within the radius, from loaded chunks only. */
    List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit);
    SpatialNpcIndex.Scan nearbyNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget);
    Visibility lineOfSight(String world, Vec3 from, Vec3 to);

    /** Starts or replaces combat movement. Holds any waypoint patrol until release. */
    MoveStatus move(UUID npc, WorldPosition goal, double speed);
    /** Ends combat movement and leaves the NPC standing. */
    void stop(UUID npc);
    /** Ends combat movement and resumes any held waypoint patrol. */
    void release(UUID npc);
    boolean teleport(UUID npc, WorldPosition destination);
    void face(UUID npc, Vec3 point);
    /** Horizontal facing in degrees; yaw 0 faces positive Z. */
    double yaw(UUID npc);
    void knockback(UUID npc, Vec3 direction, double strength);
    void heal(UUID npc, double amount);
    boolean revive(UUID npc, WorldPosition at);

    /** Damages a real entity on behalf of an NPC, with no fabricated attacker entity. */
    DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops);
    /** Applies authoritative virtual damage and publishes it to viewers and behavior triggers. */
    DamageOutcome damageVirtual(UUID npc, TargetRef attacker, double amount);

    /** Launches a real helper entity without a real attacker; empty when the platform refused it. */
    Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity);
    /** The helper's position, or empty when the real entity no longer exists. */
    Optional<Vec3> helperPosition(UUID helper);
    void removeHelper(UUID helper);

    /** Empty when the location's world is not loaded. */
    Optional<WorldPosition> resolve(CombatSettings.Location location);
    void drop(WorldPosition at, List<Drop> items, int experience);
}
