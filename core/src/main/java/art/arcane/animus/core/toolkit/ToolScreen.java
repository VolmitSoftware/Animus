package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A code-built Animus menu window: nine wide, filler in empty slots, a handler per button. The last row is navigation
 * only (Back, Search, page arrows, Discard, Save or Mode, Help); a list pages 36 entries through rows 0-3 and keeps its
 * own actions in row 4. See {@link #list}, {@link #confirm}, {@link #draft} and {@link #help}.
 */
abstract class ToolScreen<P, S> implements Screen {
    static final int WIDTH = 9, ENTRIES = 36, ACTIONS = 36;
    /** Navigation slots of a six-row screen; {@link #bottom} moves them to a shorter screen's last row. SAVE and MODE share a slot. */
    static final int BACK = 45, SEARCH = 46, PREVIOUS = 48, PAGE = 49, NEXT = 50, DISCARD = 51, SAVE = 52, MODE = 52, HELP = 53;
    /** Changes to more NPCs than this ask once more, as destructive changes always do. */
    static final int BULK = 20;
    static final String FILLER = "minecraft:gray_stained_glass_pane", HEAD = "minecraft:player_head", NOTICE = "minecraft:red_stained_glass_pane";
    static final String CONFIRM = "Click again to confirm", CANCELS = "&7Any other click cancels";
    /** The colour of a lore line that names a raw key, id or path. */
    static final String RAW = "&8";
    /** Buttons never show an item's vanilla attribute, bundle or music disc lines; filler shows no tooltip at all. */
    static final String TOOLTIP = "tooltip_display", PLAIN = TOOLTIP + "={hidden_components:[\"minecraft:attribute_modifiers\",\"minecraft:bundle_contents\",\"minecraft:jukebox_playable\"]}",
            HIDDEN = TOOLTIP + "={hide_tooltip:true}";
    /** Lore lines longer than this many visible characters wrap. */
    static final int LORE_WIDTH = 48;
    /** Lore lines a Simple tooltip shows under its name before its two click hints share a line. */
    static final int SIMPLE_LINES = 3;
    static final String CLICK = "&fClick: &7", RIGHT_CLICK = "&fRight-click: &7", SNEAK_CLICK = "&fSneak-click: &7",
            PAIRED = "&f, right-click: &7", PAIRED_SNEAK = "&f, sneak-click: &7";

    @FunctionalInterface interface Handler { void click(GuiModel.Click click); }

    /** An action waiting for its second click: its slot shows {@code name} in red and {@code lines} until the next click. */
    private record Armed(int slot, String name, List<String> lines, Runnable run) {}

    final Toolkit<P, S> toolkit;
    final ScreenManager<P, S>.Viewer viewer;
    final Toolkit.Commands commands;
    private final Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
    private final Map<Integer, Handler> handlers = new HashMap<>();
    private Armed armed;
    private String search = "";
    private boolean begun;
    /** A {@link #forSelection} screen drew for NPCs that were selected; once none is left it leaves. */
    private boolean held;
    int page;

    ToolScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        this.toolkit = toolkit; this.viewer = viewer; this.commands = commands;
    }

    abstract String title();
    abstract int rows();
    abstract void draw();

    /**
     * The permission node this screen needs, checked before it opens and again on each click; never weaker than what
     * the old menu asked on the way to the same actions. A button that needs more is greyed for players without it and
     * checks its own node when clicked.
     */
    String node() { return PermissionNodes.TOOLS; }

    /**
     * Work the screen starts once it first shows, such as reading its files. It never runs from the constructor, so
     * nothing is read or written for a player the permission check turns away.
     */
    void begin() { }

    /**
     * Whether the screen works on the selected NPCs, so that once they are all gone, deleted or undone, the window
     * leaves it for the NPC list instead of showing an empty screen.
     */
    boolean forSelection() { return false; }

    /** The first slot {@link #list} fills; a screen with a header in its top row starts the list on the row below. */
    int firstEntry() { return 0; }

    /** Whether {@link #list} always offers Search; otherwise it does once the list runs past one page. */
    boolean searchable() { return false; }

    /** The menu edits text, so it shows placeholders as written. */
    @Override public final boolean fillsPlaceholders() { return false; }

    @Override public final ScreenFrame render() {
        if (!begun) { begun = true; begin(); }
        items.clear();
        handlers.clear();
        if (forSelection() && !toolkit.selected(viewer.id()).isEmpty()) held = true;
        draw();
        if (armed != null) {
            ScreenFrame.Item under = items.get(armed.slot());
            NpcItem icon = under == null ? new NpcItem(NOTICE, 1, Optional.empty(), Optional.empty()) : under.item();
            icon(armed.slot(), icon, "&c" + armed.name(), armed.lines(), under == null ? Optional.empty() : under.head());
        }
        Optional<String> notice = toolkit.notice(viewer.id());
        if (notice.isPresent()) {
            icon(bottom(PAGE), NOTICE, "&c" + notice.get(), List.of(), Optional.empty());
            handlers.remove(bottom(PAGE));
        }
        for (int slot = 0; slot < rows() * WIDTH; slot++)
            items.putIfAbsent(slot, new ScreenFrame.Item(new NpcItem(FILLER, 1, Optional.of("[" + HIDDEN + "]"), Optional.empty()), Optional.of(" "), List.of(), Optional.empty()));
        return new ScreenFrame(RichText.colorize(Palette.DARK_GREY + title()), WIDTH + "x" + rows(), WIDTH, rows(), items);
    }

    /** Every click ends the notice and any armed action; a second click on the armed slot runs it. */
    @Override public final void click(int slot, GuiModel.Click click) {
        toolkit.dismiss(viewer.id());
        Armed waiting = armed;
        armed = null;
        Handler handler = waiting != null && waiting.slot() == slot ? (GuiModel.Click any) -> waiting.run().run() : handlers.get(slot);
        if (handler == null || !toolkit.allows(viewer.player(), node())) return;
        toolkit.clicked(viewer.player());
        handler.click(click);
    }

    /** A screen whose NPCs all vanished since it drew leaves on the next tick; see {@link #leave}. */
    @Override public boolean tick(long tick) {
        if (held && forSelection() && toolkit.selected(viewer.id()).isEmpty()) leave();
        return false;
    }

    /**
     * Leaves this screen, while it shows, for the NPC list over Home, or Home alone when no NPC is left: what a window
     * does once the NPCs it showed are gone.
     */
    void leave() {
        if (!viewer.shows(this)) return;
        boolean any = !toolkit.npcs().stored().isEmpty();
        if (!toolkit.show(viewer.player(), HomeScreen::new) || !any) return;
        toolkit.screens().viewer(viewer.id()).ifPresent((ScreenManager<P, S>.Viewer home) -> home.push(new NpcsScreen<>(toolkit, home, commands)));
    }

    /** Opens {@code next} over this screen when the player holds its node. */
    void push(ToolScreen<P, S> next) { if (toolkit.allows(viewer.player(), next.node())) viewer.push(next); }

    /** Shows {@code next} in this screen's place, while this screen is still the one showing and the player holds its node. */
    void replace(ToolScreen<P, S> next) { if (viewer.shows(this) && toolkit.allows(viewer.player(), next.node())) viewer.replace(next); }

    /** Whether the player holds {@code node}, for a button whose action needs more than this screen's node. */
    boolean allows(String node) { return toolkit.allows(viewer.player(), node); }

    /** Whether the player holds {@code node}, asked without a refusal, to grey out what they may not use. */
    boolean holds(String node) { return toolkit.host().permitted(viewer.player(), node); }

    /** What shows instead of a button the player lacks {@code node} for: grey, saying so. */
    void denied(int slot, String name, String node) { icon(slot, "minecraft:gray_dye", "&7" + name, List.of("&7No permission", "&7" + node), Optional.empty()); }

    /** A button that opens a screen, greyed out when the player lacks that screen's node. {@code lore} is {@code &}-coded. */
    void opens(int slot, String icon, String name, List<String> lore, Supplier<? extends ToolScreen<P, S>> next) {
        String needs = next.get().node();
        if (!holds(needs)) { denied(slot, name, needs); return; }
        button(slot, icon, title(name), lore, (GuiModel.Click click) -> push(next.get()));
    }

    /** A button whose action needs {@code node}, greyed out when the player lacks it. */
    void gated(int slot, String node, String icon, String name, List<String> lore, Handler handler) {
        if (holds(node)) button(slot, icon, title(name), lore, handler);
        else denied(slot, name, node);
    }

    /** Whether the viewer's menu mode is Advanced. */
    boolean advanced() { return toolkit.advanced(viewer.id()); }

    /** A navigation slot of a six-row screen, such as {@link #HELP}, moved to this screen's last row. */
    int bottom(int slot) { return (rows() - 1) * WIDTH + slot - BACK; }

    /** Tells the player why nothing happened: red in chat, and on this window until the next click. */
    void refuse(String reason) { toolkit.refuse(viewer.player(), reason); }

    /** A button that runs at once, or asks once more when it destroys something or changes more than {@link #BULK} NPCs. */
    void confirm(int slot, String icon, String name, String what, int affected, boolean destructive, Runnable run) {
        button(slot, icon, title(name), lore(List.of("&7" + what), any(name.toLowerCase(Locale.ROOT))), (GuiModel.Click click) -> ask(slot, what, affected, destructive, run));
    }

    /**
     * Runs now, or for a destructive change or more than {@link #BULK} NPCs turns {@code slot} red until the next click,
     * which runs it on that slot and cancels it anywhere else. Usable from any handler, list entries too.
     */
    void ask(int slot, String what, int affected, boolean destructive, Runnable run) {
        if (!destructive && affected <= BULK) { run.run(); return; }
        List<String> lines = new ArrayList<>(List.of("&c" + what));
        if (affected > 1) lines.add("&c" + ToolkitFiles.count(affected) + " affected");
        lines.add(CANCELS);
        armed = new Armed(slot, CONFIRM, lines, run);
    }

    /** Asks for a line of text; the window hides meanwhile and comes back before {@code answer} runs. */
    void prompt(String label, String initial, Consumer<String> answer) { toolkit.prompt(viewer, label, initial, answer); }

    /** As {@link #prompt(String, String, Consumer)}, with a hint under the input, such as the characters an id allows. */
    void prompt(String label, String initial, String hint, Consumer<String> answer) { toolkit.prompt(viewer, label, initial, hint, answer); }

    /** The next wand click on an NPC answers {@code chosen}; the window hides meanwhile. */
    void pick(String label, Consumer<String> chosen) { toolkit.pick(viewer, label, chosen); }

    /** As {@link #pick}, leaving the window closed afterwards so the result shows. */
    void pickAndClose(String label, Consumer<String> chosen) { toolkit.pick(viewer, label, false, chosen); }

    /**
     * A number changed by clicking: a click adds {@code step}, a right-click subtracts it, sneaking makes it ten steps.
     * The result stays within the bounds and loses floating-point noise past six decimals.
     */
    static double step(GuiModel.Click click, double value, double step, double minimum, double maximum) {
        double delta = (click.right() ? -step : step) * (sneak(click) ? 10 : 1);
        double next = Math.round((value + delta) * 1_000_000d) / 1_000_000d;
        return Math.clamp(next, minimum, maximum);
    }

    /** The next entry after {@code current} in a fixed list, wrapping; right-clicks go backwards. */
    static <T> T cycle(GuiModel.Click click, List<T> options, T current) {
        int at = Math.max(0, options.indexOf(current));
        int next = click.right() ? (at - 1 + options.size()) % options.size() : (at + 1) % options.size();
        return options.get(next);
    }

    /** A clickable item. {@code lore} is {@code &}-coded. */
    void button(int slot, String icon, String name, List<String> lore, Handler handler) {
        icon(slot, icon, name, lore, Optional.empty());
        handlers.put(slot, handler);
    }

    /** A clickable item that shows {@code item} with its own components, such as a dyed boot. */
    void button(int slot, NpcItem item, String name, List<String> lore, Handler handler) {
        icon(slot, item, name, lore, Optional.empty());
        handlers.put(slot, handler);
    }

    void icon(int slot, String icon, String name, List<String> lore, Optional<String> head) {
        icon(slot, new NpcItem(icon, 1, Optional.empty(), Optional.empty()), name, lore, head);
    }

    /**
     * Dark grey lore lines are raw keys, ids and paths: Advanced shows them, Simple leaves them out. A Simple tooltip
     * longer than {@link #SIMPLE_LINES} lines under its name puts its Click and Right-click hints on one line.
     */
    void icon(int slot, NpcItem item, String name, List<String> lore, Optional<String> head) {
        boolean raw = advanced();
        List<String> kept = new ArrayList<>(lore.size());
        for (String line : lore) if (raw || !line.startsWith(RAW)) kept.add(line);
        List<String> lines = !raw && kept.size() > SIMPLE_LINES ? paired(kept) : kept;
        List<String> coloured = new ArrayList<>(lines.size());
        for (String line : lines) for (String part : line.contains(PAIRED) || line.contains(PAIRED_SNEAK) ? List.of(line) : wrap(line)) coloured.add(RichText.colorize(part));
        items.put(slot, new ScreenFrame.Item(shown(item), Optional.of(RichText.colorize(name)), coloured, head));
    }

    /**
     * {@code Click: +2} and the hint after it as one line, {@code Click: +2, right-click: -2} or
     * {@code Click: turn on, sneak-click: type a value}, kept whole when drawn.
     */
    static List<String> paired(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        for (int index = 0; index + 1 < out.size(); index++) {
            if (!out.get(index).startsWith(CLICK)) continue;
            String next = out.get(index + 1);
            String joined = next.startsWith(RIGHT_CLICK) ? PAIRED + next.substring(RIGHT_CLICK.length())
                    : next.startsWith(SNEAK_CLICK) ? PAIRED_SNEAK + next.substring(SNEAK_CLICK.length()) : null;
            if (joined == null) continue;
            out.set(index, out.get(index) + joined);
            out.remove(index + 1);
            break;
        }
        return out;
    }

    void head(int slot, String name, List<String> lore, String player, Handler handler) {
        icon(slot, HEAD, name, lore, Optional.of(player));
        handlers.put(slot, handler);
    }

    /** The Back button; the first screen of a window shows Close there instead. */
    void back() { button(bottom(BACK), "minecraft:oak_door", title("Back"), any("previous screen"), (GuiModel.Click click) -> viewer.back()); }

    /** Close, for the first screen of a window. */
    void closeButton() { button(bottom(BACK), "minecraft:barrier", title("Close"), any("close the window"), (GuiModel.Click click) -> viewer.close()); }

    /** The Mode lever: Simple or Advanced, switched for this player at once and saved. */
    void modeLever() {
        ToolkitMode mode = toolkit.mode(viewer.id());
        ToolkitMode other = mode == ToolkitMode.SIMPLE ? ToolkitMode.ADVANCED : ToolkitMode.SIMPLE;
        String meaning = mode == ToolkitMode.SIMPLE ? "Simple shows the common tasks." : "Advanced shows everything.";
        button(bottom(MODE), "minecraft:lever", title("Mode: " + mode.label()), lore(List.of("&7" + meaning), any("switch to " + other.label())),
                (GuiModel.Click click) -> toolkit.mode(viewer.id(), other));
    }

    /** The Help item: a click prints {@code text} and a {@code [Wiki]} link to {@code page#anchor} in chat; the window stays. */
    void help(String text, String page, String anchor) {
        button(bottom(HELP), "minecraft:paper", title("Help"), any("explain this screen in chat"), (GuiModel.Click click) ->
                toolkit.say(viewer.player(), ChatLine.reply(text).open("Wiki", ChatLine.docsUrl(page, anchor), "Open the Animus wiki")));
    }

    /**
     * Save (grey "Saved" when nothing changed), Discard and Back for a screen that keeps changes until saved. When
     * {@code owner}, Back with unsaved changes asks once, since leaving drops them.
     */
    void draft(int changes, int affected, boolean owner, Runnable save, Runnable discard) {
        String counted = changes + (changes == 1 ? " change" : " changes");
        if (changes == 0) icon(bottom(SAVE), "minecraft:gray_dye", "&7Saved", List.of("&7Nothing to save"), Optional.empty());
        else {
            String what = "Saves " + counted + (affected > 1 ? " on " + ToolkitFiles.count(affected) : "");
            button(bottom(SAVE), "minecraft:emerald", title("Save"), lore(List.of("&f" + counted + (affected > 1 ? " for " + ToolkitFiles.count(affected) : "")), any("save")),
                    (GuiModel.Click click) -> ask(bottom(SAVE), what, affected, false, save));
            button(bottom(DISCARD), "minecraft:bucket", title("Discard"), lore(List.of("&7Drops " + counted), any("discard")), (GuiModel.Click click) -> discard.run());
        }
        if (!owner || changes == 0) { back(); return; }
        button(bottom(BACK), "minecraft:oak_door", title("Back"), lore(List.of("&e" + counted + " not saved"), any("previous screen")),
                (GuiModel.Click click) -> armed = new Armed(bottom(BACK), "Click again to leave unsaved", List.of("&c" + counted + " are dropped", CANCELS), viewer::back));
    }

    /** One page of entries in rows 0-3 from {@link #firstEntry}, with Search over {@code text} (ignoring case), page arrows and the page count. */
    <T> void list(List<T> entries, Function<T, String> text, String empty, BiConsumer<Integer, T> draw) {
        String wanted = search.toLowerCase(Locale.ROOT);
        List<T> shown = wanted.isEmpty() ? entries : entries.stream().filter((T entry) -> text.apply(entry).toLowerCase(Locale.ROOT).contains(wanted)).toList();
        int first = firstEntry(), per = ENTRIES - first;
        int pages = Math.max(1, (shown.size() + per - 1) / per);
        page = Math.clamp(page, 0, pages - 1);
        if (shown.isEmpty()) icon(22, "minecraft:paper", "&7" + (wanted.isEmpty() ? empty : "Nothing matches " + search), List.of(), Optional.empty());
        for (int index = page * per, slot = first; index < shown.size() && slot < ENTRIES; index++, slot++) draw.accept(slot, shown.get(index));
        if (!search.isEmpty())
            button(bottom(SEARCH), "minecraft:spyglass", title("Search"), lore(List.of("&fNow: " + search), any("clear the search")), (GuiModel.Click click) -> { search = ""; page = 0; });
        else if (searchable() || entries.size() > per)
            button(bottom(SEARCH), "minecraft:spyglass", title("Search"), any("type a name"),
                    (GuiModel.Click click) -> prompt("Search", "", (String typed) -> { search = typed.strip(); page = 0; }));
        if (page > 0) button(bottom(PREVIOUS), "minecraft:arrow", title("Previous page"), hints("page " + page, "", "first page"),
                (GuiModel.Click click) -> page = sneak(click) ? 0 : page - 1);
        if (page < pages - 1) button(bottom(NEXT), "minecraft:arrow", title("Next page"), hints("page " + (page + 2), "", "last page"),
                (GuiModel.Click click) -> page = sneak(click) ? pages - 1 : page + 1);
        if (pages > 1) icon(bottom(PAGE), "minecraft:paper", "&7Page &f" + (page + 1) + "&7 of &f" + pages, List.of("&7" + shown.size() + " in all"), Optional.empty());
    }

    /** Shows {@link #list} filtered by {@code text} from the first page, as typing it into Search does. */
    void search(String text) { search = text.strip(); page = 0; }

    /** The words a list searches, for entries that are their own label. */
    static String itself(String entry) { return entry; }

    /** The selection as it is at the click, or empty after telling the player there is nothing to act on. */
    Optional<List<String>> chosen() {
        List<String> selected = toolkit.selected(viewer.id());
        if (!selected.isEmpty()) return Optional.of(selected);
        refuse("Nothing selected; pick an NPC in NPCs or click one with the wand");
        return Optional.empty();
    }

    /** A single item with its own components and its vanilla attribute lines hidden. */
    static NpcItem shown(NpcItem item) {
        String components = item.components().orElse("[]");
        if (!components.contains(TOOLTIP + "="))
            components = components.equals("[]") ? "[" + PLAIN + "]" : components.substring(0, components.length() - 1) + "," + PLAIN + "]";
        return new NpcItem(item.id(), 1, Optional.of(components), Optional.empty());
    }

    /** The item's readable name, such as {@code Leather Boots} or {@code 3x Bread}. */
    static String itemName(NpcItem item) {
        String path = item.id().substring(item.id().indexOf(':') + 1);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("_")) if (!word.isEmpty()) name.append(name.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return item.count() == 1 ? name.toString() : item.count() + "x " + name;
    }

    /** A {@code &}-coded line cut at spaces into lines of at most {@link #LORE_WIDTH} visible characters, each keeping the colour. */
    static List<String> wrap(String line) {
        if (RichText.plain(RichText.colorize(line)).length() <= LORE_WIDTH) return List.of(line);
        String colour = line.length() > 1 && line.charAt(0) == '&' ? line.substring(0, 2) : "";
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int visible = 0;
        for (String word : line.substring(colour.length()).split(" ")) {
            int length = RichText.plain(RichText.colorize(word)).length();
            if (visible > 0 && visible + 1 + length > LORE_WIDTH) { out.add(colour + current); current.setLength(0); visible = 0; }
            if (visible > 0) { current.append(' '); visible++; }
            current.append(word);
            visible += length;
        }
        if (!current.isEmpty()) out.add(colour + current);
        return out;
    }

    /** {@code Folder town / market}, the lore line naming where an NPC's file lives. */
    static String folder(NpcDefinition npc) { return "&7Folder &f" + FolderFiles.shown(npc.group()); }

    /** A player head wearing a skin texture, the base64 {@code textures} value of a skin file, and its signature. */
    static NpcItem skinHead(String texture, Optional<String> signature) {
        String signed = signature.map((String value) -> ",signature:\"" + value.replace("\"", "") + "\"").orElse("");
        return new NpcItem(HEAD, 1, Optional.of("[profile={properties:[{name:\"textures\",value:\"" + texture.replace("\"", "") + "\"" + signed + "}]}]"), Optional.empty());
    }

    /**
     * What each click does, the last lines of every button: {@code Click: x}, then {@code Right-click: y} and
     * {@code Sneak-click: z} when they do something else. An empty text adds no line.
     */
    static List<String> hints(String click, String right, String sneak) {
        List<String> lines = new ArrayList<>(any(click));
        if (!right.isEmpty() && !right.equals(click)) lines.add(RIGHT_CLICK + right);
        if (!sneak.isEmpty() && !sneak.equals(click)) lines.add(SNEAK_CLICK + sneak);
        return lines;
    }

    /**
     * The lines above a number row's three click hints: in Simple only the white {@code Now} line, so the row stays at
     * four lines; Advanced adds what the number is above it.
     */
    List<String> numberRow(String what, String now) { return advanced() ? List.of("&7" + what, "&fNow: " + now) : List.of("&fNow: " + now); }

    /** The hint for a button every click treats the same. */
    static List<String> any(String what) { return List.of(CLICK + what); }

    static List<String> lore(List<String> lines, List<String> hints) {
        List<String> all = new ArrayList<>(lines);
        all.addAll(hints);
        return all;
    }

    static boolean sneak(GuiModel.Click click) { return click.shift(); }
    static boolean right(GuiModel.Click click) { return click == GuiModel.Click.RIGHT; }

    static String title(String text) { return Palette.PINK + text; }

    /** The one NPC by the name players see, with its id beside it in Advanced; else how many. For lore and what a confirm says. */
    String named(Collection<String> ids) { return ids.size() == 1 ? toolkit.shown(viewer.id(), ids.iterator().next()) : ToolkitFiles.count(ids.size()); }

    /** Up to {@code shown} of the NPCs by the names players see, then how many more. */
    String npcNames(List<String> ids, int shown) {
        return names(ids.stream().map((String id) -> toolkit.npcs().find(id).map(NpcsScreen::name).orElse(id)).toList(), shown);
    }

    /** Up to {@code shown} names, then how many more. */
    static String names(List<String> names, int shown) {
        if (names.isEmpty()) return "none";
        String listed = String.join(", ", names.subList(0, Math.min(shown, names.size())));
        return names.size() > shown ? listed + " +" + (names.size() - shown) : listed;
    }
}
