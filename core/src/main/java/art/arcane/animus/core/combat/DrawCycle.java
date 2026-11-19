package art.arcane.animus.core.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * How a drawn weapon is used from shot to shot, as a player, skeleton, pillager or drowned uses it. A bow is drawn for a
 * full draw, let go with the shot, lowered while it rests and drawn again, so the next draw ends as the cooldown does. A
 * crossbow is charged with its loading sounds, held loaded and aimed for a moment, fired, and charged again after the
 * shot. A trident is raised for half a second and thrown. Any other weapon is thrown with a swing and never drawn. Owned
 * by one brain on the engine's thread.
 */
final class DrawCycle {
    /** How the weapon is used, and how long it is drawn, charged or raised before it can be let go. */
    enum Style {
        BOW(20), CROSSBOW(25), TRIDENT(10), THROWN(0);
        private final int windup;
        Style(int windup) { this.windup = windup; }
    }
    /** What viewers hear: a sound id in a sound category, {@code players} or {@code hostile}, with vanilla's volume and pitch. */
    record Sound(String id, String category, float volume, float pitch) {}
    /** One aiming turn: whether the weapon may be let go now, the sounds it made, and whether a crossbow became loaded. */
    record Turn(boolean ready, List<Sound> sounds, boolean loaded) {}

    /** Ticks a let-go weapon stays lowered at least before the next draw, so viewers see the release. */
    static final int RELEASE_TICKS = 6;
    /** Ticks a loaded crossbow is held aimed at least before it fires. */
    static final int AIM_TICKS = 6;
    /** Each Quick Charge level takes this many ticks off a crossbow's charge, as in vanilla. */
    private static final int QUICK_CHARGE_TICKS = 5;
    private static final long NONE = Long.MIN_VALUE;
    private static final Set<String> SKELETONS = Set.of("minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:bogged", "minecraft:parched");

    private final String body;
    /** The sound category of the body: a player's weapon sounds as a player's, any other body's as a hostile mob's. */
    private final String category;
    private Style style = Style.THROWN;
    private int windup;
    private int quickCharge;
    /** The tick the draw or charge began, or {@link #NONE} while the weapon is down. */
    private long since = NONE;
    /** The tick a crossbow became loaded, or {@link #NONE} while it is empty. */
    private long loadedAt = NONE;
    /** The earliest tick the next draw may begin. */
    private long rest = NONE;
    /** The crossbow loading sounds this charge has played: 0, the start, or the start and the middle. */
    private int chargeSounds;

    /** {@code body} is the NPC's body type, whose own shot sound a skeleton or drowned makes. */
    DrawCycle(String body) {
        this.body = body;
        category = body.equals(CombatLoadout.PLAYER) ? "players" : "hostile";
    }

    /** The weapon now in use. Another weapon lowers and empties the last; true when that emptied a loaded crossbow. */
    boolean use(Style next, int quickChargeLevel) {
        int level = Math.max(0, quickChargeLevel);
        if (next == style && level == quickCharge) return false;
        boolean emptied = end();
        style = next;
        quickCharge = level;
        windup = next == Style.CROSSBOW ? Math.max(0, Style.CROSSBOW.windup - QUICK_CHARGE_TICKS * level) : next.windup;
        return emptied;
    }

    /** Whether viewers see the weapon drawn, charged or raised now. */
    boolean drawing() { return since != NONE; }
    /** Whether a crossbow is loaded, which viewers see as the arrow on it and the body holding it aimed. */
    boolean loaded() { return loadedAt != NONE; }

    /** A turn that could let go now: begins or continues the draw or charge, and says whether the weapon is ready. */
    Turn aim(long now, RandomGenerator random) {
        if (style == Style.THROWN) return new Turn(true, List.of(), false);
        if (loadedAt != NONE) return new Turn(now - loadedAt >= AIM_TICKS, List.of(), false);
        if (since == NONE) {
            if (now < rest) return new Turn(false, List.of(), false);
            since = now;
            chargeSounds = 0;
        }
        long elapsed = now - since;
        if (style != Style.CROSSBOW) return new Turn(elapsed >= windup, List.of(), false);
        List<Sound> sounds = new ArrayList<>(3);
        // Vanilla plays the start sound a fifth of the way through the charge and the middle sound halfway.
        double progress = windup == 0 ? 1 : (double) elapsed / windup;
        if (chargeSounds < 1 && progress >= .2) {
            chargeSounds = 1;
            sounds.add(new Sound(quickCharge == 0 ? "minecraft:item.crossbow.loading_start" : "minecraft:item.crossbow.quick_charge_" + Math.min(3, quickCharge), category, .5f, 1));
        }
        if (chargeSounds < 2 && progress >= .5) {
            chargeSounds = 2;
            sounds.add(new Sound("minecraft:item.crossbow.loading_middle", category, .5f, 1));
        }
        if (elapsed < windup) return new Turn(false, sounds, false);
        since = NONE;
        loadedAt = now;
        sounds.add(new Sound("minecraft:item.crossbow.loading_end", category, 1, 1 / (random.nextFloat() * .5f + 1) + .2f));
        return new Turn(AIM_TICKS == 0, sounds, true);
    }

    /**
     * The shot left: the weapon is lowered, a crossbow emptied, and the next draw waits for the release to show and, for a
     * bow or trident, for the rest of the cooldown. Returns the sound of the shot; a thrown weapon makes none here.
     */
    Optional<Sound> release(long now, int cooldownTicks, RandomGenerator random) {
        since = NONE;
        loadedAt = NONE;
        chargeSounds = 0;
        rest = now + (style == Style.CROSSBOW ? RELEASE_TICKS : Math.max(RELEASE_TICKS, cooldownTicks - windup));
        return switch (style) {
            case BOW -> Optional.of(SKELETONS.contains(body) ? new Sound("minecraft:entity.skeleton.shoot", category, 1, 1 / (random.nextFloat() * .4f + .8f))
                    : new Sound("minecraft:entity.arrow.shoot", category, 1, 1 / (random.nextFloat() * .4f + 1.2f) + .5f));
            case CROSSBOW -> Optional.of(new Sound("minecraft:item.crossbow.shoot", category, 1, 1));
            case TRIDENT -> Optional.of(body.equals("minecraft:drowned") ? new Sound("minecraft:entity.drowned.shoot", category, 1, 1 / (random.nextFloat() * .4f + .8f))
                    : new Sound("minecraft:item.trident.throw", category, 1, 1));
            case THROWN -> Optional.empty();
        };
    }

    /** A turn that could not let go, as when the target is out of reach or hidden: a draw or charge stops; a loaded crossbow stays loaded. */
    void lower(long now) {
        if (since == NONE) return;
        since = NONE;
        rest = Math.max(rest, now + RELEASE_TICKS);
    }

    /** The fight is over or the weapon changed: lowered and emptied. True when a loaded crossbow was emptied. */
    boolean end() {
        boolean emptied = loadedAt != NONE;
        since = NONE;
        loadedAt = NONE;
        rest = NONE;
        chargeSounds = 0;
        return emptied;
    }
}
