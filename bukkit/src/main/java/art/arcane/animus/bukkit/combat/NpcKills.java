package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.text.RichText;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Death messages for players an NPC kills. An NPC body is no entity, so its hits carry no causing entity and vanilla
 * would print "%1$s was slain by %2$s"; a player who dies while an NPC's hit lands is slain or shot by that NPC instead.
 * The hit and the death run on the same thread, the main thread or the player's region, so the landing hit is kept per
 * thread. Paper keeps vanilla's translated message and adds the NPC to it; Spigot takes the message as English text.
 */
public final class NpcKills implements Listener {
    private record Hit(String npc, boolean projectile) {}

    private static final ThreadLocal<Hit> LANDING = new ThreadLocal<>();

    /** Runs one NPC hit on a real entity. {@code npc} is the NPC's name as its definition writes it. */
    public static void landing(String npc, boolean projectile, Runnable hit) {
        Hit outer = LANDING.get();
        LANDING.set(new Hit(npc, projectile));
        try {
            hit.run();
        } finally {
            if (outer == null) LANDING.remove();
            else LANDING.set(outer);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void died(PlayerDeathEvent event) {
        Hit hit = LANDING.get();
        if (hit == null) return;
        String npc = RichText.colorize(hit.npc());
        if (PaperText.AVAILABLE != null && PaperText.AVAILABLE.rename(event, npc)) return;
        event.setDeathMessage(String.format(Locale.ROOT, hit.projectile() ? "%s was shot by %s" : "%s was slain by %s", event.getEntity().getDisplayName(), npc));
    }

    /**
     * Paper's text components, null on Spigot. The plugin compiles against Spigot, and its own copy of Adventure is
     * relocated together with every Adventure class name written here, so Paper's classes are found from the type its
     * death event returns.
     */
    private record PaperText(Method message, Method setMessage, Class<?> translatable, Method key, Method arguments, Method displayName,
                             Method create, Class<?> part, Object legacy, Method deserialize) {
        static final PaperText AVAILABLE = find();

        private static PaperText find() {
            try {
                Method message = PlayerDeathEvent.class.getMethod("deathMessage");
                Class<?> component = message.getReturnType();
                ClassLoader paper = component.getClassLoader();
                String text = component.getPackageName();
                Class<?> part = Class.forName(text + ".ComponentLike", false, paper);
                Class<?> translatable = Class.forName(text + ".TranslatableComponent", false, paper);
                Class<?> serializer = Class.forName(text + ".serializer.legacy.LegacyComponentSerializer", false, paper);
                return new PaperText(message, PlayerDeathEvent.class.getMethod("deathMessage", component), translatable,
                        translatable.getMethod("key"), translatable.getMethod("arguments"), Player.class.getMethod("displayName"),
                        component.getMethod("translatable", String.class, part.arrayType()), part,
                        serializer.getMethod("legacySection").invoke(null), serializer.getMethod("deserialize", String.class));
            } catch (ReflectiveOperationException | LinkageError spigot) {
                return null;
            }
        }

        /** Vanilla's "death.attack" message with the NPC as its killer; false when the message is something else. */
        boolean rename(PlayerDeathEvent event, String npc) {
            try {
                Object current = message.invoke(event);
                if (!translatable.isInstance(current)) return false;
                String id = (String) key.invoke(current);
                if (!id.startsWith("death.attack.") || ((List<?>) arguments.invoke(current)).size() != 1) return false;
                Object parts = Array.newInstance(part, 2);
                Array.set(parts, 0, displayName.invoke(event.getEntity()));
                Array.set(parts, 1, deserialize.invoke(legacy, npc));
                setMessage.invoke(event, create.invoke(null, id, parts));
                return true;
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return false;
            }
        }
    }
}
