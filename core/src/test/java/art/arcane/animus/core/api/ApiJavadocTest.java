package art.arcane.animus.core.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every public type of the two published API modules carries a javadoc comment. */
class ApiJavadocTest {
    private static final Pattern TYPE = Pattern.compile("^\\s*(?:@\\w+\\s+)*public\\s+(?:static\\s+|final\\s+|sealed\\s+|abstract\\s+)*(?:class|interface|record|enum)\\s+(\\w+)", Pattern.MULTILINE);

    @Test void everyPublicApiTypeIsDocumented() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String module : List.of("../animus-api/src/main/java", "../animus-bukkit-api/src/main/java")) {
            try (Stream<Path> files = Files.walk(Path.of(module))) {
                for (Path file : files.filter((Path path) -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    Matcher type = TYPE.matcher(source);
                    while (type.find()) {
                        String before = source.substring(0, type.start()).stripTrailing();
                        if (!before.endsWith("*/")) missing.add(file.getFileName() + ": " + type.group(1));
                    }
                }
            }
        }
        assertTrue(missing.size() < 1000, "the scan ran");
        assertEquals(List.of(), missing);
    }
}
