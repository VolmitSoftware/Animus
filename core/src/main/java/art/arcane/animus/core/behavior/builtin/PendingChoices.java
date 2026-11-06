package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Bounded single-use prompts. A click must present an unguessable live token from the player it was issued to. */
public final class PendingChoices {
    public enum Selection { ACCEPTED, UNKNOWN, EXPIRED, WRONG_PLAYER, UNKNOWN_OPTION, NPC_UNAVAILABLE }
    private record Prompt(NpcHandle npc, UUID player, Optional<String> behavior, String id, List<ChoiceOption> options, long expiresAt) {}

    private static final int TOKEN_BYTES = 16;
    private int maximum; // this monitor
    private final AtomicInteger pendingMaximum = new AtomicInteger();
    private final DialogueChoiceSink sink;
    private final LongSupplier nanos;
    private final Consumer<Throwable> sinkFailure;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Prompt> prompts = new LinkedHashMap<>();

    PendingChoices(int maximum, DialogueChoiceSink sink, LongSupplier nanos, Consumer<Throwable> sinkFailure) {
        if (maximum < 1) throw new IllegalArgumentException("Pending choice capacity must be positive");
        this.maximum = maximum;
        this.sink = Objects.requireNonNull(sink);
        this.nanos = Objects.requireNonNull(nanos);
        this.sinkFailure = Objects.requireNonNull(sinkFailure);
    }

    /** New capacity, adopted by the next prompt. A lower one keeps every open prompt and refuses new ones until they end. */
    void maximum(int next) {
        if (next < 1) throw new IllegalArgumentException("Pending choice capacity must be positive");
        pendingMaximum.set(next);
    }

    /** Replaces any earlier prompt from this NPC to this player, so its older clicks become stale. */
    synchronized String open(NpcHandle npc, UUID player, Optional<String> behavior, String id, List<ChoiceOption> options, Duration lifetime) {
        int next = pendingMaximum.getAndSet(0);
        if (next > 0) maximum = next;
        long now = nanos.getAsLong();
        prompts.values().removeIf(prompt -> expired(prompt, now) || prompt.npc.equals(npc) && prompt.player.equals(player));
        if (prompts.size() >= maximum)
            throw new CapabilityRefusal(Outcome.Status.BUSY, "choice-capacity", "Too many dialogue choices are waiting for an answer");
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        prompts.put(token, new Prompt(npc, player, behavior, id, List.copyOf(options), now + lifetime.toNanos()));
        return token;
    }

    synchronized void discard(String token) { prompts.remove(token); }

    /** A click from the wrong player, on a bad option or out of range leaves the prompt for its rightful answer. */
    Selection select(UUID player, String token, String option, Predicate<NpcHandle> npcAvailable) {
        Prompt prompt;
        synchronized (this) {
            prompt = prompts.get(token);
            if (prompt == null) return Selection.UNKNOWN;
            if (expired(prompt, nanos.getAsLong())) { prompts.remove(token); return Selection.EXPIRED; }
        }
        if (!prompt.player.equals(player)) return Selection.WRONG_PLAYER;
        if (prompt.options.stream().noneMatch(candidate -> candidate.id().equals(option))) return Selection.UNKNOWN_OPTION;
        if (!npcAvailable.test(prompt.npc)) return Selection.NPC_UNAVAILABLE;
        synchronized (this) {
            if (!prompts.remove(token, prompt)) return Selection.UNKNOWN;
        }
        try { sink.publish(new DialogueChoice(prompt.npc, player, prompt.behavior, prompt.id, option)); }
        catch (RuntimeException | LinkageError | AssertionError failure) { sinkFailure.accept(failure); }
        return Selection.ACCEPTED;
    }

    private static boolean expired(Prompt prompt, long now) { return now - prompt.expiresAt >= 0; }
}
