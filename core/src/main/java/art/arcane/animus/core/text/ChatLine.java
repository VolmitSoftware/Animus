package art.arcane.animus.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * One reply line with click chips such as {@code [Click to copy]}. A player gets hover text and the click; the console,
 * which cannot click, gets {@link #console()}: the text with each chip's value written out once. Build a line with the
 * factories and chip methods, or let {@link #parse} find transaction ids and links in a finished reply.
 */
public final class ChatLine {
    /** The Animus pages of the Volmit wiki. */
    public static final String DOCS = "https://docs.volmitsoftware.com/animus/";
    private static final Pattern TRANSACTION = Pattern.compile("\\btx ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b");
    private static final Pattern URL = Pattern.compile("https?://[^\\s§]+");
    private static final Pattern HASH = Pattern.compile("\\b[0-9a-f]{64}\\b");
    private static final int SHORT_ID = 8, SHORT_HASH = 12;

    private enum Click { COPY, OPEN, SUGGEST }

    /** A clickable label. {@code console} is what the console sees in its place, or null for nothing. */
    private record Chip(String label, Palette tone, Click click, String value, String hover, String console) {}

    private final List<Object> pieces = new ArrayList<>();

    private ChatLine() {}

    /** Section-coded or {@code &}-coded text as it is, without the prefix. */
    public static ChatLine of(String text) { return new ChatLine().text(text); }

    /** A plain reply in the Animus look; see {@link Palette#reply}. */
    public static ChatLine reply(String message) { return new ChatLine().raw(Palette.reply(message)); }

    /** The prefix, then the result in semantic green. */
    public static ChatLine success(String message) { return new ChatLine().raw(Palette.success(message)); }

    /** The prefix, then the reason in semantic red. */
    public static ChatLine error(String message) { return new ChatLine().raw(Palette.error(message)); }

    /**
     * A finished reply with its transaction ids, long ids and links made clickable: {@code tx <uuid>} becomes a short id
     * that copies the full one plus {@code [Undo]}, which suggests the rollback command; a 64-character hex id, such as an
     * import id, shows its first 12 characters and copies the full id; a wiki link becomes {@code [Docs]}; any other link
     * becomes {@code [Open]} and {@code [Click to copy]}. A reply with none of these comes back as one text piece.
     */
    public static ChatLine parse(String coloured) {
        ChatLine line = new ChatLine();
        if (coloured == null) return line;
        List<Matcher> matchers = List.of(TRANSACTION.matcher(coloured), HASH.matcher(coloured), URL.matcher(coloured));
        int index = 0;
        while (true) {
            Matcher match = null;
            for (Matcher candidate : matchers)
                if (candidate.find(index) && (match == null || candidate.start() < match.start())) match = candidate;
            if (match == null) break;
            line.raw(coloured.substring(index, match.start()));
            int end = match.end();
            if (match.pattern() == TRANSACTION) line.raw("tx ").transaction(match.group(1));
            else if (match.pattern() == HASH) line.copy(match.group().substring(0, SHORT_HASH), match.group(), "Click to copy the full id");
            else {
                String url = trimUrl(match.group());
                end = match.start() + url.length();
                if (url.startsWith(DOCS)) line.docs(url);
                else line.link("Open", url);
            }
            index = end;
        }
        return line.raw(coloured.substring(index));
    }

    /** {@code https://docs.volmitsoftware.com/animus/<page>#<anchor>}; an empty anchor links the page. */
    public static String docsUrl(String page, String anchor) { return DOCS + page + (anchor.isEmpty() ? "" : "#" + anchor); }

    /** Text with {@code &} or section codes. */
    public ChatLine text(String text) { return raw(RichText.colorize(text)); }

    /** Text that is already section-coded, whose values must not be read as colour codes again. */
    private ChatLine raw(String coloured) {
        if (coloured != null && !coloured.isEmpty()) pieces.add(coloured);
        return this;
    }

    /** {@code label} in pink; a click copies {@code value}. The console sees the value. */
    public ChatLine copy(String label, String value, String hover) { return chip(new Chip(label, label.startsWith("[") ? Palette.PINK_LIGHT : Palette.PINK, Click.COPY, value, hover, value)); }

    /** A value shown as itself in pink that copies itself, such as an NPC id or coordinates. */
    public ChatLine copyable(String value) { return copy(value, value, "Click to copy"); }

    /** A bracketed label that opens {@code url}. The console sees the URL. */
    public ChatLine open(String label, String url, String hover) { return chip(new Chip("[" + label + "]", Palette.PINK_LIGHT, Click.OPEN, url, hover, url)); }

    /** A bracketed label that puts {@code command} in the player's chat box to edit and send. The console sees nothing. */
    public ChatLine suggest(String label, String command, String hover) { return chip(new Chip("[" + label + "]", Palette.PINK_LIGHT, Click.SUGGEST, command, hover, null)); }

    /** {@code [<label>]} that opens the link and {@code [Click to copy]} that copies it; the console sees the link once. */
    public ChatLine link(String label, String url) {
        open(label, url, "Open in your browser");
        return chip(new Chip("[Click to copy]", Palette.PINK_LIGHT, Click.COPY, url, "Copy the link", url));
    }

    /**
     * {@code [<label>]} whose hover shows {@code lines} and whose click copies them, one per line, for a bug report. The
     * console sees nothing in its place, so send it the lines themselves.
     */
    public ChatLine details(String label, List<String> lines) {
        String text = String.join("\n", lines.stream().map(RichText::plain).toList());
        return chip(new Chip("[" + label + "]", Palette.PINK_LIGHT, Click.COPY, text, text + "\n\nClick to copy", null));
    }

    /** {@code [Docs]} for a wiki section. */
    public ChatLine docs(DocsLink link) { return docs(link.url()); }

    private ChatLine docs(String url) { return open("Docs", url, "Open the Animus wiki"); }

    /** The first characters of a transaction id, copying the full id, then {@code [Undo]} suggesting its rollback. */
    public ChatLine transaction(String id) {
        chip(new Chip(id.substring(0, Math.min(SHORT_ID, id.length())), Palette.PINK, Click.COPY, id, "Click to copy the transaction id", id));
        return suggest("Undo", "/animus rollback " + id, "Undo this change with /animus rollback");
    }

    /** True when the line has a chip; a line without one sends as plain text. */
    public boolean interactive() { return pieces.stream().anyMatch(Chip.class::isInstance); }

    /**
     * Section-coded text for a receiver that cannot click: each value chip as its value, in the surrounding colour, and each
     * bracketed chip as its console text once, so a link with both [Open] and [Click to copy] prints one URL.
     */
    public String console() {
        StringBuilder out = new StringBuilder();
        List<String> shown = new ArrayList<>();
        for (Object piece : pieces) {
            if (piece instanceof String text) { out.append(text); continue; }
            Chip chip = (Chip) piece;
            boolean bracketed = chip.label().startsWith("[");
            if (chip.console() == null || bracketed && shown.contains(chip.console())) continue;
            shown.add(chip.console());
            if (bracketed) space(out);
            out.append(chip.console());
        }
        return out.toString().stripTrailing();
    }

    /** The line as an Adventure component, for a receiver that shows hover and click. */
    public Component component() {
        TextComponent.Builder line = Component.text();
        OptionalInt carried = OptionalInt.empty();
        for (Object piece : pieces) {
            if (piece instanceof String text) {
                for (RichText.Run run : RichText.sectionRuns(text)) {
                    OptionalInt colour = run.rgb().isPresent() ? run.rgb() : carried;
                    carried = colour;
                    line.append(Component.text(run.text(), style(run, colour)));
                }
                continue;
            }
            Chip chip = (Chip) piece;
            ClickEvent click = switch (chip.click()) {
                case COPY -> ClickEvent.copyToClipboard(chip.value());
                case OPEN -> ClickEvent.openUrl(chip.value());
                case SUGGEST -> ClickEvent.suggestCommand(chip.value());
            };
            line.append(Component.text(chip.label(), TextColor.color(chip.tone().rgb()))
                    .hoverEvent(HoverEvent.showText(Component.text(chip.hover(), TextColor.color(Palette.GREY.rgb()))))
                    .clickEvent(click));
        }
        return line.build();
    }

    /** A bracketed chip stands apart from what is before it; a value chip sits inline, as the value would. */
    private ChatLine chip(Chip chip) {
        Objects.requireNonNull(chip.value());
        boolean bracketed = chip.label().startsWith("[");
        if (bracketed && !pieces.isEmpty() && pieces.getLast() instanceof String text && !text.endsWith(" ")) pieces.add(" ");
        else if (!pieces.isEmpty() && pieces.getLast() instanceof Chip) pieces.add(" ");
        pieces.add(chip);
        return this;
    }

    private static Style style(RichText.Run run, OptionalInt colour) {
        Style.Builder style = Style.style();
        if (colour.isPresent()) style.color(TextColor.color(colour.getAsInt()));
        if (run.bold()) style.decoration(TextDecoration.BOLD, true);
        if (run.italic()) style.decoration(TextDecoration.ITALIC, true);
        if (run.underlined()) style.decoration(TextDecoration.UNDERLINED, true);
        if (run.strikethrough()) style.decoration(TextDecoration.STRIKETHROUGH, true);
        if (run.obfuscated()) style.decoration(TextDecoration.OBFUSCATED, true);
        return style.build();
    }

    private static void space(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') out.append(' ');
    }

    /** A link at the end of a sentence keeps its full stop outside. */
    private static String trimUrl(String url) {
        int end = url.length();
        while (end > 0 && ".,;:)'\"".indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }

    /** The console text without colour codes. */
    @Override public String toString() { return RichText.plain(console()); }
}
