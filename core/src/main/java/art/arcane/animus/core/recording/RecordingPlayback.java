package art.arcane.animus.core.recording;

import art.arcane.animus.core.npc.EquipmentSlot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cursor over one recording that a runtime drives one tick at a time on the NPC's owner thread. Not thread-safe; it
 * holds no platform objects, so {@link #state()} can travel in a Folia handoff and {@link #resume} continues it.
 *
 * <p>Each {@link #advance()} moves the cursor one tick in the current direction and queues the events at the tick it
 * reaches. Playing backward ({@code pingpong} on its way back) runs a tick's events in reverse order, and an
 * {@code equipment} event then restores the item its slot held before it (the slot's previous equipment event), or
 * does nothing when the slot had none. {@link #due()} hands out queued events, at most {@link #EVENTS_PER_CALL} a call.
 */
public final class RecordingPlayback {
    /** The most events one {@link #due()} call returns; the rest wait for the next call. */
    public static final int EVENTS_PER_CALL = 8;
    /** Queued events beyond this are dropped, so a recording dense with events cannot grow the queue without bound. */
    static final int MAXIMUM_PENDING = RecordingDocument.MAXIMUM_EVENTS;

    public enum Direction { FORWARD, BACKWARD }

    /** Everything a playback needs to continue on another owner, with the same document. */
    public record State(int cursor, Direction direction, Playback mode) {
        public State { Objects.requireNonNull(direction); Objects.requireNonNull(mode); }
    }

    private final RecordingDocument document;
    private final Playback mode;
    /** The tick a {@code once} playback ends on: durationTicks, or the last tick of a route step's segment. */
    private final int end;
    private final ArrayDeque<RecordingEvent> pending = new ArrayDeque<>();
    private int cursor;
    private Direction direction;
    private int segment;
    /** Hundredths of a tick the recording's speed has moved past the cursor; a whole tick steps it. */
    private int carry;

    /**
     * Starts at {@code startTick}, playing forward; events at the start tick are due first.
     *
     * @throws IllegalArgumentException when {@code startTick} is outside 0..durationTicks
     */
    public RecordingPlayback(RecordingDocument document, Playback mode, int startTick) {
        this(document, new State(startTick, Direction.FORWARD, mode), document.durationTicks());
        queue(startTick, pending);
    }

    private RecordingPlayback(RecordingDocument document, State state, int end) {
        this.document = Objects.requireNonNull(document);
        Objects.requireNonNull(state);
        if (end < 0 || end > document.durationTicks() || (state.mode() != Playback.ONCE && end != document.durationTicks()))
            throw new IllegalArgumentException("Playback end " + end + " is outside 0.." + document.durationTicks());
        if (state.cursor() < 0 || state.cursor() > end)
            throw new IllegalArgumentException("Playback cursor " + state.cursor() + " is outside 0.." + end);
        if (state.mode() == Playback.ONCE && state.direction() == Direction.BACKWARD) throw new IllegalArgumentException("A once playback only plays forward");
        this.end = end;
        mode = state.mode();
        cursor = state.cursor();
        direction = state.direction();
    }

    /**
     * A {@code once} playback of the ticks {@code fromTick..toTick}, as a route step plays it: it starts on {@code fromTick}
     * with that tick's events due and finishes on {@code toTick}.
     *
     * @throws IllegalArgumentException unless {@code 0 <= fromTick < toTick <= durationTicks}
     */
    public static RecordingPlayback segment(RecordingDocument document, int fromTick, int toTick) {
        if (fromTick < 0 || fromTick >= toTick) throw new IllegalArgumentException("A segment needs 0 <= fromTick < toTick");
        RecordingPlayback playback = new RecordingPlayback(document, new State(fromTick, Direction.FORWARD, Playback.ONCE), toTick);
        playback.queue(fromTick, playback.pending);
        return playback;
    }

    /**
     * Continues a playback from a {@link #state()} taken on another owner. Events at the cursor already ran there, so
     * nothing is due until the next {@link #advance()}; events still queued at the handoff are not carried.
     *
     * @throws IllegalArgumentException when the state does not fit this document
     */
    public static RecordingPlayback resume(RecordingDocument document, State state) { return new RecordingPlayback(document, state, document.durationTicks()); }

    /** {@link #resume} with this playback's document and end: a segment stays a segment. */
    public RecordingPlayback resume(State state) { return new RecordingPlayback(document, state, end); }

    public RecordingDocument document() { return document; }

    public Playback mode() { return mode; }

    /** The tick of the recording the playback is at, 0..durationTicks. */
    public int cursor() { return cursor; }

    /** The tick a {@code once} playback finishes on; durationTicks unless it plays a segment. */
    public int end() { return end; }

    public Direction direction() { return direction; }

    public State state() { return new State(cursor, direction, mode); }

    /**
     * One game tick of play: the cursor moves the recording's {@code speed} in ticks, a whole tick at a time, queueing the
     * events of every tick it reaches. {@code once} stays on its end tick, {@code loop} goes from the last tick to 0,
     * {@code pingpong} turns at both ends.
     */
    public void advance() {
        carry += document.details().hundredths();
        while (carry >= 100) {
            carry -= 100;
            if (!step()) { carry = 0; return; }
            queue(cursor, pending);
        }
    }

    /**
     * Moves {@code ticks} ticks as {@link #advance()} would, for catch-up. Swings and uses on the way are dropped; the
     * last equipment change of each slot is queued, so the NPC ends up dressed as the recorder was.
     */
    public void skip(int ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Cannot skip a negative number of ticks");
        int duration = document.durationTicks();
        int remaining = switch (mode) {
            case ONCE -> Math.min(ticks, end - cursor);
            case LOOP -> ticks % (duration + 1);
            case PINGPONG -> ticks % (2 * duration);
        };
        Map<EquipmentSlot, RecordingEvent.Equipment> dressed = new EnumMap<>(EquipmentSlot.class);
        List<RecordingEvent> crossed = new ArrayList<>();
        List<EquipmentSlot> changed = new ArrayList<>();
        for (int i = 0; i < remaining && step(); i++) {
            crossed.clear();
            queue(cursor, crossed);
            for (RecordingEvent event : crossed)
                if (event instanceof RecordingEvent.Equipment equipment) {
                    dressed.put(equipment.slot(), equipment);
                    changed.remove(equipment.slot());
                    changed.add(equipment.slot());
                }
        }
        for (EquipmentSlot slot : changed) offer(dressed.get(slot), pending);
    }

    /** The events reached since the last call, in play order, at most {@link #EVENTS_PER_CALL}. */
    public List<RecordingEvent> due() {
        if (pending.isEmpty()) return List.of();
        List<RecordingEvent> due = new ArrayList<>(Math.min(EVENTS_PER_CALL, pending.size()));
        while (due.size() < EVENTS_PER_CALL && !pending.isEmpty()) due.add(pending.poll());
        return List.copyOf(due);
    }

    /** True once a {@code once} playback is on its end tick and has handed out every event. Loops never finish. */
    public boolean finished() { return mode == Playback.ONCE && cursor == end && pending.isEmpty(); }

    /** The absolute pose at the cursor: linear position, shortest-arc yaw, linear pitch, and the earlier frame's flags. */
    public RecordingPose pose() {
        List<RecordingFrame> frames = document.frames();
        if (segment >= frames.size() - 1 || frames.get(segment).tick() > cursor || frames.get(segment + 1).tick() < cursor) segment = segment(frames, cursor);
        RecordingFrame a = frames.get(segment), b = frames.get(segment + 1);
        RecordingDocument.Origin origin = document.origin();
        if (cursor >= b.tick())
            return new RecordingPose(origin.x() + b.dx(), origin.y() + b.dy(), origin.z() + b.dz(), RecordingFrame.wrapYaw(b.yaw()), b.pitch(), b.flags());
        double u = (double) (cursor - a.tick()) / (b.tick() - a.tick());
        return new RecordingPose(origin.x() + a.dx() + (b.dx() - a.dx()) * u, origin.y() + a.dy() + (b.dy() - a.dy()) * u,
                origin.z() + a.dz() + (b.dz() - a.dz()) * u, RecordingFrame.wrapYaw(a.yaw() + RecordingFrame.yawDelta(a.yaw(), b.yaw()) * u),
                a.pitch() + (b.pitch() - a.pitch()) * u, a.flags());
    }

    /** The index of the frame that starts the segment holding {@code tick}: the last frame at or before it, never the final frame. */
    private static int segment(List<RecordingFrame> frames, int tick) {
        int low = 0, high = frames.size() - 2;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (frames.get(middle).tick() <= tick) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    /** Moves the cursor one tick; false when a {@code once} playback is already at its end. */
    private boolean step() {
        int duration = document.durationTicks();
        switch (mode) {
            case ONCE -> {
                if (cursor == end) return false;
                cursor++;
            }
            case LOOP -> cursor = cursor == duration ? 0 : cursor + 1;
            case PINGPONG -> {
                if (direction == Direction.FORWARD) {
                    if (cursor == duration) { direction = Direction.BACKWARD; cursor--; }
                    else cursor++;
                } else if (cursor == 0) { direction = Direction.FORWARD; cursor++; }
                else cursor--;
            }
        }
        return true;
    }

    /** Adds the events at {@code tick} to {@code into} in play order for the current direction. */
    private void queue(int tick, Collection<RecordingEvent> into) {
        List<RecordingEvent> events = document.events();
        int first = firstAt(events, tick);
        int end = first;
        while (end < events.size() && events.get(end).tick() == tick) end++;
        if (direction == Direction.FORWARD) {
            for (int i = first; i < end; i++) offer(events.get(i), into);
            return;
        }
        for (int i = end - 1; i >= first; i--) {
            RecordingEvent event = events.get(i);
            if (event instanceof RecordingEvent.Equipment equipment) {
                for (int j = i - 1; j >= 0; j--)
                    if (events.get(j) instanceof RecordingEvent.Equipment earlier && earlier.slot() == equipment.slot()) {
                        offer(new RecordingEvent.Equipment(equipment.tick(), equipment.slot(), earlier.item()), into);
                        break;
                    }
            } else offer(event, into);
        }
    }

    private static int firstAt(List<RecordingEvent> events, int tick) {
        int low = 0, high = events.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (events.get(middle).tick() < tick) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void offer(RecordingEvent event, Collection<RecordingEvent> into) {
        if (into.size() < MAXIMUM_PENDING) into.add(event);
    }
}
