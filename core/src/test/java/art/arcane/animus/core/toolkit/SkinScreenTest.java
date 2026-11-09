package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.SkinDocuments;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Skins screen: labels, renames, guarded deletes and skins looked up by player name. */
class SkinScreenTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), COW = UUID.fromString("32345678-1234-1234-1234-123456789abc");
    static final String WORN_TEXT = JsonDocuments.write(JsonParser.parseString("{\"textureRaw\":\"e30=\"}")), WORN = JsonDocuments.hash(WORN_TEXT);
    static final String SPARE_TEXT = JsonDocuments.write(JsonParser.parseString("{\"textureRaw\":\"e30=\",\"label\":\"Spare\"}")), SPARE = JsonDocuments.hash(SPARE_TEXT);
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});
    final List<String> looked = new ArrayList<>();
    SkinLookup.Found answer = SkinLookup.Found.missing("unset");
    Toolkit<Player, Stack> toolkit;

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        store.commit(List.of(
                new DocumentStore.Change("skins/" + WORN + ".json", "missing", WORN_TEXT),
                new DocumentStore.Change("skins/" + SPARE + ".json", "missing", SPARE_TEXT),
                new DocumentStore.Change("groups/looks.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"looks\",\"values\":{\"skins\":[\"" + WORN + "\"]}}"),
                new DocumentStore.Change("npcs/town/guard.json", "missing", ToolkitScreensTest.npc("guard", GUARD, ",\"skin\":\"" + WORN + "\"")),
                new DocumentStore.Change("npcs/town/cow.json", "missing", ToolkitScreensTest.npc("cow", COW, "").replace("minecraft:player", "minecraft:cow"))));
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        alex.permissions.add(Toolkit.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty());
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, Runnable::run, (Player player) -> Optional.empty(), Optional.empty(), System::nanoTime));
        SkinLookup lookup = (String name) -> { looked.add(name); return CompletableFuture.completedFuture(answer); };
        toolkit.open(alex.id, commands, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) -> new SkinScreen<>(kit, viewer, given, lookup));
        assertEquals("Skins", RichText.plain(alex.frame.title()));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void click(String name, Click click) { manager.click(alex, alex.window, MockScreenHost.slot(alex, name), click); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    /** The last chat line without its {@code [Animus]: } prefix. */
    String last() { return alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""); }

    void select(String... ids) {
        toolkit.selection().clear(alex.id);
        for (String id : ids) toolkit.selection().toggle(alex.id, id);
    }

    @Test void entriesShowTheLabelOrFirstWearerWithWearersHashAndClickHints() {
        String worn = lore("guard");
        assertTrue(worn.contains("Worn by guard") && worn.contains("Groups looks") && worn.contains(WORN.substring(0, 12)), worn);
        assertTrue(worn.contains("Left put on the selection") && worn.contains("Right rename") && worn.contains("Sneak delete (only when unused)"), worn);
        String spare = lore("Spare");
        assertTrue(spare.contains("Worn by none") && spare.contains("Sneak delete the file, asks once more"), spare);
        assertTrue(lore("From a player name").contains("Click: type a name"));
    }

    @Test void entriesDrawTheirOwnSkinAndKeepTheirPlaceWhenWorn() {
        ScreenFrame.Item spare = alex.frame.items().get(MockScreenHost.slot(alex, "Spare"));
        assertTrue(spare.item().components().orElseThrow().contains("profile={properties:[{name:\"textures\",value:\"e30=\"}]}"), spare.item().toString());
        int before = MockScreenHost.slot(alex, "Spare");
        select("guard");
        click("Spare", Click.LEFT);
        assertEquals(before, MockScreenHost.slot(alex, "Spare"), "wearing a skin does not move it under the cursor");
    }

    @Test void renamingLabelsTheSkinAndMovesItsWearersInOneTransaction() throws IOException {
        click("guard", Click.RIGHT);
        assertNotNull(alex.prompt);
        assertEquals("Skin label", alex.prompt.label());
        assertEquals("", alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("  Town guard "));
        assertTrue(last().startsWith("Labelled skin " + WORN.substring(0, 12) + " Town guard; now "), last());
        assertTrue(last().contains("moved guard and 1 group; tx "), last());
        String moved = npcs.find("guard").orElseThrow().json().get("skin").getAsString();
        assertNull(store.read("skins/" + WORN + ".json"), "the old file goes");
        String text = store.read("skins/" + moved + ".json");
        assertEquals("Town guard", JsonDocuments.object(text).get("label").getAsString());
        assertEquals("e30=", SkinDocuments.read(store, moved).textureValue(), "the new file is still a valid content-addressed skin");
        assertTrue(store.read("groups/looks.json").contains(moved));
        assertEquals("Town guard", RichText.plain(alex.frame.items().get(MockScreenHost.slot(alex, "Town guard")).name().orElseThrow()));

        click("Town guard", Click.RIGHT);
        assertEquals("Town guard", alex.prompt.initial(), "a rename starts from the current label");
        MockScreenHost.answer(alex, Optional.of("Town guard"));
        assertTrue(last().endsWith("already labelled Town guard"), last());
        click("Town guard", Click.RIGHT);
        MockScreenHost.answer(alex, Optional.empty());
        assertEquals("Cancelled; nothing changed", last());
        assertNotNull(alex.window, "the window comes back after a cancel");
    }

    @Test void aBlankLabelRemovesItAndAnOverlongOneIsRefused() throws IOException {
        click("Spare", Click.RIGHT);
        MockScreenHost.answer(alex, Optional.of("x".repeat(SkinFiles.LABEL + 1)));
        assertEquals("A skin label is at most 64 characters", last());
        click("Spare", Click.RIGHT);
        MockScreenHost.answer(alex, Optional.of(" "));
        assertTrue(last().startsWith("Removed the label of skin " + SPARE.substring(0, 12) + "; now " + WORN.substring(0, 12) + "; tx "), last());
        assertNull(store.read("skins/" + SPARE + ".json"));
        assertEquals(WORN_TEXT, store.read("skins/" + WORN + ".json"), "without its label it is the same file as the unlabelled one, which stays");
    }

    @Test void deletingAsksTwiceAndRefusesASkinSomeoneWears() throws IOException {
        click("guard", Click.SHIFT_LEFT);
        assertTrue(last().startsWith("guard is worn by guard and groups looks; take it off first"), last());
        assertNotNull(store.read("skins/" + WORN + ".json"));

        click("Spare", Click.SHIFT_LEFT);
        assertNotNull(store.read("skins/" + SPARE + ".json"), "the first sneak click only arms");
        assertTrue(lore("Delete Spare?").contains("Sneak-click again to delete skins/" + SPARE.substring(0, 12)));
        click("Delete Spare?", Click.LEFT);
        assertEquals("Nothing selected; click NPCs with the wand first", last(), "any other click disarms");
        click("Spare", Click.SHIFT_LEFT);
        click("Delete Spare?", Click.SHIFT_LEFT);
        assertTrue(last().startsWith("Deleted skins/" + SPARE.substring(0, 12) + "; tx "), last());
        assertNull(store.read("skins/" + SPARE + ".json"));
        assertFalse(alex.frame.items().values().stream().anyMatch((ScreenFrame.Item item) -> item.name().map(RichText::plain).orElse("").contains("Spare")));
    }

    @Test void aPlayerNameIsLookedUpSavedWithItsLabelAndWornByTheSelection() throws IOException {
        String texture = Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        answer = SkinLookup.Found.skin("Notch", new BodySkin(texture, Base64.getEncoder().encodeToString(new byte[]{1, 2})));
        select("guard", "cow");
        click("From a player name", Click.LEFT);
        assertEquals("Player name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("notch"));
        assertEquals(List.of("notch"), looked);
        assertTrue(alex.messages.contains("[Animus]: Looking up notch on Mojang"), alex.messages.toString());
        String hash = npcs.find("guard").orElseThrow().json().get("skin").getAsString();
        assertTrue(last().startsWith("Saved Notch's skin as skins/" + hash.substring(0, 12) + "; Skin Notch " + hash + " on guard, 1 skipped (not a player body); tx "), last());
        JsonObject saved = JsonDocuments.object(store.read("skins/" + hash + ".json"));
        assertEquals("Notch", saved.get("label").getAsString(), "the label is the account's own spelling");
        assertEquals(texture, SkinDocuments.read(store, hash).textureValue());
        assertTrue(lore("Notch").contains("Worn by guard"));
    }

    @Test void aFailedLookupOrABadNameSaysWhyAndWritesNothing() throws IOException {
        int before = new SkinFiles(npcs, store).skins().size();
        answer = SkinLookup.Found.missing("No Minecraft account is named Ghost");
        click("From a player name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Ghost"));
        assertEquals("No Minecraft account is named Ghost", last());
        click("From a player name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("not a name!"));
        assertEquals("'not a name!' is not a Minecraft name (1-16 letters, digits or _)", last());
        assertEquals(List.of("Ghost"), looked, "a bad name is refused before any lookup");
        assertEquals(before, new SkinFiles(npcs, store).skins().size());
    }

    @Test void withoutTextInputTheScreenStaysAndSaysSo() {
        alex.noPrompt = true;
        click("From a player name", Click.LEFT);
        assertEquals("This server has no text input for Player name; use the matching /animus command", last());
        assertNotNull(alex.window);
    }
}
