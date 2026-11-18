package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandCompletionsTest {
    @TempDir Path data;

    /** Parameters whose value is free text a player makes up; nothing useful can be offered for them. */
    private static final List<String> FREE_TEXT = List.of("name", "token", "option", "text", "title", "url");

    /** A player standing at 10.7 64 -3.2 in town with one mob and one other player nearby. */
    private static class Standing extends AnimusDirectorTest.Actor {
        final UUID mob = UUID.randomUUID(), neighbour = UUID.randomUUID();
        Standing() { super(true, AnimusDirector.ADMIN); }
        @Override public Optional<WorldPosition> position() { return Optional.of(new WorldPosition("town", new Vec3(10.7, 64, -3.2))); }
        @Override public List<UUID> nearby() { return List.of(mob); }
        @Override public List<UUID> nearbyPlayers() { return List.of(neighbour); }
    }

    @Test void aMobIsLentByItsUuidAndAStateDeleteTakesAPlayersUuid() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        Standing actor = new Standing();
        assertEquals(List.of("stop", actor.mob.toString()), List.copyOf(tree.values("combat mob", "mob", actor, java.util.Map.of())), "a mob, never a player");
        assertEquals(List.of(actor.player().orElseThrow().toString(), actor.neighbour.toString()),
                List.copyOf(tree.values("behaviors state delete player", "uuid", actor, java.util.Map.of())), "players, never a mob");
        assertEquals(List.of(actor.player().orElseThrow().toString(), actor.neighbour.toString()),
                List.copyOf(tree.values("behaviors state delete relationship", "uuid", actor, java.util.Map.of())));
    }

    @Test void anArgumentOffersWhatThatCommandTakes() {
        assertEquals(CommandCompletions.GUI_ID, CommandCompletions.kind("gui create", "id"));
        assertEquals(CommandCompletions.GUI_ID, CommandCompletions.kind("hologram create", "id"));
        assertEquals("id", CommandCompletions.kind("create", "id"), "a new NPC's id stays a free NPC id");
        assertEquals(CommandCompletions.ACTION, CommandCompletions.kind("modules action", "capability"));
        assertEquals(CommandCompletions.CONDITION, CommandCompletions.kind("modules condition", "capability"));
        assertEquals(CommandCompletions.SHOP_SOURCE, CommandCompletions.kind("import shops preview", "source"));
        assertEquals(CommandCompletions.GLOSS_SOURCE, CommandCompletions.kind("import gloss file", "source"));
        assertEquals(CommandCompletions.IMPORT_FILE, CommandCompletions.kind("import preview", "file"));
        assertEquals("file", CommandCompletions.kind("npc skin", "file"));
        assertEquals(List.of("gloss:", "harbor:", "town:"), CommandCompletions.namespaces(List.of("town:board", "town:map", "harbor:menu")),
                "a GUI id starts from a namespace, never an NPC-style id");
        art.arcane.animus.api.Capabilities.Availability say = capability("say", art.arcane.animus.api.Capabilities.Kind.ACTION, true),
                near = capability("near", art.arcane.animus.api.Capabilities.Kind.CONDITION, true),
                gone = capability("gone", art.arcane.animus.api.Capabilities.Kind.CONDITION, false),
                click = capability("click", art.arcane.animus.api.Capabilities.Kind.TRIGGER, true);
        assertEquals(List.of("town:near"), CommandCompletions.capabilities(List.of(say, near, gone, click), art.arcane.animus.api.Capabilities.Kind.CONDITION),
                "conditions that can run here, not every capability");
        assertEquals(List.of("town:say"), CommandCompletions.capabilities(List.of(say, near, gone, click), art.arcane.animus.api.Capabilities.Kind.ACTION));
    }

    private static art.arcane.animus.api.Capabilities.Availability capability(String name, art.arcane.animus.api.Capabilities.Kind kind, boolean available) {
        return new art.arcane.animus.api.Capabilities.Availability(new art.arcane.animus.api.Capabilities.Descriptor(new art.arcane.animus.api.Capabilities.Key("town", name), kind,
                "A " + name, "{}", java.util.Set.of(art.arcane.animus.api.Platform.PAPER), false), available, available ? "ready" : "module stopped");
    }

    @Test void eachImportIsOfferedTheInboxEntriesItReads() throws Exception {
        Files.createDirectories(data.resolve("imports/inbox/dock-shops"));
        Files.writeString(data.resolve("imports/inbox/harbor-saves.yml"), "npc: {}");
        Files.writeString(data.resolve("imports/inbox/board.json"), "{}");
        assertEquals(List.of("dock-shops"), CompletionFiles.inboxFolders(data), "import shops preview reads a folder, never a Citizens save");
        assertEquals(List.of("board.json", "dock-shops"), CompletionFiles.glossSources(data), "import gloss file reads a folder or a .json bundle");
        assertEquals(List.of("board.json", "harbor-saves.yml"), CompletionFiles.inboxFiles(data));
    }

    @Test void npcCompletionListsTheNpcsThePlayerMayActOn() throws Exception {
        AnimusDirectorTest.Actor alex = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_EDIT, PermissionNodes.NPC_MOVE);
        List<art.arcane.animus.core.npc.NpcDefinition> all = List.of(npc("mayor", Optional.empty()), npc("stall", alex.player()), npc("booth", Optional.of(UUID.randomUUID())));
        assertEquals(List.of("stall"), CommandCompletions.owned(all, alex).stream().map(art.arcane.animus.core.npc.NpcDefinition::id).toList(), "as /animus list shows them");
        AnimusDirectorTest.Actor staff = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_EDIT, PermissionNodes.NPC_OTHERS);
        assertEquals(3, CommandCompletions.owned(all, staff).size());
        assertEquals(3, CommandCompletions.owned(all, new AnimusDirectorTest.Actor(false)).size(), "the console");
    }

    private static art.arcane.animus.core.npc.NpcDefinition npc(String id, Optional<UUID> owner) throws java.io.IOException {
        return art.arcane.animus.core.npc.NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.nameUUIDFromBytes(id.getBytes()) + "\",\"id\":\"" + id
                + "\",\"group\":\"\",\"name\":\"" + id + "\",\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}"
                + owner.map((UUID player) -> ",\"owner\":\"" + player + "\"").orElse("") + "}");
    }

    /**
     * Every parameter of every command offers something: a source that needs the running server reaches for it (here it
     * has none and throws), and every other one answers with values. A parameter that answers nothing has no completer.
     */
    @Test void everyParameterHasACompleter() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        AnimusDirector director = tree.director();
        TreeMap<String, String> missing = new TreeMap<>();
        Standing actor = new Standing();
        tree.changes().record(actor.player().orElseThrow(), "0f1e2d3c-4b5a-4968-8776-655443322110", "Made guard", "");
        assertEquals(List.of("0f1e2d3c"), List.copyOf(tree.values("", "change", actor, java.util.Map.of())), "undo offers the player's own changes");
        List<DirectorRuntimeNode> commands = new ArrayList<>();
        collect(director.engine().getRoot(), commands);
        for (DirectorRuntimeNode command : commands)
            for (DirectorParameterDescriptor parameter : command.getDescriptor().getParameters()) {
                Class<?> type = parameter.getType();
                if (type.isEnum() || type == boolean.class || FREE_TEXT.contains(parameter.getName())) continue;
                try {
                    Collection<String> offered = tree.values(command.path().substring("/animus ".length()), parameter.getName(), actor, java.util.Map.of());
                    if (offered.isEmpty()) missing.put(parameter.getName(), command.path());
                } catch (RuntimeException needsServer) { /* the source is the running server's */ }
            }
        assertTrue(missing.isEmpty(), "parameters without completion: " + missing);
    }

    @Test void coordinatesAndTargetsComeFromWhereThePlayerStands() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        Standing actor = new Standing();
        assertEquals(List.of("~", "10"), List.copyOf(tree.values("", "x", actor, java.util.Map.of())));
        assertEquals(List.of("~", "64"), List.copyOf(tree.values("", "y", actor, java.util.Map.of())));
        assertEquals(List.of("~", "-4"), List.copyOf(tree.values("", "z", actor, java.util.Map.of())), "the block the player stands in");
        assertEquals(List.of("~,~,~", "10,64,-4"), List.copyOf(tree.values("", "at", actor, java.util.Map.of())));
        Standing looking = new Standing() { @Override public Optional<Vec3> target() { return Optional.of(new Vec3(3, 70, 8)); } };
        assertEquals(List.of("~", "3"), List.copyOf(tree.values("", "x", looking, java.util.Map.of())), "the looked-at block comes before the player's own");
        assertEquals(List.of("~,~,~", "3,70,8"), List.copyOf(tree.values("", "at", looking, java.util.Map.of())));
        assertEquals(List.of("stop", actor.mob.toString()), List.copyOf(tree.values("", "mob", actor, java.util.Map.of())), "a mob has no name; nearby ones by UUID");
        assertTrue(tree.values("behaviors state delete player", "uuid", actor, java.util.Map.of()).contains(actor.player().orElseThrow().toString()), "state deletes take a UUID, not a name");
        assertTrue(tree.values("", "value", actor, java.util.Map.of()).contains("reset"));
        assertTrue(tree.values("", "expires", actor, java.util.Map.of()).contains("7d"));
        assertEquals(CommandCompletions.PROOF_BODIES, List.copyOf(tree.values("", "body", actor, java.util.Map.of())), "previews offer preview bodies, not NPC types");
    }

    @Test void theParametersThatWereWiredWrongNowMeanOneThing() {
        AnimusDirector director = new AnimusCommandTree(null).director();
        DirectorRuntimeNode root = director.engine().getRoot();
        assertEquals(List.of("viewer"), names(find(root, "debug", "network")), "debug network completes recorded viewers");
        assertEquals(List.of("uuid"), names(find(root, "behaviors", "state", "delete", "player")));
        assertEquals(List.of("npc", "uuid"), names(find(root, "behaviors", "state", "delete", "relationship")));
        assertEquals(List.of("body", "skin"), names(find(root, "proof", "spawn")));
        assertEquals(List.of("npc", "charge"), names(find(root, "combat", "guard")));
    }

    @Test void npcIdsAreNotCutBeforeThePrefixIsApplied() throws Exception {
        List<art.arcane.animus.core.npc.NpcDefinition> many = new ArrayList<>();
        for (int index = 0; index < 300; index++)
            many.add(art.arcane.animus.core.npc.NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + UUID.randomUUID() + "\",\"id\":\"citizens-" + index
                    + "\",\"type\":\"minecraft:player\",\"group\":\"\",\"name\":\"N\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}"));
        List<String> ids = CommandCompletions.ids(many, true);
        assertTrue(ids.contains("citizens-299") && ids.contains(CommandGroup.SELECTED), "every id is offered: " + ids.size());
        AnimusDirector director = new AnimusDirector(new AnimusDirectorTest.Probe(), (String parameter, CommandActor actor) -> parameter.equals("npc") ? ids : List.of());
        List<String> offered = director.complete(new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN), new String[]{"spawn", "citizens-29"});
        assertTrue(offered.contains("citizens-299") && offered.contains("citizens-29"), offered.toString());
    }

    @Test void healthValuesFollowTheSetting() {
        assertEquals(List.of("on", "off", "reset"), CommandCompletions.healthValues("killable"));
        assertEquals(List.of("respawn", "permadeath", "delete", "reset"), CommandCompletions.healthValues("after-death"));
        assertTrue(CommandCompletions.healthValues("maximum").contains("20"));
        assertTrue(CommandCompletions.healthValues("delay").contains("100"));
        assertEquals(List.of("reset"), CommandCompletions.healthValues(""));
    }

    @Test void dataFolderNamesCompleteNewestFirst() throws Exception {
        Files.createDirectories(data.resolve("imports/inbox/Shopkeepers"));
        Files.writeString(data.resolve("imports/inbox/saves.yml"), "npc: {}");
        String older = "a".repeat(64), newer = "b".repeat(64);
        Files.createDirectories(data.resolve("imports/" + older + "/applications/" + "c".repeat(64)));
        Files.createDirectories(data.resolve("imports/" + newer));
        Files.setLastModifiedTime(data.resolve("imports/" + older), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(data.resolve("imports/" + newer), FileTime.fromMillis(2_000));
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        Files.createDirectories(data.resolve("backups"));
        Files.writeString(data.resolve("backups/" + first + ".json"), "{}");
        Files.writeString(data.resolve("backups/" + second + ".json"), "{}");
        Files.writeString(data.resolve("backups/settings-1.json"), "{}");
        Files.setLastModifiedTime(data.resolve("backups/" + first + ".json"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(data.resolve("backups/" + second + ".json"), FileTime.fromMillis(2_000));
        Files.createDirectories(data.resolve("skins"));
        Files.writeString(data.resolve("skins/" + "d".repeat(64) + ".json"), "{}");
        assertEquals(List.of("saves.yml"), CompletionFiles.inboxFiles(data));
        assertEquals(List.of("Shopkeepers"), CompletionFiles.inboxFolders(data));
        assertEquals(List.of("saves.yml", "Shopkeepers"), CompletionFiles.inbox(data));
        assertEquals(List.of(newer, older), CompletionFiles.previews(data));
        assertEquals(List.of("c".repeat(64)), CompletionFiles.applications(data));
        assertEquals(List.of(second.substring(0, 8), first.substring(0, 8)), CompletionFiles.transactions(data), "receipts only, newest first, as the short id Undo suggests");
        assertEquals(List.of("d".repeat(64)), CompletionFiles.skins(data));
        assertEquals(List.of(), CompletionFiles.inbox(data.resolve("missing")));
    }

    @Test void transactionsSharingTheShortIdCompleteInFull() throws Exception {
        String first = "ee3120a3-f545-4304-9a1b-80ba58e9c3e8", second = "ee3120a3-0000-4304-9a1b-80ba58e9c3e8", other = "12345678-f545-4304-9a1b-80ba58e9c3e8";
        Files.createDirectories(data.resolve("backups"));
        Files.writeString(data.resolve("backups/" + first + ".json"), "{}");
        Files.writeString(data.resolve("backups/" + second + ".json"), "{}");
        Files.writeString(data.resolve("backups/" + other + ".json"), "{}");
        Files.setLastModifiedTime(data.resolve("backups/" + first + ".json"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(data.resolve("backups/" + second + ".json"), FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(data.resolve("backups/" + other + ".json"), FileTime.fromMillis(3_000));
        assertEquals(List.of("12345678", second, first), CompletionFiles.transactions(data));
    }

    /** Mount, seat, speak and the skin options live in one tree: each has its parameters, its node, help and a range list both share. */
    @Test void mountSeatSpeakAndSkinOptionsCompleteTogether() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        DirectorRuntimeNode root = tree.director().engine().getRoot();
        assertEquals(List.of("4", "8", "16", "32"), List.copyOf(tree.values("", "range", new Standing(), java.util.Map.of())), "mount set and speak offer one range list");
        assertTrue(names(find(root, "speak")).containsAll(List.of("npc", "text", "target", "range", "bubble")), names(find(root, "speak")).toString());
        assertTrue(names(find(root, "npc", "skin")).containsAll(List.of("url", "file", "slim", "follow", "from-name")), names(find(root, "npc", "skin")).toString());
        assertTrue(names(find(root, "mount", "set")).contains("range"), names(find(root, "mount", "set")).toString());
        assertEquals(PermissionNodes.NPC_SPEAK, AnimusDirector.permission(find(root, "speak")));
        assertEquals(PermissionNodes.NPC_SKIN, AnimusDirector.permission(find(root, "npc", "skin")));
        assertEquals(PermissionNodes.SEAT, AnimusDirector.permission(find(root, "seat", "toggle")));
        for (String command : List.of("speak", "mount", "seat"))
            assertTrue(tree.director().complete(new Standing(), new String[]{"help", command.substring(0, 3)}).contains(command), "help completes " + command);
    }

    private static List<String> names(DirectorRuntimeNode node) {
        return node.getDescriptor().getParameters().stream().map(DirectorParameterDescriptor::getName).toList();
    }

    private static DirectorRuntimeNode find(DirectorRuntimeNode node, String... path) {
        DirectorRuntimeNode cursor = node;
        for (String name : path)
            cursor = cursor.getChildren().stream().filter((DirectorRuntimeNode child) -> child.getDescriptor().getName().equals(name)).findFirst().orElseThrow();
        return cursor;
    }

    private static void collect(DirectorRuntimeNode node, List<DirectorRuntimeNode> out) {
        if (node.isInvocable()) out.add(node);
        for (DirectorRuntimeNode child : node.getChildren()) collect(child, out);
    }
}
