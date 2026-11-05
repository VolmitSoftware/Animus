package art.arcane.animus.core.rig.pack;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code /animus pack build}. The pack is {@code pack.mcmeta} plus every file under the data folder's
 * {@code pack/assets/}, where the editor's rig export puts its item definitions, models and textures, plus the same
 * sources made from each {@code models/<namespace>/<name>.bbmodel} (rig {@code namespace:name}) by the editor's import
 * rules. A bone shows {@code minecraft:paper} with {@code item_model="animus:<rig path>/<bone>"}; the layout is in
 * docs/runtime.md, "Resource pack layout". The zip is reproducible: sorted, stored entries with one fixed time. A
 * mergeable folder and the rig and animation documents made from models are written beside it. Input is bounded and
 * nothing is fetched.
 */
public final class RigPackBuilder {
    /** Resource pack format per supported Minecraft version, from each client's {@code version.json}. */
    public static final Map<String, Integer> FORMATS = Map.of("26.1.2", 84, "26.2", 88, "26.3", 97);
    public static final String ZIP = "animus-rigs.zip", FOLDER = "pack", DOCUMENTS = "documents", SOURCES = BlockbenchImport.SOURCE_ROOT, MODELS = "models";
    private static final LocalDateTime EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final String ASSET = "[a-z0-9_.-]+/(?:[a-z0-9_.-]+/)*[a-z0-9_.-]+\\.(?:json|png|mcmeta)";

    public record Limits(int maximumModels, long maximumFileBytes, int maximumElements, int maximumTextures, int maximumTextureBytes,
                         int maximumTextureSide, int maximumAnimations, int maximumSourceFiles, long maximumPackBytes) {
        /** The editor's import limits (BbmodelLimits), so a model the editor takes builds here too. */
        public static final Limits DEFAULT = new Limits(64, 16L * 1024 * 1024, 4096, 16, 4 * 1024 * 1024, 1024, 64, 16384, 128L * 1024 * 1024);
    }
    public record Result(Path zip, Path folder, Path documents, String sha1, int models, int files, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    private RigPackBuilder() {}

    /** File lane only. {@code out} is rebuilt: its pack folder, documents folder and zip are written from scratch. */
    public static Result build(Path data, Path out, Limits limits) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> files = new TreeMap<>(), documents = new TreeMap<>();
        long total = 0;
        Path sources = data.resolve(SOURCES);
        for (Path file : walk(sources, limits.maximumSourceFiles())) {
            String relative = sources.relativize(file).toString().replace('\\', '/');
            if (!relative.matches(ASSET)) { problems.add(SOURCES + "/" + relative + ": skipped; pack sources are lowercase .json, .png or .mcmeta files"); continue; }
            long size = Files.size(file);
            if (size > limits.maximumFileBytes()) { problems.add(SOURCES + "/" + relative + ": " + size + " bytes is over the " + limits.maximumFileBytes() + " byte limit"); continue; }
            total += size;
            if (total > limits.maximumPackBytes()) throw new IOException("The pack sources pass the " + limits.maximumPackBytes() + " byte limit");
            files.put("assets/" + relative, Files.readAllBytes(file));
        }
        int converted = 0;
        for (Path file : models(data.resolve(MODELS), limits, problems)) {
            Path relative = data.resolve(MODELS).relativize(file);
            String label = MODELS + "/" + relative.toString().replace('\\', '/');
            String namespace = relative.getName(0).toString(), name = relative.getFileName().toString().replaceFirst("\\.bbmodel$", "");
            if (!namespace.matches("[a-z0-9_-]{1,64}") || !name.matches("[a-z0-9_-]{1,64}")) { problems.add(label + ": names use lowercase letters, digits, _ and -"); continue; }
            long size = Files.size(file);
            if (size > limits.maximumFileBytes()) { problems.add(label + ": " + size + " bytes is over the " + limits.maximumFileBytes() + " byte limit"); continue; }
            BlockbenchImport.Output output;
            try { output = BlockbenchImport.convert(namespace + ":" + name, Files.readAllBytes(file), limits); }
            catch (IOException | RuntimeException refused) { problems.add(label + ": " + refused.getMessage()); continue; }
            output.warnings().stream().limit(40).forEach((String warning) -> problems.add(label + ": " + warning));
            for (Map.Entry<String, byte[]> resource : output.resources().entrySet()) {
                String path = "assets/" + resource.getKey().substring(SOURCES.length() + 1);
                if (files.containsKey(path)) { problems.add(label + ": " + SOURCES + "/" + path.substring(7) + " already exists there and was kept"); continue; }
                total += resource.getValue().length;
                if (total > limits.maximumPackBytes()) throw new IOException("The pack passes the " + limits.maximumPackBytes() + " byte limit");
                files.put(path, resource.getValue());
            }
            documents.put("rigs/" + namespace + "/" + name + ".json", JsonDocuments.write(output.rig()).getBytes(StandardCharsets.UTF_8));
            for (JsonObject clip : output.animations())
                documents.put("animations/" + clip.get("id").getAsString().replaceFirst(":", "/") + ".json", JsonDocuments.write(clip).getBytes(StandardCharsets.UTF_8));
            converted++;
        }
        files.put("pack.mcmeta", mcmeta().getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(out);
        Path folder = out.resolve(FOLDER), documentFolder = out.resolve(DOCUMENTS), zip = out.resolve(ZIP);
        replaceTree(folder, files);
        replaceTree(documentFolder, documents);
        Path partial = out.resolve(ZIP + ".partial");
        String sha1 = writeZip(partial, files);
        Files.move(partial, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(out.resolve(ZIP + ".sha1"), sha1 + "\n");
        return new Result(zip, folder, documentFolder, sha1, converted, files.size(), problems);
    }

    /** {@code pack.mcmeta} for every supported version. */
    public static String mcmeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Animus rig models");
        pack.addProperty("min_format", FORMATS.values().stream().mapToInt(Integer::intValue).min().orElseThrow());
        pack.addProperty("max_format", FORMATS.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return JsonDocuments.write(root);
    }

    private static List<Path> walk(Path root, int limit) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                if (found.size() >= limit) throw new IOException("More than " + limit + " files under " + SOURCES);
                found.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }

    private static List<Path> models(Path models, Limits limits, List<String> problems) throws IOException {
        if (!Files.isDirectory(models, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> namespaces = Files.list(models)) {
            for (Path namespace : namespaces.sorted().toList()) {
                if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) continue;
                try (Stream<Path> entries = Files.list(namespace)) {
                    for (Path file : entries.sorted().toList()) {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.getFileName().toString().endsWith(".bbmodel")) continue;
                        if (found.size() >= limits.maximumModels()) { problems.add(MODELS + ": only the first " + limits.maximumModels() + " models were read"); return found; }
                        found.add(file);
                    }
                }
            }
        }
        return found;
    }

    private static String writeZip(Path destination, Map<String, byte[]> files) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-1 is unavailable", impossible); }
        try (OutputStream output = Files.newOutputStream(destination);
             DigestOutputStream hashed = new DigestOutputStream(output, digest);
             ZipOutputStream zip = new ZipOutputStream(hashed, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                CRC32 crc = new CRC32();
                crc.update(file.getValue());
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(file.getValue().length);
                entry.setCompressedSize(file.getValue().length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(EPOCH);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Deletes the folder without following links, then writes the files under it. */
    private static void replaceTree(Path folder, Map<String, byte[]> files) throws IOException {
        if (Files.isSymbolicLink(folder)) throw new IOException(folder + " is a link; refusing to replace it");
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = folder.resolve(file.getKey()).normalize();
            if (!target.startsWith(folder)) throw new IOException("Refusing to write outside " + folder);
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
    }
}
