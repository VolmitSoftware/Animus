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
        assertTrue(BodyVariant.FIELDS.keySet().containsAll(Set.of("horse", "llama", "frog", "armadillo", "dragon", "sheep", "villager", "zombieVillager", "player", "fallingBlock", "textDisplay")));
        assertEquals("minecraft:ender_dragon", BodyVariant.FIELDS.get("dragon").type());
        assertEquals("minecraft:tropical_fish", BodyVariant.FIELDS.get("tropicalFish").type());
        assertEquals(BodyVariant.FIELDS.size(), BodyVariant.types().size());
        assertEquals("sheep", BodyVariant.fieldOf("minecraft:sheep").orElseThrow().field());
    }

    @Test void mobSettingsParseWithTheirWireIndexes() throws IOException {
        BodyVariant sheep = variant("minecraft:sheep", "\"sheep\":{\"color\":\"red\",\"sheared\":true}");
        assertEquals(14, sheep.index("color"));
        assertTrue(sheep.flag("sheared"));
        BodyVariant villager = variant("minecraft:villager", "\"villager\":{\"type\":\"desert\",\"profession\":\"librarian\",\"level\":\"master\"}");
        assertEquals(Map.of("type", "desert", "profession", "librarian", "level", "master"), villager.choices());
        assertEquals(4, villager.index("level"));
        assertEquals("cold", variant("minecraft:cow", "\"cow\":{\"variant\":\"cold\"}").choices().get("variant"));
        assertEquals(1, variant("minecraft:player", "\"player\":{\"cape\":\"hidden\"}").index("cape"));
    }

    @Test void numberSettingsTakeWholeNumbersInRange() throws IOException {
        assertEquals(4, variant("minecraft:slime", "\"slime\":{\"size\":4}").number("size", 0));
        assertEquals(7, variant("minecraft:slime", "").number("size", 7));
        assertEquals(List.of("$.slime.size: expected a whole number from 1 to 127"), NpcAppearanceTest.errors("minecraft:slime", "\"slime\":{\"size\":0}"));
        assertEquals(List.of("$.slime.size: expected a whole number from 1 to 127"), NpcAppearanceTest.errors("minecraft:slime", "\"slime\":{\"size\":2.5}"));
        assertEquals(List.of("$.phantom.size: expected a whole number from 0 to 64"), NpcAppearanceTest.errors("minecraft:phantom", "\"phantom\":{\"size\":\"3\"}"));
    }
}
