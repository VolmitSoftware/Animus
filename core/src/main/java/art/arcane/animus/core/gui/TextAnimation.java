package art.arcane.animus.core.gui;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A Gloss text animation ({@code schemaVersion} 1): frames on one wall clock shared by every viewer. Text shows it
 * with {@code |animation.<id>|}; the chosen frame is then filled for each viewer.
 */
public record TextAnimation(String id, long revision, GuiModel.Condition show, Mode mode, int frameIntervalMs, List<GuiTemplate> frames) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAXIMUM_FRAMES = 1024;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "revision", "preset", "show", "mode", "frameIntervalMs", "frames");
    public enum Mode { ASCEND, DESCEND, ASCEND_DESCEND, RANDOM }

    public TextAnimation { frames = List.copyOf(frames); }

    /** The frame index at a wall-clock time, as Gloss chooses it. */
    public int frame(long nowMillis) {
        int count = frames.size();
        long tick = Math.floorDiv(nowMillis, frameIntervalMs);
        return switch (mode) {
            case ASCEND -> (int) Math.floorMod(tick, count);
            case DESCEND -> count - 1 - (int) Math.floorMod(tick, count);
            case ASCEND_DESCEND -> {
                int cycle = (int) Math.floorMod(tick, 2L * count);
                yield cycle < count ? cycle : 2 * count - 1 - cycle;
            }
            case RANDOM -> {
                long mixed = tick ^ ((long) id.hashCode() << 32);
                mixed = (mixed ^ (mixed >>> 33)) * 0xFF51AFD7ED558CCDL;
                mixed = (mixed ^ (mixed >>> 33)) * 0xC4CEB9FE1A85EC53L;
                mixed ^= mixed >>> 33;
                yield (int) Math.floorMod(mixed, count);
            }
        };
    }

    /** The text for this viewer now; empty text when {@code show} is false. */
    public String render(long nowMillis, GuiExpression.Scope scope) {
        if (!show.test(scope)) return "";
        return frames.get(frame(nowMillis)).render(scope);
    }

    public record Result(Optional<TextAnimation> animation, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    public static Result parse(String id, String json) {
        JsonObject object;
        try { object = JsonDocuments.object(json); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
        return parse(id, object);
    }

    /** Frames may use the hologram expression roots but not other animations. */
    public static Result parse(String id, JsonObject object) {
        List<String> problems = new ArrayList<>();
        GuiParser parser = new GuiParser(problems, HologramDocument.ROOTS);
        parser.unknownFields(object, FIELDS, "$");
        int schema = parser.integer(object, "schemaVersion", "$", Integer.MIN_VALUE, Integer.MAX_VALUE).orElse(SCHEMA_VERSION);
        if (object.has("schemaVersion") && schema != SCHEMA_VERSION)
            parser.problem("$.schemaVersion", "text animation schemaVersion " + schema + " is not supported; Animus reads version " + SCHEMA_VERSION);
        long revision = object.has("revision") ? parser.integer(object, "revision", "$", 1, Integer.MAX_VALUE).orElse(1) : 1;
        if (object.has("preset") && !object.get("preset").isJsonNull()) parser.problem("$.preset", "Gloss presets are not available in Animus; inline the preset's fields");
        GuiModel.Condition show = parser.condition(object.get("show"), "$.show", HologramDocument.ROOTS);
        Mode mode = parser.choice(object, "mode", "$", Set.of("ascend", "descend", "ascend_descend", "random"))
                .map((String text) -> Mode.valueOf(text.toUpperCase(Locale.ROOT))).orElse(Mode.ASCEND);
        int interval = parser.integer(object, "frameIntervalMs", "$", 1, 60_000).orElse(1000);
        List<GuiTemplate> frames = new ArrayList<>();
        JsonElement list = object.get("frames");
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty()) parser.problem("$.frames", "expected a non-empty array of strings");
        else {
            JsonArray array = list.getAsJsonArray();
            if (array.size() > MAXIMUM_FRAMES) parser.problem("$.frames", "at most " + MAXIMUM_FRAMES + " frames");
            for (int index = 0; index < array.size() && index < MAXIMUM_FRAMES; index++) {
                JsonElement frame = array.get(index);
                String path = "$.frames[" + index + "]";
                if (frame.isJsonNull()) { frames.add(GuiTemplate.literal("")); continue; }
                if (!frame.isJsonPrimitive() || !frame.getAsJsonPrimitive().isString()) { parser.problem(path, "expected string"); continue; }
                if (frame.getAsString().contains("|animation.")) { parser.problem(path, "a frame cannot show another animation"); continue; }
                parser.parseTemplate(frame.getAsString(), path, HologramDocument.ROOTS).ifPresent(frames::add);
            }
        }
        if (!problems.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new TextAnimation(id, revision, show, mode, interval, frames)), List.of());
    }
}
