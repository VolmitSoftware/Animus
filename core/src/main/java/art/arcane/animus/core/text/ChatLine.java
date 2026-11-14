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
 * factories and chip methods, or let {@link #parse} find transaction ids and links in a finished reply. A player in
 * Simple mode gets {@link #withoutTransactionIds()}: each change keeps its {@code [Undo]} but not its id.
 */
public final class ChatLine {
    /** The Animus pages of the Volmit wiki. */
    public static final String DOCS = "https://docs.volmitsoftware.com/animus/";
    /** A transaction id in a reply, with the {@code "; "} that leads it into the sentence when there is one. */
    private static final Pattern TRANSACTION = Pattern.compile("(?:; )?\\btx ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b");
    /** The command an {@code [Undo]} chip runs, before the change's short id. */
    public static final String UNDO = "/animus undo ";
    private static final Pattern URL = Pattern.compile("https?://[^\\s§]+");
    private static final Pattern HASH = Pattern.compile("\\b[0-9a-f]{64}\\b");
    /** An Animus command in a {@code Next:} line: words after {@code /animus}, ending at a semicolon, a comma and space, or the line's end. */
    private static final Pattern COMMAND = Pattern.compile("(?<![A-Za-z0-9_./])/animus(?:\\x20[A-Za-z0-9<>|_=:@./-]+(?:,[A-Za-z0-9<>|_=:@./-]+)*)*");
    private static final String NEXT = "Next: ";
    private static final int SHORT_ID = 8, SHORT_HASH = 12;

    private enum Click { COPY, OPEN, SUGGEST, RUN, NONE }

    /** A clickable label. {@code console} is what the console sees in its place, or null for nothing. */
    private record Chip(String label, Palette tone, Click click, String value, String hover, String console) {}

    /** {@code lead} (such as {@code "; tx "}) and the first characters of {@code id}, copying the full id; left out without ids. */
    private record Transaction(String lead, String id) {}

    private final List<Object> pieces = new ArrayList<>();
    private boolean ids = true;

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
     * that copies the full one plus {@code [Undo]}, which runs {@code /animus undo}; a 64-character hex id, such as an
     * import id, shows its first 12 characters and copies the full id; a wiki link becomes {@code [Docs]}; any other link
     * becomes {@code [Open]} and {@code [Click to copy]}; after {@code Next: }, each {@code /animus} command goes into the
     * player's chat box on click, cut before its first {@code <placeholder>}. A reply with none of these comes back as one text piece.
     */
    public static ChatLine parse(String coloured) {
        ChatLine line = new ChatLine();
        if (coloured == null) return line;
        List<Matcher> matchers = List.of(TRANSACTION.matcher(coloured), HASH.matcher(coloured), URL.matcher(coloured), COMMAND.matcher(coloured));
        int next = coloured.indexOf(NEXT);
        int commands = next < 0 ? -1 : next + NEXT.length();
        int index = 0;
        while (true) {
            Matcher match = null;
            for (Matcher candidate : matchers) {
                boolean command = candidate.pattern() == COMMAND;
                if (command && commands < 0) continue;
                if (candidate.find(command ? Math.max(index, commands) : index) && (match == null || candidate.start() < match.start())) match = candidate;
            }
            if (match == null) break;
            line.raw(coloured.substring(index, match.start()));
            int end = match.end();
            if (match.pattern() == TRANSACTION) line.transaction(match.group().substring(0, match.start(1) - match.start()), match.group(1)).undo(match.group(1));
            else if (match.pattern() == HASH) line.copy(match.group().substring(0, SHORT_HASH), match.group(), "Click to copy the full id");
            else if (match.pattern() == COMMAND) line.command(match.group());
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

    /** A bracketed label that runs {@code command} as the player, such as a page arrow or [Setup]. The console sees the command. */
    public ChatLine run(String label, String command, String hover) { return chip(new Chip("[" + label + "]", Palette.PINK_LIGHT, Click.RUN, command, hover, command)); }

    /**
     * {@code label} in {@code tone} that runs {@code command} when {@code run}, or puts it in the chat box to finish
     * otherwise, such as a help entry or a clickable id. The console sees the label.
     */
    public ChatLine command(String label, Palette tone, String command, boolean run, String hover) {
        return chip(new Chip(label, tone, run ? Click.RUN : Click.SUGGEST, command, hover, label));
    }

    /** {@code label} in {@code tone} with {@code hover} and no click, such as a parameter chip. The console sees the label. */
    public ChatLine hover(String label, Palette tone, String hover) { return chip(new Chip(label, tone, Click.NONE, "", hover, label)); }

    /** The command as itself in pink; a click puts it, up to its first placeholder, in the chat box. The console sees the command. */
    private ChatLine command(String command) {
        int placeholder = command.indexOf(" <");
        String typed = placeholder < 0 ? command : command.substring(0, placeholder + 1);
        return chip(new Chip(command, Palette.PINK, Click.SUGGEST, typed, "Click to put it in your chat box", command));
    }

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

    /** {@code [Path]} with the data-folder path on hover and on click to copy, so a reply can name a file without spelling it out. */
    public ChatLine path(String path) { return copy("[Path]", path, path + "\nClick to copy"); }

    /** {@code [Docs]} for a wiki section. */
    public ChatLine docs(DocsLink link) { return docs(link.url()); }

    private ChatLine docs(String url) { return open("Docs", url, "Open the Animus wiki"); }

    /** {@code "; tx "} and the first characters of a transaction id, copying the full id; see {@link #withoutTransactionIds()}. */
    public ChatLine transaction(String id) { return transaction("; tx ", id); }

    private ChatLine transaction(String lead, String id) {
        if (!pieces.isEmpty() && pieces.getLast() instanceof Chip) pieces.add(" ");
        pieces.add(new Transaction(lead, id));
        return this;
    }

    /** {@code [Undo]}, which runs {@code /animus undo} for this change by its first characters. The console sees nothing. */
    public ChatLine undo(String id) { return chip(new Chip("[Undo]", Palette.PINK_LIGHT, Click.RUN, UNDO + shortId(id), "Undo this change", null)); }

    /** This line for a player in Simple mode: every transaction id and its {@code tx} lead left out, each {@code [Undo]} kept. */
    public ChatLine withoutTransactionIds() {
        ChatLine line = new ChatLine();
        line.pieces.addAll(pieces);
        line.ids = false;
        return line;
    }

    /** The full transaction ids this line reports, in order. */
    public List<String> transactions() {
        return pieces.stream().filter(Transaction.class::isInstance).map((Object piece) -> ((Transaction) piece).id()).toList();
    }

    /** What the change was: the text before the first transaction id, without the prefix, such as {@code Deleted guard}. */
    public String lead() {
        StringBuilder out = new StringBuilder();
        for (Object piece : pieces) {
            if (piece instanceof Transaction) break;
            if (piece instanceof String text) RichText.sectionRuns(text).forEach((RichText.Run run) -> out.append(run.text()));
            else if (piece instanceof Chip chip && !chip.label().startsWith("[")) out.append(chip.label());
        }
        String text = out.toString();
        if (text.startsWith(PREFIX_PLAIN)) text = text.substring(PREFIX_PLAIN.length());
        return text.replaceFirst("[\\s;,:(]+$", "").strip();
    }

    private static final String PREFIX_PLAIN = RichText.plain(Palette.PREFIX);

    /** True when the line is a refusal: it starts as {@link Palette#error} does. */
    public boolean refusal() { return !pieces.isEmpty() && pieces.getFirst() instanceof String text && text.startsWith(Palette.error("")); }

    /** True when the line has a chip; a line without one sends as plain text. */
    public boolean interactive() { return pieces.stream().anyMatch((Object piece) -> piece instanceof Chip || piece instanceof Transaction); }

    /**
     * Section-coded text for a receiver that cannot click: each value chip as its value, in the surrounding colour, and each
     * bracketed chip as its console text once, so a link with both [Open] and [Click to copy] prints one URL.
     */
    public String console() {
        StringBuilder out = new StringBuilder();
        List<String> shown = new ArrayList<>();
        for (Object piece : pieces) {
            if (piece instanceof String text) { out.append(text); continue; }
            if (piece instanceof Transaction transaction) { if (ids) out.append(transaction.lead()).append(transaction.id()); continue; }
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
            if (piece instanceof Transaction transaction) {
                if (!ids) continue;
                for (RichText.Run run : RichText.sectionRuns(transaction.lead())) line.append(Component.text(run.text(), style(run, carried)));
                piece = new Chip(shortId(transaction.id()), Palette.PINK, Click.COPY, transaction.id(), "Click to copy the change id", transaction.id());
            }
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
                case RUN -> ClickEvent.runCommand(chip.value());
                case NONE -> null;
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
        else if (!pieces.isEmpty() && (pieces.getLast() instanceof Chip || pieces.getLast() instanceof Transaction)) pieces.add(" ");
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

    /** The text a player reads, chips as their labels, without colour codes; for measuring how wide a line is. */
    public String visible() {
        StringBuilder out = new StringBuilder();
        for (Object piece : pieces) {
            if (piece instanceof String text) out.append(RichText.plain(text));
            else if (piece instanceof Transaction transaction) { if (ids) out.append(RichText.plain(transaction.lead())).append(shortId(transaction.id())); }
            else out.append(((Chip) piece).label());
        }
        return out.toString();
    }

    /** The console text without colour codes. */
    @Override public String toString() { return RichText.plain(console()); }

    /** The first eight characters of a long id, as replies show transaction, route and ride ids. */
    public static String shortId(String id) { return id.substring(0, Math.min(SHORT_ID, id.length())); }
}
