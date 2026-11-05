package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The repository's top-level schema/ folder, shared with animus-web. The build passes its location to the tests. */
public final class SharedFixtures {
    private SharedFixtures() {}

    public static Path schemaDirectory() {
        String configured = System.getProperty("animusSchemaDirectory");
        if (configured == null) throw new IllegalStateException("animusSchemaDirectory is not set; run the tests through Gradle");
        Path directory = Path.of(configured);
        if (!Files.isRegularFile(directory.resolve("fixtures/index.json"))) throw new IllegalStateException("No shared fixtures under " + directory);
        return directory;
    }

    public static Path fixture(String relative) { return schemaDirectory().resolve("fixtures").resolve(relative); }

    public static String text(Path path) throws IOException { return Files.readString(path); }

    public static JsonObject object(Path path) throws IOException { return JsonDocuments.object(Files.readString(path)); }
}
