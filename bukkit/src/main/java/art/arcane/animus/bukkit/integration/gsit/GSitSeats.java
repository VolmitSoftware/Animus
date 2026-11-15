package art.arcane.animus.bukkit.integration.gsit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * GSit's player-sit settings for seats on Animus NPCs. GSit never sees a click on an Animus NPC, since NPCs are packets,
 * so Animus asks GSit the questions its own player-sit handler asks before sitting one player on another: may this
 * player sit on NPCs, has either side turned player-sit off, is the world and region allowed, is the hand empty, is the
 * NPC close enough and is the stack full. GSit is read by reflection while it runs; a GSit that cannot be read leaves
 * Animus's own rules alone and says so once. Main thread.
 */
public final class GSitSeats {
    private static final String API = "dev.geco.gsit.api.GSitAPI";
    private final PluginManager plugins;
    private final java.util.function.Consumer<String> warn;
    private Plugin bound;
    private Bound api;
    private boolean warned;

    private record Bound(Object main, Method canUsePlayerSit, Object config, Field allowNpc, Field emptyHand, Field maxStack, Field maxDistance,
                         Object permissions, Method hasPermission, Object environment, Method allowedWorld, Method allowedLocation,
                         Object crawl, Method crawling, Object poses, Method posing) {}

    public GSitSeats(PluginManager plugins, java.util.function.Consumer<String> warn) { this.plugins = plugins; this.warn = warn; }

    /**
     * Null allows the seat; an empty reason refuses it silently, as GSit refuses a player-sit without a message.
     * {@code stacked} is how many players already sit on the NPC.
     */
    public String refusal(Player player, Location npc, double npcHeight, int stacked) {
        Bound gsit = bind();
        if (gsit == null) return null;
        try {
            if (!gsit.allowNpc().getBoolean(gsit.config())) return "";
            if (!(boolean) gsit.hasPermission().invoke(gsit.permissions(), player, new String[]{"PlayerSit", "PlayerSit.*"})) return "";
            if (!(boolean) gsit.allowedWorld().invoke(gsit.environment(), (Entity) player)) return "";
            if (gsit.emptyHand().getBoolean(gsit.config()) && !player.getInventory().getItemInMainHand().getType().isAir()) return "";
            if (!(boolean) gsit.canUsePlayerSit().invoke(null, player.getUniqueId())) return "";
            if ((boolean) gsit.crawling().invoke(gsit.crawl(), player)) return "";
            double distance = gsit.maxDistance().getDouble(gsit.config());
            if (distance > 0 && npc.clone().add(0, npcHeight / 2, 0).distanceSquared(player.getLocation().add(0, player.getHeight() / 2, 0)) > distance * distance) return "";
            if (!(boolean) gsit.allowedLocation().invoke(gsit.environment(), npc, player, "playersit")) return "sitting here is not allowed";
            long maximum = gsit.maxStack().getLong(gsit.config());
            // GSit counts the target and everyone already in its stack.
            if (maximum > 0 && maximum <= stacked + 1L) return "its seats are full";
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unreadable(failure);
            return null;
        }
    }

    /** GSit holds the player crawling or in one of its poses. */
    public boolean posed(Player player) {
        Bound gsit = bind();
        if (gsit == null) return false;
        try { return (boolean) gsit.crawling().invoke(gsit.crawl(), player) || (boolean) gsit.posing().invoke(gsit.poses(), player); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { unreadable(failure); return false; }
    }

    /** Whether GSit runs now, for status. */
    public boolean running() { Plugin plugin = plugins.getPlugin("GSit"); return plugin != null && plugin.isEnabled(); }

    private Bound bind() {
        Plugin plugin = plugins.getPlugin("GSit");
        if (plugin == null || !plugin.isEnabled()) { bound = null; api = null; return null; }
        if (plugin == bound) return api;
        bound = plugin;
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> gsit = Class.forName(API, true, loader);
            Object main = gsit.getMethod("getInstance").invoke(null);
            Method canUse = gsit.getMethod("canPlayerUsePlayerSit", UUID.class);
            Object config = main.getClass().getMethod("getConfigService").invoke(main);
            Class<?> configs = config.getClass();
            Object permissions = main.getClass().getMethod("getPermissionService").invoke(main);
            Object environment = main.getClass().getMethod("getEnvironmentUtil").invoke(main);
            Object crawl = main.getClass().getMethod("getCrawlService").invoke(main);
            Object poses = main.getClass().getMethod("getPoseService").invoke(main);
            api = new Bound(main, canUse, config, configs.getField("PS_ALLOW_SIT_NPC"), configs.getField("PS_EMPTY_HAND_ONLY"), configs.getField("PS_MAX_STACK"),
                    configs.getField("PS_MAX_DISTANCE"), permissions, permissions.getClass().getMethod("hasPermission", Permissible.class, String[].class),
                    environment, environment.getClass().getMethod("isEntityInAllowedWorld", Entity.class),
                    environment.getClass().getMethod("canUseInLocation", Location.class, Player.class, String.class),
                    crawl, crawl.getClass().getMethod("isPlayerCrawling", Player.class), poses, poses.getClass().getMethod("isPlayerPosing", Player.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            api = null;
            unreadable(failure);
        }
        return api;
    }

    private void unreadable(Throwable failure) {
        if (warned) return;
        warned = true;
        warn.accept("GSit " + (bound == null ? "" : bound.getDescription().getVersion() + " ") + "cannot be read, so its player-sit settings do not apply to NPC seats: "
                + failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
    }
}
