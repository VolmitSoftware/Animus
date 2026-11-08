package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fire and lava for NPCs that list them in their sources. Each such NPC checks what its body touches about every
 * {@link #CHECK_TICKS} ticks, so one turn costs a bounded block read per NPC. As in vanilla, lava deals 4 and fire 1 at
 * each check, both set the body burning (15 and 8 seconds), a burning body takes 1 a second once out of them, and water
 * puts it out. Lava and fire are reduced by armor; the burn is not. Owner thread only.
 */
public final class Hazards {
    static final int CHECK_TICKS = 10;
    static final int LAVA_BURN_TICKS = 300, FIRE_BURN_TICKS = 160, BURN_INTERVAL = 20;
    static final double LAVA_DAMAGE = 4, FIRE_DAMAGE = 1, BURN_DAMAGE = 1;
    /** The most block cells one contact check reads; a larger body reads only the cells nearest its feet. */
    static final int MAXIMUM_CELLS = 128;

    /** One block's contribution to what the body touches. */
    @FunctionalInterface public interface Cells { CombatWorld.Contact at(int x, int y, int z); }

    private static final class Exposure {
        final CombatBrain brain;
        long checkedAt, nextBurn;
        int burnTicks;
        boolean shown;
        Exposure(CombatBrain brain, long now) { this.brain = brain; checkedAt = now; }
    }

    private final CombatEngine engine;
    private final CombatWorld world;
    private final Map<UUID, Exposure> exposures = new HashMap<>();
    private final ArrayDeque<Exposure> order = new ArrayDeque<>();

    Hazards(CombatEngine engine, CombatWorld world) { this.engine = engine; this.world = world; }

    /** Watches the NPC when its defense takes fire or lava; forgets it otherwise. */
    void watch(CombatBrain brain, long now) {
        forget(brain.npc());
        HealthSettings.Defense defense = brain.settings().health().defense();
        if (!defense.takes(HealthSettings.DamageSource.FIRE) && !defense.takes(HealthSettings.DamageSource.LAVA)) return;
        Exposure exposure = new Exposure(brain, now);
        exposures.put(brain.npc(), exposure);
        order.addLast(exposure);
    }
    void forget(UUID npc) {
        Exposure gone = exposures.remove(npc);
        if (gone != null) order.remove(gone);
    }
    void clear() { exposures.clear(); order.clear(); }
    int size() { return exposures.size(); }

    /** A fire projectile set the NPC alight; only an NPC that takes fire burns. */
    void ignite(UUID npc, int ticks) {
        Exposure exposure = exposures.get(npc);
        if (exposure != null && ticks > 0 && exposure.brain.settings().health().defense().takes(HealthSettings.DamageSource.FIRE))
            exposure.burnTicks = Math.max(exposure.burnTicks, ticks);
    }

    /** Checks a share of the watched NPCs, so each is checked once every {@link #CHECK_TICKS} ticks. */
    void tick(long now) {
        int visits = (order.size() + CHECK_TICKS - 1) / CHECK_TICKS;
        for (int i = 0; i < visits && !order.isEmpty(); i++) {
            Exposure exposure = order.pollFirst();
            order.addLast(exposure);
            if (now - exposure.checkedAt >= CHECK_TICKS) check(exposure, now);
        }
    }

    private void check(Exposure exposure, long now) {
        long elapsed = Math.max(0, now - exposure.checkedAt);
        exposure.checkedAt = now;
        CombatBrain brain = exposure.brain;
        if (brain.retired() || brain.state() == CombatState.DEAD) {
            exposure.burnTicks = 0;
            show(exposure);
            return;
        }
        HealthSettings.Defense defense = brain.settings().health().defense();
        switch (world.contact(brain.npc())) {
            case WATER -> exposure.burnTicks = 0;
            case LAVA -> {
                exposure.burnTicks = Math.max(exposure.burnTicks, LAVA_BURN_TICKS);
                engine.environment(brain, HealthSettings.DamageSource.LAVA, LAVA_DAMAGE, true);
            }
            case FIRE -> {
                if (defense.takes(HealthSettings.DamageSource.FIRE)) {
                    exposure.burnTicks = Math.max(exposure.burnTicks, FIRE_BURN_TICKS);
                    engine.environment(brain, HealthSettings.DamageSource.FIRE, FIRE_DAMAGE, true);
                }
            }
            case NONE -> {
                if (exposure.burnTicks > 0) {
                    exposure.burnTicks = (int) Math.max(0, exposure.burnTicks - elapsed);
                    if (now >= exposure.nextBurn && defense.takes(HealthSettings.DamageSource.FIRE)) {
                        exposure.nextBurn = now + BURN_INTERVAL;
                        engine.environment(brain, HealthSettings.DamageSource.FIRE, BURN_DAMAGE, false);
                    }
                }
            }
        }
        if (brain.state() == CombatState.DEAD) exposure.burnTicks = 0;
        show(exposure);
    }

    private void show(Exposure exposure) {
        boolean burning = exposure.burnTicks > 0;
        if (burning == exposure.shown) return;
        exposure.shown = burning;
        world.burning(exposure.brain.npc(), burning);
    }

    /**
     * What a body with these bounds touches, reading each block cell it overlaps: lava wins over water, and water over fire.
     * At most {@link #MAXIMUM_CELLS} cells are read, from the feet up.
     */
    public static CombatWorld.Contact contact(Hitbox bounds, Cells cells) {
        double inset = .001;
        int minX = (int) Math.floor(bounds.min().x() + inset), maxX = (int) Math.floor(bounds.max().x() - inset);
        int minY = (int) Math.floor(bounds.min().y() + inset), maxY = (int) Math.floor(bounds.max().y() - inset);
        int minZ = (int) Math.floor(bounds.min().z() + inset), maxZ = (int) Math.floor(bounds.max().z() - inset);
        boolean water = false, fire = false;
        int read = 0;
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++) {
                    if (read++ >= MAXIMUM_CELLS) return water ? CombatWorld.Contact.WATER : fire ? CombatWorld.Contact.FIRE : CombatWorld.Contact.NONE;
                    switch (cells.at(x, y, z)) {
                        case LAVA -> { return CombatWorld.Contact.LAVA; }
                        case WATER -> water = true;
                        case FIRE -> fire = true;
                        case NONE -> { }
                    }
                }
        return water ? CombatWorld.Contact.WATER : fire ? CombatWorld.Contact.FIRE : CombatWorld.Contact.NONE;
    }
}
