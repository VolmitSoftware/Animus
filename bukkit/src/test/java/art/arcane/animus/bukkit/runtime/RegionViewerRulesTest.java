package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.control.LookClose;
import com.google.gson.JsonParser;
import java.lang.reflect.Proxy;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folia viewer rules that read a player directly: who a look-close NPC may face, and which permissions a deny filter counts. */
class RegionViewerRulesTest {
    /** An operator who has no node set: every unregistered node answers true, and none is set. */
    private static Player player(boolean dead) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "isDead" -> dead;
            case "hasPermission" -> true;
            case "isPermissionSet" -> false;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void aDeadPlayerIsNeverALookCloseCandidate() {
        LookClose look = LookClose.parse(JsonParser.parseString("{\"modules\": {\"animus:look-close\": {\"range\": 8}}}").getAsJsonObject()).orElseThrow();
        assertTrue(RegionActor.lookCandidate(player(false), look));
        assertFalse(RegionActor.lookCandidate(player(true), look), "a player waiting to respawn is not looked at");
    }

    @Test
    void anOperatorsDefaultGrantIsNotASetPermission() {
        assertFalse(BukkitRuntimeHost.setAndHeld(player(false), "group.outcasts"), "an operator is not in every group a deny filter lists");
    }
}
