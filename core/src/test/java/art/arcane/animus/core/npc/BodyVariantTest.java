package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BodyVariantTest {
    private static BodyVariant variant(String type, String extra) throws IOException {
        return NpcAppearance.of(NpcDefinition.parse(JsonDocuments.write(NpcAppearanceTest.definition(type, extra)))).traits().variant();
    }

    @Test void horseCoatParsesToItsWireIds() throws IOException {
        BodyVariant horse = variant("minecraft:horse", "\"horse\":{\"color\":\"chestnut\",\"markings\":\"white_dots\"}");
        assertEquals(Map.of("color", "chestnut", "markings", "white_dots"), horse.choices());
        assertEquals(2, horse.index("color"));
        assertEquals(3, horse.index("markings"));
        assertEquals(2 | 3 << 8, BodyVariant.horseVariant(horse));
    }

    @Test void undeclaredSettingsKeepTheClientDefaults() throws IOException {
        BodyVariant plain = variant("minecraft:horse", "");
        assertEquals(BodyVariant.NONE, plain);
        assertEquals(-1, plain.index("color"));
        assertEquals(0, BodyVariant.horseVariant(variant("minecraft:horse", "\"horse\":{}")));
        assertEquals(4 << 8, BodyVariant.horseVariant(variant("minecraft:horse", "\"horse\":{\"markings\":\"black_dots\"}")));
    }

    @Test void llamaFrogArmadilloAndDragonSettingsParse() throws IOException {
        BodyVariant llama = variant("minecraft:llama", "\"llama\":{\"variant\":\"gray\",\"chest\":true}");
        assertEquals(3, llama.index("variant"));
        assertEquals(Set.of("chest"), llama.flags());
        assertEquals("cold", variant("minecraft:frog", "\"frog\":{\"variant\":\"cold\"}").choices().get("variant"));
        assertEquals(1, variant("minecraft:armadillo", "\"armadillo\":{\"state\":\"rolling\"}").index("state"));
        assertEquals("sitting", variant("minecraft:ender_dragon", "\"dragon\":{\"phase\":\"sitting\"}").choices().get("phase"));
    }

    @Test void settingsOfAnotherBodyAndUnknownValuesAreRefused() {
        assertEquals(List.of("$.horse: available only for minecraft:horse bodies"),
                NpcAppearanceTest.errors("minecraft:player", "\"horse\":{\"color\":\"white\"}"));
        assertEquals(List.of("$.horse.color: expected white, creamy, chestnut, brown, black, gray or dark_brown", "$.horse.saddle: unknown field"),
                NpcAppearanceTest.errors("minecraft:horse", "\"horse\":{\"color\":\"pink\",\"saddle\":true}"));
        assertEquals(List.of("$.llama.chest: expected boolean"), NpcAppearanceTest.errors("minecraft:llama", "\"llama\":{\"chest\":1}"));
        assertEquals(List.of("$.frog: expected object"), NpcAppearanceTest.errors("minecraft:frog", "\"frog\":\"warm\""));
    }

    @Test void everyBodyTypeNamesItsOwnField() {
        assertEquals(Set.of("horse", "llama", "frog", "armadillo", "dragon"), BodyVariant.FIELDS.keySet());
        assertEquals("minecraft:ender_dragon", BodyVariant.FIELDS.get("dragon").type());
    }
}
