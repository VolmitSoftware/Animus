package art.arcane.animus.core.combat;

import java.util.Optional;
import java.util.UUID;

/**
 * Runtime control of a combat NPC, for behaviors and commands. Called on the owner thread. Nothing here
 * changes a definition or survives a despawn, and none of it overrides protection decisions.
 */
public interface CombatOperations {
    enum Status { APPLIED, NOT_COMBATANT, INVALID_TARGET, DEAD, UNAVAILABLE }
    record Result(Status status, String detail) {
        public boolean applied() { return status == Status.APPLIED; }
    }

    /** Clears a disengage so the NPC targets by its rules again. */
    Result engage(UUID npc);
    /** Drops every target, cancels shots in flight and stops rule targeting until engage. */
    Result disengage(UUID npc);
    /** Attacks this target regardless of rules, within leash and give-up range. */
    Result target(UUID npc, TargetRef target);
    /** Drops the current target. Rule targeting continues. */
    Result release(UUID npc);
    /** Guards this participant, or stops guarding when empty. Replaces any guard from settings until despawn. */
    Result guard(UUID npc, Optional<TargetRef> guarded);
}
