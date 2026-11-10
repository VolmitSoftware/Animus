import java.lang.reflect.*;
import java.util.*;

/** Dumps vanilla entity model layers: cube geometry from the layer definitions and each part's pose after the model's
 *  setupAnim with a default render state (an idle entity at tick 0). Args: LAYER=fully.qualified.ModelClass ... */
public class DumpEntityLayers {
    static Object get(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException e) { }
        }
        throw new NoSuchFieldException(name);
    }
    static String num(double v) {
        if (Math.abs(v) < 1e-6) return "0";
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
        return Float.toString((float) v);
    }
    static String vec(Object v) throws Exception {
        return num((float) v.getClass().getMethod("x").invoke(v)) + "," + num((float) v.getClass().getMethod("y").invoke(v)) + "," + num((float) v.getClass().getMethod("z").invoke(v));
    }

    public static void main(String[] args) throws Exception {
        Map<?, ?> roots = (Map<?, ?>) Class.forName("net.minecraft.client.model.geom.LayerDefinitions").getMethod("createRoots").invoke(null);
        Class<?> layers = Class.forName("net.minecraft.client.model.geom.ModelLayers");
        Class<?> modelPart = Class.forName("net.minecraft.client.model.geom.ModelPart");
        StringBuilder out = new StringBuilder("{\n");
        boolean first = true;
        for (String arg : args) {
            String[] kv = arg.split("=", 2);
            String[] head = kv[0].split("@", 2);
            String[] spec = kv.length > 1 ? kv[1].split(";") : new String[] {""};
            String modelName = spec[0];
            Object location = null;
            if (head[0].contains(":")) {
                for (Object key : roots.keySet()) if (key.toString().equals(head[0])) location = key;
            } else location = layers.getField(head[0]).get(null);
            Object definition = location == null ? null : roots.get(location);
            if (definition == null) throw new IllegalStateException("no layer " + kv[0]);
            Object mesh = get(definition, "mesh"), material = get(definition, "material");
            Object rootDef = mesh.getClass().getMethod("getRoot").invoke(mesh);
            Object baked = definition.getClass().getMethod("bakeRoot").invoke(definition);
            String posed = "rest";
            if (!modelName.isEmpty()) {
                try {
                    Class<?> model = Class.forName(modelName);
                    Object instance = model.getConstructor(modelPart).newInstance(baked);
                    Method setup = null;
                    Class<?> stateType = null;
                    Class<?> renderState = Class.forName("net.minecraft.client.renderer.entity.state.EntityRenderState");
                    for (Class<?> c = model; c != null; c = c.getSuperclass())
                        for (Method m : c.getDeclaredMethods()) {
                            if (m.getName().equals("setupAnim") && m.getParameterCount() == 1 && setup == null) setup = m;
                            for (Class<?> type : m.getParameterTypes())
                                if (renderState.isAssignableFrom(type) && (stateType == null || stateType.isAssignableFrom(type))) stateType = type;
                        }
                    Object state = stateType.getConstructor().newInstance();
                    for (int i = 1; i < spec.length; i++) assign(state, spec[i]);
                    setup.setAccessible(true);
                    setup.invoke(instance, state);
                    posed = stateType.getSimpleName();
                } catch (Throwable failure) {
                    Throwable cause = failure instanceof InvocationTargetException ite ? ite.getCause() : failure;
                    System.err.println(kv[0] + ": rest pose (" + cause + ")");
                    if (head.length > 1) throw new IllegalStateException(kv[0], cause);
                    baked = definition.getClass().getMethod("bakeRoot").invoke(definition);
                }
            }
            if (!first) out.append(",\n");
            first = false;
            out.append("\"").append(location).append(head.length > 1 ? "@" + head[1] : "").append("\":{\"posed\":\"").append(posed).append("\",\"w\":").append(get(material, "xTexSize")).append(",\"h\":").append(get(material, "yTexSize")).append(",\"root\":");
            part(out, rootDef, baked);
            out.append("}");
        }
        out.append("\n}\n");
        System.out.print(out);
    }

    /** field=value on a render state: a boolean, a number, an enum constant, or start@TICK for an AnimationState. */
    static void assign(Object state, String text) throws Exception {
        String[] kv = text.split("=", 2);
        Field field = null;
        for (Class<?> c = state.getClass(); c != null && field == null; c = c.getSuperclass()) {
            try { field = c.getDeclaredField(kv[0]); } catch (NoSuchFieldException e) { }
        }
        if (field == null) throw new NoSuchFieldException(kv[0] + " on " + state.getClass());
        field.setAccessible(true);
        Class<?> type = field.getType();
        String value = kv[1];
        if (value.startsWith("start@")) {
            Object animation = field.get(state);
            animation.getClass().getMethod("start", int.class).invoke(animation, Integer.parseInt(value.substring(6)));
        } else if (type == boolean.class) field.setBoolean(state, Boolean.parseBoolean(value));
        else if (type == float.class) field.setFloat(state, Float.parseFloat(value));
        else if (type == int.class) field.setInt(state, Integer.parseInt(value));
        else if (type.isEnum()) field.set(state, Enum.valueOf((Class) type, value));
        else throw new IllegalArgumentException(kv[0] + " is " + type);
    }

    static void part(StringBuilder out, Object def, Object baked) throws Exception {
        out.append("{\"pose\":[");
        String[] keys = {"x", "y", "z", "xRot", "yRot", "zRot", "xScale", "yScale", "zScale"};
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) out.append(',');
            out.append(num(((Number) get(baked, keys[i])).doubleValue()));
        }
        out.append("]");
        if (!(boolean) get(baked, "visible")) out.append(",\"hidden\":1");
        if ((boolean) get(baked, "skipDraw")) out.append(",\"skip\":1");
        out.append(",\"cubes\":[");
        List<?> cubes = (List<?>) get(def, "cubes");
        for (int i = 0; i < cubes.size(); i++) {
            Object c = cubes.get(i);
            if (i > 0) out.append(',');
            Object g = get(c, "grow"), tc = get(c, "texCoord"), ts = get(c, "texScale");
            Set<?> faces = (Set<?>) get(c, "visibleFaces");
            out.append("{\"o\":[").append(vec(get(c, "origin"))).append("],\"s\":[").append(vec(get(c, "dimensions")))
               .append("],\"uv\":[").append(num((float) tc.getClass().getMethod("u").invoke(tc))).append(',').append(num((float) tc.getClass().getMethod("v").invoke(tc))).append("]");
            double gx = ((Number) get(g, "growX")).doubleValue(), gy = ((Number) get(g, "growY")).doubleValue(), gz = ((Number) get(g, "growZ")).doubleValue();
            if (gx != 0 || gy != 0 || gz != 0) out.append(",\"g\":[").append(num(gx)).append(',').append(num(gy)).append(',').append(num(gz)).append("]");
            float tsu = (float) ts.getClass().getMethod("u").invoke(ts), tsv = (float) ts.getClass().getMethod("v").invoke(ts);
            if (tsu != 1 || tsv != 1) out.append(",\"ts\":[").append(num(tsu)).append(',').append(num(tsv)).append("]");
            if ((boolean) get(c, "mirror")) out.append(",\"m\":1");
            if (faces.size() != 6) {
                List<String> names = new ArrayList<>();
                for (Object f : faces) names.add("\"" + f.toString().toLowerCase(Locale.ROOT) + "\"");
                Collections.sort(names);
                out.append(",\"f\":[").append(String.join(",", names)).append("]");
            }
            out.append('}');
        }
        out.append("],\"children\":{");
        Map<?, ?> children = (Map<?, ?>) get(def, "children");
        Map<?, ?> bakedChildren = (Map<?, ?>) get(baked, "children");
        TreeMap<String, Object> kids = new TreeMap<>();
        for (Map.Entry<?, ?> e : children.entrySet()) kids.put((String) e.getKey(), e.getValue());
        boolean first = true;
        for (Map.Entry<String, Object> e : kids.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(e.getKey()).append("\":");
            part(out, e.getValue(), bakedChildren.get(e.getKey()));
        }
        out.append("}}");
    }
}
