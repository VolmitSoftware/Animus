package art.arcane.animus.bukkit.gui;

import art.arcane.animus.core.gui.GlossBridge;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Gloss's public API reached by reflection through Gloss's own class loader. Animus has no compile or runtime
 * dependency on Gloss: without it, or when its API cannot be bound, the bridge is absent with the reason.
 */
public final class BukkitGlossBridge implements GlossBridge<Player> {
    private static final String API = "art.arcane.gloss.api.GlossAPI";
    private final JavaPlugin animus;
    private final Plugin gloss;
    private final Method get, open, menuIds;

    private BukkitGlossBridge(JavaPlugin animus, Plugin gloss) throws ReflectiveOperationException {
        this.animus = animus;
        this.gloss = gloss;
        Class<?> api = Class.forName(API, true, gloss.getClass().getClassLoader());
        get = api.getMethod("get");
        open = api.getMethod("open", Plugin.class, Player.class, String.class);
        menuIds = api.getMethod("menuIds");
        get.invoke(null);
    }

    /** Call after startup, when Gloss has had its chance to enable. */
    public static GlossBridge<Player> load(JavaPlugin animus) {
        Plugin gloss = animus.getServer().getPluginManager().getPlugin("Gloss");
        if (gloss == null || !gloss.isEnabled()) return GlossBridge.absent("Gloss is not installed");
        try { return new BukkitGlossBridge(animus, gloss); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invoked && invoked.getCause() != null ? invoked.getCause() : failure;
            return GlossBridge.absent("Gloss is installed but its API is unavailable: " + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        }
    }

    @Override public String unavailable() { return gloss.isEnabled() ? null : "Gloss is not enabled"; }
    @Override public Path folder() { return gloss.getDataFolder().toPath(); }

    @Override public Set<String> menuIds() {
        if (!gloss.isEnabled()) return Set.of();
        try {
            Object ids = menuIds.invoke(get.invoke(null));
            Set<String> copy = new TreeSet<>();
            if (ids instanceof Collection<?> values) for (Object value : values) copy.add(String.valueOf(value));
            return Set.copyOf(copy);
        } catch (ReflectiveOperationException | RuntimeException failure) { return Set.of(); }
    }

    @Override public boolean open(Player player, String menuId) {
        Objects.requireNonNull(player); Objects.requireNonNull(menuId);
        if (!gloss.isEnabled() || !menuIds().contains(menuId)) return false;
        try { open.invoke(get.invoke(null), animus, player, menuId); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) {
            animus.getLogger().warning("Gloss refused to open menu " + menuId + ": " + failure.getClass().getSimpleName());
            return false;
        }
    }
}
