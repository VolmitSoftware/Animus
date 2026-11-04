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
    private final Hitbox localBounds;
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

    public synchronized Snapshot respawn(WorldPosition spawn) {
        Objects.requireNonNull(spawn, "spawn");
        advance(); position = spawn; health = maximumHealth; active = true;
        return snapshot();
    }

    private void advance() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Runtime revision exhausted");
        revision++;
    }
}
