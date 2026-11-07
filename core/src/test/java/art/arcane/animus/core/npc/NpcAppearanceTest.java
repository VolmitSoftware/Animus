package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcAppearanceTest {
    static JsonObject definition(String type, String extra) {
        String base = """
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"guard","group":"town",
                 "name":"Captain of the Northern Watch","type":"%s","spawned":false,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}%s}
                """;
        return JsonParser.parseString(base.formatted(type, extra.isEmpty() ? "" : "," + extra)).getAsJsonObject();
    }
    static List<String> errors(String type, String extra) { return NpcDefinition.validate(definition(type, extra)); }

    @Test void declaredEquipmentHologramPoseAndOwnerParse() throws IOException {
        NpcDefinition parsed = NpcDefinition.parse(JsonDocuments.write(definition("minecraft:player", """
                "equipment":{"main_hand":{"id":"minecraft:iron_sword"},"head":{"id":"minecraft:player_head","count":1,"components":"[profile={name:\\"Steve\\"}]"},
                             "saddle":{"id":"minecraft:saddle"}},
                "hologram":{"lines":["&6Quest giver","Right-click to talk"]},
                "nameplate":{"visible":true},
                "pose":"sitting",
                "owner":"00000000-0000-0000-0000-00000000000a"
                """)));
        NpcAppearance appearance = NpcAppearance.of(parsed);
        assertEquals(Map.of(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:iron_sword", 1, Optional.empty(), Optional.empty()),
                EquipmentSlot.HEAD, new NpcItem("minecraft:player_head", 1, Optional.of("[profile={name:\"Steve\"}]"), Optional.empty()),
                EquipmentSlot.SADDLE, new NpcItem("minecraft:saddle", 1, Optional.empty(), Optional.empty())), appearance.equipment());
        assertEquals(List.of("&6Quest giver", "Right-click to talk"), appearance.hologram());
        assertTrue(appearance.nameplateVisible());
        assertEquals(NpcPose.SITTING, appearance.pose());
        assertTrue(appearance.armorStand().isEmpty());
        assertEquals(Optional.of(UUID.fromString("00000000-0000-0000-0000-00000000000a")), parsed.owner());
    }

    @Test void bareEquipmentItemIdsAreMinecraftItems() throws IOException {
        String extra = "\"equipment\":{\"main_hand\":{\"id\":\"netherite_axe\"},\"head\":{\"id\":\"minecraft:iron_helmet\"}}";
        assertEquals(List.of(), errors("minecraft:player", extra));
        NpcAppearance appearance = NpcAppearance.of(NpcDefinition.parse(JsonDocuments.write(definition("minecraft:player", extra))));
        assertEquals(new NpcItem("minecraft:netherite_axe", 1, Optional.empty(), Optional.empty()), appearance.equipment().get(EquipmentSlot.MAIN_HAND));
        assertEquals(new NpcItem("minecraft:iron_helmet", 1, Optional.empty(), Optional.empty()), appearance.equipment().get(EquipmentSlot.HEAD));
    }

    @Test void bareEntityTypesAreMinecraftTypes() throws IOException {
        assertEquals(List.of(), errors("villager", ""));
        assertEquals("minecraft:villager", NpcDefinition.parse(JsonDocuments.write(definition("villager", ""))).json().get("type").getAsString());
        assertEquals("minecraft:villager", NpcDefinition.parse(JsonDocuments.write(definition("minecraft:villager", ""))).json().get("type").getAsString());
        assertFalse(errors(":villager", "").isEmpty());
        assertFalse(errors("Villager", "").isEmpty(), "types are written in lowercase");
    }

    @Test void absentFieldsGiveAPlainStandingBody() throws IOException {
        NpcDefinition parsed = NpcDefinition.parse(JsonDocuments.write(definition("minecraft:horse", "")));
        NpcAppearance appearance = NpcAppearance.of(parsed);
        assertTrue(appearance.equipment().isEmpty());
        assertTrue(appearance.hologram().isEmpty());
        assertTrue(appearance.nameplateVisible());
        assertEquals(NpcPose.STANDING, appearance.pose());
        assertEquals(Optional.empty(), parsed.owner());
        assertTrue(NpcInventory.of(parsed).slots().isEmpty());
    }

    @Test void equipmentErrorsNameTheExactField() {
        assertEquals(List.of("$.equipment.hand: unknown slot; expected main_hand, off_hand, head, chest, legs, feet, body or saddle"),
                errors("minecraft:player", "\"equipment\":{\"hand\":{\"id\":\"minecraft:stone\"}}"));
        assertEquals(List.of("$.equipment.head.id: expected namespace:item"), errors("minecraft:player", "\"equipment\":{\"head\":{\"id\":\"Stone Block\"}}"));
        assertEquals(List.of("$.equipment.head.count: expected integer 1..99"), errors("minecraft:player", "\"equipment\":{\"head\":{\"id\":\"minecraft:stone\",\"count\":100}}"));
        assertEquals(List.of("$.equipment.head.components: expected [component=value,...] of at most 4096 characters"),
                errors("minecraft:player", "\"equipment\":{\"head\":{\"id\":\"minecraft:stone\",\"components\":\"glint\"}}"));
        assertEquals(List.of("$.equipment.head.colour: unknown field"), errors("minecraft:player", "\"equipment\":{\"head\":{\"id\":\"minecraft:stone\",\"colour\":1}}"));
        assertEquals(List.of("$.equipment: expected object"), errors("minecraft:player", "\"equipment\":[]"));
        assertEquals(List.of("$.equipment.head: expected item object"), errors("minecraft:player", "\"equipment\":{\"head\":\"minecraft:stone\"}"));
    }

    @Test void hologramIsBoundedInCountLengthAndCharacters() {
        String seventeen = "\"" + "x\",\"".repeat(16) + "x\"";
        assertEquals(List.of("$.hologram.lines: expected at most 16 lines"), errors("minecraft:player", "\"hologram\":{\"lines\":[" + seventeen + "]}"));
        assertEquals(List.of("$.hologram.lines[0]: expected at most 256 characters without control characters"),
                errors("minecraft:player", "\"hologram\":{\"lines\":[\"" + "a".repeat(257) + "\"]}"));
        assertEquals(List.of("$.hologram.lines[1]: expected at most 256 characters without control characters"),
                errors("minecraft:player", "\"hologram\":{\"lines\":[\"ok\",\"two\\nlines\"]}"));
        assertEquals(List.of("$.hologram.lines[0]: expected string or object with text"), errors("minecraft:player", "\"hologram\":{\"lines\":[3]}"));
        assertEquals(List.of("$.hologram.spacing: unknown field"), errors("minecraft:player", "\"hologram\":{\"lines\":[],\"spacing\":1}"));
        assertEquals(List.of(), errors("minecraft:player", "\"hologram\":{\"lines\":[\"" + "a".repeat(256) + "\"]}"));
    }

    @Test void namesMayExceedTheProfileLimitButStayBounded() {
        assertEquals(List.of(), errors("minecraft:player", ""));
        JsonObject longName = definition("minecraft:player", "");
        longName.addProperty("name", "n".repeat(257));
        assertEquals(List.of("$.name: expected at most 256 characters without control characters"), NpcDefinition.validate(longName));
        longName.addProperty("name", "Guard\u0007");
        assertEquals(List.of("$.name: expected at most 256 characters without control characters"), NpcDefinition.validate(longName));
        assertEquals(List.of("$.nameplate.visible: expected boolean"), errors("minecraft:player", "\"nameplate\":{\"visible\":\"no\"}"));
    }

    @Test void posesAreLimitedToWhatTheBodyTypeCanShow() {
        for (String pose : List.of("standing", "sneaking", "sleeping", "swimming", "sitting"))
            assertEquals(List.of(), errors("minecraft:player", "\"pose\":\"" + pose + "\""), pose);
        assertEquals(List.of("$.pose: sneaking is available only for minecraft:player bodies"), errors("minecraft:horse", "\"pose\":\"sneaking\""));
        assertEquals(List.of("$.pose: sitting is available only for minecraft:player bodies"), errors("minecraft:armor_stand", "\"pose\":\"sitting\""));
        assertEquals(List.of(), errors("minecraft:horse", "\"pose\":\"standing\""));
        assertEquals(List.of("$.pose: expected standing, sneaking, sleeping, swimming or sitting"), errors("minecraft:player", "\"pose\":\"crawling\""));
    }

    @Test void armorStandPoseAnglesAndPartsParse() throws IOException {
        NpcDefinition parsed = NpcDefinition.parse(JsonDocuments.write(definition("minecraft:armor_stand", """
                "armorStand":{"small":true,"arms":true,"basePlate":false,"invisible":false,"head":[10,-20,0],"rightArm":[-90,0,15.5]}
                """)));
        ArmorStandLook look = NpcAppearance.of(parsed).armorStand().orElseThrow();
        assertTrue(look.small()); assertTrue(look.arms()); assertFalse(look.basePlate()); assertFalse(look.invisible());
        assertEquals(Map.of(ArmorStandLook.Part.HEAD, new ArmorStandLook.Angles(10, -20, 0), ArmorStandLook.Part.RIGHT_ARM, new ArmorStandLook.Angles(-90, 0, 15.5f)), look.angles());
        assertEquals(List.of("$.armorStand: available only for minecraft:armor_stand bodies"), errors("minecraft:player", "\"armorStand\":{}"));
        assertEquals(List.of("$.armorStand.head: expected [x, y, z] degrees within -360..360"), errors("minecraft:armor_stand", "\"armorStand\":{\"head\":[1,2]}"));
        assertEquals(List.of("$.armorStand.leftLeg: expected [x, y, z] degrees within -360..360"), errors("minecraft:armor_stand", "\"armorStand\":{\"leftLeg\":[1,2,400]}"));
        assertEquals(List.of("$.armorStand.small: expected boolean"), errors("minecraft:armor_stand", "\"armorStand\":{\"small\":1}"));
        assertEquals(List.of("$.armorStand.marker: unknown field"), errors("minecraft:armor_stand", "\"armorStand\":{\"marker\":true}"));
    }

    @Test void inventoryKeepsSlotsExactlyAndValidatesThem() throws IOException {
        NpcDefinition parsed = NpcDefinition.parse(JsonDocuments.write(definition("minecraft:player", """
                "inventory":{"size":36,"slots":{"0":{"id":"minecraft:bread","count":16},"35":{"id":"minecraft:compass","imported":{"type":"COMPASS","meta":{"encoded-meta":"abc"}}}}}
                """)));
        NpcInventory inventory = NpcInventory.of(parsed);
        assertEquals(36, inventory.size());
        assertEquals(new NpcItem("minecraft:bread", 16, Optional.empty(), Optional.empty()), inventory.slots().get(0));
        assertEquals("{\"type\":\"COMPASS\",\"meta\":{\"encoded-meta\":\"abc\"}}", inventory.slots().get(35).imported().orElseThrow().toString());
        assertEquals(List.of("$.inventory.slots.36: slot outside the inventory size 36"),
                errors("minecraft:player", "\"inventory\":{\"size\":36,\"slots\":{\"36\":{\"id\":\"minecraft:stone\"}}}"));
        assertEquals(List.of("$.inventory.size: expected a multiple of 9 within 9..72"), errors("minecraft:player", "\"inventory\":{\"size\":10,\"slots\":{}}"));
        assertEquals(List.of("$.inventory.slots.first: expected slot number"), errors("minecraft:player", "\"inventory\":{\"size\":9,\"slots\":{\"first\":{\"id\":\"minecraft:stone\"}}}"));
        assertEquals(List.of("$.inventory.slots.0.id: expected namespace:item"), errors("minecraft:player", "\"inventory\":{\"size\":9,\"slots\":{\"0\":{}}}"));
    }

    @Test void importedItemDataIsBoundedAndOpaque() {
        String big = "\"" + "a".repeat(17_000) + "\"";
        assertEquals(List.of("$.equipment.head.imported: expected an object of at most 16384 bytes"),
                errors("minecraft:player", "\"equipment\":{\"head\":{\"id\":\"minecraft:stone\",\"imported\":{\"meta\":" + big + "}}}"));
        assertEquals(List.of("$.equipment.head.imported: expected an object of at most 16384 bytes"),
                errors("minecraft:player", "\"equipment\":{\"head\":{\"id\":\"minecraft:stone\",\"imported\":5}}"));
    }

    @Test void ownerMustBeACanonicalUuid() {
        assertEquals(List.of("$.owner: expected canonical player UUID"), errors("minecraft:player", "\"owner\":\"SERVER\""));
        assertEquals(List.of(), errors("minecraft:player", "\"owner\":\"00000000-0000-0000-0000-00000000000a\""));
    }

    @Test void tagsParseInOrderAndAreEmptyWhenAbsent() throws IOException {
        assertEquals(List.of("guard", "north_gate", "quest-1"),
                NpcDefinition.parse(JsonDocuments.write(definition("minecraft:player", "\"tags\":[\"guard\",\"north_gate\",\"quest-1\"]"))).tags());
        assertEquals(List.of(), NpcDefinition.parse(JsonDocuments.write(definition("minecraft:player", ""))).tags());
        assertEquals(List.of(), errors("minecraft:player", "\"tags\":[]"));
    }

    @Test void tagErrorsNameTheExactField() {
        String pattern = ": expected 1–32 lowercase letters, digits, _ or -";
        assertEquals(List.of("$.tags: expected array"), errors("minecraft:player", "\"tags\":\"guard\""));
        String many = String.join(",", java.util.stream.IntStream.range(0, 33).mapToObj(n -> "\"t" + n + "\"").toList());
        assertEquals(List.of("$.tags: expected at most 32 tags"), errors("minecraft:player", "\"tags\":[" + many + "]"));
        assertEquals(List.of("$.tags[2]" + pattern, "$.tags[3]" + pattern, "$.tags[4]" + pattern),
                errors("minecraft:player", "\"tags\":[\"a\",\"b\",\"Guard\",\"_x\",7]"));
        assertEquals(List.of("$.tags[1]" + pattern), errors("minecraft:player", "\"tags\":[\"a\",\"" + "x".repeat(33) + "\"]"));
        assertEquals(List.of("$.tags[3]: duplicate tag"), errors("minecraft:player", "\"tags\":[\"a\",\"b\",\"c\",\"a\"]"));
    }

    @Test void hologramStyleAndLineMarginsParse() throws IOException {
        NpcAppearance look = NpcAppearance.of(NpcDefinition.parse(JsonDocuments.write(definition("minecraft:villager", """
                "hologram":{"lines":["Top",{"text":"Bottom","marginTop":0.1,"marginBottom":0.25}],"lineHeight":0.4,"viewRange":24,
                            "billboard":"vertical","shadowed":true,"seeThrough":true,"interpolationDelay":2,"interpolationDuration":5}
                """))));
        assertEquals(List.of("Top", "Bottom"), look.hologram());
        HologramStyle style = look.hologramStyle();
        assertEquals("vertical", style.billboard());
        assertTrue(style.shadowed());
        assertTrue(style.seeThrough());
        assertEquals(24, style.viewRange().orElseThrow());
        assertEquals(0.4, style.lineHeight().orElseThrow());
        assertEquals(2, style.interpolationDelay());
        assertEquals(5, style.interpolationDuration());
        assertEquals(List.of(new HologramStyle.Margin(0, 0), new HologramStyle.Margin(0.1, 0.25)), style.margins());
        assertEquals(HologramStyle.DEFAULT, NpcAppearance.PLAIN.hologramStyle());
    }

    @Test void hologramStyleRejectsOutOfRangeValues() {
        assertEquals(List.of("$.hologram.lines[0].colour: unknown field", "$.hologram.lines[0].marginTop: expected -4..4 blocks",
                        "$.hologram.lineHeight: expected 0..4 blocks", "$.hologram.viewRange: expected 1..512 blocks",
                        "$.hologram.billboard: expected center, fixed, vertical or horizontal", "$.hologram.interpolationDuration: expected 0..1200 ticks"),
                errors("minecraft:villager", """
                        "hologram":{"lines":[{"text":"x","marginTop":9,"colour":1}],"billboard":"up","lineHeight":5,"viewRange":0,"interpolationDuration":-1}
                        """));
    }

    @Test void bodyTraitsParseAndValidate() throws IOException {
        NpcAppearance look = NpcAppearance.of(NpcDefinition.parse(JsonDocuments.write(definition("minecraft:cow",
                "\"glowing\":true,\"teamColor\":\"dark_red\",\"baby\":true,\"usingItem\":true,\"listed\":true,\"gravity\":false"))));
        assertEquals(new BodyTraits(true, Optional.of("dark_red"), true, true, true, false, NpcAttributes.NONE), look.traits());
        assertEquals(BodyTraits.DEFAULT, NpcAppearance.PLAIN.traits());
        assertEquals(List.of("$.glowing: expected boolean", "$.teamColor: expected a team colour such as red, dark_aqua or gold"),
                errors("minecraft:cow", "\"glowing\":1,\"teamColor\":\"pink\""));
    }
}
