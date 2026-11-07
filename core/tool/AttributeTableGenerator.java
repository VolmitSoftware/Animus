import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ConstantDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes core/src/main/java/art/arcane/animus/core/npc/VanillaAttributes.java from the attribute registrations in each
 * target's server jar. Arguments: the output file, then version=jar pairs, oldest version first. It reads the static
 * initializer of net.minecraft.world.entity.ai.attributes.Attributes: every register(id, new RangedAttribute(name,
 * default, minimum, maximum)) call, and the attribute supplier each presented body type is built from, following its
 * create*Attributes calls up the class hierarchy, so no Minecraft code runs.
 */
public final class AttributeTableGenerator {
    private static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes.class";

    record Attribute(String id, double fallback, double minimum, double maximum) {}

    /** Each presented body type and the static method its vanilla attribute supplier comes from. */
    private static final Map<String, String[]> BODIES = new LinkedHashMap<>();
    static {
        BODIES.put("minecraft:player", new String[]{"net/minecraft/world/entity/player/Player", "createAttributes"});
        BODIES.put("minecraft:armor_stand", new String[]{"net/minecraft/world/entity/decoration/ArmorStand", "createAttributes"});
        BODIES.put("minecraft:horse", new String[]{"net/minecraft/world/entity/animal/equine/AbstractHorse", "createBaseHorseAttributes"});
        BODIES.put("minecraft:llama", new String[]{"net/minecraft/world/entity/animal/equine/Llama", "createAttributes"});
        BODIES.put("minecraft:turtle", new String[]{"net/minecraft/world/entity/animal/turtle/Turtle", "createAttributes"});
        BODIES.put("minecraft:frog", new String[]{"net/minecraft/world/entity/animal/frog/Frog", "createAttributes"});
        BODIES.put("minecraft:armadillo", new String[]{"net/minecraft/world/entity/animal/armadillo/Armadillo", "createAttributes"});
        BODIES.put("minecraft:ender_dragon", new String[]{"net/minecraft/world/entity/boss/enderdragon/EnderDragon", "createAttributes"});
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length < 2) throw new IllegalArgumentException("usage: <output.java> <version=server.jar>...");
        Map<String, Attribute> latest = new TreeMap<>();
        Map<String, String> since = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> carried = new LinkedHashMap<>();
        for (int i = 1; i < arguments.length; i++) {
            String[] pair = arguments[i].split("=", 2);
            for (Attribute attribute : read(Path.of(pair[1]))) {
                latest.put(attribute.id(), attribute);
                since.putIfAbsent(attribute.id(), pair[0]);
            }
            try (ZipFile zip = new ZipFile(Path.of(pair[1]).toFile())) {
                for (Map.Entry<String, String[]> body : BODIES.entrySet())
                    carried.computeIfAbsent(body.getKey(), (String key) -> new java.util.TreeSet<>()).addAll(supplier(zip, body.getValue()[0], body.getValue()[1]));
            }
        }
        String first = arguments[1].split("=", 2)[0];
        StringBuilder rows = new StringBuilder();
        for (Attribute attribute : latest.values()) {
            String added = since.get(attribute.id());
            rows.append("            new Attribute(\"").append(attribute.id()).append("\", ").append(number(attribute.fallback())).append(", ")
                    .append(number(attribute.minimum())).append(", ").append(number(attribute.maximum())).append(", \"")
                    .append(added.equals(first) ? "" : added).append("\"),\n");
        }
        rows.setLength(rows.length() - 2);
        StringBuilder bodies = new StringBuilder();
        for (Map.Entry<String, java.util.Set<String>> body : carried.entrySet()) {
            bodies.append("            Map.entry(\"").append(body.getKey()).append("\", Set.of(");
            bodies.append(String.join(", ", body.getValue().stream().map((String id) -> "\"" + id + "\"").toList())).append(")),\n");
        }
        bodies.setLength(bodies.length() - 2);
        String source = """
                package art.arcane.animus.core.npc;

                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                /**
                 * Every entity attribute the supported Minecraft versions register, with its vanilla default and range. Generated by
                 * {@code ./gradlew :core:generateAttributeTable} from the server jars; do not edit by hand.
                 */
                public final class VanillaAttributes {
                    /** {@code since} is the first supported version that registers the attribute, empty when every supported version does. */
                    public record Attribute(String id, double defaultValue, double minimum, double maximum, String since) {}

                    public static final List<Attribute> ALL = List.of(
                %s);

                    /** The attributes each presented body type carries in any supported version; viewers are sent only these. */
                    public static final Map<String, Set<String>> BODIES = Map.ofEntries(
                %s);

                    private VanillaAttributes() {}
                }
                """.formatted(rows, bodies);
        Files.writeString(Path.of(arguments[0]), source, StandardCharsets.UTF_8);
    }

    private static List<Attribute> read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ATTRIBUTES);
            if (entry == null) throw new IOException(jar + " has no " + ATTRIBUTES);
            byte[] bytes;
            try (InputStream input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
            ClassModel model = ClassFile.of().parse(bytes);
            for (MethodModel method : model.methods()) {
                if (!method.methodName().equalsString("<clinit>")) continue;
                CodeModel code = method.code().orElseThrow();
                return registrations(code);
            }
            throw new IOException(jar + ": Attributes has no static initializer");
        }
    }

    /**
     * The attribute ids a static create*Attributes method adds: each Attributes field it loads, plus those of the
     * create*Attributes methods it calls, found on the named class or the first superclass declaring them.
     */
    private static java.util.Set<String> supplier(ZipFile zip, String owner, String name) throws IOException {
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (String at = owner; at != null; ) {
            ZipEntry entry = zip.getEntry(at + ".class");
            if (entry == null) throw new IOException("No class " + at);
            ClassModel model;
            try (InputStream input = zip.getInputStream(entry)) { model = ClassFile.of().parse(input.readAllBytes()); }
            for (MethodModel method : model.methods()) {
                if (!method.methodName().equalsString(name) || !method.methodType().equalsString("()" + BUILDER)) continue;
                for (CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof java.lang.classfile.instruction.FieldInstruction field && field.opcode() == Opcode.GETSTATIC
                            && field.owner().asInternalName().equals("net/minecraft/world/entity/ai/attributes/Attributes"))
                        found.add(field.name().stringValue().toLowerCase(Locale.ROOT));
                    else if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESTATIC && invoke.typeSymbol().descriptorString().equals("()" + BUILDER))
                        found.addAll(supplier(zip, invoke.owner().asInternalName(), invoke.name().stringValue()));
                }
                return found;
            }
            at = model.superclass().map((java.lang.classfile.constantpool.ClassEntry parent) -> parent.asInternalName()).orElse(null);
        }
        throw new IOException("No " + owner + "." + name);
    }
    private static final String BUILDER = "Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder;";

    /** The constants loaded since the previous register call are the id, the translation key and three doubles. */
    private static List<Attribute> registrations(CodeModel code) throws IOException {
        List<Attribute> found = new ArrayList<>();
        List<ConstantDesc> constants = new ArrayList<>();
        for (CodeElement element : code) {
            if (element instanceof ConstantInstruction constant) constants.add(constant.constantValue());
            else if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESTATIC && invoke.name().equalsString("register")) {
                List<Double> doubles = new ArrayList<>();
                String id = null;
                for (ConstantDesc value : constants) {
                    if (id == null && value instanceof String text) id = text;
                    else if (value instanceof Double number) doubles.add(number);
                }
                if (id == null || doubles.size() != 3) throw new IOException("Unexpected registration shape near " + id + ": " + constants);
                found.add(new Attribute(id.toLowerCase(Locale.ROOT), doubles.get(0), doubles.get(1), doubles.get(2)));
                constants.clear();
            }
        }
        return found;
    }

    private static String number(double value) {
        if (value == Double.MAX_VALUE) return "Double.MAX_VALUE";
        String text = Double.toString(value);
        return text.contains("E") ? text.replace("E", "e") : text;
    }
}
