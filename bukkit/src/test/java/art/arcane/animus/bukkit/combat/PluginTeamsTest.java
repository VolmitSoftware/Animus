package art.arcane.animus.bukkit.combat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The teams rule reads scoreboard teams, and an absent Towny or Factions matches nobody. */
class PluginTeamsTest {
    private interface Answer { Object of(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object self, Method method, Object[] args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName();
            return answer.of(method.getName(), args);
        }));
    }

    private static Server server(boolean scoreboards) {
        Team blue = proxy(Team.class, (String name, Object[] args) -> "hasEntry".equals(name) ? "Alex".equals(args[0]) : null);
        Scoreboard main = proxy(Scoreboard.class, (String name, Object[] args) -> "getTeam".equals(name) && "blue".equals(args[0]) ? blue : null);
        ScoreboardManager manager = proxy(ScoreboardManager.class, (String name, Object[] args) -> "getMainScoreboard".equals(name) ? main : null);
        PluginManager plugins = proxy(PluginManager.class, (String name, Object[] args) -> null);
        return proxy(Server.class, (String name, Object[] args) -> switch (name) {
            case "getScoreboardManager" -> scoreboards ? manager : null;
            case "getPluginManager" -> plugins;
            default -> null;
        });
    }
    private static Player player(String playerName) { return proxy(Player.class, (String name, Object[] args) -> "getName".equals(name) ? playerName : null); }

    @Test void aScoreboardTeamMatchesItsEntries() {
        PluginTeams teams = new PluginTeams(server(true));
        assertTrue(teams.member(player("Alex"), "sbteam", "blue"));
        assertFalse(teams.member(player("Sam"), "sbteam", "blue"));
        assertFalse(teams.member(player("Alex"), "sbteam", "red"));
    }
    @Test void absentPluginsAndScoreboardsMatchNobody() {
        PluginTeams teams = new PluginTeams(server(false));
        assertFalse(teams.member(player("Alex"), "sbteam", "blue"), "Folia has no main scoreboard");
        assertFalse(teams.member(player("Alex"), "towny", "Riverton"));
        assertFalse(teams.member(player("Alex"), "factionsenemy", "Red"));
        assertFalse(teams.member(player("Alex"), "unknown", "x"));
    }
}
