package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.RigDocument;
import art.arcane.animus.core.rig.pack.RigPackBuilder;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server and the editor agree on the resource pack: {@code schema/fixtures/pack/robot/} is what the editor's
 * Blockbench import writes for {@code robot.bbmodel} (pinned by animus-web's pack_fixture_test.dart).
 */
final class RigPackContractTest {
    private static final Pattern ITEM_MODEL = Pattern.compile("item_model=\"([a-z0-9_.-]+):([a-z0-9_./-]+)\"");
    @TempDir Path data;

    private static Map<String, byte[]> golden() throws IOException {
        Path root = SharedFixtures.fixture("pack/robot");
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return files;
    }

    @Test
    void serverImportMatchesTheEditorImport() throws IOException {
        Files.createDirectories(data.resolve("models/town"));
        Files.copy(SharedFixtures.fixture("pack/robot.bbmodel"), data.resolve("models/town/robot.bbmodel"));
        RigPackBuilder.Result result = RigPackBuilder.build(data, data.resolve("pack/out"), RigPackBuilder.Limits.DEFAULT);
        assertEquals(1, result.models(), result.problems().toString());
        Map<String, byte[]> expected = golden();
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            String path = file.getKey();
            Path produced = path.startsWith("pack/assets/") ? result.folder().resolve("assets/" + path.substring("pack/assets/".length())) : result.documents().resolve(path);
            assertTrue(Files.isRegularFile(produced), path + " was not produced");
            if (path.endsWith(".png")) assertArrayEquals(file.getValue(), Files.readAllBytes(produced), path);
            else assertEquals(JsonDocuments.object(new String(file.getValue(), StandardCharsets.UTF_8)), JsonDocuments.object(Files.readString(produced)), path);
        }
        long producedAssets;
        try (Stream<Path> walk = Files.walk(result.folder().resolve("assets"))) { producedAssets = walk.filter(Files::isRegularFile).count(); }
        assertEquals(expected.keySet().stream().filter((String path) -> path.startsWith("pack/assets/")).count(), producedAssets, "no extra pack files");
    }

    @Test
    void anEditorExportBuildsIntoAPackWhereEveryBoneResolves() throws IOException {
        for (Map.Entry<String, byte[]> file : golden().entrySet()) {
            Path target = data.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        RigDocument rig = RigDocument.parse("town:robot", Files.readString(data.resolve("rigs/town/robot.json"))).document().orElseThrow();
        for (String clip : List.of("idle", "walk")) {
            AnimationDocument animation = AnimationDocument.parse("town:robot/" + clip, Files.readString(data.resolve("animations/town/robot/" + clip + ".json"))).document().orElseThrow();
            assertEquals(List.of(), animation.problemsAgainst(rig));
        }
        RigPackBuilder.Result result = RigPackBuilder.build(data, data.resolve("pack/out"), RigPackBuilder.Limits.DEFAULT);
        assertEquals(List.of(), result.problems());
        Map<String, byte[]> zip = new TreeMap<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(result.zip()))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertEquals(ZipEntry.STORED, entry.getMethod());
                zip.put(entry.getName(), input.readAllBytes());
            }
        }
        assertTrue(zip.containsKey("pack.mcmeta"));
        int shown = 0;
        for (RigDocument.Bone bone : rig.bones()) {
            if (bone.visual().type() != RigDocument.Type.ITEM) continue;
            Matcher model = ITEM_MODEL.matcher(bone.visual().item().orElseThrow().components().orElseThrow());
            assertTrue(model.find(), bone.id());
            byte[] definition = zip.get("assets/" + model.group(1) + "/items/" + model.group(2) + ".json");
            assertTrue(definition != null, "no item definition for " + model.group(0));
            String reference = JsonDocuments.object(new String(definition, StandardCharsets.UTF_8)).getAsJsonObject("model").get("model").getAsString();
            byte[] itemModel = zip.get("assets/" + reference.substring(0, reference.indexOf(':')) + "/models/" + reference.substring(reference.indexOf(':') + 1) + ".json");
            assertTrue(itemModel != null, "no model " + reference);
            JsonObject textures = JsonDocuments.object(new String(itemModel, StandardCharsets.UTF_8)).getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                String id = texture.getValue().getAsString();
                if (id.startsWith("minecraft:")) continue;
                assertTrue(zip.containsKey("assets/" + id.substring(0, id.indexOf(':')) + "/textures/" + id.substring(id.indexOf(':') + 1) + ".png"), "no texture " + id);
            }
            shown++;
        }
        assertEquals(4, shown);
    }
}
