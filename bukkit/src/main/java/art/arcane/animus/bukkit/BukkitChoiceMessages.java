package art.arcane.animus.bukkit;

import art.arcane.animus.core.text.TextPart;
import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

/** Maps formatted chat parts onto BungeeCord components: section codes become styles, never commands. */
public final class BukkitChoiceMessages {
    private BukkitChoiceMessages() {}

    public static BaseComponent[] components(List<TextPart> parts) {
        List<BaseComponent> components = new ArrayList<>(parts.size());
        for (TextPart part : parts) {
            TextComponent piece = new TextComponent(TextComponent.fromLegacyText(part.text()));
            part.command().ifPresent((String command) -> piece.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
            part.hover().ifPresent((String hover) -> piece.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover)))));
            components.add(piece);
        }
        return components.toArray(new BaseComponent[0]);
    }
}
