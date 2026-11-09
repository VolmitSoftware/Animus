package art.arcane.animus.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.AnimusCommandTree;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.AnimusHelp;
import art.arcane.animus.core.command.CommandActor;
import java.util.List;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class ModTextTest {
    private static Style styled(Component component, String text) {
        if (component.getContents().toString().contains(text) && component.getStyle().getClickEvent() != null) return component.getStyle();
        for (Component child : component.getSiblings()) { Style found = styled(child, text); if (found != null) return found; }
        return null;
    }

    @Test void helpLinesKeepTheirClicksHoversAndColours() {
        Component line = ModText.miniMessage("<hover:show_text:'<#FFB3D6>Lists NPCs'><click:suggest_command:/animus list ><#FF5FA8>list</#FF5FA8></click></hover>");
        assertEquals("list", line.getString());
        Style style = styled(line, "list");
        assertInstanceOf(ClickEvent.SuggestCommand.class, style.getClickEvent());
        assertEquals("/animus list ", ((ClickEvent.SuggestCommand) style.getClickEvent()).command());
        assertEquals("Lists NPCs", ((HoverEvent.ShowText) style.getHoverEvent()).value().getString());
    }

    @Test void theDirectorTreeLoadsAndRendersItsHelpWithoutBukkit() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        List<String> lines = AnimusHelp.lines(AnimusHelp.page(director.engine().getRoot(), director.engine().getRoot().getChildren(), 0));
        assertTrue(lines.size() > 5, "a page of entries: " + lines.size());
        Component list = lines.stream().map(ModText::miniMessage).filter((Component line) -> line.getString().startsWith("⇀ list")).findFirst().orElseThrow();
        // VolmLib's gradient splits the name into one styled piece per letter; the click and hover sit on each piece.
        Style style = list.visit((Style piece, String text) -> piece.getClickEvent() == null ? java.util.Optional.<Style>empty() : java.util.Optional.of(piece), Style.EMPTY).orElseThrow();
        assertInstanceOf(ClickEvent.SuggestCommand.class, style.getClickEvent());
        assertEquals("/animus list ", ((ClickEvent.SuggestCommand) style.getClickEvent()).command());
        assertTrue(((HoverEvent.ShowText) style.getHoverEvent()).value().getString().contains("✦ /animus list [page=1]"));
    }
}
