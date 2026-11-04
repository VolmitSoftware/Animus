package art.arcane.animus.core.combat;

/** Where an NPC is in its combat cycle. Runtime only; never written into definitions. */
public enum CombatState { IDLE, GUARDING, PURSUING, ATTACKING, AVOIDING, RETURNING, DEAD }
