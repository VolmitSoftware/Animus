package art.arcane.animus.core.hotload;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLivenessTest {
    @Test void everyConfigKeyAppliesLive() {
        for (String key : new String[] {"statusMessage", "packetProofEnabled", "hotload.enabled", "npcs.viewDistance", "npcs.enabled", "npcs.packetsPerTick",
                "files.maximumOperations", "files.deliveryBudgetMillis", "modules.enabled", "modules.maximumInvocations", "behaviors.enabled",
                "behaviors.engine.fibers", "behaviors.catalog.maximumDocuments", "behaviors.triggers.enabled", "behaviors.state.flushMillis",
                "behaviors.actions.actionsPerTick", "behaviors.actions.commandBehaviors[0]", "combat.enabled", "combat.brainsPerTick",
                "builds.enabled", "builds.maximumVolume", "shops.flushMillis", "shops.messages.bought", "editor.listenerEnabled", "editor.port",
                "editor.relayUrl", "editor.allowedOrigins[1]", "rigs.updatesPerTick", "navigation.planningJobs",
                "recordings.enabled", "recordings.maximumTicks", "recordings.ghostColor", "recordings.maximumPreviews"})
            assertFalse(SettingsLiveness.restartRequired(key, true), key);
    }

    @Test void onlyTheNpcSwitchWaitsForARestartOnFolia() {
        assertTrue(SettingsLiveness.restartRequired("npcs.enabled", false));
        assertFalse(SettingsLiveness.restartRequired("npcs.viewDistance", false));
        assertFalse(SettingsLiveness.restartRequired("npcs.enabledX", false));
    }
}
