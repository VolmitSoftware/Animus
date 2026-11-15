package art.arcane.animus.core.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Citizens' skin trait: the texture, updateSkins, fetchDefaultSkin and placeholder skin names carried over. */
class CitizensSkinsTest {
    static JsonObject trait(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void map_savedTextureByName_keepsTheAccountAndFollowsWithUpdateSkins() throws IOException {
        CitizensSkins.Result result = CitizensSkins.map(trait("{\"skinName\":\"Notch\",\"textureRaw\":\"dGV4\",\"signature\":\"c2ln\",\"updateSkins\":true,\"fetchDefaultSkin\":true}"), "Guard");
        JsonObject file = JsonDocuments.object(result.file().orElseThrow());
        assertEquals("{\"textureRaw\":\"dGV4\",\"signature\":\"c2ln\",\"label\":\"Notch\",\"player\":\"Notch\"}", file.toString(), "Citizens' own keys are not copied into the skin file");
        assertEquals("{\"refresh\":true}", result.options().toString());
        assertFalse(result.mirror());
    }

    @Test void map_mineSkinTexture_hasNoAccountToFollow() throws IOException {
        CitizensSkins.Result result = CitizensSkins.map(trait("{\"skinName\":\"5f2c4a0b9e3d4c1f8a7b6c5d4e3f2a1b\",\"textureRaw\":\"dGV4\",\"signature\":\"c2ln\",\"updateSkins\":true}"), "Guard");
        assertEquals("{\"textureRaw\":\"dGV4\",\"signature\":\"c2ln\"}", JsonDocuments.object(result.file().orElseThrow()).toString());
        assertTrue(result.options().isEmpty(), "a generated skin's name is no account");
    }

    @Test void map_noTraitOrNoTexture_takesTheSkinFromTheNpcsName() {
        assertEquals("{\"fromName\":true}", CitizensSkins.map(null, "&6Notch").options().toString(), "Citizens dresses a player NPC by its name by default");
        assertTrue(CitizensSkins.map(null, "Bench Sitter").options().isEmpty(), "a name with a space is no account");
        assertEquals("{\"fromName\":true,\"refresh\":true}", CitizensSkins.map(trait("{\"skinName\":\"notch\",\"updateSkins\":true}"), "Notch").options().toString());
        assertTrue(CitizensSkins.map(trait("{\"fetchDefaultSkin\":false}"), "Notch").options().isEmpty(), "fetchDefaultSkin false keeps the default look");
        assertTrue(CitizensSkins.map(trait("{\"skinName\":\"Notch\",\"textureRaw\":\"cache\"}"), "Notch").file().isEmpty(), "a texture of cache is none");
    }

    @Test void map_otherNameWithoutTexture_isLeftForReview() {
        CitizensSkins.Result result = CitizensSkins.map(trait("{\"skinName\":\"Dinnerbone\"}"), "Guard");
        assertTrue(result.options().isEmpty());
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/traits/skintrait/skinName").status());
        assertTrue(result.notes().get("/traits/skintrait/skinName").reason().contains("/animus npc skin <npc> Dinnerbone"));
    }

    @Test void map_viewerPlaceholder_becomesTheMirror() {
        CitizensSkins.Result result = CitizensSkins.map(trait("{\"skinName\":\"%player%\",\"textureRaw\":\"dGV4\"}"), "Mirror");
        assertTrue(result.mirror());
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/skintrait/skinName").status());
        assertTrue(CitizensSkins.map(trait("{\"skinName\":\"<player>\"}"), "Notch").options().isEmpty(), "no name lookup for a mirror");
    }

    @Test void map_placeholderApiName_staysTheSourceAndRefreshes() throws IOException {
        CitizensSkins.Result result = CitizensSkins.map(trait("{\"skinName\":\"%vault_top_1%\",\"textureRaw\":\"dGV4\",\"signature\":\"c2ln\"}"), "Champion");
        JsonObject file = JsonDocuments.object(result.file().orElseThrow());
        assertEquals("%vault_top_1%", file.get("player").getAsString());
        assertFalse(file.has("label"));
        assertEquals("{\"refresh\":true}", result.options().toString(), "Citizens fills it again every few minutes");
    }

    @Test void map_npcPlaceholder_isTheNpcsName() throws IOException {
        CitizensSkins.Result result = CitizensSkins.map(trait("{\"skinName\":\"<npc>\",\"textureRaw\":\"dGV4\",\"signature\":\"c2ln\"}"), "&bSteve");
        assertEquals("Steve", JsonDocuments.object(result.file().orElseThrow()).get("player").getAsString());
    }
}
