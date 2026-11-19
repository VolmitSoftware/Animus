package art.arcane.animus.core.editor;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EditorSchemasTest {
    @Test void theJarCarriesEveryServedSchemaAndNoOther() throws Exception {
        URL folder = EditorSchemas.class.getResource("/animus/schema/");
        assertNotNull(folder, "the hand-written schemas are on the classpath");
        Path root = Path.of(folder.toURI());
        Set<String> shipped;
        try (Stream<Path> files = Files.walk(root)) {
            shipped = files.filter(Files::isRegularFile).map((Path file) -> root.relativize(file).toString().replace('\\', '/')).collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(new TreeSet<>(EditorSchemas.BUNDLED.values()), shipped);
    }
}
