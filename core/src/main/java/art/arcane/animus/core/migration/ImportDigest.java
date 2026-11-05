package art.arcane.animus.core.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One hash over a staged Citizens import ({@code imports/<importId>/}), so the browser converter and this converter can
 * be compared without showing any record. It is the SHA-256 of, for every file sorted by its {@code /} path relative to
 * the staged directory, {@code path + "\n" + sha256(bytes) + "\n"}, in lowercase hex. No file is left out.
 */
public final class ImportDigest {
    private ImportDigest() {}

    public static String outputHash(Path stagedDirectory) throws IOException {
        if (!Files.isDirectory(stagedDirectory)) throw new IOException("Not a staged import directory: " + stagedDirectory);
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(stagedDirectory)) {
            List<Path> regular = paths.filter(Files::isRegularFile).toList();
            for (Path path : regular) {
                String relative = stagedDirectory.relativize(path).toString().replace('\\', '/');
                files.put(relative, hex(Files.readAllBytes(path)));
            }
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> file : files.entrySet()) canonical.append(file.getKey()).append('\n').append(file.getValue()).append('\n');
        return hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
