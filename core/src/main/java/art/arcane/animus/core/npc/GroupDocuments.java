package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Bounded reads of {@code groups/<id>.json}: links are not followed and nothing past {@link GroupDocument#MAX_BYTES} is read. */
public final class GroupDocuments {
    public static final String FOLDER = "groups";
    /** The most group files one catalog reads. */
    public static final int MAX_GROUPS = 4096;

    private GroupDocuments() {}

    public static String read(DocumentStore documents, String id) throws IOException {
        if (id == null || !id.matches(GroupDocument.ID)) throw new IOException("Invalid group identifier");
        Path path = documents.resolve(FOLDER + "/" + id + ".json");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(GroupDocument.MAX_BYTES + 1); }
        if (bytes.length > GroupDocument.MAX_BYTES) throw new IOException("$: the group file exceeds 256 KiB");
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** The ids of the regular group files directly under {@code groups/}, sorted. */
    public static List<String> ids(DocumentStore documents) throws IOException {
        Path directory = documents.resolve(FOLDER);
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return ids;
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            for (Path file : listing) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = name.substring(0, name.length() - 5);
                if (!id.matches(GroupDocument.ID)) continue;
                if (ids.size() >= MAX_GROUPS) throw new IOException("Group file limit exceeded: " + MAX_GROUPS);
                ids.add(id);
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }
}
