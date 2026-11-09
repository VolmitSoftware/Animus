package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatOperations;
import art.arcane.animus.core.combat.CombatState;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.command.CombatControl.Operation;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CombatRepliesTest {
    private static final UUID MOB = UUID.fromString("1234abcd-0000-4000-8000-000000000000");

    @Test void statusReadsAsASentence() {
        assertEquals("guard is idle, no target", CombatModule.status("guard", new CombatEngine.Status(UUID.randomUUID(), CombatState.IDLE, Optional.empty(), false, -1), CombatModule::shortWho));
        assertEquals("guard is attacking, targeting 1234abcd, disengaged, 12 ammunition", CombatModule.status("guard",
                new CombatEngine.Status(UUID.randomUUID(), CombatState.ATTACKING, Optional.of(new TargetRef(TargetKind.MOB, MOB)), true, 12), CombatModule::shortWho));
    }

    @Test void operationsNameTheNpcWithoutStateWords() {
        CombatOperations.Result applied = new CombatOperations.Result(CombatOperations.Status.APPLIED, "Guarding");
        assertEquals("guard is guarding baker", CombatModule.sentence("guard", Operation.GUARD, Optional.of("baker"), applied));
        assertEquals("guard stopped guarding", CombatModule.sentence("guard", Operation.GUARD, Optional.empty(), applied));
        assertEquals("Revived spare-2", CombatModule.sentence("spare-2", Operation.REVIVE, Optional.empty(), applied));
        assertEquals("guard can fight", CombatModule.sentence("guard", Operation.ENGAGE, Optional.empty(), applied));
        assertEquals("guard stopped fighting", CombatModule.sentence("guard", Operation.DISENGAGE, Optional.empty(), applied));
        assertEquals("guard targets 1234abcd", CombatModule.sentence("guard", Operation.TARGET, Optional.of("1234abcd"), applied));
        assertEquals("guard released its target", CombatModule.sentence("guard", Operation.RELEASE, Optional.empty(), applied));
        assertEquals("guard: NPC is dead", CombatModule.sentence("guard", Operation.ENGAGE, Optional.empty(), new CombatOperations.Result(CombatOperations.Status.DEAD, "NPC is dead")));
    }
}
