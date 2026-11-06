package art.arcane.animus.mod;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.text.TextPart;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Player-facing text as styled vanilla components. Every mod text path goes through here so hex colours render. */
final class ModText {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

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

    /**
     * A lenient MiniMessage line, such as a help page row, as a vanilla component with its colours, decorations, hover
     * text and run or suggest clicks. Fonts are dropped.
     */
    static MutableComponent miniMessage(String line) { return converted(MINI_MESSAGE.deserialize(line)); }

    /** An Adventure component, such as a {@link art.arcane.animus.core.text.ChatLine}, as a vanilla component. */
    static MutableComponent adventure(net.kyori.adventure.text.Component source) { return converted(source); }

    private static MutableComponent converted(net.kyori.adventure.text.Component source) {
        MutableComponent out = Component.literal(source instanceof net.kyori.adventure.text.TextComponent text ? text.content() : "");
        out.setStyle(style(source.style()));
        for (net.kyori.adventure.text.Component child : source.children()) out.append(converted(child));
        return out;
    }

    private static Style style(net.kyori.adventure.text.format.Style source) {
        Style style = Style.EMPTY;
        if (source.color() != null) style = style.withColor(TextColor.fromRgb(source.color().value()));
        style = style.withBold(decoration(source, net.kyori.adventure.text.format.TextDecoration.BOLD))
                .withItalic(decoration(source, net.kyori.adventure.text.format.TextDecoration.ITALIC))
                .withUnderlined(decoration(source, net.kyori.adventure.text.format.TextDecoration.UNDERLINED))
                .withStrikethrough(decoration(source, net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH))
                .withObfuscated(decoration(source, net.kyori.adventure.text.format.TextDecoration.OBFUSCATED));
        net.kyori.adventure.text.event.ClickEvent click = source.clickEvent();
        if (click != null && click.payload() instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text text) {
            if (click.action() == net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND) style = style.withClickEvent(new ClickEvent.RunCommand(text.value()));
            else if (click.action() == net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND) style = style.withClickEvent(new ClickEvent.SuggestCommand(text.value()));
            else if (click.action() == net.kyori.adventure.text.event.ClickEvent.Action.COPY_TO_CLIPBOARD) style = style.withClickEvent(new ClickEvent.CopyToClipboard(text.value()));
            else if (click.action() == net.kyori.adventure.text.event.ClickEvent.Action.OPEN_URL) style = style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(text.value())));
        }
        net.kyori.adventure.text.event.HoverEvent<?> hover = source.hoverEvent();
        if (hover != null && hover.value() instanceof net.kyori.adventure.text.Component shown) style = style.withHoverEvent(new HoverEvent.ShowText(converted(shown)));
        return style;
    }

    /** True or false when the line sets the decoration, null to inherit. */
    private static Boolean decoration(net.kyori.adventure.text.format.Style source, net.kyori.adventure.text.format.TextDecoration decoration) {
        return switch (source.decoration(decoration)) {
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NOT_SET -> null;
        };
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
