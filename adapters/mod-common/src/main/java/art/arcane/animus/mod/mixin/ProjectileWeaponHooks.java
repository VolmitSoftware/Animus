package art.arcane.animus.mod.mixin;

import art.arcane.animus.mod.ModHooks;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bow and crossbow shots of players and crossbow mobs: the projectiles spawned inside belong to this shot. */
@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponHooks {
    @Inject(method = "shoot", at = @At("HEAD"))
    private void animus$aiming(ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles, float velocity,
            float inaccuracy, boolean critical, LivingEntity target, CallbackInfo callback) {
        ModHooks.aiming(shooter, hand, weapon, velocity);
    }

    @Inject(method = "shoot", at = @At("RETURN"))
    private void animus$aimed(ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles, float velocity,
            float inaccuracy, boolean critical, LivingEntity target, CallbackInfo callback) {
        ModHooks.aimed();
    }
}
