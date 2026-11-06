package art.arcane.animus.bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin compiles against one Spigot API, but runs on Spigot and Paper from 26.1.2 to 26.3, whose APIs differ:
 * Paper has no {@code Material.getKeyOrThrow()}, for one. Every server class, method and field the compiled plugin
 * names is resolved here against each target's API jar, the way the JVM links it, so a call missing on any target
 * fails the build instead of throwing {@code NoSuchMethodError} on a live server.
 */
class ServerApiLinkageTest {
    private static final List<String> SERVER_PACKAGES = List.of("org/bukkit/", "org/spigotmc/", "net/md_5/", "io/papermc/", "com/destroystokyo/");

    @Test
    void everyServerMemberThePluginNamesExistsOnEveryTarget() throws IOException {
        Map<String, List<Path>> targets = targets();
        assertTrue(targets.keySet().stream().anyMatch((String name) -> name.startsWith("paper-")), "no Paper API targets: " + targets.keySet());
        assertTrue(targets.keySet().stream().anyMatch((String name) -> name.startsWith("spigot-")), "no Spigot API targets: " + targets.keySet());
        Path plugin = Path.of(Objects.requireNonNull(System.getProperty("animus.pluginJar"), "animus.pluginJar is not set; run through Gradle"));
        List<Reference> references = references(plugin);
        assertFalse(references.isEmpty(), "no server references found in " + plugin);
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, List<Path>> target : targets.entrySet()) {
            List<Path> classpath = new ArrayList<>(List.of(plugin));
            classpath.addAll(target.getValue());
            try (Classes classes = new Classes(classpath)) {
                for (Reference reference : references) {
                    if (!classes.links(reference)) missing.add(target.getKey() + ": " + reference.key() + " (used by " + reference.user() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "Server API missing on a supported target:\n" + String.join("\n", missing));
    }

    @Test
    void theResolverCatchesTheSpigotOnlyMaterialKeyCall() throws IOException {
        Map<String, List<Path>> targets = targets();
        Reference spigotOnly = new Reference("org/bukkit/Material", "getKeyOrThrow", "()Lorg/bukkit/NamespacedKey;", Kind.METHOD, "test");
        Reference shared = new Reference("org/bukkit/Material", "getKey", "()Lorg/bukkit/NamespacedKey;", Kind.METHOD, "test");
        for (Map.Entry<String, List<Path>> target : targets.entrySet()) {
            try (Classes classes = new Classes(target.getValue())) {
                assertTrue(classes.links(shared), target.getKey() + " lacks Material.getKey()");
                if (target.getKey().startsWith("paper-")) assertFalse(classes.links(spigotOnly), target.getKey() + " unexpectedly has Material.getKeyOrThrow()");
            }
        }
    }

    enum Kind { CLASS, METHOD, FIELD }

    record Reference(String owner, String name, String descriptor, Kind kind, String user) {
        String key() { return kind == Kind.CLASS ? owner : owner + "." + name + descriptor; }
    }

    /** Target name to API classpath, from {@code animus.apiTargets}: {@code name=path:path;name=path}. */
    private static Map<String, List<Path>> targets() {
        String property = System.getProperty("animus.apiTargets");
        assertTrue(property != null && !property.isBlank(), "animus.apiTargets is not set; run through Gradle");
        Map<String, List<Path>> targets = new LinkedHashMap<>();
        for (String entry : property.split(";")) {
            int split = entry.indexOf('=');
            targets.put(entry.substring(0, split), paths(entry.substring(split + 1)));
        }
        return targets;
    }

    private static List<Path> paths(String joined) {
        assertTrue(joined != null && !joined.isBlank(), "missing classpath property");
        return Stream.of(joined.split(java.io.File.pathSeparator)).filter((String part) -> !part.isBlank()).map(Path::of).toList();
    }

    private static boolean server(String owner) {
        for (String prefix : SERVER_PACKAGES) if (owner.startsWith(prefix)) return true;
        return false;
    }

    /** Server classes, methods and fields named by the shipped jar's classes, plus members it reaches through its own subclasses. */
    private static List<Reference> references(Path jar) throws IOException {
        Map<String, Reference> found = new LinkedHashMap<>();
        Set<String> own = new TreeSet<>();
        List<ClassModel> models = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/") || entry.getName().endsWith("module-info.class")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassModel model = ClassFile.of().parse(in.readAllBytes());
                    models.add(model);
                    own.add(model.thisClass().asInternalName());
                }
            }
        }
        for (ClassModel model : models) {
            String user = model.thisClass().asInternalName();
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof MemberRefEntry member) {
                    String owner = member.owner().asInternalName();
                    if (owner.startsWith("[")) continue;
                    if (!server(owner) && !own.contains(owner)) continue;
                    Kind kind = member instanceof FieldRefEntry ? Kind.FIELD : Kind.METHOD;
                    Reference reference = new Reference(owner, member.name().stringValue(), member.type().stringValue(), kind, user);
                    found.putIfAbsent(reference.key(), reference);
                } else if (entry instanceof ClassEntry type) {
                    String owner = type.asInternalName();
                    while (owner.startsWith("[")) owner = owner.substring(1);
                    if (owner.startsWith("L") && owner.endsWith(";")) owner = owner.substring(1, owner.length() - 1);
                    if (!server(owner)) continue;
                    Reference reference = new Reference(owner, "", "", Kind.CLASS, user);
                    found.putIfAbsent(reference.key(), reference);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Class files from jars and directories, falling back to the running JDK; resolves members as the JVM links them. */
    private static final class Classes implements AutoCloseable {
        private final List<Path> directories = new ArrayList<>();
        private final List<ZipFile> jars = new ArrayList<>();
        private final Map<String, Optional<ClassModel>> cache = new HashMap<>();

        Classes(List<Path> classpath) throws IOException {
            for (Path path : classpath) {
                if (Files.isDirectory(path)) directories.add(path);
                else if (Files.isRegularFile(path)) jars.add(new ZipFile(path.toFile()));
            }
        }

        Optional<ClassModel> find(String name) {
            return cache.computeIfAbsent(name, (String key) -> {
                try {
                    String file = key + ".class";
                    for (Path directory : directories) {
                        Path candidate = directory.resolve(file);
                        if (Files.isRegularFile(candidate)) return Optional.of(ClassFile.of().parse(Files.readAllBytes(candidate)));
                    }
                    for (ZipFile jar : jars) {
                        ZipEntry entry = jar.getEntry(file);
                        if (entry != null) try (InputStream in = jar.getInputStream(entry)) { return Optional.of(ClassFile.of().parse(in.readAllBytes())); }
                    }
                    if (server(key)) return Optional.empty();
                    try (InputStream in = ClassLoader.getSystemResourceAsStream(file)) {
                        return in == null ? Optional.empty() : Optional.of(ClassFile.of().parse(in.readAllBytes()));
                    }
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        }

        /**
         * True when the reference links. A server class must exist; a member must be found in its owner or a supertype.
         * When the search meets a non-server class that is not on the classpath, the reference is given the benefit of
         * the doubt: that class belongs to a library this test does not judge.
         */
        boolean links(Reference reference) {
            Optional<ClassModel> owner = find(reference.owner());
            if (owner.isEmpty()) return !server(reference.owner());
            if (reference.kind() == Kind.CLASS) return true;
            Lookup lookup = new Lookup();
            search(reference, owner.get(), lookup, new TreeSet<>());
            return lookup.found || lookup.unknown;
        }

        private static final class Lookup { boolean found, unknown; }

        private void search(Reference reference, ClassModel model, Lookup lookup, Set<String> seen) {
            if (lookup.found || !seen.add(model.thisClass().asInternalName())) return;
            if (reference.kind() == Kind.FIELD) {
                for (FieldModel field : model.fields())
                    if (field.fieldName().equalsString(reference.name()) && field.fieldType().equalsString(reference.descriptor())) { lookup.found = true; return; }
            } else {
                for (MethodModel method : model.methods()) {
                    if (!method.methodName().equalsString(reference.name())) continue;
                    if (method.methodType().equalsString(reference.descriptor()) || polymorphic(model, method)) { lookup.found = true; return; }
                }
            }
            List<String> supers = new ArrayList<>();
            model.superclass().ifPresent((ClassEntry parent) -> supers.add(parent.asInternalName()));
            for (ClassEntry face : model.interfaces()) supers.add(face.asInternalName());
            if (supers.isEmpty() && !model.thisClass().asInternalName().equals("java/lang/Object")) supers.add("java/lang/Object");
            for (String parent : supers) {
                Optional<ClassModel> found = find(parent);
                if (found.isEmpty()) { if (!server(parent)) lookup.unknown = true; continue; }
                search(reference, found.get(), lookup, seen);
            }
        }

        private static boolean polymorphic(ClassModel model, MethodModel method) {
            String owner = model.thisClass().asInternalName();
            return (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle"))
                    && (method.flags().flagsMask() & (ClassFile.ACC_NATIVE | ClassFile.ACC_VARARGS)) == (ClassFile.ACC_NATIVE | ClassFile.ACC_VARARGS);
        }

        @Override public void close() throws IOException { for (ZipFile jar : jars) jar.close(); }
    }
}
