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
    private static final List<String> FREE_TEXT = List.of("name", "token", "option", "text", "title");

    /** A player standing at 10.7 64 -3.2 in town with one mob nearby. */
    private static class Standing extends AnimusDirectorTest.Actor {
        final UUID mob = UUID.randomUUID();
        Standing() { super(true, AnimusDirector.ADMIN); }
        @Override public Optional<WorldPosition> position() { return Optional.of(new WorldPosition("town", new Vec3(10.7, 64, -3.2))); }
        @Override public List<UUID> nearby() { return List.of(mob); }
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
        List<DirectorRuntimeNode> commands = new ArrayList<>();
        collect(director.engine().getRoot(), commands);
        for (DirectorRuntimeNode command : commands)
            for (DirectorParameterDescriptor parameter : command.getDescriptor().getParameters()) {
                Class<?> type = parameter.getType();
                if (type.isEnum() || type == boolean.class || FREE_TEXT.contains(parameter.getName())) continue;
                try {
                    Collection<String> offered = tree.values(parameter.getName(), actor, java.util.Map.of());
                    if (offered.isEmpty()) missing.put(parameter.getName(), command.path());
                } catch (RuntimeException needsServer) { /* the source is the running server's */ }
            }
        assertTrue(missing.isEmpty(), "parameters without completion: " + missing);
    }

    @Test void coordinatesAndTargetsComeFromWhereThePlayerStands() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        Standing actor = new Standing();
        assertEquals(List.of("~", "10"), List.copyOf(tree.values("x", actor, java.util.Map.of())));
        assertEquals(List.of("~", "64"), List.copyOf(tree.values("y", actor, java.util.Map.of())));
        assertEquals(List.of("~", "-4"), List.copyOf(tree.values("z", actor, java.util.Map.of())), "the block the player stands in");
        assertEquals(List.of("~,~,~", "10,64,-4"), List.copyOf(tree.values("at", actor, java.util.Map.of())));
        Standing looking = new Standing() { @Override public Optional<Vec3> target() { return Optional.of(new Vec3(3, 70, 8)); } };
        assertEquals(List.of("~", "3"), List.copyOf(tree.values("x", looking, java.util.Map.of())), "the looked-at block comes before the player's own");
        assertEquals(List.of("~,~,~", "3,70,8"), List.copyOf(tree.values("at", looking, java.util.Map.of())));
        assertEquals(List.of("stop", actor.mob.toString()), List.copyOf(tree.values("mob", actor, java.util.Map.of())), "a mob has no name; nearby ones by UUID");
        assertTrue(tree.values("uuid", actor, java.util.Map.of()).contains(actor.player().orElseThrow().toString()), "state deletes take a UUID, not a name");
        assertTrue(tree.values("value", actor, java.util.Map.of()).contains("reset"));
        assertTrue(tree.values("expires", actor, java.util.Map.of()).contains("7d"));
        assertEquals(CommandCompletions.PROOF_BODIES, List.copyOf(tree.values("body", actor, java.util.Map.of())), "previews offer preview bodies, not NPC types");
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
