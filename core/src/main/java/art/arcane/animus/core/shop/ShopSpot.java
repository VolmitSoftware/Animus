package art.arcane.animus.core.shop;

import art.arcane.animus.core.virtual.Vec3;
import java.util.Objects;
import java.util.Set;

/**
 * A container block a player picked for a shop, by every name its world answers to, and the spot in front of the face
 * they looked at where the shop's NPC stands facing them.
 */
public record ShopSpot(Set<String> worlds, int x, int y, int z, Vec3 stand, float yaw) {
    public ShopSpot { worlds = Set.copyOf(worlds); Objects.requireNonNull(stand); }

    /** The block as a shop file lists it, under the world name {@code world}. */
    public ShopDocument.Container container(String world) { return new ShopDocument.Container(world, x, y, z); }
}
