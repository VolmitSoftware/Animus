package art.arcane.animus.core.quest;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * What behaviors may do with a player's Animus quests. Every method runs on the player's owner and throws
 * {@link Refused} when the quest, the player or the request does not allow it.
 */
public interface QuestControl {
    /** A refusal with a stable code for behavior diagnostics. */
    final class Refused extends RuntimeException {
        private final String code;
        public Refused(String code, String message) { super(message, null, false, false); this.code = Objects.requireNonNull(code); }
        public String code() { return code; }
    }

    /** Where quests run but nothing is wired yet, such as a platform without a quest service. */
    QuestControl ABSENT = new QuestControl() {
        private Refused absent() { return new Refused("quests-unavailable", "Animus quests are not running on this server"); }
        @Override public Optional<String> unavailable() { return Optional.of("Animus quests are not running on this server"); }
        @Override public long start(UUID player, String quest, boolean force) { throw absent(); }
        @Override public long advance(UUID player, String quest, OptionalInt stage) { throw absent(); }
        @Override public long complete(UUID player, String quest, boolean rewards) { throw absent(); }
        @Override public int objective(UUID player, String quest, String objective, int amount) { throw absent(); }
        @Override public String status(UUID player, String quest) { throw absent(); }
    };

    Optional<String> unavailable();
    /** Starts the quest; {@code force} skips its requirements and repeat rule. Returns the new stage value, 1. */
    long start(UUID player, String quest, boolean force);
    /** Moves the active quest to its next stage, or to {@code stage} (from 1). Past the last stage it completes. Returns the stage value. */
    long advance(UUID player, String quest, OptionalInt stage);
    /** Completes the active quest, paying its rewards unless {@code rewards} is false. Returns the finished stage value. */
    long complete(UUID player, String quest, boolean rewards);
    /** Adds to a custom objective of the active stage; returns its count afterwards. */
    int objective(UUID player, String quest, String objective, int amount);
    /** {@code active}, {@code ready} (waiting for the turn-in NPC), {@code finished} or {@code none}. */
    String status(UUID player, String quest);
}
