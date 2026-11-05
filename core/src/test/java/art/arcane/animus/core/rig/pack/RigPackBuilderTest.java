package art.arcane.animus.core.rig.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.RigDocument;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
                "faces":{"north":{"uv":[0,0,8,12],"texture":0},"south":{"uv":[0,0,8,12],"texture":0},"east":{"uv":[0,0,4,12],"texture":0},
                         "west":{"uv":[0,0,4,12],"texture":0},"up":{"uv":[0,0,8,4],"texture":0},"down":{"uv":[0,0,8,4],"texture":null}}},
               {"name":"head","uuid":"e2","type":"cube","from":[-3,20,-3],"to":[3,26,3],"origin":[0,20,0],"rotation":[0,22.5,0],
                "faces":{"north":{"uv":[0,0,6,6],"texture":0}}},
               {"name":"bad","uuid":"e3","type":"cube","from":[-1,26,-1],"to":[1,28,1],"origin":[0,26,0],"rotation":[10,20,0],
                "faces":{"north":{"uv":[0,0,2,2],"texture":0}}}],
             "outliner":[{"name":"Body","uuid":"g1","origin":[0,8,0],"rotation":[0,0,0],"children":["e1",
                 {"name":"Head","uuid":"g2","origin":[0,20,0],"rotation":[0,0,5],"children":["e2","e3"]}]}],
             "textures":[{"name":"skin.png","id":"0","uuid":"t1","width":16,"height":16,"source":"data:image/png;base64,%s"}],
             "animations":[{"name":"Walk","loop":"loop","length":1,"animators":{
                 "g2":{"name":"Head","type":"bone","keyframes":[
                    {"channel":"rotation","time":0,"interpolation":"linear","data_points":[{"x":"0","y":"0","z":"0"}]},
                    {"channel":"rotation","time":0.5,"interpolation":"catmullrom","data_points":[{"x":"0","y":"30","z":0}]},
                    {"channel":"position","time":0.25,"interpolation":"step","data_points":[{"x":0,"y":"16","z":0}]},
                    {"channel":"position","time":0.75,"interpolation":"linear","data_points":[{"x":0,"y":"math.sin(query.anim_time)","z":0}]}]}}}]}
            """.formatted(PNG);

    @TempDir Path data;

    private Path model(String namespace, String name, String content) throws IOException {
        Path file = data.resolve("models").resolve(namespace).resolve(name + ".bbmodel");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private RigPackBuilder.Result build() throws IOException { return RigPackBuilder.build(data.resolve("models"), data.resolve("pack/out"), RigPackBuilder.Limits.DEFAULT); }

    @Test
    void buildsAZipWithCustomModelDataForEveryBone() throws IOException {
        model("town", "golem", MODEL);
        RigPackBuilder.Result result = build();
        assertEquals(1, result.models());
        List<String> names = entries(result.zip());
        assertTrue(names.contains("pack.mcmeta"), names.toString());
        assertTrue(names.contains("assets/animus/items/rig_bone.json"), names.toString());
        assertTrue(names.contains("assets/town/models/rig/golem/body.json"), names.toString());
        assertTrue(names.contains("assets/town/models/rig/golem/head.json"), names.toString());
        assertTrue(names.contains("assets/town/textures/rig/golem/skin.png"), names.toString());
        assertEquals(names.stream().sorted().toList(), names, "entries are sorted");
        JsonObject items = JsonDocuments.object(entry(result.zip(), "assets/animus/items/rig_bone.json"));
        String dispatch = items.toString();
        assertTrue(dispatch.contains("minecraft:custom_model_data"), dispatch);
        assertTrue(dispatch.contains("\"town:golem/head\""), dispatch);
        JsonObject meta = JsonDocuments.object(entry(result.zip(), "pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(84, meta.get("min_format").getAsInt());
        assertEquals(97, meta.get("max_format").getAsInt());
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("bad") && problem.contains("rotation")), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("math.sin")), result.problems().toString());
    }

    @Test
    void writesARigAndAnimationsThatTheRuntimeAccepts() throws IOException {
        model("town", "golem", MODEL);
        build();
        Path documents = data.resolve("pack/out/documents");
        RigDocument.Result rig = RigDocument.parse("town:golem", Files.readString(documents.resolve("rigs/town/golem.json")));
        assertEquals(List.of(), rig.problems());
        RigDocument golem = rig.document().orElseThrow();
        assertEquals(List.of("body", "head"), golem.bones().stream().map(RigDocument.Bone::id).toList());
        assertEquals(0, golem.bones().get(1).parent());
        assertArrayEquals(new double[]{0, 1.25, 0}, golem.bones().get(1).pivot(), 1e-9);
        assertArrayEquals(new double[]{0, 0, 5}, golem.bones().get(1).rotation(), 1e-9);
        String components = golem.bones().get(1).visual().item().orElseThrow().components().orElseThrow();
        assertTrue(components.contains("custom_model_data={strings:[\"town:golem/head\"]}"), components);
        assertEquals("town:golem/walk", golem.animation("walk").orElseThrow());
        AnimationDocument.Result walk = AnimationDocument.parse("town:golem/walk", Files.readString(documents.resolve("animations/town/golem/walk.json")));
        assertEquals(List.of(), walk.problems());
        AnimationDocument clip = walk.document().orElseThrow();
        assertEquals(List.of(), clip.problemsAgainst(golem));
        assertEquals(20, clip.durationTicks());
        AnimationDocument.Track position = clip.tracks().stream().filter((AnimationDocument.Track track) -> track.target() == AnimationDocument.Target.TRANSLATION).findFirst().orElseThrow();
        assertArrayEquals(new double[]{0, 1, 0}, position.keyframes().getFirst().value(), 1e-9);
    }

    @Test
    void buildsAreByteForByteReproducible() throws IOException {
        model("town", "golem", MODEL);
        byte[] first = Files.readAllBytes(build().zip());
        byte[] second = Files.readAllBytes(build().zip());
        assertArrayEquals(first, second);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(data.resolve("pack/out/animus-rigs.zip")))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) assertEquals(ZipEntry.STORED, entry.getMethod());
        }
        assertTrue(Files.isRegularFile(data.resolve("pack/out/pack/pack.mcmeta")), "a mergeable folder is written too");
    }

    @Test
    void refusesOversizedFilesAndTexturesThatAreNotEmbedded() throws IOException {
        model("town", "linked", MODEL.replace("data:image/png;base64," + PNG, "https://example.com/skin.png"));
        Path big = model("town", "big", "{}");
        Files.write(big, new byte[(int) RigPackBuilder.Limits.DEFAULT.maximumFileBytes() + 1]);
        RigPackBuilder.Result result = build();
        assertEquals(0, result.models());
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("big.bbmodel") && problem.contains("bytes")), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch((String problem) -> problem.contains("linked.bbmodel") && problem.contains("embedded")), result.problems().toString());
        assertFalse(entries(result.zip()).stream().anyMatch((String name) -> name.contains("linked")));
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) names.add(entry.getName());
        }
        return names;
    }

    private static String entry(Path zip, String name) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
                if (entry.getName().equals(name)) return new String(readAll(input), StandardCharsets.UTF_8);
        }
        throw new AssertionError("No entry " + name);
    }
    private static byte[] readAll(InputStream input) throws IOException { return input.readAllBytes(); }
}
