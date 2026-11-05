package art.arcane.animus.core.rig;

import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code rigs/<namespace>/<path>.json}: a tree of bones shown as packet-only display entities. Validated against
 * {@code schema/rig-v1.schema.json} plus the rules a schema cannot express: unique bone ids, parents listed before
 * their children, depth at most 16, and sockets that name bones.
 */
public record RigDocument(String id, long revision, Body body, List<Bone> bones, Map<String, String> sockets,
                          Map<String, String> animations, String source) {
    public static final int MAX_BONES = 64, MAX_DEPTH = 16;
    public static final Set<String> SOCKETS = Set.of("main_hand", "off_hand", "head", "chest", "legs", "feet", "body", "saddle");
    private static final Set<String> FIELDS = Set.of("schemaVersion", "revision", "id", "units", "body", "bones", "sockets", "animations");
    private static final Set<String> BONE_FIELDS = Set.of("id", "parent", "pivot", "rotation", "visual");
    private static final Set<String> ITEM_FIELDS = Set.of("id", "count", "components");
    private static final double[] ZERO = {0, 0, 0}, ONE = {1, 1, 1};
    private static final double LIMIT = 1024;

    public enum Body { HIDDEN, SHOWN }
    public enum Type { ITEM, BLOCK, TEXT, NONE }

    /** What one bone shows. Fields that do not belong to the type are empty. */
    public record Visual(Type type, double[] translation, double[] rotation, double[] scale, Optional<NpcItem> item, String itemDisplay,
                         Optional<String> block, Optional<String> text, String billboard, int background, boolean shadow, int lineWidth) {
        public static final Visual NONE = new Visual(Type.NONE, ZERO, ZERO, ONE, Optional.empty(), "none", Optional.empty(), Optional.empty(), "fixed", 0x40000000, false, 200);
        public Visual {
            translation = translation.clone(); rotation = rotation.clone(); scale = scale.clone();
            Objects.requireNonNull(item); Objects.requireNonNull(block); Objects.requireNonNull(text);
        }
        @Override public double[] translation() { return translation.clone(); }
        @Override public double[] rotation() { return rotation.clone(); }
        @Override public double[] scale() { return scale.clone(); }
        /** The visual's own transform in the bone's space. */
        public Affine transform() { return Affine.translation(translation).times(Affine.rotation(rotation)).times(Affine.scale(scale)); }
    }
    /** {@code parent} is the index of an earlier bone, or -1. */
    public record Bone(String id, int parent, double[] pivot, double[] rotation, Visual visual) {
        public Bone { pivot = pivot.clone(); rotation = rotation.clone(); Objects.requireNonNull(visual); }
        @Override public double[] pivot() { return pivot.clone(); }
        @Override public double[] rotation() { return rotation.clone(); }
        public boolean shown() { return visual.type() != Type.NONE; }
    }
    public record Result(Optional<RigDocument> document, List<String> problems) {
        public Result { Objects.requireNonNull(document); problems = List.copyOf(problems); }
    }

    public RigDocument {
        bones = List.copyOf(bones);
        sockets = Collections.unmodifiableMap(new TreeMap<>(sockets));
        animations = Collections.unmodifiableMap(new TreeMap<>(animations));
    }

    /** Index of a bone, or -1. */
    public int index(String bone) {
        for (int i = 0; i < bones.size(); i++) if (bones.get(i).id().equals(bone)) return i;
        return -1;
    }
    /** Indexes of the bones that show something, in document order. Display entities are created in this order. */
    public int[] displayBones() {
        return java.util.stream.IntStream.range(0, bones.size()).filter((int i) -> bones.get(i).shown()).toArray();
    }
    /** The named animation, such as {@code idle} or {@code walk}. */
    public Optional<String> animation(String name) { return Optional.ofNullable(animations.get(name)); }

    /** @param expectedId the id this file's location gives it, or null when there is no location to check */
    public static Result parse(String expectedId, String source) {
        List<String> problems = new ArrayList<>();
        if (source.getBytes(StandardCharsets.UTF_8).length > RigFields.MAX_BYTES) return new Result(Optional.empty(), List.of("$: exceeds 256 KiB"));
        JsonObject root;
        try { root = JsonDocuments.object(source); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
        RigFields.keys(root, FIELDS, "$", problems);
        RigFields.schemaVersion(root, problems);
        long revision = RigFields.revision(root, problems);
        Optional<String> id = RigFields.documentId(root, "id", "$", problems);
        if (id.isPresent() && expectedId != null && !id.get().equals(expectedId)) problems.add("$.id: expected " + expectedId + " to match the file's location");
        if (root.has("units") && !"blocks".equals(RigFields.string(root.get("units")) ? root.get("units").getAsString() : null))
            problems.add("$.units: expected blocks");
        Body body = Body.HIDDEN;
        if (root.has("body")) body = RigFields.choice(root, "body", "$", Set.of("hidden", "shown"), problems).map((String value) -> Body.valueOf(value.toUpperCase(java.util.Locale.ROOT))).orElse(Body.HIDDEN);
        List<Bone> bones = bones(root.get("bones"), problems);
        Map<String, Integer> byId = new HashMap<>();
        for (int i = 0; i < bones.size(); i++) byId.put(bones.get(i).id(), i);
        Map<String, String> sockets = new LinkedHashMap<>();
        if (root.has("sockets")) {
            if (!root.get("sockets").isJsonObject()) problems.add("$.sockets: expected object");
            else for (Map.Entry<String, JsonElement> socket : root.getAsJsonObject("sockets").entrySet()) {
                String path = "$.sockets." + socket.getKey();
                if (!SOCKETS.contains(socket.getKey())) { problems.add(path + ": unknown equipment slot"); continue; }
                RigFields.boneId(socket.getValue(), path, problems).ifPresent((String bone) -> {
                    if (!byId.containsKey(bone)) problems.add(path + ": names no bone of this rig");
                    else sockets.put(socket.getKey(), bone);
                });
            }
        }
        Map<String, String> animations = new LinkedHashMap<>();
        if (root.has("animations")) {
            if (!root.get("animations").isJsonObject()) problems.add("$.animations: expected object");
            else for (Map.Entry<String, JsonElement> animation : root.getAsJsonObject("animations").entrySet()) {
                String path = "$.animations." + animation.getKey();
                if (!animation.getKey().matches(RigFields.BONE_ID)) { problems.add(path + ": expected a name of lowercase letters, digits, _ or -"); continue; }
                JsonElement value = animation.getValue();
                if (!RigFields.string(value) || value.getAsString().length() > 256 || !value.getAsString().matches(RigFields.DOCUMENT_ID))
                    problems.add(path + ": expected namespace:path");
                else animations.put(animation.getKey(), value.getAsString());
            }
        }
        if (!problems.isEmpty() || id.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new RigDocument(id.get(), revision, body, bones, sockets, animations, source)), List.of());
    }

    private static List<Bone> bones(JsonElement element, List<String> problems) {
        List<Bone> bones = new ArrayList<>();
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty() || element.getAsJsonArray().size() > MAX_BONES) {
            problems.add("$.bones: expected 1.." + MAX_BONES + " bones");
            return bones;
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Integer> depths = new ArrayList<>();
        int index = 0;
        for (JsonElement entry : element.getAsJsonArray()) {
            String path = "$.bones[" + index++ + "]";
            if (!entry.isJsonObject()) { problems.add(path + ": expected object"); continue; }
            JsonObject bone = entry.getAsJsonObject();
            RigFields.keys(bone, BONE_FIELDS, path, problems);
            Optional<String> id = RigFields.boneId(bone.get("id"), path + ".id", problems);
            int parent = -1, depth = 1;
            if (bone.has("parent")) {
                Optional<String> parentId = RigFields.boneId(bone.get("parent"), path + ".parent", problems);
                if (parentId.isPresent()) {
                    Integer parentIndex = seen.get(parentId.get());
                    if (parentIndex == null) problems.add(path + ".parent: must name an earlier bone");
                    else { parent = parentIndex; depth = depths.get(parentIndex) + 1; }
                }
            }
            if (depth > MAX_DEPTH) problems.add(path + ".parent: bone depth exceeds " + MAX_DEPTH);
            double[] pivot = RigFields.vector(bone.get("pivot"), path + ".pivot", LIMIT, problems);
            double[] rotation = RigFields.vector(bone, "rotation", path, LIMIT, ZERO, problems);
            Visual visual = bone.has("visual") ? visual(bone.get("visual"), path + ".visual", problems) : Visual.NONE;
            if (id.isEmpty()) continue;
            if (seen.containsKey(id.get())) { problems.add(path + ".id: duplicate bone id " + id.get()); continue; }
            seen.put(id.get(), bones.size()); depths.add(depth);
            bones.add(new Bone(id.get(), parent, pivot == null ? ZERO : pivot, rotation, visual));
        }
        return bones;
    }

    private static Visual visual(JsonElement element, String path, List<String> problems) {
        if (element == null || !element.isJsonObject()) { problems.add(path + ": expected object"); return Visual.NONE; }
        JsonObject visual = element.getAsJsonObject();
        Optional<String> kind = RigFields.choice(visual, "type", path, Set.of("item", "block", "text", "none"), problems);
        if (kind.isEmpty()) return Visual.NONE;
        Type type = Type.valueOf(kind.get().toUpperCase(java.util.Locale.ROOT));
        Set<String> allowed = switch (type) {
            case ITEM -> Set.of("type", "translation", "rotation", "scale", "item", "itemDisplay");
            case BLOCK -> Set.of("type", "translation", "rotation", "scale", "block");
            case TEXT -> Set.of("type", "translation", "rotation", "scale", "text", "billboard", "background", "shadow", "lineWidth");
            case NONE -> Set.of("type");
        };
        RigFields.keys(visual, allowed, path, problems);
        if (type == Type.NONE) return Visual.NONE;
        double[] translation = RigFields.vector(visual, "translation", path, LIMIT, ZERO, problems);
        double[] rotation = RigFields.vector(visual, "rotation", path, LIMIT, ZERO, problems);
        double[] scale = RigFields.vector(visual, "scale", path, LIMIT, ONE, problems);
        Optional<NpcItem> item = Optional.empty();
        String itemDisplay = "none", billboard = "fixed";
        Optional<String> block = Optional.empty(), text = Optional.empty();
        int background = 0x40000000, lineWidth = 200;
        boolean shadow = false;
        switch (type) {
            case ITEM -> {
                JsonElement value = visual.get("item");
                if (value == null || !value.isJsonObject()) problems.add(path + ".item: expected item object");
                else {
                    RigFields.keys(value.getAsJsonObject(), ITEM_FIELDS, path + ".item", problems);
                    item = NpcItem.parse(value, path + ".item", problems);
                }
                if (visual.has("itemDisplay")) itemDisplay = RigFields.choice(visual, "itemDisplay", path, Set.of("none", "thirdperson_lefthand", "thirdperson_righthand",
                        "firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed"), problems).orElse("none");
            }
            case BLOCK -> {
                block = RigFields.string(visual, "block", path, problems);
                if (block.isPresent() && (block.get().length() > 1024 || !block.get().matches("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[[^\\]]*\\])?"))) {
                    problems.add(path + ".block: expected a block state such as minecraft:oak_log[axis=y]");
                    block = Optional.empty();
                }
            }
            case TEXT -> {
                text = RigFields.string(visual, "text", path, problems);
                if (text.isPresent() && text.get().length() > 1024) { problems.add(path + ".text: expected at most 1024 characters"); text = Optional.empty(); }
                if (visual.has("billboard")) billboard = RigFields.choice(visual, "billboard", path, Set.of("fixed", "vertical", "horizontal", "center"), problems).orElse("fixed");
                if (visual.has("background")) {
                    JsonElement value = visual.get("background");
                    if (!RigFields.string(value) || !value.getAsString().matches("#[0-9a-fA-F]{8}")) problems.add(path + ".background: expected #AARRGGBB");
                    else background = (int) Long.parseLong(value.getAsString().substring(1), 16);
                }
                if (visual.has("shadow")) {
                    if (!RigFields.bool(visual.get("shadow"))) problems.add(path + ".shadow: expected boolean");
                    else shadow = visual.get("shadow").getAsBoolean();
                }
                if (visual.has("lineWidth")) lineWidth = RigFields.integer(visual.get("lineWidth"), 1, 1024, path + ".lineWidth", problems, 200);
            }
            case NONE -> { }
        }
        return new Visual(type, translation, rotation, scale, item, itemDisplay, block, text, billboard, background, shadow, lineWidth);
    }
}
