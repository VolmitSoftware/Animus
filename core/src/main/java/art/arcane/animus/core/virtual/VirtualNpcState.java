package art.arcane.animus.core.virtual;

import java.util.Objects;
import java.util.UUID;

/** Mutable authoritative runtime state, intentionally separate from editable persisted definitions. */
public final class VirtualNpcState {
    public record Snapshot(UUID npcId, WorldPosition position, Hitbox bounds, double health, boolean active, long revision) {
        public Snapshot {
            Objects.requireNonNull(npcId, "npcId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(bounds, "bounds");
            if (!Double.isFinite(health) || health < 0 || revision < 0 || (active && health == 0))
                throw new IllegalArgumentException("Invalid virtual runtime state");
        }
    }

    private final UUID npcId;
    private Hitbox localBounds;
    private final double maximumHealth;
    private WorldPosition position;
    private double health;
    private boolean active;
    private long revision;

    public VirtualNpcState(UUID npcId, WorldPosition position, double width, double height, double maximumHealth) {
        this.npcId = Objects.requireNonNull(npcId, "npcId");
        this.position = Objects.requireNonNull(position, "position");
        this.localBounds = Hitbox.atFeet(Vec3.ZERO, width, height);
        if (!Double.isFinite(maximumHealth) || maximumHealth <= 0)
            throw new IllegalArgumentException("Maximum health must be finite and positive");
        this.maximumHealth = maximumHealth;
        health = maximumHealth;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(npcId, position, localBounds.translated(position.point()), health, active, revision);
    }

    public synchronized Snapshot spawn() {
        if (health == 0) throw new IllegalStateException("Dead NPC must be explicitly respawned");
        if (!active) { advance(); active = true; }
        return snapshot();
    }

    public synchronized Snapshot despawn() {
        if (active) { advance(); active = false; }
        return snapshot();
    }

    public synchronized Snapshot move(WorldPosition next) {
        Objects.requireNonNull(next, "next");
        if (!position.equals(next)) { advance(); position = next; }
        return snapshot();
    }

    /** The caller must obtain protection/event approval before this authoritative mutation. */
    public synchronized Snapshot damage(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) throw new IllegalArgumentException("Damage must be finite and positive");
        if (active) {
            advance();
            health = Math.max(0, health - amount);
            if (health == 0) active = false;
        }
        return snapshot();
    }

    /** Restores at most this much health to a living NPC, never above its maximum. */
    public synchronized Snapshot heal(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) throw new IllegalArgumentException("Healing must be finite and positive");
        if (active && health < maximumHealth) {
            advance();
            health = Math.min(maximumHealth, health + amount);
        }
        return snapshot();
    }

    public synchronized Snapshot respawn(WorldPosition spawn) {
        Objects.requireNonNull(spawn, "spawn");
        advance(); position = spawn; health = maximumHealth; active = true;
        return snapshot();
    }

    /** A runtime scale change: new bounds about the same feet. */
    public synchronized Snapshot resize(double width, double height) {
        localBounds = Hitbox.atFeet(Vec3.ZERO, width, height);
        advance();
        return snapshot();
    }

    /** A definition replacement may change geometry/position without healing or reviving this NPC. */
    public synchronized VirtualNpcState reconfigured(WorldPosition next, double width, double height) {
        return reconfigured(next, width, height, maximumHealth);
    }

    /** As above, with a new maximum. Current health never rises, and falls only to fit the new maximum. */
    public synchronized VirtualNpcState reconfigured(WorldPosition next, double width, double height, double newMaximumHealth) {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Runtime revision exhausted");
        VirtualNpcState copy = new VirtualNpcState(npcId, next, width, height, newMaximumHealth);
        copy.health = Math.min(health, newMaximumHealth);
        copy.active = active;
        copy.revision = revision + 1;
        return copy;
    }

    private void advance() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Runtime revision exhausted");
        revision++;
    }
}
