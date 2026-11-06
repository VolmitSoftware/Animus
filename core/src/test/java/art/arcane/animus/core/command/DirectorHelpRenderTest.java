package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Every {@code /animus} help page, round-tripped through real, lenient MiniMessage as players receive it. */
class DirectorHelpRenderTest {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final List<String> FORBIDDEN = List.of("show_text", "hover:", "click:", "run_command", "suggest_command", "gradient", "font:", "<#", "\\<");

    @Test void everyHelpLineIsCleanMiniMessageWithHoverAndClick() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        List<DirectorRuntimeNode> groups = new ArrayList<>();
        groups(director.engine().getRoot(), groups);
        List<String> broken = new ArrayList<>();
        int entries = 0;
        for (DirectorRuntimeNode group : groups) {
            List<DirectorRuntimeNode> visible = group.getChildren().stream().filter((DirectorRuntimeNode child) -> !child.getDescriptor().isHidden()).toList();
            DirectorMiniMenu.DirectorHelpPage first = new DirectorMiniMenu.DirectorHelpPage(group, visible, 0, DirectorMiniMenu.MAX_ENTRIES_PER_PAGE);
            int pages = DirectorMiniMenu.helpWindow(first, AnimusDirector.THEME, AnimusDirector.TEXT).pages();
            for (int page = 0; page < pages; page++) {
                for (String line : DirectorMiniMenu.render(new DirectorMiniMenu.DirectorHelpPage(group, visible, page, DirectorMiniMenu.MAX_ENTRIES_PER_PAGE), AnimusDirector.THEME, AnimusDirector.TEXT)) {
                    Component parsed = MINI_MESSAGE.deserialize(line);
                    String plain = PLAIN.serialize(parsed);
                    for (String token : FORBIDDEN) if (plain.contains(token)) broken.add(group.path() + ": leaked " + token + " in " + plain);
                    if (plain.contains("\n")) broken.add(group.path() + ": spans lines: " + plain);
                    for (Component shownHover : hovers(parsed)) {
                        String shown = PLAIN.serialize(shownHover);
                        for (String token : FORBIDDEN) if (shown.contains(token)) broken.add(group.path() + ": hover leaked " + token + " in " + shown);
                    }
                    if (line.contains("⇀")) entries++;
                }
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken));
        assertTrue(entries > 100, "every group lists its commands: " + entries);
    }

    @Test void rootEntriesClickIntoGroupsAndSuggestCommands() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode root = director.engine().getRoot();
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN);
        director.execute(admin, new String[0]);
        List<String> lines = DirectorMiniMenu.render(admin.pages.getLast(), AnimusDirector.THEME, AnimusDirector.TEXT);
        String banner = PLAIN.serialize(MINI_MESSAGE.deserialize(lines.getFirst()));
        assertTrue(banner.contains("/animus"), banner);
        String behaviors = lines.stream().filter((String line) -> PLAIN.serialize(MINI_MESSAGE.deserialize(line)).startsWith("⇀ behaviors")).findFirst().orElseThrow();
        ClickEvent click = first(MINI_MESSAGE.deserialize(behaviors));
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/animus behaviors help=1", ((ClickEvent.Payload.Text) click.payload()).value());
        String create = lines.stream().filter((String line) -> PLAIN.serialize(MINI_MESSAGE.deserialize(line)).startsWith("⇀ create")).findFirst().orElseThrow();
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, first(MINI_MESSAGE.deserialize(create)).action());
        assertTrue(create.contains("#FF5FA8") && create.contains("#E0408C"), "Animus pinks: " + create);
        assertFalse(lines.stream().anyMatch((String line) -> line.contains("choice")), "the hidden click command is not listed");
        assertTrue(PLAIN.serialize(hovers(MINI_MESSAGE.deserialize(create)).getFirst()).contains("Create a dormant NPC definition"), "entries carry their description in a hover");
    }

    @Test void consoleHelpIsPlainText() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusDirectorTest.Actor console = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN) {
            @Override public void menu(DirectorMiniMenu.DirectorHelpPage page) { DirectorMiniMenu.renderConsole(page, AnimusDirector.TEXT).forEach(this::plain); }
        };
        director.execute(console, new String[]{"rigs"});
        assertEquals("--- /animus rigs ---", console.lines.getFirst());
        assertTrue(console.lines.stream().anyMatch((String line) -> line.startsWith("play <npc=...> <animation=...> [layer=0]")), console.text());
        assertTrue(console.lines.stream().noneMatch((String line) -> line.contains("<#")), console.text());
    }

    private static List<Component> hovers(Component component) {
        List<Component> found = new ArrayList<>();
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.value() instanceof Component shown) found.add(shown);
        for (Component child : component.children()) found.addAll(hovers(child));
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
