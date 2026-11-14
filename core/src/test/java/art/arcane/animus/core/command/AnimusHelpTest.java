package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** {@code /animus} help in VolmLib's shared menu: topics, VolmLib's entry lines with hover and click, and pages that fit the chat. */
class AnimusHelpTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
    private final DirectorRuntimeNode root = director.engine().getRoot();

    private AnimusHelp.Page rootPage(int index) { return AnimusHelp.page(root, visible(root), index); }

    private static List<DirectorRuntimeNode> visible(DirectorRuntimeNode group) {
        return group.getChildren().stream().filter((DirectorRuntimeNode child) -> !child.getDescriptor().isHidden()).toList();
    }

    @Test void theRootIsGroupedByTopicWithTheNewcomerToolsFirst() {
        AnimusHelp.Page page = rootPage(0);
        List<String> titles = page.sections().stream().map(AnimusHelp.Section::title).toList();
        assertEquals(List.of("Tools", "NPCs", "Movement", "Recordings", "Combat", "Content", "Services", "Web editor", "Quests", "Behaviors", "Trait groups", "Diagnostics"), titles);
        assertEquals(List.of("setup", "gui", "tools"), page.sections().getFirst().entries().stream().map((DirectorRuntimeNode node) -> node.getDescriptor().getName()).toList());
        String first = String.join("\n", AnimusHelp.lines(page));
        assertTrue(first.contains("setup") && first.contains("gui") && first.contains("tools"), "setup, gui and tools are on the first page");
        assertTrue(first.contains("Open the Animus menu; "), "gui says first that it opens the menu");
        List<String> npcs = page.sections().get(1).entries().stream().map((DirectorRuntimeNode node) -> node.getDescriptor().getName()).toList();
        assertEquals(List.of("status", "list", "create", "mythic", "inspect", "edit", "clone", "delete", "spawn", "despawn", "undo", "select", "selection", "validate", "reload",
                "rollback", "npc"), npcs);
        assertFalse(page.entries().stream().anyMatch((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals("choice")), "the hidden click target stays out");
    }

    @Test void everyEntryIsDrawnAsVolmLibDrawsItWithTheDescriptionOnHover() {
        Component create = MINI.deserialize(AnimusHelp.entry(child(root, "create")));
        assertEquals("⇀ create [id] ⊰type⊱ ⊰name⊱ ⊰folder⊱ ⊰world⊱ ⊰x⊱ ⊰y⊱ ⊰z⊱ ⊰spawned⊱", PLAIN.serialize(create));
        ClickEvent click = first(create);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/animus create ", ((ClickEvent.Payload.Text) click.payload()).value());
        String hover = PLAIN.serialize(hovers(create).getFirst());
        assertTrue(hover.startsWith("create\n✎ Make an NPC where you stand"), hover);
        assertTrue(hover.contains("✦ " + AnimusDirector.usage(child(root, "create"))), hover);
        assertFalse(hover.contains("<String>"), "the example names arguments as the list line does: " + hover);
        assertEquals(ClickEvent.Action.RUN_COMMAND, first(MINI.deserialize(AnimusHelp.entry(child(root, "validate")))).action(), "a command without parameters runs on click");
        Component behaviors = MINI.deserialize(AnimusHelp.entry(child(root, "behaviors")));
        assertEquals("/animus behaviors help=1", ((ClickEvent.Payload.Text) first(behaviors).payload()).value());
        assertEquals("⇀ behaviors - Load, run, trace and cancel behaviors", PLAIN.serialize(behaviors), "a group shows its own description");
        DirectorRuntimeNode navigation = child(root, "navigation");
        String volmLib = DirectorMiniMenu.render(new DirectorMiniMenu.DirectorHelpPage(navigation, List.of(child(navigation, "go")), 0, 1), AnimusDirector.THEME, AnimusDirector.TEXT).get(2);
        String ours = AnimusHelp.entry(child(navigation, "go"));
        assertEquals(volmLib.substring(0, volmLib.indexOf("✦")), ours.substring(0, ours.indexOf("✦")), "the line is VolmLib's own up to its example");
    }

    @Test void pagesFitTheChatAndListEveryEntryOnce() {
        int pages = AnimusHelp.pageCount(rootPage(0));
        assertTrue(pages > 1, "the root needs several pages: " + pages);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < pages; index++) {
            List<String> lines = AnimusHelp.lines(rootPage(index));
            int rows = lines.stream().mapToInt(DirectorMiniMenu::chatRows).sum();
            assertTrue(rows <= DirectorMiniMenu.CHAT_LINE_COUNT, "page " + (index + 1) + " fills " + rows + " rows");
            assertEquals(DirectorMiniMenu.banner("/animus", AnimusDirector.THEME), lines.getFirst(), "the shared banner");
            String bar = PLAIN.serialize(MINI.deserialize(lines.getLast()));
            List<String> arrows = runs(MINI.deserialize(lines.getLast()));
            if (index + 1 < pages) {
                assertTrue(bar.endsWith("Page " + (index + 2) + " ❭"), bar);
                assertTrue(arrows.contains("/animus help " + (index + 2)), arrows.toString());
            }
            if (index > 0) {
                assertTrue(bar.startsWith("〈 Page " + index), bar);
                assertTrue(arrows.contains("/animus help " + index), arrows.toString());
            }
            for (String line : lines) {
                String plain = PLAIN.serialize(MINI.deserialize(line));
                if (plain.startsWith("⇀ ")) assertTrue(seen.add(plain.split(" ")[1]), "listed twice: " + plain);
            }
        }
        assertEquals(rootPage(0).entries().size(), seen.size());
        assertEquals(AnimusHelp.lines(rootPage(pages - 1)), AnimusHelp.lines(rootPage(pages + 5)), "past the end shows the last page");
    }

    @Test void recordingsAndTheWebEditorLinkTheirOwnPages() {
        assertEquals(art.arcane.animus.core.text.DocsLink.RECORDINGS, AnimusHelp.topic(child(root, "record")).docs());
        assertEquals(art.arcane.animus.core.text.DocsLink.PAIRING, AnimusHelp.topic(child(child(root, "editor"), "pair")).docs());
    }

    @Test void theShortPageListsTheCommonCommandsInlineAndLinksEveryCommand() {
        List<DirectorRuntimeNode> common = List.of(child(root, "create"), child(child(root, "editor"), "pair"), child(root, "list"));
        AnimusHelp.Page page = AnimusHelp.common(root, common);
        assertTrue(page.brief());
        List<art.arcane.animus.core.text.ChatLine> lines = AnimusHelp.brief(page);
        assertEquals(List.of("[Animus]: Common commands; click one to use it", "⇀ create - Make an NPC where you stand, or at x y z",
                "⇀ editor pair - Pair the web editor with a one-time link", "⇀ list - List saved NPCs; click an id to inspect it", "[All commands]"),
                lines.stream().map((art.arcane.animus.core.text.ChatLine line) -> PLAIN.serialize(line.component())).toList());
        Component create = lines.get(1).component();
        ClickEvent fill = first(create);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, fill.action());
        assertEquals("/animus create ", ((ClickEvent.Payload.Text) fill.payload()).value());
        assertTrue(PLAIN.serialize(hovers(create).getFirst()).startsWith("/animus create <id> [type=minecraft:player]"), "the hover is the usage line");
        assertEquals(List.of("/animus help all"), runs(lines.getLast().component()));
    }

    @Test void aCutTopicRepeatsItsHeadingOnTheNextPage() {
        for (int index = 1; index < AnimusHelp.pageCount(rootPage(0)); index++) {
            String second = PLAIN.serialize(MINI.deserialize(AnimusHelp.lines(rootPage(index)).get(1)));
            assertTrue(second.endsWith("[Docs]"), "page " + (index + 1) + " starts with a topic heading: " + second);
        }
    }

    @Test void aGroupPageMatchesTheSharedMenuWithItsBackLink() {
        DirectorRuntimeNode rigs = child(root, "rigs");
        List<String> lines = AnimusHelp.lines(AnimusHelp.page(rigs, visible(rigs), 0));
        assertEquals(DirectorMiniMenu.banner("/animus rigs", AnimusDirector.THEME), lines.get(0));
        assertEquals(AnimusHelp.clicks(DirectorMiniMenu.backLink("/animus help=1", AnimusDirector.THEME, AnimusDirector.TEXT)), lines.get(1), "help links run help N");
        assertTrue(PLAIN.serialize(MINI.deserialize(lines.get(2))).startsWith("⇀ "), "entries follow the back link without a heading: " + lines.get(2));
    }

    @Test void everyGroupRendersCleanLinesThatFitTheChat() {
        List<DirectorRuntimeNode> groups = new ArrayList<>();
        groups(root, groups);
        List<String> broken = new ArrayList<>();
        for (DirectorRuntimeNode group : groups) {
            AnimusHelp.Page page = AnimusHelp.page(group, visible(group), 0);
            for (int index = 0; index < AnimusHelp.pageCount(page); index++) {
                List<String> lines = AnimusHelp.lines(AnimusHelp.page(group, visible(group), index));
                if (lines.stream().mapToInt(DirectorMiniMenu::chatRows).sum() > DirectorMiniMenu.CHAT_LINE_COUNT) broken.add(group.path() + " page " + (index + 1) + " overflows");
                for (String line : lines) {
                    String plain = PLAIN.serialize(MINI.deserialize(line));
                    if (plain.contains("&") || plain.contains("§") || plain.contains("<#")) broken.add(group.path() + ": " + plain);
                }
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    @Test void theConsoleGetsEveryEntryAsPlainText() {
        AnimusDirectorTest.Actor console = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN) {
            @Override public void help(AnimusHelp.Page page) { AnimusHelp.console(page).forEach(this::plain); }
        };
        director.execute(console, new String[]{"rigs"});
        assertEquals("--- /animus rigs ---", console.lines.getFirst());
        assertTrue(console.lines.contains("  play <npc> <animation> [layer=0] [speed=1] [loop=default] - Play an animation on an NPC's rig"), console.text());
        console.lines.clear();
        director.execute(console, new String[0]);
        assertTrue(console.lines.stream().anyMatch((String line) -> line.startsWith("NPCs: https://docs.volmitsoftware.com/animus/02-commands-permissions#")), console.text());
        assertTrue(console.lines.contains("  list [page=1] - List saved NPCs; click an id to inspect it"), console.text());
        assertTrue(console.lines.stream().noneMatch((String line) -> line.contains("§") || line.contains("<#")), console.text());
    }

    @Test void usageReadsTheSameEverywhere() {
        assertEquals("/animus combat attack <npc> <target> [damage=1]", AnimusDirector.usage(child(child(root, "combat"), "attack")));
        assertEquals("/animus combat status [npc=]", AnimusDirector.usage(child(child(root, "combat"), "status")));
    }

    private static DirectorRuntimeNode child(DirectorRuntimeNode group, String name) {
        return group.getChildren().stream().filter((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals(name)).findFirst().orElseThrow();
    }

    private static List<Component> hovers(Component component) {
        List<Component> found = new ArrayList<>();
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.value() instanceof Component shown) found.add(shown);
        for (Component child : component.children()) found.addAll(hovers(child));
        return found;
    }

    private static List<String> runs(Component component) {
        List<String> found = new ArrayList<>();
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) found.add(((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : component.children()) found.addAll(runs(child));
        return found;
    }

    private static ClickEvent first(Component component) {
        if (component.clickEvent() != null) return component.clickEvent();
        for (Component child : component.children()) { ClickEvent found = first(child); if (found != null) return found; }
        return null;
    }

    private static void groups(DirectorRuntimeNode node, List<DirectorRuntimeNode> out) {
        if (node.isInvocable()) return;
        out.add(node);
        for (DirectorRuntimeNode child : node.getChildren()) groups(child, out);
    }
}
