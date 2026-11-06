package art.arcane.animus.core.recording;

import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A performance recording, {@code recordings/<id>.json}: frames relative to {@code origin} from tick 0 to
 * {@code durationTicks}, and the swing, equipment and use events in between. Instances are immutable and always valid;
 * {@link #serialize()} writes the one byte-exact form the server stores, which {@link #parse} reads back unchanged.
 */
public record RecordingDocument(long revision, String id, UUID recordedBy, String world, Origin origin, int sampleTicks, int durationTicks,
                                List<RecordingFrame> frames, List<RecordingEvent> events) {
    public static final int SCHEMA_VERSION = 1, MAXIMUM_EVENTS = 4096, MAXIMUM_SAMPLE_TICKS = 20;
    public static final int MAXIMUM_DURATION = RecordingSettings.MAXIMUM_TICKS;
    public static final String ID = "[a-z0-9][a-z0-9_-]{0,63}";
    private static final int MAXIMUM_REPORTED = 32;
    private static final double MAXIMUM_COORDINATE = 30_000_000;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "revision", "id", "recordedBy", "world", "origin", "sampleTicks", "durationTicks", "frames", "events");
    private static final Set<String> ORIGIN_FIELDS = Set.of("x", "y", "z", "yaw", "pitch");
    private static final Gson STRINGS = new GsonBuilder().disableHtmlEscaping().create();

    /** Where the recording started, in the recorded world. Frame yaw and pitch are absolute, not relative to this. */
    public record Origin(double x, double y, double z, double yaw, double pitch) {
        public Origin {
            if (!coordinate(x) || !coordinate(y) || !coordinate(z)) throw new IllegalArgumentException("Origin must be finite within ±30000000");
            if (!Double.isFinite(yaw) || yaw < -360 || yaw > 360 || !Double.isFinite(pitch) || pitch < -90 || pitch > 90)
                throw new IllegalArgumentException("Origin yaw must be within -360..360 and pitch within -90..90");
        }
    }

    /** @throws IllegalArgumentException with {@code $.field: message} problems joined by {@code ; } */
    public RecordingDocument {
        Objects.requireNonNull(id); Objects.requireNonNull(recordedBy); Objects.requireNonNull(world); Objects.requireNonNull(origin);
        frames = List.copyOf(frames);
        events = List.copyOf(events);
        List<String> problems = invariants(revision, id, world, sampleTicks, durationTicks, frames, events);
        if (!problems.isEmpty()) throw new IllegalArgumentException(join(problems));
    }

    /** The most frames a recording of {@code durationTicks} sampled every {@code sampleTicks} may hold. */
    public static int maximumFrames(int durationTicks, int sampleTicks) { return durationTicks / sampleTicks + 1; }

    /**
     * Reads a recording. Text above {@code maximumBytes} of UTF-8 is refused before it is parsed.
     *
     * @throws IOException naming each problem as {@code $.field: message}, joined by {@code ; }
     */
    public static RecordingDocument parse(String text, int maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        if (text == null) throw new IOException("$: recording is missing");
        if (text.length() > maximumBytes || text.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
            throw new IOException("$: recording exceeds " + maximumBytes + " bytes");
        return decode(JsonDocuments.object(text));
    }

    /** Reads a recording already parsed as JSON, as the editor holds it; its serialized form must fit {@code maximumBytes}. */
    public static RecordingDocument from(JsonObject source, int maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        RecordingDocument document = decode(Objects.requireNonNull(source));
        int bytes = document.serialize().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximumBytes) throw new IOException("$: recording is " + bytes + " bytes, above the limit of " + maximumBytes);
        return document;
    }

    /** The stored form: fixed key order, one frame and one event per line, plain decimal numbers, a final newline. */
    public String serialize() {
        StringBuilder out = new StringBuilder(128 + frames.size() * 40 + events.size() * 64);
        out.append("{\n  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n  \"revision\": ").append(revision)
                .append(",\n  \"id\": ").append(STRINGS.toJson(id)).append(",\n  \"recordedBy\": ").append(STRINGS.toJson(recordedBy.toString()))
                .append(",\n  \"world\": ").append(STRINGS.toJson(world))
                .append(",\n  \"origin\": {\"x\": ").append(number(origin.x())).append(", \"y\": ").append(number(origin.y()))
                .append(", \"z\": ").append(number(origin.z())).append(", \"yaw\": ").append(number(origin.yaw()))
                .append(", \"pitch\": ").append(number(origin.pitch())).append("},\n  \"sampleTicks\": ").append(sampleTicks)
                .append(",\n  \"durationTicks\": ").append(durationTicks).append(",\n  \"frames\": [\n");
        for (int i = 0; i < frames.size(); i++) {
            RecordingFrame frame = frames.get(i);
            out.append("    [").append(frame.tick()).append(", ").append(number(frame.dx())).append(", ").append(number(frame.dy())).append(", ")
                    .append(number(frame.dz())).append(", ").append(number(frame.yaw())).append(", ").append(number(frame.pitch())).append(", ")
                    .append(frame.flags()).append(i + 1 < frames.size() ? "],\n" : "]\n");
        }
        out.append("  ],\n  \"events\": [");
        for (int i = 0; i < events.size(); i++) {
            out.append(i == 0 ? "\n    " : ",\n    ");
            event(out, events.get(i));
        }
        out.append(events.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        return out.toString();
    }

    private static void event(StringBuilder out, RecordingEvent event) {
        out.append("{\"tick\": ").append(event.tick()).append(", \"type\": \"").append(event.type()).append('"');
        switch (event) {
            case RecordingEvent.Swing ignored -> { }
            case RecordingEvent.Equipment equipment -> {
                out.append(", \"slot\": \"").append(equipment.slot().key()).append("\", \"item\": ");
                if (equipment.item().isEmpty()) out.append("null");
                else {
                    NpcItem item = equipment.item().get();
                    out.append("{\"id\": ").append(STRINGS.toJson(item.id()));
                    if (item.count() != 1) out.append(", \"count\": ").append(item.count());
                    item.components().ifPresent((String components) -> out.append(", \"components\": ").append(STRINGS.toJson(components)));
                    out.append('}');
                }
            }
            case RecordingEvent.Use use -> out.append(", \"x\": ").append(use.x()).append(", \"y\": ").append(use.y()).append(", \"z\": ").append(use.z())
                    .append(", \"block\": ").append(STRINGS.toJson(use.block()));
        }
        out.append('}');
    }

    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    private static RecordingDocument decode(JsonObject source) throws IOException {
        List<String> errors = new ArrayList<>();
        for (String key : source.keySet()) if (!FIELDS.contains(key)) errors.add("$." + key + ": unknown field");
        integer(source.get("schemaVersion"), "$.schemaVersion", SCHEMA_VERSION, SCHEMA_VERSION, errors);
        long revision = integer(source.get("revision"), "$.revision", 1, Long.MAX_VALUE, errors);
        String id = string(source.get("id"), "$.id", errors);
        if (id != null && !id.matches(ID)) { errors.add("$.id: expected 1–64 lowercase letters, digits, _ or -"); id = null; }
        UUID recordedBy = null;
        String recorder = string(source.get("recordedBy"), "$.recordedBy", new ArrayList<>());
        try {
            if (recorder == null || !UUID.fromString(recorder).toString().equals(recorder)) throw new IllegalArgumentException();
            recordedBy = UUID.fromString(recorder);
        } catch (IllegalArgumentException invalid) { errors.add("$.recordedBy: expected canonical player UUID"); }
        String world = string(source.get("world"), "$.world", errors);
        Origin origin = origin(source.get("origin"), errors);
        int sampleTicks = (int) integer(source.get("sampleTicks"), "$.sampleTicks", 1, MAXIMUM_SAMPLE_TICKS, errors);
        int durationTicks = (int) integer(source.get("durationTicks"), "$.durationTicks", 1, MAXIMUM_DURATION, errors);
        int eventTicks = durationTicks > 0 ? durationTicks : MAXIMUM_DURATION;
        List<RecordingFrame> frames = frames(source.get("frames"), sampleTicks > 0 && durationTicks > 0 ? maximumFrames(durationTicks, sampleTicks) : -1,
                durationTicks, sampleTicks, errors);
        List<RecordingEvent> events = events(source.get("events"), eventTicks, errors);
        if (!errors.isEmpty()) throw new IOException(join(errors));
        try { return new RecordingDocument(revision, id, recordedBy, world, origin, sampleTicks, durationTicks, frames, events); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
    }

    private static Origin origin(JsonElement value, List<String> errors) {
        if (value == null || !value.isJsonObject()) { errors.add("$.origin: expected object"); return null; }
        JsonObject origin = value.getAsJsonObject();
        int before = errors.size();
        for (String key : origin.keySet()) if (!ORIGIN_FIELDS.contains(key)) errors.add("$.origin." + key + ": unknown field");
        double[] parts = new double[5];
        String[] keys = {"x", "y", "z", "yaw", "pitch"};
        for (int i = 0; i < 5; i++) {
            JsonElement part = origin.get(keys[i]);
            boolean numeric = part != null && part.isJsonPrimitive() && part.getAsJsonPrimitive().isNumber();
            double number = numeric ? part.getAsDouble() : Double.NaN;
            parts[i] = number;
            if (i < 3 && !coordinate(number)) errors.add("$.origin." + keys[i] + ": expected finite coordinate within ±30000000");
            if (i == 3 && !(number >= -360 && number <= 360)) errors.add("$.origin.yaw: expected yaw -360..360");
            if (i == 4 && !(number >= -90 && number <= 90)) errors.add("$.origin.pitch: expected pitch -90..90");
        }
        return errors.size() == before ? new Origin(parts[0], parts[1], parts[2], parts[3], parts[4]) : null;
    }

    private static List<RecordingFrame> frames(JsonElement value, int maximum, int durationTicks, int sampleTicks, List<String> errors) {
        if (value == null || !value.isJsonArray()) { errors.add("$.frames: expected array"); return List.of(); }
        JsonArray array = value.getAsJsonArray();
        if (maximum > 0 && array.size() > maximum) {
            errors.add("$.frames: expected at most " + maximum + " frames for " + durationTicks + " ticks sampled every " + sampleTicks);
            return List.of();
        }
        if (maximum < 0 && array.size() > maximumFrames(MAXIMUM_DURATION, 1)) { errors.add("$.frames: too many frames"); return List.of(); }
        List<RecordingFrame> frames = new ArrayList<>(array.size());
        for (int i = 0; i < array.size() && errors.size() < MAXIMUM_REPORTED; i++) {
            String at = "$.frames[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonArray() || element.getAsJsonArray().size() != 7) { errors.add(at + ": expected [tick, dx, dy, dz, yaw, pitch, flags]"); continue; }
            JsonArray parts = element.getAsJsonArray();
            int before = errors.size();
            int tick = (int) integer(parts.get(0), at + "[0]", 0, MAXIMUM_DURATION, errors);
            double[] numbers = new double[5];
            for (int k = 1; k <= 5; k++) {
                JsonElement part = parts.get(k);
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber() || !Double.isFinite(part.getAsDouble())) {
                    errors.add(at + "[" + k + "]: expected a number");
                    continue;
                }
                numbers[k - 1] = part.getAsDouble();
            }
            if (errors.size() == before) {
                for (int k = 0; k < 3; k++)
                    if (Math.abs(RecordingFrame.position(numbers[k])) > RecordingFrame.MAXIMUM_OFFSET) errors.add(at + "[" + (k + 1) + "]: expected offset within ±30000000");
                double yaw = RecordingFrame.angle(numbers[3]), pitch = RecordingFrame.angle(numbers[4]);
                if (yaw < -360 || yaw > 360) errors.add(at + "[4]: expected yaw -360..360");
                if (pitch < -90 || pitch > 90) errors.add(at + "[5]: expected pitch -90..90");
            }
            int flags = (int) integer(parts.get(6), at + "[6]", 0, RecordingFrame.ALL_FLAGS, errors, "flags 0.." + RecordingFrame.ALL_FLAGS);
            if (errors.size() == before) frames.add(new RecordingFrame(tick, numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], flags));
        }
        return frames;
    }

    private static List<RecordingEvent> events(JsonElement value, int durationTicks, List<String> errors) {
        if (value == null || !value.isJsonArray()) { errors.add("$.events: expected array"); return List.of(); }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > MAXIMUM_EVENTS) { errors.add("$.events: expected at most " + MAXIMUM_EVENTS + " events"); return List.of(); }
        List<RecordingEvent> events = new ArrayList<>(array.size());
        for (int i = 0; i < array.size() && errors.size() < MAXIMUM_REPORTED; i++) {
            String at = "$.events[" + i + "]";
            if (!array.get(i).isJsonObject()) { errors.add(at + ": expected object"); continue; }
            event(array.get(i).getAsJsonObject(), at, durationTicks, errors).ifPresent(events::add);
        }
        return events;
    }

    private static Optional<RecordingEvent> event(JsonObject event, String at, int durationTicks, List<String> errors) {
        int before = errors.size();
        int tick = (int) integer(event.get("tick"), at + ".tick", 0, durationTicks, errors);
        JsonElement typeValue = event.get("type");
        String type = typeValue != null && typeValue.isJsonPrimitive() && typeValue.getAsJsonPrimitive().isString() ? typeValue.getAsString() : "";
        Set<String> fields = switch (type) {
            case "swing" -> Set.of("tick", "type");
            case "equipment" -> Set.of("tick", "type", "slot", "item");
            case "use" -> Set.of("tick", "type", "x", "y", "z", "block");
            default -> null;
        };
        if (fields == null) { errors.add(at + ".type: expected swing, equipment or use"); return Optional.empty(); }
        for (String key : event.keySet()) if (!fields.contains(key)) errors.add(at + "." + key + ": unknown field");
        RecordingEvent parsed = switch (type) {
            case "swing" -> errors.size() == before ? new RecordingEvent.Swing(tick) : null;
            case "equipment" -> equipment(event, at, tick, before, errors);
            default -> use(event, at, tick, before, errors);
        };
        return Optional.ofNullable(parsed);
    }

    private static RecordingEvent equipment(JsonObject event, String at, int tick, int before, List<String> errors) {
        JsonElement slotValue = event.get("slot");
        Optional<EquipmentSlot> slot = slotValue != null && slotValue.isJsonPrimitive() && slotValue.getAsJsonPrimitive().isString()
                ? EquipmentSlot.fromKey(slotValue.getAsString()).filter(RecordingEvent.SLOTS::contains) : Optional.empty();
        if (slot.isEmpty()) errors.add(at + ".slot: expected main_hand, off_hand, head, chest, legs or feet");
        Optional<NpcItem> item = Optional.empty();
        JsonElement itemValue = event.get("item");
        if (itemValue == null || (!itemValue.isJsonNull() && !itemValue.isJsonObject())) errors.add(at + ".item: expected item object or null");
        else if (itemValue.isJsonObject()) {
            if (itemValue.getAsJsonObject().has("imported")) errors.add(at + ".item.imported: not allowed in a recording");
            else item = NpcItem.parse(itemValue, at + ".item", errors);
        }
        return errors.size() == before ? new RecordingEvent.Equipment(tick, slot.orElseThrow(), item) : null;
    }

    private static RecordingEvent use(JsonObject event, String at, int tick, int before, List<String> errors) {
        int[] position = new int[3];
        String[] keys = {"x", "y", "z"};
        for (int k = 0; k < 3; k++)
            position[k] = (int) integer(event.get(keys[k]), at + "." + keys[k], -RecordingEvent.MAXIMUM_COORDINATE, RecordingEvent.MAXIMUM_COORDINATE, errors,
                    "integer coordinate within ±30000000");
        JsonElement blockValue = event.get("block");
        String block = blockValue != null && blockValue.isJsonPrimitive() && blockValue.getAsJsonPrimitive().isString() ? blockValue.getAsString() : null;
        if (!NpcItem.validId(block)) errors.add(at + ".block: expected namespace:block");
        return errors.size() == before ? new RecordingEvent.Use(tick, position[0], position[1], position[2], block) : null;
    }

    private static List<String> invariants(long revision, String id, String world, int sampleTicks, int durationTicks, List<RecordingFrame> frames,
                                           List<RecordingEvent> events) {
        List<String> problems = new ArrayList<>();
        if (revision < 1) problems.add("$.revision: expected integer 1.." + Long.MAX_VALUE);
        if (!id.matches(ID)) problems.add("$.id: expected 1–64 lowercase letters, digits, _ or -");
        if (world.isBlank()) problems.add("$.world: expected nonblank string");
        if (sampleTicks < 1 || sampleTicks > MAXIMUM_SAMPLE_TICKS) problems.add("$.sampleTicks: expected integer 1.." + MAXIMUM_SAMPLE_TICKS);
        if (durationTicks < 1 || durationTicks > MAXIMUM_DURATION) problems.add("$.durationTicks: expected integer 1.." + MAXIMUM_DURATION);
        if (!problems.isEmpty()) return problems;
        int maximum = maximumFrames(durationTicks, sampleTicks);
        if (frames.size() < 2) problems.add("$.frames: expected at least 2 frames");
        else if (frames.size() > maximum)
            problems.add("$.frames: expected at most " + maximum + " frames for " + durationTicks + " ticks sampled every " + sampleTicks);
        else {
            if (frames.getFirst().tick() != 0) problems.add("$.frames[0][0]: expected the first frame at tick 0");
            for (int i = 1; i < frames.size() && problems.size() < MAXIMUM_REPORTED; i++)
                if (frames.get(i).tick() <= frames.get(i - 1).tick())
                    problems.add("$.frames[" + i + "][0]: expected a tick above the previous frame's " + frames.get(i - 1).tick());
            if (frames.getLast().tick() != durationTicks)
                problems.add("$.frames[" + (frames.size() - 1) + "][0]: expected the last frame at durationTicks " + durationTicks);
        }
        if (events.size() > MAXIMUM_EVENTS) problems.add("$.events: expected at most " + MAXIMUM_EVENTS + " events");
        else for (int i = 0; i < events.size() && problems.size() < MAXIMUM_REPORTED; i++) {
            if (events.get(i).tick() > durationTicks) problems.add("$.events[" + i + "].tick: expected integer 0.." + durationTicks);
            else if (i > 0 && RecordingEvent.ORDER.compare(events.get(i - 1), events.get(i)) > 0)
                problems.add("$.events[" + i + "]: expected events sorted by tick then type");
        }
        return problems;
    }

    private static boolean coordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= MAXIMUM_COORDINATE; }

    private static String join(List<String> problems) {
        if (problems.size() <= MAXIMUM_REPORTED) return String.join("; ", problems);
        return String.join("; ", problems.subList(0, MAXIMUM_REPORTED)) + "; and " + (problems.size() - MAXIMUM_REPORTED) + " more";
    }

    private static String string(JsonElement value, String at, List<String> errors) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            errors.add(at + ": expected nonblank string");
            return null;
        }
        return value.getAsString();
    }

    private static long integer(JsonElement value, String at, long minimum, long maximum, List<String> errors) {
        return integer(value, at, minimum, maximum, errors, "integer " + minimum + ".." + maximum);
    }

    /** The integer at {@code at}, or {@code -1} after adding {@code at: expected <expected>}. */
    private static long integer(JsonElement value, String at, long minimum, long maximum, List<String> errors, String expected) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long number = value.getAsBigDecimal().longValueExact();
            if (number < minimum || number > maximum) throw new ArithmeticException();
            return number;
        } catch (ArithmeticException | NumberFormatException invalid) {
            errors.add(at + ": expected " + expected);
            return -1;
        }
    }
}
