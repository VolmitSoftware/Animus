package art.arcane.animus.bukkit.recording;

import art.arcane.animus.bukkit.BukkitWorldLookup;
import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogReloads;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingDocuments;
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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Performance capture and ghost previews. One recording session and one preview per player, capped by
 * {@code [recordings] maximumSessions} and {@code maximumPreviews}, read live; lowering a cap lets running ones finish.
 * Every session and preview ticks on its player's owner. Saves run on the file lane as one transaction each.
 */
public final class RecordingSessions implements Listener, AutoCloseable {
    private static final String ADMIN = "animus.admin";
    private final JavaPlugin plugin;
    private final AnimusRuntime runtime;
    private final Executor files;
    private final RecorderOwners owners;
    private final GhostBodies ghosts;
    private final RecordingRoster<RecordingSession> sessions = new RecordingRoster<>("recording");
    private final RecordingRoster<Preview> previews = new RecordingRoster<>("preview");
    private final Map<UUID, Boolean> ghostChoice = new ConcurrentHashMap<>();
    private final AutoCloseable settingsSubscription;
    private volatile RecordingSettings settings;
    private volatile List<String> knownIds = List.of();
    private volatile long idsRefreshed;
    private volatile boolean closed;

    /** A saved recording playing once for one player. */
    private static final class Preview {
        final Player viewer;
        final CommandActor actor;
        final String id;
        final UUID world;
        GhostReplay<Player, ItemStack> ghost;
        AutoCloseable task;
        Location last;
        boolean ended;
        Preview(Player viewer, CommandActor actor, String id) {
            this.viewer = viewer; this.actor = actor; this.id = id; world = viewer.getWorld().getUID(); last = viewer.getLocation();
        }
    }

    /** @param regions the Folia schedulers, or null off Folia */
    public RecordingSessions(JavaPlugin plugin, AnimusRuntime runtime, Executor files, FoliaRegions regions, String version) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.files = files;
        owners = new RecorderOwners(plugin, regions);
        ghosts = new GhostBodies(plugin, version, owners);
        settings = runtime.recordingSettings();
        settingsSubscription = runtime.onSettings((PluginSettings next) -> settings = next.recordings());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // Commands, each on the player's owner.

    public void start(Player player, CommandActor actor, String id) {
        if (!id.matches(RecordingDocument.ID)) { actor.tell("Recording ids are 1 to 64 lowercase letters, digits, _ or -."); return; }
        RecordingSettings current = settings;
        if (sessions.get(player.getUniqueId()).isPresent()) { actor.tell("You are already recording; /animus record stop or cancel first."); return; }
        RecordingSession session;
        try { session = new RecordingSession(player, actor, id, current); }
        catch (IllegalArgumentException invalid) { actor.tell("Cannot record here: " + invalid.getMessage()); return; }
        Optional<String> refused = sessions.admit(player.getUniqueId(), session, current.enabled() && !closed, current.maximumSessions());
        if (refused.isPresent()) { actor.tell(refused.get()); return; }
        session.task = owners.everyTick(player, () -> tick(session), () -> end(session, RecordingCapture.End.QUIT));
        String ghost = !ghostWanted(player) ? "off" : ghosts.unavailable().map((String reason) -> "unavailable, " + reason).orElse("every " + current.ghostIntervalTicks() + " ticks");
        actor.tell("Recording " + id + ": sampling every " + current.sampleTicks() + " ticks, at most " + current.maximumTicks() + " ticks; ghost " + ghost + ". /animus record stop saves it.");
    }

    public void stop(Player player, CommandActor actor) {
        Optional<RecordingSession> found = sessions.get(player.getUniqueId());
        if (found.isEmpty()) { actor.tell("You are not recording."); return; }
        RecordingSession session = found.get();
        if (session.state == RecordingSession.State.SAVING) { actor.tell("Recording " + session.capture.id() + " is already saving."); return; }
        end(session, RecordingCapture.End.STOPPED);
    }

    public void cancel(Player player, CommandActor actor) {
        Optional<RecordingSession> found = sessions.get(player.getUniqueId());
        if (found.isEmpty()) { actor.tell("You are not recording."); return; }
        RecordingSession session = found.get();
        if (session.state == RecordingSession.State.SAVING) { actor.tell("Recording " + session.capture.id() + " is saving and can no longer be cancelled."); return; }
        retire(session);
        actor.tell("Recording " + session.capture.id() + " discarded.");
    }

    public void ghost(Player player, CommandActor actor, boolean on) {
        ghostChoice.put(player.getUniqueId(), on);
        if (!on) {
            sessions.get(player.getUniqueId()).ifPresent((RecordingSession session) -> closeGhost(session));
            actor.tell("Recording ghost off.");
            return;
        }
        RecordingSettings current = settings;
        if (!current.ghostEnabled()) actor.tell("Recording ghost on for you, but [recordings] ghostEnabled is false in config.toml.");
        else actor.tell("Recording ghost on" + ghosts.unavailable().map((String reason) -> ", but ghosts are unavailable: " + reason).orElse("") + ".");
    }

    public List<String> status(Optional<Player> player) {
        RecordingSettings current = settings;
        List<String> lines = new ArrayList<>();
        lines.add("Recordings " + (current.enabled() ? "enabled" : "disabled") + " | sessions " + sessions.size() + "/" + current.maximumSessions()
                + " | previews " + previews.size() + "/" + current.maximumPreviews() + " | ghosts "
                + (current.ghostEnabled() ? ghosts.unavailable().map((String reason) -> "unavailable: " + reason).orElse("available") : "off in config.toml"));
        for (RecordingSession session : sessions.values())
            if (player.isEmpty() || session.player.getUniqueId().equals(player.get().getUniqueId()) || player.get().hasPermission(ADMIN))
                lines.add("  " + session.describe());
        for (Preview preview : previews.values()) lines.add("  preview " + preview.id + " for " + preview.viewer.getName());
        return lines;
    }

    public void preview(Player player, CommandActor actor, String id) {
        if (!id.matches(RecordingDocument.ID)) { actor.tell("Recording ids are 1 to 64 lowercase letters, digits, _ or -."); return; }
        RecordingSettings current = settings;
        if (!current.enabled()) { actor.tell("Recordings are disabled; set [recordings] enabled = true in config.toml."); return; }
        Optional<String> unavailable = ghosts.unavailable();
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
                owners.later(player, () -> {
                    if (refused != null) actor.tell("Cannot preview " + id + ": " + refused);
                    else beginPreview(player, actor, read);
                }, () -> {});
            });
        } catch (RejectedExecutionException busy) { actor.tell("The file queue is full; try the preview again shortly."); }
    }

    public void stopPreview(Player player, CommandActor actor) {
        Optional<Preview> found = previews.get(player.getUniqueId());
        if (found.isEmpty()) { actor.tell("No preview is playing."); return; }
        endPreview(found.get(), "stopped");
    }

    /** Recording ids for tab completion, from a listing refreshed on the file lane at most every five seconds. */
    public List<String> ids() {
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

    // Listeners: Bukkit fires these on the player's owner.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swung(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        recording(event.getPlayer()).ifPresent((RecordingSession session) -> session.capture.swing());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interacted(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (!RecorderLook.openable(block)) return;
        recording(event.getPlayer()).ifPresent((RecordingSession session) ->
                session.capture.use(block.getX(), block.getY(), block.getZ(), block.getType().getKeyOrThrow().toString()));
    }

    private Optional<RecordingSession> recording(Player player) {
        return sessions.get(player.getUniqueId()).filter((RecordingSession session) -> session.player == player && session.state == RecordingSession.State.RECORDING);
    }

    // Ticks.

    private void tick(RecordingSession session) {
        if (session.state != RecordingSession.State.RECORDING) {
            if (session.state == RecordingSession.State.HELD && !session.player.isOnline()) {
                plugin.getLogger().warning("Recording " + session.capture.id() + " was too large to save and its recorder left; it is discarded.");
                retire(session);
            }
            return;
        }
        RecordingSettings current = settings;
        Optional<RecordingCapture.End> end = session.tick(current.maximumTicks());
        if (end.isPresent()) { end(session, end.get()); return; }
        if (current.enabled() && current.ghostEnabled() && ghostWanted(session.player) && ghosts.unavailable().isEmpty()) {
            Optional<RecordingDocument> stretch = session.ghostDue(current);
            if (stretch.isPresent()) {
                try { session.ghost = ghosts.play(session.player, stretch.get(), session.player.getName(), RecorderLook.skin(session.player), current); }
                catch (RuntimeException failure) { plugin.getLogger().warning("Recording ghost failed: " + failure.getMessage()); }
            }
        } else closeGhost(session);
        if (session.ghost != null) {
            session.ghost.tick();
            if (session.ghost.finished()) closeGhost(session);
        }
    }

    private void end(RecordingSession session, RecordingCapture.End reason) {
        if (session.state == RecordingSession.State.SAVING || session.state == RecordingSession.State.DONE) return;
        closeGhost(session);
        session.ended = reason;
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
                owners.later(session.player, finish, finish);
            });
        } catch (RejectedExecutionException busy) { saved(session, null, "the file queue is full; run /animus record stop again", why); }
    }

    private void saved(RecordingSession session, RecordingWriter.Outcome outcome, String problem, String why) {
        String id = session.capture.id();
        if (outcome instanceof RecordingWriter.Saved saved) {
            retire(session);
            report(session, "Recording " + id + " saved" + why + ": " + session.capture.durationTicks() + " ticks, " + saved.frames() + " frames, "
                    + saved.events() + " events, " + saved.bytes() + " bytes, revision " + saved.revision() + ". Rollback: /animus rollback " + saved.transaction());
            idsRefreshed = 0;
            return;
        }
        if (!session.player.isOnline()) {
            retire(session);
            plugin.getLogger().warning("Recording " + id + " was not saved after its recorder left: "
                    + (outcome instanceof RecordingWriter.TooLarge large ? large.bytes() + " bytes, above maximumFileBytes " + large.maximum() : problem));
            return;
        }
        session.state = RecordingSession.State.HELD;
        if (outcome instanceof RecordingWriter.TooLarge large)
            report(session, "Recording " + id + " is " + large.bytes() + " bytes, above [recordings] maximumFileBytes " + large.maximum()
                    + "; it is held. Raise the limit and /animus record stop again, or /animus record cancel.");
        else report(session, "Recording " + id + " was not saved: " + problem + ". It is held; /animus record stop retries, cancel discards.");
    }

    private void report(RecordingSession session, String message) {
        if (session.player.isOnline()) session.actor.tell(message);
        else plugin.getLogger().info(message);
    }

    private void retire(RecordingSession session) {
        session.state = RecordingSession.State.DONE;
        closeGhost(session);
        sessions.remove(session.player.getUniqueId(), session);
        close(session.task);
    }

    private void closeGhost(RecordingSession session) {
        if (session.ghost == null) return;
        session.ghost.close();
        session.ghost = null;
        ghosts.release(session.player);
    }

    private boolean ghostWanted(Player player) { return ghostChoice.getOrDefault(player.getUniqueId(), Boolean.TRUE); }

    // Previews.

    private void beginPreview(Player player, CommandActor actor, RecordingDocument document) {
        RecordingSettings current = settings;
        if (closed || !player.isOnline() || player.isDead() || !player.hasPermission(ADMIN)) return;
        if (!current.enabled()) { actor.tell("Recordings are disabled; set [recordings] enabled = true in config.toml."); return; }
        World recorded = BukkitWorldLookup.find(plugin.getServer(), document.world());
        if (recorded == null) { actor.tell("Recording " + document.id() + " names world " + document.world() + ", which is not loaded."); return; }
        if (!recorded.getUID().equals(player.getWorld().getUID())) {
            actor.tell("Recording " + document.id() + " was made in " + recorded.getName() + "; go there to preview it.");
            return;
        }
        previews.get(player.getUniqueId()).ifPresent((Preview running) -> endPreview(running, null));
        Preview preview = new Preview(player, actor, document.id());
        Optional<String> refused = previews.admit(player.getUniqueId(), preview, true, current.maximumPreviews());
        if (refused.isPresent()) { actor.tell(refused.get()); return; }
        Player recorder = plugin.getServer().getPlayer(document.recordedBy());
        String name = recorder != null ? recorder.getName() : Optional.ofNullable(plugin.getServer().getOfflinePlayer(document.recordedBy()).getName()).orElse("Ghost");
        Optional<BodySkin> skin = recorder != null ? RecorderLook.skin(recorder) : Optional.empty();
        try { preview.ghost = ghosts.play(player, document, name, skin, current); }
        catch (RuntimeException failure) { previews.remove(player.getUniqueId(), preview); actor.tell("Cannot preview " + document.id() + ": " + failure.getMessage()); return; }
        preview.task = owners.everyTick(player, () -> tickPreview(preview), () -> endPreview(preview, null));
        RecordingDocument.Origin origin = document.origin();
        actor.tell("Previewing " + document.id() + " (" + document.durationTicks() + " ticks) at " + Math.round(origin.x()) + " " + Math.round(origin.y()) + " "
                + Math.round(origin.z()) + ". /animus record preview " + document.id() + " stop ends it.");
    }

    private void tickPreview(Preview preview) {
        if (preview.ended) return;
        Player viewer = preview.viewer;
        RecordingSettings current = settings;
        if (!viewer.isOnline()) { endPreview(preview, null); return; }
        if (viewer.isDead()) { endPreview(preview, "you died"); return; }
        if (!viewer.getWorld().getUID().equals(preview.world)) { endPreview(preview, "you changed world"); return; }
        if (!viewer.hasPermission(ADMIN)) { endPreview(preview, "you no longer have " + ADMIN); return; }
        if (!current.enabled()) { endPreview(preview, "recordings were disabled"); return; }
        Location at = viewer.getLocation();
        if (at.distanceSquared(preview.last) > RecordingCapture.TELEPORT_DISTANCE * RecordingCapture.TELEPORT_DISTANCE) { endPreview(preview, "you teleported"); return; }
        preview.last = at;
        preview.ghost.tick();
        if (preview.ghost.finished()) endPreview(preview, "finished");
    }

    /** Ends a preview; {@code reason} is told to the viewer when it is not null. */
    private void endPreview(Preview preview, String reason) {
        if (preview.ended) return;
        preview.ended = true;
        if (preview.ghost != null) { preview.ghost.close(); ghosts.release(preview.viewer); }
        previews.remove(preview.viewer.getUniqueId(), preview);
        close(preview.task);
        if (reason != null && preview.viewer.isOnline()) preview.actor.tell("Preview " + preview.id + " ended: " + reason + ".");
    }

    private static void close(AutoCloseable task) {
        if (task == null) return;
        try { task.close(); }
        catch (Exception ignored) { /* The scheduler stops with the plugin. */ }
    }

    /**
     * Saves every running recording directly, since the file lane closes with the plugin, then removes every ghost.
     * Catalogs are not reloaded: the next start reads the files.
     */
    @Override public void close() {
        closed = true;
        close(settingsSubscription);
        for (Preview preview : previews.values()) endPreview(preview, null);
        for (RecordingSession session : sessions.values()) {
            close(session.task);
            if (session.state != RecordingSession.State.RECORDING) { retire(session); continue; }
            Optional<RecordingCapture.Take> take = session.capture.finish();
            retire(session);
            if (take.isEmpty()) continue;
            try {
                RecordingWriter.Outcome outcome = RecordingWriter.save(runtime.documents(), CatalogReloads.NONE, take.get(), settings.maximumFileBytes());
                plugin.getLogger().info("Recording " + session.capture.id() + (outcome instanceof RecordingWriter.Saved ? " saved" : " not saved, too large") + " at shutdown.");
            } catch (IOException | RuntimeException failure) { plugin.getLogger().warning("Recording " + session.capture.id() + " not saved at shutdown: " + failure.getMessage()); }
        }
        ghosts.close();
    }
}
