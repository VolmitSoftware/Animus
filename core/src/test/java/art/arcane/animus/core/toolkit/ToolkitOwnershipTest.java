package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.ScreenHost;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A player without animus.admin works in the menu on the NPCs they own and no others, and makes no more than their limit. */
class ToolkitOwnershipTest extends ToolkitHarness {
    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, "")), true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
        own("baker");
        alex.permissions.clear();
        alex.permissions.addAll(Set.of(PermissionNodes.TOOLS, PermissionNodes.NPC_VIEW, PermissionNodes.NPC_CREATE, PermissionNodes.NPC_EDIT, PermissionNodes.NPC_MOVE,
                PermissionNodes.NPC_DELETE, "animus.npc.limit.2"));
        alex.pose = new ScreenHost.Pose(Set.of("world", "minecraft:overworld"), 12.3, 64, -7.8, 65.62, 90, 0, false);
    }

    @Test void theWandRefusesAnNpcThePlayerDoesNotOwn() {
        assertTrue(toolkit.interacted(alex, GUARD, false, false), "the click is used");
        assertTrue(toolkit.selected(alex.id).isEmpty());
        assertTrue(alex.messages.contains("bar:guard is not yours; you can change only NPCs you own"), alex.messages.toString());
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, BAKER, false, false));
        assertEquals(List.of("baker"), toolkit.selected(alex.id));
    }

    @Test void theNpcListShowsOnlyTheirOwnAndASelectedServerNpcFallsAway() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.home(alex.id);
        click("NPCs", Click.LEFT);
        drain();
        assertTrue(buttons().containsValue("baker"), buttons().toString());
        assertFalse(buttons().containsValue("guard"), "an NPC the player does not own is not listed");
        assertEquals(List.of(), toolkit.selected(alex.id), "a selection made before the menu read the player keeps only their own");
    }

    @Test void staffWithTheOthersNodeSeeAndSelectEveryNpc() {
        alex.permissions.add(PermissionNodes.NPC_OTHERS);
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        assertEquals(List.of("guard"), toolkit.selected(alex.id), "a server NPC");
        toolkit.home(alex.id);
        click("NPCs", Click.LEFT);
        drain();
        assertTrue(buttons().containsValue("guard") && buttons().containsValue("baker"), buttons().toString());
    }

    @Test void theOwnerFieldIsHiddenFromAPlayer() {
        assertFalse(PropertyScreen.offered(PropertyScreen.SCHEMA, alex.permissions::contains).getAsJsonObject("properties").has("owner"));
        assertTrue(PropertyScreen.offered(PropertyScreen.SCHEMA, Set.of(PermissionNodes.ADMIN, PermissionNodes.OWNED_COMMANDS, PermissionNodes.OWNED_SERVER, PermissionNodes.OWNED_ITEMS)::contains)
                .getAsJsonObject("properties").has("owner"), "an admin edits it");
        assertFalse(PropertyScreen.offered(PropertyScreen.SCHEMA, alex.permissions::contains).getAsJsonObject("properties").getAsJsonObject("modules").getAsJsonObject("properties")
                .has("mythic:mob"), "nor MythicMobs skills without animus.owned.commands");
    }

    @Test void newNpcStopsAtTheLimitAndOnlyTheirOwnCount() {
        make("First");
        assertTrue(npcs.find("first").isPresent());
        make("Second");
        assertTrue(npcs.find("second").isEmpty(), "baker and first make two");
        assertTrue(alex.messages.getLast().contains("You own 2 NPCs, your limit; remove one to make another"), alex.messages.toString());
    }

    @Test void aPluginsTemporaryNpcCountsOnlyAgainstItsOwner() throws IOException {
        npcs.addTemporary("plugin:Pets", com.google.gson.JsonParser.parseString(npc("stray", java.util.UUID.randomUUID(), "")).getAsJsonObject());
        make("First");
        assertTrue(npcs.find("first").isPresent(), "a temporary NPC no one owns counts for no one");
        assertTrue(npcs.delete("first") != null);
        npcs.addTemporary("plugin:Pets", com.google.gson.JsonParser.parseString(npc("pet", java.util.UUID.randomUUID(), ",\"owner\":\"" + alex.id + "\"")).getAsJsonObject());
        make("Second");
        assertTrue(npcs.find("second").isEmpty(), "baker and the plugin's pet make two, as the commands count them");
        assertTrue(alex.messages.getLast().contains("You own 2 NPCs, your limit; remove one to make another"), alex.messages.toString());
    }

    private void make(String name) {
        toolkit.home(alex.id);
        click("New NPC", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of(name));
        drain();
        click("Villager", Click.LEFT);
        drain();
    }
}
