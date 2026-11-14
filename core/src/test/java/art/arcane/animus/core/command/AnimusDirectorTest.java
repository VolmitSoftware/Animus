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
        @Override public boolean permitted(String node) { return PermissionNodes.granted(nodes::contains, node); }
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

        @Director @Positional({"setting", "value"})
        void health(@Param(name = "npc") String npc, @Param(name = "setting", defaultValue = "") String setting, @Param(name = "value", defaultValue = "") String value) {
            calls.add("health " + npc + " [" + setting + "] [" + value + "]");
        }

        @Director @Positional("page") void list(@Param(name = "page", defaultValue = "1") int page, @Param(name = "loud", defaultValue = "false") boolean loud) {
            calls.add("list " + page + " " + loud);
        }
    }

    @Director(name = "group")
    static final class Group {
        final List<String> calls;
        Group() { this(new ArrayList<>()); }
        Group(List<String> calls) { this.calls = calls; }
        @Director void run(@Param(name = "first") int first, @Param(name = "second") int second) { calls.add("run " + first + " " + second); }
    }

    /** A command that reports a change, as a save does. */
    @Director(name = "saver")
    static final class Saver {
        @Director @AnimusPermission("probe.shop") void save() { AnimusDirector.actor().tell(art.arcane.animus.core.text.Palette.success("Saved bakery; tx 0f1e2d3c-4b5a-4968-8776-655443322110")); }
    }

    @Test void aChangeIsRememberedWithTheNodeItsCommandNeeded() {
        RecentChanges changes = new RecentChanges();
        AnimusDirector saver = new AnimusDirector(new Saver(), (String parameter, CommandActor actor, java.util.Map<String, String> given) -> List.of(),
                (String plugin) -> true, (UUID player) -> false, () -> changes);
        Actor keeper = new Actor(true, "probe.shop");
        saver.execute(keeper, new String[]{"save"});
        assertEquals("probe.shop", changes.latest(keeper.player().orElseThrow()).orElseThrow().node());
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
        assertTrue(admin.text().contains("Unexpected \"3.5\"; write speed=3.5"), admin.text());
        assertFalse(admin.text().contains("page=2"), "the refusal names this command's own key: " + admin.text());
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
        assertTrue(admin.text().contains("second takes a number"), admin.text());
        assertFalse(admin.text().contains("Cannot convert") || admin.text().contains(" int "), "no Java type in the refusal: " + admin.text());
        assertTrue(probe.calls.isEmpty(), "no refused command ran: " + probe.calls);
    }

    @Test void bareWordsFillTheOptionalValuesACommandNamesInOrder() {
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        run(admin, "health", "guard");
        run(admin, "health", "guard", "killable");
        run(admin, "health", "guard", "killable", "on");
        run(admin, "health", "guard", "setting=maximum", "20");
        run(admin, "health", "guard", "value=off", "regenerate");
        run(admin, "list", "2");
        run(admin, "list", "loud=true", "3");
        assertEquals(List.of("health guard [] []", "health guard [killable] []", "health guard [killable] [on]", "health guard [maximum] [20]",
                "health guard [regenerate] [off]", "list 2 false", "list 3 true"), probe.calls);
        run(admin, "health", "guard", "killable", "on", "extra");
        assertTrue(admin.text().contains("Unexpected \"extra\"; /probe health takes no more values"), admin.text());
        admin.lines.clear();
        run(admin, "list", "2", "true");
        assertTrue(admin.text().contains("Unexpected \"true\"; write loud=true"), "an optional value outside the list still needs its key: " + admin.text());
        assertEquals(7, probe.calls.size(), "no refused command ran");
    }

    @Test void completionOffersTheNextBareOptionalValue() {
        AnimusDirector named = new AnimusDirector(probe, (String parameter, CommandActor actor, java.util.Map<String, String> given) -> switch (parameter) {
            case "npc" -> List.of("guard");
            case "setting" -> List.of("killable", "maximum");
            case "value" -> given.containsKey("setting") ? List.of("value-for-" + given.get("setting")) : List.of("reset");
            default -> List.of();
        }, (String plugin) -> true);
        Actor admin = new Actor(false, AnimusDirector.ADMIN);
        assertEquals(List.of("killable", "maximum", "setting=", "value="), named.complete(admin, new String[]{"health", "guard", ""}));
        assertEquals(List.of("value-for-killable", "value="), named.complete(admin, new String[]{"health", "guard", "killable", ""}));
        assertEquals(List.of("value=value-for-maximum"), named.complete(admin, new String[]{"health", "guard", "setting=maximum", "value="}));
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
        assertEquals(List.of("create", "edit", "group", "health", "list", "spawn"), director.complete(admin, new String[]{""}), "open needs its own node");
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
        assertTrue(admin.text().contains("Unknown command \"nothing-like-this\" /probe help"), admin.text());
        assertFalse(admin.text().contains("Did you mean"), "nothing is close: " + admin.text());
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
            String group = typed.size() == 1 ? "/animus" : "/animus " + typed.getFirst();
            assertTrue(admin.text().contains("Unknown command \"" + typed.get(typed.size() == 3 ? 1 : typed.size() - 1) + "\"" + (typed.size() == 1 ? "" : " in " + group)),
                    typed + " -> " + admin.text());
            assertTrue(admin.text().endsWith(" " + group + " help"), "a [Help] chip for the group: " + typed + " -> " + admin.text());
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

    @Test void anUnknownCommandNamesTheNearestOneWhenItIsClose() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"creat", "guard"});
        assertEquals("[Animus]: Unknown command \"creat\". Did you mean create? /animus help", admin.lines.getLast());
        real.execute(admin, new String[]{"npc", "skn"});
        assertEquals("[Animus]: Unknown command \"skn\" in /animus npc. Did you mean skin? /animus npc help", admin.lines.getLast());
        real.execute(admin, new String[]{"desp"});
        assertTrue(admin.lines.getLast().contains("Did you mean despawn?"), "a prefix counts: " + admin.lines.getLast());
        real.execute(admin, new String[]{"xy"});
        assertFalse(admin.lines.getLast().contains("Did you mean"), "two letters that share nothing are not close: " + admin.lines.getLast());
        assertEquals(java.util.Optional.of("create"), AnimusDirector.nearest(List.of("clone", "create", "delete"), "craete"));
        assertEquals(java.util.Optional.empty(), AnimusDirector.nearest(List.of("clone", "create"), "zzzzzz"));
    }

    @Test void helpForACommandShowsItsDescriptionUsageAndDocs() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"help", "create"});
        assertTrue(admin.pages.isEmpty(), "one command is not a help page");
        assertEquals("[Animus]: create: Make an NPC where you stand, or at x y z", admin.lines.get(0));
        assertTrue(admin.lines.get(1).startsWith("[Animus]: Usage: /animus create <id> [type=minecraft:player] [name=]"), admin.lines.get(1));
        assertTrue(admin.lines.get(1).endsWith("https://docs.volmitsoftware.com/animus/02-commands-permissions#status-and-npc-management"), admin.lines.get(1));
        admin.lines.clear();
        real.execute(admin, new String[]{"help", "npc", "skin"});
        assertTrue(admin.lines.getFirst().startsWith("[Animus]: npc skin: "), admin.text());
        assertTrue(admin.text().contains("Usage: /animus npc skin <npc> <player>"), admin.text());
        admin.lines.clear();
        real.execute(admin, new String[]{"help", "record", "start"});
        assertTrue(admin.text().endsWith(art.arcane.animus.core.text.DocsLink.RECORDINGS.url()), admin.text());
        real.execute(admin, new String[]{"help", "npc"});
        assertEquals("/animus npc", admin.pages.getLast().group().path(), "a group name opens its page");
        admin.lines.clear();
        real.execute(admin, new String[]{"help", "routes"});
        assertEquals(List.of("[Animus]: No help for \"routes\" /animus help"), admin.lines);
        Actor visitor = new Actor(true, "animus.status");
        real.execute(visitor, new String[]{"help", "delete"});
        assertTrue(visitor.text().contains("No permission: animus.npc.delete"), visitor.text());
        assertEquals(List.of("create"), real.complete(admin, new String[]{"help", "cre"}), "help completes command names");
        assertEquals(List.of("skin"), real.complete(admin, new String[]{"help", "npc", "sk"}));
        assertTrue(real.complete(admin, new String[]{"help", "a"}).contains("all"));
        assertEquals(List.of(), real.complete(admin, new String[]{"help", "create", ""}), "no values after a command name");
    }

    @Test void aSimplePlayerGetsTheShortHelpAndAllCommandsIsOneClickAway() {
        AnimusDirector simple = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor, java.util.Map<String, String> given) -> List.of(),
                (String plugin) -> true, (UUID player) -> true, RecentChanges::new);
        Actor player = new Actor(true, AnimusDirector.ADMIN);
        simple.execute(player, new String[]{"help"});
        AnimusHelp.Page brief = player.pages.getLast();
        assertTrue(brief.brief());
        assertEquals(List.of("/animus gui", "/animus create", "/animus select", "/animus spawn", "/animus despawn", "/animus delete", "/animus list", "/animus undo",
                "/animus editor pair"), brief.entries().stream().map(DirectorRuntimeNode::path).toList());
        assertEquals("⇀ gui - Open the Animus menu", AnimusHelp.brief(brief).get(1).visible(), "the menu's line calls it the Animus menu");
        player.pages.clear();
        simple.execute(player, new String[0]);
        assertTrue(player.pages.getLast().brief(), "a bare /animus is the same short page");
        simple.execute(player, new String[]{"help", "all"});
        assertFalse(player.pages.getLast().brief(), "help all is the whole tree");
        simple.execute(player, new String[]{"help", "2"});
        assertFalse(player.pages.getLast().brief(), "a page number is a page of the whole tree");
        assertEquals(1, player.pages.getLast().index());
        Actor console = new Actor(false, AnimusDirector.ADMIN);
        simple.execute(console, new String[]{"help"});
        assertFalse(console.pages.getLast().brief(), "the console reads every command");
        Actor viewer = new Actor(true, PermissionNodes.NPC_VIEW);
        simple.execute(viewer, new String[0]);
        assertEquals(List.of("/animus select", "/animus list"), viewer.pages.getLast().entries().stream().map(DirectorRuntimeNode::path).toList(),
                "only what the player may run");
        Actor advanced = new Actor(true, AnimusDirector.ADMIN);
        new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of()).execute(advanced, new String[]{"help"});
        assertFalse(advanced.pages.getLast().brief(), "Advanced keeps the whole tree");
    }

    /** A probe with a menu, as {@code /animus gui} and {@code /animus tools} open it, and a {@code gui} group of its own commands. */
    @Director(name = "probe")
    static final class WithMenu {
        final List<String> calls = new ArrayList<>();
        Tools tools = new Tools(calls);
        Gui gui = new Gui(calls);
        @Director void list() { calls.add("list"); }
    }

    @Director(name = ToolkitCommands.GROUP, aliases = "tool")
    @AnimusPermission(PermissionNodes.TOOLS)
    static final class Tools {
        final List<String> calls;
        Tools(List<String> calls) { this.calls = calls; }
        @Director(name = ToolkitCommands.MENU, hidden = true) void menu() { calls.add("menu"); }
        @Director(name = ToolkitCommands.HOME, hidden = true) void home() { calls.add("home"); }
        @Director void wand() { calls.add("wand"); }
    }

    @Director(name = ToolkitCommands.GUI, aliases = "guis")
    @AnimusPermission(PermissionNodes.GUI)
    static final class Gui {
        final List<String> calls;
        Gui(List<String> calls) { this.calls = calls; }
        @Director void reload() { calls.add("reload"); }
    }

    @Test void theBareCommandShowsHelpAndGuiOpensTheMenuAtHome() {
        WithMenu menu = new WithMenu();
        AnimusDirector director = new AnimusDirector(menu, (String parameter, CommandActor actor) -> List.of());
        Actor builder = new Actor(true, PermissionNodes.TOOLS);
        director.execute(builder, new String[0]);
        assertTrue(menu.calls.isEmpty(), "a bare /probe opens nothing: " + menu.calls);
        assertEquals(1, builder.pages.size(), "a bare /probe is help");
        assertEquals("/probe", builder.pages.getLast().group().path());
        director.execute(builder, new String[]{"gui"});
        director.execute(builder, new String[]{"GUI"});
        director.execute(builder, new String[]{"guis"});
        assertEquals(List.of("home", "home", "home"), menu.calls, "/probe gui opens Home, and so does the alias a client suggests beside it");
        director.execute(builder, new String[]{"tools"});
        director.execute(builder, new String[]{"tool"});
        assertEquals(List.of("home", "home", "home", "menu", "menu"), menu.calls, "/probe tools still opens the selected NPC's screen");
        director.execute(builder, new String[]{"gui", "reload"});
        assertEquals(5, menu.calls.size(), "the gui commands keep their own node");
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        director.execute(admin, new String[]{"gui", "reload"});
        assertEquals("reload", menu.calls.getLast());
        director.execute(admin, new String[]{"guis", "help"});
        assertEquals("/probe gui", admin.pages.getLast().group().path(), "help lists the gui commands");
        Actor visitor = new Actor(true, "probe.other");
        director.execute(visitor, new String[]{"gui"});
        Actor console = new Actor(false, AnimusDirector.ADMIN);
        director.execute(console, new String[]{"gui"});
        director.execute(console, new String[0]);
        assertEquals(6, menu.calls.size(), "no menu without the node, and none for the console: " + menu.calls);
        assertEquals(List.of("/probe gui", "/probe"), console.pages.stream().map((AnimusHelp.Page page) -> page.group().path()).toList(),
                "the console gets the gui commands, then the root help");
    }

    @Test void helpForGuiSaysItOpensTheMenuAndLeadsToTheGuiCommands() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor builder = new Actor(true, PermissionNodes.TOOLS);
        real.execute(builder, new String[]{"help", "gui"});
        assertTrue(builder.pages.isEmpty(), "no paged help that wipes the chat");
        assertEquals("[Animus]: gui: Open the Animus menu", builder.lines.getFirst());
        assertTrue(builder.lines.get(1).contains("Usage: /animus gui"), builder.text());
        assertFalse(builder.text().contains("/animus gui help"), "nothing a menu user may not run");
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"help", "gui"});
        assertTrue(admin.pages.isEmpty());
        assertTrue(admin.text().contains("/animus gui help"), "one click to the chest GUI commands: " + admin.text());
        Actor console = new Actor(false, AnimusDirector.ADMIN);
        real.execute(console, new String[]{"help", "gui"});
        assertEquals("/animus gui", console.pages.getLast().group().path(), "the console reads the gui commands");
    }

    @Test void guiIsOfferedToEveryPlayerWhoMayOpenTheMenu() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor builder = new Actor(true, PermissionNodes.TOOLS);
        assertTrue(real.complete(builder, new String[]{""}).contains("gui"), "a menu user without animus.gui still completes gui");
        assertEquals(List.of("gui"), real.complete(builder, new String[]{"gu"}));
        assertEquals(List.of(), real.complete(builder, new String[]{"gui", ""}), "the gui commands stay behind animus.gui");
        builder.lines.clear();
        real.execute(builder, new String[]{"giu"});
        assertTrue(builder.text().contains("Did you mean gui?"), builder.text());
        Actor viewer = new Actor(true, PermissionNodes.NPC_VIEW);
        assertFalse(real.complete(viewer, new String[]{""}).contains("gui"), "no menu, no gui");
    }

    @Test void theMenuCommandsStayOutOfHelp() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"help", "tools"});
        assertEquals(List.of("mode", "wand"), admin.entries());
        assertEquals(List.of("[Animus]: No help for \"tools menu\" /animus help"), help(real, "tools", "menu"));
        assertEquals(List.of("[Animus]: No help for \"tools home\" /animus help"), help(real, "tools", "home"));
    }

    private static List<String> help(AnimusDirector director, String... topic) {
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        List<String> words = new ArrayList<>(List.of("help"));
        words.addAll(List.of(topic));
        director.execute(admin, words.toArray(String[]::new));
        return admin.lines;
    }

    @Test void groupsAnswerToTheOtherNumberToo() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        java.util.Map<String, String> spelled = java.util.Map.of("shops", "shop", "builds", "build", "records", "record", "behavior", "behaviors",
                "module", "modules", "rig", "rigs", "group", "groups", "imports", "import");
        spelled.forEach((String typed, String group) -> {
            Actor admin = new Actor(true, AnimusDirector.ADMIN);
            real.execute(admin, new String[]{typed});
            assertEquals("/animus " + group, admin.pages.getLast().group().path(), typed);
        });
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"import"});
        assertEquals(List.of("apply", "beautyquests", "betonquest", "citizens", "denizen", "gloss", "plan", "preview", "quests", "shops", "stage"), admin.entries(), "every import lives under /animus import");
        real.execute(admin, new String[]{"shop"});
        assertFalse(admin.entries().contains("import"));
        real.execute(admin, new String[]{"gui", "help"});
        assertFalse(admin.entries().contains("import"));
        assertTrue(admin.entries().contains("export"));
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
                    AnimusDirector.ADMIN, "animus.status", "animus.debug", "animus.debug.mode", "animus.build.donate", PermissionNodes.QUESTS_USE);
            real.execute(everything, words.toArray(String[]::new));
            String text = everything.text();
            assertFalse(text.contains("Unexpected \"") || text.contains("Unknown parameter") || text.contains("Missing argument")
                    || text.contains("Cannot convert") || text.contains("Cannot parse default") || text.contains("Usage:") && !text.contains("Failed to execute"),
                    command.path() + " " + words + " -> " + text);
            Actor stray = new Actor(true, AnimusDirector.ADMIN, "animus.status", "animus.debug", "animus.debug.mode", "animus.build.donate", PermissionNodes.QUESTS_USE);
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
        art.arcane.animus.core.npc.NpcDefinition smith = art.arcane.animus.core.npc.NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\""
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

    private static final List<String> LINKS = List.of("beautyquests", "betonquest", "quests");

    /** Integration commands show in help, completion and the client tree only while their plugin runs; typed anyway, they name it. */
    @Test void integrationCommandsNeedTheirPlugin() {
        AnimusDirector without = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of(), (String plugin) -> false);
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        without.execute(admin, new String[]{"import"});
        assertTrue(admin.entries().stream().noneMatch(LINKS::contains), admin.entries().toString());
        without.execute(admin, new String[]{"npc"});
        assertFalse(admin.entries().contains("model"));
        assertFalse(admin.entries().contains("disguise"));
        without.execute(admin, new String[]{"help"});
        assertFalse(admin.entries().contains("mythic"));
        assertTrue(without.complete(admin, new String[]{"import", ""}).stream().noneMatch(LINKS::contains));
        assertFalse(without.complete(admin, new String[]{"npc", ""}).contains("model"));
        assertFalse(without.complete(admin, new String[]{""}).contains("mythic"));
        assertEquals(List.of(), without.complete(admin, new String[]{"npc", "model", ""}));
        assertFalse(without.complete(admin, new String[]{"create", "guard", ""}).contains("mythic="), "create takes no Mythic mob");
        for (String[] typed : List.of(new String[]{"npc", "model", "guard"}, new String[]{"npc", "disguise", "guard", "zombie"}, new String[]{"import", "quests", "preview"},
                new String[]{"import", "beautyquests", "apply"}, new String[]{"import", "betonquest", "preview"},
                new String[]{"mythic", "boss", "SkeletonKing"})) {
            admin.lines.clear();
            without.execute(admin, typed);
            assertEquals(1, admin.lines.size(), admin.text());
            assertTrue(admin.lines.getFirst().matches(".* needs (ModelEngine|LibsDisguises|Quests|BeautyQuests|BetonQuest|MythicMobs)"), admin.text());
        }
        AnimusDirector with = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of(), (String plugin) -> true);
        with.execute(admin, new String[]{"import"});
        assertTrue(admin.entries().containsAll(LINKS), admin.entries().toString());
        with.execute(admin, new String[]{"npc"});
        assertTrue(admin.entries().containsAll(List.of("model", "disguise")));
        with.execute(admin, new String[]{"help"});
        assertTrue(admin.entries().contains("mythic"));
        assertTrue(with.complete(admin, new String[]{"import", ""}).containsAll(LINKS));
        assertTrue(with.complete(admin, new String[]{""}).contains("mythic"));
    }

    @Test void eachIntegrationAnswersToItsOwnPlugin() {
        AnimusDirector models = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of(), "ModelEngine"::equals);
        Actor admin = new Actor(true, AnimusDirector.ADMIN);
        models.execute(admin, new String[]{"npc"});
        assertTrue(admin.entries().contains("model"));
        assertFalse(admin.entries().contains("disguise"));
        models.execute(admin, new String[]{"import"});
        assertTrue(admin.entries().stream().noneMatch(LINKS::contains));
    }
}
