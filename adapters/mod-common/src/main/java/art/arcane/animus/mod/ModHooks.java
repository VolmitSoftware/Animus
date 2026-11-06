package art.arcane.animus.mod;

import net.minecraft.world.phys.HitResult;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
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
}
