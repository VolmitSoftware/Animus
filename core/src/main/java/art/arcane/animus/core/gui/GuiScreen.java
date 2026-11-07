package art.arcane.animus.core.gui;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.GuiModel.Component;
import art.arcane.animus.core.npc.NpcItem;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A chest GUI document open for one viewer: its page, its variant and its actions. With {@link Cells}, the host draws
 * the list area itself, such as a shop drawing its offers into a layout GUI, and the list's source and template are
 * not used.
 */
public final class GuiScreen<P, S> implements Screen {
    /** What a host draws into the list area, one cell per entry, in pages. */
    public interface Cells {
        int size();
        ScreenFrame.Item item(int index);
        void click(int index, Click click);
    }

    private final GuiDocument document;
    private final ScreenManager<P, S>.Viewer viewer;
    private final Screens<P, S> screens;
    private final Map<String, String> args;
    private final Map<Integer, Bound> bound = new HashMap<>();
    private final Optional<Cells> cells;
    private final Map<Integer, Integer> cellSlots = new HashMap<>();
    private List<Object> entries = List.of();
    private long entriesAt = Long.MIN_VALUE, renderedAt;
    private int page;
    private final GuiActions<P, S> actions = new GuiActions<>(new Actions());
    private record Bound(Component component, GuiExpression.Scope scope) {}

    public GuiScreen(GuiDocument document, ScreenManager<P, S>.Viewer viewer, Screens<P, S> screens, Map<String, String> args) {
        this(document, viewer, screens, args, Optional.empty());
    }

    public GuiScreen(GuiDocument document, ScreenManager<P, S>.Viewer viewer, Screens<P, S> screens, Map<String, String> args, Optional<Cells> cells) {
        this.cells = cells;
        this.document = document;
        this.viewer = viewer;
        this.screens = screens;
        this.args = Map.copyOf(args);
    }

    public GuiDocument document() { return document; }

    /** The document's {@code show} condition for this viewer. */
    public boolean shown() { return document.show().test(scope()); }

    @Override public boolean closeOnTeleport() { return document.closeOnTeleport(); }

    @Override public ScreenFrame render() {
        GuiExpression.Scope scope = scope();
        Optional<GuiModel.Variant> variant = document.variants().stream()
                .filter((GuiModel.Variant candidate) -> GuiActions.test(candidate.when(), scope))
                .max(java.util.Comparator.comparingInt(GuiModel.Variant::priority));
        GuiTemplate title = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().title()).orElse(document.title());
        List<String> mask = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().mask()).orElse(document.mask());
        Map<String, Component> keys = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().keys()).orElse(document.keys());
        Map<Integer, Component> slots = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().slots()).orElse(document.slots());
        Map<Integer, Component> resolved = GuiDocument.resolve(document.width(), mask, keys, slots);
        bound.clear();
        cellSlots.clear();
        if (cells.isEmpty()) document.list().ifPresent((GuiModel.ListSection list) -> entries(list, scope));
        Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
        for (Map.Entry<Integer, Component> slot : resolved.entrySet()) place(slot.getKey(), slot.getValue(), scope, items);
        document.list().ifPresent((GuiModel.ListSection list) -> {
            List<Integer> area = GuiDocument.area(document.width(), mask, list.area());
            int size = list.pageSize().orElse(area.size());
            List<Object> all = cells.isPresent() ? List.of() : entries(list, scope);
            int count = cells.map(Cells::size).orElse(all.size());
            page = Math.clamp(page, 0, pages(count, size) - 1);
            for (int index = 0; index < area.size(); index++) {
                int position = page * size + index;
                if (index >= size || position >= count) continue;
                if (cells.isPresent()) {
                    items.put(area.get(index), cells.get().item(position));
                    bound.remove(area.get(index));
                    cellSlots.put(area.get(index), position);
                    continue;
                }
                Object entry = all.get(position);
                GuiExpression.Scope entryScope = (String name) -> name.equals(list.variable()) ? entry : scope.variable(name);
                place(area.get(index), list.template(), entryScope, items);
            }
        });
        renderedAt = viewer.tick();
        return new ScreenFrame(RichText.colorize(title.render(scope)), document.resolution(), document.width(), document.rows(), items);
    }

    @Override public boolean tick(long tick) {
        GuiModel.Refresh refresh = document.refresh();
        int interval = refresh.slotsTicks() == 0 ? refresh.titleTicks() : refresh.titleTicks() == 0 ? refresh.slotsTicks() : Math.min(refresh.titleTicks(), refresh.slotsTicks());
        return interval > 0 && tick - renderedAt >= interval;
    }

    @Override public void click(int slot, Click click) {
        Integer cell = cellSlots.get(slot);
        if (cell != null) { cells.orElseThrow().click(cell, click); return; }
        Bound target = bound.get(slot);
        if (target == null) return;
        switch (target.component()) {
            case GuiModel.Button button -> actions.click(button.actions(), click, target.scope());
            case GuiModel.Toggle toggle -> actions.click(toggled(toggle, target.scope()) ? toggle.trueActions() : toggle.falseActions(), click, target.scope());
            case GuiModel.Decoration ignored -> {}
        }
    }

    private void place(int slot, Component component, GuiExpression.Scope scope, Map<Integer, ScreenFrame.Item> items) {
        if (!component.show().test(scope)) { items.remove(slot); bound.remove(slot); return; }
        GuiModel.Icon icon = switch (component) {
            case GuiModel.Button button -> button.icon();
            case GuiModel.Decoration decoration -> decoration.icon();
            case GuiModel.Toggle toggle -> toggled(toggle, scope) ? toggle.trueIcon() : toggle.falseIcon();
        };
        items.put(slot, item(icon, scope));
        bound.put(slot, new Bound(component, scope));
    }

    private static boolean toggled(GuiModel.Toggle toggle, GuiExpression.Scope scope) {
        try { return GuiExpression.text(toggle.condition().evaluate(scope)).equals(toggle.expectedValue()); }
        catch (GuiExpression.Invalid failed) { return false; }
    }

    static ScreenFrame.Item item(GuiModel.Icon icon, GuiExpression.Scope scope) {
        Optional<String> name = icon.name().map((GuiTemplate text) -> RichText.colorize(text.render(scope)));
        List<String> lore = new ArrayList<>();
        for (GuiTemplate line : icon.lore()) lore.add(RichText.colorize(line.render(scope)));
        if (icon.kind() == GuiModel.IconKind.PLAYER_HEAD)
            return new ScreenFrame.Item(new NpcItem("minecraft:player_head", 1, Optional.empty(), Optional.empty()), name, lore, Optional.of(icon.item().render(scope)));
        String id = MinecraftIds.normalize(icon.item().render(scope));
        if (!NpcItem.validId(id)) id = "minecraft:barrier";
        Optional<String> components = icon.customModelValue().map((Integer value) -> "[custom_model_data={floats:[" + value + "f]}]");
        return new ScreenFrame.Item(new NpcItem(id, Math.clamp(icon.count(), 1, NpcItem.MAX_COUNT), components, Optional.empty()), name, lore, Optional.empty());
    }

    private List<Object> entries(GuiModel.ListSection list, GuiExpression.Scope scope) {
        if (entriesAt != Long.MIN_VALUE && (list.refreshTicks() == 0 || viewer.tick() - entriesAt < list.refreshTicks())) return entries;
        try { entries = list.source().list(scope); }
        catch (GuiExpression.Invalid failed) { entries = List.of(); }
        entriesAt = viewer.tick();
        return entries;
    }

    private int listCount() { return cells.map(Cells::size).orElse(entries.size()); }

    private int listPageSize() {
        return document.list().map((GuiModel.ListSection list) -> list.pageSize().orElse(GuiDocument.area(document.width(), document.mask(), list.area()).size())).orElse(1);
    }

    private static int pages(int entries, int size) { return size < 1 || entries <= 0 ? 1 : Math.max(1, (entries + size - 1) / size); }

    private boolean navigate(GuiModel.Navigate navigate, String target) {
        switch (navigate.mode()) {
            case PAGE -> {
                int size = listPageSize();
                int total = pages(listCount(), size);
                int next = switch (target.toLowerCase(java.util.Locale.ROOT)) {
                    case "next" -> page + 1;
                    case "prev", "previous" -> page - 1;
                    default -> { try { yield Integer.parseInt(target); } catch (NumberFormatException invalid) { yield page; } }
                };
                page = Math.clamp(next, 0, total - 1);
                return true;
            }
            case PUSH -> { screens.gui(viewer, relative(target), Map.of()).ifPresent(viewer::push); return false; }
            case REPLACE -> { screens.gui(viewer, relative(target), Map.of()).ifPresent(viewer::replace); return false; }
            case BACK -> { viewer.back(); return false; }
            case HOME -> { viewer.home(); return false; }
            case CLOSE -> { viewer.close(); return false; }
        }
        return true;
    }

    /** An id without a namespace names a document in this document's namespace, so copied Gloss folders keep working. */
    private String relative(String target) { return GuiIds.relative(document.id(), target); }

    private final class Actions implements GuiActions.Context<P, S> {
        @Override public P player() { return viewer.player(); }
        @Override public java.util.UUID id() { return viewer.id(); }
        @Override public ScreenHost<P, S> host() { return viewer.host(); }
        @Override public Map<String, String> session() { return viewer.session(); }
        @Override public boolean open() { return viewer.open(); }
        @Override public boolean cooldown(String key, int ticks) { return viewer.cooldown(key, ticks); }
        @Override public boolean schedule(int ticks, Runnable work) { return viewer.schedule(ticks, work); }
        @Override public void close() { viewer.close(); }
        @Override public void refresh() { viewer.refresh(); }
        @Override public boolean navigate(GuiModel.Navigate navigate, String target) { return GuiScreen.this.navigate(navigate, target); }
        @Override public void inventory(String id, Map<String, String> args) { screens.gui(viewer, relative(id), args).ifPresent(viewer::push); }
        @Override public void shop(String id) { screens.shop(viewer, relative(id)).ifPresent(viewer::push); }
        @Override public List<Action> named(String name) { return document.actions().getOrDefault(name, List.of()); }
    }

    /** {@code viewer.*}, {@code player.*}, {@code server.*}, {@code time.*}, {@code args.*}, {@code session.*}, {@code npc.*}, {@code page} and {@code pages}. */
    private GuiExpression.Scope scope() {
        return (String name) -> {
            int dot = name.indexOf('.');
            String root = dot < 0 ? name : name.substring(0, dot);
            String property = dot < 0 ? "" : name.substring(dot + 1);
            return switch (root) {
                case "viewer", "player" -> switch (property) {
                    case "name" -> viewer.host().name(viewer.player());
                    case "uuid" -> viewer.id().toString();
                    default -> viewer.host().viewer(viewer.player(), property);
                };
                case "server" -> viewer.host().viewer(viewer.player(), "server." + property);
                case "time" -> time(property);
                case "args" -> args.get(property);
                case "session" -> viewer.session().get(property);
                case "npc" -> screens.npc(viewer, property);
                case "page" -> (double) (page + 1);
                case "pages" -> (double) pages(listCount(), listPageSize());
                default -> null;
            };
        };
    }

    static Object time(String property) {
        LocalDateTime now = LocalDateTime.now();
        return switch (property) {
            case "millis", "ms", "epochMs" -> (double) System.currentTimeMillis();
            case "seconds" -> System.currentTimeMillis() / 1000.0;
            case "ticks" -> System.currentTimeMillis() / 50.0;
            case "hour" -> (double) now.getHour();
            case "minute" -> (double) now.getMinute();
            case "second" -> (double) now.getSecond();
            case "day" -> (double) now.getDayOfMonth();
            case "month" -> (double) now.getMonthValue();
            case "year" -> (double) now.getYear();
            case "weekday" -> (double) now.getDayOfWeek().getValue();
            default -> null;
        };
    }
}
