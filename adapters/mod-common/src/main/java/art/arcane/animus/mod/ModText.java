package art.arcane.animus.mod;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.text.TextPart;
import java.util.List;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Player-facing text as styled vanilla components. Every mod text path goes through here so hex colours render. */
final class ModText {
    private ModText() {}

    static MutableComponent component(String text) { return component(text, Style.EMPTY); }

    /** {@code base} styles every run first, so a window item name can stay upright while its codes still colour it. */
    static MutableComponent component(String text, Style base) { return styled(RichText.runs(text), base); }

    /** Text that is already section-coded, such as hotload lines, whose values must not be read as colour codes again. */
    static MutableComponent section(String coloured) { return styled(RichText.sectionRuns(coloured), Style.EMPTY); }

    private static MutableComponent styled(List<RichText.Run> runs, Style base) {
        MutableComponent root = Component.empty();
        for (RichText.Run run : runs) {
            Style style = base;
            if (run.rgb().isPresent()) style = style.withColor(TextColor.fromRgb(run.rgb().getAsInt()));
            if (run.bold()) style = style.withBold(Boolean.TRUE);
            if (run.italic()) style = style.withItalic(Boolean.TRUE);
            if (run.underlined()) style = style.withUnderlined(Boolean.TRUE);
            if (run.strikethrough()) style = style.withStrikethrough(Boolean.TRUE);
            if (run.obfuscated()) style = style.withObfuscated(Boolean.TRUE);
            root.append(Component.literal(run.text()).setStyle(style));
        }
        return root;
    }

    static Component parts(List<TextPart> parts) {
        MutableComponent line = Component.empty();
        for (TextPart part : parts) {
            Style style = Style.EMPTY;
            if (part.command().isPresent()) style = style.withClickEvent(new ClickEvent.RunCommand(part.command().get()));
            if (part.hover().isPresent()) style = style.withHoverEvent(new HoverEvent.ShowText(component(part.hover().get())));
            line.append(component(part.text()).setStyle(style));
        }
        return line;
    }
}
