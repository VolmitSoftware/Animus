package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Puts an NPC somewhere on Folia, wherever the destination lies. The region that holds the destination finds clear ground
 * there and hands the spot back to the NPC's own region, which moves the NPC; its next turn hands it on to the
 * destination's region like any chunk crossing. Nothing reads terrain or the NPC from the wrong region.
 */
final class RegionPlacement {
    /** One placement's view of the regions and the NPC. */
    interface Host {
        /** On the NPC's region: whether it also holds the destination's chunk. */
        boolean ownsDestination(ChunkPosition chunk);
        /** Whether the destination's chunk is known to be loaded. */
        boolean knownLoaded(ChunkPosition chunk);
        /** Queues work on the region that holds the chunk; false when it refused. */
        boolean onDestination(ChunkPosition chunk, Runnable work);
        /** On the destination's region: clear, supported ground at or near the destination. */
        Optional<WorldPosition> clearSpot(WorldPosition destination);
        /** Hands work back to the NPC's region; {@code refused} runs instead when it went dormant. False when it refused at once. */
        boolean onOwner(Runnable work, Runnable refused);
        /** On the NPC's region: whether the NPC may be moved now. */
        boolean placeable();
        /** On the NPC's region: moves the NPC to the spot. */
        void place(WorldPosition spot);
    }

    private RegionPlacement() {}

    /**
     * On the NPC's region. False when it cannot try (the NPC may not move, or the destination's chunk is not known to be
     * loaded) and when its own region found no ground. Otherwise {@code done} hears whether the NPC moved, at once or once
     * the regions have answered.
     */
    static boolean place(Host host, WorldPosition destination, Consumer<Boolean> done) {
        if (!host.placeable()) return false;
        ChunkPosition chunk = destination.chunk();
        if (host.ownsDestination(chunk)) {
            Optional<WorldPosition> spot = host.clearSpot(destination);
            spot.ifPresent(host::place);
            done.accept(spot.isPresent());
            return spot.isPresent();
        }
        if (!host.knownLoaded(chunk)) return false;
        return host.onDestination(chunk, () -> {
            Optional<WorldPosition> spot = host.clearSpot(destination);
            if (spot.isEmpty()) { done.accept(false); return; }
            boolean handed = host.onOwner(() -> {
                boolean movable = host.placeable();
                if (movable) host.place(spot.get());
                done.accept(movable);
            }, () -> done.accept(false));
            if (!handed) done.accept(false);
        });
    }
}
