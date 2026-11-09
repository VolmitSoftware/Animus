package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnimusDirectorTest {
    /** A console or player holding exactly the given nodes; output is kept as plain text. */
    static class Actor implements CommandActor {
        final List<String> lines = new ArrayList<>();
        final List<AnimusHelp.Page> pages = new ArrayList<>();
        final Set<String> nodes;
        final Optional<UUID> player;
        Actor(boolean player, String... nodes) { this.player = player ? Optional.of(UUID.randomUUID()) : Optional.empty(); this.nodes = Set.of(nodes); }
        @Override public boolean permitted(String node) { return nodes.contains(node); }
        @Override public void tell(String message) { lines.add(RichText.plain(art.arcane.animus.core.text.Palette.reply(message))); }
        @Override public void plain(String line) { lines.add(RichText.plain(line)); }
        @Override public Optional<UUID> player() { return player; }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
        @Override public void help(AnimusHelp.Page page) { pages.add(page); }
        String text() { return String.join("\n", lines); }
        /** The names the last help page lists, A-Z. */
        List<String> entries() { return pages.getLast().entries().stream().map((DirectorRuntimeNode node) -> node.getDescriptor().getName()).sorted().toList(); }
    }

    /** A small tree that records what each command received. */
    @Director(name = "probe")
    static final class Probe {
        final List<String> calls = new ArrayList<>();
        Group group = new Group(calls);

        @Director void spawn(@Param(name = "npc") String npc, @Param(name = "speed", defaultValue = "2.0") double speed, @Param(name = "loud", defaultValue = "false") boolean loud) {
            calls.add("spawn " + npc + " " + speed + " " + loud);
        }

        @Director void create(@Param(name = "id") String id, @Param(name = "name") String name, @Param(name = "group", defaultValue = "default") String group) {
            calls.add("create " + id + " [" + name + "] " + group);
        }

        @Director void edit(@Param(name = "npc") String npc, @Param(name = "patch") String patch) { calls.add("edit " + npc + " " + patch); }

        @Director(hidden = true) @AnimusPermission("") void secret(@Param(name = "token") String token) { calls.add("secret " + token); }

        @Director @AnimusPermission("probe.open") void open() { calls.add("open"); }
    }

    @Director(name = "group")
    static final class Group {
        final List<String> calls;
        Group() { this(new ArrayList<>()); }
        Group(List<String> calls) { this.calls = calls; }
        @Director void run(@Param(name = "first") int first, @Param(name = "second") int second) { calls.add("run " + first + " " + second); }
    }

    private final Probe probe = new Probe();
    private final AnimusDirector director = new AnimusDirector(probe, (String parameter, CommandActor actor) -> parameter.equals("npc") ? List.of("guard", "smith") : List.of());

    private void run(Actor actor, String... words) { director.execute(actor, words); }

    @Test void requiredValuesBindPositionallyAndOptionalsOnlyByKey() {
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        run(admin, "spawn", "guard");
        run(admin, "spawn", "guard", "speed=3.5", "loud=true");
        run(admin, "spawn", "loud=true", "npc=smith");
        run(admin, "group", "run", "1", "2");
        assertEquals(List.of("spawn guard 2.0 false", "spawn guard 3.5 true", "spawn smith 2.0 true", "run 1 2"), probe.calls);
    }

    @Test void strayWordsAndUnknownKeysFailWithTheUsageLine() {
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        run(admin, "spawn", "guard", "3.5");
        assertTrue(admin.text().contains("Unexpected \"3.5\"; optional values are key=value"), admin.text());
        assertTrue(admin.text().contains("Usage: /probe spawn <npc> [speed=2.0] [loud=false]"), admin.text());
        admin.lines.clear();
        run(admin, "spawn", "guard", "colour=red");
        assertTrue(admin.text().contains("Unknown parameter key: colour"), admin.text());
        admin.lines.clear();
        run(admin, "spawn");
        assertTrue(admin.text().contains("Missing argument \"npc\""), admin.text());
        assertFalse(admin.text().contains("(String)"), "no Java type in the refusal: " + admin.text());
        admin.lines.clear();
        run(admin, "group", "run", "1", "two");
        assertTrue(admin.text().contains("Cannot convert \"two\""), admin.text());
        assertTrue(probe.calls.isEmpty(), "no refused command ran: " + probe.calls);
    }

    @Test void quotedValuesAndJsonObjectsArriveWhole() {
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        run(admin, "create", "guard", "\"Town", "Guard\"", "group=\"North", "Gate\"");
        run(admin, "edit", "guard", "{\"name\":", "\"A", "B\",", "\"spawned\":true}");
        run(admin, "edit", "guard", "patch={\"text\":\"a=b\"}");
        assertEquals(List.of("create guard [Town Guard] North Gate", "edit guard {\"name\": \"A B\", \"spawned\":true}", "edit guard {\"text\":\"a=b\"}"), probe.calls);
    }

    @Test void permissionsGateExecutionHelpAndCompletion() {
        Actor visitor = new Actor(true, "probe.open");
        run(visitor, "spawn", "guard");
        assertTrue(visitor.text().contains("No permission: animus.admin; ask an operator to grant it"), visitor.text());
        run(visitor, "open");
        assertEquals(List.of("open"), probe.calls);
        run(visitor);
        assertEquals(List.of("open"), visitor.entries(), "help lists only what the actor may run");
        assertEquals(List.of("open"), director.complete(visitor, new String[]{""}));
        assertEquals(List.of(), director.complete(visitor, new String[]{"spawn", ""}));
        Actor nobody = new Actor(true);
        run(nobody, "group");
        assertTrue(nobody.text().contains("No permission: animus."), nobody.text());
    }

    @Test void completionOffersBareValuesOnlyForTheNextRequiredParameter() {
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        assertEquals(List.of("create", "edit", "group", "spawn"), director.complete(admin, new String[]{""}), "open needs its own node");
        assertEquals(List.of("guard", "smith", "speed=", "loud="), director.complete(admin, new String[]{"spawn", ""}));
        assertEquals(List.of("speed=", "loud="), director.complete(admin, new String[]{"spawn", "guard", ""}), "no bare values once the required ones are given");
        assertEquals(List.of("loud=true", "loud=false"), director.complete(admin, new String[]{"spawn", "guard", "loud="}));
        assertEquals(List.of("loud="), director.complete(admin, new String[]{"spawn", "guard", "speed=1", "l"}));
        assertEquals(List.of("run"), director.complete(admin, new String[]{"group", "r"}));
    }

    @Test void hiddenCommandsStayOutOfHelpAndAnswerEveryRefusalTheSame() {
        Actor visitor = new Actor(true);
        run(visitor, "secret", "abc");
        assertEquals(List.of("secret abc"), probe.calls, "a hidden command needs no permission");
        run(visitor, "secret", "abc", "forged");
        run(visitor, "secret");
        assertEquals(List.of("[Animus]: " + ChoiceCommands.NOT_AVAILABLE, "[Animus]: " + ChoiceCommands.NOT_AVAILABLE), visitor.lines, "no usage line, no reason");
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        run(admin);
        assertFalse(admin.entries().contains("secret"));
        assertFalse(director.complete(admin, new String[]{"se"}).contains("secret"));
    }

    @Test void groupsShowHelpAndUnknownWordsAreRefused() {
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        run(admin, "group");
        assertEquals("/probe group", admin.pages.getLast().group().path());
        run(admin, "help");
        assertEquals("/probe", admin.pages.getLast().group().path());
        int shown = admin.pages.size();
        run(admin, "nothing-like-this");
        assertTrue(admin.text().contains("Unknown command \"nothing-like-this\" in /probe"), admin.text());
        assertTrue(admin.text().contains("Usage: /probe <command>"), admin.text());
        assertEquals(shown, admin.pages.size(), "an unknown word shows no help");
    }

    @Test void anUnknownCommandIsRefusedWithItsUsageAndNoHelp() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        for (List<String> typed : List.of(List.of("nope"), List.of("npc", "nope"), List.of("navigation", "nope", "extra"))) {
            Actor admin = new Actor(true, AnimusDirector.ADMIN);
            List<CommandSound> heard = new ArrayList<>();
            CommandActor listening = new Actor(true, AnimusDirector.ADMIN) {
                @Override public void tell(String message) { admin.tell(message); }
                @Override public void plain(String line) { admin.plain(line); }
                @Override public void help(AnimusHelp.Page page) { admin.help(page); }
                @Override public void play(CommandSound sound) { heard.add(sound); }
            };
            real.execute(listening, typed.toArray(String[]::new));
            assertTrue(admin.pages.isEmpty(), typed + " showed help");
            assertTrue(admin.text().contains("Unknown command \"" + typed.get(typed.size() == 3 ? 1 : typed.size() - 1) + "\""), typed + " -> " + admin.text());
            String group = typed.size() == 1 ? "/animus" : "/animus " + typed.getFirst();
            assertTrue(admin.text().contains("Usage: " + group + " <command>"), typed + " -> " + admin.text());
            assertEquals(List.of(CommandSound.FAILURE), heard);
        }
        Actor paged = new Actor(true, AnimusDirector.ADMIN);
        real.execute(paged, new String[]{"behaviors", "help", "2"});
        assertEquals("/animus behaviors", paged.pages.getLast().group().path());
        assertEquals(1, paged.pages.getLast().index(), "help N is page N, as help=N is");
        assertTrue(AnimusHelp.lines(paged.pages.getLast()).stream().noneMatch((String line) -> line.matches("(?s).*run_command:[^>]*(help|page)=.*")),
                "help links run help N");
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"npc"});
        assertEquals("/animus npc", admin.pages.getLast().group().path(), "a group name still shows its help");
        assertTrue(admin.lines.isEmpty(), admin.text());
    }

    @Test void completionOffersNothingAfterAnUnknownWord() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        assertEquals(List.of(), real.complete(admin, new String[]{"nope"}));
        assertEquals(List.of(), real.complete(admin, new String[]{"nope", ""}));
    }

    /** Every real command maps its required words and keyed defaults; it then fails only for lack of services. */
    @Test void everyAnimusCommandMapsItsArguments() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        List<DirectorRuntimeNode> commands = new ArrayList<>();
        collect(real.engine().getRoot(), commands);
        assertTrue(commands.size() > 90, "the whole tree is built: " + commands.size());
        for (DirectorRuntimeNode command : commands) {
            List<String> words = new ArrayList<>(List.of(command.path().substring("/animus ".length()).split(" ")));
            for (DirectorParameterDescriptor parameter : command.getDescriptor().getParameters()) {
                if (parameter.isRequired()) words.add(sample(parameter.getType()));
                else words.add(parameter.getName() + "=" + (parameter.getDefaultValue().isEmpty() ? sample(parameter.getType()) : parameter.getDefaultValue()));
            }
            Actor everything = new Actor(command.getDescriptor().getOrigin() != art.arcane.volmlib.util.director.DirectorOrigin.CONSOLE,
                    AnimusDirector.ADMIN, "animus.status", "animus.debug", "animus.debug.mode", "animus.build.donate");
            real.execute(everything, words.toArray(String[]::new));
            String text = everything.text();
            assertFalse(text.contains("Unexpected \"") || text.contains("Unknown parameter") || text.contains("Missing argument")
                    || text.contains("Cannot convert") || text.contains("Cannot parse default") || text.contains("Usage:") && !text.contains("Failed to execute"),
                    command.path() + " " + words + " -> " + text);
            Actor stray = new Actor(true, AnimusDirector.ADMIN, "animus.status", "animus.debug", "animus.debug.mode", "animus.build.donate");
            words.add("stray-word");
            real.execute(stray, words.toArray(String[]::new));
            if (!command.getDescriptor().isHidden()) assertTrue(stray.text().contains("Unexpected \"stray-word\"") && stray.text().contains("Usage: " + command.path()), command.path() + " -> " + stray.text());
        }
        art.arcane.animus.core.AnimusLog.shared().debug(false);
    }

    @Test void theAnimusTreeKeepsItsPermissionNodes() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor visitor = new Actor(true, "animus.status", "animus.build.donate");
        real.execute(visitor, new String[0]);
        assertEquals(List.of("build", "status"), visitor.entries());
        real.execute(visitor, new String[]{"build"});
        assertEquals(List.of("donate"), visitor.entries());
        assertEquals(List.of("build", "status"), real.complete(visitor, new String[]{""}));
        Actor debugger = new Actor(true, "animus.debug");
        real.execute(debugger, new String[]{"debug"});
        assertEquals(List.of("network", "npc", "performance", "summary", "watch"), debugger.entries(), "mode needs its own node");
        real.execute(debugger, new String[]{"list"});
        assertTrue(debugger.text().contains("No permission: animus."));
    }

    @Test void debugModeTogglesTheSharedGateInMemory() {
        art.arcane.animus.core.AnimusLog log = art.arcane.animus.core.AnimusLog.shared();
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        try {
            Actor operator = new Actor(true, "animus.debug", "animus.debug.mode");
            log.debug(false);
            real.execute(operator, new String[]{"debug", "mode"});
            assertTrue(log.debug());
            assertTrue(operator.text().contains("Debug mode on: every NPC's route, spawn and packet diagnostics log until restart"), operator.text());
            real.execute(operator, new String[]{"debug", "mode", "enabled=off"});
            assertFalse(log.debug());
            assertTrue(operator.text().contains("Debug mode off: per-NPC diagnostics summarize every"), operator.text());
            real.execute(operator, new String[]{"debug", "mode", "enabled=maybe"});
            assertTrue(operator.text().contains("Unknown state maybe"), operator.text());
            Actor reader = new Actor(true, "animus.debug");
            real.execute(reader, new String[]{"debug", "mode", "enabled=on"});
            assertFalse(log.debug(), "reading diagnostics does not allow switching debug mode");
            assertTrue(reader.text().contains("No permission: animus."), reader.text());
        } finally { log.debug(false); }
    }

    @Test void navigationGoCentresWholeBlockCoordinates() {
        assertEquals(10.5, WorldCommands.Navigation.blockCenter(10));
        assertEquals(-3.5, WorldCommands.Navigation.blockCenter(-4));
        assertEquals(10.25, WorldCommands.Navigation.blockCenter(10.25), "an exact point stays");
        assertEquals(0.5, WorldCommands.Navigation.blockCenter(0));
    }

    @Test void npcReferencesResolveThroughNestedFolders() throws Exception {
        art.arcane.animus.core.npc.NpcDefinition smith = art.arcane.animus.core.npc.NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\""
                + UUID.randomUUID() + "\",\"id\":\"smith\",\"type\":\"minecraft:player\",\"group\":\"world/town/market\",\"name\":\"Smith\",\"spawned\":false,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}");
        java.util.function.Function<String, Optional<art.arcane.animus.core.npc.NpcDefinition>> find = (String id) -> id.equals("smith") ? Optional.of(smith) : Optional.empty();
        assertEquals(Optional.of("smith"), CommandGroup.located(find, "world/town/market/smith"));
        assertEquals(Optional.of("smith"), CommandGroup.located(find, "npcs/world/town/market/smith.json"));
        assertEquals(Optional.empty(), CommandGroup.located(find, "world/town/smith"), "another folder is a different NPC");
        assertEquals(Optional.empty(), CommandGroup.located(find, "npcs/../smith.json"));
        assertEquals(Optional.empty(), CommandGroup.located(find, "world/town/market/ghost"));
    }

    private static void collect(DirectorRuntimeNode node, List<DirectorRuntimeNode> out) {
        if (node.isInvocable()) out.add(node);
        for (DirectorRuntimeNode child : node.getChildren()) collect(child, out);
    }

    private static String sample(Class<?> type) {
        if (type == int.class || type == Integer.class) return "1";
        if (type == double.class || type == Double.class) return "1.5";
        if (type == boolean.class || type == Boolean.class) return "true";
        if (type == UUID.class) return UUID.randomUUID().toString();
        if (type.isEnum()) return ((Enum<?>) type.getEnumConstants()[0]).name().toLowerCase(java.util.Locale.ROOT);
        return "sample";
    }
}
