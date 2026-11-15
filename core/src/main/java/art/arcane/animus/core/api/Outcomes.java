package art.arcane.animus.core.api;

import art.arcane.animus.api.Outcome;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Chaining of outcome stages: the next step runs only after an OK. */
final class Outcomes {
    private Outcomes() {}

    static <A, B> CompletionStage<Outcome<B>> then(CompletionStage<Outcome<A>> stage, Function<A, CompletionStage<Outcome<B>>> next) {
        return stage.thenCompose((Outcome<A> outcome) -> outcome.status() == Outcome.Status.OK ? next.apply(outcome.value().orElseThrow()) : CompletableFuture.completedFuture(retype(outcome)));
    }

    /** A refusal carried over to another value type. */
    static <A, B> Outcome<B> retype(Outcome<A> outcome) { return new Outcome<>(outcome.status(), Optional.empty(), outcome.diagnostics()); }
}
