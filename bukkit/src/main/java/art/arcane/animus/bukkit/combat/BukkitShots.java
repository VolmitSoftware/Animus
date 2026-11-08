package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.Hazards;
import art.arcane.animus.core.combat.ProjectileFlight;
import art.arcane.animus.core.combat.RealShot;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.navigation.ChunkOwnership;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WitherSkull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Real projectiles from players and mobs against NPC bodies on Bukkit: which ones count, the shot each carries, and what
 * happens to the projectile after a hit. Also reads what an NPC body touches for fire and lava. Every call runs on the
 * thread that owns the projectile or the blocks.
 */
public final class BukkitShots {
    private BukkitShots() {}

    /** The kind of a projectile that can hurt an NPC, or empty for one that passes through. */
    public static Optional<RealShot.Kind> kind(Projectile projectile) {
        if (projectile instanceof Trident) return Optional.of(RealShot.Kind.TRIDENT);
        if (projectile instanceof AbstractArrow) return Optional.of(RealShot.Kind.ARROW);
        if (projectile instanceof Snowball) return Optional.of(RealShot.Kind.SNOWBALL);
        if (projectile instanceof Egg) return Optional.of(RealShot.Kind.EGG);
        if (projectile instanceof SmallFireball) return Optional.of(RealShot.Kind.SMALL_FIREBALL);
        if (projectile instanceof WitherSkull) return Optional.of(RealShot.Kind.WITHER_SKULL);
        if (projectile instanceof LargeFireball) return Optional.of(RealShot.Kind.LARGE_FIREBALL);
        if (projectile instanceof ThrownPotion) return Optional.of(RealShot.Kind.POTION);
        return Optional.empty();
    }
    /** The player or mob that fired it; null for a dispenser, an NPC helper or no shooter. */
    public static TargetRef shooter(Projectile projectile) {
        ProjectileSource source = projectile.getShooter();
        if (source instanceof Player player) return new TargetRef(TargetKind.PLAYER, player.getUniqueId());
        return source instanceof Mob mob ? new TargetRef(TargetKind.MOB, mob.getUniqueId()) : null;
    }
    /** How many bodies a piercing arrow passes through before it stops. */
    public static int pierce(Projectile projectile) { return projectile instanceof AbstractArrow arrow && !(arrow instanceof Trident) ? arrow.getPierceLevel() : 0; }

    public static WorldPosition position(Projectile projectile) {
        Location at = projectile.getLocation();
        return new WorldPosition(at.getWorld().getUID().toString(), new Vec3(at.getX(), at.getY(), at.getZ()));
    }
    public static Vec3 velocity(Projectile projectile) {
        Vector velocity = projectile.getVelocity();
        return new Vec3(velocity.getX(), velocity.getY(), velocity.getZ());
    }

    /** The shot this projectile delivers as it strikes, fired from where its flight began. */
    public static RealShot shot(Projectile projectile, RealShot.Kind kind, TargetRef shooter, ProjectileFlight flight, RandomGenerator random) {
        WorldPosition at = position(projectile);
        WorldPosition from = new WorldPosition(at.world(), flight.origin().orElse(at.point()));
        double arrowDamage = 0;
        if (kind == RealShot.Kind.ARROW && projectile instanceof AbstractArrow arrow) {
            ItemStack weapon = arrow.getWeapon();
            int power = weapon == null ? 0 : weapon.getEnchantmentLevel(Enchantment.POWER);
            arrowDamage = RealShot.arrowDamage(arrow.getVelocity().length(), arrow.getDamage(), power, arrow.isCritical(), random);
        }
        return RealShot.of(kind, shooter, from, arrowDamage, kind == RealShot.Kind.POTION && projectile instanceof ThrownPotion potion ? instant(potion) : Optional.empty());
    }
    private static Optional<CombatSettings.Effect> instant(ThrownPotion potion) {
        for (PotionEffect effect : potion.getEffects()) {
            if (effect.getType().equals(PotionEffectType.INSTANT_DAMAGE)) return Optional.of(new CombatSettings.Effect("minecraft:instant_damage", effect.getAmplifier(), 1));
            if (effect.getType().equals(PotionEffectType.INSTANT_HEALTH)) return Optional.of(new CombatSettings.Effect("minecraft:instant_health", effect.getAmplifier(), 1));
        }
        return Optional.empty();
    }

    /**
     * What vanilla does to the projectile after it hits a living body: an arrow thuds and is gone unless it still pierces, a
     * trident bounces back, a potion drops to break at the body's feet, and a fireball or skull bursts where it hit.
     */
    public static void land(Projectile projectile, RealShot.Kind kind, Vec3 point, boolean spent) {
        World world = projectile.getWorld();
        Location at = new Location(world, point.x(), point.y(), point.z());
        switch (kind.landing()) {
            case REMOVE -> {
                if (kind == RealShot.Kind.ARROW) world.playSound(at, Sound.ENTITY_ARROW_HIT, 1f, 1.2f / (float) (Math.random() * .2 + .9));
                if (spent) projectile.remove();
            }
            case BOUNCE -> {
                world.playSound(at, Sound.ITEM_TRIDENT_HIT, 1f, 1f);
                projectile.setVelocity(projectile.getVelocity().multiply(new Vector(-.01, -.1, -.01)));
            }
            case DROP -> projectile.setVelocity(new Vector(0, -.1, 0));
            case EXPLODE -> {
                float power = projectile instanceof Explosive explosive ? explosive.getYield() : 1f;
                boolean fire = projectile instanceof Explosive explosive && explosive.isIncendiary();
                projectile.remove();
                world.createExplosion(at, power, fire, false, projectile.getShooter() instanceof org.bukkit.entity.Entity source ? source : null);
            }
        }
    }

    /** What a body with these bounds touches in this world, reading only loaded blocks in chunks this thread may read. */
    public static CombatWorld.Contact contact(World world, Hitbox bounds, ChunkOwnership readable) {
        return Hazards.contact(bounds, (int x, int y, int z) -> {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !readable.owns(x >> 4, z >> 4) || !world.isChunkLoaded(x >> 4, z >> 4)) return CombatWorld.Contact.NONE;
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type == Material.LAVA) return CombatWorld.Contact.LAVA;
            if (type == Material.WATER || type == Material.BUBBLE_COLUMN) return CombatWorld.Contact.WATER;
            if (type == Material.FIRE || type == Material.SOUL_FIRE) return CombatWorld.Contact.FIRE;
            BlockData data = block.getBlockData();
            return data instanceof Waterlogged wet && wet.isWaterlogged() ? CombatWorld.Contact.WATER : CombatWorld.Contact.NONE;
        });
    }
}
