package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every real helper entity an NPC launched. A shot leaves the ledger exactly once, so virtual
 * collision and a real hit callback cannot both apply its damage.
 */
public final class HelperLedger {
    public static final class Shot {
        private final UUID helper;
        private final UUID owner;
        private final Optional<TargetRef> intended;
        private final double damage;
        private final double knockback;
        private final boolean safeShot;
        private final Optional<CombatSettings.Effect> effect;
        private final long expiresAt;
        private Vec3 lastPosition;

        /** @param effect what the shot applies instead of damage, for a potion */
        public Shot(UUID helper, UUID owner, Optional<TargetRef> intended, double damage, double knockback, boolean safeShot, Optional<CombatSettings.Effect> effect,
                    long expiresAt, Vec3 lastPosition) {
            this.helper = Objects.requireNonNull(helper, "helper");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.intended = Objects.requireNonNull(intended, "intended");
            this.damage = damage;
            this.knockback = knockback;
            this.safeShot = safeShot;
            this.effect = Objects.requireNonNull(effect, "effect");
            this.expiresAt = expiresAt;
            this.lastPosition = Objects.requireNonNull(lastPosition, "lastPosition");
        }
        public UUID helper() { return helper; }
        public UUID owner() { return owner; }
        public Optional<TargetRef> intended() { return intended; }
        public double damage() { return damage; }
        public double knockback() { return knockback; }
        public boolean safeShot() { return safeShot; }
        public Optional<CombatSettings.Effect> effect() { return effect; }
        public long expiresAt() { return expiresAt; }
        public Vec3 lastPosition() { return lastPosition; }
        void moved(Vec3 position) { lastPosition = position; }
    }

    private final int maximum;
    private final int perOwner;
    private final Map<UUID, Shot> shots = new HashMap<>();
    private final Map<UUID, Integer> owners = new HashMap<>();
    private final ArrayDeque<UUID> order = new ArrayDeque<>();

    public HelperLedger(int maximum, int perOwner) {
        if (maximum < 1 || perOwner < 1) throw new IllegalArgumentException("Helper budgets must be positive");
        this.maximum = maximum;
        this.perOwner = perOwner;
    }

    public int size() { return shots.size(); }
    public boolean tracks(UUID helper) { return shots.containsKey(helper); }
    public boolean hasCapacity(UUID owner) { return shots.size() < maximum && owners.getOrDefault(owner, 0) < perOwner; }

    public void track(Shot shot) {
        if (!hasCapacity(shot.owner())) throw new IllegalStateException("Helper budget exhausted");
        shots.put(shot.helper(), shot);
        owners.merge(shot.owner(), 1, Integer::sum);
        order.addLast(shot.helper());
    }

    /** The only exit from the ledger: the first caller gets the shot and every later caller gets nothing. */
    public Optional<Shot> claim(UUID helper) {
        Shot shot = shots.remove(helper);
        if (shot == null) return Optional.empty();
        order.remove(helper);
        owners.computeIfPresent(shot.owner(), (UUID owner, Integer count) -> count == 1 ? null : count - 1);
        return Optional.of(shot);
    }

    /** The next shot in fair rotation, or empty when none remain. */
    public Optional<Shot> next() {
        UUID helper = order.pollFirst();
        if (helper == null) return Optional.empty();
        order.addLast(helper);
        return Optional.of(shots.get(helper));
    }

    public List<UUID> retireOwner(UUID owner) {
        List<UUID> retired = new ArrayList<>();
        for (Shot shot : List.copyOf(shots.values())) {
            if (shot.owner().equals(owner) && claim(shot.helper()).isPresent()) retired.add(shot.helper());
        }
        return retired;
    }

    public List<UUID> retireAll() {
        List<UUID> retired = new ArrayList<>(order);
        for (UUID helper : retired) claim(helper);
        return retired;
    }
}
