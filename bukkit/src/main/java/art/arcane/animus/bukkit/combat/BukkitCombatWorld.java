package art.arcane.animus.bukkit.combat;

import art.arcane.animus.bukkit.BukkitWorldLookup;
import art.arcane.animus.bukkit.runtime.DefinitionItems;
import art.arcane.animus.core.npc.NpcItem;

import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.bukkit.event.VirtualNpcDamageEvent;
import art.arcane.animus.bukkit.navigation.BukkitTerrain;
import art.arcane.animus.core.runtime.RuntimeCombatWorld;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.SplashPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Bukkit side of the combat engine, on the main thread. NPC bodies stay virtual: damage dealt by an NPC
 * uses a damage source with a location and no causing entity, and its arrows have no shooter.
 */
public final class BukkitCombatWorld extends RuntimeCombatWorld<Player, ItemStack> {
    private static final Map<RangedWeapon, Class<? extends Projectile>> THROWN = Map.of(RangedWeapon.SNOWBALL, Snowball.class, RangedWeapon.EGG, Egg.class,
            RangedWeapon.FIREBALL, LargeFireball.class, RangedWeapon.SMALL_FIREBALL, SmallFireball.class, RangedWeapon.WITHER_SKULL, WitherSkull.class,
            RangedWeapon.LLAMA_SPIT, LlamaSpit.class, RangedWeapon.POTION, SplashPotion.class);
    private final Plugin plugin;
    private final NamespacedKey helperKey;
    private final Set<UUID> suppressedDrops = new HashSet<>();

    public BukkitCombatWorld(Plugin plugin, PersistedNpcService<Player, ItemStack> living) {
        super(living, new BukkitTerrain(plugin.getServer()));
        this.plugin = plugin;
        helperKey = new NamespacedKey(plugin, "combat_helper");
    }

    /** Whether this entity is a combat helper launched by an NPC. */
    public boolean helper(Entity entity) {
        return entity.getPersistentDataContainer().has(helperKey, PersistentDataType.BYTE);
    }
    /** Whether a death in progress should drop nothing because an NPC that disallows victim drops caused it. */
    public boolean suppressesDrops(UUID entity) { return suppressedDrops.contains(entity); }

    @Override public Optional<CombatTarget> real(TargetRef ref) {
        if (ref.kind() == TargetKind.NPC) return Optional.empty();
        return observe(plugin.getServer().getEntity(ref.id())).filter((CombatTarget target) -> target.ref().kind() == ref.kind());
    }

    @Override public Optional<CombatTarget> charge(TargetRef ref) {
        if (ref.kind() == TargetKind.NPC) return Optional.empty();
        return observe(plugin.getServer().getEntity(ref.id()), this::helper, true).filter((CombatTarget target) -> target.ref().kind() == ref.kind());
    }

    /** A combat observation of a living player or mob, or empty when it cannot be a participant. */
    public Optional<CombatTarget> observe(Entity entity) { return observe(entity, this::helper); }
    /** On the entity's owner: a combat observation, or empty when it cannot be a participant or is a helper. */
    public static Optional<CombatTarget> observe(Entity entity, java.util.function.Predicate<Entity> helper) { return observe(entity, helper, false); }
    /** As {@link #observe(Entity, java.util.function.Predicate)}; with {@code anyMode} a creative or spectator player counts, as a guard's charge. */
    public static Optional<CombatTarget> observe(Entity entity, java.util.function.Predicate<Entity> helper, boolean anyMode) {
        if (!(entity instanceof LivingEntity creature) || !creature.isValid() || creature.isDead() || creature.getHealth() <= 0 || helper.test(entity)) return Optional.empty();
        TargetKind kind;
        Set<TargetCategory> categories;
        String name;
        if (creature instanceof Player player) {
            if (!anyMode && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) return Optional.empty();
            kind = TargetKind.PLAYER;
            categories = Set.of(TargetCategory.PLAYERS);
            name = player.getName();
        } else if (creature instanceof Mob) {
            kind = TargetKind.MOB;
            categories = creature instanceof Enemy ? Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS) : Set.of(TargetCategory.MOBS, TargetCategory.ANIMALS);
            name = creature.getCustomName() == null ? "" : creature.getCustomName();
        } else return Optional.empty();
        BoundingBox box = creature.getBoundingBox();
        if (box.getWidthX() <= 0 || box.getHeight() <= 0 || box.getWidthZ() <= 0) return Optional.empty();
        Location at = creature.getLocation();
        return Optional.of(new CombatTarget(new TargetRef(kind, creature.getUniqueId()), creature.getType().getKey().toString(), name,
                new WorldPosition(at.getWorld().getUID().toString(), new Vec3(at.getX(), at.getY(), at.getZ())),
                new Hitbox(new Vec3(box.getMinX(), box.getMinY(), box.getMinZ()), new Vec3(box.getMaxX(), box.getMaxY(), box.getMaxZ())),
                creature.getHealth(), categories, "", Optional.empty(), details(creature)));
    }

    /** What item, health and effect rules read from a real entity. */
    private static CombatTarget.Details details(LivingEntity creature) {
        AttributeInstance maximum = creature.getAttribute(Attribute.MAX_HEALTH);
        EntityEquipment equipment = creature.getEquipment();
        List<String> worn = new ArrayList<>();
        String mainHand = CombatLoadout.EMPTY, offHand = CombatLoadout.EMPTY;
        if (equipment != null) {
            mainHand = item(equipment.getItemInMainHand());
            offHand = item(equipment.getItemInOffHand());
            for (ItemStack piece : equipment.getArmorContents()) if (piece != null && piece.getType() != Material.AIR) worn.add(item(piece));
        }
        Set<String> effects = new HashSet<>();
        for (PotionEffect effect : creature.getActivePotionEffects()) effects.add(effect.getType().getKey().toString());
        return new CombatTarget.Details(maximum == null ? 0 : maximum.getValue(), mainHand, offHand, worn, effects, creature.isInvulnerable());
    }
    private static String item(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR ? CombatLoadout.EMPTY : stack.getType().getKey().toString();
    }

    @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) {
        World world = world(center.world());
        if (world == null) return List.of();
        Vec3 point = center.point();
        BoundingBox area = new BoundingBox(point.x() - radius, point.y() - radius, point.z() - radius, point.x() + radius, point.y() + radius, point.z() + radius);
        List<CombatTarget> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(area, (Entity candidate) -> candidate instanceof LivingEntity)) {
            if (found.size() >= limit) break;
            Optional<CombatTarget> target = observe(entity);
            if (target.isPresent() && target.get().position().point().distance(point) <= radius) found.add(target.get());
        }
        return found;
    }
    @Override public Visibility lineOfSight(String worldId, Vec3 from, Vec3 to) {
        World world = world(worldId);
        if (world == null || !loaded(worldId, from, to)) return Visibility.UNAVAILABLE;
        double distance = from.distance(to);
        if (distance < .0001) return Visibility.CLEAR;
        return world.rayTraceBlocks(location(world, from), vector(to.subtract(from)), distance, FluidCollisionMode.NEVER, true) == null
                ? Visibility.CLEAR : Visibility.BLOCKED;
    }

    @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
        Entity entity = plugin.getServer().getEntity(target.ref().id());
        Optional<VirtualNpcState.Snapshot> self = npc(attacker);
        if (!(entity instanceof LivingEntity victim) || self.isEmpty()) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        return damage(plugin, attacker, self.get(), victim, amount, kind, knockback, victimDrops, suppressedDrops,
                () -> npc(attacker).filter(VirtualNpcState.Snapshot::active).isPresent());
    }
    /**
     * On the victim's owner: one NPC hit with no causing entity. {@code attacking} is asked again after listeners ran,
     * so a hit from an NPC that despawned meanwhile does nothing.
     */
    public static DamageOutcome damage(Plugin plugin, UUID attacker, VirtualNpcState.Snapshot self, LivingEntity victim, double amount, DamageKind kind,
                                       double knockback, boolean victimDrops, Set<UUID> suppressedDrops, java.util.function.BooleanSupplier attacking) {
        if (!victim.isValid() || victim.isDead() || !self.active()) return new DamageOutcome(DamageStatus.INVALID, 0, false);
        VirtualNpcDamageEvent event = new VirtualNpcDamageEvent(attacker, victim, Math.min(100, amount));
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return new DamageOutcome(DamageStatus.CANCELLED, 0, false);
        if (!victim.isValid() || victim.isDead() || !attacking.getAsBoolean())
            return new DamageOutcome(DamageStatus.INVALID, 0, false);
        Vec3 origin = self.bounds().min().add(self.bounds().max()).multiply(.5);
        DamageSource source = DamageSource.builder(kind == DamageKind.PROJECTILE ? DamageType.ARROW : DamageType.MOB_ATTACK)
                .withDamageLocation(location(victim.getWorld(), origin)).build();
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        boolean suppress = !victimDrops && !(victim instanceof Player);
        if (suppress) suppressedDrops.add(victim.getUniqueId());
        try {
            victim.damage(event.getDamage(), source);
        } finally {
            if (suppress) suppressedDrops.remove(victim.getUniqueId());
        }
        double after = victim.isDead() ? 0 : victim.getHealth() + victim.getAbsorptionAmount();
        double applied = Math.max(0, before - after);
        if (applied <= 0) return new DamageOutcome(DamageStatus.NO_CHANGE, 0, false);
        if (knockback > 0 && victim.isValid() && !victim.isDead()) {
            Location at = victim.getLocation();
            Vector away = new Vector(at.getX() - origin.x(), 0, at.getZ() - origin.z());
            if (away.lengthSquared() > .0001) victim.setVelocity(victim.getVelocity().add(away.normalize().multiply(knockback)).setY(Math.max(victim.getVelocity().getY(), .25)));
        }
        return new DamageOutcome(DamageStatus.APPLIED, applied, victim.isDead() || victim.getHealth() <= 0);
    }
    @Override public boolean applyEffect(UUID attacker, CombatTarget target, CombatSettings.Effect effect) {
        return effect(plugin.getServer().getEntity(target.ref().id()), effect);
    }
    /** On the entity's owner: a potion helper's only effect. */
    public static boolean effect(Entity entity, CombatSettings.Effect effect) {
        NamespacedKey key = NamespacedKey.fromString(effect.id());
        PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
        if (!(entity instanceof LivingEntity victim) || !victim.isValid() || victim.isDead() || type == null) return false;
        return victim.addPotionEffect(new PotionEffect(type, effect.durationTicks(), effect.amplifier()));
    }
    @Override public boolean permitted(TargetRef player, String node) {
        if (player.kind() != TargetKind.PLAYER) return false;
        Player online = plugin.getServer().getPlayer(player.id());
        return online != null && online.hasPermission(node);
    }
    @Override public void tell(UUID player, String speaker, String message) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) online.sendMessage(DialogueFormat.speech(speaker, message));
    }
    @Override public void actionBar(UUID player, String text) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) actionBar(online, text);
    }
    /** A health line above the hotbar, as Spigot sends one. */
    public static void actionBar(Player player, String text) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacy(text));
    }
    @Override public void credit(TargetRef killer, UUID npc) {
        Player online = killer.kind() == TargetKind.PLAYER ? plugin.getServer().getPlayer(killer.id()) : null;
        if (online != null) online.incrementStatistic(Statistic.MOB_KILLS);
    }
    /** Every helper is a shooterless projectile tagged as a helper; it never saves with its chunk and never breaks blocks. */
    @Override public Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity, RangedWeapon weapon) {
        World world = world(from.world());
        Vec3 point = from.point();
        if (world == null || !world.isChunkLoaded(((int) Math.floor(point.x())) >> 4, ((int) Math.floor(point.z())) >> 4)) return Optional.empty();
        return launch(world, point, velocity, weapon, this::tag).map(Projectile::getUniqueId);
    }
    /** True on a thread while it spawns a helper, whose launch event then belongs to no server trigger. */
    private static final ThreadLocal<Boolean> LAUNCHING = ThreadLocal.withInitial(() -> false);
    public static boolean launching() { return LAUNCHING.get(); }

    /** On the owner of the launch chunk: a shooterless helper projectile, tagged before it can touch anything. */
    public static Optional<Projectile> launch(World world, Vec3 point, Vec3 velocity, RangedWeapon weapon, java.util.function.Consumer<Projectile> tag) {
        double speed = velocity.distance(Vec3.ZERO);
        if (speed < .0001) return Optional.empty();
        Location at = location(world, point);
        Projectile projectile;
        LAUNCHING.set(true);
        try {
            projectile = switch (weapon) {
                case ARROW -> world.spawnArrow(at, vector(velocity), (float) speed, 0);
                case SPECTRAL_ARROW -> world.spawnArrow(at, vector(velocity), (float) speed, 0, SpectralArrow.class);
                case TRIDENT -> world.spawnArrow(at, vector(velocity), (float) speed, 0, Trident.class);
                default -> world.spawn(at, THROWN.get(weapon), tag::accept);
            };
        } finally {
            LAUNCHING.set(false);
        }
        if (!projectile.isValid()) return Optional.empty();
        tag.accept(projectile);
        if (projectile instanceof AbstractArrow arrow) arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        if (projectile instanceof Fireball fireball) fireball.setAcceleration(vector(velocity.normalized().multiply(.1)));
        projectile.setVelocity(vector(velocity));
        return Optional.of(projectile);
    }
    private void tag(Projectile projectile) { tag(projectile, helperKey, (byte) 1); }
    /** Marks a helper so its own hits, damage and explosion never apply; it never saves with its chunk. */
    public static void tag(Projectile projectile, NamespacedKey key, byte value) {
        projectile.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value);
        projectile.setPersistent(false);
        if (projectile instanceof Explosive explosive) {
            explosive.setYield(0);
            explosive.setIsIncendiary(false);
        }
    }
    @Override public Optional<Vec3> helperPosition(UUID helper) {
        Entity entity = plugin.getServer().getEntity(helper);
        if (entity == null || !entity.isValid()) return Optional.empty();
        Location at = entity.getLocation();
        return Optional.of(new Vec3(at.getX(), at.getY(), at.getZ()));
    }
    @Override public void removeHelper(UUID helper) {
        Entity entity = plugin.getServer().getEntity(helper);
        if (entity != null) entity.remove();
    }

    @Override public Optional<WorldPosition> resolve(CombatSettings.Location location) {
        World world = world(location.world());
        return world == null ? Optional.empty() : Optional.of(new WorldPosition(world.getUID().toString(), new Vec3(location.x(), location.y(), location.z())));
    }
    @Override public void drop(WorldPosition at, List<Drop> items, int experience) {
        World world = world(at.world());
        Vec3 point = at.point();
        if (world == null || !world.isChunkLoaded(((int) Math.floor(point.x())) >> 4, ((int) Math.floor(point.z())) >> 4)) return;
        drop(world, point, items, experience);
    }
    /** On the owner of the chunk: death drops and experience. */
    public static void drop(World world, Vec3 point, List<Drop> items, int experience) {
        Location place = location(world, point);
        for (Drop drop : items) {
            ItemStack stack = DefinitionItems.stack(new NpcItem(drop.item(), drop.amount(), drop.components(), Optional.empty()), "drop", new ArrayList<>());
            if (stack != null) world.dropItemNaturally(place, stack);
        }
        if (experience > 0) world.spawn(place, ExperienceOrb.class, (ExperienceOrb orb) -> orb.setExperience(experience));
    }

    private World world(String id) { return BukkitWorldLookup.find(plugin.getServer(), id); }
    @Override public Contact contact(UUID npc) {
        Optional<VirtualNpcState.Snapshot> self = npc(npc);
        World world = self.isEmpty() ? null : world(self.get().position().world());
        return world == null ? Contact.NONE : BukkitShots.contact(world, self.get().bounds(), (int x, int z) -> true);
    }
    private static Location location(World world, Vec3 point) { return new Location(world, point.x(), point.y(), point.z()); }
    private static Vector vector(Vec3 point) { return new Vector(point.x(), point.y(), point.z()); }
}
