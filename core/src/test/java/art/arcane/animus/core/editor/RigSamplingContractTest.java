package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.rig.AnimationDocument;
import art.arcane.animus.core.rig.RigDocument;
import art.arcane.animus.core.rig.RigSampler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Java sampler against every golden sample the Dart sampler also checks. */
final class RigSamplingContractTest {
    @Test
    void javaSamplerMatchesEveryGoldenSample() throws IOException {
        JsonObject index = SharedFixtures.object(SharedFixtures.fixture("index.json"));
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (JsonElement listed : index.getAsJsonArray("samples")) {
            String samplePath = listed.getAsString();
            JsonObject sample = SharedFixtures.object(SharedFixtures.fixture(samplePath));
            Path folder = SharedFixtures.fixture(samplePath).getParent().getParent();
            RigDocument rig = rig(folder.resolve(sample.get("rig").getAsString()));
            List<RigSampler.Layer> layers = new ArrayList<>();
            for (JsonElement layerElement : sample.getAsJsonArray("layers")) {
                JsonObject layer = layerElement.getAsJsonObject();
                AnimationDocument animation = animation(folder.resolve(layer.get("animation").getAsString()));
                layers.add(RigSampler.Layer.of(animation, layer.get("speed").getAsDouble()));
            }
            double yaw = sample.get("yaw").getAsDouble();
            double blocks = sample.getAsJsonObject("tolerance").get("blocks").getAsDouble();
            double degrees = sample.getAsJsonObject("tolerance").get("degrees").getAsDouble();
            for (JsonElement tickElement : sample.getAsJsonArray("samples")) {
                JsonObject expected = tickElement.getAsJsonObject();
                double tick = expected.get("tick").getAsDouble();
                List<RigSampler.Layer> timed = layers.stream().map((RigSampler.Layer layer) -> layer.at(tick)).toList();
                RigSampler.Pose pose = RigSampler.sample(rig, timed, yaw);
                for (Map.Entry<String, JsonElement> bone : expected.getAsJsonObject("bones").entrySet()) {
                    String label = samplePath + " tick " + tick + " " + bone.getKey();
                    RigSampler.BonePose actual = pose.bone(bone.getKey());
                    JsonObject wanted = bone.getValue().getAsJsonObject();
                    compare(mismatches, label + " rotation", wanted.getAsJsonArray("rotation"), actual.rotation(), degrees);
                    compare(mismatches, label + " translation", wanted.getAsJsonArray("translation"), actual.translation(), blocks);
                    compare(mismatches, label + " scale", wanted.getAsJsonArray("scale"), actual.scale(), blocks);
                    if (wanted.get("visible").getAsBoolean() != actual.visible()) mismatches.add(label + " visible");
                    compare(mismatches, label + " model", wanted.getAsJsonArray("model"), actual.model().values(), blocks);
                    if (wanted.has("visual")) compare(mismatches, label + " visual", wanted.getAsJsonArray("visual"), actual.visual().orElseThrow().values(), blocks);
                    checked++;
                }
            }
        }
        assertTrue(mismatches.isEmpty(), () -> String.join("\n", mismatches.subList(0, Math.min(40, mismatches.size()))));
        assertTrue(checked > 100, "expected every golden bone sample to be checked");
    }

    @Test
    void sampleFixtureDocumentsParseAndAgree() throws IOException {
        Path folder = SharedFixtures.fixture("animation");
        RigDocument rig = rig(folder.resolve("rigs/golem.json"));
        for (String clip : List.of("clips/golem-walk.json", "clips/golem-wave.json", "clips/golem-bow.json")) {
            AnimationDocument animation = animation(folder.resolve(clip));
            assertEquals(List.of(), animation.problemsAgainst(rig), clip);
        }
    }

    private static RigDocument rig(Path path) throws IOException {
        RigDocument.Result result = RigDocument.parse(null, SharedFixtures.text(path));
        return result.document().orElseThrow(() -> new AssertionError(path + " " + result.problems()));
    }

    private static AnimationDocument animation(Path path) throws IOException {
        AnimationDocument.Result result = AnimationDocument.parse(null, SharedFixtures.text(path));
        return result.document().orElseThrow(() -> new AssertionError(path + " " + result.problems()));
    }

    private static void compare(List<String> mismatches, String label, JsonArray expected, double[] actual, double tolerance) {
        if (expected.size() != actual.length) { mismatches.add(label + " length " + actual.length); return; }
        for (int i = 0; i < actual.length; i++) {
            double wanted = expected.get(i).getAsDouble();
            if (Math.abs(wanted - actual[i]) > tolerance) { mismatches.add(label + "[" + i + "] expected " + wanted + " got " + actual[i]); return; }
        }
    }
}
