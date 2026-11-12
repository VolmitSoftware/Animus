package art.arcane.animus.core.rig.pack;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The server's copy of the editor's Blockbench import ({@code animus-web/lib/logic/bbmodel_import.dart}); both produce
 * the same rig, animations and pack sources, which {@code schema/fixtures/pack/} pins. Every group becomes a bone and
 * its cubes one item model drawn by an item display; a cube rotation an item model cannot hold (one axis, 22.5 degree
 * steps up to 45) becomes a bone of its own. Blockbench models face -Z and rigs face +Z, so pivots and rotations turn
 * 180 degrees about Y: pivots {@code (-x, y, -z) / 16}, rotations {@code (-x, y, -z)}. Item displays draw their model
 * turned 180 degrees, which cancels that turn for the cubes. Groups named {@code hitbox} and {@code shadow} (any case)
 * are Model Engine's hitbox and shadow definitions, never drawn there, and are left out with their cubes. Only embedded PNG textures are read; nothing is fetched.
 * Pack source paths and ids follow the wiki page animus/06-rigs-animations, "Resource pack layout".
 */
final class BlockbenchImport {
    static final String SOURCE_ROOT = "pack/assets", NAMESPACE = "animus";
    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};
    private static final int MAXIMUM_BONES = 64, MAXIMUM_DEPTH = 16, MAXIMUM_TRACKS = 64, MAXIMUM_DURATION = 1200, KEYFRAMES_PER_CHANNEL = 256;

    /** The rig and animation documents, pack sources by data-folder path ({@code pack/assets/...}) and what changed on the way. */
    record Output(JsonObject rig, List<JsonObject> animations, Map<String, byte[]> resources, List<String> warnings) {}

    private static final class Unit {
        final String id, parent;
        final double[] pivot, rotation;
        final List<JsonObject> cubes = new ArrayList<>();
        Unit(String id, String parent, double[] pivot, double[] rotation) { this.id = id; this.parent = parent; this.pivot = pivot; this.rotation = rotation; }
    }
    private record Texture(String id, double uvWidth, double uvHeight) {}

    private final JsonObject model;
    private final String rigId, rigPath;
    private final RigPackBuilder.Limits limits;
    private final boolean javaBlock;
    private final List<String> warnings = new ArrayList<>();
    private final List<Unit> units = new ArrayList<>();
    private final Map<String, String> boneByGroup = new LinkedHashMap<>();
    private final Map<String, JsonObject> elements = new LinkedHashMap<>(), groups = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final List<Texture> textures = new ArrayList<>();

    private BlockbenchImport(JsonObject model, String rigId, RigPackBuilder.Limits limits) {
        this.model = model; this.rigId = rigId; this.limits = limits;
        rigPath = rigId.substring(rigId.indexOf(':') + 1);
        JsonElement meta = model.get("meta");
        javaBlock = meta != null && meta.isJsonObject() && "java_block".equals(text(meta.getAsJsonObject().get("model_format")));
    }

    /** The item model id a bone's display uses. */
    static String itemModel(String rigId, String bone) { return NAMESPACE + ":" + rigId.substring(rigId.indexOf(':') + 1) + "/" + bone; }
    static String itemDefinitionPath(String itemModel) {
        int colon = itemModel.indexOf(':');
        return SOURCE_ROOT + "/" + itemModel.substring(0, colon) + "/items/" + itemModel.substring(colon + 1) + ".json";
    }
    static String modelPath(String model) {
        int colon = model.indexOf(':');
        return SOURCE_ROOT + "/" + (colon < 0 ? "minecraft" : model.substring(0, colon)) + "/models/" + model.substring(colon + 1) + ".json";
    }
    static String texturePath(String texture) {
        int colon = texture.indexOf(':');
        return SOURCE_ROOT + "/" + (colon < 0 ? "minecraft" : texture.substring(0, colon)) + "/textures/" + texture.substring(colon + 1) + ".png";
    }

    static Output convert(String rigId, byte[] bytes, RigPackBuilder.Limits limits) throws IOException {
        if (bytes.length > limits.maximumFileBytes()) throw new IllegalArgumentException("the file is " + bytes.length + " bytes; the limit is " + limits.maximumFileBytes());
        JsonObject root;
        try { root = JsonDocuments.object(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()); }
        catch (IOException invalid) { throw new IllegalArgumentException("not a .bbmodel: " + invalid.getMessage()); }
        if (!root.has("elements") || !root.get("elements").isJsonArray() || !root.has("outliner") || !root.get("outliner").isJsonArray())
            throw new IllegalArgumentException("not a .bbmodel: it needs elements and outliner lists");
        return new BlockbenchImport(root, rigId, limits).run();
    }

    private Output run() {
        JsonArray rawElements = model.getAsJsonArray("elements");
        if (rawElements.size() > limits.maximumElements()) throw new IllegalArgumentException(rawElements.size() + " elements; the limit is " + limits.maximumElements());
        int skipped = 0;
        for (JsonElement element : rawElements) {
            if (!element.isJsonObject() || text(element.getAsJsonObject().get("uuid")) == null) continue;
            JsonElement type = element.getAsJsonObject().get("type");
            if (type != null && !type.isJsonNull() && !"cube".equals(text(type))) { skipped++; continue; }
            elements.put(text(element.getAsJsonObject().get("uuid")), element.getAsJsonObject());
        }
        if (skipped > 0) warnings.add(skipped + " mesh or locator elements are not cubes and were skipped.");
        if (model.has("groups") && model.get("groups").isJsonArray())
            for (JsonElement group : model.getAsJsonArray("groups"))
                if (group.isJsonObject() && text(group.getAsJsonObject().get("uuid")) != null) groups.put(text(group.getAsJsonObject().get("uuid")), group.getAsJsonObject());
        textures();
        Unit loose = new Unit("root", null, new double[3], new double[3]);
        for (JsonElement node : model.getAsJsonArray("outliner")) {
            if (text(node) != null) { JsonObject cube = elements.get(text(node)); if (cube != null) loose.cubes.add(cube); }
            else if (node.isJsonObject()) group(node.getAsJsonObject(), null, 1);
        }
        if (!loose.cubes.isEmpty() || units.isEmpty()) {
            Unit root = new Unit(free("root"), null, loose.pivot, loose.rotation);
            root.cubes.addAll(loose.cubes);
            units.addFirst(root);
        }
        List<Unit> all = new ArrayList<>();
        for (Unit unit : List.copyOf(units)) { all.add(unit); all.addAll(freeRotations(unit)); }
        if (all.size() > MAXIMUM_BONES) throw new IllegalArgumentException("the model needs " + all.size() + " bones (groups and freely rotated cubes); a rig holds " + MAXIMUM_BONES);
        JsonObject rig = new JsonObject();
        rig.addProperty("schemaVersion", 1);
        rig.addProperty("revision", 1);
        rig.addProperty("id", rigId);
        rig.addProperty("units", "blocks");
        rig.addProperty("body", "hidden");
        JsonArray bones = new JsonArray();
        for (Unit unit : all) bones.add(bone(unit));
        rig.add("bones", bones);
        List<JsonObject> clips = animations();
        if (!clips.isEmpty()) {
            JsonObject named = new JsonObject();
            for (JsonObject clip : clips) named.addProperty(clip.get("id").getAsString().substring(rigId.length() + 1), clip.get("id").getAsString());
            rig.add("animations", named);
        }
        return new Output(rig, clips, resources, warnings);
    }

    private String free(String base) {
        Set<String> used = new HashSet<>();
        for (Unit unit : units) used.add(unit.id);
        if (!used.contains(base)) return base;
        for (int suffix = 2; ; suffix++) if (!used.contains(base + "-" + suffix)) return base + "-" + suffix;
    }

    private void group(JsonObject node, String parent, int depth) {
        String uuid = text(node.get("uuid"));
        JsonObject group = new JsonObject();
        JsonObject declared = uuid == null ? null : groups.get(uuid);
        if (declared != null) for (Map.Entry<String, JsonElement> entry : declared.entrySet()) group.add(entry.getKey(), entry.getValue());
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) group.add(entry.getKey(), entry.getValue());
        String name = display(group.get("name"));
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "hitbox" -> { warnings.add("Group " + name + " is the Model Engine hitbox; it was left out and the NPC keeps its body's hitbox."); return; }
            case "shadow" -> { warnings.add("Group " + name + " is the Model Engine shadow; it was left out."); return; }
            default -> { }
        }
        Unit unit;
        if (depth > MAXIMUM_DEPTH && parent != null) {
            warnings.add("Group " + display(group.get("name")) + " nests deeper than " + MAXIMUM_DEPTH + "; its cubes joined " + parent + ".");
            unit = units.stream().filter((Unit candidate) -> candidate.id.equals(parent)).findFirst().orElseThrow();
        } else {
            double[] origin = vector(group.get("origin"), new double[3]);
            unit = new Unit(free(sanitize(name, "bone")), parent,
                    javaBlock ? new double[]{origin[0] - 8, origin[1], origin[2] - 8} : origin, vector(group.get("rotation"), new double[3]));
            units.add(unit);
        }
        if (uuid != null) boneByGroup.put(uuid, unit.id);
        JsonElement children = group.get("children");
        if (children == null || !children.isJsonArray()) return;
        for (JsonElement child : children.getAsJsonArray()) {
            if (text(child) != null) {
                JsonObject cube = elements.get(text(child));
                if (cube != null) unit.cubes.add(cube);
                else if (groups.containsKey(text(child))) {
                    JsonObject reference = new JsonObject();
                    reference.addProperty("uuid", text(child));
                    group(reference, unit.id, depth + 1);
                }
            } else if (child.isJsonObject()) group(child.getAsJsonObject(), unit.id, depth + 1);
        }
    }

    private List<Unit> freeRotations(Unit unit) {
        List<Unit> extra = new ArrayList<>();
        for (JsonObject cube : List.copyOf(unit.cubes)) {
            double[] rotation = vector(cube.get("rotation"), new double[3]);
            if (elementRotation(rotation) != null || (rotation[0] == 0 && rotation[1] == 0 && rotation[2] == 0)) continue;
            unit.cubes.remove(cube);
            double[] origin = vector(cube.get("origin"), unit.pivot);
            String name = cube.has("name") && !cube.get("name").isJsonNull() ? display(cube.get("name")) : "cube";
            Unit own = new Unit(free(sanitize(unit.id + "-" + name, unit.id + "-cube")), unit.id,
                    javaBlock ? new double[]{origin[0] - 8, origin[1], origin[2] - 8} : origin, rotation);
            JsonObject flat = cube.deepCopy();
            JsonArray zero = new JsonArray();
            zero.add(0); zero.add(0); zero.add(0);
            flat.add("rotation", zero);
            own.cubes.add(flat);
            units.add(own);
            extra.add(own);
        }
        return extra;
    }

    /** One axis in 22.5 degree steps within 45, as {axis, angle}; null otherwise. */
    private static Object[] elementRotation(double[] rotation) {
        List<Integer> turned = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) if (rotation[axis] != 0) turned.add(axis);
        if (turned.size() != 1) return null;
        double angle = rotation[turned.getFirst()];
        if (Math.abs(angle) > 45 || angle / 22.5 != Math.rint(angle / 22.5)) return null;
        return new Object[]{new String[]{"x", "y", "z"}[turned.getFirst()], angle};
    }

    private void textures() {
        JsonElement raw = model.get("textures");
        if (raw == null || !raw.isJsonArray()) return;
        if (raw.getAsJsonArray().size() > limits.maximumTextures())
            throw new IllegalArgumentException(raw.getAsJsonArray().size() + " textures; the limit is " + limits.maximumTextures());
        JsonObject resolution = model.has("resolution") && model.get("resolution").isJsonObject() ? model.getAsJsonObject("resolution") : new JsonObject();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < raw.getAsJsonArray().size(); index++) {
            JsonElement entry = raw.getAsJsonArray().get(index);
            JsonObject json = entry.isJsonObject() ? entry.getAsJsonObject() : new JsonObject();
            double uvWidth = number(json.has("uv_width") && !json.get("uv_width").isJsonNull() ? json.get("uv_width") : resolution.get("width"), 16);
            double uvHeight = number(json.has("uv_height") && !json.get("uv_height").isJsonNull() ? json.get("uv_height") : resolution.get("height"), 16);
            String label = json.has("name") && !json.get("name").isJsonNull() ? display(json.get("name")) : "texture " + index;
            byte[] png = png(json.get("source"), label);
            if (png == null) { textures.add(new Texture(null, uvWidth, uvHeight)); continue; }
            String original = json.has("name") && !json.get("name").isJsonNull() ? display(json.get("name")) : "";
            String name = sanitize(original.replaceAll("(?i)\\.png$", ""), "texture");
            for (int suffix = 2; !names.add(name); suffix++) name = sanitize(original, "texture") + "-" + suffix;
            String id = NAMESPACE + ":item/" + rigPath + "/" + name;
            resources.put(texturePath(id), png);
            textures.add(new Texture(id, uvWidth, uvHeight));
        }
    }

    private byte[] png(JsonElement source, String name) {
        String prefix = "data:image/png;base64,", value = text(source);
        if (value == null || !value.startsWith(prefix)) {
            warnings.add("Texture " + name + " is not embedded in the file; its faces show the missing texture.");
            return null;
        }
        if ((long) (value.length() - prefix.length()) * 3 / 4 > limits.maximumTextureBytes())
            throw new IllegalArgumentException("Texture " + name + " is over " + limits.maximumTextureBytes() + " bytes.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value.substring(prefix.length())); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Texture " + name + " is not valid base64."); }
        int[] size = pngSize(bytes);
        if (size == null) throw new IllegalArgumentException("Texture " + name + " is not a PNG.");
        if (size[0] > limits.maximumTextureSide() || size[1] > limits.maximumTextureSide() * 32L)
            throw new IllegalArgumentException("Texture " + name + " is " + size[0] + "x" + size[1] + "; the limit is " + limits.maximumTextureSide() + " wide.");
        return bytes;
    }

    static int[] pngSize(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes.length < 24) return null;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return null;
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') return null;
        long width = ((bytes[16] & 0xffL) << 24) | ((bytes[17] & 0xff) << 16) | ((bytes[18] & 0xff) << 8) | (bytes[19] & 0xff);
        long height = ((bytes[20] & 0xffL) << 24) | ((bytes[21] & 0xff) << 16) | ((bytes[22] & 0xff) << 8) | (bytes[23] & 0xff);
        return new int[]{(int) Math.min(Integer.MAX_VALUE, width), (int) Math.min(Integer.MAX_VALUE, height)};
    }

    private JsonObject bone(Unit unit) {
        JsonObject bone = new JsonObject();
        bone.addProperty("id", unit.id);
        if (unit.parent != null) bone.addProperty("parent", unit.parent);
        bone.add("pivot", rounded(-unit.pivot[0] / 16, unit.pivot[1] / 16, -unit.pivot[2] / 16));
        if (unit.rotation[0] != 0 || unit.rotation[1] != 0 || unit.rotation[2] != 0) bone.add("rotation", rounded(-unit.rotation[0], unit.rotation[1], -unit.rotation[2]));
        if (unit.cubes.isEmpty()) return bone;
        double scale = writeModel(unit);
        JsonObject visual = new JsonObject();
        visual.addProperty("type", "item");
        JsonObject item = new JsonObject();
        item.addProperty("id", "minecraft:paper");
        item.addProperty("components", "[item_model=\"" + itemModel(rigId, unit.id) + "\"]");
        visual.add("item", item);
        if (scale != 1) visual.add("scale", rounded(1 / scale, 1 / scale, 1 / scale));
        bone.add("visual", visual);
        return bone;
    }

    /** Writes the bone's item definition and model; returns how much its cubes shrank to fit the model bounds (-16..32). */
    private double writeModel(Unit unit) {
        double[] origin = unit.pivot;
        double extent = 0;
        for (JsonObject cube : unit.cubes) {
            double inflate = number(cube.get("inflate"), 0);
            for (String corner : List.of("from", "to")) {
                double[] point = vector(cube.get(corner), new double[3]);
                for (int axis = 0; axis < 3; axis++) extent = Math.max(extent, Math.abs(point[axis] + (javaBlock && axis != 1 ? -8 : 0) - origin[axis]) + inflate);
            }
        }
        double scale = extent > 24 ? 24 / extent : 1;
        if (scale != 1) warnings.add("Bone " + unit.id + " is larger than an item model allows; its model is scaled by " + format(round4(scale)) + " and its display by the inverse.");
        JsonArray out = new JsonArray();
        Set<Integer> used = new TreeSet<>();
        for (JsonObject cube : unit.cubes) {
            double inflate = number(cube.get("inflate"), 0);
            double[] from = place(vector(cube.get("from"), new double[3]), origin, scale), to = place(vector(cube.get("to"), new double[3]), origin, scale);
            double[] low = new double[3], high = new double[3];
            for (int axis = 0; axis < 3; axis++) { low[axis] = Math.min(from[axis], to[axis]) - inflate * scale; high[axis] = Math.max(from[axis], to[axis]) + inflate * scale; }
            JsonObject faces = new JsonObject();
            JsonElement rawFaces = cube.get("faces");
            if (rawFaces != null && rawFaces.isJsonObject()) {
                for (String side : FACES) {
                    JsonElement raw = rawFaces.getAsJsonObject().get(side);
                    if (raw == null || !raw.isJsonObject() || !integer(raw.getAsJsonObject().get("texture"))) continue;
                    int index = raw.getAsJsonObject().get("texture").getAsInt();
                    if (index < 0 || index >= textures.size()) continue;
                    used.add(index);
                    Texture texture = textures.get(index);
                    double[] uv = vector4(raw.getAsJsonObject().get("uv"));
                    JsonObject face = new JsonObject();
                    face.add("uv", rounded(uv[0] * 16 / texture.uvWidth(), uv[1] * 16 / texture.uvHeight(), uv[2] * 16 / texture.uvWidth(), uv[3] * 16 / texture.uvHeight()));
                    face.addProperty("texture", "#" + index);
                    JsonElement turn = raw.getAsJsonObject().get("rotation");
                    if (numeric(turn) && turn.getAsDouble() != 0) face.addProperty("rotation", Math.round(turn.getAsDouble()));
                    JsonElement tint = raw.getAsJsonObject().get("tint");
                    if (numeric(tint) && tint.getAsDouble() >= 0) face.addProperty("tintindex", Math.round(tint.getAsDouble()));
                    faces.add(side, face);
                }
            }
            if (faces.isEmpty()) continue;
            JsonObject element = new JsonObject();
            element.add("from", rounded(low));
            element.add("to", rounded(high));
            element.add("faces", faces);
            JsonElement shade = cube.get("shade");
            if (shade != null && shade.isJsonPrimitive() && shade.getAsJsonPrimitive().isBoolean() && !shade.getAsBoolean()) element.addProperty("shade", false);
            Object[] rotation = elementRotation(vector(cube.get("rotation"), new double[3]));
            if (rotation != null && (double) rotation[1] != 0) {
                JsonObject turn = new JsonObject();
                turn.add("angle", number4((double) rotation[1]));
                turn.addProperty("axis", (String) rotation[0]);
                turn.add("origin", rounded(place(vector(cube.get("origin"), origin), origin, scale)));
                element.add("rotation", turn);
            }
            out.add(element);
        }
        JsonObject textureMap = new JsonObject();
        for (int index : used) textureMap.addProperty(String.valueOf(index), textures.get(index).id() == null ? "minecraft:missingno" : textures.get(index).id());
        if (!textureMap.isEmpty()) textureMap.add("particle", textureMap.entrySet().iterator().next().getValue());
        String item = itemModel(rigId, unit.id), model = NAMESPACE + ":item/" + rigPath + "/" + unit.id;
        JsonObject definition = new JsonObject(), reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", model);
        definition.add("model", reference);
        resources.put(itemDefinitionPath(item), JsonDocuments.write(definition).getBytes(StandardCharsets.UTF_8));
        JsonObject itemModel = new JsonObject();
        itemModel.add("textures", textureMap);
        itemModel.add("elements", out);
        resources.put(modelPath(model), JsonDocuments.write(itemModel).getBytes(StandardCharsets.UTF_8));
        return scale;
    }

    private double[] place(double[] point, double[] origin, double scale) {
        double[] result = new double[3];
        for (int axis = 0; axis < 3; axis++) result[axis] = 8 + (point[axis] + (javaBlock && axis != 1 ? -8 : 0) - origin[axis]) * scale;
        return result;
    }

    private List<JsonObject> animations() {
        JsonElement raw = model.get("animations");
        if (raw == null || !raw.isJsonArray()) return List.of();
        if (raw.getAsJsonArray().size() > limits.maximumAnimations())
            throw new IllegalArgumentException(raw.getAsJsonArray().size() + " animations; the limit is " + limits.maximumAnimations());
        List<JsonObject> clips = new ArrayList<>();
        Set<String> names = new HashSet<>(), approximated = new LinkedHashSet<>();
        for (JsonElement entryElement : raw.getAsJsonArray()) {
            if (!entryElement.isJsonObject()) continue;
            JsonObject entry = entryElement.getAsJsonObject();
            String label = entry.has("name") && !entry.get("name").isJsonNull() ? display(entry.get("name")) : "";
            String name = sanitize(label.replaceAll("^animation\\.[^.]*\\.", ""), "animation");
            for (int suffix = 2; !names.add(name); suffix++) name = sanitize(label, "animation") + "-" + suffix;
            int duration = (int) Math.max(1, Math.min(MAXIMUM_DURATION, Math.ceil(number(entry.get("length"), 1) * 20)));
            String loop = switch (String.valueOf(text(entry.get("loop")))) { case "loop" -> "loop"; case "hold" -> "hold"; default -> "once"; };
            JsonArray tracks = new JsonArray();
            JsonElement animators = entry.get("animators");
            if (animators != null && animators.isJsonObject()) {
                for (Map.Entry<String, JsonElement> animator : animators.getAsJsonObject().entrySet()) {
                    String bone = boneByGroup.get(animator.getKey());
                    if (bone == null || !animator.getValue().isJsonObject() || !animator.getValue().getAsJsonObject().has("keyframes")
                            || !animator.getValue().getAsJsonObject().get("keyframes").isJsonArray()) continue;
                    JsonArray keyframes = animator.getValue().getAsJsonObject().getAsJsonArray("keyframes");
                    for (String channel : List.of("rotation", "position", "scale")) {
                        List<JsonObject> keys = new ArrayList<>();
                        for (JsonElement key : keyframes) if (key.isJsonObject() && channel.equals(text(key.getAsJsonObject().get("channel")))) keys.add(key.getAsJsonObject());
                        if (keys.isEmpty()) continue;
                        if (keys.size() > KEYFRAMES_PER_CHANNEL)
                            throw new IllegalArgumentException("Animation " + name + " has " + keys.size() + " " + channel + " keyframes on " + bone + "; the limit is " + KEYFRAMES_PER_CHANNEL);
                        keys.sort(Comparator.comparingDouble((JsonObject key) -> number(key.get("time"), 0)));
                        JsonArray frames = new JsonArray();
                        double previous = -1;
                        for (JsonObject key : keys) {
                            double tick = round(Math.min(number(key.get("time"), 0) * 20, duration), 1000);
                            if (tick <= previous) continue;
                            previous = tick;
                            double[] value = point(key, name);
                            double[] converted = switch (channel) {
                                case "rotation" -> new double[]{value[0], -value[1], -value[2]};
                                case "position" -> new double[]{value[0] / 16, value[1] / 16, -value[2] / 16};
                                default -> value;
                            };
                            String interpolation = key.has("interpolation") && !key.get("interpolation").isJsonNull() ? display(key.get("interpolation")) : "linear";
                            String easing = switch (interpolation) { case "step" -> "hold"; case "catmullrom", "bezier" -> "ease_in_out"; default -> "linear"; };
                            if (easing.equals("ease_in_out")) approximated.add(interpolation);
                            JsonObject frame = new JsonObject();
                            frame.add("tick", whole(tick));
                            frame.add("value", rounded(converted));
                            if (!easing.equals("linear")) frame.addProperty("easing", easing);
                            frames.add(frame);
                        }
                        JsonObject track = new JsonObject();
                        track.addProperty("bone", bone);
                        track.addProperty("target", channel.equals("position") ? "translation" : channel);
                        track.addProperty("blend", "replace");
                        track.add("keyframes", frames);
                        tracks.add(track);
                    }
                }
            }
            if (tracks.size() > MAXIMUM_TRACKS) throw new IllegalArgumentException("Animation " + name + " has " + tracks.size() + " tracks; the limit is " + MAXIMUM_TRACKS);
            JsonObject clip = new JsonObject();
            clip.addProperty("schemaVersion", 1);
            clip.addProperty("revision", 1);
            clip.addProperty("id", rigId + "/" + name);
            clip.addProperty("rig", rigId);
            clip.addProperty("durationTicks", duration);
            clip.addProperty("loop", loop);
            clip.add("tracks", tracks);
            clips.add(clip);
        }
        for (String interpolation : approximated) warnings.add("Blockbench " + interpolation + " keyframes play as ease_in_out.");
        return clips;
    }

    private double[] point(JsonObject key, String animation) {
        JsonElement points = key.get("data_points");
        JsonElement first = points != null && points.isJsonArray() && !points.getAsJsonArray().isEmpty() ? points.getAsJsonArray().get(0) : null;
        if (first == null || !first.isJsonObject()) return new double[3];
        double[] result = new double[3];
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) result[i] = molang(first.getAsJsonObject().get(axes[i]), animation);
        return result;
    }
    private double molang(JsonElement value, String animation) {
        if (numeric(value)) return value.getAsDouble();
        String text = text(value);
        if (text != null) {
            String trimmed = text.trim().isEmpty() ? "0" : text.trim();
            try { return Double.parseDouble(trimmed); }
            catch (NumberFormatException expression) { warnings.add("Animation " + animation + " uses a Molang expression (" + text + "); it plays as 0."); }
        }
        return 0;
    }

    /** A Blockbench name as a bone id or path segment, as the editor makes it. */
    static String sanitize(String name, String fallback) {
        String out = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^[_-]+", "");
        if (out.length() > 48) out = out.substring(0, 48);
        return out.isEmpty() ? fallback : out;
    }
    private static String text(JsonElement element) { return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null; }
    /** A value as Dart's string interpolation writes it. */
    private static String display(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return element.getAsString();
        return element.toString();
    }
    private static boolean numeric(JsonElement element) { return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(); }
    /** Dart reads a JSON number without a fraction or exponent as an int. */
    private static boolean integer(JsonElement element) {
        if (!numeric(element)) return false;
        String literal = element.getAsJsonPrimitive().getAsString();
        return !literal.contains(".") && !literal.contains("e") && !literal.contains("E");
    }
    private static double number(JsonElement element, double fallback) { return numeric(element) ? element.getAsDouble() : fallback; }
    private static double[] vector(JsonElement element, double[] fallback) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 3) return fallback.clone();
        double[] result = new double[3];
        for (int axis = 0; axis < 3; axis++) result[axis] = number(element.getAsJsonArray().get(axis), fallback[axis]);
        return result;
    }
    private static double[] vector4(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 4) return new double[]{0, 0, 16, 16};
        double[] result = new double[4];
        for (int i = 0; i < 4; i++) result[i] = number(element.getAsJsonArray().get(i), 0);
        return result;
    }
    /** Rounds half away from zero, as Dart's roundToDouble. */
    private static double round(double value, double scale) {
        double scaled = value * scale;
        return Math.signum(scaled) * Math.floor(Math.abs(scaled) + 0.5) / scale;
    }
    private static double round4(double value) { return round(value, 10000); }
    /** Whole numbers as integers, as the editor writes them. */
    private static JsonPrimitive whole(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return new JsonPrimitive((long) value);
        return new JsonPrimitive(value);
    }
    private static JsonPrimitive number4(double value) { return whole(round4(value) == 0 ? 0 : round4(value)); }
    private static JsonArray rounded(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) array.add(number4(value));
        return array;
    }
    private static String format(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
}
