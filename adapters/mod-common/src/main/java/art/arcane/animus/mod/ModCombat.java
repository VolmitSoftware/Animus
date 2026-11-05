package art.arcane.animus.mod;

import art.arcane.animus.core.hire.CombatGuarding;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.CombatModule;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Combat on mod servers: the shared combat module with a vanilla combat world, the manual {@code attack} command, and
 * the hooks the shared mixins report. Mod servers have no standard protection API, so no protection adapter is
 * installed and every protection decision allows. Taking control of a real mob is not available here.
 */
final class ModCombat implements AutoCloseable {
    static final String PROTECTION = "none (mod servers have no standard protection API)";
    static final String MOB_CONTROL_UNAVAILABLE = "Mob control is not available on mod servers; use combat <npc-id> target or guard";
    private static final String USAGE = CombatModule.MODULE_USAGE + " | attack <npc-id> <target-uuid> [damage]";
    private final AnimusRuntime runtime;
    private final PersistedNpcService<ServerPlayer, ItemStack> living;
    private final VanillaCombatWorld world;
    private final CombatModule module;

    ModCombat(MinecraftServer server, AnimusRuntime runtime, PersistedNpcService<ServerPlayer, ItemStack> living, VanillaTerrain terrain, Consumer<String> warnings) {
        this.runtime = Objects.requireNonNull(runtime); this.living = Objects.requireNonNull(living);
        world = living.available() ? new VanillaCombatWorld(server, living, terrain) : null;
        module = new CombatModule(runtime, living, world, runtime.moduleHooks().protection(ProtectionAdapter.NONE), world == null ? "unavailable because the NPC runtime is: " + living.status() : null,
                warnings, (UUID id) -> server.getPlayerList().getPlayer(id) != null);
    }

    String status() {
        return (module.active() ? "Combat ready" : "Combat unavailable") + module.status() + " | protection=" + PROTECTION
                + " | manual attack" + (living.available() ? "" : " unavailable") + " | mob-control=unavailable on mod servers"
                + " | pursuit-regions=unavailable (no protection plugin)";
    }

    String execute(String[] args) {
        if (args.length > 0 && args[0].equals("combat")) {
            if (args.length == 1 || args.length == 2 && args[1].equals("status")) return status();
            return module.execute(args);
        }
        if (args[0].equals("target")) throw new IllegalStateException(MOB_CONTROL_UNAVAILABLE);
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException(USAGE);
        if (world == null) throw new IllegalStateException(status());
        return attack(args[1], CombatModule.uuid(args[2]), args.length == 4 ? Double.parseDouble(args[3]) : AttackProfile.DEFAULT.damage());
    }

    String operate(UUID npc, BehaviorPlatform.CombatOperation operation, Optional<UUID> target) { return module.operate(npc, operation, target); }
    CombatGuarding hireGuarding() { return new CombatGuarding(module); }

    void tick() { module.tick(); }
    int waiting() { return module.waiting(); }
    int load() { return module.load(); }

    /** One administrator-requested hit, revalidated like any NPC attack. */
    private String attack(String id, UUID targetId, double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || amount > 100) throw new IllegalArgumentException("Damage must be within 0..100");
        NpcDefinition definition = runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        if (!CombatModule.manualAllowed(definition)) throw new IllegalStateException("NPC combat is disabled or its migration review has not been accepted");
        NpcRuntime.ActiveNpc npc = living.combatant(definition.uuid()).filter((NpcRuntime.ActiveNpc active) -> active.state().active())
                .orElseThrow(() -> new IllegalStateException("NPC must have an active runtime before combat"));
        Entity entity = world.entity(targetId);
        if (!(entity instanceof LivingEntity)) throw new IllegalArgumentException("Target must be a loaded living entity");
        Optional<CombatTarget> observed = world.observe(entity);
        if (observed.isEmpty()) return result(id, "INVALID_TARGET", 0, null, null);
        CombatTarget target = observed.get();
        WorldPosition from = npc.state().position();
        if (!from.world().equals(target.position().world())) return result(id, "DIFFERENT_WORLD", 0, target.health(), target.health());
        Hitbox bounds = npc.state().bounds();
        Vec3 eye = new Vec3(from.point().x(), bounds.min().y() + (bounds.max().y() - bounds.min().y()) * .85, from.point().z());
        double reach = AttackProfile.DEFAULT.reach();
        if (distance(eye, target.bounds()) > reach) return result(id, "OUT_OF_REACH", 0, target.health(), target.health());
        CombatWorld.Visibility sight = world.lineOfSight(from.world(), eye, target.bounds().min().add(target.bounds().max()).multiply(.5));
        if (sight == CombatWorld.Visibility.UNAVAILABLE) return result(id, "UNAVAILABLE_CHUNK", 0, target.health(), target.health());
        if (sight == CombatWorld.Visibility.BLOCKED) return result(id, "OBSTRUCTED", 0, target.health(), target.health());
        ProtectionAdapter.Request request = new ProtectionAdapter.Request(ProtectionAdapter.Action.NPC_ATTACK, new TargetRef(TargetKind.NPC, definition.uuid()),
                from, target.ref(), target.position(), target.categories());
        if (!module.protection().allows(request)) return result(id, "PROTECTED", 0, target.health(), target.health());
        CombatWorld.DamageOutcome outcome = world.damageReal(definition.uuid(), target, amount, CombatWorld.DamageKind.MELEE, reach, 0, true);
        double after = world.observe(entity).map(CombatTarget::health).orElse(0.0);
        return result(id, outcome.status().name(), outcome.applied(), target.health(), after);
    }
    private static String result(String id, String status, double applied, Double before, Double after) {
        return "Attack " + id + ": " + status + " applied=" + applied + " health-before=" + before + " health-after=" + after;
    }
    private static double distance(Vec3 point, Hitbox box) {
        double x = Math.max(box.min().x() - point.x(), Math.max(0, point.x() - box.max().x()));
        double y = Math.max(box.min().y() - point.y(), Math.max(0, point.y() - box.max().y()));
        double z = Math.max(box.min().z() - point.z(), Math.max(0, point.z() - box.max().z()));
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** A validated player hit on a combat NPC, scaled by the player's attack charge like a vanilla hit. */
    void playerHit(ServerPlayer player, UUID npc) {
        if (!module.active() || !module.engine().registered(npc)) return;
        double base = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double charge = player.getAttackStrengthScale(.5f);
        net.minecraft.world.phys.Vec3 at = player.position();
        module.engine().playerAttack(npc, new TargetRef(TargetKind.PLAYER, player.getUUID()),
                new WorldPosition(ModWorldKeys.key(player.level()), new Vec3(at.x(), at.y(), at.z())), base * (.2 + charge * charge * .8));
    }
    /** A real player or mob was hurt by another; guards and allies may respond. */
    void realDamaged(LivingEntity victim, DamageSource source) {
        if (!module.active()) return;
        TargetRef hurt = reference(victim), attacker = source.getEntity() == null ? null : reference(source.getEntity());
        if (hurt != null && attacker != null) module.engine().realDamaged(hurt, attacker);
    }
    /** A player's chat message; NPCs with chat rules nearby may react. */
    void chat(ServerPlayer player, String message) {
        if (module.active()) module.engine().chat(new TargetRef(TargetKind.PLAYER, player.getUUID()), message);
    }
    /** True when the projectile is an NPC helper; its hit is resolved here and vanilla's is skipped. */
    boolean helperHit(Projectile projectile, HitResult hit) {
        if (world == null || !world.helper(projectile)) return false;
        if (module.active()) {
            TargetRef struck = hit instanceof EntityHitResult entityHit ? reference(entityHit.getEntity()) : null;
            if (struck == null || !module.engine().helperHitReal(projectile.getUUID(), struck)) module.engine().helperHitBlock(projectile.getUUID());
        }
        projectile.discard();
        return true;
    }
    boolean suppressesDrops(LivingEntity entity) { return world != null && world.suppressesDrops(entity); }

    private static TargetRef reference(Entity entity) {
        if (entity instanceof Player) return new TargetRef(TargetKind.PLAYER, entity.getUUID());
        return entity instanceof Mob ? new TargetRef(TargetKind.MOB, entity.getUUID()) : null;
    }

    @Override public void close() {
        module.close();
        if (world != null) world.clear();
    }
}
