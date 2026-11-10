package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitizensLooksTest {
    private static final String SELF = "11111111-2222-4333-8444-555555555555";

    private static CitizensLooks.Result map(String type, String traits) throws IOException {
        return CitizensLooks.map(type, JsonDocuments.object(traits), SELF, true);
    }

    @Test void mobTraitsBecomeTheBodysSettings() throws IOException {
        CitizensLooks.Result sheep = map("minecraft:sheep", "{\"sheeptrait\":{\"color\":\"RED\",\"sheared\":true}}");
        assertEquals(JsonDocuments.object("{\"sheep\":{\"color\":\"red\",\"sheared\":true}}"), sheep.fields());
        assertTrue(sheep.converted().containsAll(List.of("/traits/sheeptrait/color", "/traits/sheeptrait/sheared")));
        CitizensLooks.Result villager = map("minecraft:villager", "{\"profession\":\"LIBRARIAN\",\"villagertrait\":{\"type\":\"minecraft:desert\",\"level\":5}}");
        assertEquals(JsonDocuments.object("{\"villager\":{\"profession\":\"librarian\",\"type\":\"desert\",\"level\":\"master\"}}"), villager.fields());
        assertEquals(JsonDocuments.object("{\"rabbit\":{\"type\":\"evil\"}}"), map("minecraft:rabbit", "{\"rabbittype\":{\"type\":\"THE_KILLER_BUNNY\"}}").fields());
        assertEquals(JsonDocuments.object("{\"creeper\":{\"powered\":true}}"), map("minecraft:creeper", "{\"powered\":true}").fields());
    }

    @Test void citizensDefaultsApplyWhenTheTraitSavesNoValue() throws IOException {
        assertEquals(JsonDocuments.object("{\"cat\":{\"variant\":\"black\"}}"), map("minecraft:cat", "{\"cattrait\":{}}").fields());
        assertEquals(JsonDocuments.object("{\"slime\":{\"size\":3}}"), map("minecraft:slime", "{\"slimesize\":{}}").fields());
        assertEquals(JsonDocuments.object("{\"parrot\":{\"variant\":\"yellow_blue\"}}"), map("minecraft:parrot", "{\"parrottrait\":{\"variant\":\"CYAN\"}}").fields());
    }

    @Test void horseModifiersShowTheCoatAndChest() throws IOException {
        CitizensLooks.Result horse = map("minecraft:horse", "{\"horsemodifiers\":{\"color\":\"BLACK\",\"style\":\"WHITEFIELD\",\"tamed\":true}}");
        assertEquals(JsonDocuments.object("{\"horse\":{\"color\":\"black\",\"markings\":\"white_field\"}}"), horse.fields());
        assertEquals(CitizensImporter.Status.CONVERTED, horse.notes().get("/traits/horsemodifiers/tamed").status());
        assertEquals(JsonDocuments.object("{\"mule\":{\"chest\":true}}"), map("minecraft:mule", "{\"horsemodifiers\":{\"carryingChest\":true}}").fields());
    }

    @Test void aTraitForAnotherBodyIsKeptWithItsReason() throws IOException {
        CitizensLooks.Result looks = map("minecraft:player", "{\"sheeptrait\":{\"color\":\"RED\"}}");
        assertEquals(new JsonObject(), looks.fields());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, looks.notes().get("/traits/sheeptrait").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("minecraft:player", "{\"paintingtrait\":{\"art\":\"KEBAB\"}}").notes().get("/traits/paintingtrait").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("minecraft:player", "{\"boattrait\":{\"type\":\"OAK\"}}").notes().get("/traits/boattrait").status());
    }

    @Test void wolfCollarShowsOnlyWhenTamed() throws IOException {
        assertEquals(JsonDocuments.object("{\"wolf\":{\"collar\":\"blue\",\"sitting\":true}}"),
                map("minecraft:wolf", "{\"wolfmodifiers\":{\"collarColor\":\"BLUE\",\"tamed\":true,\"sitting\":true}}").fields());
        CitizensLooks.Result wild = map("minecraft:wolf", "{\"wolfmodifiers\":{\"collarColor\":\"BLUE\"}}");
        assertEquals(new JsonObject(), wild.fields());
        assertEquals(CitizensImporter.Status.CONVERTED, wild.notes().get("/traits/wolfmodifiers/collarColor").status());
    }

    @Test void skinLayersBossBarsFiltersAndRedirects() throws IOException {
        CitizensLooks.Result looks = map("minecraft:player", """
                {"skinlayers":{"cape":false,"hat":true},
                 "bossbar":{"title":"&cKing","color":"RED","style":"SEGMENTED_10","flags":["DARKEN_SKY"],"range":-1,"track":"health","viewPermission":"vip.boss"},
                 "playerfilter":{"mode":"ALLOWLIST","players":["8667BA71-B85A-4004-AF54-457A9734EED7"],"groups":["vip"]},
                 "clickredirecttrait":{"uuid":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"}}""");
        assertEquals(JsonDocuments.object("""
                {"player":{"cape":"hidden"},
                 "bossBar":{"title":"&cKing","color":"red","style":"segmented_10","flags":["darken_sky"],"progress":"health","permission":"vip.boss"},
                 "viewers":{"mode":"allow","players":["8667ba71-b85a-4004-af54-457a9734eed7"],"groups":["vip"]},
                 "clickRedirect":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"}"""), looks.fields());
        assertEquals(new JsonObject(), map("minecraft:player", "{\"bossbar\":{\"visible\":false,\"title\":\"x\"}}").fields());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED,
                map("minecraft:player", "{\"clickredirecttrait\":{\"uuid\":\"" + SELF + "\"}}").notes().get("/traits/clickredirecttrait/uuid").status());
    }

    @Test void nonLivingAndLaterTraitsBecomeBodySettings() throws IOException {
        assertEquals(JsonDocuments.object("{\"painting\":{\"variant\":\"minecraft:kebab\"}}"), map("minecraft:painting", "{\"paintingtrait\":{\"art\":\"KEBAB\"}}").fields());
        assertEquals(JsonDocuments.object("{\"textDisplay\":{\"billboard\":\"center\",\"text\":\"&aHi\",\"alignment\":\"left\",\"shadowed\":true}}"),
                map("minecraft:text_display", "{\"textdisplaytrait\":{\"text\":\"&aHi\",\"alignment\":\"LEFT\",\"shadowed\":true},\"displaytrait\":{\"billboard\":\"CENTER\"}}").fields());
        assertEquals(JsonDocuments.object("{\"endCrystal\":{\"base\":\"hidden\"}}"), map("minecraft:end_crystal", "{\"endercrystaltrait\":{}}").fields());
        assertEquals(JsonDocuments.object("{\"wither\":{\"invulnerableTicks\":20,\"armored\":true}}"), map("minecraft:wither", "{\"withertrait\":{\"arrowshield\":true}}").fields());
        assertEquals(JsonDocuments.object("{\"camel\":{\"sitting\":true}}"), map("minecraft:camel", "{\"cameltrait\":{\"pose\":\"SITTING\"}}").fields());
        assertEquals(JsonDocuments.object("{\"player\":{\"gameMode\":\"spectator\"}}"), map("minecraft:player", "{\"gamemodetrait\":{\"mode\":\"SPECTATOR\"}}").fields());
    }
}
