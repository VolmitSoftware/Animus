package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.SkinDocuments;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Skins screen: wearing, details with rename and delete, the action row and skins looked up by player name. */
class SkinScreenTest extends ToolkitHarness {
    static final UUID COW = UUID.fromString("32345678-1234-1234-1234-123456789abc");
    static final String WORN_TEXT = JsonDocuments.write(JsonParser.parseString("{\"textureRaw\":\"e30=\"}")), WORN = JsonDocuments.hash(WORN_TEXT);
    static final String SPARE_TEXT = JsonDocuments.write(JsonParser.parseString("{\"textureRaw\":\"e30=\",\"label\":\"Spare\"}")), SPARE = JsonDocuments.hash(SPARE_TEXT);
    final List<String> looked = new ArrayList<>();
    SkinLookup.Found answer = SkinLookup.Found.missing("unset");
    final SkinLookup lookup = (String name) -> { looked.add(name); return CompletableFuture.completedFuture(answer); };

    @BeforeEach void setUp() throws IOException {
        start(List.of(file("skins/" + WORN + ".json", WORN_TEXT), file("skins/" + SPARE + ".json", SPARE_TEXT),
                file("groups/looks.json", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"looks\",\"values\":{\"skins\":[\"" + WORN + "\"]}}"),
                npcFile("guard", GUARD, ",\"skin\":\"" + WORN + "\""),
                new DocumentStore.Change("npcs/town/cow.json", "missing", npc("cow", COW, "").replace("minecraft:player", "minecraft:cow"))), false);
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        assertEquals("Skin", title());
    }

    /** The last chat line without its {@code [Animus]: } prefix. */
    String last() { return alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""); }

    void select(String... ids) {
        toolkit.selection().clear(alex.id);
        for (String id : ids) toolkit.selection().toggle(alex.id, id);
    }

    @Test void simpleShowsTheSkinsAndTheThreeWaysToGetOne() {
        assertEquals(Map.of(0, "guard", 1, "Spare", 36, "My skin", 37, "Player name", 38, "Default", 44, "Player bodies only", 45, "Back", 46, "Search", 53, "Help"), buttons());
        String worn = lore("guard");
        assertTrue(worn.contains("Worn by guard, trait group looks"), worn);
        assertFalse(worn.contains(WORN.substring(0, 12)), "Simple shows no hash");
        assertTrue(lore("Spare").contains("Not worn"), lore("Spare"));
        assertTrue(lore("Spare").contains("Click: details"), "with nothing selected a click opens the skin");
        select("guard");
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        int skin = ToolScreen.WIDTH;
        assertEquals("guard", name(skin), "the skins start under the header, which names the NPC guard too");
        assertTrue(lore(skin).contains("Click: wear it, right-click: details"), lore(skin));
        assertTrue(lore(skin).contains("Now: worn"), lore(skin));
        assertFalse(lore(skin).contains("Sneak-click"), "nothing hides on a sneak-click");
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        assertTrue(lore(skin).contains(WORN.substring(0, 12)), "Advanced shows the hash");
    }

    @Test void fromAnNpcTheHeaderNamesItAndThePlayerBodyNoteShowsOnlyWhenItApplies() {
        select("guard");
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        assertEquals("guard", name(SkinScreen.HEADER), "the NPC it changes, as the create flow shows its new NPC");
        assertTrue(lore(SkinScreen.HEADER).contains("Now: guard"), "the skin it wears: " + lore(SkinScreen.HEADER));
        assertFalse(shows("Player bodies only"), "a player body needs no warning");
        assertTrue(lore("My skin").contains("Click: wear it"), lore("My skin"));
        assertTrue(lore("Default").contains("Click: wear it"), lore("Default"));
        select("guard", "cow");
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        assertEquals("2 NPCs", name(SkinScreen.HEADER));
        assertTrue(shows("Player bodies only"), "the cow is skipped, so say so");
    }

    @Test void entriesDrawTheirOwnSkinAndKeepTheirPlaceWhenWorn() {
        ScreenFrame.Item spare = alex.frame.items().get(MockScreenHost.slot(alex, "Spare"));
        assertTrue(spare.item().components().orElseThrow().contains("profile={properties:[{name:\"textures\",value:\"e30=\"}]}"), spare.item().toString());
        select("guard");
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        int before = MockScreenHost.slot(alex, "Spare");
        click("Spare", Click.LEFT);
        assertEquals(SPARE, npcs.find("guard").orElseThrow().json().get("skin").getAsString(), "a click wears it");
        assertEquals(before, MockScreenHost.slot(alex, "Spare"), "wearing a skin does not move it under the cursor");
    }

    @Test void detailsRenameTheSkinAndMoveItsWearersInOneTransaction() throws IOException {
        click("guard", Click.RIGHT);
        assertEquals("guard", title());
        assertEquals(Map.of(4, "guard", 20, "Wear it", 24, "Rename", 35, "Delete", 45, "Back", 53, "Help"), buttons());
        click("Rename", Click.LEFT);
        assertEquals("Skin name", alex.prompt.label());
        assertEquals("", alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("  Town guard "));
        assertEquals("Skin", title(), "a change goes back to the list");
        assertTrue(last().startsWith("Labelled skin " + WORN.substring(0, 12) + " Town guard; now "), last());
        assertTrue(last().endsWith("moved guard and 1 trait group"), last());
        String moved = npcs.find("guard").orElseThrow().json().get("skin").getAsString();
        assertNull(store.read("skins/" + WORN + ".json"), "the old file goes");
        String text = store.read("skins/" + moved + ".json");
        assertEquals("Town guard", JsonDocuments.object(text).get("label").getAsString());
        assertEquals("e30=", SkinDocuments.read(store, moved).textureValue(), "the new file is still a valid content-addressed skin");
        assertTrue(store.read("groups/looks.json").contains(moved));
        assertEquals("Town guard", RichText.plain(alex.frame.items().get(MockScreenHost.slot(alex, "Town guard")).name().orElseThrow()));

        click("Town guard", Click.RIGHT);
        click("Rename", Click.LEFT);
        assertEquals("Town guard", alex.prompt.initial(), "a rename starts from the current name");
        MockScreenHost.answer(alex, Optional.empty());
        assertEquals("Cancelled; nothing changed", last());
        assertNotNull(alex.window, "the window comes back after a cancel");
    }

    @Test void aBlankNameRemovesItAndAnOverlongOneIsRefused() throws IOException {
        click("Spare", Click.LEFT);
        click("Rename", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("x".repeat(SkinFiles.LABEL + 1)));
        assertEquals("A skin label is at most 64 characters", last());
        click("Spare", Click.LEFT);
        click("Rename", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of(" "));
        assertTrue(last().startsWith("Removed the label of skin " + SPARE.substring(0, 12) + "; now " + WORN.substring(0, 12)), last());
        assertNull(store.read("skins/" + SPARE + ".json"));
        assertEquals(WORN_TEXT, store.read("skins/" + WORN + ".json"), "without its label it is the same file as the unlabelled one, which stays");
    }

    @Test void deleteAsksOnceAndRefusesASkinSomeoneWears() throws IOException {
        click("guard", Click.RIGHT);
        click("Delete", Click.LEFT);
        assertEquals("Worn by guard, trait group looks; take it off first", last());
        assertNotNull(store.read("skins/" + WORN + ".json"));
        click("Back", Click.LEFT);

        click("Spare", Click.RIGHT);
        click("Delete", Click.LEFT);
        assertEquals("Click again to confirm", name(SkinDetailScreen.DELETE));
        assertNotNull(store.read("skins/" + SPARE + ".json"), "the first click only asks");
        click("Rename", Click.LEFT);
        MockScreenHost.answer(alex, Optional.empty());
        assertEquals("Delete", name(SkinDetailScreen.DELETE), "any other click cancels");
        click("Delete", Click.LEFT);
        click(SkinDetailScreen.DELETE, Click.LEFT);
        assertTrue(last().startsWith("Deleted skin Spare"), last());
        assertNull(store.read("skins/" + SPARE + ".json"));
        assertEquals("Skin", title());
        assertFalse(shows("Spare"));
    }

    @Test void defaultTakesTheSkinOffTheSelection() {
        select("guard");
        click("Default", Click.LEFT);
        assertFalse(npcs.find("guard").orElseThrow().json().has("skin"));
        assertTrue(last().startsWith("Skin removed on guard"), last());
    }

    @Test void mySkinWithoutASignatureSaysWhy() {
        select("guard");
        click("My skin", Click.LEFT);
        assertEquals("Your skin has no signature; offline-mode servers cannot copy it", last());
    }

    @Test void aPlayerNameIsLookedUpSavedWithItsLabelAndWornByTheSelection() throws IOException {
        String texture = Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        answer = SkinLookup.Found.skin("Notch", new BodySkin(texture, Base64.getEncoder().encodeToString(new byte[]{1, 2})));
        select("guard", "cow");
        click("Player name", Click.LEFT);
        assertEquals("Player name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("notch"));
        assertEquals(List.of("notch"), looked);
        assertTrue(alex.messages.contains("[Animus]: Looking up notch on Mojang"), alex.messages.toString());
        String hash = npcs.find("guard").orElseThrow().json().get("skin").getAsString();
        assertTrue(last().startsWith("Skin Notch on guard, 1 skipped (not a player body)"), last());
        JsonObject saved = JsonDocuments.object(store.read("skins/" + hash + ".json"));
        assertEquals("Notch", saved.get("label").getAsString(), "the label is the account's own spelling");
        assertEquals(texture, SkinDocuments.read(store, hash).textureValue());
        assertTrue(lore("Notch").contains("Worn by guard"));
    }

    @Test void wearersGoByTheNamesPlayersSee() throws IOException {
        store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", store.hash("npcs/town/guard.json"),
                npc("guard", GUARD, ",\"skin\":\"" + WORN + "\"").replace("\"name\":\"guard\"", "\"name\":\"&6Guard Tom\""))));
        assertTrue(npcs.reload().isEmpty(), npcs.diagnostics().toString());
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        assertTrue(lore("guard").contains("Worn by Guard Tom, trait group looks"), lore("guard"));
    }

    @Test void aFailedLookupOrABadNameSaysWhyAndWritesNothing() throws IOException {
        int before = new SkinFiles(npcs, store).skins().size();
        answer = SkinLookup.Found.missing("No Minecraft account is named Ghost");
        click("Player name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Ghost"));
        assertEquals("No Minecraft account is named Ghost", last());
        click("Player name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("not a name!"));
        assertEquals("'not a name!' is not a Minecraft name (1-16 letters, digits or _)", last());
        assertEquals(List.of("Ghost"), looked, "a bad name is refused before any lookup");
        assertEquals(before, new SkinFiles(npcs, store).skins().size());
    }

    @Test void withoutTextInputTheScreenStaysAndSaysSo() {
        alex.noPrompt = true;
        click("Player name", Click.LEFT);
        assertEquals("This server has no text input for Player name; use the matching /animus command", last());
        assertNotNull(alex.window);
    }
}
