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
 * default, minimum, maximum)) call, and the attribute supplier every living entity type is built from: the
 * create*Attributes method DefaultAttributes registers for it, unless BODIES names another, following its
 * create*Attributes calls up the class hierarchy, so no Minecraft code runs.
 */
public final class AttributeTableGenerator {
    private static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes.class";
    private static final String DEFAULTS = "net/minecraft/world/entity/ai/attributes/DefaultAttributes.class";

    record Attribute(String id, double fallback, double minimum, double maximum) {}

    /**
     * Body types whose supplier Animus takes from another method than DefaultAttributes registers, such as a horse's base
     * attributes without its random ones; a comma separates names the class has had.
     */
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
        BODIES.put("minecraft:sheep", new String[]{"net/minecraft/world/entity/animal/sheep/Sheep", "createAttributes"});
        BODIES.put("minecraft:wolf", new String[]{"net/minecraft/world/entity/animal/wolf/Wolf", "createAttributes"});
        BODIES.put("minecraft:cat", new String[]{"net/minecraft/world/entity/animal/feline/Cat", "createAttributes"});
        BODIES.put("minecraft:parrot", new String[]{"net/minecraft/world/entity/animal/parrot/Parrot", "createAttributes"});
        BODIES.put("minecraft:fox", new String[]{"net/minecraft/world/entity/animal/fox/Fox", "createAttributes"});
        BODIES.put("minecraft:axolotl", new String[]{"net/minecraft/world/entity/animal/axolotl/Axolotl", "createAttributes"});
        BODIES.put("minecraft:rabbit", new String[]{"net/minecraft/world/entity/animal/rabbit/Rabbit", "createAttributes"});
        BODIES.put("minecraft:villager", new String[]{"net/minecraft/world/entity/npc/villager/Villager", "createAttributes"});
        BODIES.put("minecraft:zombie_villager", new String[]{"net/minecraft/world/entity/monster/zombie/Zombie", "createAttributes"});
        BODIES.put("minecraft:creeper", new String[]{"net/minecraft/world/entity/monster/Creeper", "createAttributes"});
        BODIES.put("minecraft:slime", new String[]{"net/minecraft/world/entity/monster/Monster", "createMonsterAttributes"});
        BODIES.put("minecraft:magma_cube", new String[]{"net/minecraft/world/entity/monster/cubemob/MagmaCube,net/minecraft/world/entity/monster/MagmaCube", "createAttributes"});
        BODIES.put("minecraft:phantom", new String[]{"net/minecraft/world/entity/monster/Monster", "createMonsterAttributes"});
        BODIES.put("minecraft:mooshroom", new String[]{"net/minecraft/world/entity/animal/cow/Cow", "createAttributes"});
        BODIES.put("minecraft:panda", new String[]{"net/minecraft/world/entity/animal/panda/Panda", "createAttributes"});
        BODIES.put("minecraft:goat", new String[]{"net/minecraft/world/entity/animal/goat/Goat", "createAttributes"});
        BODIES.put("minecraft:pig", new String[]{"net/minecraft/world/entity/animal/pig/Pig", "createAttributes"});
        BODIES.put("minecraft:cow", new String[]{"net/minecraft/world/entity/animal/cow/Cow", "createAttributes"});
        BODIES.put("minecraft:chicken", new String[]{"net/minecraft/world/entity/animal/chicken/Chicken", "createAttributes"});
        BODIES.put("minecraft:shulker", new String[]{"net/minecraft/world/entity/monster/Shulker", "createAttributes"});
        BODIES.put("minecraft:pufferfish", new String[]{"net/minecraft/world/entity/animal/fish/AbstractFish", "createAttributes"});
        BODIES.put("minecraft:tropical_fish", new String[]{"net/minecraft/world/entity/animal/fish/AbstractFish", "createAttributes"});
        BODIES.put("minecraft:snow_golem", new String[]{"net/minecraft/world/entity/animal/golem/SnowGolem", "createAttributes"});
        BODIES.put("minecraft:polar_bear", new String[]{"net/minecraft/world/entity/animal/polarbear/PolarBear", "createAttributes"});
        BODIES.put("minecraft:piglin", new String[]{"net/minecraft/world/entity/monster/piglin/Piglin", "createAttributes"});
        BODIES.put("minecraft:allay", new String[]{"net/minecraft/world/entity/animal/allay/Allay", "createAttributes"});
        BODIES.put("minecraft:bee", new String[]{"net/minecraft/world/entity/animal/bee/Bee", "createAttributes"});
        BODIES.put("minecraft:vex", new String[]{"net/minecraft/world/entity/monster/Vex", "createAttributes"});
        BODIES.put("minecraft:evoker", new String[]{"net/minecraft/world/entity/monster/illager/Evoker", "createAttributes"});
        BODIES.put("minecraft:sniffer", new String[]{"net/minecraft/world/entity/animal/sniffer/Sniffer", "createAttributes"});
        BODIES.put("minecraft:bat", new String[]{"net/minecraft/world/entity/ambient/Bat", "createAttributes"});
        BODIES.put("minecraft:copper_golem", new String[]{"net/minecraft/world/entity/animal/golem/CopperGolem", "createAttributes"});
        BODIES.put("minecraft:donkey", new String[]{"net/minecraft/world/entity/animal/equine/AbstractChestedHorse", "createBaseChestedHorseAttributes"});
        BODIES.put("minecraft:mule", new String[]{"net/minecraft/world/entity/animal/equine/AbstractChestedHorse", "createBaseChestedHorseAttributes"});
        BODIES.put("minecraft:wither", new String[]{"net/minecraft/world/entity/boss/wither/WitherBoss", "createAttributes"});
        BODIES.put("minecraft:camel", new String[]{"net/minecraft/world/entity/animal/camel/Camel", "createAttributes"});
        BODIES.put("minecraft:enderman", new String[]{"net/minecraft/world/entity/monster/Enderman,net/minecraft/world/entity/monster/EnderMan", "createAttributes"});
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
                Map<String, String[]> suppliers = new TreeMap<>(defaults(zip));
                suppliers.putAll(BODIES);
                for (Map.Entry<String, String[]> body : suppliers.entrySet())
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
     * The supplier DefaultAttributes registers for each entity type: in its static initializer, an EntityType field load
     * followed by the static create*Attributes call whose builder is built and put for it.
     */
    private static Map<String, String[]> defaults(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(DEFAULTS);
        if (entry == null) throw new IOException("No " + DEFAULTS);
        ClassModel model;
        try (InputStream input = zip.getInputStream(entry)) { model = ClassFile.of().parse(input.readAllBytes()); }
        Map<String, String[]> found = new LinkedHashMap<>();
        for (MethodModel method : model.methods()) {
            if (!method.methodName().equalsString("<clinit>")) continue;
            String type = null;
            for (CodeElement element : method.code().orElseThrow()) {
                if (element instanceof java.lang.classfile.instruction.FieldInstruction field && field.opcode() == Opcode.GETSTATIC
                        && field.typeSymbol().descriptorString().equals("Lnet/minecraft/world/entity/EntityType;"))
                    type = "minecraft:" + field.name().stringValue().toLowerCase(Locale.ROOT);
                else if (type != null && element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESTATIC
                        && invoke.typeSymbol().descriptorString().equals("()" + BUILDER)) {
                    found.put(type, new String[]{invoke.owner().asInternalName(), invoke.name().stringValue()});
                    type = null;
                }
            }
        }
        if (found.isEmpty()) throw new IOException(DEFAULTS + " registers no attribute suppliers");
        return found;
    }

    /**
     * The attribute ids a static create*Attributes method adds: each Attributes field it loads, plus those of the
     * create*Attributes methods it calls, found on the named class or the first superclass declaring them.
     */
    private static java.util.Set<String> supplier(ZipFile zip, String owners, String name) throws IOException {
        java.util.Set<String> found = new java.util.TreeSet<>();
        String owner = owners.split(",")[0];
        for (String candidate : owners.split(",")) if (zip.getEntry(candidate + ".class") != null) { owner = candidate; break; }
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
