package art.arcane.animus.core.hotload;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLivenessTest {
    @Test void liveKeysTakeEffectAtOnce() {
        for (String key : new String[] {"statusMessage", "hotload.enabled", "hotload.sound", "shops.enabled", "shops.economy", "shops.priceFormat",
                "shops.messages.bought", "behaviors.actions.commandBehaviors", "behaviors.actions.commandBehaviors[0]",
                "behaviors.actions.commandNamespaces", "behaviors.actions.choiceLifetimeSeconds"})
            assertTrue(SettingsLiveness.live(key), key);
    }

    @Test void startupBudgetsAndTransportsNeedARestart() {
        for (String key : new String[] {"packetProofEnabled", "npcs.viewDistance", "npcs.enabled", "files.maximumOperations", "modules.enabled",
                "behaviors.engine.fibers", "behaviors.actions.actionsPerTick", "combat.enabled", "builds.maximumVolume", "shops.flushMillis",
                "editor.listenerEnabled", "editor.relayUrl", "rigs.updatesPerTick", "navigation.planningJobs", "statusMessageX"})
            assertTrue(SettingsLiveness.restartRequired(key), key);
    }
}
