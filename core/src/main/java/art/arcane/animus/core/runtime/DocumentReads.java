package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingDocuments;
import art.arcane.animus.core.recording.RecordingSource;
import art.arcane.animus.core.recording.RouteRecordings;
import art.arcane.animus.core.storage.DocumentStore;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Skin, appearance, reusable route and recording reads of one runtime. Each read runs on the I/O lane and hands its
 * result back through a queue the owner drains a few at a time; at most {@link #MAX_SKIN_READS} skin reads and
 * {@link #MAX_ROUTE_READS} route reads are in flight. Owner thread, except the tasks themselves.
 */
final class DocumentReads<P, I> {
    static final int MAX_SKIN_READS = 8, MAX_ROUTE_READS = 8;
    private final PersistedNpcService<P, I> service;
    private final Executor io;
    private final Deque<PersistedNpcService<P, I>.Value> routeReadQueue = new ArrayDeque<>();
    private final Set<RouteRead> routeReads = new HashSet<>();
    private final ConcurrentLinkedQueue<RouteReadCompletion> routeReadCompletions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SkinCompletion> skinCompletions = new ConcurrentLinkedQueue<>();
    private final Set<SkinRead> skinTasks = new HashSet<>();
    private final ConcurrentLinkedQueue<AppearanceCompletion> appearanceCompletions = new ConcurrentLinkedQueue<>();
    private final Set<AppearanceRead> appearanceTasks = new HashSet<>();

    DocumentReads(PersistedNpcService<P, I> service, Executor io) { this.service = service; this.io = io; }

    int skinReads() { return skinTasks.size(); }
    int routeReads() { return routeReads.size(); }
    boolean routeReadsBusy() { return routeReads.size() >= MAX_ROUTE_READS; }
    private DocumentStore documents() { return service.runtime.documents(); }

    /** Starts the skin read an activation waits for; false while every skin read is busy, so reconciliation waits. */
    boolean readSkin(PersistedNpcService<P, I>.Entry entry, DefinitionSpec<I> spec) {
        if (skinTasks.size() >= MAX_SKIN_READS) return false;
        SkinRead task = new SkinRead(entry, spec, documents()); entry.task = task; skinTasks.add(task);
        try { io.execute(task); }
        catch (RejectedExecutionException rejectedRead) { skinTasks.remove(task); entry.task = null; entry.error = "$.skin: I/O queue is full; retry reload"; }
        return true;
    }
    void readAppearance(PersistedNpcService<P, I>.Value value, String skin) {
        if (appearanceTasks.size() >= MAX_SKIN_READS) throw new IllegalStateException("Skin reads are busy; retry shortly");
        AppearanceRead task = new AppearanceRead(value, skin, documents());
        value.appearanceTask = task; appearanceTasks.add(task);
        try { io.execute(task); }
        catch (RejectedExecutionException full) {
            appearanceTasks.remove(task); value.appearanceTask = null;
            throw new IllegalStateException("I/O queue is full; retry shortly");
        }
    }
    void cancelAppearance(PersistedNpcService<P, I>.Value value) {
        if (value.appearanceTask == null) return;
        value.appearanceTask.cancelled = true;
        value.appearanceTask = null;
    }
    /** Reads a reusable route on the I/O lane; throws when the read cannot start. */
    void readRoute(PersistedNpcService<P, I>.Value value, String route) {
        RouteRead task = new RouteRead(value, route, null, documents());
        value.routeTask = task; routeReads.add(task);
        try { io.execute(task); }
        catch (RejectedExecutionException full) {
            routeReads.remove(task); value.routeTask = null;
            throw new IllegalStateException("I/O queue is full; retry shortly");
        }
    }
    /** Reads a recording on the I/O lane to start it from its first frame; false when the read could not start. */
    boolean readRecording(PersistedNpcService<P, I>.Value value, RecordingSource source) {
        if (routeReadsBusy()) return false;
        RouteRead task = new RouteRead(value, source, true, documents());
        value.routeTask = task; routeReads.add(task);
        value.navigationStatus = "loading recording " + source.recording();
        try { io.execute(task); }
        catch (RejectedExecutionException full) { routeReads.remove(task); value.routeTask = null; return false; }
        return true;
    }
    /** Queues a read of the NPC's declared route or recording, unless it has none or one is queued or running. */
    void requestRoute(PersistedNpcService<P, I>.Value value) {
        if ((value.routeSource == null && recordingToRead(value) == null && inlineSteps(value) == null) || value.routeReadQueued || value.routeTask != null) return;
        // Each NPC queues at most once, so the queue never outgrows the active NPCs; reads start a few per tick.
        value.routeReadQueued = true; routeReadQueue.addLast(value);
        if (value.route == null) value.navigationStatus = value.routeSource == null ? "loading recording" : "loading reusable route";
    }
    void cancelRoute(PersistedNpcService<P, I>.Value value) {
        routeReadQueue.remove(value); value.routeReadQueued = false;
        if (value.routeTask != null) { value.routeTask.cancelled = true; value.routeTask = null; }
    }
    /** The recording a hotload re-reads: the one playing or kept, else the declared one. A playing recording wins over a declared route. */
    private static RecordingSource recordingToRead(PersistedNpcService<?, ?>.Value value) {
        if (value.recording != null && !value.recording.step()) return value.recording.source();
        if (value.recording == null && value.heldRecording != null && !value.heldRecording.step()) return value.heldRecording.source();
        return value.recordingSource;
    }
    /** The definition's inline route when it has recording steps, whose recordings a route load reads; otherwise null. */
    private static WaypointSettings inlineSteps(PersistedNpcService<?, ?>.Value value) {
        try {
            WaypointSettings inline = WaypointSettings.parse(value.spec.definition()).orElse(null);
            return inline == null || inline.recordings().isEmpty() ? null : inline;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Finished skin, appearance and route reads, a bounded number of each, then the queued route reads that fit. */
    void drain() {
        for (int i = 0; i < MAX_SKIN_READS; i++) {
            SkinCompletion completion = skinCompletions.poll(); if (completion == null) break;
            try { finishSkin(completion); }
            catch (RuntimeException failure) { service.quarantine(completion.task.entry.id, "activation", failure); }
        }
        for (int i = 0; i < MAX_SKIN_READS; i++) {
            AppearanceCompletion completion = appearanceCompletions.poll(); if (completion == null) break;
            finishAppearance(completion);
        }
        for (int i = 0; i < MAX_ROUTE_READS; i++) {
            RouteReadCompletion completion = routeReadCompletions.poll(); if (completion == null) break;
            RouteRead task = completion.task; routeReads.remove(task); PersistedNpcService<P, I>.Value value = task.value;
            boolean ownsTask = value.routeTask == task;
            if (ownsTask) value.routeTask = null;
            if (task.cancelled || !ownsTask || !service.valueCurrent(value)) continue;
            if (task.recording != null) service.recordings.finishRead(value, task.recording, task.start, completion.recording, completion.error);
            else service.routes.finishRead(value, task.source, task.inline != null, completion.settings, completion.recordings, completion.error);
        }
        for (int i = 0; i < MAX_ROUTE_READS && routeReads.size() < MAX_ROUTE_READS && !routeReadQueue.isEmpty(); i++) {
            PersistedNpcService<P, I>.Value value = routeReadQueue.removeFirst(); value.routeReadQueued = false;
            if (!service.valueCurrent(value)) continue;
            RecordingSource recording = (value.recording != null && !value.recording.step()) || value.routeSource == null ? recordingToRead(value) : null;
            WaypointSettings inline = recording == null && value.routeSource == null ? inlineSteps(value) : null;
            if (recording == null && value.routeSource == null && inline == null) continue;
            RouteRead task = recording != null ? new RouteRead(value, recording, false, documents())
                    : new RouteRead(value, value.routeSource, inline, documents());
            value.routeTask = task; routeReads.add(task);
            try { io.execute(task); }
            catch (RejectedExecutionException full) { routeReads.remove(task); value.routeTask = null; value.routeError = "I/O queue full; retry reload"; }
        }
    }
    private void finishSkin(SkinCompletion completion) {
        SkinRead task = completion.task; skinTasks.remove(task);
        PersistedNpcService<P, I>.Entry entry = task.entry;
        if (service.closed || task.cancelled || service.entries.get(entry.id) != entry || entry.task != task
                || service.runtime.npcs().snapshot().get(entry.id) != task.spec.definition()) return;
        entry.task = null;
        service.skinRead(entry, task.spec, completion.skin, completion.error);
    }
    private void finishAppearance(AppearanceCompletion completion) {
        AppearanceRead task = completion.task; appearanceTasks.remove(task); PersistedNpcService<P, I>.Value value = task.value;
        boolean owns = value.appearanceTask == task;
        if (owns) value.appearanceTask = null;
        if (task.cancelled || !owns || !service.valueCurrent(value)) return;
        if (completion.error != null) { service.failures.record("skin read", "npc=" + value.spec.definition().id(), "$.skin: " + completion.error); return; }
        value.displaySkin = completion.skin;
        service.views.represent(value);
    }
    /** Cancels every read in flight and forgets every queued read and completion, for a runtime that stops or suspends. */
    void cancelAll() {
        for (RouteRead task : routeReads) task.cancelled = true;
        routeReads.clear(); routeReadQueue.clear(); routeReadCompletions.clear();
        for (SkinRead task : skinTasks) task.cancelled = true;
        skinTasks.clear(); skinCompletions.clear();
        for (AppearanceRead task : appearanceTasks) task.cancelled = true;
        appearanceTasks.clear(); appearanceCompletions.clear();
    }

    private final class SkinCompletion {
        final SkinRead task; final BodySkin skin; final String error;
        SkinCompletion(SkinRead task, BodySkin skin, String error) { this.task = task; this.skin = skin; this.error = error; }
    }
    private final class RouteReadCompletion {
        final RouteRead task; final WaypointSettings settings; final RouteRecordings recordings; final RecordingDocument recording; final String error;
        RouteReadCompletion(RouteRead task, WaypointSettings settings, RouteRecordings recordings, RecordingDocument recording, String error) {
            this.task = task; this.settings = settings; this.recordings = recordings; this.recording = recording; this.error = error;
        }
    }
    private final class AppearanceCompletion {
        final AppearanceRead task; final BodySkin skin; final String error;
        AppearanceCompletion(AppearanceRead task, BodySkin skin, String error) { this.task = task; this.skin = skin; this.error = error; }
    }
    final class AppearanceRead implements Runnable {
        final PersistedNpcService<P, I>.Value value; final String reference; final DocumentStore documents; volatile boolean cancelled;
        AppearanceRead(PersistedNpcService<P, I>.Value value, String reference, DocumentStore documents) { this.value = value; this.reference = reference; this.documents = documents; }
        @Override public void run() {
            BodySkin skin = null; String error = null;
            try { if (!cancelled && !service.closed) skin = SkinDocuments.read(documents, reference); }
            catch (Exception failure) { error = PersistedNpcService.concise(failure); }
            if (!service.closed) appearanceCompletions.add(new AppearanceCompletion(this, skin, error));
        }
    }
    /**
     * Reads one reusable route and the recordings its steps play, the recordings of an inline route's steps, or one
     * recording; every recording within the live {@code [recordings] maximumFileBytes}.
     */
    final class RouteRead implements Runnable {
        final PersistedNpcService<P, I>.Value value; final String source; final WaypointSettings inline; final RecordingSource recording; final boolean start; final int maximumBytes;
        final DocumentStore documents; volatile boolean cancelled;
        /** A reusable route {@code source}, or with {@code inline} the definition's own route, whose step recordings are read. */
        RouteRead(PersistedNpcService<P, I>.Value value, String source, WaypointSettings inline, DocumentStore documents) {
            this.value = value; this.source = source; this.inline = inline; this.recording = null; this.start = false;
            this.maximumBytes = service.recordingSettings.maximumFileBytes(); this.documents = documents;
        }
        /** {@code start}: play the recording whatever was playing; otherwise a hotload re-read that keeps an unchanged recording. */
        RouteRead(PersistedNpcService<P, I>.Value value, RecordingSource recording, boolean start, DocumentStore documents) {
            this.value = value; this.source = recording.recording(); this.inline = null; this.recording = recording; this.start = start;
            this.maximumBytes = service.recordingSettings.maximumFileBytes(); this.documents = documents;
        }
        @Override public void run() {
            WaypointSettings loaded = null; RouteRecordings steps = null; RecordingDocument played = null; String error = null;
            try {
                if (!cancelled && !service.closed) {
                    if (recording != null) played = RecordingDocuments.read(documents, source, maximumBytes);
                    else {
                        loaded = inline != null ? inline : RouteDocuments.read(documents, source);
                        steps = RouteRecordings.read(loaded, (String id) -> RecordingDocuments.read(documents, id, maximumBytes));
                    }
                }
            } catch (Exception failure) { error = PersistedNpcService.concise(failure); }
            if (!service.closed) routeReadCompletions.add(new RouteReadCompletion(this, loaded, steps, played, error));
        }
    }
    final class SkinRead implements Runnable {
        final PersistedNpcService<P, I>.Entry entry; final DefinitionSpec<I> spec; final DocumentStore documents;
        volatile boolean cancelled;
        SkinRead(PersistedNpcService<P, I>.Entry entry, DefinitionSpec<I> spec, DocumentStore documents) { this.entry = entry; this.spec = spec; this.documents = documents; }
        @Override public void run() {
            BodySkin skin = null; String error = null;
            try {
                if (!cancelled && !service.closed) {
                    skin = SkinDocuments.read(documents, spec.skin());
                }
            } catch (Exception failure) { error = PersistedNpcService.concise(failure); }
            // At most MAX_SKIN_READS tasks remain admitted until their completion is drained by the owner.
            if (!service.closed) skinCompletions.add(new SkinCompletion(this, skin, error));
        }
    }
}
