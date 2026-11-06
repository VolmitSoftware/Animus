package art.arcane.animus.core.hotload;

import art.arcane.animus.core.building.Schematic;
import art.arcane.animus.core.building.schematic.SchematicException;
import art.arcane.animus.core.building.schematic.SchematicReader;
import art.arcane.animus.core.config.HotloadSettings;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The watched files of one data folder, for {@link HotloadEngine}. Text files are read as UTF-8; a schematic is read as
 * a JSON summary of its bytes, size and palette, so its changes diff like any document. Symlinks are never followed.
 */
public final class HotloadFiles implements HotloadEngine.Source {
    private static final int MAXIMUM_DEPTH = 16;
    private final Path root;
    /** The folder as given and, when a symlink leads to it, as resolved; Animus writes may name either. */
    private final java.util.List<Path> bases;
    private final Supplier<HotloadSettings> settings;
    private final Supplier<SchematicReader.Limits> schematicLimits;
    private volatile boolean truncated;

    public HotloadFiles(Path root, Supplier<HotloadSettings> settings, Supplier<SchematicReader.Limits> schematicLimits) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.schematicLimits = Objects.requireNonNull(schematicLimits, "schematicLimits");
        Path real;
        try { real = this.root.toRealPath(); }
        catch (IOException missing) { real = this.root; }
        bases = real.equals(this.root) ? java.util.List.of(this.root) : java.util.List.of(this.root, real);
    }

    public Path root() { return root; }

    /** True when the last scan stopped at {@code maximumFiles}. */
    public boolean truncated() { return truncated; }

    /** The data-folder relative path of an absolute path, or empty when it is outside the folder or not watched. */
    public Optional<String> relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path base : bases) {
            if (!absolute.startsWith(base) || absolute.equals(base)) continue;
            String relative = base.relativize(absolute).toString().replace('\\', '/');
            if (HotloadTargets.of(relative).isPresent()) return Optional.of(relative);
        }
        return Optional.empty();
    }

    @Override public Map<String, String> signatures() throws IOException {
        HotloadSettings active = settings.get();
        Map<String, String> found = new HashMap<>();
        truncated = false;
        add(found, HotloadTargets.CONFIG, active.maximumFiles());
        if (!active.enabled()) return found;
        add(found, art.arcane.animus.core.behavior.catalog.ServerBehaviorList.PATH, active.maximumFiles());
        Deque<Path> folders = new ArrayDeque<>();
        for (String folder : HotloadTargets.FOLDERS) folders.add(root.resolve(folder));
        while (!folders.isEmpty()) {
            Path folder = folders.poll();
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || root.relativize(folder).getNameCount() > MAXIMUM_DEPTH) continue;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (HotloadTargets.temporary(name)) continue;
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) { folders.add(entry); continue; }
                    if (!add(found, root.relativize(entry).toString().replace('\\', '/'), active.maximumFiles())) return found;
                }
            } catch (IOException unreadable) { /* A folder that vanished mid-scan is read again next poll. */ }
        }
        return found;
    }

    @Override public String read(String path) throws IOException {
        Path file = root.resolve(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        int maximum = settings.get().maximumFileBytes();
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(maximum + 1); }
        if (bytes.length > maximum) throw new HotloadEngine.Refused("$: the file exceeds " + maximum + " bytes ([hotload] maximumFileBytes)");
        Optional<HotloadTargets.Target> target = HotloadTargets.of(path);
        if (target.isPresent() && target.get().area() == HotloadTargets.Area.SCHEMATIC) return schematicSummary(file.getFileName().toString(), bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notText) { throw new HotloadEngine.Refused("$: the file is not UTF-8 text"); }
    }

    /** A schematic as the JSON its hotload diffs: bytes, hash and, when it reads, its size and palette; otherwise the reader's error. */
    String schematicSummary(String name, byte[] bytes) {
        JsonObject summary = new JsonObject();
        summary.addProperty("bytes", bytes.length);
        summary.addProperty("sha256", sha256(bytes));
        try {
            Schematic schematic = SchematicReader.read(name, bytes, schematicLimits.get());
            summary.addProperty("format", schematic.format());
            summary.addProperty("size", schematic.width() + "x" + schematic.height() + "x" + schematic.length());
            summary.addProperty("palette", schematic.palette().size());
            summary.addProperty("blocks", schematic.definedCells());
        } catch (SchematicException | RuntimeException invalid) {
            summary.addProperty("error", String.valueOf(invalid.getMessage()));
        }
        return summary.toString();
    }

    private boolean add(Map<String, String> found, String relative, int maximum) {
        if (HotloadTargets.of(relative).isEmpty()) return true;
        if (found.size() >= maximum) { truncated = true; return false; }
        Path file = root.resolve(relative);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile()) found.put(relative, attributes.size() + ":" + attributes.lastModifiedTime().toMillis() + ":" + attributes.fileKey());
        } catch (IOException missing) { /* Gone between listing and reading; the next poll sees it removed. */ }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
