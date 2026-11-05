package art.arcane.animus.api;

import java.math.BigDecimal;
import java.util.*;

/**
 * Named extension points the engine calls itself. Unlike capabilities, a hook runs synchronously on the platform
 * owner thread that needs the answer, so it must be quick, nonblocking and thread-safe. One module may provide each
 * point. A throw, a null answer or an unavailable provider denies (protection) or refuses payment (economy); a call
 * longer than the module invocation deadline suspends the module. @since 1.1
 */
public final class Integrations {
    private Integrations() {}
    public enum Point { PROTECTION, ECONOMY }
    public record Descriptor(String description,Set<Platform> platforms) {
        public Descriptor {ApiText.text(description,2048,"description");platforms=Set.copyOf(platforms);if(platforms.isEmpty())throw new IllegalArgumentException("No supported platforms");}
    }

    public enum Decision { ALLOW, DENY }
    /** Combat actions, NPC build block changes and an NPC opening a door, fence gate or trapdoor on its route ({@code BLOCK_USE}). */
    public enum ProtectionAction { NPC_ATTACK, PLAYER_ATTACK, MOB_ATTACK, MOB_CONTROL, BLOCK_PLACE, BLOCK_BREAK, BLOCK_USE }
    public enum PartyKind { PLAYER, MOB, NPC }
    public record Party(PartyKind kind,UUID uuid) {public Party {Objects.requireNonNull(kind);Objects.requireNonNull(uuid);}}
    public record Position(String world,double x,double y,double z) {
        public Position {Objects.requireNonNull(world);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Non-finite position");}
    }
    /**
     * Combat has a target; a block change has none and its target position is the block. {@code player} is the
     * player who started a build. Categories are lowercase target categories such as {@code animals}.
     */
    public record ProtectionRequest(ProtectionAction action,Party actor,Position actorPosition,Optional<Party> target,Position targetPosition,Set<String> targetCategories,Optional<UUID> player) {
        public ProtectionRequest {Objects.requireNonNull(action);Objects.requireNonNull(actor);Objects.requireNonNull(actorPosition);target=Objects.requireNonNull(target);Objects.requireNonNull(targetPosition);targetCategories=Set.copyOf(targetCategories);player=Objects.requireNonNull(player);}
    }
    /** Combined with the platform's own protection: both must allow. */
    @FunctionalInterface public interface Protection {Decision decide(ProtectionRequest request) throws Exception;}
    /** Used only where the platform has no money provider. Amounts are positive. */
    public interface Economy {
        boolean has(UUID player,BigDecimal amount) throws Exception;
        boolean withdraw(UUID player,BigDecimal amount) throws Exception;
        boolean deposit(UUID player,BigDecimal amount) throws Exception;
        String format(BigDecimal amount) throws Exception;
    }
    public interface Registrar {
        void protection(Descriptor descriptor,Protection protection);
        void economy(Descriptor descriptor,Economy economy);
    }
}
