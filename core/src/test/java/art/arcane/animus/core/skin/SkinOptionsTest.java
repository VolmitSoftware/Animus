package art.arcane.animus.core.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.SkinSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The animus:skin module, the PNG check and the [skins] settings. */
class SkinOptionsTest {
    static JsonObject npc(String modules) { return JsonParser.parseString("{\"modules\":" + modules + "}").getAsJsonObject(); }

    @Test void parse_readsBothOptionsAndRefusesUnknownFields() {
        assertEquals(Optional.of(new SkinOptions(true, false)), SkinOptions.parse(npc("{\"animus:skin\":{\"refresh\":true}}")));
        assertEquals(Optional.of(new SkinOptions(false, true)), SkinOptions.parse(npc("{\"animus:skin\":{\"fromName\":true}}")));
        assertEquals(Optional.empty(), SkinOptions.parse(npc("{\"animus:skin\":{\"enabled\":false,\"refresh\":true}}")));
        assertEquals(Optional.empty(), SkinOptions.parse(npc("{}")));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> SkinOptions.parse(npc("{\"animus:skin\":{\"player\":\"Notch\"}}")));
        assertEquals("$.modules.animus:skin.player: unknown field", unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SkinOptions.parse(npc("{\"animus:skin\":{\"refresh\":\"yes\"}}")));
    }

    @Test void set_addsAndClearsOptionsAndDropsAnEmptyModule() {
        JsonObject document = npc("{\"animus:mirror\":{}}");
        SkinOptions.set(document, "refresh", true);
        assertEquals("{\"animus:mirror\":{},\"animus:skin\":{\"refresh\":true}}", document.get("modules").toString());
        SkinOptions.set(document, "refresh", false);
        assertEquals("{\"animus:mirror\":{}}", document.get("modules").toString());
        JsonObject bare = new JsonObject();
        SkinOptions.set(bare, "fromName", false);
        assertFalse(bare.has("modules"), "turning off what was never on writes nothing");
    }

    @Test void png_checksSignatureAndSize() {
        assertEquals(Optional.empty(), SkinPng.problem(MineSkinTest.png(64, 64)));
        assertEquals(Optional.empty(), SkinPng.problem(MineSkinTest.png(64, 32)));
        assertEquals(Optional.of("The PNG is 64x48; a skin is 64x64 (or the old 64x32)"), SkinPng.problem(MineSkinTest.png(64, 48)));
        assertTrue(SkinPng.problem(new byte[SkinFile.MAXIMUM_IMAGE + 1]).orElseThrow().contains("at most 20000 bytes"));
    }

    @Test void settings_boundTheRefreshAndNeverPrintTheKey() {
        assertTrue(new SkinSettings(" msk_secret ", 30).keyed());
        assertEquals("msk_secret", new SkinSettings(" msk_secret ", 30).mineskinKey());
        assertFalse(new SkinSettings("msk_secret", 30).toString().contains("msk_secret"));
        assertThrows(IllegalArgumentException.class, () -> new SkinSettings("", 1));
        assertThrows(IllegalArgumentException.class, () -> new SkinSettings("a\nb", 30));
    }
}
