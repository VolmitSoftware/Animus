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
    /** What the NPC's body touches: lava over water over fire, or nothing that hurts or quenches it. */
    enum Contact { NONE, FIRE, WATER, LAVA }
    /** {@code SCHEDULED}: handed to the target's owner, which applies it later and reports the fight itself. */
    enum DamageStatus { APPLIED, NO_CHANGE, CANCELLED, PROTECTED, INVALID, FAILED, SCHEDULED }

    /** @param applied health and absorption actually removed; killed whether the target died from this hit */
    record DamageOutcome(DamageStatus status, double applied, boolean killed) {}
    /** {@code components} is the text after the item id in a give command. */
    record Drop(String item, int amount, Optional<String> components) {
        public Drop(String item, int amount) { this(item, amount, Optional.empty()); }
    }

    /** The authoritative NPC state, including a dead NPC, or empty when it has no runtime. */
    Optional<VirtualNpcState.Snapshot> npc(UUID npc);
    /** The NPC's display name for name rules. */
    default String npcName(UUID npc) { return ""; }
    /** A current real entity observation; empty when it is gone, dead, unloaded, or not attackable. */
    Optional<CombatTarget> real(TargetRef ref);
    /** At most limit attackable real entities within the radius, from loaded chunks only. */
    List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit);
    /** Whether an online player stands within the radius. Guards with no player near sleep; the default keeps them awake. */
    default boolean playersNear(WorldPosition center, double radius) { return true; }
    SpatialNpcIndex.Scan nearbyNpcs(WorldPosition center, double radius, SpatialNpcIndex.Cursor cursor, SpatialNpcIndex.Budget budget);
    Visibility lineOfSight(String world, Vec3 from, Vec3 to);

    /** Starts or replaces combat movement. Holds any waypoint patrol until release. */
    MoveStatus move(UUID npc, WorldPosition goal, double speed);
    /** What the NPC's movement is doing now, or why the last move was refused. For diagnostics only. */
    default String movement(UUID npc) { return "unknown"; }
    /** Ends combat movement and leaves the NPC standing. */
    void stop(UUID npc);
    /** Ends combat movement and resumes any held waypoint patrol. */
    void release(UUID npc);
    boolean teleport(UUID npc, WorldPosition destination);
    /**
     * Where the NPC stands guard: its definition's position, which a fight never moves. Empty for an NPC that patrols a
     * route, whose fights end where it left the route, and for a platform that does not know.
     */
    default Optional<WorldPosition> post(UUID npc) { return Optional.empty(); }
    /** The definition's own position, dead or alive, patrolling or not; where a health NPC with no respawn points comes back. */
    default Optional<WorldPosition> home(UUID npc) { return post(npc); }
    /** A short straight step onto clear, supported ground at the point, checked along the way; false leaves the NPC in place. */
    default boolean step(UUID npc, WorldPosition to) { return false; }
    /** Turns the NPC back to its definition's facing and pose, standing. */
    default void restorePose(UUID npc) {}
    void face(UUID npc, Vec3 point);
    /** Shows one attack: the main-arm swing, or the type's own attack animation. Sent promptly, never dropped as cosmetic. */
    default void swing(UUID npc) {}
    /** Shows the NPC drawing its bow, crossbow or trident while true, as a player holding use does. */
    default void drawing(UUID npc, boolean drawing) {}
    /** Horizontal facing in degrees; yaw 0 faces positive Z. */
    double yaw(UUID npc);
    void knockback(UUID npc, Vec3 direction, double strength);
    void heal(UUID npc, double amount);
    boolean revive(UUID npc, WorldPosition at);

    /** Damages a real entity on behalf of an NPC, with no fabricated attacker entity. */
    DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops);
    /** Applies authoritative virtual damage and publishes it to viewers and behavior triggers. {@code attacker} is null for the environment. */
    DamageOutcome damageVirtual(UUID npc, TargetRef attacker, double amount);
    /** What the NPC's body touches now, from loaded blocks this thread owns; {@link Contact#NONE} when unknown. */
    default Contact contact(UUID npc) { return Contact.NONE; }
    /** Shows or hides the NPC burning for everyone who sees it. */
    default void burning(UUID npc, boolean burning) {}

    /**
     * Applies a potion's status effect to a real entity on behalf of an NPC. The platform's own potion damage and
     * splash spread never apply; this is the only effect the potion has.
     */
    boolean applyEffect(UUID attacker, CombatTarget target, CombatSettings.Effect effect);
    /** Whether an online player has this permission node. False for anything else. */
    boolean permitted(TargetRef player, String node);
    /** Shows a line above one online player's hotbar. */
    default void actionBar(UUID player, String text) {}
    /** Credits a kill of this NPC to the player who dealt the last blow, as a mob kill counts. Other killers are ignored. */
    default void credit(TargetRef killer, UUID npc) {}
    /** Sends a line of NPC speech to one online player. */
    void tell(UUID player, String speaker, String message);

    /**
     * Launches a real helper projectile without a real attacker; empty when the platform refused it. Its vanilla
     * damage, effects and explosion must never apply.
     */
    Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity, RangedWeapon weapon);
    /** The helper's position, or empty when the real entity no longer exists. */
    Optional<Vec3> helperPosition(UUID helper);
    void removeHelper(UUID helper);

    /** A combat NPC that this engine does not hold, as its owner last published it; empty when there is none. */
    default Optional<CombatTarget> peer(UUID npc) { return Optional.empty(); }
    /** Delivers a hit on a combat NPC this engine does not hold to that NPC's owner, which applies its own defense. */
    default DamageOutcome strikePeer(TargetRef attacker, WorldPosition from, CombatTarget target, double amount, double knockback, DamageKind kind) {
        return new DamageOutcome(DamageStatus.NO_CHANGE, 0, false);
    }
    /** Offers the target to idle squad members this engine does not hold, at most {@code limit}; returns how many were asked. */
    default int alertPeers(String squad, UUID source, WorldPosition from, double range, TargetRef target, int limit) { return 0; }
    /** A fight this engine saw, for guards and witnesses that other engines hold. */
    default void foughtElsewhere(TargetRef attacker, TargetRef victim) {}

    /** Empty when the location's world is not loaded. */
    Optional<WorldPosition> resolve(CombatSettings.Location location);
    void drop(WorldPosition at, List<Drop> items, int experience);
}
