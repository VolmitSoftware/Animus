package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** {@code /animus} help: topics, inline descriptions, hover and click on every entry, and pages that fit the chat. */
class AnimusHelpTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
    private final DirectorRuntimeNode root = director.engine().getRoot();

    private AnimusHelp.Page rootPage(int index) { return AnimusHelp.page(root, visible(root), index); }

    private static List<DirectorRuntimeNode> visible(DirectorRuntimeNode group) {
        return group.getChildren().stream().filter((DirectorRuntimeNode child) -> !child.getDescriptor().isHidden()).toList();
    }

    @Test void theRootIsGroupedByTopicWithLifecycleFirst() {
        AnimusHelp.Page page = rootPage(0);
        List<String> titles = page.sections().stream().map(AnimusHelp.Section::title).toList();
        assertEquals(List.of("NPCs", "Movement", "Combat", "Content", "Services", "Behaviors", "Groups", "Diagnostics", "Tools"), titles);
        List<String> npcs = page.sections().getFirst().entries().stream().map((DirectorRuntimeNode node) -> node.getDescriptor().getName()).toList();
        assertEquals(List.of("status", "list", "create", "inspect", "edit", "clone", "delete", "spawn", "despawn", "select", "selection", "validate", "reload", "rollback", "npc"), npcs);
        assertFalse(page.entries().stream().anyMatch((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals("choice")), "the hidden click target stays out");
    }

    @Test void everyEntryShowsItsDescriptionInlineWithUsageAndExampleOnHover() {
        ChatLine create = AnimusHelp.entry(child(root, "create"));
        String text = create.toString();
        assertTrue(text.startsWith("⇀ create <id> [type=minecraft:player] [name=] [folder=] [+4]"), text);
        assertTrue(text.endsWith("Save a new despawned NPC where you stand; the console gives world, x, y and z"), text);
        Component component = create.component();
        ClickEvent click = first(component);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/animus create ", ((ClickEvent.Payload.Text) click.payload()).value());
        String hover = PLAIN.serialize(hovers(component).getFirst());
        assertTrue(hover.contains("/animus create <id> [type=minecraft:player] [name=] [folder=] [world=] [x=] [y=] [z=]"), hover);
        assertTrue(hover.contains("Example: /animus create gate-guard"), hover);
        ChatLine validate = AnimusHelp.entry(child(root, "validate"));
        assertEquals(ClickEvent.Action.RUN_COMMAND, first(validate.component()).action(), "a command without parameters runs on click");
        ChatLine behaviors = AnimusHelp.entry(child(root, "behaviors"));
        assertEquals("/animus behaviors help", ((ClickEvent.Payload.Text) first(behaviors.component()).payload()).value());
        assertTrue(behaviors.toString().contains("Load, run, trace and cancel behaviors"), "a group line shows its own description: " + behaviors);
    }

    @Test void pagesFitTheChatAndListEveryEntryOnce() {
        int pages = AnimusHelp.pageCount(rootPage(0));
        assertTrue(pages > 1, "the root needs several pages: " + pages);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < pages; index++) {
            List<ChatLine> lines = AnimusHelp.lines(rootPage(index));
            int rows = lines.stream().mapToInt(AnimusHelp::rows).sum();
            assertTrue(rows <= 20, "page " + (index + 1) + " fills " + rows + " rows");
            assertTrue(lines.getFirst().toString().contains("/animus (" + (index + 1) + "/" + pages + ")"), lines.getFirst().toString());
            ChatLine bar = lines.getLast();
            assertTrue(bar.toString().contains("Page " + (index + 1) + " of " + pages), bar.toString());
            List<String> arrows = runs(bar.component());
            if (index + 1 < pages) assertTrue(arrows.contains("/animus help=" + (index + 2)), arrows.toString());
            if (index > 0) assertTrue(arrows.contains("/animus help=" + index), arrows.toString());
            for (ChatLine line : lines) if (line.toString().startsWith("⇀ ")) assertTrue(seen.add(line.toString().split(" ")[1]), "listed twice: " + line);
        }
        assertEquals(rootPage(0).entries().size(), seen.size());
        assertEquals(text(AnimusHelp.lines(rootPage(pages - 1))), text(AnimusHelp.lines(rootPage(pages + 5))), "past the end shows the last page");
    }

    @Test void aCutTopicRepeatsItsHeadingOnTheNextPage() {
        for (int index = 1; index < AnimusHelp.pageCount(rootPage(0)); index++) {
            ChatLine second = AnimusHelp.lines(rootPage(index)).get(1);
            assertTrue(second.visible().endsWith("[Docs]"), "page " + (index + 1) + " starts with a topic heading: " + second.visible());
        }
    }

    @Test void everyGroupRendersCleanLinesAndEachCommandFitsTwoRows() {
        List<DirectorRuntimeNode> groups = new ArrayList<>();
        groups(root, groups);
        List<String> broken = new ArrayList<>();
        for (DirectorRuntimeNode group : groups) {
            AnimusHelp.Page page = AnimusHelp.page(group, visible(group), 0);
            for (int index = 0; index < AnimusHelp.pageCount(page); index++)
                for (ChatLine line : AnimusHelp.lines(AnimusHelp.page(group, visible(group), index))) {
                    String plain = PLAIN.serialize(line.component());
                    if (plain.contains("&") || plain.contains("§") || plain.contains("\n")) broken.add(group.path() + ": " + plain);
                    if (AnimusHelp.rows(line) > 3) broken.add(group.path() + " too long: " + plain);
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

    private static List<String> text(List<ChatLine> lines) { return lines.stream().map(ChatLine::toString).toList(); }

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
