package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.GuiModel.Component;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.npc.NpcItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** A chest GUI document open for one viewer: its page, its variant and its actions. */
public final class GuiScreen<P, S> implements Screen {
    private static final int MAXIMUM_CALL_DEPTH = 32, MAXIMUM_ACTIONS_PER_CLICK = 1024;
    private final GuiDocument document;
    private final ScreenManager<P, S>.Viewer viewer;
    private final Screens<P, S> screens;
    private final Map<String, String> args;
    private final Map<Integer, Bound> bound = new HashMap<>();
    private List<Object> entries = List.of();
    private long entriesAt = Long.MIN_VALUE, renderedAt;
    private int page;
    private int budget;
    private record Bound(Component component, GuiExpression.Scope scope) {}

    public GuiScreen(GuiDocument document, ScreenManager<P, S>.Viewer viewer, Screens<P, S> screens, Map<String, String> args) {
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
                .filter((GuiModel.Variant candidate) -> test(candidate.when(), scope))
                .max(java.util.Comparator.comparingInt(GuiModel.Variant::priority));
        GuiTemplate title = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().title()).orElse(document.title());
        List<String> mask = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().mask()).orElse(document.mask());
        Map<String, Component> keys = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().keys()).orElse(document.keys());
        Map<Integer, Component> slots = variant.flatMap((GuiModel.Variant chosen) -> chosen.presentation().slots()).orElse(document.slots());
        Map<Integer, Component> resolved = GuiDocument.resolve(document.width(), mask, keys, slots);
        bound.clear();
        document.list().ifPresent((GuiModel.ListSection list) -> entries(list, scope));
        Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
        for (Map.Entry<Integer, Component> slot : resolved.entrySet()) place(slot.getKey(), slot.getValue(), scope, items);
        document.list().ifPresent((GuiModel.ListSection list) -> {
            List<Integer> area = GuiDocument.area(document.width(), mask, list.area());
            int size = list.pageSize().orElse(area.size());
            List<Object> all = entries(list, scope);
            page = Math.clamp(page, 0, pages(all.size(), size) - 1);
            for (int index = 0; index < area.size(); index++) {
                int position = page * size + index;
                if (index >= size || position >= all.size()) continue;
                Object entry = all.get(position);
                GuiExpression.Scope entryScope = (String name) -> name.equals(list.variable()) ? entry : scope.variable(name);
                place(area.get(index), list.template(), entryScope, items);
            }
        });
        renderedAt = viewer.tick();
        return new ScreenFrame(GuiText.colorize(title.render(scope)), document.resolution(), document.width(), document.rows(), items);
    }

    @Override public boolean tick(long tick) {
        GuiModel.Refresh refresh = document.refresh();
        int interval = refresh.slotsTicks() == 0 ? refresh.titleTicks() : refresh.titleTicks() == 0 ? refresh.slotsTicks() : Math.min(refresh.titleTicks(), refresh.slotsTicks());
        return interval > 0 && tick - renderedAt >= interval;
    }

    @Override public void click(int slot, Click click) {
        Bound target = bound.get(slot);
        if (target == null) return;
        budget = MAXIMUM_ACTIONS_PER_CLICK;
        switch (target.component()) {
            case GuiModel.Button button -> run(button.actions(), 0, click, target.scope(), 0);
            case GuiModel.Toggle toggle -> run(toggled(toggle, target.scope()) ? toggle.trueActions() : toggle.falseActions(), 0, click, target.scope(), 0);
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
        Optional<String> name = icon.name().map((GuiTemplate text) -> GuiText.colorize(text.render(scope)));
        List<String> lore = new ArrayList<>();
        for (GuiTemplate line : icon.lore()) lore.add(GuiText.colorize(line.render(scope)));
        if (icon.kind() == GuiModel.IconKind.PLAYER_HEAD)
            return new ScreenFrame.Item(new NpcItem("minecraft:player_head", 1, Optional.empty(), Optional.empty()), name, lore, Optional.of(icon.item().render(scope)));
        String id = icon.item().render(scope).trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
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

    private int listPageSize() {
        return document.list().map((GuiModel.ListSection list) -> list.pageSize().orElse(GuiDocument.area(document.width(), document.mask(), list.area()).size())).orElse(1);
    }

    private static int pages(int entries, int size) { return size < 1 || entries <= 0 ? 1 : Math.max(1, (entries + size - 1) / size); }

    /** Runs actions from {@code index}; returns false when a {@code stop}, a refusal or a delay ended this list. */
    private boolean run(List<Action> actions, int index, Click click, GuiExpression.Scope scope, int depth) {
        for (int position = index; position < actions.size(); position++) {
            if (!viewer.open() || --budget < 0) return false;
            Action action = actions.get(position);
            GuiModel.Common common = action.common();
            if (!common.triggers().isEmpty() && !common.triggers().contains(click)) continue;
            if (common.when().isPresent() && !test(common.when().get(), scope)) continue;
            if (common.cooldownTicks() > 0 && !viewer.cooldown("action:" + System.identityHashCode(action), common.cooldownTicks())) continue;
            if (action instanceof GuiModel.Delay delay) {
                int next = position + 1;
                viewer.schedule(delay.ticks(), () -> { budget = MAXIMUM_ACTIONS_PER_CLICK; run(actions, next, click, scope, depth); viewer.refresh(); });
                return false;
            }
            if (!perform(action, click, scope, depth)) return false;
        }
        return true;
    }

    private boolean perform(Action action, Click click, GuiExpression.Scope scope, int depth) {
        ScreenHost<P, S> host = viewer.host();
        P player = viewer.player();
        switch (action) {
            case GuiModel.Command command -> host.command(command.source() == GuiModel.CommandSource.PLAYER ? Optional.of(player) : Optional.empty(),
                    command.command().render(scope));
            case GuiModel.Sound sound -> host.sound(player, sound.sound(), sound.source(), sound.volume(), sound.pitch());
            case GuiModel.Message message -> host.message(player, GuiText.colorize(message.message().render(scope)));
            case GuiModel.Title title -> host.title(player, GuiText.colorize(title.title().render(scope)),
                    title.subtitle().map((GuiTemplate text) -> GuiText.colorize(text.render(scope))).orElse(""), title.fadeInTicks(), title.stayTicks(), title.fadeOutTicks());
            case GuiModel.ActionBar bar -> host.actionBar(player, GuiText.colorize(bar.text().render(scope)));
            case GuiModel.Close ignored -> { viewer.close(); return false; }
            case GuiModel.Navigate navigate -> { return navigate(navigate, scope); }
            case GuiModel.Inventory inventory -> {
                Map<String, String> next = new LinkedHashMap<>();
                inventory.args().forEach((String key, GuiTemplate value) -> next.put(key, value.render(scope)));
                screens.gui(viewer, inventory.id().render(scope), next).ifPresent(viewer::push);
                return false;
            }
            case GuiModel.SetSession set -> viewer.session().put(set.variable(), set.value().render(scope));
            case GuiModel.Give give -> { return give(give, scope); }
            case GuiModel.Take take -> { return take(take, scope); }
            case GuiModel.Economy economy -> { return economy(economy, scope); }
            case GuiModel.Delay ignored -> { return false; }
            case GuiModel.Sequence sequence -> {
                for (GuiModel.Step step : sequence.steps()) {
                    if (step.atTicks() == 0) { if (!perform(step.action(), click, scope, depth)) return false; }
                    else viewer.schedule(step.atTicks(), () -> { budget = MAXIMUM_ACTIONS_PER_CLICK; perform(step.action(), click, scope, depth); viewer.refresh(); });
                }
            }
            case GuiModel.If branch -> { return run(test(branch.condition(), scope) ? branch.then() : branch.otherwise(), 0, click, scope, depth); }
            case GuiModel.Switch choice -> {
                String value;
                try { value = GuiExpression.text(choice.on().evaluate(scope)); }
                catch (GuiExpression.Invalid failed) { value = ""; }
                return run(choice.cases().getOrDefault(value, choice.fallback()), 0, click, scope, depth);
            }
            case GuiModel.Chance chance -> { return run(ThreadLocalRandom.current().nextDouble(100) < chance.percent() ? chance.then() : chance.otherwise(), 0, click, scope, depth); }
            case GuiModel.Cooldown cooldown -> { return run(viewer.cooldown("key:" + cooldown.key(), cooldown.ticks()) ? cooldown.then() : cooldown.otherwise(), 0, click, scope, depth); }
            case GuiModel.Stop ignored -> { return false; }
            case GuiModel.Call call -> {
                if (depth >= MAXIMUM_CALL_DEPTH) return false;
                return run(document.actions().getOrDefault(call.action(), List.of()), 0, click, scope, depth + 1);
            }
            case GuiModel.OpenShop open -> { screens.shop(viewer, open.shop()).ifPresent(viewer::push); return false; }
        }
        return true;
    }

    private boolean navigate(GuiModel.Navigate navigate, GuiExpression.Scope scope) {
        String target = navigate.target().map((GuiTemplate text) -> text.render(scope).trim()).orElse("");
        switch (navigate.mode()) {
            case PAGE -> {
                int size = listPageSize();
                int total = pages(entries.size(), size);
                int next = switch (target.toLowerCase(java.util.Locale.ROOT)) {
                    case "next" -> page + 1;
                    case "prev", "previous" -> page - 1;
                    default -> { try { yield Integer.parseInt(target); } catch (NumberFormatException invalid) { yield page; } }
                };
                page = Math.clamp(next, 0, total - 1);
                return true;
            }
            case PUSH -> { screens.gui(viewer, target, Map.of()).ifPresent(viewer::push); return false; }
            case REPLACE -> { screens.gui(viewer, target, Map.of()).ifPresent(viewer::replace); return false; }
            case BACK -> { viewer.back(); return false; }
            case HOME -> { viewer.home(); return false; }
            case CLOSE -> { viewer.close(); return false; }
        }
        return true;
    }

    private Optional<S> stack(GuiModel.Icon icon, GuiExpression.Scope scope) {
        ScreenFrame.Item item = item(icon, scope);
        List<String> errors = new ArrayList<>();
        return viewer.host().stack(item.item(), item.name(), item.lore(), errors);
    }

    private int amount(Optional<GuiExpression> amount, GuiModel.Icon icon, GuiExpression.Scope scope) {
        if (amount.isEmpty()) return icon.count();
        try { return (int) Math.clamp(Math.floor(amount.get().number(scope)), 0, 2304); }
        catch (GuiExpression.Invalid failed) { return 0; }
    }

    private boolean give(GuiModel.Give give, GuiExpression.Scope scope) {
        Optional<S> stack = stack(give.item(), scope);
        int amount = amount(give.amount(), give.item(), scope);
        if (stack.isEmpty() || amount <= 0) return false;
        InventoryTransaction<S> transaction = InventoryTransaction.begin(viewer.host().inventory(viewer.player()), viewer.host().items());
        if (give.dropIfFull()) {
            List<S> overflow = transaction.addOrOverflow(stack.get(), amount);
            transaction.commit();
            for (S extra : overflow) viewer.host().drop(viewer.player(), extra);
            return true;
        }
        if (!transaction.add(stack.get(), amount)) { transaction.rollback(); return false; }
        transaction.commit();
        return true;
    }

    private boolean take(GuiModel.Take take, GuiExpression.Scope scope) {
        Optional<S> stack = stack(take.item(), scope);
        int amount = amount(take.amount(), take.item(), scope);
        MatchRule rule = take.item().name().isEmpty() && take.item().lore().isEmpty() && take.item().customModelValue().isEmpty() ? MatchRule.MATERIAL : MatchRule.STRICT;
        InventoryTransaction<S> transaction = InventoryTransaction.begin(viewer.host().inventory(viewer.player()), viewer.host().items());
        if (stack.isEmpty() || amount <= 0 || !transaction.remove(stack.get(), amount, rule)) {
            transaction.rollback();
            take.denyMessage().ifPresent((GuiTemplate text) -> viewer.host().message(viewer.player(), GuiText.colorize(text.render(scope))));
            return false;
        }
        transaction.commit();
        return true;
    }

    private boolean economy(GuiModel.Economy action, GuiExpression.Scope scope) {
        Economy economy = viewer.host().economy();
        BigDecimal amount;
        try { amount = BigDecimal.valueOf(action.amount().map((GuiExpression expression) -> expression.number(scope)).orElse(0.0)).max(BigDecimal.ZERO); }
        catch (GuiExpression.Invalid | NumberFormatException failed) { amount = BigDecimal.ZERO; }
        boolean ok;
        if (economy.unavailable().isPresent()) ok = false;
        else if (amount.signum() == 0) ok = true;
        else ok = switch (action.operation()) {
            case HAS -> economy.has(viewer.id(), amount);
            case WITHDRAW -> economy.has(viewer.id(), amount) && economy.withdraw(viewer.id(), amount);
            case DEPOSIT -> economy.deposit(viewer.id(), amount);
        };
        if (!ok) action.denyMessage().ifPresent((GuiTemplate text) -> viewer.host().message(viewer.player(), GuiText.colorize(text.render(scope))));
        return ok;
    }

    private static boolean test(GuiExpression expression, GuiExpression.Scope scope) {
        try { return expression.test(scope); }
        catch (GuiExpression.Invalid failed) { return false; }
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
                case "pages" -> (double) pages(entries.size(), listPageSize());
                default -> null;
            };
        };
    }

    private static Object time(String property) {
        LocalDateTime now = LocalDateTime.now();
        return switch (property) {
            case "millis" -> (double) System.currentTimeMillis();
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
