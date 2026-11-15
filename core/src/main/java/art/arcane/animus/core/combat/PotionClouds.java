package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Potions NPCs threw, as vanilla splash and lingering potions work. A splash reaches every participant within four
 * blocks of where it broke, weaker with distance, and the one it struck in full. A lingering potion leaves a cloud of
 * three blocks that shrinks over thirty seconds and applies a quarter of the effect to each participant inside at most
 * once a second. Harmful effects reach only foes and ask protection; helpful ones reach only friends. Owner thread only.
 */
final class PotionClouds {
    static final double SPLASH_RADIUS = 4, CLOUD_RADIUS = 3;
    static final int CLOUD_TICKS = 600, REAPPLY_TICKS = 20, CHECK_TICKS = 5, MAXIMUM_CLOUDS = 64;
    private static final int TARGETS_PER_CHECK = 32;

    private static final class Cloud {
        final HelperLedger.Shot shot;
        final UUID owner;
        final WorldPosition from, center;
        final long started;
        final Map<TargetRef, Long> next = new HashMap<>();
        Cloud(HelperLedger.Shot shot, UUID owner, WorldPosition from, WorldPosition center, long started) {
            this.shot = shot; this.owner = owner; this.from = from; this.center = center; this.started = started;
        }
    }

    private final CombatEngine engine;
    private final CombatWorld world;
    private final List<Cloud> clouds = new ArrayList<>();

    PotionClouds(CombatEngine engine, CombatWorld world) {
        this.engine = engine;
        this.world = world;
    }

    int size() { return clouds.size(); }
    void clear() { clouds.clear(); }

    /** A potion broke at {@code at}; {@code struck} is what it touched, which takes the full effect. */
    void broke(HelperLedger.Shot shot, CombatBrain owner, WorldPosition from, WorldPosition at, Optional<CombatTarget> struck) {
        CombatSettings.Effect effect = shot.effect().orElseThrow();
        boolean lingering = shot.flight().lingering();
        world.brokePotion(at, effect.id(), lingering, lingering ? CLOUD_RADIUS : SPLASH_RADIUS, lingering ? CLOUD_TICKS : 0);
        if (lingering) {
            if (clouds.size() < MAXIMUM_CLOUDS) clouds.add(new Cloud(shot, owner.npc(), from, at, engine.now()));
            return;
        }
        for (CombatTarget target : engine.around(at, SPLASH_RADIUS + 2, TARGETS_PER_CHECK)) {
            if (!CombatEngine.splashes(shot, owner, target)) continue;
            boolean direct = struck.isPresent() && struck.get().ref().equals(target.ref());
            double distance = CombatGeometry.center(target.bounds()).distance(at.point());
            if (!direct && distance >= SPLASH_RADIUS) continue;
            double intensity = direct ? 1 : 1 - distance / SPLASH_RADIUS;
            if (!instant(effect) && effect.durationTicks() * intensity <= 20) continue;
            engine.affect(owner, from, target, effect, intensity);
        }
    }

    void tick(long now) {
        if (clouds.isEmpty() || now % CHECK_TICKS != 0) return;
        for (Iterator<Cloud> each = clouds.iterator(); each.hasNext();) {
            Cloud cloud = each.next();
            long age = now - cloud.started;
            CombatBrain owner = engine.brain(cloud.owner);
            if (age >= CLOUD_TICKS || owner == null) { each.remove(); continue; }
            double radius = CLOUD_RADIUS * (1 - (double) age / CLOUD_TICKS);
            CombatSettings.Effect effect = cloud.shot.effect().orElseThrow();
            CombatSettings.Effect quarter = new CombatSettings.Effect(effect.id(), effect.amplifier(), Math.max(1, effect.durationTicks() / 4));
            for (CombatTarget target : engine.around(cloud.center, radius + 2, TARGETS_PER_CHECK)) {
                Vec3 feet = target.position().point(), middle = cloud.center.point();
                if (Math.hypot(feet.x() - middle.x(), feet.z() - middle.z()) > radius || Math.abs(feet.y() - middle.y()) > 2) continue;
                if (!CombatEngine.splashes(cloud.shot, owner, target)) continue;
                Long ready = cloud.next.get(target.ref());
                if (ready != null && now < ready) continue;
                cloud.next.put(target.ref(), now + REAPPLY_TICKS);
                engine.affect(owner, cloud.from, target, quarter, instant(effect) ? .5 : 1);
            }
        }
    }

    private static boolean instant(CombatSettings.Effect effect) { return effect.id().equals("minecraft:instant_health") || effect.id().equals("minecraft:instant_damage"); }
}
