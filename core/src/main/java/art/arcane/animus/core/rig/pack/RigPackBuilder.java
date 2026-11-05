package art.arcane.animus.core.rig.pack;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
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
 * {@code /animus pack build}: turns every {@code models/<namespace>/<name>.bbmodel} into one resource pack. Each bone's
 * cubes become an item model, reached through {@code assets/animus/items/rig_bone.json}, which selects on the item's
 * {@code custom_model_data} string {@code namespace:name/bone}. The zip is reproducible: sorted, stored entries with a
 * fixed timestamp. A mergeable folder and the generated rig and animation documents are written beside it. Input is
 * bounded and only embedded textures are read; nothing touches the network.
 */
public final class RigPackBuilder {
    /** Resource pack format per supported Minecraft version, from each client's {@code version.json}. */
    public static final Map<String, Integer> FORMATS = Map.of("26.1.2", 84, "26.2", 88, "26.3", 97);
    public static final String ZIP = "animus-rigs.zip", FOLDER = "pack", DOCUMENTS = "documents";
    private static final LocalDateTime EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0);

    public record Limits(int maximumModels, long maximumFileBytes, int maximumElements, int maximumTextures, int maximumTextureBytes,
                         int maximumTextureSide, int maximumAnimations, long maximumPackBytes) {
        public static final Limits DEFAULT = new Limits(64, 8L * 1024 * 1024, 4096, 16, 1024 * 1024, 1024, 64, 64L * 1024 * 1024);
    }
    public record Result(Path zip, Path folder, Path documents, String sha1, int models, int bones, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    private RigPackBuilder() {}

    /** File lane only. {@code out} is replaced: its pack folder, documents folder and zip are written again from scratch. */
    public static Result build(Path models, Path out, Limits limits) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> files = new TreeMap<>(), documents = new TreeMap<>();
        Map<String, String> cases = new TreeMap<>();
        int converted = 0, bones = 0;
        for (Path file : sources(models, limits, problems)) {
            Path relative = models.relativize(file);
            String label = "models/" + relative.toString().replace('\\', '/');
            String namespace = relative.getName(0).toString(), name = relative.getFileName().toString().replaceFirst("\\.bbmodel$", "");
            if (!namespace.matches("[a-z0-9_-]{1,64}") || !name.matches("[a-z0-9_-]{1,64}")) { problems.add(label + ": names use lowercase letters, digits, _ and -"); continue; }
            long size = Files.size(file);
            if (size > limits.maximumFileBytes()) { problems.add(label + ": " + size + " bytes is over the " + limits.maximumFileBytes() + " byte limit"); continue; }
            BlockbenchImport.Output output;
            try { output = BlockbenchImport.convert(namespace, name, JsonDocuments.read(file), limits); }
            catch (IOException | RuntimeException refused) { problems.add(label + ": " + refused.getMessage()); continue; }
            output.problems().stream().limit(40).forEach((String problem) -> problems.add(label + " " + problem));
            for (Map.Entry<String, String> model : output.models().entrySet()) {
                files.put("assets/" + namespace + "/models/rig/" + name + "/" + model.getKey() + ".json", model.getValue().getBytes(StandardCharsets.UTF_8));
                cases.put(namespace + ":" + name + "/" + model.getKey(), namespace + ":rig/" + name + "/" + model.getKey());
                bones++;
            }
            for (Map.Entry<String, byte[]> texture : output.textures().entrySet())
                files.put("assets/" + namespace + "/textures/rig/" + name + "/" + texture.getKey() + ".png", texture.getValue());
            documents.put("rigs/" + namespace + "/" + name + ".json", output.rig().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> animation : output.animations().entrySet())
                documents.put("animations/" + namespace + "/" + name + "/" + animation.getKey() + ".json", animation.getValue().getBytes(StandardCharsets.UTF_8));
            converted++;
        }
        files.put("pack.mcmeta", mcmeta().getBytes(StandardCharsets.UTF_8));
        files.put("assets/animus/items/rig_bone.json", dispatch(cases).getBytes(StandardCharsets.UTF_8));
        long total = files.values().stream().mapToLong((byte[] bytes) -> bytes.length).sum();
        if (total > limits.maximumPackBytes()) throw new IOException("The pack would be " + total + " bytes, over the " + limits.maximumPackBytes() + " byte limit");

        Files.createDirectories(out);
        Path folder = out.resolve(FOLDER), documentFolder = out.resolve(DOCUMENTS), zip = out.resolve(ZIP);
        replaceTree(folder, files);
        replaceTree(documentFolder, documents);
        Path partial = out.resolve(ZIP + ".partial");
        String sha1 = writeZip(partial, files);
        Files.move(partial, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(out.resolve(ZIP + ".sha1"), sha1 + "\n");
        return new Result(zip, folder, documentFolder, sha1, converted, bones, problems);
    }

    private static List<Path> sources(Path models, Limits limits, List<String> problems) throws IOException {
        if (!Files.isDirectory(models, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> namespaces = Files.list(models)) {
            for (Path namespace : namespaces.sorted().toList()) {
                if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) continue;
                try (Stream<Path> entries = Files.list(namespace)) {
                    for (Path file : entries.sorted().toList()) {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.getFileName().toString().endsWith(".bbmodel")) continue;
                        if (found.size() >= limits.maximumModels()) { problems.add("models: only the first " + limits.maximumModels() + " models were read"); return found; }
                        found.add(file);
                    }
                }
            }
        }
        return found;
    }

    private static String mcmeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Animus rig models");
        pack.addProperty("min_format", FORMATS.values().stream().mapToInt(Integer::intValue).min().orElseThrow());
        pack.addProperty("max_format", FORMATS.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return JsonDocuments.write(root);
    }

    /** One item definition for every bone: a select on the first custom model data string. */
    private static String dispatch(Map<String, String> cases) {
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:custom_model_data");
        select.addProperty("index", 0);
        JsonArray list = new JsonArray();
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            JsonObject option = new JsonObject();
            option.addProperty("when", entry.getKey());
            option.add("model", model(entry.getValue()));
            list.add(option);
        }
        select.add("cases", list);
        select.add("fallback", model("minecraft:item/paper"));
        JsonObject root = new JsonObject();
        root.add("model", select);
        return JsonDocuments.write(root);
    }
    private static JsonObject model(String reference) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", reference);
        return model;
    }

    private static String writeZip(Path destination, Map<String, byte[]> files) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-1 is unavailable", impossible); }
        try (OutputStream output = Files.newOutputStream(destination);
             java.security.DigestOutputStream hashed = new java.security.DigestOutputStream(output, digest);
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
