package art.arcane.animus.core.hire;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CombatOperation;
import art.arcane.animus.core.runtime.CombatModule;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Hiring through the combat module: a hire engages the NPC and guards the hirer; a dismissal stops and releases it. */
public final class CombatGuarding implements HireDesk.Guarding {
    private final CombatModule combat;

    public CombatGuarding(CombatModule combat) { this.combat = Objects.requireNonNull(combat); }

    @Override public void hire(UUID npc, UUID player) {
        combat.operate(npc, CombatOperation.ENABLE, Optional.empty());
        combat.operate(npc, CombatOperation.GUARD, Optional.of(player));
    }

    @Override public void dismiss(UUID npc) {
        combat.operate(npc, CombatOperation.GUARD, Optional.empty());
        combat.operate(npc, CombatOperation.RELEASE, Optional.empty());
    }

    @Override public boolean guards(UUID npc, UUID player) { return combat.guards(npc, player); }
}
