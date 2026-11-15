package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.api.NpcService;
import art.arcane.animus.bukkit.api.AnimusNpcs;
import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.core.api.NpcScopes;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.RichText;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * {@link AnimusNpcs} over the loaded definitions and the public NPC service. The index is rebuilt only when the definition
 * snapshot changes. Register it as a listener: a plugin that disables loses what it made, and a player who leaves is
 * forgotten by the service's hiding.
 */
public final class BukkitNpcLookup implements AnimusNpcs, Listener {
    private record Index(Map<String, NpcDefinition> source, List<NpcInfo> all, Map<String, NpcInfo> byKey, Map<UUID, NpcInfo> byUuid) {}

    private final Supplier<Map<String, NpcDefinition>> definitions;
    private volatile Index index = new Index(null, List.of(), Map.of(), Map.of());
    private volatile NpcScopes scopes;
    private volatile java.util.function.Consumer<UUID> left = (UUID player) -> {};

    /** {@code definitions} answers the current immutable snapshot and is safe to call from any thread. */
    public BukkitNpcLookup(Supplier<Map<String, NpcDefinition>> definitions) { this.definitions = Objects.requireNonNull(definitions); }

    /** Connect once the NPC service runs; {@code left} hears each player who quits. */
    public void connect(NpcScopes scopes, java.util.function.Consumer<UUID> left) { this.scopes = Objects.requireNonNull(scopes); this.left = Objects.requireNonNull(left); }

    @Override public NpcService service(Plugin plugin) {
        Objects.requireNonNull(plugin);
        NpcScopes running = scopes;
        if (running == null) throw new IllegalStateException("Animus is still starting, or did not start; the server log says why");
        if (!plugin.isEnabled()) throw new IllegalStateException(plugin.getName() + " is not enabled");
        return running.open(owner(plugin), plugin::isEnabled);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void disabled(PluginDisableEvent event) {
        NpcScopes running = scopes;
        if (running != null) running.release(owner(event.getPlugin()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { left.accept(event.getPlayer().getUniqueId()); }

    private static String owner(Plugin plugin) { return "plugin:" + plugin.getName(); }

    @Override public Optional<NpcInfo> byKey(String key) { return Optional.ofNullable(current().byKey().get(Objects.requireNonNull(key))); }
    @Override public Optional<NpcInfo> byUuid(UUID uuid) { return Optional.ofNullable(current().byUuid().get(Objects.requireNonNull(uuid))); }
    @Override public List<NpcInfo> byName(String name) {
        String wanted = plain(Objects.requireNonNull(name));
        return current().all().stream().filter((NpcInfo npc) -> plain(npc.name()).equals(wanted)).toList();
    }
    @Override public List<NpcInfo> all() { return current().all(); }

    private static String plain(String name) { return RichText.plain(name).toLowerCase(Locale.ROOT); }

    private Index current() {
        Map<String, NpcDefinition> source = definitions.get();
        Index known = index;
        if (known.source() == source) return known;
        List<NpcInfo> all = source.values().stream().sorted(Comparator.comparing(NpcDefinition::id))
                .map((NpcDefinition definition) -> new NpcInfo(definition.uuid(), definition.id(), definition.group(), definition.name(), definition.spawned() && !definition.dead()))
                .toList();
        Map<String, NpcInfo> byKey = new HashMap<>();
        Map<UUID, NpcInfo> byUuid = new HashMap<>();
        for (NpcInfo npc : all) { byKey.put(npc.key(), npc); byUuid.put(npc.uuid(), npc); }
        Index next = new Index(source, all, Map.copyOf(byKey), Map.copyOf(byUuid));
        index = next;
        return next;
    }
}
