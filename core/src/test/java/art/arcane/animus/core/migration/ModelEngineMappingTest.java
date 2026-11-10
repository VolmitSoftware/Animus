package art.arcane.animus.core.migration;

import art.arcane.animus.core.integration.ModelEngineModule;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelEngineMappingTest {
    private static JsonElement json(String text) { return JsonParser.parseString(text); }
    private static ModelEngineModule module(ModelEngineMapping.Result result) {
        JsonObject document = new JsonObject();
        JsonObject modules = new JsonObject();
        modules.add(ModelEngineModule.MODULE, result.module().orElseThrow());
        document.add("modules", modules);
        return ModelEngineModule.parse(document).orElseThrow();
    }

    @Test void modelEngineFourSavesTheModeledEntityAsJsonText() {
        JsonObject trait = new JsonObject();
        trait.addProperty("model_data", """
                {"version":"R4.0.3","base_visible":false,"rotation_locked":false,"head_y":0.0,"models":[{"blueprint":"kindletronsr","render_scale":1.5,
                 "hitbox_scale":1.5,"can_hurt":true,"animation_handler":{"id":"state_machine"}}]}""");
        ModelEngineMapping.Result result = ModelEngineMapping.map(trait);
        assertEquals(new ModelEngineModule("kindletronsr", 1.5, false, true, Optional.empty(), Map.of()), module(result));
        assertEquals(List.of(), result.warnings());
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/meg_model").status());
    }

    @Test void modelEngineThreeKeysModelsByIdWithTheirDefaultStates() {
        ModelEngineMapping.Result result = ModelEngineMapping.map(json("""
                {"models":{"drake":{"defaultStates":{"IDLE":{"stateId":"breathe"},"WALK":{"stateId":"run"},"DEATH":{"stateId":"death"}},"states":{}}},
                 "isBaseEntityVisible":true}"""));
        assertEquals(new ModelEngineModule("drake", 1, true, true, Optional.empty(),
                Map.of(ModelEngineModule.State.IDLE, "breathe", ModelEngineModule.State.WALK, "run")), module(result));
    }

    @Test void onlyTheFirstOfSeveralModelsIsShown() {
        ModelEngineMapping.Result result = ModelEngineMapping.map(json("{\"models\":[{\"blueprint\":\"body\"},{\"blueprint\":\"hat\"}]}"));
        assertEquals("body", module(result).model());
        assertEquals(List.of("Model Engine showed 2 models (body, hat); Animus shows the first, body"), result.warnings());
    }

    @Test void unreadableDataStaysForReview() {
        ModelEngineMapping.Result result = ModelEngineMapping.map(json("{\"something\":\"else\"}"));
        assertTrue(result.module().isEmpty());
        assertEquals(List.of("Model Engine data names no model this converter can read; kept in citizens:review"), result.warnings());
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/traits/meg_model").status());
    }

    @Test void aModelIdAnimusCannotStoreStaysForReview() {
        ModelEngineMapping.Result result = ModelEngineMapping.map(json("{\"models\":[{\"blueprint\":\"has space\"}]}"));
        assertTrue(result.module().isEmpty());
        assertEquals(List.of("Model Engine model 'has space' is not a valid Animus model id; kept in citizens:review"), result.warnings());
    }
}
