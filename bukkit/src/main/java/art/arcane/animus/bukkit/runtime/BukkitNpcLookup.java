package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.api.AnimusNpcs;
import art.arcane.animus.bukkit.api.NpcInfo;
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

/** {@link AnimusNpcs} over the loaded definitions. The index is rebuilt only when the definition snapshot changes. */
public final class BukkitNpcLookup implements AnimusNpcs {
    private record Index(Map<String, NpcDefinition> source, List<NpcInfo> all, Map<String, NpcInfo> byKey, Map<UUID, NpcInfo> byUuid) {}

    private final Supplier<Map<String, NpcDefinition>> definitions;
    private volatile Index index = new Index(null, List.of(), Map.of(), Map.of());

    /** {@code definitions} answers the current immutable snapshot and is safe to call from any thread. */
    public BukkitNpcLookup(Supplier<Map<String, NpcDefinition>> definitions) { this.definitions = Objects.requireNonNull(definitions); }

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
