package art.arcane.animus.core.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JsonDocumentTreeTest {
    @TempDir Path root;

    @Test void missingAndEmptyTreesReturnNoDocuments() throws Exception {
        assertEquals(List.of(), JsonDocumentTree.files(root.resolve("absent")));
        assertEquals(List.of(), JsonDocumentTree.files(root, 1, 1, 0));
        assertFalse(Files.exists(root.resolve("absent")));
    }

    @Test void documentsAreSortedAndDirectoriesOrOtherFilesAreExcluded() throws Exception {
        Path z = Files.writeString(root.resolve("z.json"), "{}");
        Path a = Files.writeString(root.resolve("a.json"), "{}");
        Files.writeString(root.resolve("notes.txt"), "ordinary non-JSON file");
        Files.createDirectory(root.resolve("directory.json"));
        List<Path> documents = JsonDocumentTree.files(root, 5, 2, 1);
        assertEquals(List.of(a, z), documents);
        assertThrows(UnsupportedOperationException.class, () -> documents.add(root));
    }

    @Test void nonJsonFilesAndDirectoriesStillConsumeTheTraversalBudget() throws Exception {
        Files.writeString(root.resolve("one.txt"), "1");
        Files.writeString(root.resolve("two.txt"), "2");
        Files.createDirectory(root.resolve("three"));
        IOException failure = assertThrows(IOException.class, () -> JsonDocumentTree.files(root, 3, 10, 2));
        assertTrue(failure.getMessage().contains("entry limit"));
        assertEquals(List.of(), JsonDocumentTree.files(root, 4, 1, 2));
    }

    @Test void documentLimitIsEnforcedBeforeReturningAPartialRegistry() throws Exception {
        Files.writeString(root.resolve("one.json"), "{}"); Files.writeString(root.resolve("two.json"), "{}");
        assertEquals(2, JsonDocumentTree.files(root, 10, 2, 1).size());
        Files.writeString(root.resolve("three.json"), "{}");
        IOException failure = assertThrows(IOException.class, () -> JsonDocumentTree.files(root, 10, 2, 1));
        assertTrue(failure.getMessage().contains("document limit"));
    }

    @Test void anythingNestedTooDeepIsSkippedAndNamedWhileTheRestLoads() throws Exception {
        Path shallow = Files.writeString(root.resolve("guard.json"), "{}");
        Path nested = Files.createDirectories(root.resolve("one/two/three/four"));
        Files.writeString(nested.resolve("deep.json"), "{}");
        Files.writeString(root.resolve("one/two/not-json.txt"), "bounded even without a JSON extension");
        java.util.List<String> skipped = new java.util.ArrayList<>();
        assertEquals(List.of(shallow), JsonDocumentTree.files(root, 100, 10, 2, skipped::add));
        assertEquals(List.of("Skipped " + root.resolve("one/two/not-json.txt") + ": nested deeper than 2 folders; move it higher up",
                "Skipped " + root.resolve("one/two/three") + ": nested deeper than 2 folders; move it higher up"), skipped.stream().sorted().toList());
    }

    @Test void symlinksInsideAreSkippedWithoutFollowingTheirTargets() throws Exception {
        Path target = Files.createDirectory(root.resolve("target"));
        Files.writeString(target.resolve("outside.json"), "{}");
        Path linkedRoot = Files.createSymbolicLink(root.resolve("linked-root"), target);
        assertThrows(IOException.class, () -> JsonDocumentTree.files(linkedRoot), "the root itself must be a real folder");
        Path scan = Files.createDirectory(root.resolve("scan"));
        Path real = Files.writeString(scan.resolve("real.json"), "{}");
        Files.createSymbolicLink(scan.resolve("directory-link"), target);
        Files.createSymbolicLink(scan.resolve("file-link.json"), target.resolve("outside.json"));
        Files.createSymbolicLink(scan.resolve("dangling.json"), root.resolve("absent-target"));
        java.util.List<String> skipped = new java.util.ArrayList<>();
        assertEquals(List.of(real), JsonDocumentTree.files(scan, 100, 10, 8, skipped::add));
        assertEquals(3, skipped.size(), skipped.toString());
        assertTrue(skipped.stream().allMatch((String line) -> line.endsWith(": symbolic links are not read; replace it with the real file or folder")), skipped.toString());
    }

    @Test void aFileOrDanglingRootCannotBeMistakenForAnEmptyDefinitionDirectory() throws Exception {
        Path malformed = Files.writeString(root.resolve("npcs"), "a file is not an empty directory");
        assertThrows(IOException.class, () -> JsonDocumentTree.files(malformed));
        Path dangling = Files.createSymbolicLink(root.resolve("dangling"), root.resolve("absent"));
        assertThrows(IOException.class, () -> JsonDocumentTree.files(dangling));
    }
}
