package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.presentation.DisplayEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One holographic menu open for one viewer. The menu is placed in front of the viewer when it opens, as Gloss places
 * it: local x is the viewer's right, y up and z the way they faced. Hover is found every tick by casting the eye ray
 * onto each clickable component's plane; a click arrives through an interaction box and is only accepted when that
 * same ray hits a clickable plane, the nearest one winning. Everything runs on the viewer's owner.
 */
final class MenuSession<P, S> {
    static final double TEXT_LINE = 0.25, CHARACTER = 0.15, REACH = 16, CONTENT_TICKS = 10;
    private static final UUID NONE = new UUID(0, 0);
    private final HoloService<P, S> service;
    private final HoloService<P, S>.Viewer viewer;
    final MenuDocument document;
    final Map<String, String> args;
    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Boolean> toggles = new HashMap<>();
    private final Map<String, Double> hover = new HashMap<>();
    private final Map<String, Integer> hovered = new HashMap<>();
    private final Map<String, Integer> pages = new HashMap<>();
    private final DisplaySet<P> displays;
    private final GuiActions<P, S> actions;
    private double feetX, feetY, feetZ, facing;
    private String world;
    private List<Placed> placed = List.of();
    private long renderedAt = Long.MIN_VALUE;
    private boolean hidden, closed, relocate;

    /** One drawn piece of the menu: a component, a list entry, a tab or a slider, with its scope. */
    private record Placed(String key, MenuDocument.Component component, MenuDocument.Data data, double[] local, GuiExpression.Scope scope,
                          Optional<MenuDocument.Tab> tab, Optional<String> list, List<String> textRows) {}
    /** A clickable plane in world space. */
    private record Plane(Placed placed, double[] centre, double width, double height) {}

    MenuSession(HoloService<P, S> service, HoloService<P, S>.Viewer viewer, MenuDocument document, Map<String, String> args, ScreenHost.Pose pose) {
        this.service = service; this.viewer = viewer; this.document = document; this.args = Map.copyOf(args);
        session.putAll(document.vars());
        displays = new DisplaySet<>(service.transport());
        actions = new GuiActions<>(new Actions());
        anchor(pose);
    }

    private void anchor(ScreenHost.Pose pose) {
        feetX = pose.x(); feetY = pose.y(); feetZ = pose.z(); facing = Math.toRadians(pose.yaw());
        world = HoloService.world(pose);
    }

    boolean closed() { return closed; }
    /** The viewer teleported; the menu moves to where they arrive on the next tick. */
    void relocate() { relocate = true; }
    String world() { return world; }

    double[] right() { return new double[]{-Math.cos(facing), 0, -Math.sin(facing)}; }
    double[] forward() { return new double[]{-Math.sin(facing), 0, Math.cos(facing)}; }

    /** The menu centre: the opening feet plus the menu offset, unscaled. */
    double[] origin() { return place(new double[]{feetX, feetY, feetZ}, document.offset()); }

    private double[] place(double[] from, double[] local) {
        double[] right = right(), forward = forward();
        return new double[]{from[0] + right[0] * local[0] + forward[0] * local[2], from[1] + local[1], from[2] + right[2] * local[0] + forward[2] * local[2]};
    }

    /** One owner tick; false once the menu closed itself. */
    boolean tick(ScreenHost.Pose pose, long tick) {
        if (closed) return false;
        if (relocate) { relocate = false; if (!document.followPlayer()) { double turned = facing; anchor(pose); facing = turned; } }
        if (!pose.in(world)) { close(); return false; }
        double[] origin = origin();
        double[] offset = document.offset();
        double allowed = document.maxDistance() * document.maxDistance() + offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2];
        if (squared(origin, new double[]{pose.x(), pose.y(), pose.z()}) > allowed) { close(); return false; }
        if (document.followPlayer() && (Math.abs(pose.x() - feetX) > 1.0E-3 || Math.abs(pose.y() - feetY) > 1.0E-3 || Math.abs(pose.z() - feetZ) > 1.0E-3
                || Math.abs(Math.toRadians(pose.yaw()) - facing) > 1.0E-3)) anchor(pose);
        else if (document.lockPosition() && squared(new double[]{pose.x(), pose.y(), pose.z()}, new double[]{feetX, feetY, feetZ}) > 0.01)
            service.host().teleport(viewer.player(), world, feetX, feetY, feetZ, pose.yaw(), pose.pitch());
        GuiExpression.Scope base = scope();
        if (!document.show().test(base)) { if (!hidden) { displays.clear(viewer.player()); hidden = true; } return true; }
        hidden = false;
        if (renderedAt == Long.MIN_VALUE || tick - renderedAt >= CONTENT_TICKS) { placed = expand(base); renderedAt = tick; }
        Optional<Plane> looked = aim(pose);
        for (Placed piece : placed) {
            MenuDocument.Hover settings = hoverOf(piece);
            boolean selected = looked.isPresent() && looked.get().placed().key().equals(piece.key());
            double step = settings.ticks() == 0 ? 1 : 1.0 / settings.ticks();
            double progress = Math.clamp(hover.getOrDefault(piece.key(), 0.0) + (selected ? step : -step), 0, 1);
            if (progress == 0) hover.remove(piece.key()); else hover.put(piece.key(), progress);
            if (selected) hovered.merge(piece.key(), 1, Integer::sum); else hovered.remove(piece.key());
        }
        displays.apply(viewer.player(), entities());
        return true;
    }

    /** Re-reads list sources, toggle states and text now, for example after a click. */
    void dirty() { renderedAt = Long.MIN_VALUE; }

    void close() {
        if (closed) return;
        closed = true;
        displays.clear(viewer.player());
    }

    /** The client dropped everything already. */
    void forget() { closed = true; displays.forget(); }

    boolean owns(int entityId) { return displays.slot(entityId).isPresent(); }

    /** A click on one of this menu's boxes. It counts only when the eye ray hits a clickable plane. */
    void click(ScreenHost.Pose pose, GuiModel.Click click) {
        if (closed || hidden || !pose.in(world)) return;
        Optional<Plane> target = aim(pose);
        if (target.isEmpty()) return;
        Placed piece = target.get().placed();
        if (!piece.component().show().test(scope())) return;
        switch (piece.data()) {
            case MenuDocument.Button button -> actions.click(button.actions(), click, piece.scope());
            case MenuDocument.Toggle toggle -> {
                boolean state = toggles.computeIfAbsent(piece.key(), (String key) -> initial(toggle, piece.scope()));
                if (actions.completes(state ? toggle.falseActions() : toggle.trueActions(), click, piece.scope())) toggles.put(piece.key(), !state);
            }
            case MenuDocument.Tabs tabs -> piece.tab().ifPresent((MenuDocument.Tab tab) -> session.put(tabs.variable(), tab.id()));
            case MenuDocument.Slider slider -> {
                double current;
                try { current = Double.parseDouble(session.getOrDefault(slider.variable(), Double.toString(slider.min()))); }
                catch (NumberFormatException invalid) { current = slider.min(); }
                double step = slider.step() * (click.shift() ? 5 : 1) * (click.right() ? -1 : 1);
                session.put(slider.variable(), GuiExpression.text(Math.clamp(current + step, slider.min(), slider.max())));
            }
            default -> {}
        }
        dirty();
    }

    private static boolean initial(MenuDocument.Toggle toggle, GuiExpression.Scope scope) {
        try { return GuiExpression.text(toggle.condition().evaluate(scope)).equalsIgnoreCase(toggle.expected().render(scope)); }
        catch (GuiExpression.Invalid failed) { return false; }
    }

    private Optional<Plane> aim(ScreenHost.Pose pose) {
        double[] eye = {pose.x(), pose.eyeY(), pose.z()}, look = HoloService.look(pose.yaw(), pose.pitch());
        double[] normal = forward();
        Plane best = null;
        double nearest = REACH;
        for (Plane plane : planes()) {
            double facingDot = look[0] * -normal[0] + look[2] * -normal[2];
            double denominator = look[0] * normal[0] + look[1] * normal[1] + look[2] * normal[2];
            if (Math.abs(denominator) < 1.0E-6 || facingDot > 0) continue;
            double distance = ((plane.centre()[0] - eye[0]) * normal[0] + (plane.centre()[1] - eye[1]) * normal[1] + (plane.centre()[2] - eye[2]) * normal[2]) / denominator;
            if (distance < 0 || distance >= nearest) continue;
            double[] hit = {eye[0] + look[0] * distance - plane.centre()[0], eye[1] + look[1] * distance - plane.centre()[1], eye[2] + look[2] * distance - plane.centre()[2]};
            double[] right = right();
            double across = hit[0] * right[0] + hit[2] * right[2], up = hit[1];
            if (Math.abs(across) < plane.width() / 2 && Math.abs(up) < plane.height() / 2) { best = plane; nearest = distance; }
        }
        return Optional.ofNullable(best);
    }

    private List<Plane> planes() {
        List<Plane> planes = new ArrayList<>();
        for (Placed piece : placed) {
            if (piece.data() instanceof MenuDocument.Decoration) continue;
            double[] size = size(piece);
            MenuDocument.Hover settings = hoverOf(piece);
            double[] centre = centre(piece);
            if (settings.hitbox().isPresent()) {
                MenuDocument.Hitbox box = settings.hitbox().get();
                size = new double[]{box.width().orElse(size[0]), box.height().orElse(size[1])};
                centre = place(box.menuAnchor() ? origin() : centre, box.offset());
            }
            planes.add(new Plane(piece, centre, size[0], size[1]));
        }
        return planes;
    }

    private double[] centre(Placed piece) {
        double[] motion = motion(piece);
        double[] local = {piece.local()[0] + motion[0], piece.local()[1] + motion[1], piece.local()[2] + motion[2]};
        return place(origin(), local);
    }

    private double[] motion(Placed piece) {
        MenuDocument.Motion motion = piece.component().motion();
        if (motion == MenuDocument.Motion.NONE) return new double[]{0, 0, 0, 1, 0};
        GuiExpression.Scope scope = piece.scope();
        return new double[]{motion.x().value(scope), motion.y().value(scope), motion.z().value(scope), Math.max(0.001, motion.scale().value(scope)),
                Math.clamp(Math.round(motion.ticks().value(scope)), 0, 59)};
    }

    private static MenuDocument.Hover hoverOf(Placed piece) {
        return switch (piece.data()) {
            case MenuDocument.Button button -> button.hover();
            case MenuDocument.Toggle toggle -> toggle.hover();
            default -> MenuDocument.Hover.NONE;
        };
    }

    /** Click-plane width and height before a hitbox override. */
    private double[] size(Placed piece) {
        double scale = motion(piece)[3];
        MenuDocument.Icon icon = icon(piece);
        if (piece.data() instanceof MenuDocument.Slider slider) return new double[]{slider.width() * scale, TEXT_LINE * slider.style().scaleY() * scale};
        if (icon == null) return new double[]{Math.max(0.3, HoloParts.widest(piece.textRows()) * CHARACTER) * scale, TEXT_LINE * Math.max(1, piece.textRows().size()) * scale};
        return switch (icon) {
            case MenuDocument.TextIcon text -> new double[]{Math.max(0.3, HoloParts.widest(piece.textRows()) * CHARACTER * text.style().scaleX()) * scale,
                    TEXT_LINE * text.style().scaleY() * Math.max(1, piece.textRows().size()) * scale};
            case MenuDocument.ItemIcon item -> new double[]{0.75 * item.style().scaleX() * scale, 0.75 * item.style().scaleY() * scale};
            case MenuDocument.BlockIcon block -> new double[]{0.75 * block.style().scaleX() * scale, 0.75 * block.style().scaleY() * scale};
            case MenuDocument.MobIcon mob -> new double[]{mob.width() * scale, mob.height() * scale};
        };
    }

    private MenuDocument.Icon icon(Placed piece) {
        return switch (piece.data()) {
            case MenuDocument.Button button -> button.icon();
            case MenuDocument.Decoration decoration -> decoration.icon();
            case MenuDocument.Toggle toggle -> toggles.computeIfAbsent(piece.key(), (String key) -> initial(toggle, piece.scope())) ? toggle.trueIcon() : toggle.falseIcon();
            default -> null;
        };
    }

    /** Components, list entries and tabs in draw order, with their text read now. */
    private List<Placed> expand(GuiExpression.Scope base) {
        List<Placed> out = new ArrayList<>();
        for (MenuDocument.Component component : document.components()) {
            if (!component.show().test(base)) continue;
            switch (component.data()) {
                case MenuDocument.ListData list -> {
                    List<Object> entries;
                    try { entries = list.source().list(base); }
                    catch (GuiExpression.Invalid failed) { entries = List.of(); }
                    int pageCount = Math.max(1, (entries.size() + list.pageSize() - 1) / list.pageSize());
                    int page = Math.clamp(pages.getOrDefault(component.id(), 0), 0, pageCount - 1);
                    pages.put(component.id(), page);
                    for (int position = 0; position < list.pageSize(); position++) {
                        int index = page * list.pageSize() + position;
                        if (index >= entries.size()) break;
                        Object entry = entries.get(index);
                        GuiExpression.Scope scope = (String name) -> name.equals(list.variable()) ? entry : base.variable(name);
                        int column = position % list.flow().columns(), row = position / list.flow().columns();
                        double[] local = component.offset();
                        local = new double[]{local[0] + list.flow().x() + column * list.flow().spacingX(), local[1] + list.flow().y() - row * list.flow().spacingY(), local[2]};
                        out.add(placed(component.id() + "[" + position + "]", component, list.template(), local, scope, Optional.empty(), Optional.of(component.id())));
                    }
                }
                case MenuDocument.Tabs tabs -> {
                    int count = tabs.tabs().size();
                    for (int index = 0; index < count; index++) {
                        double[] local = component.offset();
                        local = new double[]{local[0] + index * tabs.spacing() - tabs.spacing() * (count - 1) / 2, local[1], local[2]};
                        out.add(placed(component.id() + "#" + index, component, tabs, local, base, Optional.of(tabs.tabs().get(index)), Optional.empty()));
                    }
                }
                default -> out.add(placed(component.id(), component, component.data(), component.offset(), base, Optional.empty(), Optional.empty()));
            }
        }
        return out;
    }

    private Placed placed(String key, MenuDocument.Component component, MenuDocument.Data data, double[] local, GuiExpression.Scope scope,
                          Optional<MenuDocument.Tab> tab, Optional<String> list) {
        List<String> rows = new ArrayList<>();
        Placed draft = new Placed(key, component, data, local, scope, tab, list, rows);
        String text = switch (data) {
            case MenuDocument.Tabs ignored -> tab.map((MenuDocument.Tab chosen) -> chosen.label().render(scope)).orElse("");
            case MenuDocument.Slider slider -> slider.label().render(scope);
            default -> icon(draft) instanceof MenuDocument.TextIcon icon ? icon.text().render(scope) : "";
        };
        if (!text.isEmpty()) for (String row : GuiText.colorize(text).split("\n", -1)) rows.add(row);
        return new Placed(key, component, data, local, scope, tab, list, List.copyOf(rows));
    }

    private Map<String, DisplayEntity> entities() {
        Map<String, DisplayEntity> out = new LinkedHashMap<>();
        double[] normal = forward();
        float yaw = (float) (Math.toDegrees(facing) + 180);
        for (Placed piece : placed) {
            double[] motion = motion(piece);
            float scale = (float) motion[3];
            int moveTicks = (int) motion[4];
            MenuDocument.Hover settings = hoverOf(piece);
            double lift = settings.highlight() * settings.easing().apply(hover.getOrDefault(piece.key(), 0.0));
            double[] centre = centre(piece);
            centre = new double[]{centre[0] - normal[0] * lift, centre[1], centre[2] - normal[2] * lift};
            MenuDocument.Icon icon = icon(piece);
            if (icon == null) {
                HoloParts.Style style = piece.data() instanceof MenuDocument.Tabs tabs ? tabs.style() : ((MenuDocument.Slider) piece.data()).style();
                text(out, piece.key(), piece.textRows(), style, centre, yaw, scale, moveTicks);
                continue;
            }
            switch (icon) {
                case MenuDocument.TextIcon text -> text(out, piece.key(), piece.textRows(), text.style(), centre, yaw, scale, moveTicks);
                case MenuDocument.ItemIcon item -> {
                    ScreenFrame.Item stack = GuiScreen.item(item.icon(), piece.scope());
                    out.put(piece.key(), new DisplayEntity(0, NONE, new DisplayEntity.Item(stack, item.icon().kind() == GuiModel.IconKind.PLAYER_HEAD ? 8 : 6),
                            centre[0], centre[1] - 0.05 * scale, centre[2], yaw, 0, item.style().display(scale, 0, 0, 0, moveTicks)));
                    if (stack.item().count() > 1) {
                        String badge = item.countFormat().map((GuiTemplate format) -> format.render(piece.scope())).orElse("{count}").replace("{count}", Integer.toString(stack.item().count()));
                        out.put(piece.key() + ":count", new DisplayEntity(0, NONE, HoloParts.Style.MENU.text(GuiText.colorize(badge)), centre[0], centre[1] - 0.37 * scale - TEXT_LINE / 2,
                                centre[2], yaw, 0, HoloParts.Style.MENU.display(scale, 0, 0, 0, moveTicks)));
                    }
                }
                case MenuDocument.BlockIcon block -> {
                    float size = 0.75f * scale;
                    out.put(piece.key(), new DisplayEntity(0, NONE, new DisplayEntity.Block(block.block()), centre[0], centre[1] - 0.05 * scale, centre[2], yaw, 0,
                            block.style().display(size, -size / 2, -size / 2, -size / 2, moveTicks)));
                }
                case MenuDocument.MobIcon mob -> out.put(piece.key(), new DisplayEntity(0, NONE, new DisplayEntity.Mob(mob.type()), centre[0], centre[1] - mob.height() * scale / 2,
                        centre[2], yaw, 0, DisplayEntity.Style.DEFAULT));
            }
            if (piece.data() instanceof MenuDocument.Button button && button.tooltip().isPresent()) {
                MenuDocument.Tooltip tip = button.tooltip().get();
                if (hovered.getOrDefault(piece.key(), 0) > tip.delayTicks() && !tip.lines().isEmpty()) {
                    List<String> rows = tip.lines().stream().map((GuiTemplate line) -> GuiText.colorize(line.render(piece.scope()))).toList();
                    double above = size(piece)[1] / 2 + 0.08;
                    text(out, piece.key() + ":tooltip", rows, tip.style(), new double[]{centre[0] - normal[0] * 0.05, centre[1] + above + TEXT_LINE * rows.size() / 2, centre[2] - normal[2] * 0.05},
                            yaw, 1, 0);
                }
            }
        }
        for (Plane plane : planes()) {
            double width = Math.min(64, Math.max(0.05, plane.width())), height = Math.min(64, Math.max(0.05, plane.height()));
            out.put(plane.placed().key() + ":box", new DisplayEntity(0, NONE, new DisplayEntity.Hitbox((float) width, (float) height),
                    plane.centre()[0], plane.centre()[1] - height / 2, plane.centre()[2], 0, 0, DisplayEntity.Style.DEFAULT));
        }
        return out;
    }

    private static void text(Map<String, DisplayEntity> out, String slot, List<String> rows, HoloParts.Style style, double[] centre, float yaw, float scale, int moveTicks) {
        if (rows.isEmpty()) return;
        double height = TEXT_LINE * style.scaleY() * scale * rows.size();
        out.put(slot, new DisplayEntity(0, NONE, style.text(String.join("\n", rows)), centre[0], centre[1] - height / 2, centre[2], yaw, 0,
                style.display(scale, 0, 0, 0, moveTicks)));
    }

    private GuiExpression.Scope scope() {
        int page = pages.values().stream().findFirst().orElse(0);
        return service.scope(viewer, document.id(), session, args, page);
    }

    private static double squared(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return x * x + y * y + z * z;
    }

    private boolean current() { return !closed && viewer.menu() == this; }

    private final class Actions implements GuiActions.Context<P, S> {
        @Override public P player() { return viewer.player(); }
        @Override public UUID id() { return viewer.id(); }
        @Override public ScreenHost<P, S> host() { return service.host(); }
        @Override public Map<String, String> session() { return session; }
        @Override public boolean open() { return current(); }
        @Override public boolean cooldown(String key, int ticks) { return viewer.cooldown(document.id() + "/" + key, ticks); }
        @Override public boolean schedule(int ticks, Runnable work) { return viewer.schedule(ticks, () -> { if (current()) work.run(); }); }
        @Override public void close() { service.closeMenu(viewer); }
        @Override public void refresh() { dirty(); }
        @Override public boolean navigate(GuiModel.Navigate navigate, String target) {
            switch (navigate.mode()) {
                case PAGE -> {
                    for (Map.Entry<String, Integer> list : pages.entrySet()) {
                        int next = switch (target.toLowerCase(Locale.ROOT)) {
                            case "next" -> list.getValue() + 1;
                            case "prev", "previous" -> list.getValue() - 1;
                            default -> { try { yield Integer.parseInt(target) - 1; } catch (NumberFormatException invalid) { yield list.getValue(); } }
                        };
                        list.setValue(Math.max(0, next));
                    }
                    dirty();
                    return true;
                }
                case PUSH -> { service.openMenu(viewer, GuiIds.relative(document.id(), target), Map.of(), true); return false; }
                case REPLACE -> { service.openMenu(viewer, GuiIds.relative(document.id(), target), Map.of(), false); return false; }
                case BACK -> { service.back(viewer); return false; }
                case HOME -> { service.home(viewer); return false; }
                case CLOSE -> { service.closeMenu(viewer); return false; }
            }
            return true;
        }
        @Override public void inventory(String id, Map<String, String> next) { service.closeMenu(viewer); service.openInventory(viewer, GuiIds.relative(document.id(), id), next); }
        @Override public void shop(String id) { service.closeMenu(viewer); service.openShop(viewer, GuiIds.relative(document.id(), id)); }
        @Override public List<Action> named(String name) { return document.actions().getOrDefault(name, List.of()); }
        @Override public Optional<double[]> location() { return Optional.of(origin()); }
    }
}
