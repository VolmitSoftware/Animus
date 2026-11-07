package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * A code-built toolkit window: nine wide, filler in empty slots, a handler per button. List screens page their entries
 * through the top five rows and keep Back and the page arrows in the bottom row.
 */
abstract class ToolScreen<P, S> implements Screen {
    static final int WIDTH = 9, LIST = 45, BACK = 45, PREVIOUS = 48, INFO = 49, NEXT = 50;
    static final String FILLER = "minecraft:gray_stained_glass_pane", HEAD = "minecraft:player_head";

    @FunctionalInterface interface Handler { void click(GuiModel.Click click); }

    final Toolkit<P, S> toolkit;
    final ScreenManager<P, S>.Viewer viewer;
    final Toolkit.Commands commands;
    /** Changes to more NPCs than this ask twice, as deletes always do. */
    static final int BULK = 20;
    private final Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
    private final Map<Integer, Handler> handlers = new HashMap<>();
    private int armed = -1;
    int page;

    ToolScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        this.toolkit = toolkit; this.viewer = viewer; this.commands = commands;
    }

    abstract String title();
    abstract int rows();
    abstract void draw();

    @Override public final ScreenFrame render() {
        items.clear();
        handlers.clear();
        draw();
        for (int slot = 0; slot < rows() * WIDTH; slot++)
            items.putIfAbsent(slot, new ScreenFrame.Item(new NpcItem(FILLER, 1, Optional.empty(), Optional.empty()), Optional.of(" "), List.of(), Optional.empty()));
        return new ScreenFrame(RichText.colorize(Palette.DARK_GREY + title()), WIDTH + "x" + rows(), WIDTH, rows(), items);
    }

    @Override public final void click(int slot, GuiModel.Click click) {
        if (slot != armed) armed = -1;
        Handler handler = handlers.get(slot);
        if (handler != null) handler.click(click);
    }

    /**
     * A button that asks twice when it destroys something or touches more than {@link #BULK} NPCs: the first click arms
     * it and the lore turns red, the second click on the same button runs it, any other click disarms it. A button
     * that needs no confirmation runs at once.
     */
    void guarded(int slot, String icon, String name, String detail, int affected, boolean destructive, Runnable run) {
        boolean asks = destructive || affected > BULK;
        boolean ready = asks && armed == slot;
        String shown = ready ? "&cClick again to " + name.toLowerCase(java.util.Locale.ROOT) : name;
        List<String> lines = ready ? List.of("&c" + ToolkitFiles.count(affected) + " affected", "&7Any other click cancels") : List.of("&7" + detail, asks ? "&7Asks once more" : "&7Right away");
        button(slot, icon, title(shown), lore(lines, any(ready ? "do it" : asks ? "arm it" : name.toLowerCase(java.util.Locale.ROOT))), (GuiModel.Click click) -> {
            if (asks && armed != slot) { armed = slot; return; }
            armed = -1;
            run.run();
        });
    }

    /** Asks the player for a line of text; the window hides meanwhile and comes back before {@code answer} runs. */
    void prompt(String label, String initial, java.util.function.Consumer<String> answer) { toolkit.prompt(viewer, label, initial, answer); }

    /** The next wand click on an NPC answers {@code chosen}; the window hides meanwhile. */
    void pick(String label, java.util.function.Consumer<String> chosen) { toolkit.pick(viewer, label, chosen); }

    /**
     * A number changed by clicking: left adds {@code step}, right subtracts it, sneak makes it ten steps. The result
     * stays within the bounds and loses floating-point noise past six decimals.
     */
    static double step(GuiModel.Click click, double value, double step, double minimum, double maximum) {
        double delta = (click == GuiModel.Click.RIGHT || click == GuiModel.Click.SHIFT_RIGHT ? -step : step) * (sneak(click) ? 10 : 1);
        double next = Math.round((value + delta) * 1_000_000d) / 1_000_000d;
        return Math.clamp(next, minimum, maximum);
    }

    /** The next entry after {@code current} in a fixed list, wrapping; right clicks go backwards. */
    static <T> T cycle(GuiModel.Click click, List<T> options, T current) {
        int at = Math.max(0, options.indexOf(current));
        int next = right(click) || click == GuiModel.Click.SHIFT_RIGHT ? (at - 1 + options.size()) % options.size() : (at + 1) % options.size();
        return options.get(next);
    }

    /** A clickable item. {@code lore} is {@code &}-coded. */
    void button(int slot, String icon, String name, List<String> lore, Handler handler) {
        icon(slot, icon, name, lore, Optional.empty());
        handlers.put(slot, handler);
    }

    void icon(int slot, String icon, String name, List<String> lore, Optional<String> head) {
        List<String> coloured = new ArrayList<>(lore.size());
        for (String line : lore) coloured.add(RichText.colorize(line));
        items.put(slot, new ScreenFrame.Item(new NpcItem(icon, 1, Optional.empty(), Optional.empty()), Optional.of(RichText.colorize(name)), coloured, head));
    }

    void head(int slot, String name, List<String> lore, String player, Handler handler) {
        icon(slot, HEAD, name, lore, Optional.of(player));
        handlers.put(slot, handler);
    }

    /** What each click does, the last lines of every button. */
    static List<String> hints(String left, String right, String sneak) {
        return List.of("&fLeft &7" + left, "&fRight &7" + right, "&fSneak &7" + sneak);
    }

    /** The hint for a button every click treats the same. */
    static List<String> any(String what) { return List.of("&fAny click &7" + what); }

    static List<String> lore(List<String> lines, List<String> hints) {
        List<String> all = new ArrayList<>(lines);
        all.addAll(hints);
        return all;
    }

    /** The selection as it is at the click, or empty after telling the player there is nothing to act on. */
    Optional<List<String>> chosen() {
        List<String> selected = toolkit.selected(viewer.id());
        if (!selected.isEmpty()) return Optional.of(selected);
        toolkit.say(viewer.player(), ChatLine.error("Nothing selected; click NPCs with the wand first"));
        return Optional.empty();
    }

    static boolean sneak(GuiModel.Click click) { return click == GuiModel.Click.SHIFT_LEFT || click == GuiModel.Click.SHIFT_RIGHT; }
    static boolean right(GuiModel.Click click) { return click == GuiModel.Click.RIGHT; }

    static String title(String text) { return Palette.PINK + text; }

    /** The Back button, at the start of the bottom row; the toolkit's first screen closes instead. */
    void back() {
        button((rows() - 1) * WIDTH, "minecraft:oak_door", title("Back"), any("previous screen"), (GuiModel.Click click) -> viewer.back());
    }

    /** One page of entries in the top five rows, with page arrows when there is more than one page. */
    <T> void list(List<T> entries, String empty, BiConsumer<Integer, T> draw) {
        int pages = Math.max(1, (entries.size() + LIST - 1) / LIST);
        page = Math.clamp(page, 0, pages - 1);
        if (entries.isEmpty()) icon(22, "minecraft:paper", "&7" + empty, List.of(), Optional.empty());
        for (int index = page * LIST, slot = 0; index < entries.size() && slot < LIST; index++, slot++) draw.accept(slot, entries.get(index));
        if (page > 0) button(PREVIOUS, "minecraft:arrow", title("Previous page"), hints("page " + page, "page " + page, "first page"),
                (GuiModel.Click click) -> page = sneak(click) ? 0 : page - 1);
        if (page < pages - 1) button(NEXT, "minecraft:arrow", title("Next page"), hints("page " + (page + 2), "page " + (page + 2), "last page"),
                (GuiModel.Click click) -> page = sneak(click) ? pages - 1 : page + 1);
        if (pages > 1) icon(INFO, "minecraft:paper", "&7Page &f" + (page + 1) + "&7 of &f" + pages, List.of("&7" + entries.size() + " in all"), Optional.empty());
    }

    /** Up to {@code shown} names, then how many more. */
    static String names(List<String> names, int shown) {
        if (names.isEmpty()) return "none";
        String listed = String.join(", ", names.subList(0, Math.min(shown, names.size())));
        return names.size() > shown ? listed + " +" + (names.size() - shown) : listed;
    }
}
