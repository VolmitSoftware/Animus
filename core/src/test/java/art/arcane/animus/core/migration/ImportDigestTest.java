package art.arcane.animus.core.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImportDigestTest {
    @TempDir Path root;

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void hashesEveryFileByPathAndContentInPathOrder() throws Exception {
        Files.createDirectories(root.resolve("npcs/world"));
        Files.writeString(root.resolve("report.json"), "{}\n");
        Files.writeString(root.resolve("npcs/world/citizens-1.json"), "é\n");
        String expected = sha("npcs/world/citizens-1.json\n" + sha("é\n") + "\nreport.json\n" + sha("{}\n") + "\n");
        assertEquals(expected, ImportDigest.outputHash(root));
        Files.writeString(root.resolve("report.json"), "{ }\n");
        assertNotEquals(expected, ImportDigest.outputHash(root));
    }

    @Test void refusesAMissingDirectory() {
        assertThrows(IOException.class, () -> ImportDigest.outputHash(root.resolve("missing")));
    }
}
