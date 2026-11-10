package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.RichText;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Animus NPCs another plugin's files may name: each by its id and UUID, and by the Citizens id an import gave it, so
 * references every quest plugin keeps to Citizens NPCs can follow them.
 */
public final class NpcLinks {
    /** One NPC: its Animus id, UUID, the Citizens id it came from or -1, and its name. */
    public record Link(String key, UUID uuid, int citizensId, String name) {
        public Link { Objects.requireNonNull(key); Objects.requireNonNull(uuid); Objects.requireNonNull(name); }
        public boolean imported() { return citizensId >= 0; }
    }

    private final Map<Integer, Link> byCitizensId = new HashMap<>();
    private final Map<String, Link> byKey = new HashMap<>();
    private final Set<UUID> uuids = new HashSet<>();
    private final Set<String> names = new HashSet<>();

    private NpcLinks(Collection<Link> links) {
        for (Link link : links) {
            byKey.put(link.key(), link);
            uuids.add(link.uuid());
            if (!link.imported()) continue;
            byCitizensId.put(link.citizensId(), link);
            names.add(plain(link.name()));
        }
    }

    public static NpcLinks of(Collection<Link> links) { return new NpcLinks(List.copyOf(links)); }

    /** Every loaded NPC, with the Citizens id its {@code migration} record keeps when an import installed it. */
    public static NpcLinks from(Collection<NpcDefinition> definitions) {
        return of(definitions.stream().map((NpcDefinition definition) -> new Link(definition.id(), definition.uuid(),
                definition.citizensId().map(Integer::parseInt).orElse(-1), definition.name())).toList());
    }

    /** The Animus id of the NPC imported from this Citizens id. */
    public Optional<String> citizens(int citizensId) { return imported(citizensId).map(Link::key); }

    /** The NPC imported from this Citizens id. */
    public Optional<Link> imported(int citizensId) { return Optional.ofNullable(byCitizensId.get(citizensId)); }

    /** The NPC with this Animus id. */
    public Optional<Link> key(String key) { return Optional.ofNullable(byKey.get(key)); }

    /** An NPC has this UUID. */
    public boolean has(UUID uuid) { return uuids.contains(uuid); }

    /** An imported NPC carries this name, compared without colours, case or extra spaces as BetonQuest's {@code byName} lookup does. */
    public boolean named(String name) { return names.contains(plain(name)); }

    private static String plain(String name) { return RichText.plain(name).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    /** How many NPCs came from Citizens. */
    public int size() { return byCitizensId.size(); }
}
