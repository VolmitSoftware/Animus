package art.arcane.animus.core.quest;

import art.arcane.animus.core.behavior.BehaviorExpression;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.BehaviorFlags;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.ServerNpc;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * A quest's place in behavior state: its stage is the player-scoped quest variable named after the quest in its
 * namespace ({@code animus:quests/<namespace>}), the same one {@code quest('player', name)},
 * {@code animus:quest-state} and {@code animus:set-quest-state} use. Reward flags are player or global flags of that
 * namespace. Stage 0 is not started, 1 to N the active stage and N + 1 finished. Thread-safe.
 */
public final class QuestState {
    private static final BehaviorProgram.Value UNSET_STAGE = new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, 0L),
            UNSET_FLAG = new BehaviorProgram.Value(BehaviorProgram.Type.STRING, "unset");
    private final BehaviorState state;
    private final Supplier<BehaviorFacts> facts;

    public QuestState(BehaviorState state, Supplier<BehaviorFacts> facts) {
        this.state = Objects.requireNonNull(state);
        this.facts = Objects.requireNonNull(facts);
    }

    /** The behavior stage value, 0 when unset, or empty when it cannot be read. */
    public java.util.OptionalLong stage(UUID player, QuestDocument quest) {
        try { return java.util.OptionalLong.of((Long) state.read(subject(BehaviorFlags.QUESTS, quest.id(), player), quest.name(), BehaviorFlags.declaration("player"), UNSET_STAGE, BehaviorProgram.Type.INTEGER).value()); }
        catch (BehaviorState.Rejected | ClassCastException unreadable) { return java.util.OptionalLong.empty(); }
    }

    public void stage(UUID player, QuestDocument quest, long value) {
        state.write(subject(BehaviorFlags.QUESTS, quest.id(), player), Map.of(quest.name(), new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, value)),
                Map.of(quest.name(), BehaviorFlags.declaration("player")));
    }

    public void flag(UUID player, QuestDocument quest, QuestDocument.Flag flag) {
        String scope = flag.global() ? "global" : "player";
        state.write(subject(BehaviorFlags.FLAGS, quest.id(), player), Map.of(flag.name(), flag.value()), Map.of(flag.name(), BehaviorFlags.declaration(scope)));
    }

    /** Whether the requirement condition holds for the player; a failing or unreadable condition does not. */
    public boolean holds(UUID player, QuestDocument quest, BehaviorExpression condition) {
        try { return Boolean.TRUE.equals(condition.evaluate(new Reads(player, quest))); }
        catch (BehaviorExpression.Failed | BehaviorState.Rejected failed) { return false; }
    }

    private static BehaviorState.Subject subject(String kind, String quest, UUID player) {
        return BehaviorFlags.subject(kind, quest, ServerNpc.HANDLE.uuid(), Optional.of(player));
    }

    /** What a requirement condition reads: no variables, {@code context.quest}, and the quest namespace's flags and quests. */
    private final class Reads implements BehaviorExpression.Environment {
        private final UUID player;
        private final QuestDocument quest;
        Reads(UUID player, QuestDocument quest) { this.player = player; this.quest = quest; }
        @Override public BehaviorProgram.Value variable(String name) { return null; }
        @Override public BehaviorFacts facts() { return facts.get(); }
        @Override public Optional<UUID> player() { return Optional.of(player); }
        @Override public UUID npc() { return ServerNpc.HANDLE.uuid(); }
        @Override public Map<String, Object> context() { return Map.of("quest", quest.id()); }
        @Override public Optional<BehaviorProgram.Value> flag(String scope, String name) { return read(player, scope, name); }
        @Override public long quest(String scope, String name) {
            return (Long) state.read(subject(BehaviorFlags.QUESTS, quest.id(), player), name, BehaviorFlags.declaration(scope), UNSET_STAGE, BehaviorProgram.Type.INTEGER).value();
        }
        @Override public RandomGenerator random() { return ThreadLocalRandom.current(); }
        @Override public Optional<BehaviorProgram.Value> playerFlag(UUID other, String name) { return read(other, "player", name); }
        private Optional<BehaviorProgram.Value> read(UUID who, String scope, String name) {
            BehaviorProgram.Value value = state.read(subject(BehaviorFlags.FLAGS, quest.id(), who), name, BehaviorFlags.declaration(scope), UNSET_FLAG, BehaviorProgram.Type.ANY);
            return value == UNSET_FLAG ? Optional.empty() : Optional.of(value);
        }
    }
}
