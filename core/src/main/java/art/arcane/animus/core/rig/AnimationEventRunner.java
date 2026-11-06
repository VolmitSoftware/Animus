package art.arcane.animus.core.rig;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs animation events through the capability registry, so they get the same argument checks, settings and owner
 * routing as behavior actions. Events are cosmetic: a refused one is logged once per animation, capability and reason.
 * Thread-safe.
 */
public final class AnimationEventRunner implements NpcRig.Events {
    /** Runs one action for an NPC with no triggering player. */
    @FunctionalInterface public interface Actions { CompletionStage<Outcome<String>> run(Capabilities.Key key, NpcHandle npc, String argumentsJson); }

    private static final int MAX_REPORTED = 256;
    private final Actions actions;
    private final Consumer<String> log;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public AnimationEventRunner(Actions actions, Consumer<String> log) {
        this.actions = Objects.requireNonNull(actions); this.log = Objects.requireNonNull(log);
    }

    @Override public void fired(UUID npc, RigPlayback.Fired fired) {
        AnimationDocument.Event event = fired.event();
        int colon = event.capability().indexOf(':');
        Capabilities.Key key = new Capabilities.Key(event.capability().substring(0, colon), event.capability().substring(colon + 1));
        CompletionStage<Outcome<String>> result;
        try { result = actions.run(key, new NpcHandle(npc), event.arguments()); }
        catch (RuntimeException failure) { report(fired, String.valueOf(failure.getMessage())); return; }
        result.whenComplete((Outcome<String> outcome, Throwable failure) -> {
            if (failure != null) report(fired, String.valueOf(failure.getMessage()));
            else if (outcome.status() != Outcome.Status.OK && outcome.status() != Outcome.Status.BUSY) report(fired, reason(outcome));
        });
    }

    private static String reason(Outcome<String> outcome) {
        return outcome.diagnostics().isEmpty() ? outcome.status().name() : outcome.diagnostics().stream().map(Diagnostic::message).reduce((String a, String b) -> a + "; " + b).orElseThrow();
    }
    private void report(RigPlayback.Fired fired, String reason) {
        String line = "Animation " + fired.animation() + " event " + fired.event().capability() + " at tick " + fired.event().tick() + " did not run: " + reason;
        if (reported.size() < MAX_REPORTED && reported.add(line)) log.accept(line);
    }
}
