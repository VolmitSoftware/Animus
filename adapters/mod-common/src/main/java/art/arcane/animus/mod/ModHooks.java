package art.arcane.animus.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * Where the shared mixins report. Every hook is a no-op while no runtime is running, so the mod costs nothing on a
 * server where Animus failed to start, and on the client.
 */
public final class ModHooks {
    private static volatile AnimusModRuntime runtime;

    private ModHooks() {}

    static void attach(AnimusModRuntime active) { runtime = active; }
    static void detach(AnimusModRuntime active) { if (runtime == active) runtime = null; }

    /** True on the server thread that owns this player; vanilla re-runs packet handlers there. */
    public static boolean owner(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return runtime != null && server != null && server.isSameThread();
    }
    /** True when the entity ID belongs to a virtual body this player sees; the packet is then consumed. */
    public static boolean interact(ServerPlayer player, int entityId, boolean attack, String hand, boolean secondary) {
        AnimusModRuntime active = runtime;
        return active != null && active.interact(player, entityId, attack, hand, secondary);
    }
    /** The drop or swap-hands key, on the owner pass; true when an NPC interaction trigger consumed it. */
    public static boolean pressed(ServerPlayer player, art.arcane.animus.core.behavior.trigger.TriggerEvent.Action key) {
        AnimusModRuntime active = runtime;
        return active != null && active.pressed(player, key);
    }
    /** The player's arm-swing packet, on the owner pass. */
    public static void swung(ServerPlayer player, InteractionHand hand) {
        AnimusModRuntime active = runtime;
        if (active != null) active.swung(player, hand);
    }
    /** The player's use-on-block packet, on the owner pass, before vanilla validates or applies it. */
    public static void usedBlock(ServerPlayer player, InteractionHand hand, BlockPos at) {
        AnimusModRuntime active = runtime;
        if (active != null) active.usedBlock(player, hand, at);
    }
    public static void input(ServerPlayer player, Input input) {
        AnimusModRuntime active = runtime;
        if (active != null) active.input(player, input);
    }
    public static void tickEnd(ServerPlayer player) {
        AnimusModRuntime active = runtime;
        if (active != null) active.tickEnd(player);
    }
    /** The destination is absolute; relative axes are already added to the player's position. */
    public static void teleported(ServerPlayer player, double x, double y, double z) {
        AnimusModRuntime active = runtime;
        if (active != null) active.teleported(player, x, y, z);
    }
    public static void joined(ServerPlayer player) {
        AnimusModRuntime active = runtime;
        if (active != null) active.joined(player);
    }
    public static void quit(ServerPlayer player) {
        AnimusModRuntime active = runtime;
        if (active != null) active.quit(player);
    }
    /** Respawn replaces the player object; {@code old} still names the world the player died in. */
    public static void respawned(ServerPlayer old, ServerPlayer player) {
        AnimusModRuntime active = runtime;
        if (active != null) active.respawned(old, player);
    }
    /** After a teleport; {@code from} is the level the player was in before it. */
    public static void teleportedFrom(ServerPlayer player, Level from) {
        AnimusModRuntime active = runtime;
        if (active != null && from != null && from != player.level()) active.worldChanged(player, from);
    }
    public static void died(ServerPlayer player) {
        AnimusModRuntime active = runtime;
        if (active != null) active.died(player);
    }
    public static void chat(ServerPlayer player, String message) {
        AnimusModRuntime active = runtime;
        if (active != null) active.chat(player, message);
    }
    public static void realDamaged(LivingEntity victim, DamageSource source) {
        AnimusModRuntime active = runtime;
        if (active != null && !victim.level().isClientSide()) active.realDamaged(victim, source);
    }
    public static boolean helperHit(Projectile projectile, HitResult hit) {
        AnimusModRuntime active = runtime;
        return active != null && !projectile.level().isClientSide() && active.helperHit(projectile, hit);
    }
    public static boolean suppressesDrops(LivingEntity entity) {
        AnimusModRuntime active = runtime;
        return active != null && active.suppressesDrops(entity);
    }

    /*
     * Server behavior world events, on the server thread. A true answer means a trigger cancelled the action; the caller
     * stops it and resyncs the client.
     */

    public static boolean opening(ServerPlayer player, AbstractContainerMenu menu) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.opening(player, menu);
    }
    public static void closing(ServerPlayer player) {
        ModServerBehaviors behaviors = behaviors();
        if (behaviors != null) behaviors.closing(player);
    }
    public static boolean clicking(ServerPlayer player, int containerId, int slot, int button, ContainerInput input) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.clicking(player, containerId, slot, button, input);
    }
    public static boolean dropping(ServerPlayer player, boolean all) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.dropping(player, all);
    }
    public static boolean pickingUp(ServerPlayer player, ItemStack stack) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.pickingUp(player, stack);
    }
    public static boolean consuming(ServerPlayer player, ItemStack stack) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.consuming(player, stack);
    }
    public static boolean clickingBlock(ServerPlayer player, boolean left, InteractionHand hand, BlockPos pos) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.clickingBlock(player, left, hand, pos);
    }
    public static boolean placing(ServerPlayer player, Block block, BlockPos pos) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.placing(player, block, pos);
    }
    public static boolean breaking(ServerPlayer player, BlockPos pos) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.breaking(player, pos);
    }
    public static boolean hurting(LivingEntity victim, ServerLevel level, DamageSource source, float amount) {
        ModServerBehaviors behaviors = behaviors();
        return behaviors != null && behaviors.hurting(victim, level, source, amount);
    }
    public static void killed(LivingEntity victim, DamageSource source) {
        ModServerBehaviors behaviors = behaviors();
        if (behaviors != null) behaviors.killed(victim, source);
    }
    /** A bow or crossbow shot begins; {@code velocity} is the speed vanilla gives its projectiles. */
    public static void aiming(LivingEntity shooter, InteractionHand hand, ItemStack weapon, float velocity) {
        ModServerBehaviors behaviors = behaviors();
        if (behaviors != null && !shooter.level().isClientSide()) behaviors.aiming(shooter, hand, weapon, velocity);
    }
    public static void aimed() {
        ModServerBehaviors behaviors = behaviors();
        if (behaviors != null) behaviors.aimed();
    }
    /** A projectile an NPC did not launch enters the world. */
    public static boolean launching(ServerLevel level, Entity entity) {
        if (!(entity instanceof Projectile projectile)) return false;
        AnimusModRuntime active = runtime;
        ModServerBehaviors behaviors = active == null ? null : active.serverBehaviors();
        return behaviors != null && !active.helper(projectile) && behaviors.launching(projectile, level);
    }
    /** A real projectile hits; helpers resolve their own hits before this. */
    public static boolean projectileHit(Projectile projectile, HitResult hit) {
        ModServerBehaviors behaviors = projectile.level().isClientSide() ? null : behaviors();
        return behaviors != null && behaviors.projectileHit(projectile, hit);
    }
    /** Undoes a client's predicted change to one block, block entity data included. */
    public static void resendBlock(ServerPlayer player, BlockPos pos) { ModServerBehaviors.resendBlock(player, pos); }

    private static ModServerBehaviors behaviors() {
        AnimusModRuntime active = runtime;
        return active == null ? null : active.serverBehaviors();
    }
}
