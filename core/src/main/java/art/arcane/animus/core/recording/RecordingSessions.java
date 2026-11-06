package art.arcane.animus.core.recording;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogReloads;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.RecordingControl;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.virtual.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

/**
 * Performance capture and ghost previews on any platform. One recording session and one preview per player, capped by
 * {@code [recordings] maximumSessions} and {@code maximumPreviews}, read live; lowering a cap lets running ones finish.
 * Every session and preview ticks on its player's owner. Saves run on the file lane as one transaction each.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public final class RecordingSessions<P, I> implements RecordingControl, AutoCloseable {
    private final RecordingHost<P, I> host;
    private final AnimusRuntime runtime;
    private final Executor files;
    private final RecordingRoster<RecordingSession<P, I>> sessions = new RecordingRoster<>("recording");
    private final RecordingRoster<Preview<P, I>> previews = new RecordingRoster<>("preview");
    private final Map<UUID, Boolean> ghostChoice = new ConcurrentHashMap<>();
    private final AutoCloseable settingsSubscription;
    private volatile RecordingSettings settings;
    private volatile List<String> knownIds = List.of();
    private volatile long idsRefreshed;
    private volatile boolean closed;

    /** A saved recording playing once for one player. */
    private static final class Preview<P, I> {
        final P viewer;
        final CommandActor actor;
        final String id;
        final String world;
        GhostReplay<P, I> ghost;
        AutoCloseable task;
        Vec3 last;
        boolean ended;
        Preview(RecordingHost<P, I> host, P viewer, CommandActor actor, String id) {
            this.viewer = viewer; this.actor = actor; this.id = id; world = host.world(viewer); last = host.position(viewer);
        }
    }

    public RecordingSessions(RecordingHost<P, I> host, AnimusRuntime runtime, Executor files) {
        this.host = host;
        this.runtime = runtime;
        this.files = files;
        settings = runtime.recordingSettings();
        settingsSubscription = runtime.onSettings((PluginSettings next) -> settings = next.recordings());
    }

    // Commands, each on the player's owner.

    @Override public void start(CommandActor actor, String id) { player(actor, "start").ifPresent((P player) -> start(player, actor, id)); }
    @Override public void stop(CommandActor actor) { player(actor, "stop").ifPresent((P player) -> stop(player, actor)); }
    @Override public void cancel(CommandActor actor) { player(actor, "cancel").ifPresent((P player) -> cancel(player, actor)); }
    @Override public List<String> status(CommandActor actor) { return status(actor.player().flatMap(host::player)); }
    @Override public void ghost(CommandActor actor, Optional<Boolean> on) {
        player(actor, "show the ghost of").ifPresent((P player) -> ghost(player, actor, on.orElseGet(() -> !ghostWanted(player))));
    }
    @Override public void preview(CommandActor actor, String id) { player(actor, "preview").ifPresent((P player) -> preview(player, actor, id)); }
    @Override public void stopPreview(CommandActor actor) { player(actor, "stop the preview of").ifPresent((P player) -> stopPreview(player, actor)); }

    /** The online player behind {@code actor}; empty after telling the console it cannot. */
    private Optional<P> player(CommandActor actor, String verb) {
        Optional<P> player = actor.player().flatMap(host::player);
        if (player.isEmpty()) actor.tell("Only a player can " + verb + " a recording.");
        return player;
    }

    void start(P player, CommandActor actor, String id) {
        if (!id.matches(RecordingDocument.ID)) { actor.tell("Recording ids are 1 to 64 lowercase letters, digits, _ or -."); return; }
        RecordingSettings current = settings;
        if (sessions.get(host.id(player)).isPresent()) { actor.tell("You are already recording; /animus record stop or cancel first."); return; }
        RecordingSession<P, I> session;
        try { session = new RecordingSession<>(host, player, actor, id, current); }
        catch (IllegalArgumentException invalid) { actor.tell("Cannot record here: " + invalid.getMessage()); return; }
        Optional<String> refused = sessions.admit(host.id(player), session, current.enabled() && !closed, current.maximumSessions());
        if (refused.isPresent()) { actor.tell(refused.get()); return; }
        session.task = host.everyTick(player, () -> tick(session), () -> end(session, RecordingCapture.End.QUIT));
        String ghost = !ghostWanted(player) ? "off" : host.ghostsUnavailable().map((String reason) -> "unavailable, " + reason).orElse("every " + current.ghostIntervalTicks() + " ticks");
        actor.tell("Recording " + id + ": sampling every " + current.sampleTicks() + " ticks, at most " + current.maximumTicks() + " ticks; ghost " + ghost + ". /animus record stop saves it.");
    }

    void stop(P player, CommandActor actor) {
        Optional<RecordingSession<P, I>> found = sessions.get(host.id(player));
        if (found.isEmpty()) { actor.tell("You are not recording."); return; }
        RecordingSession<P, I> session = found.get();
        if (session.state == RecordingSession.State.SAVING) { actor.tell("Recording " + session.capture.id() + " is already saving."); return; }
        end(session, RecordingCapture.End.STOPPED);
    }

    void cancel(P player, CommandActor actor) {
        Optional<RecordingSession<P, I>> found = sessions.get(host.id(player));
        if (found.isEmpty()) { actor.tell("You are not recording."); return; }
        RecordingSession<P, I> session = found.get();
        if (session.state == RecordingSession.State.SAVING) { actor.tell("Recording " + session.capture.id() + " is saving and can no longer be cancelled."); return; }
        retire(session);
        actor.tell("Recording " + session.capture.id() + " discarded.");
    }

    void ghost(P player, CommandActor actor, boolean on) {
        ghostChoice.put(host.id(player), on);
        if (!on) {
            sessions.get(host.id(player)).ifPresent(this::closeGhost);
            actor.tell("Recording ghost off.");
            return;
        }
        RecordingSettings current = settings;
        if (!current.ghostEnabled()) actor.tell("Recording ghost on for you, but [recordings] ghostEnabled is false in config.toml.");
        else actor.tell("Recording ghost on" + host.ghostsUnavailable().map((String reason) -> ", but ghosts are unavailable: " + reason).orElse("") + ".");
    }

    List<String> status(Optional<P> player) {
        RecordingSettings current = settings;
        List<String> lines = new ArrayList<>();
        lines.add("Recordings " + (current.enabled() ? "enabled" : "disabled") + " | sessions " + sessions.size() + "/" + current.maximumSessions()
                + " | previews " + previews.size() + "/" + current.maximumPreviews() + " | ghosts "
                + (current.ghostEnabled() ? host.ghostsUnavailable().map((String reason) -> "unavailable: " + reason).orElse("available") : "off in config.toml"));
        for (RecordingSession<P, I> session : sessions.values())
            if (player.isEmpty() || host.id(session.player).equals(host.id(player.get())) || host.admin(player.get()))
                lines.add("  " + session.describe());
        for (Preview<P, I> preview : previews.values()) lines.add("  preview " + preview.id + " for " + host.name(preview.viewer));
        return lines;
    }

    void preview(P player, CommandActor actor, String id) {
        if (!id.matches(RecordingDocument.ID)) { actor.tell("Recording ids are 1 to 64 lowercase letters, digits, _ or -."); return; }
        RecordingSettings current = settings;
        if (!current.enabled()) { actor.tell("Recordings are disabled; set [recordings] enabled = true in config.toml."); return; }
        Optional<String> unavailable = host.ghostsUnavailable();
        if (unavailable.isPresent()) { actor.tell("Previews are unavailable: " + unavailable.get()); return; }
        int maximumBytes = current.maximumFileBytes();
        try {
            files.execute(() -> {
                RecordingDocument document;
                String problem = null;
                try { document = RecordingDocuments.read(runtime.documents(), id, maximumBytes); }
                catch (IOException | RuntimeException failure) { document = null; problem = failure instanceof NoSuchFileException ? "no recording " + id : String.valueOf(failure.getMessage()); }
                RecordingDocument read = document;
                String refused = problem;
                host.later(player, () -> {
                    if (refused != null) actor.tell("Cannot preview " + id + ": " + refused);
                    else beginPreview(player, actor, read);
                }, () -> {});
            });
        } catch (RejectedExecutionException busy) { actor.tell("The file queue is full; try the preview again shortly."); }
    }

    void stopPreview(P player, CommandActor actor) {
        Optional<Preview<P, I>> found = previews.get(host.id(player));
        if (found.isEmpty()) { actor.tell("No preview is playing."); return; }
        endPreview(found.get(), "stopped");
    }

    /** Recording ids for tab completion, from a listing refreshed on the file lane at most every five seconds. */
    @Override public List<String> ids() {
        long now = System.nanoTime();
        if (now - idsRefreshed > 5_000_000_000L && !closed) {
            idsRefreshed = now;
            try {
                files.execute(() -> {
                    try {
                        Path folder = runtime.documents().resolve("recordings");
                        if (!Files.isDirectory(folder)) { knownIds = List.of(); return; }
                        try (Stream<Path> listing = Files.list(folder)) {
                            knownIds = listing.map((Path path) -> path.getFileName().toString()).filter((String name) -> name.endsWith(".json"))
                                    .map((String name) -> name.substring(0, name.length() - 5)).filter((String name) -> name.matches(RecordingDocument.ID))
                                    .sorted().limit(500).toList();
                        }
                    } catch (IOException | RuntimeException ignored) { /* Completion is a convenience; the listing stays as it was. */ }
                });
            } catch (RejectedExecutionException busy) { /* Retried on the next completion. */ }
        }
        return knownIds;
    }

    // Player events, each on the player's owner.

    /** The player swung its main arm. */
    public void swung(P player) { recording(player).ifPresent((RecordingSession<P, I> session) -> session.capture.swing()); }

    /** The player right-clicked an openable block (door, trapdoor, fence gate) with its main hand. */
    public void used(P player, int x, int y, int z, String block) {
        recording(player).ifPresent((RecordingSession<P, I> session) -> session.capture.use(x, y, z, block));
    }

    private Optional<RecordingSession<P, I>> recording(P player) {
        return sessions.get(host.id(player)).filter((RecordingSession<P, I> session) -> session.player == player && session.state == RecordingSession.State.RECORDING);
    }

    // Ticks.

    private void tick(RecordingSession<P, I> session) {
        if (session.state != RecordingSession.State.RECORDING) {
            if (session.state == RecordingSession.State.HELD && !host.online(session.player)) {
                host.warn("Recording " + session.capture.id() + " was too large to save and its recorder left; it is discarded.");
                retire(session);
            }
            return;
        }
        RecordingSettings current = settings;
        Optional<RecordingCapture.End> end = session.tick(current.maximumTicks());
        if (end.isPresent()) { end(session, end.get()); return; }
        if (current.enabled() && current.ghostEnabled() && ghostWanted(session.player) && host.ghostsUnavailable().isEmpty()) {
            Optional<RecordingDocument> stretch = session.ghostDue(current);
            if (stretch.isPresent()) {
                try { session.ghost = host.ghost(session.player, stretch.get(), host.name(session.player), host.skin(session.player), current); }
                catch (RuntimeException failure) { host.warn("Recording ghost failed: " + failure.getMessage()); }
            }
        } else closeGhost(session);
        if (session.ghost != null) {
            session.ghost.tick();
            if (session.ghost.finished()) closeGhost(session);
        }
    }

    private void end(RecordingSession<P, I> session, RecordingCapture.End reason) {
        if (session.state == RecordingSession.State.SAVING || session.state == RecordingSession.State.DONE) return;
        closeGhost(session);
        Optional<RecordingCapture.Take> take = session.capture.finish();
        String why = reason == RecordingCapture.End.STOPPED ? "" : " (" + reason.reason() + ")";
        if (take.isEmpty()) {
            retire(session);
            report(session, "Recording " + session.capture.id() + " discarded: under " + RecordingCapture.MINIMUM_TICKS + " ticks" + why + ".");
            return;
        }
        session.state = RecordingSession.State.SAVING;
        int maximumBytes = settings.maximumFileBytes();
        try {
            files.execute(() -> {
                RecordingWriter.Outcome outcome;
                String failure = null;
                try { outcome = RecordingWriter.save(runtime.documents(), runtime.catalogs(), take.get(), maximumBytes); }
                catch (IOException | RuntimeException problem) { outcome = null; failure = String.valueOf(problem.getMessage()); }
                RecordingWriter.Outcome saved = outcome;
                String problem = failure;
                Runnable finish = () -> saved(session, saved, problem, why);
                host.later(session.player, finish, finish);
            });
        } catch (RejectedExecutionException busy) { saved(session, null, "the file queue is full; run /animus record stop again", why); }
    }

    private void saved(RecordingSession<P, I> session, RecordingWriter.Outcome outcome, String problem, String why) {
        String id = session.capture.id();
        if (outcome instanceof RecordingWriter.Saved saved) {
            retire(session);
            report(session, "Recording " + id + " saved" + why + ": " + session.capture.durationTicks() + " ticks, " + saved.frames() + " frames, "
                    + saved.events() + " events, " + saved.bytes() + " bytes, revision " + saved.revision() + ". Rollback: /animus rollback " + saved.transaction());
            idsRefreshed = 0;
            return;
        }
        if (!host.online(session.player)) {
            retire(session);
            host.warn("Recording " + id + " was not saved after its recorder left: "
                    + (outcome instanceof RecordingWriter.TooLarge large ? large.bytes() + " bytes, above maximumFileBytes " + large.maximum() : problem));
            return;
        }
        session.state = RecordingSession.State.HELD;
        if (outcome instanceof RecordingWriter.TooLarge large)
            report(session, "Recording " + id + " is " + large.bytes() + " bytes, above [recordings] maximumFileBytes " + large.maximum()
                    + "; it is held. Raise the limit and /animus record stop again, or /animus record cancel.");
        else report(session, "Recording " + id + " was not saved: " + problem + ". It is held; /animus record stop retries, cancel discards.");
    }

    private void report(RecordingSession<P, I> session, String message) {
        if (host.online(session.player)) session.actor.tell(message);
        else host.info(message);
    }

    private void retire(RecordingSession<P, I> session) {
        session.state = RecordingSession.State.DONE;
        closeGhost(session);
        sessions.remove(host.id(session.player), session);
        close(session.task);
    }

    private void closeGhost(RecordingSession<P, I> session) {
        if (session.ghost == null) return;
        session.ghost.close();
        session.ghost = null;
        host.release(session.player);
    }

    private boolean ghostWanted(P player) { return ghostChoice.getOrDefault(host.id(player), Boolean.TRUE); }

    // Previews.

    private void beginPreview(P player, CommandActor actor, RecordingDocument document) {
        RecordingSettings current = settings;
        if (closed || !host.online(player) || host.dead(player) || !host.admin(player)) return;
        if (!current.enabled()) { actor.tell("Recordings are disabled; set [recordings] enabled = true in config.toml."); return; }
        if (host.resolveWorld(document.world()).isEmpty()) { actor.tell("Recording " + document.id() + " names world " + document.world() + ", which is not loaded."); return; }
        if (!RecordingWorlds.same(host.world(player), document.world(), host::resolveWorld)) {
            actor.tell("Recording " + document.id() + " was made in " + document.world() + "; go there to preview it.");
            return;
        }
        previews.get(host.id(player)).ifPresent((Preview<P, I> running) -> endPreview(running, null));
        Preview<P, I> preview = new Preview<>(host, player, actor, document.id());
        Optional<String> refused = previews.admit(host.id(player), preview, true, current.maximumPreviews());
        if (refused.isPresent()) { actor.tell(refused.get()); return; }
        Optional<P> recorder = host.player(document.recordedBy());
        String name = recorder.map(host::name).or(() -> host.knownName(document.recordedBy())).orElse("Ghost");
        Optional<BodySkin> skin = recorder.flatMap(host::skin);
        try { preview.ghost = host.ghost(player, document, name, skin, current); }
        catch (RuntimeException failure) { previews.remove(host.id(player), preview); actor.tell("Cannot preview " + document.id() + ": " + failure.getMessage()); return; }
        preview.task = host.everyTick(player, () -> tickPreview(preview), () -> endPreview(preview, null));
        RecordingDocument.Origin origin = document.origin();
        actor.tell("Previewing " + document.id() + " (" + document.durationTicks() + " ticks) at " + Math.round(origin.x()) + " " + Math.round(origin.y()) + " "
                + Math.round(origin.z()) + ". /animus record preview " + document.id() + " action=stop ends it.");
    }

    private void tickPreview(Preview<P, I> preview) {
        if (preview.ended) return;
        P viewer = preview.viewer;
        RecordingSettings current = settings;
        if (!host.online(viewer)) { endPreview(preview, null); return; }
        if (host.dead(viewer)) { endPreview(preview, "you died"); return; }
        if (!host.world(viewer).equals(preview.world)) { endPreview(preview, "you changed world"); return; }
        if (!host.admin(viewer)) { endPreview(preview, "you no longer have animus.admin"); return; }
        if (!current.enabled()) { endPreview(preview, "recordings were disabled"); return; }
        Vec3 at = host.position(viewer);
        if (at.distance(preview.last) > RecordingCapture.TELEPORT_DISTANCE) { endPreview(preview, "you teleported"); return; }
        preview.last = at;
        preview.ghost.tick();
        if (preview.ghost.finished()) endPreview(preview, "finished");
    }

    /** Ends a preview; {@code reason} is told to the viewer when it is not null. */
    private void endPreview(Preview<P, I> preview, String reason) {
        if (preview.ended) return;
        preview.ended = true;
        if (preview.ghost != null) { preview.ghost.close(); host.release(preview.viewer); }
        previews.remove(host.id(preview.viewer), preview);
        close(preview.task);
        if (reason != null && host.online(preview.viewer)) preview.actor.tell("Preview " + preview.id + " ended: " + reason + ".");
    }

    private static void close(AutoCloseable task) {
        if (task == null) return;
        try { task.close(); }
        catch (Exception ignored) { /* The scheduler stops with the plugin. */ }
    }

    /**
     * Saves every running recording directly, since the file lane closes with the runtime, then removes every ghost.
     * Catalogs are not reloaded: the next start reads the files.
     */
    @Override public void close() {
        closed = true;
        close(settingsSubscription);
        for (Preview<P, I> preview : previews.values()) endPreview(preview, null);
        for (RecordingSession<P, I> session : sessions.values()) {
            close(session.task);
            if (session.state != RecordingSession.State.RECORDING) { retire(session); continue; }
            Optional<RecordingCapture.Take> take = session.capture.finish();
            retire(session);
            if (take.isEmpty()) continue;
            try {
                RecordingWriter.Outcome outcome = RecordingWriter.save(runtime.documents(), CatalogReloads.NONE, take.get(), settings.maximumFileBytes());
                host.info("Recording " + session.capture.id() + (outcome instanceof RecordingWriter.Saved ? " saved" : " not saved, too large") + " at shutdown.");
            } catch (IOException | RuntimeException failure) { host.warn("Recording " + session.capture.id() + " not saved at shutdown: " + failure.getMessage()); }
        }
        host.closeGhosts();
    }
}
