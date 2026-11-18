package art.arcane.animus.core.command;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@code /animus combat}, {@code attack} and {@code target} reach on a platform. Each answer is one reply line; a
 * refusal throws with its reason. Called on the actor's owner.
 */
public interface CombatControl {
    /**
     * The operations {@link #npc} takes. {@code target} needs a target; {@code guard} without one stops guarding;
     * {@code revive} brings a dead NPC back now, or heals a living one.
     */
    enum Operation { STATUS, ENGAGE, DISENGAGE, RELEASE, TARGET, GUARD, REVIVE }

    String status();

    String npc(String npc, Operation operation, Optional<UUID> target);

    /** One manual hit, checked like any NPC attack. */
    String attack(String npc, UUID target, double damage);

    /** Lends a mob to the NPC's control. */
    String control(String npc, UUID mob);

    /** Ends every mob control the NPC holds. */
    String releaseMobs(String npc);

    /**
     * One manual hit's answer in words: {@code guard hit for 2 damage; health 20 to 18}, or why it did nothing, such as
     * {@code guard's hit did nothing: out of reach}. {@code status} is the platform's code for the outcome.
     */
    static String attacked(String npc, String status, double applied, Double before, Double after) {
        if (applied > 0) return npc + " hit for " + number(applied) + " damage" + (before == null || after == null ? "" : "; health " + number(before) + " to " + number(after));
        int space = status.indexOf(' ');
        String code = space < 0 ? status : status.substring(0, space), detail = space < 0 ? "" : status.substring(space + 1);
        return npc + "'s hit did nothing: " + (code.equals("APPLIED") || code.equals("NO_CHANGE") ? "the target took no damage" : art.arcane.animus.core.text.PlainWords.of(code))
                + (detail.isEmpty() ? "" : ", " + detail);
    }

    private static String number(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 100) / 100.0); }
}
