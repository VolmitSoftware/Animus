package art.arcane.animus.core.rig.pack;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One Blockbench {@code .bbmodel} turned into item models, textures, a rig and its animations. Every group becomes a
 * bone (its parent is the enclosing group); each bone's cubes become one item model centred on the bone's pivot and
 * shrunk to the item model bounds when needed, with the bone's display scale making up the difference. Only embedded
 * PNG textures are read: there is no file or network access. Cube rotations are kept when an item model can express them
 * (one axis, 22.5 degree steps up to 45); free rotations belong on bones.
 */
final class BlockbenchImport {
    static final String CARRIER = "minecraft:paper", DISPATCH = "animus:rig_bone";
    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    private static final Set<Double> ANGLES = Set.of(-45.0, -22.5, 0.0, 22.5, 45.0);
    private static final double MODEL_REACH = 24;

    /** Bone item model JSON by bone id, texture PNGs by texture name, the rig, and animation documents by name. */
    record Output(String namespace, String model, Map<String, String> models, Map<String, byte[]> textures, String rig,
                  Map<String, String> animations, List<String> problems) {}

    private record Group(String id, String name, int parent, double[] origin, double[] rotation, List<JsonObject> elements, int depth) {}
    private record Texture(String key, String name, double uvWidth, double uvHeight, byte[] png) {}

    private BlockbenchImport() {}

    static Output convert(String namespace, String model, String source, RigPackBuilder.Limits limits) throws IOException {
        JsonObject root = JsonDocuments.object(source);
        List<String> problems = new ArrayList<>();
        JsonObject resolution = root.has("resolution") && root.get("resolution").isJsonObject() ? root.getAsJsonObject("resolution") : new JsonObject();
        double width = positive(resolution.get("width"), 16), height = positive(resolution.get("height"), 16);
        List<Texture> textures = textures(root, width, height, limits);
        Map<String, Integer> textureIndex = new HashMap<>();
        for (int i = 0; i < textures.size(); i++) textureIndex.put(String.valueOf(i), i);
        JsonArray textureList = root.has("textures") && root.get("textures").isJsonArray() ? root.getAsJsonArray("textures") : new JsonArray();
        for (int i = 0; i < textureList.size(); i++) {
            JsonObject texture = textureList.get(i).getAsJsonObject();
            for (String key : List.of("uuid", "id")) if (texture.has(key) && string(texture.get(key))) textureIndex.putIfAbsent(texture.get(key).getAsString(), i);
        }

        Map<String, JsonObject> elements = new LinkedHashMap<>();
        JsonArray elementList = root.has("elements") && root.get("elements").isJsonArray() ? root.getAsJsonArray("elements") : new JsonArray();
        if (elementList.size() > limits.maximumElements()) throw new IllegalArgumentException("more than " + limits.maximumElements() + " cubes");
        for (JsonElement element : elementList) {
            if (!element.isJsonObject()) continue;
            JsonObject cube = element.getAsJsonObject();
            if (cube.has("type") && string(cube.get("type")) && !cube.get("type").getAsString().equals("cube")) continue;
            String uuid = string(cube.get("uuid")) ? cube.get("uuid").getAsString() : "element-" + elements.size();
            elements.put(uuid, cube);
        }

        List<Group> groups = new ArrayList<>();
        Map<String, String> groupIds = new HashMap<>();
        Set<String> used = new HashSet<>(), placed = new HashSet<>();
        Group loose = new Group(unique("model", used), "model", -1, new double[3], new double[3], new ArrayList<>(), 1);
        JsonArray outliner = root.has("outliner") && root.get("outliner").isJsonArray() ? root.getAsJsonArray("outliner") : new JsonArray();
        for (JsonElement node : outliner) walk(node, -1, 1, groups, groupIds, elements, placed, used, loose, limits);
        for (Map.Entry<String, JsonObject> element : elements.entrySet()) if (!placed.contains(element.getKey())) loose.elements().add(element.getValue());
        if (!loose.elements().isEmpty()) groups.addFirst(loose);
        if (groups.isEmpty()) throw new IllegalArgumentException("no groups or cubes");
        if (groups.size() > 64) throw new IllegalArgumentException("more than 64 groups; a rig has at most 64 bones");
        if (!loose.elements().isEmpty())
            for (int i = 1; i < groups.size(); i++) { Group group = groups.get(i); groups.set(i, new Group(group.id(), group.name(), group.parent() < 0 ? -1 : group.parent() + 1, group.origin(), group.rotation(), group.elements(), group.depth())); }

        Map<String, String> models = new TreeMap<>();
        Map<String, byte[]> usedTextures = new TreeMap<>();
        JsonArray bones = new JsonArray();
        for (Group group : groups) {
            JsonObject bone = new JsonObject();
            bone.addProperty("id", group.id());
            if (group.parent() >= 0) bone.addProperty("parent", groups.get(group.parent()).id());
            bone.add("pivot", vector(group.origin()[0] / 16, group.origin()[1] / 16, group.origin()[2] / 16));
            if (group.rotation()[0] != 0 || group.rotation()[1] != 0 || group.rotation()[2] != 0) bone.add("rotation", vector(group.rotation()[0], group.rotation()[1], group.rotation()[2]));
            if (!group.elements().isEmpty()) {
                double factor = factor(group);
                models.put(group.id(), itemModel(namespace, model, group, factor, textures, textureIndex, usedTextures, problems));
                JsonObject visual = new JsonObject();
                visual.addProperty("type", "item");
                JsonObject item = new JsonObject();
                item.addProperty("id", CARRIER);
                item.addProperty("components", "[item_model=\"" + DISPATCH + "\",custom_model_data={strings:[\"" + namespace + ":" + model + "/" + group.id() + "\"]}]");
                visual.add("item", item);
                // Item displays turn their model half a turn about Y; this turns it back so the bone faces as modelled.
                visual.add("rotation", vector(0, 180, 0));
                double scale = 1 / factor;
                if (scale != 1) visual.add("scale", vector(scale, scale, scale));
                bone.add("visual", visual);
            }
            bones.add(bone);
        }

        Map<String, String> animations = animations(root, namespace, model, groupIds, limits, problems);
        JsonObject rig = new JsonObject();
        rig.addProperty("schemaVersion", 1);
        rig.addProperty("revision", 1);
        rig.addProperty("id", namespace + ":" + model);
        rig.addProperty("units", "blocks");
        rig.addProperty("body", "hidden");
        rig.add("bones", bones);
        if (!animations.isEmpty()) {
            JsonObject named = new JsonObject();
            for (String name : animations.keySet()) named.addProperty(name, namespace + ":" + model + "/" + name);
            rig.add("animations", named);
        }
        return new Output(namespace, model, models, usedTextures, JsonDocuments.write(rig), animations, problems);
    }

    private static void walk(JsonElement node, int parent, int depth, List<Group> groups, Map<String, String> groupIds, Map<String, JsonObject> elements,
                             Set<String> placed, Set<String> used, Group loose, RigPackBuilder.Limits limits) {
        if (string(node)) {
            JsonObject cube = elements.get(node.getAsString());
            if (cube == null) return;
            placed.add(node.getAsString());
            (parent < 0 ? loose : groups.get(parent)).elements().add(cube);
            return;
        }
        if (!node.isJsonObject()) return;
        if (depth > 16) throw new IllegalArgumentException("groups nest deeper than 16");
        if (groups.size() >= 64) throw new IllegalArgumentException("more than 64 groups; a rig has at most 64 bones");
        JsonObject group = node.getAsJsonObject();
        String name = string(group.get("name")) ? group.get("name").getAsString() : "bone";
        String id = unique(name, used);
        groups.add(new Group(id, name, parent, triple(group.get("origin")), triple(group.get("rotation")), new ArrayList<>(), depth));
        if (string(group.get("uuid"))) groupIds.put(group.get("uuid").getAsString(), id);
        int index = groups.size() - 1;
        if (group.has("children") && group.get("children").isJsonArray())
            for (JsonElement child : group.getAsJsonArray("children")) walk(child, index, depth + 1, groups, groupIds, elements, placed, used, loose, limits);
    }

    /** Shrinks a bone's cubes so they stay within 24 pixels of the model centre, the item model bounds. */
    private static double factor(Group group) {
        double reach = 0;
        for (JsonObject cube : group.elements()) {
            double inflate = number(cube.get("inflate"), 0);
            double[] from = triple(cube.get("from")), to = triple(cube.get("to"));
            for (int axis = 0; axis < 3; axis++) {
                reach = Math.max(reach, Math.abs(from[axis] - inflate - group.origin()[axis]));
                reach = Math.max(reach, Math.abs(to[axis] + inflate - group.origin()[axis]));
            }
        }
        return reach <= MODEL_REACH ? 1 : MODEL_REACH / reach;
    }

    private static String itemModel(String namespace, String model, Group group, double factor, List<Texture> textures, Map<String, Integer> textureIndex,
                                    Map<String, byte[]> usedTextures, List<String> problems) {
        JsonArray cubes = new JsonArray();
        Map<String, String> references = new TreeMap<>();
        for (JsonObject cube : group.elements()) {
            String name = string(cube.get("name")) ? cube.get("name").getAsString() : "cube";
            double inflate = number(cube.get("inflate"), 0);
            double[] from = triple(cube.get("from")), to = triple(cube.get("to")), origin = triple(cube.get("origin")), rotation = triple(cube.get("rotation"));
            JsonObject element = new JsonObject();
            element.add("from", place(new double[]{from[0] - inflate, from[1] - inflate, from[2] - inflate}, group.origin(), factor));
            element.add("to", place(new double[]{to[0] + inflate, to[1] + inflate, to[2] + inflate}, group.origin(), factor));
            int axes = (rotation[0] != 0 ? 1 : 0) + (rotation[1] != 0 ? 1 : 0) + (rotation[2] != 0 ? 1 : 0);
            if (axes == 1) {
                int axis = rotation[0] != 0 ? 0 : rotation[1] != 0 ? 1 : 2;
                if (ANGLES.contains(rotation[axis])) {
                    JsonObject turn = new JsonObject();
                    turn.add("origin", place(origin, group.origin(), factor));
                    turn.addProperty("axis", new String[]{"x", "y", "z"}[axis]);
                    turn.addProperty("angle", rotation[axis]);
                    element.add("rotation", turn);
                } else axes = 2;
            }
            if (axes > 1) problems.add("cube " + name + " in " + group.name() + ": rotation " + java.util.Arrays.toString(rotation)
                    + " dropped; item models turn a cube about one axis in 22.5 degree steps up to 45, so put free rotations on a group");
            JsonObject faces = new JsonObject();
            JsonObject source = cube.has("faces") && cube.get("faces").isJsonObject() ? cube.getAsJsonObject("faces") : new JsonObject();
            for (String side : FACES) {
                if (!source.has(side) || !source.get(side).isJsonObject()) continue;
                JsonObject face = source.getAsJsonObject(side);
                JsonElement reference = face.get("texture");
                if (reference == null || reference.isJsonNull()) continue;
                Integer index = textureIndex.get(reference.isJsonPrimitive() ? reference.getAsJsonPrimitive().getAsString().replaceFirst("\\.0$", "") : "");
                if (index == null || index >= textures.size()) { problems.add("cube " + name + " " + side + ": unknown texture " + reference); continue; }
                Texture texture = textures.get(index);
                references.put(texture.key(), namespace + ":rig/" + model + "/" + texture.name());
                usedTextures.put(texture.name(), texture.png());
                JsonObject out = new JsonObject();
                double[] uv = face.has("uv") && face.get("uv").isJsonArray() && face.getAsJsonArray("uv").size() == 4
                        ? new double[]{face.getAsJsonArray("uv").get(0).getAsDouble(), face.getAsJsonArray("uv").get(1).getAsDouble(), face.getAsJsonArray("uv").get(2).getAsDouble(), face.getAsJsonArray("uv").get(3).getAsDouble()}
                        : new double[]{0, 0, texture.uvWidth(), texture.uvHeight()};
                JsonArray scaled = new JsonArray();
                scaled.add(round(uv[0] * 16 / texture.uvWidth())); scaled.add(round(uv[1] * 16 / texture.uvHeight()));
                scaled.add(round(uv[2] * 16 / texture.uvWidth())); scaled.add(round(uv[3] * 16 / texture.uvHeight()));
                out.add("uv", scaled);
                out.addProperty("texture", "#" + texture.key());
                int turn = (int) number(face.get("rotation"), 0);
                if (turn == 90 || turn == 180 || turn == 270) out.addProperty("rotation", turn);
                faces.add(side, out);
            }
            element.add("faces", faces);
            cubes.add(element);
        }
        JsonObject result = new JsonObject();
        JsonObject textureMap = new JsonObject();
        references.forEach(textureMap::addProperty);
        if (!references.isEmpty()) textureMap.addProperty("particle", references.values().iterator().next());
        result.add("textures", textureMap);
        result.add("elements", cubes);
        return JsonDocuments.write(result);
    }

    private static JsonArray place(double[] point, double[] pivot, double factor) {
        JsonArray result = new JsonArray();
        for (int axis = 0; axis < 3; axis++) result.add(round((point[axis] - pivot[axis]) * factor + 8));
        return result;
    }

    private static List<Texture> textures(JsonObject root, double width, double height, RigPackBuilder.Limits limits) {
        List<Texture> textures = new ArrayList<>();
        if (!root.has("textures") || !root.get("textures").isJsonArray()) return textures;
        JsonArray list = root.getAsJsonArray("textures");
        if (list.size() > limits.maximumTextures()) throw new IllegalArgumentException("more than " + limits.maximumTextures() + " textures");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonObject texture = list.get(i).getAsJsonObject();
            String label = string(texture.get("name")) ? texture.get("name").getAsString() : "texture" + i;
            String source = string(texture.get("source")) ? texture.get("source").getAsString() : "";
            String prefix = "data:image/png;base64,";
            if (!source.startsWith(prefix))
                throw new IllegalArgumentException("texture " + label + " is not embedded; save the model with its textures embedded (files and network addresses are never read)");
            String encoded = source.substring(prefix.length());
            if (encoded.length() > (long) limits.maximumTextureBytes() * 4 / 3 + 8) throw new IllegalArgumentException("texture " + label + " exceeds " + limits.maximumTextureBytes() + " bytes");
            byte[] png;
            try { png = Base64.getDecoder().decode(encoded.trim()); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("texture " + label + " is not valid base64"); }
            int[] size = pngSize(png, label);
            if (size[0] > limits.maximumTextureSide() || size[1] > limits.maximumTextureSide())
                throw new IllegalArgumentException("texture " + label + " is larger than " + limits.maximumTextureSide() + " pixels");
            String name = unique(label.replaceFirst("(?i)\\.png$", ""), names);
            double uvWidth = positive(texture.get("uv_width"), width), uvHeight = positive(texture.get("uv_height"), height);
            textures.add(new Texture("t" + i, name, uvWidth, uvHeight, png));
        }
        return textures;
    }

    private static int[] pngSize(byte[] png, String label) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (png.length < 24) throw new IllegalArgumentException("texture " + label + " is not a PNG");
        for (int i = 0; i < 8; i++) if (png[i] != signature[i]) throw new IllegalArgumentException("texture " + label + " is not a PNG");
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') throw new IllegalArgumentException("texture " + label + " is not a PNG");
        int width = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int height = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("texture " + label + " has no pixels");
        return new int[]{width, height};
    }

    private static Map<String, String> animations(JsonObject root, String namespace, String model, Map<String, String> groupIds,
                                                  RigPackBuilder.Limits limits, List<String> problems) {
        Map<String, String> result = new TreeMap<>();
        if (!root.has("animations") || !root.get("animations").isJsonArray()) return result;
        JsonArray list = root.getAsJsonArray("animations");
        if (list.size() > limits.maximumAnimations()) { problems.add("only the first " + limits.maximumAnimations() + " animations were read"); }
        Set<String> names = new HashSet<>();
        for (int a = 0; a < Math.min(list.size(), limits.maximumAnimations()); a++) {
            if (!list.get(a).isJsonObject()) continue;
            JsonObject animation = list.get(a).getAsJsonObject();
            String label = string(animation.get("name")) ? animation.get("name").getAsString() : "animation" + a;
            String name = unique(label.replaceFirst("^animation\\.[^.]*\\.", ""), names);
            int duration = (int) Math.max(1, Math.min(1200, Math.round(number(animation.get("length"), 1) * 20)));
            String loop = string(animation.get("loop")) ? animation.get("loop").getAsString() : "once";
            JsonArray tracks = new JsonArray();
            JsonObject animators = animation.has("animators") && animation.get("animators").isJsonObject() ? animation.getAsJsonObject("animators") : new JsonObject();
            for (Map.Entry<String, JsonElement> animator : animators.entrySet()) {
                String bone = groupIds.get(animator.getKey());
                if (bone == null || !animator.getValue().isJsonObject()) continue;
                JsonObject source = animator.getValue().getAsJsonObject();
                if (source.has("type") && string(source.get("type")) && !source.get("type").getAsString().equals("bone")) continue;
                JsonArray keyframes = source.has("keyframes") && source.get("keyframes").isJsonArray() ? source.getAsJsonArray("keyframes") : new JsonArray();
                for (String channel : List.of("rotation", "position", "scale")) {
                    TreeMap<Double, JsonObject> ordered = new TreeMap<>();
                    for (JsonElement entry : keyframes) {
                        if (!entry.isJsonObject()) continue;
                        JsonObject keyframe = entry.getAsJsonObject();
                        if (!string(keyframe.get("channel")) || !keyframe.get("channel").getAsString().equals(channel)) continue;
                        double tick = Math.max(0, Math.min(duration, Math.round(number(keyframe.get("time"), 0) * 2000) / 100.0));
                        JsonArray value = value(keyframe, channel.equals("position") ? 1 / 16.0 : 1, label + " " + bone + " " + channel, problems);
                        if (value == null) continue;
                        JsonObject out = new JsonObject();
                        out.addProperty("tick", tick);
                        out.add("value", value);
                        String easing = easing(keyframe);
                        if (!easing.equals("linear")) out.addProperty("easing", easing);
                        ordered.put(tick, out);
                    }
                    if (ordered.isEmpty()) continue;
                    if (tracks.size() >= 64) { problems.add(label + ": only the first 64 tracks were kept"); break; }
                    JsonObject track = new JsonObject();
                    track.addProperty("bone", bone);
                    track.addProperty("target", channel.equals("position") ? "translation" : channel);
                    JsonArray frames = new JsonArray();
                    ordered.values().stream().limit(256).forEach(frames::add);
                    track.add("keyframes", frames);
                    tracks.add(track);
                }
            }
            JsonObject document = new JsonObject();
            document.addProperty("schemaVersion", 1);
            document.addProperty("revision", 1);
            document.addProperty("id", namespace + ":" + model + "/" + name);
            document.addProperty("rig", namespace + ":" + model);
            document.addProperty("durationTicks", duration);
            document.addProperty("loop", Set.of("once", "loop", "hold").contains(loop) ? loop : "once");
            document.add("tracks", tracks);
            result.put(name, JsonDocuments.write(document));
        }
        return result;
    }

    /** The first data point as numbers; Molang expressions cannot be evaluated here and are reported. */
    private static JsonArray value(JsonObject keyframe, double scale, String label, List<String> problems) {
        JsonElement points = keyframe.get("data_points");
        if (points == null || !points.isJsonArray() || points.getAsJsonArray().isEmpty() || !points.getAsJsonArray().get(0).isJsonObject()) return null;
        JsonObject point = points.getAsJsonArray().get(0).getAsJsonObject();
        JsonArray result = new JsonArray();
        for (String axis : List.of("x", "y", "z")) {
            JsonElement raw = point.get(axis);
            double number;
            try { number = raw == null ? 0 : raw.getAsJsonPrimitive().isNumber() ? raw.getAsDouble() : raw.getAsString().isBlank() ? 0 : Double.parseDouble(raw.getAsString().trim()); }
            catch (NumberFormatException | IllegalStateException | UnsupportedOperationException expression) {
                problems.add(label + ": keyframe value " + raw + " is an expression, which a rig cannot run; the keyframe was skipped");
                return null;
            }
            if (!Double.isFinite(number) || Math.abs(number * scale) > 4096) { problems.add(label + ": keyframe value out of range; skipped"); return null; }
            result.add(round(number * scale));
        }
        return result;
    }

    private static String easing(JsonObject keyframe) {
        String interpolation = string(keyframe.get("interpolation")) ? keyframe.get("interpolation").getAsString() : "linear";
        return switch (interpolation) {
            case "step" -> "hold";
            case "catmullrom" -> "ease_in_out";
            default -> "linear";
        };
    }

    private static String unique(String label, Set<String> used) {
        String base = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^[_-]+", "");
        if (base.isEmpty()) base = "part";
        if (base.length() > 60) base = base.substring(0, 60);
        String candidate = base;
        for (int i = 2; !used.add(candidate); i++) candidate = base + "_" + i;
        return candidate;
    }
    private static double[] triple(JsonElement element) {
        double[] result = new double[3];
        if (element == null || !element.isJsonArray()) return result;
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < Math.min(3, array.size()); i++) result[i] = number(array.get(i), 0);
        return result;
    }
    private static double number(JsonElement element, double fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            double value = primitive.isNumber() ? primitive.getAsDouble() : Double.parseDouble(primitive.getAsString().trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException invalid) { return fallback; }
    }
    private static double positive(JsonElement element, double fallback) {
        double value = number(element, fallback);
        return value > 0 ? value : fallback;
    }
    private static boolean string(JsonElement element) { return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(); }
    private static JsonArray vector(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(round(x)); array.add(round(y)); array.add(round(z));
        return array;
    }
    private static double round(double value) {
        double rounded = Math.round(value * 1_000_000d) / 1_000_000d;
        return rounded == 0 ? 0 : rounded;
    }
}
