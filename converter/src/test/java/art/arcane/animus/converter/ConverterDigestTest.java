package art.arcane.animus.converter;

import art.arcane.animus.core.migration.ImportDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConverterDigestTest {
    @TempDir Path root;

    private static Path citizensFixtures() {
        Path current = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(current.resolve("settings.gradle"))) current = current.getParent();
        return current.resolve("schema/fixtures/citizens");
    }

    @Test void digestOfAPreviewMatchesTheCommittedGoldenTree() throws Exception {
        String preview = ConverterMain.run(new String[]{"preview", citizensFixtures().resolve("absent.yml").toString(), root.toString()});
        Matcher id = Pattern.compile("^Preview ([a-f0-9]{64}) ").matcher(preview);
        assertTrue(id.find(), preview);
        String digest = ConverterMain.run(new String[]{"digest", root.resolve("imports/" + id.group(1)).toString()});
        assertTrue(digest.matches("[a-f0-9]{64}"), digest);
        assertEquals(ImportDigest.outputHash(citizensFixtures().resolve("expected/absent")), digest);
    }

    @Test void shopkeepersPreviewPrintsCountsAndTheGoldenOutputHash() throws Exception {
        Path shopkeepers = citizensFixtures().resolveSibling("shopkeepers");
        String preview = ConverterMain.run(new String[]{"shopkeepers-preview", shopkeepers.resolve("player.yml").toString(), root.toString(), "config=" + shopkeepers.resolve("player-config.yml")});
        assertTrue(preview.contains("Output hash: " + ImportDigest.outputHash(shopkeepers.resolve("expected/player"))), preview);
        assertTrue(preview.contains("Shops: 6"), preview);
        Matcher id = Pattern.compile("^Shopkeepers preview ([a-f0-9]{64})").matcher(preview);
        assertTrue(id.find(), preview);
        String applied = ConverterMain.run(new String[]{"shopkeepers-apply", root.toString(), id.group(1)});
        assertTrue(applied.contains("waiting for their NPC: 1"), applied);
        assertThrows(IllegalArgumentException.class, () -> ConverterMain.run(new String[]{"shopkeepers-preview", "a", "b", "colour=x"}));
    }

    @Test void digestNeedsOneStagedDirectory() {
        assertThrows(IllegalArgumentException.class, () -> ConverterMain.run(new String[]{"digest", "a", "b"}));
        assertThrows(Exception.class, () -> ConverterMain.run(new String[]{"digest", root.resolve("missing").toString()}));
    }
}
