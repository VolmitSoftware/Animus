package art.arcane.animus.core.api;

import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the public NPC service needs from a platform beyond {@link art.arcane.animus.core.runtime.NpcRuntime} and the
 * behavior platform. Methods marked "on the NPC's thread" run inside {@code NpcRuntime.onOwner} work.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public interface ApiPlatform<P, I> {
    /** A server item for a definition item, on the NPC's thread; null with an error when it cannot exist. */
    I item(NpcItem item, String path, List<String> errors);

    /** The item that empties an equipment slot. */
    I emptyItem();

    /**
     * On the NPC's thread: moves the active NPC onto clear ground at the destination and turns it to the facing. Completes
     * with false when the destination is not loaded or not clear, the NPC rides or carries someone, or on Folia lies in
     * another world. On Folia a destination another region holds is checked there and the NPC is handed over.
     */
    java.util.concurrent.CompletionStage<Boolean> teleport(String id, WorldPosition to, float yaw, float pitch);

    /** On the NPC's thread: where a real entity stands, when this thread may read it now. */
    Optional<WorldPosition> entity(UUID entity);

    /** On the player's thread: where the player stands. */
    WorldPosition position(P player);

    /** The world key of a world name or UUID, as {@link WorldPosition#world()} holds it; empty when no such world is loaded. Any thread. */
    Optional<String> world(String reference);

    /** The world name a world key belongs to, for locations given back to plugins; the key itself when the world is gone. Any thread. */
    String worldName(String key);

    /** The point an NPC at {@code at} looks at to face {@code yaw} and {@code pitch}: two blocks ahead at eye height. */
    static WorldPosition facing(WorldPosition at, float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(Math.max(-90, Math.min(90, pitch)));
        art.arcane.animus.core.virtual.Vec3 ahead = new art.arcane.animus.core.virtual.Vec3(-Math.sin(yawRadians) * Math.cos(pitchRadians) * 2,
                1.62 - Math.sin(pitchRadians) * 2, Math.cos(yawRadians) * Math.cos(pitchRadians) * 2);
        return new WorldPosition(at.world(), at.point().add(ahead));
    }
}
