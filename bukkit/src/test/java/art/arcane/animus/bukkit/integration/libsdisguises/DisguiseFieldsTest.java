package art.arcane.animus.bukkit.integration.libsdisguises;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcDisguise;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisguiseFieldsTest {
    static final String NPC = """
            {"schemaVersion":1,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"","name":"Guard","type":"minecraft:player","spawned":false,
             "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"disguise":"x"}
            """;

    private static JsonObject worn(DisguiseFields fields) {
        NpcDisguise.Applied applied = NpcDisguise.apply(JsonParser.parseString(NPC).getAsJsonObject(), fields.body());
        assertTrue(NpcDefinition.validate(applied.definition()).isEmpty(), applied.notes().toString());
        return applied.definition();
    }

    @Test
    void bukkitNamesBecomeAnimusVariantValues() {
        DisguiseFields horse = new DisguiseFields("minecraft:horse");
        horse.horse("DARK_BROWN", "WHITEFIELD");
        JsonObject coat = worn(horse).getAsJsonObject("horse");
        assertEquals("dark_brown", coat.get("color").getAsString());
        assertEquals("white_field", coat.get("markings").getAsString());

        DisguiseFields llama = new DisguiseFields("minecraft:llama");
        llama.llama("GRAY", true);
        llama.equipment("body", "minecraft:red_carpet", 1, Optional.empty());
        JsonObject wool = worn(llama);
        assertEquals("gray", wool.getAsJsonObject("llama").get("variant").getAsString());
        assertTrue(wool.getAsJsonObject("llama").get("chest").getAsBoolean());
        assertEquals("minecraft:red_carpet", wool.getAsJsonObject("equipment").getAsJsonObject("body").get("id").getAsString());

        DisguiseFields frog = new DisguiseFields("minecraft:frog");
        frog.frog("minecraft:cold");
        assertEquals("cold", worn(frog).getAsJsonObject("frog").get("variant").getAsString());

        DisguiseFields armadillo = new DisguiseFields("minecraft:armadillo");
        armadillo.armadillo("SCARED");
        assertEquals("scared", worn(armadillo).getAsJsonObject("armadillo").get("state").getAsString());
    }

    @Test
    void flagsGlowAndPoseMapToTraits() {
        DisguiseFields zombie = new DisguiseFields("minecraft:zombie");
        zombie.baby();
        zombie.glowing(Optional.of("DARK_AQUA"));
        zombie.floating();
        zombie.name("Bob");
        zombie.nameplate(false);
        zombie.equipment("main_hand", "minecraft:iron_sword", 1, Optional.of("[]"));
        JsonObject worn = worn(zombie);
        assertEquals("minecraft:zombie", worn.get("type").getAsString());
        assertTrue(worn.get("baby").getAsBoolean());
        assertTrue(worn.get("glowing").getAsBoolean());
        assertEquals("dark_aqua", worn.get("teamColor").getAsString());
        assertFalse(worn.get("gravity").getAsBoolean());
        assertEquals("Bob", worn.get("name").getAsString());
        assertFalse(worn.getAsJsonObject("nameplate").get("visible").getAsBoolean());
        assertFalse(worn.getAsJsonObject("equipment").getAsJsonObject("main_hand").has("components"));

        DisguiseFields player = new DisguiseFields("minecraft:player");
        player.pose("CROUCHING");
        player.pose("FALL_FLYING");
        assertEquals("sneaking", worn(player).get("pose").getAsString());
        assertEquals(List.of("setEntityPose fall_flying"), player.body().unsupported());
    }

    @Test
    void dragonPhasesAndColoursWithoutAnAnimusValueAreReported() {
        DisguiseFields dragon = new DisguiseFields("minecraft:ender_dragon");
        dragon.dragon(10);
        assertEquals("hovering", worn(dragon).getAsJsonObject("dragon").get("phase").getAsString());
        DisguiseFields circling = new DisguiseFields("minecraft:ender_dragon");
        circling.dragon(0);
        circling.glowing(Optional.of("MAGIC"));
        assertEquals(List.of("setPhase 0", "setGlowColor magic"), circling.body().unsupported());
        assertFalse(worn(circling).has("teamColor"));
    }

    @Test
    void armorStandAnglesAreDegreesWithinOneTurn() {
        DisguiseFields stand = new DisguiseFields("minecraft:armor_stand");
        stand.armorStand(true, true, false, false, Map.of(ArmorStandLook.Part.HEAD, new double[]{Math.PI / 2, 0, 3 * Math.PI}));
        JsonObject look = worn(stand).getAsJsonObject("armorStand");
        assertTrue(look.get("small").getAsBoolean());
        assertTrue(look.get("arms").getAsBoolean());
        assertFalse(look.get("basePlate").getAsBoolean());
        assertEquals(90.0, look.getAsJsonArray("head").get(0).getAsDouble());
        assertEquals(180.0, Math.abs(look.getAsJsonArray("head").get(2).getAsDouble()));
    }

    @Test
    void carriedBlocksItemsAndTextBecomeTheBodyField() {
        DisguiseFields sand = new DisguiseFields("minecraft:falling_block");
        sand.block("fallingBlock", "minecraft:sand");
        JsonObject falling = worn(sand);
        assertEquals("minecraft:falling_block", falling.get("type").getAsString());
        assertEquals(JsonParser.parseString("{\"block\":\"minecraft:sand\"}"), falling.get("fallingBlock"));

        DisguiseFields display = new DisguiseFields("minecraft:block_display");
        display.block("blockDisplay", "minecraft:oak_stairs[facing=east]");
        assertEquals("minecraft:oak_stairs[facing=east]", worn(display).getAsJsonObject("blockDisplay").get("block").getAsString());

        DisguiseFields apple = new DisguiseFields("minecraft:item");
        apple.item("droppedItem", "minecraft:apple", 3, Optional.empty());
        assertEquals("minecraft:apple", worn(apple).getAsJsonObject("droppedItem").getAsJsonObject("item").get("id").getAsString());

        DisguiseFields sword = new DisguiseFields("minecraft:item_display");
        sword.item("itemDisplay", "minecraft:diamond_sword", 1, Optional.empty());
        assertEquals("minecraft:diamond_sword", worn(sword).getAsJsonObject("itemDisplay").getAsJsonObject("item").get("id").getAsString());

        DisguiseFields sign = new DisguiseFields("minecraft:text_display");
        sign.text("&aWelcome");
        assertEquals("&aWelcome", worn(sign).getAsJsonObject("textDisplay").get("text").getAsString());
    }
}
