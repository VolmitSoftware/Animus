package art.arcane.animus.bukkit.combat;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * The {@code teams} target rule's soft hooks, read the way Sentinel's integrations read them. {@code sbteam} is the main
 * scoreboard's team. {@code towny}, {@code nation}, {@code nationenemies} and {@code nationallies} ask Towny's
 * {@code TownyUniverse}. {@code factions}, {@code factionsenemy} and {@code factionsally} ask FactionsUUID's
 * {@code FPlayers} or MassiveCraft Factions' {@code MPlayer}, whichever is installed. A plugin that is absent, or whose
 * API throws, matches nothing. Reached by reflection, so nothing is needed at build time.
 */
public final class PluginTeams {
    private final Server server;
    private final ClassLoader towny, factions;

    public PluginTeams(Server server) {
        this.server = Objects.requireNonNull(server);
        towny = loader("Towny");
        factions = loader("Factions");
    }

    public boolean member(Player player, String kind, String name) {
        try {
            return switch (kind.toLowerCase(Locale.ROOT)) {
                case "sbteam" -> scoreboard(player, name);
                case "towny" -> towny != null && townyTown(player.getUniqueId(), name);
                case "nation" -> towny != null && townyNation(player.getUniqueId(), name, "member");
                case "nationenemies" -> towny != null && townyNation(player.getUniqueId(), name, "hasEnemy");
                case "nationallies" -> towny != null && townyNation(player.getUniqueId(), name, "hasAlly");
                case "factions" -> factions != null && faction(player, name, null);
                case "factionsenemy" -> factions != null && faction(player, name, "ENEMY");
                case "factionsally" -> factions != null && faction(player, name, "ALLY");
                default -> false;
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    private boolean scoreboard(Player player, String name) {
        ScoreboardManager manager = server.getScoreboardManager();
        Scoreboard main = manager == null ? null : manager.getMainScoreboard();
        Team team = main == null ? null : main.getTeam(name);
        return team != null && team.hasEntry(player.getName());
    }

    private Object universe() throws ReflectiveOperationException {
        return Class.forName("com.palmergames.bukkit.towny.TownyUniverse", true, towny).getMethod("getInstance").invoke(null);
    }
    private boolean townyTown(UUID player, String name) throws ReflectiveOperationException {
        Object universe = universe();
        Object town = call(universe, "getTown", name);
        Object resident = call(universe, "getResident", player);
        return town != null && resident != null && Boolean.TRUE.equals(call(town, "hasResident", resident));
    }
    /** {@code member}: the player's nation is this one; otherwise the player's nation has this one as an enemy or ally. */
    private boolean townyNation(UUID player, String name, String relation) throws ReflectiveOperationException {
        Object universe = universe();
        Object nation = call(universe, "getNation", name);
        Object resident = call(universe, "getResident", player);
        if (nation == null || resident == null) return false;
        if (relation.equals("member")) return Boolean.TRUE.equals(call(nation, "hasResident", resident));
        Object own = call(resident, "getNationOrNull");
        return own != null && Boolean.TRUE.equals(call(own, relation, nation));
    }

    /** The player is in the named faction, or with {@code relation}, the named faction holds that relation to the player's. */
    private boolean faction(Player player, String name, String relation) throws ReflectiveOperationException {
        Optional<Object[]> found = factionsUuid(player, name).or(() -> massive(player, name));
        if (found.isEmpty()) return false;
        Object named = found.get()[0], own = found.get()[1];
        if (named == null || own == null) return false;
        if (relation == null) return named.equals(own);
        Object held = call(named, "getRelationTo", own);
        return held != null && held.toString().equalsIgnoreCase(relation);
    }
    private Optional<Object[]> factionsUuid(Player player, String name) {
        try {
            Object players = Class.forName("com.massivecraft.factions.FPlayers", true, factions).getMethod("getInstance").invoke(null);
            Object factionsIndex = Class.forName("com.massivecraft.factions.Factions", true, factions).getMethod("getInstance").invoke(null);
            Object own = call(call(players, "getByPlayer", player), "getFaction");
            return Optional.of(new Object[] {call(factionsIndex, "getByTag", name), own});
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) { return Optional.empty(); }
    }
    private Optional<Object[]> massive(Player player, String name) {
        try {
            Object collection = Class.forName("com.massivecraft.factions.entity.FactionColl", true, factions).getMethod("get").invoke(null);
            Object member = Class.forName("com.massivecraft.factions.entity.MPlayer", true, factions).getMethod("get", Object.class).invoke(null, player.getUniqueId());
            return Optional.of(new Object[] {call(collection, "getByName", name), call(member, "getFaction")});
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) { return Optional.empty(); }
    }

    /** Calls the public method of this name whose parameters accept the arguments. */
    private static Object call(Object target, String method, Object... arguments) throws ReflectiveOperationException {
        if (target == null) return null;
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) continue;
            Class<?>[] types = candidate.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < types.length && fits; i++) fits = arguments[i] == null || wrap(types[i]).isInstance(arguments[i]);
            if (fits) return candidate.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + method);
    }
    private static Class<?> wrap(Class<?> type) { return type == boolean.class ? Boolean.class : type == int.class ? Integer.class : type; }

    private ClassLoader loader(String name) {
        org.bukkit.plugin.Plugin plugin = server.getPluginManager().getPlugin(name);
        return plugin == null ? null : plugin.getClass().getClassLoader();
    }
}
