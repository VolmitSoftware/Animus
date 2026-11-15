package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.virtual.Vec3;
import java.util.UUID;
import org.bukkit.entity.Projectile;

/** Hears each real projectile combat stops in an NPC's body, on the projectile's owner, as the strike lands. */
@FunctionalInterface
public interface ProjectileStrikes {
    ProjectileStrikes NONE = (Projectile projectile, UUID npc, Vec3 point, boolean spent) -> { };

    /** {@code spent} when the projectile goes no further. */
    void struck(Projectile projectile, UUID npc, Vec3 point, boolean spent);
}
