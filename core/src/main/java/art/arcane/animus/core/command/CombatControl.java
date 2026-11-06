package art.arcane.animus.core.command;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@code /animus combat}, {@code attack} and {@code target} reach on a platform. Each answer is one reply line; a
 * refusal throws with its reason. Called on the actor's owner.
 */
public interface CombatControl {
    /** The operations {@link #npc} takes. {@code target} needs a target; {@code guard} without one stops guarding. */
    enum Operation { STATUS, ENGAGE, DISENGAGE, RELEASE, TARGET, GUARD }

    String status();

    String npc(String npc, Operation operation, Optional<UUID> target);

    /** One manual hit, checked like any NPC attack. */
    String attack(String npc, UUID target, double damage);

    /** Lends a mob to the NPC's control. */
    String control(String npc, UUID mob);

    /** Ends every mob control the NPC holds. */
    String releaseMobs(String npc);
}
