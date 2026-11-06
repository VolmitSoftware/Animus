package art.arcane.animus.core.recording;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.RecordingSettings;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One player's recording: samples the player every {@code sampleTicks} on its owner, and replays a ghost behind it
 * every {@code ghostIntervalTicks}. Ended sessions wait for their save; a save refused for its size holds the session
 * until it is stopped again or cancelled. Owner thread only.
 */
final class RecordingSession<P, I> {
    enum State { RECORDING, SAVING, HELD, DONE }

    final P player;
    final CommandActor actor;
    final RecordingCapture capture;
    final String world;
    private final RecordingHost<P, I> host;
    private final Supplier<RecordingCapture.Sample> sampler;
    State state = State.RECORDING;
    AutoCloseable task;
    GhostReplay<P, I> ghost;

    RecordingSession(RecordingHost<P, I> host, P player, CommandActor actor, String id, RecordingSettings settings) {
        this.host = host;
        this.player = player;
        this.actor = actor;
        world = host.world(player);
        sampler = host.sampler(player);
        capture = new RecordingCapture(id, host.id(player), settings.sampleTicks(), sampler.get());
    }

    /**
     * One tick while recording: the end the player's state calls for, if any. Quit, death and world change are checked
     * every tick; position, teleports and equipment every sample.
     */
    Optional<RecordingCapture.End> tick(int maximumTicks) {
        if (!host.online(player)) return Optional.of(RecordingCapture.End.QUIT);
        if (host.dead(player)) return Optional.of(RecordingCapture.End.DEATH);
        if (!host.world(player).equals(world)) return Optional.of(RecordingCapture.End.WORLD);
        if (!capture.advance()) return Optional.empty();
        return capture.sample(sampler.get(), maximumTicks);
    }

    boolean ghostRunning() { return ghost != null && !ghost.finished(); }

    Optional<RecordingDocument> ghostDue(RecordingSettings settings) {
        return capture.ghost(settings.ghostIntervalTicks(), settings.ghostStretchTicks(), ghostRunning());
    }

    String describe() {
        String status = state == State.HELD ? "held, not saved" : state.name().toLowerCase(Locale.ROOT);
        return capture.id() + " by " + host.name(player) + ": " + status + ", " + capture.tick() + " ticks, " + capture.frames() + " frames, "
                + capture.events() + " events" + (capture.dropped() > 0 ? " (" + capture.dropped() + " dropped)" : "");
    }
}
