package art.arcane.animus.converter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConverterBehaviorsTest {
    @TempDir Path root;

    private static Path examples() {
        Path current = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(current.resolve("settings.gradle"))) current = current.getParent();
        return current.resolve("examples");
    }

    @Test void validatesAnExamplePackAndSaysWhatItCannotCheck() throws Exception {
        String output = ConverterMain.run(new String[]{"validate-behaviors", examples().resolve("packs/shop").toString()});
        assertTrue(output.startsWith("VALID"), output);
        assertTrue(output.contains("Not checked offline: The command allow-list"), output);
    }

    @Test void validatesTheWholeExamplesTreeIncludingModuleBehaviors() throws Exception {
        String output = ConverterMain.run(new String[]{"validate-behaviors", examples().toString()});
        assertTrue(output.startsWith("VALID"), output);
        assertTrue(output.contains("Not checked offline: action reference:hello"), output);
    }

    @Test void invalidDirectoryFailsWithItsProblems() throws Exception {
        Path file = root.resolve("behaviors/town/greeter.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"animus:sya\"}}}");
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> ConverterMain.run(new String[]{"validate-behaviors", root.toString()}));
        assertTrue(invalid.getMessage().startsWith("INVALID"), invalid.getMessage());
        assertTrue(invalid.getMessage().contains("did you mean animus:say?"), invalid.getMessage());
    }

    @Test void writesSchemasOffline() throws Exception {
        Path output = root.resolve("out");
        String result = ConverterMain.run(new String[]{"schema", output.toString()});
        assertTrue(result.contains("5 files"), result);
        for (String name : List.of("behavior.schema.json", "npc.schema.json", "route.schema.json", "pack.schema.json", "completions.json"))
            assertTrue(Files.isRegularFile(output.resolve("schemas").resolve(name)), name);
    }
}
