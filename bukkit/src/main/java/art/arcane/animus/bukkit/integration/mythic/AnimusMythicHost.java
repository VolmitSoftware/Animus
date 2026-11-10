package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.bukkit.BukkitWorldLookup;
import art.arcane.animus.bukkit.api.NpcPosition;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.integration.MythicBinding;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** {@link MythicHost} over the running Animus: loaded definitions, live NPC positions and the server's owners. */
final class AnimusMythicHost implements MythicHost {
    private record Entry(NpcDefinition definition, Optional<MythicBinding> binding) {}
    private record Index(Map<String, NpcDefinition> source, Map<UUID, Entry> byUuid) {}

    private final Server server;
    private final AnimusRuntime runtime;
    private final Function<UUID, Optional<VirtualNpcState.Snapshot>> states;
    private final MythicSupport.Owners owners;
    private volatile Index index = new Index(Map.of(), Map.of());

    AnimusMythicHost(Server server, AnimusRuntime runtime, Function<UUID, Optional<VirtualNpcState.Snapshot>> states, MythicSupport.Owners owners) {
        this.server = server; this.runtime = runtime; this.states = states; this.owners = owners;
    }

    @Override public Optional<Npc> npc(UUID npc) { return Optional.ofNullable(index().byUuid().get(npc)).map(this::view); }

    @Override public Optional<Npc> npc(String id) {
        if (!runtime.isRunning()) return Optional.empty();
        return runtime.npcs().find(id).map((NpcDefinition definition) -> view(entry(definition)));
    }

    @Override public Optional<org.bukkit.entity.Entity> entity(UUID id) {
        Player player = server.getPlayer(id);
        return player != null ? Optional.of(player) : Optional.ofNullable(server.getEntity(id));
    }

    @Override public Optional<Location> location(NpcPosition position) {
        World world = BukkitWorldLookup.find(server, position.world());
        return world == null ? Optional.empty() : Optional.of(new Location(world, position.x(), position.y(), position.z()));
    }

    @Override public boolean at(Location location, Runnable work) { return owners.at(location, work); }

    @Override public boolean player(UUID player, Consumer<Player> work) { return owners.player(player, work); }

    private static Entry entry(NpcDefinition definition) {
        try { return new Entry(definition, MythicBinding.parse(definition.json())); }
        catch (IllegalArgumentException malformed) { return new Entry(definition, Optional.empty()); }
    }

    private Npc view(Entry entry) {
        NpcDefinition definition = entry.definition();
        Optional<Location> location = states.apply(definition.uuid()).filter(VirtualNpcState.Snapshot::active).flatMap((VirtualNpcState.Snapshot state) -> {
            World world = BukkitWorldLookup.find(server, state.position().world());
            return world == null ? Optional.empty() : Optional.of(new Location(world, state.position().point().x(), state.position().point().y(), state.position().point().z()));
        });
        return new Npc(definition.uuid(), definition.id(), definition.name(), entry.binding(), location);
    }

    /** Definitions by UUID, rebuilt only when the loaded set changes. */
    private Index index() {
        if (!runtime.isRunning()) return new Index(Map.of(), Map.of());
        Map<String, NpcDefinition> loaded = runtime.npcs().snapshot();
        Index current = index;
        if (current.source() == loaded) return current;
        Map<UUID, Entry> byUuid = new HashMap<>();
        for (NpcDefinition definition : loaded.values()) byUuid.put(definition.uuid(), entry(definition));
        Index next = new Index(loaded, Map.copyOf(byUuid));
        index = next;
        return next;
    }
}
