package art.arcane.animus.mod;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The projectile hooks require their targets, so a renamed or reshaped method would stop the mod loading. These are
 * the exact methods the mixins inject into, looked up on this loader's game classes without initialising them.
 */
class ProjectileHookTargetsTest {
    private static Class<?> game(String name) throws ClassNotFoundException { return Class.forName(name, false, ProjectileHookTargetsTest.class.getClassLoader()); }

    @Test void everyProjectileHookTargetExistsOnThisLoader() throws ReflectiveOperationException {
        Class<?> level = game("net.minecraft.server.level.ServerLevel"), entity = game("net.minecraft.world.entity.Entity");
        Class<?> living = game("net.minecraft.world.entity.LivingEntity"), stack = game("net.minecraft.world.item.ItemStack");
        assertEquals(boolean.class, level.getMethod("addFreshEntity", entity).getReturnType());
        game("net.minecraft.world.item.ProjectileWeaponItem").getDeclaredMethod("shoot", level, living, game("net.minecraft.world.InteractionHand"), stack, List.class,
                float.class, float.class, boolean.class, living);
        game("net.minecraft.world.entity.monster.skeleton.AbstractSkeleton").getDeclaredMethod("performRangedAttack", living, float.class);
        game("net.minecraft.world.entity.projectile.Projectile").getDeclaredMethod("hitTargetOrDeflectSelf", game("net.minecraft.world.phys.HitResult"));
    }
}
