package art.arcane.animus.core.combat;

import java.util.Locale;
import java.util.Optional;

/** A kind of fight nearby that makes the one who started it a target. */
public enum FightKind {
    /** A player hurts another player. */
    PLAYER_HURTS_PLAYER,
    /** A player hurts a mob. */
    PLAYER_HURTS_MOB,
    /** A mob hurts a mob or an NPC. */
    MOB_HURTS_MOB,
    /** A player hurts an NPC. Only combat NPCs can be hurt. */
    PLAYER_HURTS_NPC,
    /** An NPC hurts another NPC. */
    NPC_HURTS_NPC;

    public String jsonName() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<FightKind> parse(String value) {
        for (FightKind kind : values()) if (kind.jsonName().equals(value)) return Optional.of(kind);
        return Optional.empty();
    }

    /** The kind of a hit, or empty when an attacker hurts itself or no kind describes it. */
    public static Optional<FightKind> of(TargetRef attacker, TargetRef victim) {
        if (attacker.equals(victim)) return Optional.empty();
        return switch (attacker.kind()) {
            case PLAYER -> Optional.of(switch (victim.kind()) {
                case PLAYER -> PLAYER_HURTS_PLAYER;
                case MOB -> PLAYER_HURTS_MOB;
                case NPC -> PLAYER_HURTS_NPC;
            });
            case MOB -> victim.kind() == TargetKind.PLAYER ? Optional.empty() : Optional.of(MOB_HURTS_MOB);
            case NPC -> victim.kind() == TargetKind.NPC ? Optional.of(NPC_HURTS_NPC) : Optional.empty();
        };
    }
}
