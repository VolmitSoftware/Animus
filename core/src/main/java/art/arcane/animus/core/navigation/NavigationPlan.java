package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.PathPlanner;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation handle. Completion callbacks may run on any thread; schedule Bukkit work explicitly. */
public final class NavigationPlan {
    private final UUID id = UUID.randomUUID();
    private final CompletableFuture<PathPlanner.Result> result = new CompletableFuture<>();
    private final CompletionStage<PathPlanner.Result> view = result.minimalCompletionStage();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Runnable cancelAction;
    private volatile CaptureProvenance capture;

    public NavigationPlan(Runnable cancelAction) { this.cancelAction = cancelAction; }
    public UUID id() { return id; }
    /** A read-only completion stage. Use cancel() on this handle to cancel the underlying request. */
    public CompletionStage<PathPlanner.Result> future() { return view; }
    public Optional<CaptureProvenance> capture() { return Optional.ofNullable(capture); }
    public boolean cancel() {
        if (result.isDone() || !cancelled.compareAndSet(false, true)) return false;
        if (!result.complete(cancelledResult())) return false;
        cancelAction.run(); return true;
    }
    public boolean cancelled() { return cancelled.get(); }
    public boolean done() { return result.isDone(); }
    public void captured(CaptureProvenance provenance) { capture = provenance; }
    public void complete(PathPlanner.Result value) { result.complete(value); }
    public void fail(Throwable failure) { result.completeExceptionally(failure); }
    public void cancelFromQueue() {
        cancelled.set(true);
        result.complete(cancelledResult());
    }
    private static PathPlanner.Result cancelledResult() {
        return new PathPlanner.Result(PathPlanner.Status.CANCELLED, List.of(), 0, 0, false);
    }
}
