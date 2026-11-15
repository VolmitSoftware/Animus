package art.arcane.animus.bukkit.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

/**
 * A player an NPC kills is slain or shot by that NPC. Spigot's death event takes the message as text, which is what
 * runs here; Paper's text component path runs on a Paper server.
 */
class NpcKillsTest {
    private static <T> T stub(Class<T> type, String name) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "getDisplayName", "getName", "toString" -> name;
            case "hashCode" -> name.hashCode();
            case "equals" -> proxy == args[0];
            default -> null;
        }));
    }

    private static PlayerDeathEvent death(String message) {
        return new PlayerDeathEvent(stub(Player.class, "Steve"), stub(DamageSource.class, "hit"), new ArrayList<>(), 0, message);
    }

    @Test void aPlayerWhoDiesWhileAnNpcHitLandsIsSlainOrShotByThatNpc() {
        NpcKills kills = new NpcKills();
        PlayerDeathEvent slain = death("%1$s was slain by %2$s");
        NpcKills.landing("&cGuard", false, () -> kills.died(slain));
        assertEquals("Steve was slain by §cGuard", slain.getDeathMessage());
        PlayerDeathEvent shot = death("%1$s was shot by %2$s");
        NpcKills.landing("Archer", true, () -> kills.died(shot));
        assertEquals("Steve was shot by Archer", shot.getDeathMessage());
    }

    @Test void otherDeathsKeepTheirMessage() {
        PlayerDeathEvent fell = death("Steve fell from a high place");
        new NpcKills().died(fell);
        assertEquals("Steve fell from a high place", fell.getDeathMessage());
    }
}
