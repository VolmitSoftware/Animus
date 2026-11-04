package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionAuthorityTest {
    @Test void equalContentReloadKeepsAuthorityBeforeReconciliation() throws Exception {
        var initial=DefinitionQueueTest.definition("guard","Guard",1);var authority=new DefinitionAuthority(initial);
        var reloaded=NpcDefinition.parse(initial.serialize());assertNotSame(initial,reloaded);
        assertTrue(authority.accepts(reloaded));assertTrue(authority.accepts(reloaded));assertTrue(authority.accepts(initial));
    }
    @Test void actualEditsAndDeletionRevokeEvenWhenOriginalValueIsStillVisible() throws Exception {
        var initial=DefinitionQueueTest.definition("guard","Guard",1);var authority=new DefinitionAuthority(initial);
        assertFalse(authority.accepts(DefinitionQueueTest.definition("guard","Changed",2)));
        assertFalse(authority.accepts(null));assertFalse(authority.accepts(null));
        assertTrue(authority.accepts(NpcDefinition.parse(initial.serialize())));
    }
}
