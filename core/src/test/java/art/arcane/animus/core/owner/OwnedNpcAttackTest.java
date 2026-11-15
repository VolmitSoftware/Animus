package art.arcane.animus.core.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A player who owns an NPC tries every way to give it powers they lack. Each attack is refused with the reason and the
 * node, whoever writes the definition: the owner through commands and the menu, an admin through the editor or by hand.
 */
class OwnedNpcAttackTest {
    static final UUID ALEX = UUID.fromString("0000000a-0000-4000-8000-0000000000a1");
    @TempDir Path root;
    private AnimusRuntime runtime;
    private Administration admin;
    /** The nodes ALEX holds; everything else is a no. */
    private final Set<String> held = new HashSet<>();

    @BeforeEach void start() throws IOException {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root));
        runtime.start();
        runtime.ownerPermissions(new OwnerPermissions() {
            @Override public Answer has(UUID player, String node) { return player.equals(ALEX) && held.contains(node) ? Answer.YES : Answer.NO; }
            @Override public Answer cached(UUID player, String node) { return has(player, node); }
        });
        admin = new Administration(runtime);
        admin.create("stall", "minecraft:player", "world", 1, 64, 2, "", "Stall", Optional.of(ALEX));
        admin.create("mayor", "minecraft:player", "world", 9, 64, 2, "", "Mayor");
        write("behaviors/town/reward.json", behavior("town:reward", "{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":\"give {player_name} diamond 64\"}}"));
        write("behaviors/town/promote.json", behavior("town:promote", "{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":\"lp user {player_name} parent set vip\",\"as\":\"player\",\"asOperator\":true}}"));
        write("behaviors/town/fly.json", behavior("town:fly", "{\"type\":\"action\",\"capability\":\"animus:command\",\"arguments\":{\"command\":\"fly\",\"as\":\"player\",\"permissions\":[\"essentials.fly\"]}}"));
        write("behaviors/town/gift.json", behavior("town:gift", "{\"type\":\"action\",\"capability\":\"animus:give-item\",\"arguments\":{\"item\":\"minecraft:diamond\",\"player\":\"Steve\"}}"));
        write("behaviors/town/greet.json", behavior("town:greet", "{\"type\":\"action\",\"capability\":\"animus:say\",\"arguments\":{\"text\":\"Hello\"}}"));
        write("behaviors/town/announce.json", behavior("town:announce", "{\"type\":\"action\",\"capability\":\"animus:message\",\"arguments\":{\"text\":\"Sale!\",\"to\":\"all\"}}"));
        write("behaviors/town/warp.json", behavior("town:warp", "{\"type\":\"action\",\"capability\":\"animus:teleport\",\"arguments\":{\"x\":0,\"y\":200,\"z\":0,\"world\":\"vault\"}}"));
        write("behaviors/town/everyone.json", """
                {"schemaVersion": 1, "id": "town:everyone", "server": true, "entry": "main", "routines": {"main": {"type": "action", "capability": "animus:say", "arguments": {"text": "hi"}}},
                 "triggers": [{"id": "join", "event": "player_join", "routine": "main"}]}""");
    }

    @AfterEach void stop() { runtime.close(); }

    @Test void bindingAConsoleCommandBehaviorIsRefused() {
        refused("{\"behaviors\":[\"town:reward\"]}", PermissionNodes.OWNED_COMMANDS, "run commands (behavior town:reward)");
    }

    @Test void anOperatorCommandNeedsTheOperatorNodeEvenWithCommands() throws IOException {
        held.add(PermissionNodes.OWNED_COMMANDS);
        refused("{\"behaviors\":[\"town:promote\"]}", PermissionNodes.OWNED_OPERATOR, "run a command as an operator (behavior town:promote)");
    }

    @Test void lentPermissionsNeedTheOwnerToHoldEachNode() throws IOException {
        held.add(PermissionNodes.OWNED_COMMANDS);
        refused("{\"behaviors\":[\"town:fly\"]}", "essentials.fly", "lend essentials.fly (behavior town:fly)");
        held.add("essentials.fly");
        admin.edit("stall", "{\"behaviors\":[\"town:fly\"]}");
        assertEquals(List.of("town:fly"), runtime.npcs().find("stall").orElseThrow().behaviors(), "an owner who holds the node may lend it");
    }

    @Test void aServerBehaviorIsRefused() { refused("{\"behaviors\":[\"town:everyone\"]}", PermissionNodes.OWNED_SERVER, "run a server behavior (behavior town:everyone)"); }

    @Test void reachingAnotherPlayerOrMakingItemsIsRefused() throws IOException {
        refused("{\"behaviors\":[\"town:gift\"]}", PermissionNodes.OWNED_ITEMS, "create items (behavior town:gift)");
        held.add(PermissionNodes.OWNED_ITEMS);
        refused("{\"behaviors\":[\"town:gift\"]}", PermissionNodes.OWNED_PLAYERS, "reach a player by name (behavior town:gift)");
        refused("{\"behaviors\":[\"town:announce\"]}", PermissionNodes.OWNED_PLAYERS, "message every player or the console (behavior town:announce)");
        refused("{\"behaviors\":[\"town:warp\"]}", PermissionNodes.OWNED_SERVER, "teleport players (behavior town:warp)");
    }

    @Test void aPlainBehaviorNeedsNothing() throws IOException {
        admin.edit("stall", "{\"behaviors\":[\"town:greet\"]}");
        assertEquals(List.of("town:greet"), runtime.npcs().find("stall").orElseThrow().behaviors());
    }

    @Test void modulesThatRunCommandsOrMakeItemsAreRefused() throws IOException {
        refused("{\"modules\":{\"animus:schedule\":{\"locations\":[{\"time\":1000,\"x\":0,\"y\":64,\"z\":0,\"commands\":[\"op Alex\"]}]}}}", PermissionNodes.OWNED_COMMANDS, "run commands (schedule arrivals)");
        refused("{\"modules\":{\"animus:health\":{\"death\":{\"drops\":[{\"item\":{\"id\":\"minecraft:diamond\"}}]}}}}", PermissionNodes.OWNED_ITEMS, "create items (death drops)");
        refused("{\"modules\":{\"mythic:mob\":{\"mob\":\"SkeletonKing\"}}}", PermissionNodes.OWNED_COMMANDS, "run MythicMobs skills (module mythic:mob)");
        refused("{\"modules\":{\"other:thing\":{}}}", PermissionNodes.OWNED_SERVER, "carry a module from another plugin (module other:thing)");
        refused("{\"modules\":{\"animus:combat\":{\"enabled\":true,\"targets\":{\"categories\":[\"players\"]}}}}", PermissionNodes.OWNED_PLAYERS, "attack players (combat targets)");
    }

    @Test void bindingAShopOrMenuThatRunsCommandsIsRefused() throws IOException {
        write("shops/town/pets.json", """
                {"schemaVersion": 1, "revision": 1, "id": "town:pets", "title": "Pets", "modes": ["buy"], "defaultMode": "buy",
                 "pages": [{"name": "Pets", "rows": 1, "buy": {"0": {"id": "pet", "type": "command", "item": {"id": "minecraft:bone"}, "price": 50, "commands": [{"command": "op Mallory"}]}}}]}""");
        refused("{\"modules\":{\"animus:shop\":{\"shop\":\"town:pets\"}}}", PermissionNodes.OWNED_COMMANDS, "run commands (shop town:pets)");
        write("gui/inventories/town/kit.json", """
                {"schemaVersion": 1, "revision": 1, "title": "Kit", "resolution": "9x1", "mask": ["K        "],
                 "keys": {"K": {"type": "button", "icon": {"type": "item", "item": "minecraft:chest"}, "actions": [{"type": "command", "command": "kit vip {player}"}]}}}""");
        refused("{\"modules\":{\"animus:gui\":{\"open\":{\"id\":\"town:kit\"}}}}", PermissionNodes.OWNED_COMMANDS, "run commands (menu town:kit)");
    }

    @Test void passingClicksToAnNpcItDoesNotOwnIsRefused() throws IOException {
        String mayor = runtime.npcs().find("mayor").orElseThrow().uuid().toString();
        refused("{\"clickRedirect\":\"" + mayor + "\"}", PermissionNodes.OWNED_SERVER, "pass its clicks to another NPC (NPC mayor)");
        admin.create("booth", "minecraft:player", "world", 4, 64, 2, "", "Booth", Optional.of(ALEX));
        admin.edit("stall", "{\"clickRedirect\":\"" + runtime.npcs().find("booth").orElseThrow().uuid() + "\"}");
    }

    @Test void ridingAnNpcItDoesNotOwnIsRefused() throws IOException {
        refused("{\"modules\":{\"animus:mount\":{\"enabled\":true,\"vehicle\":\"mayor\"}}}", PermissionNodes.OWNED_SERVER, "ride an NPC it does not own (NPC mayor)");
        admin.create("cart", "minecraft:player", "world", 4, 64, 2, "", "Cart", Optional.of(ALEX));
        admin.edit("stall", "{\"modules\":{\"animus:mount\":{\"enabled\":true,\"vehicle\":\"cart\"}}}");
    }

    @Test void aTraitGroupCannotCarryWhatTheNpcMayNot() throws IOException {
        write("groups/vip.json", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"vip\",\"values\":{\"behaviors\":[\"town:reward\"]}}");
        runtime.npcs().reload();
        refused("{\"groups\":[\"vip\"]}", PermissionNodes.OWNED_COMMANDS, "run commands (behavior town:reward)");
    }

    @Test void anAdminWritingTheFileStillCannotGiveItPowersItsOwnerLacks() throws IOException {
        DocumentStore store = runtime.documents();
        String path = runtime.npcs().find("stall").orElseThrow().path();
        com.google.gson.JsonObject edited = JsonDocuments.object(store.read(path));
        edited.add("behaviors", com.google.gson.JsonParser.parseString("[\"town:reward\"]"));
        IOException refused = assertThrows(IOException.class, () -> store.commit(List.of(new DocumentStore.Change(path, store.hash(path), JsonDocuments.write(edited)))));
        assertTrue(refused.getMessage().contains("stall is owned by a player, so it cannot run commands (behavior town:reward) unless its owner holds animus.owned.commands"), refused.getMessage());
        held.add(PermissionNodes.OWNED_COMMANDS);
        store.commit(List.of(new DocumentStore.Change(path, store.hash(path), JsonDocuments.write(edited))));
    }

    @Test void aServerNpcIsNotChecked() throws IOException {
        admin.edit("mayor", "{\"behaviors\":[\"town:reward\",\"town:promote\",\"town:everyone\"]}");
        assertEquals(3, runtime.npcs().find("mayor").orElseThrow().behaviors().size());
    }

    @Test void whatItAlreadyHadStaysWhenTheOwnerLosesANode() throws IOException {
        held.add(PermissionNodes.OWNED_COMMANDS);
        admin.edit("stall", "{\"behaviors\":[\"town:reward\"]}");
        held.clear();
        admin.rename("stall", "Market stall");
        refused("{\"behaviors\":[\"town:reward\",\"town:promote\"]}", PermissionNodes.OWNED_COMMANDS, "run commands (behavior town:promote)");
    }

    private void refused(String patch, String node, String what) {
        IOException refused = assertThrows(IOException.class, () -> admin.edit("stall", patch), patch);
        assertTrue(refused.getMessage().contains("cannot " + what + " unless its owner holds " + node), refused.getMessage());
    }

    private void write(String path, String content) throws IOException {
        runtime.documents().commit(List.of(new DocumentStore.Change(path, runtime.documents().hash(path), content)));
    }

    private static String behavior(String id, String routine) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"entry\":\"main\",\"routines\":{\"main\":" + routine + "},\"triggers\":[{\"id\":\"click\",\"event\":\"interaction\",\"routine\":\"main\"}]}";
    }
}
