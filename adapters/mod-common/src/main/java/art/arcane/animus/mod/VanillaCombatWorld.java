package art.arcane.animus.mod;

import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.runtime.RuntimeCombatWorld;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The real-entity side of combat on the mod server thread. NPC bodies stay virtual: damage dealt by an NPC uses a
 * damage source with a position and no causing entity, and its arrows have no shooter.
 */
final class VanillaCombatWorld extends RuntimeCombatWorld<ServerPlayer, ItemStack> {
    private final MinecraftServer server;
    private final VanillaTerrain terrain;
    private final Set<UUID> helpers = new HashSet<>();
    private final Set<UUID> suppressedDrops = new HashSet<>();
    /** Entity type ids, looked up in the registry because the type constants moved between Minecraft versions. */
    private static final Map<RangedWeapon, String> PROJECTILES = Map.of(RangedWeapon.ARROW, "minecraft:arrow",
            RangedWeapon.SPECTRAL_ARROW, "minecraft:spectral_arrow", RangedWeapon.TRIDENT, "minecraft:trident", RangedWeapon.SNOWBALL, "minecraft:snowball",
            RangedWeapon.EGG, "minecraft:egg", RangedWeapon.FIREBALL, "minecraft:fireball", RangedWeapon.SMALL_FIREBALL, "minecraft:small_fireball",
            RangedWeapon.WITHER_SKULL, "minecraft:wither_skull", RangedWeapon.LLAMA_SPIT, "minecraft:llama_spit", RangedWeapon.POTION, "minecraft:splash_potion");

    VanillaCombatWorld(MinecraftServer server, PersistedNpcService<ServerPlayer, ItemStack> living, VanillaTerrain terrain) {
        super(living, terrain);
        this.server = server;
        this.terrain = terrain;
    }

    /** Whether this entity is a combat helper launched by an NPC. */
    boolean helper(Entity entity) { return helpers.contains(entity.getUUID()); }
    /** Whether a death in progress should drop nothing because an NPC that disallows victim drops caused it. */
    boolean suppressesDrops(Entity entity) { return suppressedDrops.contains(entity.getUUID()); }

    Entity entity(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null) return found;
        }
        return null;
    }

    @Override public Optional<CombatTarget> real(TargetRef ref) {
        if (ref.kind() == TargetKind.NPC) return Optional.empty();
        return observe(entity(ref.id())).filter((CombatTarget target) -> target.ref().kind() == ref.kind());
    }

    /** A combat observation of a living player or mob, or empty when it cannot be a participant. */
    Optional<CombatTarget> observe(Entity entity) {
        if (!(entity instanceof LivingEntity creature) || creature.isRemoved() || creature.isDeadOrDying() || creature.getHealth() <= 0 || helper(entity)) return Optional.empty();
        TargetKind kind;
        Set<TargetCategory> categories;
        String name;
        if (creature instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return Optional.empty();
            kind = TargetKind.PLAYER;
            categories = Set.of(TargetCategory.PLAYERS);
            name = player.getGameProfile().name();
        } else if (creature instanceof Mob) {
            kind = TargetKind.MOB;
            categories = creature instanceof Enemy ? Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS) : Set.of(TargetCategory.MOBS, TargetCategory.ANIMALS);
            Component custom = creature.getCustomName();
            name = custom == null ? "" : custom.getString();
        } else return Optional.empty();
        AABB box = creature.getBoundingBox();
        if (box.getXsize() <= 0 || box.getYsize() <= 0 || box.getZsize() <= 0) return Optional.empty();
        net.minecraft.world.phys.Vec3 at = creature.position();
        return Optional.of(new CombatTarget(new TargetRef(kind, creature.getUUID()), BuiltInRegistries.ENTITY_TYPE.getKey(creature.getType()).toString(), name,
                new WorldPosition(ModWorldKeys.key(creature.level()), new Vec3(at.x(), at.y(), at.z())),
                new Hitbox(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ)),
                creature.getHealth(), categories, "", Optional.empty(), details(creature)));
    }

    /** What item, health and effect rules read from a real entity. */
    private static CombatTarget.Details details(LivingEntity creature) {
        List<String> worn = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = creature.getItemBySlot(slot);
            if (!stack.isEmpty()) worn.add(item(stack));
        }
        Set<String> effects = new HashSet<>();
        for (MobEffectInstance effect : creature.getActiveEffects()) {
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id != null) effects.add(id.toString());
        }
        return new CombatTarget.Details(creature.getMaxHealth(), item(creature.getMainHandItem()), item(creature.getOffhandItem()), worn, effects, creature.isInvulnerable());
    }
    private static String item(ItemStack stack) {
        return stack.isEmpty() ? CombatLoadout.EMPTY : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) {
        ServerLevel level = terrain.level(center.world());
        if (level == null) return List.of();
        Vec3 point = center.point();
        AABB area = new AABB(point.x() - radius, point.y() - radius, point.z() - radius, point.x() + radius, point.y() + radius, point.z() + radius);
        List<CombatTarget> found = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, (LivingEntity candidate) -> true)) {
            if (found.size() >= limit) break;
            Optional<CombatTarget> target = observe(entity);
            if (target.isPresent() && target.get().position().point().distance(point) <= radius) found.add(target.get());
        }
        return found;
    }
    @Override public Visibility lineOfSight(String world, Vec3 from, Vec3 to) {
        ServerLevel level = terrain.level(world);
        if (level == null || !loaded(world, from, to)) return Visibility.UNAVAILABLE;
        if (from.distance(to) < .0001) return Visibility.CLEAR;
        HitResult hit = level.clip(new ClipContext(vanilla(from), vanilla(to), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? Visibility.CLEAR : Visibility.BLOCKED;
    }

    @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
        Entity entity = entity(target.ref().id());
        Optional<VirtualNpcState.Snapshot> self = npc(attacker);
        if (!(entity instanceof LivingEntity victim) || victim.isRemoved() || victim.isDeadOrDying() || self.isEmpty() || !self.get().active()
                || !(victim.level() instanceof ServerLevel level))
            return new DamageOutcome(DamageStatus.INVALID, 0, false);
        Vec3 origin = self.get().bounds().min().add(self.get().bounds().max()).multiply(.5);
        ResourceKey<DamageType> type = kind == DamageKind.PROJECTILE ? DamageTypes.ARROW : DamageTypes.MOB_ATTACK;
        Holder<DamageType> holder = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type);
        DamageSource source = new DamageSource(holder, vanilla(origin));
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        boolean suppress = !victimDrops && !(victim instanceof Player);
        if (suppress) suppressedDrops.add(victim.getUUID());
        try {
            victim.hurtServer(level, source, (float) Math.min(100, amount));
        } finally {
            if (suppress) suppressedDrops.remove(victim.getUUID());
        }
        double after = victim.isDeadOrDying() ? 0 : victim.getHealth() + victim.getAbsorptionAmount();
        double applied = Math.max(0, before - after);
        if (applied <= 0) return new DamageOutcome(DamageStatus.NO_CHANGE, 0, false);
        if (knockback > 0 && !victim.isRemoved() && !victim.isDeadOrDying()) {
            net.minecraft.world.phys.Vec3 at = victim.position();
            double dx = at.x() - origin.x(), dz = at.z() - origin.z(), length = Math.hypot(dx, dz);
            if (length > .01) {
                net.minecraft.world.phys.Vec3 motion = victim.getDeltaMovement();
                victim.setDeltaMovement(motion.x() + dx / length * knockback, Math.max(motion.y(), .25), motion.z() + dz / length * knockback);
                BodyMotion.resendVelocity(victim);
            }
        }
        return new DamageOutcome(DamageStatus.APPLIED, applied, victim.isDeadOrDying() || victim.getHealth() <= 0);
    }

    @Override public boolean applyEffect(UUID attacker, CombatTarget target, CombatSettings.Effect effect) {
        Entity entity = entity(target.ref().id());
        Identifier id = Identifier.tryParse(effect.id());
        if (!(entity instanceof LivingEntity victim) || victim.isRemoved() || victim.isDeadOrDying() || id == null) return false;
        Optional<Holder.Reference<MobEffect>> type = BuiltInRegistries.MOB_EFFECT.get(id);
        return type.isPresent() && victim.addEffect(new MobEffectInstance(type.get(), effect.durationTicks(), effect.amplifier()));
    }
    @Override public boolean permitted(TargetRef player, String node) {
        if (player.kind() != TargetKind.PLAYER) return false;
        ServerPlayer online = server.getPlayerList().getPlayer(player.id());
        return online != null && VanillaRuntimeHost.permitted(online.permissions(), node);
    }
    @Override public void tell(UUID player, String speaker, String message) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) online.sendSystemMessage(ModText.component(DialogueFormat.speech(speaker, message)));
    }
    @Override public void actionBar(UUID player, String text) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) online.sendSystemMessage(ModText.component(text), true);
    }
    @Override public void credit(TargetRef killer, UUID npc) {
        ServerPlayer online = killer.kind() == TargetKind.PLAYER ? server.getPlayerList().getPlayer(killer.id()) : null;
        if (online != null) online.awardStat(net.minecraft.stats.Stats.MOB_KILLS);
    }
    @Override public Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity, RangedWeapon weapon) {
        ServerLevel level = terrain.level(from.world());
        Vec3 point = from.point();
        if (level == null || !level.getChunkSource().hasChunk(((int) Math.floor(point.x())) >> 4, ((int) Math.floor(point.z())) >> 4)) return Optional.empty();
        if (velocity.distance(Vec3.ZERO) < .0001) return Optional.empty();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.tryParse(PROJECTILES.get(weapon)));
        if (!(type.create(level, EntitySpawnReason.TRIGGERED) instanceof Projectile projectile)) return Optional.empty();
        if (projectile instanceof AbstractArrow arrow) arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        projectile.setPos(point.x(), point.y(), point.z());
        projectile.setDeltaMovement(vanilla(velocity));
        // Known as a helper before it enters the world, so its launch publishes no server trigger.
        helpers.add(projectile.getUUID());
        if (!level.addFreshEntity(projectile)) { helpers.remove(projectile.getUUID()); return Optional.empty(); }
        return Optional.of(projectile.getUUID());
    }
    @Override public Optional<Vec3> helperPosition(UUID helper) {
        Entity entity = helpers.contains(helper) ? entity(helper) : null;
        if (entity == null || entity.isRemoved()) { helpers.remove(helper); return Optional.empty(); }
        net.minecraft.world.phys.Vec3 at = entity.position();
        return Optional.of(new Vec3(at.x(), at.y(), at.z()));
    }
    @Override public void removeHelper(UUID helper) {
        Entity entity = entity(helper);
        if (entity != null) entity.discard();
        helpers.remove(helper);
    }

    @Override public Optional<WorldPosition> resolve(CombatSettings.Location location) {
        return ModWorldKeys.resolve(server, location.world()).map((String world) -> new WorldPosition(world, new Vec3(location.x(), location.y(), location.z())));
    }
    @Override public void drop(WorldPosition at, List<Drop> items, int experience) {
        ServerLevel level = terrain.level(at.world());
        Vec3 point = at.point();
        if (level == null || !level.getChunkSource().hasChunk(((int) Math.floor(point.x())) >> 4, ((int) Math.floor(point.z())) >> 4)) return;
        for (Drop drop : items) {
            ItemStack stack = VanillaRuntimeHost.stack(server, new art.arcane.animus.core.npc.NpcItem(drop.item(), drop.amount(), drop.components(), Optional.empty()), "drop", new ArrayList<>());
            if (stack == null) continue;
            ItemEntity dropped = new ItemEntity(level, point.x(), point.y(), point.z(), stack);
            dropped.setDefaultPickUpDelay();
            level.addFreshEntity(dropped);
        }
        if (experience > 0) ExperienceOrb.award(level, vanilla(point), experience);
    }

    @Override public Contact contact(UUID npc) {
        Optional<VirtualNpcState.Snapshot> self = npc(npc);
        ServerLevel level = self.isEmpty() ? null : terrain.level(self.get().position().world());
        return level == null ? Contact.NONE : ModShots.contact(level, self.get().bounds());
    }

    /** Helpers vanish with the module; no NPC arrow outlives it. */
    void clear() {
        for (UUID helper : List.copyOf(helpers)) removeHelper(helper);
        suppressedDrops.clear();
    }

    private static net.minecraft.world.phys.Vec3 vanilla(Vec3 point) { return new net.minecraft.world.phys.Vec3(point.x(), point.y(), point.z()); }
    static BlockPos block(Vec3 point) { return BlockPos.containing(point.x(), point.y(), point.z()); }
}
