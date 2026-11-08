package art.arcane.animus.mod;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.Hazards;
import art.arcane.animus.core.combat.ProjectileFlight;
import art.arcane.animus.core.combat.RealShot;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.animus.mod.mixin.ArrowDataAccess;
import java.util.Optional;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real projectiles from players and mobs against NPC bodies on mod servers: which ones count, the shot each carries, and
 * what happens to the projectile after a hit. Also reads what an NPC body touches for fire and lava. Server thread.
 */
final class ModShots {
    private ModShots() {}

    static Optional<RealShot.Kind> kind(Projectile projectile) {
        if (projectile instanceof ThrownTrident) return Optional.of(RealShot.Kind.TRIDENT);
        if (projectile instanceof AbstractArrow) return Optional.of(RealShot.Kind.ARROW);
        if (projectile instanceof Snowball) return Optional.of(RealShot.Kind.SNOWBALL);
        if (projectile instanceof ThrownEgg) return Optional.of(RealShot.Kind.EGG);
        if (projectile instanceof SmallFireball) return Optional.of(RealShot.Kind.SMALL_FIREBALL);
        if (projectile instanceof WitherSkull) return Optional.of(RealShot.Kind.WITHER_SKULL);
        if (projectile instanceof LargeFireball) return Optional.of(RealShot.Kind.LARGE_FIREBALL);
        if (projectile instanceof AbstractThrownPotion) return Optional.of(RealShot.Kind.POTION);
        return Optional.empty();
    }
    /** The player or mob that fired it; null for a dispenser or no owner. */
    static TargetRef shooter(Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner instanceof Player player) return new TargetRef(TargetKind.PLAYER, player.getUUID());
        return owner instanceof Mob mob ? new TargetRef(TargetKind.MOB, mob.getUUID()) : null;
    }
    static int pierce(Projectile projectile) { return projectile instanceof AbstractArrow arrow && !(arrow instanceof ThrownTrident) ? arrow.getPierceLevel() : 0; }
    static WorldPosition position(Projectile projectile) {
        net.minecraft.world.phys.Vec3 at = projectile.position();
        return new WorldPosition(ModWorldKeys.key(projectile.level()), new Vec3(at.x(), at.y(), at.z()));
    }
    static Vec3 velocity(Projectile projectile) {
        net.minecraft.world.phys.Vec3 motion = projectile.getDeltaMovement();
        return new Vec3(motion.x(), motion.y(), motion.z());
    }

    static RealShot shot(Projectile projectile, RealShot.Kind kind, TargetRef shooter, ProjectileFlight flight, RandomGenerator random) {
        WorldPosition at = position(projectile);
        WorldPosition from = new WorldPosition(at.world(), flight.origin().orElse(at.point()));
        double arrowDamage = 0;
        if (kind == RealShot.Kind.ARROW && projectile instanceof AbstractArrow arrow) {
            ItemStack weapon = arrow.getWeaponItem();
            int power = weapon == null || weapon.isEmpty() ? 0 : EnchantmentHelper.getItemEnchantmentLevel(
                    projectile.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER), weapon);
            arrowDamage = RealShot.arrowDamage(arrow.getDeltaMovement().length(), ((ArrowDataAccess) arrow).animus$baseDamage(), power, arrow.isCritArrow(), random);
        }
        return RealShot.of(kind, shooter, from, arrowDamage, projectile instanceof AbstractThrownPotion potion ? instant(potion) : Optional.empty());
    }
    private static Optional<CombatSettings.Effect> instant(AbstractThrownPotion potion) {
        PotionContents contents = potion.getItem().get(DataComponents.POTION_CONTENTS);
        if (contents == null) return Optional.empty();
        for (MobEffectInstance effect : contents.getAllEffects()) {
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            String name = id == null ? "" : id.toString();
            if (name.equals("minecraft:instant_damage") || name.equals("minecraft:instant_health")) return Optional.of(new CombatSettings.Effect(name, effect.getAmplifier(), 1));
        }
        return Optional.empty();
    }

    /** As vanilla treats a living target: an arrow thuds and is gone unless it still pierces, a trident bounces, a potion drops, a fireball bursts. */
    static void land(Projectile projectile, RealShot.Kind kind, Vec3 point, boolean spent) {
        Level level = projectile.level();
        switch (kind.landing()) {
            case REMOVE -> {
                if (kind == RealShot.Kind.ARROW)
                    level.playSound(null, point.x(), point.y(), point.z(), SoundEvents.ARROW_HIT, SoundSource.NEUTRAL, 1f, 1.2f / (level.getRandom().nextFloat() * .2f + .9f));
                if (spent) projectile.discard();
            }
            case BOUNCE -> {
                level.playSound(null, point.x(), point.y(), point.z(), SoundEvents.TRIDENT_HIT, SoundSource.NEUTRAL, 1f, 1f);
                projectile.setDeltaMovement(projectile.getDeltaMovement().multiply(-.01, -.1, -.01));
            }
            case DROP -> projectile.setDeltaMovement(0, -.1, 0);
            case EXPLODE -> {
                Entity owner = projectile.getOwner();
                projectile.discard();
                level.explode(owner, point.x(), point.y(), point.z(), 1f, kind == RealShot.Kind.LARGE_FIREBALL, Level.ExplosionInteraction.NONE);
            }
        }
    }

    /** What a body with these bounds touches in the level, reading only loaded chunks. */
    static CombatWorld.Contact contact(ServerLevel level, Hitbox bounds) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return Hazards.contact(bounds, (int x, int y, int z) -> {
            if (level.isOutsideBuildHeight(y) || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) return CombatWorld.Contact.NONE;
            BlockState state = level.getBlockState(at.set(x, y, z));
            if (state.getFluidState().is(FluidTags.LAVA)) return CombatWorld.Contact.LAVA;
            if (state.getFluidState().is(FluidTags.WATER)) return CombatWorld.Contact.WATER;
            return state.is(BlockTags.FIRE) ? CombatWorld.Contact.FIRE : CombatWorld.Contact.NONE;
        });
    }
}
