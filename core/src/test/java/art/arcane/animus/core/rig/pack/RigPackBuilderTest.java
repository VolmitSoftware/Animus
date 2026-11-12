package art.arcane.animus.core.rig.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.RigDocument;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RigPackBuilderTest {
    /** A 1x1 transparent PNG. */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg==";
    static final String MODEL = """
            {"meta":{"format_version":"4.10","model_format":"free"},"name":"golem","resolution":{"width":16,"height":16},
             "elements":[
               {"name":"torso","uuid":"e1","type":"cube","from":[-4,8,-2],"to":[4,20,2],"origin":[0,8,0],"rotation":[0,0,0],
                "faces":{"north":{"uv":[0,0,8,12],"texture":0},"down":{"uv":[0,0,8,4],"texture":null}}},
               {"name":"blade","uuid":"e2","type":"cube","from":[-1,20,-1],"to":[1,28,1],"origin":[0,20,0],"rotation":[10,20,0],
                "faces":{"north":{"uv":[0,0,2,2],"texture":0}}}],
             "outliner":[{"name":"Body","uuid":"g1","origin":[2,8,1],"rotation":[0,0,0],"children":["e1",
                 {"name":"Head","uuid":"g2","origin":[0,20,0],"rotation":[10,0,5],"children":["e2"]}]}],
             "textures":[{"name":"skin.png","id":"0","uuid":"t1","width":16,"height":16,"source":"data:image/png;base64,%s"}],
             "animations":[{"name":"Walk","loop":"loop","length":1,"animators":{
                 "g1":{"name":"Body","type":"bone","keyframes":[
                    {"channel":"position","time":0.25,"interpolation":"step","data_points":[{"x":16,"y":"16","z":16}]},
                    {"channel":"position","time":0.5,"interpolation":"linear","data_points":[{"x":0,"y":"math.sin(query.anim_time)","z":0}]}]}}}]}
            """.formatted(PNG);

    @TempDir Path data;

    private void write(String relative, byte[] content) throws IOException {
        Path file = data.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }
    private RigPackBuilder.Result build() throws IOException { return RigPackBuilder.build(data, data.resolve("pack/out"), RigPackBuilder.Limits.DEFAULT); }

    @Test
    void modelsBecomeItemModelsNamedAfterTheirRigAndBone() throws IOException {
        write("models/town/golem.bbmodel", MODEL.getBytes());
        RigPackBuilder.Result result = build();
        List<String> names = entries(result.zip());
        assertTrue(names.contains("assets/animus/items/golem/body.json"), names.toString());
        assertTrue(names.contains("assets/animus/models/item/golem/body.json"), names.toString());
        assertTrue(names.contains("assets/animus/items/golem/head-blade.json"), "a free cube rotation becomes its own bone: " + names);
        assertTrue(names.contains("assets/animus/textures/item/golem/skin.png"), names.toString());
        assertEquals(names.stream().sorted().toList(), names);
        RigDocument rig = RigDocument.parse("town:golem", Files.readString(data.resolve("pack/out/documents/rigs/town/golem.json"))).document().orElseThrow();
        assertEquals(List.of("body", "head", "head-blade"), rig.bones().stream().map(RigDocument.Bone::id).toList());
        assertArrayEquals(new double[]{-0.125, 0.5, -0.0625}, rig.bones().get(0).pivot(), 1e-9, "pivots turn to face +Z");
        assertArrayEquals(new double[]{-10, 0, -5}, rig.bones().get(1).rotation(), 1e-9);
        assertEquals("[item_model=\"animus:golem/body\"]", rig.bones().get(0).visual().item().orElseThrow().components().orElseThrow());
        AnimationDocument walk = AnimationDocument.parse("town:golem/walk", Files.readString(data.resolve("pack/out/documents/animations/town/golem/walk.json"))).document().orElseThrow();
        assertEquals(List.of(), walk.problemsAgainst(rig));
        assertArrayEquals(new double[]{1, 1, -1}, walk.tracks().getFirst().keyframes().getFirst().value(), 1e-9);
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("Molang")), result.problems().toString());
        JsonObject meta = JsonDocuments.object(new String(entry(result.zip(), "pack.mcmeta"))).getAsJsonObject("pack");
        assertEquals(84, meta.get("min_format").getAsInt());
        assertEquals(97, meta.get("max_format").getAsInt());
    }

    @Test
    void editorSourcesArePackedAsTheyAreAndWinOverAModel() throws IOException {
        byte[] edited = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"animus:item/golem/custom\"}}\n".getBytes();
        write("pack/assets/animus/items/golem/body.json", edited);
        write("pack/assets/animus/models/item/golem/custom.json", "{\"elements\":[]}\n".getBytes());
        write("pack/assets/Bad Name.json", "{}".getBytes());
        write("models/town/golem.bbmodel", MODEL.getBytes());
        RigPackBuilder.Result result = build();
        assertArrayEquals(edited, entry(result.zip(), "assets/animus/items/golem/body.json"));
        assertTrue(entries(result.zip()).contains("assets/animus/models/item/golem/custom.json"));
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("already exists")), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("Bad Name.json")), result.problems().toString());
    }

    @Test
    void buildsAreByteForByteReproducible() throws IOException {
        write("models/town/golem.bbmodel", MODEL.getBytes());
        byte[] first = Files.readAllBytes(build().zip());
        byte[] second = Files.readAllBytes(build().zip());
        assertArrayEquals(first, second);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(data.resolve("pack/out/animus-rigs.zip")))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) assertEquals(ZipEntry.STORED, entry.getMethod());
        }
        assertTrue(Files.isRegularFile(data.resolve("pack/out/pack/pack.mcmeta")), "a mergeable folder is written too");
        assertTrue(Files.readString(data.resolve("pack/out/animus-rigs.zip.sha1")).trim().matches("[a-f0-9]{40}"));
    }

    @Test
    void refusesOversizedModelsAndNeverReadsLinkedTextures() throws IOException {
        write("models/town/linked.bbmodel", MODEL.replace("data:image/png;base64," + PNG, "https://example.com/skin.png").getBytes());
        write("models/town/big.bbmodel", new byte[(int) RigPackBuilder.Limits.DEFAULT.maximumFileBytes() + 1]);
        RigPackBuilder.Result result = build();
        assertEquals(1, result.models(), "the linked model still builds; only its texture is missing");
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("big.bbmodel") && problem.contains("bytes")), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("linked.bbmodel") && problem.contains("not embedded")), result.problems().toString());
        assertTrue(entries(result.zip()).stream().noneMatch((String name) -> name.endsWith(".png")));
    }

    @Test
    void leavesOutModelEngineHitboxAndShadowGroups() throws IOException {
        String extra = MODEL
                .replace("\"elements\":[", "\"elements\":[{\"uuid\":\"e9\",\"type\":\"cube\",\"from\":[-12,0,-12],\"to\":[12,32,12],\"faces\":{\"north\":{\"uv\":[0,0,4,4],\"texture\":0}}},")
                .replace("\"outliner\":[", "\"outliner\":[{\"name\":\"HitBox\",\"uuid\":\"g8\",\"origin\":[0,28,0],\"children\":[\"e9\"]},{\"name\":\"shadow\",\"uuid\":\"g9\",\"origin\":[0,0,0],\"children\":[]},")
                .replace("\"animators\":{", "\"animators\":{\"g8\":{\"name\":\"HitBox\",\"type\":\"bone\",\"keyframes\":[{\"channel\":\"rotation\",\"time\":0,\"data_points\":[{\"x\":0,\"y\":45,\"z\":0}]}]},");
        BlockbenchImport.Output output = BlockbenchImport.convert("town:golem", extra.getBytes(), RigPackBuilder.Limits.DEFAULT);
        List<String> bones = new ArrayList<>();
        output.rig().getAsJsonArray("bones").forEach((com.google.gson.JsonElement bone) -> bones.add(bone.getAsJsonObject().get("id").getAsString()));
        assertEquals(List.of("body", "head", "head-blade"), bones);
        assertTrue(output.resources().keySet().stream().noneMatch((String path) -> path.contains("hitbox") || path.contains("shadow")), output.resources().keySet().toString());
        assertTrue(output.animations().getFirst().getAsJsonArray("tracks").asList().stream().noneMatch((com.google.gson.JsonElement track) -> track.getAsJsonObject().get("bone").getAsString().contains("hitbox")));
        assertTrue(output.warnings().contains("Group HitBox is the Model Engine hitbox; it was left out and the NPC keeps its body's hitbox."), output.warnings().toString());
        assertTrue(output.warnings().contains("Group shadow is the Model Engine shadow; it was left out."), output.warnings().toString());
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) names.add(entry.getName());
        }
        return names;
    }
    private static byte[] entry(Path zip, String name) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
                if (entry.getName().equals(name)) return input.readAllBytes();
        }
        throw new AssertionError("No entry " + name);
    }
}
