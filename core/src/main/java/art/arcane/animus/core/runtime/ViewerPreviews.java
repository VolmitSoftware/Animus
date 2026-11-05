package art.arcane.animus.core.runtime;

import art.arcane.animus.core.behavior.trigger.HiddenNpcs;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.DocumentStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Viewer-private previews of candidate NPC definitions. Each viewer has at most one staged preview; its candidates are
 * shown to that viewer only, as packet bodies that are never registered in a world, indexed or persisted, and the live
 * NPCs they stand in for (same id or UUID) are hidden from that viewer alone. The runtime's viewer loop presents the
 * staged set through {@link PreviewBodies} on the viewer's owner. Every method here may be called from any thread.
 * <p>
 * The hidden live NPC also stops affecting that viewer: platforms leave the viewer out of its speech, sounds and
 * particles through {@link #hides(UUID, NpcRuntime.ActiveNpc)}, and the trigger dispatcher drops the viewer's events for it.
 * <p>
 * Preview bodies are inert: they run no behaviors or triggers, take and deal no damage, and an interaction with one
 * reaches no listener, selection, GUI, shop or ride.
 */
public final class ViewerPreviews implements HiddenNpcs, AutoCloseable {
    /** How long a preview may wait for the viewer's owner to pick it up before it is refused. */
    public static final Duration ACCEPT_TIMEOUT = Duration.ofSeconds(10);

    /** One candidate body and its stored skin, or a null skin. */
    public record Candidate(NpcDefinition definition, BodySkin skin) {
        public Candidate { Objects.requireNonNull(definition); }
    }

    /** What one viewer sees instead of the live set. The generation changes on every present, so a replace swaps the bodies. */
    public record Staged(String previewId, UUID viewer, long generation, List<Candidate> candidates, Set<String> ids, Set<UUID> uuids,
                         CompletableFuture<Void> accepted, Consumer<String> ended) {
        public Staged { candidates = List.copyOf(candidates); ids = Set.copyOf(ids); uuids = Set.copyOf(uuids); }

        /** The live NPC with this definition id or UUID is replaced by a candidate for this viewer. */
        public boolean hides(String id, UUID uuid) { return ids.contains(id) || uuids.contains(uuid); }
    }

    private final Executor io;
    private final DocumentStore documents;
    private final Map<UUID, Staged> byViewer = new ConcurrentHashMap<>();
    private final Map<String, Long> requests = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;

    /** Skins are read on {@code io}, never on a server thread. */
    public ViewerPreviews(Executor io, DocumentStore documents) {
        this.io = Objects.requireNonNull(io);
        this.documents = Objects.requireNonNull(documents);
    }

    /**
     * Stages (or replaces) a preview for the viewer. Completes once the viewer's owner parsed the candidates against the
     * running server; fails with the reason when a candidate cannot be shown or the owner does not pick it up in time.
     * A different preview already staged for the same viewer ends with {@code ended}.
     */
    public CompletionStage<Void> present(String previewId, UUID viewer, List<NpcDefinition> npcs, Consumer<String> ended) {
        Objects.requireNonNull(previewId); Objects.requireNonNull(viewer); Objects.requireNonNull(ended);
        List<NpcDefinition> definitions = List.copyOf(npcs);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("the NPC runtime is closed"));
        long request = sequence.incrementAndGet();
        requests.put(previewId, request);
        CompletableFuture<List<Candidate>> read;
        try { read = CompletableFuture.supplyAsync(() -> candidates(definitions), io); }
        catch (RejectedExecutionException full) {
            requests.remove(previewId, request);
            return CompletableFuture.failedFuture(new IllegalStateException("the file lane is full; retry shortly"));
        }
        return read.thenCompose((List<Candidate> candidates) -> stage(previewId, viewer, request, candidates, ended));
    }

    private List<Candidate> candidates(List<NpcDefinition> definitions) {
        List<Candidate> candidates = new ArrayList<>();
        for (NpcDefinition definition : definitions) {
            BodySkin skin = null;
            if (definition.json().has("skin")) {
                String reference = definition.json().get("skin").getAsString();
                try { skin = SkinDocuments.read(documents, reference); }
                catch (Exception failure) { throw new CompletionException(new IllegalArgumentException(definition.id() + " skin " + reference + ": " + PersistedNpcService.concise(failure))); }
            }
            candidates.add(new Candidate(definition, skin));
        }
        return candidates;
    }

    private CompletableFuture<Void> stage(String previewId, UUID viewer, long request, List<Candidate> candidates, Consumer<String> ended) {
        Set<String> ids = new HashSet<>();
        Set<UUID> uuids = new HashSet<>();
        for (Candidate candidate : candidates) { ids.add(candidate.definition().id()); uuids.add(candidate.definition().uuid()); }
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        Staged displaced;
        synchronized (this) {
            Long current = requests.get(previewId);
            if (closed || current == null || current != request) return CompletableFuture.failedFuture(new CancellationException("the preview ended or was replaced"));
            displaced = byViewer.put(viewer, new Staged(previewId, viewer, request, candidates, ids, uuids, accepted, ended));
        }
        if (displaced != null) {
            displaced.accepted().completeExceptionally(new CancellationException("the preview was replaced"));
            if (!displaced.previewId().equals(previewId)) {
                requests.remove(displaced.previewId());
                displaced.ended().accept("ended");
            }
        }
        return accepted.orTimeout(ACCEPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).exceptionallyCompose((Throwable failure) -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof java.util.concurrent.TimeoutException)
                return CompletableFuture.failedFuture(new IllegalStateException("the viewer's presentation did not pick up the preview; the NPC runtime may be unavailable for this player"));
            return CompletableFuture.failedFuture(cause);
        });
    }

    /** Ends a preview on request, expiry or token close; the viewer's live view returns. */
    public void end(String previewId) {
        requests.remove(previewId);
        Staged removed = null;
        synchronized (this) {
            for (Staged staged : byViewer.values())
                if (staged.previewId().equals(previewId) && byViewer.remove(staged.viewer(), staged)) { removed = staged; break; }
        }
        if (removed != null) removed.accepted().completeExceptionally(new CancellationException("the preview ended"));
    }

    /** The viewer left the server: their preview ends with {@code player-left}. */
    public void left(UUID viewer) {
        Staged removed = byViewer.remove(viewer);
        if (removed == null) return;
        requests.remove(removed.previewId());
        removed.accepted().completeExceptionally(new CancellationException("the player left"));
        removed.ended().accept("player-left");
    }

    /** The viewer's owner could not show a staged preview. */
    void refuse(Staged staged, String reason) { staged.accepted().completeExceptionally(new IllegalArgumentException(reason)); }

    /** The viewer's staged preview, or null. */
    public Staged staged(UUID viewer) { return byViewer.get(viewer); }

    /** The live NPC is replaced by a preview candidate for this viewer. Cheap enough for every visibility check. */
    @Override public boolean hides(UUID viewer, String id, UUID uuid) {
        if (byViewer.isEmpty()) return false;
        Staged staged = byViewer.get(viewer);
        return staged != null && staged.hides(id, uuid);
    }

    /** The viewer is left out of this live NPC's speech, sounds and particles while a preview of theirs stands in for it. */
    public boolean hides(UUID viewer, NpcRuntime.ActiveNpc npc) { return hides(viewer, npc.id(), npc.state().npcId()); }

    public int count() { return byViewer.size(); }

    @Override public void close() {
        List<Staged> staged;
        synchronized (this) { closed = true; staged = List.copyOf(byViewer.values()); byViewer.clear(); requests.clear(); }
        for (Staged preview : staged) preview.accepted().completeExceptionally(new CancellationException("the NPC runtime closed"));
    }
}
