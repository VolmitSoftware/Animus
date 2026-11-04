package art.arcane.animus.api;

import java.util.concurrent.CompletionStage;

/** Bounded engine work only; no platform ownership is granted and trusted code cannot be forcibly stopped. */
public interface ModuleScheduler {
    @FunctionalInterface interface Work {String run(Capabilities.Cancellation cancellation) throws Exception;}
    CompletionStage<Outcome<String>> submit(Work work);
}
