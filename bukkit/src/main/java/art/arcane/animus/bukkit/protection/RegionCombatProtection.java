package art.arcane.animus.bukkit.protection;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.bukkit.protection.RegionFlags.State;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Region flags decide combat involving virtual NPCs. An NPC has no WorldGuard identity, so it is asked about as a
 * non-member; a player is asked about as themselves. A failed query throws, and the combat gate denies.
 *
 * <ul>
 *   <li>Hitting a player or an NPC: {@code pvp} must not be denied where either side stands; a player target in an
 *   {@code invincible} region is never hurt.</li>
 *   <li>An NPC hitting an animal: {@code damage-animals} must not be denied where it stands.</li>
 *   <li>A mob hitting or being controlled against an NPC: {@code mob-damage} must not be denied where the NPC stands.</li>
 * </ul>
 */
public final class RegionCombatProtection implements ProtectionAdapter {
    private final RegionFlags flags;

    public RegionCombatProtection(RegionFlags flags) { this.flags = Objects.requireNonNull(flags); }

    @Override public Decision decide(Request request) {
        WorldPosition target = request.targetPosition();
        return switch (request.action()) {
            case MOB_ATTACK, MOB_CONTROL -> allowed(target, null, Flag.MOB_DAMAGE);
            case NPC_ATTACK, PLAYER_ATTACK -> {
                TargetKind kind = request.target().kind();
                UUID attacker = player(request.attacker());
                if (kind == TargetKind.PLAYER || kind == TargetKind.NPC) {
                    if (kind == TargetKind.PLAYER && flags.state(target, request.target().id(), Flag.INVINCIBLE) == State.ALLOW) yield Decision.DENY;
                    Decision from = allowed(request.attackerPosition(), attacker, Flag.PVP);
                    Decision to = allowed(target, attacker, Flag.PVP);
                    yield from == Decision.ALLOW && to == Decision.ALLOW ? Decision.ALLOW : Decision.DENY;
                }
                if (request.targetCategories().contains(TargetCategory.ANIMALS)) yield allowed(target, attacker, Flag.DAMAGE_ANIMALS);
                yield Decision.ALLOW;
            }
        };
    }

    @Override public Optional<Boolean> inRegion(String region, WorldPosition position) { return flags.inRegion(region, position); }

    private Decision allowed(WorldPosition at, UUID player, Flag flag) {
        return flags.state(at, player, flag) == State.DENY ? Decision.DENY : Decision.ALLOW;
    }
    private static UUID player(TargetRef ref) { return ref.kind() == TargetKind.PLAYER ? ref.id() : null; }
}
