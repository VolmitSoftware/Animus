package art.arcane.animus.core.runtime;

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
    @Test void aPluginModuleOrRevisionChangeKeepsTheLiveBodyButAnEngineModuleChangeDoesNot() throws Exception {
        var initial=DefinitionQueueTest.definition("guard","Guard",1);var authority=new DefinitionAuthority(initial);
        com.google.gson.JsonObject counted=initial.json();counted.addProperty("revision",7);
        com.google.gson.JsonObject modules=new com.google.gson.JsonObject();com.google.gson.JsonObject clicks=new com.google.gson.JsonObject();clicks.addProperty("count",3);
        modules.add("demo:clicks",clicks);counted.add("modules",modules);
        NpcDefinition pluginOnly=NpcDefinition.parse(counted.toString());
        assertTrue(authority.accepts(pluginOnly),"plugin module data and the revision are not shown");
        com.google.gson.JsonObject seated=counted.deepCopy();com.google.gson.JsonObject seat=new com.google.gson.JsonObject();seat.addProperty("enabled",true);
        seated.getAsJsonObject("modules").add("animus:seat",seat);
        assertFalse(authority.accepts(NpcDefinition.parse(seated.toString())),"an engine module changes the body");
        assertNotEquals(initial.runtimeForm(),NpcDefinition.parse(seated.toString()).runtimeForm());
        assertEquals(initial.runtimeForm(),pluginOnly.runtimeForm());
    }
}
