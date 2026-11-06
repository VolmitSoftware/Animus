package art.arcane.animus.core.recording;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One recording session's frames and events, without any server types. The owner calls {@link #advance()} every tick
 * and, when it returns true, reads the player into a {@link Sample}. Call every method on the recorder's owner.
 */
public final class RecordingCapture {
    /** Recordings shorter than this many ticks are discarded instead of saved. */
    public static final int MINIMUM_TICKS = 20;
    /** A move longer than this between two samples is a teleport, which playback cannot reproduce. */
    public static final double TELEPORT_DISTANCE = 8;

    /** Why a session ended. */
    public enum End {
        STOPPED("stopped"), LIMIT("reached maximumTicks"), QUIT("the recorder left"), DEATH("the recorder died"),
        WORLD("the recorder changed world"), TELEPORT("the recorder moved more than 8 blocks in one sample"), SHUTDOWN("the server is stopping");

        private final String reason;
        End(String reason) { this.reason = reason; }
        public String reason() { return reason; }
    }

    /** What the owner read from the player at a sample tick. {@code world} is the world's name, never its UUID; {@code equipment} holds the six player slots. */
    public record Sample(String world, double x, double y, double z, double yaw, double pitch, boolean onGround, boolean sneaking, boolean sprinting,
                         boolean swimming, boolean gliding, Map<EquipmentSlot, Optional<NpcItem>> equipment) {
        public Sample {
            Objects.requireNonNull(world);
            equipment = Map.copyOf(equipment);
        }
    }

    /** A finished capture, ready to become a document once the stored revision is known. */
    public record Take(String id, UUID recordedBy, String world, RecordingDocument.Origin origin, int sampleTicks, int durationTicks,
                       List<RecordingFrame> frames, List<RecordingEvent> events) {
        public Take { frames = List.copyOf(frames); events = List.copyOf(events); }

        public RecordingDocument document(long revision) {
            return new RecordingDocument(revision, id, recordedBy, world, origin, sampleTicks, durationTicks, frames, events);
        }
    }

    private final String id;
    private final UUID recorder;
    private final String world;
    private final int sampleTicks;
    private final RecordingDocument.Origin origin;
    private final List<RecordingFrame> frames = new ArrayList<>();
    private final List<RecordingEvent> events = new ArrayList<>();
    private final Map<EquipmentSlot, Optional<NpcItem>> worn = new EnumMap<>(EquipmentSlot.class);
    private Sample last;
    private int tick, dropped, lastGhost, ghostsSkipped;

    /** Starts at tick 0 with {@code first}: its position is the origin and its equipment the starting equipment. */
    public RecordingCapture(String id, UUID recorder, int sampleTicks, Sample first) {
        if (!Objects.requireNonNull(id).matches(RecordingDocument.ID)) throw new IllegalArgumentException("Invalid recording identifier");
        if (sampleTicks < 1 || sampleTicks > RecordingDocument.MAXIMUM_SAMPLE_TICKS) throw new IllegalArgumentException("sampleTicks must be within 1..20");
        this.id = id;
        this.recorder = Objects.requireNonNull(recorder);
        this.sampleTicks = sampleTicks;
        world = first.world();
        origin = new RecordingDocument.Origin(first.x(), first.y(), first.z(), wrap(first.yaw()), clampPitch(first.pitch()));
        frames.add(frame(first, false));
        last = first;
        for (EquipmentSlot slot : RecordingEvent.SLOTS) {
            Optional<NpcItem> item = first.equipment().getOrDefault(slot, Optional.empty());
            worn.put(slot, item);
            add(new RecordingEvent.Equipment(0, slot, item));
        }
    }

    public String id() { return id; }

    /** Ticks since the session started. */
    public int tick() { return tick; }

    public int sampleTicks() { return sampleTicks; }

    /** Moves one tick on; true when the owner should read the player and call {@link #sample}. */
    public boolean advance() {
        tick++;
        return tick % sampleTicks == 0;
    }

    /**
     * Records the player at the current tick. A world change or a teleport ends the session without a frame; the frame
     * that reaches {@code maximumTicks} (the live setting) ends it with {@link End#LIMIT}.
     */
    public Optional<End> sample(Sample sample, int maximumTicks) {
        if (!sample.world().equals(world)) return Optional.of(End.WORLD);
        double dx = sample.x() - last.x(), dy = sample.y() - last.y(), dz = sample.z() - last.z();
        if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) return Optional.of(End.TELEPORT);
        boolean jumped = last.onGround() && !sample.onGround() && sample.y() > last.y();
        frames.add(frame(sample, jumped));
        for (EquipmentSlot slot : RecordingEvent.SLOTS) {
            Optional<NpcItem> item = sample.equipment().getOrDefault(slot, Optional.empty());
            if (!item.equals(worn.get(slot))) { worn.put(slot, item); add(new RecordingEvent.Equipment(tick, slot, item)); }
        }
        last = sample;
        int limit = Math.min(maximumTicks, RecordingDocument.MAXIMUM_DURATION);
        return tick + sampleTicks > limit ? Optional.of(End.LIMIT) : Optional.empty();
    }

    /** The main arm swung at the current tick. */
    public void swing() { add(new RecordingEvent.Swing(tick)); }

    /** The recorder opened or closed a door, fence gate or trapdoor at the current tick. */
    public void use(int x, int y, int z, String block) { add(new RecordingEvent.Use(tick, x, y, z, block)); }

    public int frames() { return frames.size(); }

    public int events() { return events.size(); }

    /** Events refused because the document already held {@link RecordingDocument#MAXIMUM_EVENTS}. */
    public int dropped() { return dropped; }

    /** The frames as sampled, before simplification. */
    public List<RecordingFrame> rawFrames() { return List.copyOf(frames); }

    /** Ticks covered by the frames so far. */
    public int durationTicks() { return frames.getLast().tick(); }

    /**
     * The recording up to the last frame, simplified, or empty when it covers fewer than {@link #MINIMUM_TICKS} ticks.
     * Events after the last frame move onto it. The capture itself is left as it is.
     */
    public Optional<Take> finish() {
        int duration = durationTicks();
        if (duration < MINIMUM_TICKS) return Optional.empty();
        List<RecordingEvent> sorted = sorted(clamped(events, 0, duration));
        return Optional.of(new Take(id, recorder, world, origin, sampleTicks, duration, RecordingSimplifier.simplify(frames, sorted), sorted));
    }

    /**
     * Every {@code intervalTicks} ticks, the last {@code stretchTicks} ticks as a document whose tick 0 is the first frame
     * of the stretch and starts with the equipment worn then. Empty between intervals, when a replay is still
     * {@code running} (the interval is skipped), or before the stretch holds two frames.
     */
    public Optional<RecordingDocument> ghost(int intervalTicks, int stretchTicks, boolean running) {
        if (tick - lastGhost < intervalTicks) return Optional.empty();
        lastGhost = tick;
        if (running) { ghostsSkipped++; return Optional.empty(); }
        int from = Math.max(0, durationTicks() - stretchTicks);
        int first = 0;
        while (first < frames.size() && frames.get(first).tick() < from) first++;
        if (frames.size() - first < 2) return Optional.empty();
        int start = frames.get(first).tick(), duration = durationTicks() - start;
        List<RecordingFrame> stretch = new ArrayList<>(frames.size() - first);
        for (int i = first; i < frames.size(); i++) {
            RecordingFrame frame = frames.get(i);
            stretch.add(new RecordingFrame(frame.tick() - start, frame.dx(), frame.dy(), frame.dz(), frame.yaw(), frame.pitch(), frame.flags()));
        }
        Map<EquipmentSlot, RecordingEvent.Equipment> dressed = new EnumMap<>(EquipmentSlot.class);
        List<RecordingEvent> later = new ArrayList<>();
        for (RecordingEvent event : events) {
            if (event.tick() <= start && event instanceof RecordingEvent.Equipment equipment) dressed.put(equipment.slot(), equipment);
            else if (event.tick() > start && event.tick() <= start + duration) later.add(event);
        }
        List<RecordingEvent> replayed = new ArrayList<>();
        for (RecordingEvent.Equipment equipment : dressed.values()) replayed.add(new RecordingEvent.Equipment(0, equipment.slot(), equipment.item()));
        replayed.addAll(clamped(later, start, duration));
        return Optional.of(new RecordingDocument(1, id, recorder, world, origin, sampleTicks, duration, stretch, sorted(replayed)));
    }

    /** Ghost intervals skipped because the previous replay was still running. */
    public int ghostsSkipped() { return ghostsSkipped; }

    private void add(RecordingEvent event) {
        if (events.size() >= RecordingDocument.MAXIMUM_EVENTS) { dropped++; return; }
        events.add(event);
    }

    private RecordingFrame frame(Sample sample, boolean jumped) {
        int flags = (sample.onGround() ? RecordingFrame.ON_GROUND : 0) | (sample.sneaking() ? RecordingFrame.SNEAKING : 0)
                | (sample.sprinting() ? RecordingFrame.SPRINTING : 0) | (sample.swimming() ? RecordingFrame.SWIMMING : 0)
                | (sample.gliding() ? RecordingFrame.GLIDING : 0) | (jumped ? RecordingFrame.JUMPING : 0);
        return new RecordingFrame(tick, sample.x() - origin.x(), sample.y() - origin.y(), sample.z() - origin.z(), wrap(sample.yaw()),
                clampPitch(sample.pitch()), flags);
    }

    /** {@code events} with ticks moved back by {@code shift} and capped at {@code duration}. */
    private static List<RecordingEvent> clamped(List<RecordingEvent> events, int shift, int duration) {
        List<RecordingEvent> result = new ArrayList<>(events.size());
        for (RecordingEvent event : events) {
            int at = Math.min(duration, event.tick() - shift);
            result.add(switch (event) {
                case RecordingEvent.Swing swing -> new RecordingEvent.Swing(at);
                case RecordingEvent.Equipment equipment -> new RecordingEvent.Equipment(at, equipment.slot(), equipment.item());
                case RecordingEvent.Use use -> new RecordingEvent.Use(at, use.x(), use.y(), use.z(), use.block());
            });
        }
        return result;
    }

    private static List<RecordingEvent> sorted(List<RecordingEvent> events) {
        List<RecordingEvent> sorted = new ArrayList<>(events);
        sorted.sort(RecordingEvent.ORDER);
        return sorted;
    }

    private static double wrap(double yaw) {
        double wrapped = yaw % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    private static double clampPitch(double pitch) { return Math.max(-90, Math.min(90, pitch)); }
}
