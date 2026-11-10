package art.arcane.animus.bukkit.integration.typewriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * Installs the Animus extension into {@code plugins/Typewriter/extensions/}. Typewriter refuses an extension built for
 * another engine version, so the jar is assembled here: the extension classes Animus carries plus an extension.json that
 * names the installed Typewriter and Animus versions. Runs before Typewriter enables, so it loads on the same start.
 */
public final class TypewriterExtension {
    public static final String FILE = "AnimusExtension.jar";
    static final String CLASSES = "integrations/typewriter/animus-typewriter-extension.jar", DESCRIPTOR = "integrations/typewriter/extension.json";
    private static final long FIXED_TIME = 315532800000L;

    private TypewriterExtension() {}

    /** Writes the extension when it is missing or differs; the reply says what changed, or empty when it was current. */
    public static Optional<String> install(Path typewriterFolder, String typewriterVersion, String animusVersion, ClassLoader resources) throws IOException {
        byte[] classes, descriptor;
        try (InputStream jar = open(resources, CLASSES); InputStream json = open(resources, DESCRIPTOR)) { classes = jar.readAllBytes(); descriptor = json.readAllBytes(); }
        byte[] built = build(classes, new String(descriptor, StandardCharsets.UTF_8), typewriterVersion, animusVersion);
        Path target = typewriterFolder.resolve("extensions").resolve(FILE);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), built)) return Optional.empty();
        boolean replaced = Files.exists(target);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".animus-", ".tmp");
        try {
            Files.write(temporary, built);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return Optional.of((replaced ? "Updated " : "Installed ") + "the Animus extension for Typewriter " + typewriterVersion);
    }

    /** The extension jar: every class entry of {@code classes} and the descriptor with both versions filled in. Same input, same bytes. */
    static byte[] build(byte[] classes, String descriptor, String typewriterVersion, String animusVersion) throws IOException {
        Objects.requireNonNull(typewriterVersion); Objects.requireNonNull(animusVersion);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes); JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(classes))) {
            for (JarEntry entry = in.getNextJarEntry(); entry != null; entry = in.getNextJarEntry()) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                put(out, entry.getName(), in.readAllBytes());
            }
            put(out, "extension.json", descriptor.replace("@engineVersion@", typewriterVersion).replace("@version@", animusVersion).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void put(JarOutputStream out, String name, byte[] content) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(FIXED_TIME);
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    private static InputStream open(ClassLoader resources, String path) throws IOException {
        InputStream stream = resources.getResourceAsStream(path);
        if (stream == null) throw new IOException("The Animus jar is missing " + path);
        return stream;
    }
}
