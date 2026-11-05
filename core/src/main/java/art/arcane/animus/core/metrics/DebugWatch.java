package art.arcane.animus.core.metrics;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Players who toggled {@code /animus debug watch}. After each metrics roll every watcher gets one action-bar line;
 * a watcher the platform can no longer reach is dropped.
 */
public final class DebugWatch {
    private final Set<UUID> watchers = ConcurrentHashMap.newKeySet();

    /** True when the player now watches. */
    public boolean toggle(UUID player) {
        Objects.requireNonNull(player);
        if (watchers.remove(player)) return false;
        watchers.add(player);
        return true;
    }

    public boolean watching(UUID player) { return watchers.contains(player); }
    public int count() { return watchers.size(); }
    public void clear() { watchers.clear(); }

    /** Sends the line to every watcher; {@code actionBar} returns false for a player who is gone. */
    public void publish(MetricsSnapshot snapshot, BiPredicate<UUID, String> actionBar) {
        if (watchers.isEmpty()) return;
        String text = line(snapshot);
        for (UUID watcher : List.copyOf(watchers)) if (!actionBar.test(watcher, text)) watchers.remove(watcher);
    }

    /** One compact line: NPC counts, last-second traffic and server time over 10 seconds. */
    public static String line(MetricsSnapshot snapshot) {
        MetricsSnapshot.Rates second = snapshot.second(), ten = snapshot.tenSeconds();
        RuntimeGauges gauges = snapshot.gauges();
        return String.format(Locale.ROOT, "&6Animus &7npcs &f%d&7/&f%d &8| &7out &f%.0f&7 pkt/s &f%s&7/s &8| &7in &f%.1f&7/s &8| &f%.2f&7 ms/tick &8(&f%.1f%%&8)%s",
                gauges.active(), gauges.definitions(), second.packetsPerSecond(), DebugFormat.bytes(second.bytesPerSecond()), second.inboundPerSecond(),
                ten.serverMillisPerTick(), ten.serverTickShare() * 100,
                gauges.navigationPressured() || gauges.presentationStrained() ? " &c" + (gauges.navigationPressured() ? "nav-pressure " : "") + (gauges.presentationStrained() ? "strained" : "") : "");
    }
}
