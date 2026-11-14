package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Chat lines and window titles name an NPC by the name players see, not by its id. */
class ToolkitNamesTest extends ToolkitHarness {
    static final UUID SMITH = UUID.fromString("32345678-1234-1234-1234-123456789abc");

    @BeforeEach void setUp() throws IOException {
        start(List.of(new DocumentStore.Change("npcs/town/smith.json", "missing", npc("smith", SMITH, "").replace("\"name\":\"smith\"", "\"name\":\"&6Town Smith\""))), true);
        active.put(SMITH, 3.0);
        toolkit.selectOnly(alex.id, "smith");
    }

    @Override void click(String name, Click click) { super.click(name, click); drain(); }

    @Override void click(int slot, Click click) { super.click(slot, click); drain(); }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); drain(); }

    String last() { return alex.messages.getLast(); }

    @Test void simpleChatLinesUseTheNameNotTheId() {
        toolkit.open(alex.id);
        click(NpcScreen.HOLOGRAM, Click.LEFT);
        click("Add line", Click.LEFT);
        type("Open daily");
        assertEquals("[Animus]: Hologram text on Town Smith", last());
        toolkit.open(alex.id);
        click(NpcScreen.COPY, Click.LEFT);
        click(NpcScreen.COPY, Click.LEFT);
        assertEquals("[Animus]: Copied Town Smith, despawned", last());
        click(NpcScreen.VISIT, Click.LEFT);
        assertTrue(last().contains("Teleported to Town Smith"), last());
        toolkit.open(alex.id);
        click(NpcScreen.DELETE, Click.LEFT);
        click(NpcScreen.DELETE, Click.LEFT);
        assertEquals("[Animus]: Deleted Town Smith", last());
        assertFalse(String.join("\n", alex.messages).contains("smith"), "no id in Simple: " + alex.messages);
    }

    @Test void bringHereSaysByNameThatAnNpcIsNotActive() {
        active.remove(SMITH);
        toolkit.open(alex.id);
        click(NpcScreen.BRING, Click.LEFT);
        assertTrue(last().endsWith("Town Smith is not active in your world"), last());
    }

    @Test void advancedTitlesNameTheNpc() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id);
        click(NpcScreen.FIELDS, Click.LEFT);
        assertEquals("All fields of Town Smith", title());
        toolkit.open(alex.id);
        click(NpcScreen.MODULES, Click.LEFT);
        assertEquals("Modules of Town Smith", title());
        toolkit.open(alex.id);
        click(NpcScreen.ATTRIBUTES, Click.LEFT);
        assertEquals("Attributes of Town Smith", title());
    }

    @Test void talkTitlesTheConversationAfterTheNpcAndItsLinesNameIt() {
        toolkit.open(alex.id);
        click(NpcScreen.TALK, Click.LEFT);
        assertEquals("Talk: Town Smith", title());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.equals("[Animus]: Made a conversation for Town Smith")), alex.messages.toString());
        click("Add line", Click.LEFT);
        type("Fresh bread");
        click(ToolScreen.SAVE, Click.LEFT);
        assertEquals("[Animus]: Saved Town Smith's conversation", last());
        assertFalse(String.join("\n", alex.messages).contains("smith"), "no id in Simple: " + alex.messages);
    }

    @Test void aShopNamesTheNpcsThatUseIt() throws IOException {
        store.commit(List.of(new DocumentStore.Change("shops/town/bakery.json", "missing", ToolkitShopScreensTest.BAKERY)));
        new ShopFiles(npcs, store).bindShop("town:bakery", List.of("smith"));
        toolkit.open(alex.id, ShopBindScreen::new);
        drain();
        click("Bakery", Click.RIGHT);
        assertTrue(lore(ShopDetailScreen.HEADER).contains("Now: on Town Smith"), lore(ShopDetailScreen.HEADER));
        assertTrue(lore(ShopDetailScreen.DELETE).contains("On Town Smith; remove it first"), lore(ShopDetailScreen.DELETE));
    }
}
