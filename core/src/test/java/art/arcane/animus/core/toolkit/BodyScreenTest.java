package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Body from the NPC screen: changes the selection's body, asking first when that drops the old body's settings. */
class BodyScreenTest extends ToolkitHarness {
    static final String SKIN_TEXT = JsonDocuments.write(JsonParser.parseString("{\"textureRaw\":\"e30=\"}")), SKIN = JsonDocuments.hash(SKIN_TEXT);

    @BeforeEach void setUp() throws IOException {
        start(List.of(file("skins/" + SKIN + ".json", SKIN_TEXT), npcFile("guard", GUARD, ",\"skin\":\"" + SKIN + "\""), npcFile("baker", BAKER, "")), false);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
    }

    void open(String... chosen) {
        toolkit.selection().clear(alex.id);
        for (String id : chosen) toolkit.selection().toggle(alex.id, id);
        toolkit.open(alex.id);
        click("Body", Click.LEFT);
        assertEquals("Body", title());
    }

    String type(String id) { return npcs.find(id).orElseThrow().json().get("type").getAsString(); }


    @Test void simpleShowsTheCommonBodiesWithTheCurrentOneMarked() {
        open("baker");
        Map<Integer, String> shown = new TreeMap<>(CreateFlowTest.BODY);
        shown.put(4, "baker");
        shown.put(BodyScreen.POSE, "Pose");
        assertEquals(shown, buttons());
        assertTrue(lore("baker").contains("Now: Player"), lore("baker"));
        assertTrue(lore("Player").contains("Now: this body"), lore("Player"));
        assertTrue(lore("Player").contains("Most common"), lore("Player"));
        assertTrue(alex.frame.items().get(MockScreenHost.slot(alex, "Player")).item().components().orElseThrow().contains("enchantment_glint_override=true"));
        assertTrue(lore("Cow").contains("Click: change to it"));
        assertFalse(lore("Cow").contains("minecraft:cow"), "Simple shows no raw id");
    }

    @Test void poseIsPickedFromAListOfWhatTheBodyCanTake() throws IOException {
        open("baker");
        assertTrue(lore("Pose").contains("Now: Standing"), lore("Pose"));
        click("Pose", Click.LEFT);
        assertEquals("Pose", title());
        assertTrue(shows("Sleeping"), "a player body takes every pose");
        click("Sitting", Click.LEFT);
        assertEquals("Body", title(), "the pick goes back to Body");
        assertEquals("sitting", npcs.find("baker").orElseThrow().json().get("pose").getAsString());
        assertTrue(lore("Pose").contains("Now: Sitting"), lore("Pose"));
        click("Pose", Click.LEFT);
        click("Standing", Click.LEFT);
        assertFalse(npcs.find("baker").orElseThrow().json().has("pose"), "standing is the default, so no pose is written");
    }

    @Test void aVillagerIsOfferedOnlyThePosesItCanShow() throws IOException {
        com.google.gson.JsonObject document = npcs.draft("baker").document();
        document.addProperty("type", "minecraft:villager");
        art.arcane.animus.core.npc.NpcRepository.Draft draft = npcs.draft("baker");
        npcs.apply(new art.arcane.animus.core.npc.NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        open("baker");
        click("Pose", Click.LEFT);
        assertTrue(shows("Standing") && shows("Sitting"));
        assertFalse(shows("Sleeping"), "only player bodies sleep");
    }

    @Test void noBodySitsWhereTheWindowOpensSoADoubleClickConfirmsNothing() {
        open("baker");
        for (int slot : List.of(13, 22, 31)) assertFalse(buttons().containsKey(slot), "slot " + slot + " holds " + name(slot));
    }

    @Test void aChangeThatDropsSettingsAsksOnceAndGoesOnEverySelectedNpc() {
        open("guard", "baker");
        assertEquals("2 NPCs", name(BodyScreen.HEADER));
        click("Villager", Click.LEFT);
        assertEquals("Click again to confirm", name(MockScreenHost.slot(alex, "Click again")));
        assertTrue(lore("Click again").contains("Drops skin; Undo in chat brings it back"), lore("Click again"));
        assertEquals(ToolkitFiles.PLAYER, type("guard"), "the first click only asks");
        click("Click again", Click.LEFT);
        assertEquals("minecraft:villager", type("guard"));
        assertEquals("minecraft:villager", type("baker"));
        assertFalse(npcs.find("guard").orElseThrow().json().has("skin"), "only a player body wears a skin");
        assertEquals(1, changes().size(), "one transaction");
        assertTrue(lore("2 NPCs").contains("Now: Villager"), lore("2 NPCs"));
        assertTrue(alex.messages.getLast().contains("Body Villager on 2 NPCs"), alex.messages.getLast());
    }

    @Test void aChangeThatDropsNothingIsMadeAtOnce() {
        open("baker");
        click("Cow", Click.LEFT);
        assertEquals("minecraft:cow", type("baker"));
        click("Cow", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Body already Cow on baker"), alex.messages.getLast());
    }

    @Test void allBodiesChangesItAndComesBack() {
        open("baker");
        click("All bodies", Click.LEFT);
        assertEquals("All bodies", title());
        click("Search", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("fox"));
        click("Fox", Click.LEFT);
        assertEquals("minecraft:fox", type("baker"));
        assertEquals("Body", title(), "back on the common bodies");
        assertTrue(lore("baker").contains("Now: Fox"), lore("baker"));
    }

    @Test void advancedTypesAnyBodyId() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        open("baker");
        assertEquals("Type an id", name(BodyScreen.TYPED));
        assertFalse(shows("Spawned"), "the Spawned toggle belongs to New NPC");
        click("Type an id", Click.LEFT);
        assertEquals("Body id", alex.prompt.label());
        assertEquals(ToolkitFiles.PLAYER, alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("minecraft:mannequin"));
        assertEquals("minecraft:mannequin", type("baker"));
    }

    @Test void withNothingSelectedItSaysToPickOne() {
        toolkit.open(alex.id, BodyScreen::new);
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
    }
}
