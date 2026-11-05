package art.arcane.animus.core.gui;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.command.AnimusCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.gui.exchange.GlossExchange;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.shop.ShopService;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Holograms, holographic menus and text animations on one server, drawn with packet-only displays for each viewer.
 * Every viewer is ticked on its own owner, so on Folia each region draws for its own players; holograms bound to NPCs
 * follow the NPC's published position. Clicks arrive from {@link DisplayTransport} on the viewer's owner and are
 * checked again here: the viewer must be in the hologram's or menu's world, aim at it and be within reach.
 * Also owns {@code /animus gui}, which covers chest GUIs, menus, holograms, animations and Gloss exchange.
 */
public final class HoloService<P, S> implements AutoCloseable {
    public static final int MAXIMUM_HOLOGRAMS_PER_VIEWER = 64, SCAN_TICKS = 10, MAXIMUM_PENDING = 64, MAXIMUM_HISTORY = 16;
    public static final double HOLOGRAM_REACH = 8, CLICK_SLACK = 0.35;
    private final AnimusRuntime runtime;
    private final ShopService<P, S> shops;
    private final ScreenHost<P, S> host;
    private final DisplayTransport<P> transport;
    private final NpcRuntime<P, ?> living;
    private final Executor files;
    private final GlossBridge<P> gloss;
    private final GlossExchange exchange;
    private final Consumer<String> log;
    private final Path data;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private volatile HoloCatalog catalog = HoloCatalog.EMPTY;
    private volatile Map<String, NpcDefinition> bindingsSource;
    private volatile Map<UUID, GuiBinding> bindings = Map.of();
    private volatile boolean closed;

    public HoloService(AnimusRuntime runtime, ShopService<P, S> shops, DisplayTransport<P> transport, NpcRuntime<P, ?> living, Executor files,
                       GlossBridge<P> gloss, Consumer<String> log) throws IOException {
        this.runtime = runtime; this.shops = shops; this.host = shops.screens().host(); this.transport = transport; this.living = living;
        this.files = files; this.gloss = gloss; this.log = log;
        data = runtime.documents().resolve(ShopCatalog.SHOP_FOLDER).getParent();
        exchange = new GlossExchange(data, HoloCatalog.validator(data, runtime.behaviorSettings().actions()::commandAllowed), () -> gloss, files, log);
    }

    ScreenHost<P, S> host() { return host; }
    DisplayTransport<P> transport() { return transport; }
    public HoloCatalog catalog() { return catalog; }
    public Set<UUID> viewing() { return Set.copyOf(viewers.keySet()); }

    public String status() {
        HoloCatalog current = catalog;
        int menus = 0;
        for (Viewer viewer : viewers.values()) if (viewer.menu != null) menus++;
        return "Holograms and menus | holograms=" + current.holograms().size() + " | menus=" + current.menus().size() + " | animations=" + current.animations().size()
                + " | problems=" + current.problems().size() + " | open menus=" + menus + " | displays=" + Optional.ofNullable(transport.unavailable()).map((String reason) -> "unavailable (" + reason + ")").orElse("ready")
                + " | gloss=" + Optional.ofNullable(gloss.unavailable()).orElse("present");
    }

    /** One player's holograms, menu and what their actions remember. Used on the player's owner. */
    final class Viewer {
        private P player;
        private final UUID id;
        private final Map<String, String> session = new HashMap<>();
        private final Map<String, Long> cooldowns = new HashMap<>();
        private final List<Pending> pending = new ArrayList<>();
        private final Map<String, HologramView> holograms = new LinkedHashMap<>();
        private final Deque<Opened> history = new ArrayDeque<>();
        private MenuSession<P, S> menu;
        private Optional<UUID> menuNpc = Optional.empty();
        private long tick;
        private boolean watched;

        private Viewer(P player) { this.player = player; this.id = host.id(player); }
        P player() { return player; }
        UUID id() { return id; }
        MenuSession<P, S> menu() { return menu; }

        boolean cooldown(String key, int ticks) {
            Long until = cooldowns.get(key);
            if (until != null && until > tick) return false;
            cooldowns.put(key, tick + ticks);
            return true;
        }

        boolean schedule(int ticks, Runnable work) {
            if (pending.size() >= MAXIMUM_PENDING) { log.accept("Holograms and menus for " + id + " have " + MAXIMUM_PENDING + " delayed actions; one was dropped"); return false; }
            pending.add(new Pending(tick + Math.max(1, ticks), work));
            return true;
        }
    }
    private record Pending(long at, Runnable work) {}
    private record Opened(String id, Map<String, String> args) {}

    /** One hologram as one viewer sees it: its page, its displays and its click boxes. */
    private final class HologramView {
        final String key;
        final HologramDocument document;
        final Optional<UUID> npc;
        final DisplaySet<P> displays = new DisplaySet<>(transport);
        Map<String, HologramRender.Box> boxes = Map.of();
        String page;
        long renderedAt = Long.MIN_VALUE, checkedAt = Long.MIN_VALUE;
        double x, y, z;
        boolean shown;
        HologramView(String key, HologramDocument document, Optional<UUID> npc) { this.key = key; this.document = document; this.npc = npc; }
    }

    /** Reads every document on the file lane, then the chest GUIs and shops, and reports problems. */
    public void reload(Consumer<HoloCatalog> done) {
        try {
            files.execute(() -> {
                HoloCatalog next;
                try { next = HoloCatalog.load(data, runtime.behaviorSettings().actions()::commandAllowed); }
                catch (IOException | RuntimeException failure) {
                    log.accept("Hologram and menu catalog could not be read: " + failure.getMessage());
                    next = new HoloCatalog(catalog.holograms(), catalog.menus(), catalog.animations(), List.of("catalog: " + failure.getMessage()));
                }
                catalog = next;
                next.problems().stream().limit(40).forEach((String problem) -> log.accept("Hologram or menu refused: " + problem));
                done.accept(next);
            });
        } catch (RejectedExecutionException full) { log.accept("Hologram and menu reload refused: the file queue is full"); }
    }

    /** One owner tick for one online player. */
    public void tick(P player) {
        if (closed) return;
        Viewer viewer = viewers.computeIfAbsent(host.id(player), (UUID ignored) -> new Viewer(player));
        viewer.player = player;
        viewer.tick++;
        if (!viewer.watched && transport.unavailable() == null) {
            viewer.watched = true;
            transport.watch(player, (DisplayTransport.Click click) -> click(player, click));
        }
        Iterator<Pending> due = viewer.pending.iterator();
        List<Runnable> run = new ArrayList<>();
        while (due.hasNext()) { Pending task = due.next(); if (task.at() <= viewer.tick) { run.add(task.work()); due.remove(); } }
        for (Runnable work : run) {
            try { work.run(); }
            catch (RuntimeException failure) { log.accept("Delayed hologram or menu action failed for " + viewer.id + ": " + failure); }
        }
        Optional<ScreenHost.Pose> pose = host.pose(player);
        if (pose.isEmpty() || transport.unavailable() != null) return;
        try { holograms(viewer, pose.get()); }
        catch (RuntimeException failure) { log.accept("Holograms failed to draw for " + viewer.id + ": " + failure); }
        if (viewer.menu != null) {
            boolean open;
            try { open = viewer.menu.tick(pose.get(), viewer.tick); }
            catch (RuntimeException failure) { log.accept("Menu failed for " + viewer.id + ": " + failure); viewer.menu.close(); open = false; }
            if (!open) { viewer.menu = null; viewer.history.clear(); }
        }
    }

    private void holograms(Viewer viewer, ScreenHost.Pose pose) {
        HoloCatalog current = catalog;
        boolean scan = viewer.tick % SCAN_TICKS == 1 || viewer.holograms.isEmpty() && viewer.tick % SCAN_TICKS == 0;
        if (scan) {
            Map<String, Candidate> wanted = new LinkedHashMap<>();
            for (HologramDocument document : current.holograms().values()) {
                if (wanted.size() >= MAXIMUM_HOLOGRAMS_PER_VIEWER) break;
                if (pose.in(document.world()) && near(pose, document.x(), document.y(), document.z(), document.viewDistance()))
                    wanted.put(document.id(), new Candidate(document, Optional.empty()));
            }
            for (Map.Entry<UUID, GuiBinding> bound : bindings().entrySet()) {
                if (wanted.size() >= MAXIMUM_HOLOGRAMS_PER_VIEWER) break;
                Optional<GuiBinding.Hologram> hologram = bound.getValue().hologram();
                if (hologram.isEmpty()) continue;
                Optional<HologramDocument> document = current.hologram(hologram.get().id());
                Optional<double[]> at = npcAnchor(bound.getKey(), hologram.get(), pose);
                if (document.isPresent() && at.isPresent() && near(pose, at.get()[0], at.get()[1], at.get()[2], document.get().viewDistance()))
                    wanted.put("npc:" + bound.getKey(), new Candidate(document.get(), Optional.of(bound.getKey())));
            }
            Iterator<Map.Entry<String, HologramView>> views = viewer.holograms.entrySet().iterator();
            while (views.hasNext()) {
                Map.Entry<String, HologramView> view = views.next();
                Candidate candidate = wanted.get(view.getKey());
                if (candidate == null || candidate.document() != view.getValue().document) { view.getValue().displays.clear(viewer.player); views.remove(); }
            }
            for (Map.Entry<String, Candidate> entry : wanted.entrySet())
                viewer.holograms.computeIfAbsent(entry.getKey(), (String key) -> new HologramView(key, entry.getValue().document(), entry.getValue().npc()));
        }
        for (HologramView view : viewer.holograms.values()) draw(viewer, view, pose);
    }
    private record Candidate(HologramDocument document, Optional<UUID> npc) {}

    private void draw(Viewer viewer, HologramView view, ScreenHost.Pose pose) {
        HologramDocument document = view.document;
        double[] at = {document.x(), document.y(), document.z()};
        if (view.npc.isPresent()) {
            GuiBinding binding = bindings().get(view.npc.get());
            Optional<double[]> moved = binding == null ? Optional.empty() : binding.hologram().flatMap((GuiBinding.Hologram bound) -> npcAnchor(view.npc.get(), bound, pose));
            if (moved.isEmpty()) { view.displays.clear(viewer.player); view.shown = false; return; }
            at = moved.get();
        }
        boolean movedAnchor = at[0] != view.x || at[1] != view.y || at[2] != view.z;
        boolean due = view.renderedAt == Long.MIN_VALUE || viewer.tick - view.renderedAt >= document.contentTicks() || movedAnchor;
        boolean check = view.checkedAt == Long.MIN_VALUE || viewer.tick - view.checkedAt >= document.visibilityTicks();
        if (!due && !check) return;
        GuiExpression.Scope base = hologramScope(viewer, view);
        if (check) {
            view.checkedAt = viewer.tick;
            boolean visible = document.show().test(base);
            if (!visible) { if (view.shown) view.displays.clear(viewer.player); view.shown = false; return; }
            if (!view.shown) due = true;
        }
        if (!due) return;
        view.x = at[0]; view.y = at[1]; view.z = at[2];
        List<HologramDocument.Line> lines = document.lines();
        HoloParts.Style style = document.style();
        if (!document.pages().isEmpty()) {
            HologramDocument.Page page = page(view, base);
            lines = page == null ? List.of() : page.lines();
        }
        for (HologramDocument.Variant variant : document.orderedVariants()) {
            if (!GuiActions.test(variant.when(), base)) continue;
            if (variant.lines().isPresent()) lines = variant.lines().get();
            if (variant.style().isPresent()) style = variant.style().get();
            break;
        }
        HologramRender.Render render = HologramRender.build(document, lines, style, base, at[0], at[1], at[2]);
        view.displays.apply(viewer.player, render.entities());
        view.boxes = render.boxes();
        view.renderedAt = viewer.tick;
        view.shown = true;
    }

    /** The viewer's page, or the first page they may read. */
    private HologramDocument.Page page(HologramView view, GuiExpression.Scope scope) {
        List<HologramDocument.Page> readable = view.document.pages().stream().filter((HologramDocument.Page page) -> page.show().test(scope)).toList();
        if (readable.isEmpty()) return null;
        for (HologramDocument.Page page : readable) if (page.id().equals(view.page)) return page;
        view.page = readable.getFirst().id();
        return readable.getFirst();
    }

    private Optional<double[]> npcAnchor(UUID npc, GuiBinding.Hologram bound, ScreenHost.Pose pose) {
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc);
        if (active.isEmpty()) return Optional.empty();
        VirtualNpcState.Snapshot state = active.get().state();
        if (!pose.in(state.position().world())) return Optional.empty();
        double height = state.bounds().max().y() - state.bounds().min().y();
        double[] offset = bound.offset().orElse(new double[]{0, height + 0.6, 0});
        return Optional.of(new double[]{state.position().point().x() + offset[0], state.position().point().y() + offset[1], state.position().point().z() + offset[2]});
    }

    private static boolean near(ScreenHost.Pose pose, double x, double y, double z, double distance) {
        double dx = pose.x() - x, dy = pose.y() - y, dz = pose.z() - z;
        return dx * dx + dy * dy + dz * dz <= distance * distance;
    }

    /** A click from the transport, on the viewer's owner. */
    public void click(P player, DisplayTransport.Click click) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer == null || closed) return;
        viewer.player = player;
        Optional<ScreenHost.Pose> pose = host.pose(player);
        if (pose.isEmpty()) return;
        boolean shift = click.secondary() || pose.get().sneaking();
        GuiModel.Click kind = click.attack() ? (shift ? GuiModel.Click.SHIFT_LEFT : GuiModel.Click.LEFT) : (shift ? GuiModel.Click.SHIFT_RIGHT : GuiModel.Click.RIGHT);
        try {
            if (viewer.menu != null && viewer.menu.owns(click.entityId())) { viewer.menu.click(pose.get(), kind); return; }
            for (HologramView view : List.copyOf(viewer.holograms.values())) {
                Optional<String> slot = view.displays.slot(click.entityId());
                if (slot.isEmpty()) continue;
                HologramRender.Box box = view.boxes.get(slot.get());
                if (box == null || !view.shown || !pose.get().in(view.npc.isPresent() ? HoloService.world(pose.get()) : view.document.world())) return;
                double[] eye = {pose.get().x(), pose.get().eyeY(), pose.get().z()};
                if (!box.hit(eye, look(pose.get().yaw(), pose.get().pitch()), HOLOGRAM_REACH, CLICK_SLACK)) return;
                new GuiActions<>(new HologramActions(viewer, view)).click(view.document.actions(), kind, hologramScope(viewer, view));
                view.renderedAt = Long.MIN_VALUE;
                return;
            }
        } catch (RuntimeException failure) { log.accept("Hologram or menu click failed for " + viewer.id + ": " + failure); }
    }

    private final class HologramActions implements GuiActions.Context<P, S> {
        private final Viewer viewer;
        private final HologramView view;
        HologramActions(Viewer viewer, HologramView view) { this.viewer = viewer; this.view = view; }
        @Override public P player() { return viewer.player; }
        @Override public UUID id() { return viewer.id; }
        @Override public ScreenHost<P, S> host() { return host; }
        @Override public Map<String, String> session() { return viewer.session; }
        @Override public boolean open() { return !closed && viewer.holograms.get(view.key) == view; }
        @Override public boolean cooldown(String key, int ticks) { return viewer.cooldown(view.key + "/" + key, ticks); }
        @Override public boolean schedule(int ticks, Runnable work) { return viewer.schedule(ticks, () -> { if (open()) work.run(); }); }
        @Override public void close() {}
        @Override public void refresh() { view.renderedAt = Long.MIN_VALUE; }
        @Override public boolean navigate(GuiModel.Navigate navigate, String target) {
            if (navigate.mode() != GuiModel.NavigateMode.PAGE || view.document.pages().isEmpty()) return true;
            GuiExpression.Scope scope = hologramScope(viewer, view);
            List<HologramDocument.Page> readable = view.document.pages().stream().filter((HologramDocument.Page page) -> page.show().test(scope)).toList();
            if (readable.isEmpty()) return true;
            int at = 0;
            for (int index = 0; index < readable.size(); index++) if (readable.get(index).id().equals(view.page)) at = index;
            int next = switch (target.toLowerCase(Locale.ROOT)) {
                case "next" -> Math.floorMod(at + 1, readable.size());
                case "prev", "previous" -> Math.floorMod(at - 1, readable.size());
                default -> {
                    int found = at;
                    for (int index = 0; index < readable.size(); index++) if (readable.get(index).id().equals(target)) found = index;
                    yield found;
                }
            };
            view.page = readable.get(next).id();
            view.renderedAt = Long.MIN_VALUE;
            return true;
        }
        @Override public void inventory(String id, Map<String, String> args) { openInventory(viewer, GuiIds.relative(view.document.id(), id), args); }
        @Override public void shop(String id) { openShop(viewer, GuiIds.relative(view.document.id(), id)); }
        @Override public List<GuiModel.Action> named(String name) { return List.of(); }
        @Override public Optional<double[]> location() { return Optional.of(new double[]{view.x, view.y, view.z}); }
    }

    /** On the player's owner. Opens a menu by id, replacing any open menu. */
    public boolean openMenu(P player, String id, Map<String, String> args, Optional<UUID> npc) {
        if (closed) return false;
        Viewer viewer = viewers.computeIfAbsent(host.id(player), (UUID ignored) -> new Viewer(player));
        viewer.player = player;
        viewer.history.clear();
        viewer.menuNpc = npc;
        return openMenu(viewer, id, args, false);
    }

    boolean openMenu(Viewer viewer, String id, Map<String, String> args, boolean push) {
        Optional<MenuDocument> document = catalog.menu(id);
        if (document.isEmpty()) { host.message(viewer.player, GuiText.colorize("&cNo menu named " + id + ".")); return false; }
        if (transport.unavailable() != null) { host.message(viewer.player, GuiText.colorize("&cMenus are unavailable here: " + transport.unavailable())); return false; }
        Optional<ScreenHost.Pose> pose = host.pose(viewer.player);
        if (pose.isEmpty()) return false;
        if (!viewer.watched) { viewer.watched = true; P player = viewer.player; transport.watch(player, (DisplayTransport.Click click) -> click(player, click)); }
        MenuSession<P, S> next = new MenuSession<>(this, viewer, document.get(), args, pose.get());
        if (!document.get().show().test(scope(viewer, id, Map.of(), args, 0))) { host.message(viewer.player, GuiText.colorize("&cYou cannot open that menu.")); return false; }
        if (viewer.menu != null) {
            if (push) { viewer.history.push(new Opened(viewer.menu.document.id(), viewer.menu.args)); while (viewer.history.size() > MAXIMUM_HISTORY) viewer.history.removeLast(); }
            viewer.menu.close();
        }
        viewer.menu = next;
        next.tick(pose.get(), viewer.tick);
        return true;
    }

    void closeMenu(Viewer viewer) { if (viewer.menu != null) { viewer.menu.close(); viewer.menu = null; } viewer.history.clear(); }
    void back(Viewer viewer) {
        if (viewer.history.isEmpty()) { closeMenu(viewer); return; }
        Opened previous = viewer.history.pop();
        if (viewer.menu != null) { viewer.menu.close(); viewer.menu = null; }
        openMenu(viewer, previous.id(), previous.args(), false);
    }
    void home(Viewer viewer) {
        if (viewer.history.isEmpty()) return;
        Opened first = viewer.history.getLast();
        viewer.history.clear();
        if (viewer.menu != null) { viewer.menu.close(); viewer.menu = null; }
        openMenu(viewer, first.id(), first.args(), false);
    }
    void openInventory(Viewer viewer, String id, Map<String, String> args) { shops.openGui(viewer.player, id, args, viewer.menuNpc); }
    void openShop(Viewer viewer, String id) { shops.openShop(viewer.player, id, viewer.menuNpc); }

    /** On the player's owner: the behavior action {@code animus:open-gui} and NPC bindings. */
    public boolean open(P player, GuiBinding.Kind kind, String id, Map<String, String> args, Optional<UUID> npc) {
        return switch (kind) {
            case INVENTORY -> shops.openGui(player, id, args, npc);
            case SHOP -> shops.openShop(player, id, npc);
            case MENU -> openMenu(player, id, args, npc);
            case GLOSS -> gloss.unavailable() == null && gloss.open(player, id);
        };
    }

    /** Why a kind cannot open on this server, or empty; checked before work is handed to the player's owner. */
    public Optional<CapabilityRefusal> refusal(GuiBinding.Kind kind) {
        if (kind == GuiBinding.Kind.GLOSS && gloss.unavailable() != null)
            return Optional.of(new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "gloss-unavailable", gloss.unavailable()));
        if (kind == GuiBinding.Kind.MENU && transport.unavailable() != null)
            return Optional.of(new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "displays-unavailable", transport.unavailable()));
        if ((kind == GuiBinding.Kind.INVENTORY || kind == GuiBinding.Kind.SHOP) && !shops.enabled())
            return Optional.of(new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "screens-unavailable", "Shops and chest GUIs are disabled in config.toml"));
        return Optional.empty();
    }

    public static GuiBinding.Kind kind(BehaviorPlatform.ScreenKind kind) {
        return switch (kind) { case GUI -> GuiBinding.Kind.INVENTORY; case SHOP -> GuiBinding.Kind.SHOP; case MENU -> GuiBinding.Kind.MENU; case GLOSS -> GuiBinding.Kind.GLOSS; };
    }

    /** A validated NPC click on the player's owner; true when an {@code animus:gui} binding used it. */
    public boolean interacted(P player, UUID npc, boolean attack, boolean sneaking) {
        if (closed) return false;
        GuiBinding binding = bindings().get(npc);
        if (binding == null || binding.open().isEmpty() || !binding.open().get().opensOn(attack)) return false;
        if (sneaking && host.permitted(player, "animus.admin")) return false;
        GuiBinding.Open target = binding.open().get();
        if (refusal(target.kind()).isPresent()) { host.message(player, GuiText.colorize("&c" + refusal(target.kind()).get().getMessage())); return true; }
        open(player, target.kind(), target.id(), Map.of(), Optional.of(npc));
        return true;
    }

    private Map<UUID, GuiBinding> bindings() {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot != bindingsSource) {
            Map<UUID, GuiBinding> next = new HashMap<>();
            for (NpcDefinition definition : snapshot.values()) {
                try { GuiBinding.of(definition).ifPresent((GuiBinding binding) -> next.put(definition.uuid(), binding)); }
                catch (IllegalArgumentException invalid) { /* Definitions are validated on load; an invalid module binds nothing. */ }
            }
            bindings = Map.copyOf(next);
            bindingsSource = snapshot;
        }
        return bindings;
    }

    /** The player left; nothing more is sent to them. */
    public void quit(P player) {
        Viewer viewer = viewers.remove(host.id(player));
        if (viewer == null) return;
        if (viewer.menu != null) viewer.menu.forget();
        viewer.holograms.values().forEach((HologramView view) -> view.displays.forget());
        transport.forget(player);
    }
    /** The client dropped every entity: a respawn or world change. Holograms redraw on the next scan. */
    public void worldChanged(P player) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer == null) return;
        if (viewer.menu != null) { viewer.menu.forget(); viewer.menu = null; viewer.history.clear(); }
        viewer.holograms.values().forEach((HologramView view) -> view.displays.forget());
        viewer.holograms.clear();
    }
    public void died(P player) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer != null && viewer.menu != null && viewer.menu.document.closeOnDeath()) closeMenu(viewer);
    }
    /** Holograms are redrawn where the player arrives; a menu closes when it asks to, otherwise it moves with the player. */
    public void teleported(P player) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer == null) return;
        if (viewer.menu != null) { if (viewer.menu.document.closeOnTeleport()) closeMenu(viewer); else viewer.menu.relocate(); }
        viewer.holograms.values().forEach((HologramView view) -> view.displays.clear(player));
        viewer.holograms.clear();
    }

    /** {@code viewer.*}, {@code server.*}, {@code time.*}, {@code args.*}, {@code session.*}, {@code npc.*}, {@code page}, {@code pages} and animations. */
    GuiExpression.Scope scope(Viewer viewer, String document, Map<String, String> session, Map<String, String> args, int page) {
        return scope(viewer, document, session, args, page, 1, viewer.menu != null ? viewer.menuNpc : Optional.empty());
    }

    private GuiExpression.Scope hologramScope(Viewer viewer, HologramView view) {
        int pages = Math.max(1, view.document.pages().size());
        int page = 0;
        for (int index = 0; index < view.document.pages().size(); index++) if (view.document.pages().get(index).id().equals(view.page)) page = index;
        return scope(viewer, view.document.id(), viewer.session, Map.of(), page, pages, view.npc);
    }

    private GuiExpression.Scope scope(Viewer viewer, String document, Map<String, String> session, Map<String, String> args, int page, int pages, Optional<UUID> npc) {
        Set<String> rendering = new HashSet<>();
        GuiExpression.Scope[] self = new GuiExpression.Scope[1];
        self[0] = (String name) -> {
            int dot = name.indexOf('.');
            String root = dot < 0 ? name : name.substring(0, dot);
            String property = dot < 0 ? "" : name.substring(dot + 1);
            return switch (root) {
                case "viewer", "player" -> switch (property) {
                    case "name" -> host.name(viewer.player);
                    case "uuid" -> viewer.id.toString();
                    default -> host.viewer(viewer.player, property);
                };
                case "server" -> host.viewer(viewer.player, "server." + property);
                case "time" -> GuiScreen.time(property);
                case "args" -> args.get(property);
                case "session" -> session.get(property);
                case "npc" -> npc.map((UUID id) -> npc(id, property)).orElse(null);
                case "page" -> (double) (page + 1);
                case "pages" -> (double) pages;
                case GuiTemplate.ANIMATION -> {
                    String id = GuiIds.relative(document, property);
                    Optional<TextAnimation> animation = catalog.animation(id);
                    if (animation.isEmpty() || !rendering.add(id)) yield "";
                    try { yield animation.get().render(System.currentTimeMillis(), self[0]); }
                    finally { rendering.remove(id); }
                }
                default -> null;
            };
        };
        return self[0];
    }

    private Object npc(UUID npc, String property) {
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc);
        return switch (property) {
            case "uuid" -> npc.toString();
            case "id" -> active.map(NpcRuntime.ActiveNpc::id).orElse("");
            case "name" -> active.map(NpcRuntime.ActiveNpc::name).orElse("");
            case "spawned" -> active.isPresent();
            case "health" -> active.map((NpcRuntime.ActiveNpc state) -> state.state().health()).orElse(0.0);
            default -> null;
        };
    }

    /** A world name a teleport understands: the key when the pose has one. */
    static String world(ScreenHost.Pose pose) {
        for (String name : pose.worlds()) if (name.contains(":") && !name.matches("[0-9a-f-]{36}")) return name;
        return pose.worlds().stream().filter((String name) -> !name.matches("[0-9a-f-]{36}")).findFirst().orElse(pose.worlds().iterator().next());
    }

    /** The unit look vector for a Minecraft yaw and pitch in degrees. */
    static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /** {@code /animus gui ...}. */
    public AnimusCommands.Extra command() {
        return new AnimusCommands.Extra() {
            @Override public void execute(CommandActor actor, String[] arguments) { command(actor, arguments); }
            @Override public List<String> complete(CommandActor actor, String[] arguments) {
                if (arguments.length == 2) return matching(List.of("status", "list", "open", "menu", "close", "reload", "problems", "import", "export"), arguments[1]);
                String operation = arguments[1].toLowerCase(Locale.ROOT);
                if (arguments.length == 3 && operation.equals("open")) return matching(List.copyOf(shops.catalog().guis().keySet()), arguments[2]);
                if (arguments.length == 3 && operation.equals("menu")) return matching(List.copyOf(catalog.menus().keySet()), arguments[2]);
                if (arguments.length == 3 && operation.equals("list")) return matching(List.of("inventory", "menu", "hologram", "animation"), arguments[2]);
                if (operation.equals("import") || operation.equals("export")) return exchange.complete(arguments);
                return List.of();
            }
        };
    }

    private void command(CommandActor actor, String[] arguments) {
        String operation = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "status" -> { actor.tell(status()); actor.tell(shops.status()); }
            case "list" -> list(actor, arguments.length > 2 ? arguments[2].toLowerCase(Locale.ROOT) : "all");
            case "problems" -> {
                List<String> problems = new ArrayList<>(shops.catalog().problems());
                problems.addAll(catalog.problems());
                actor.tell("GUI problems: " + problems.size());
                problems.stream().limit(30).forEach(actor::tell);
            }
            case "reload" -> reloadAll(actor);
            case "open" -> {
                if (arguments.length < 3 || arguments.length > 4) { actor.tell("gui open <gui> [player]"); return; }
                deliver(actor, arguments.length == 4 ? Optional.of(arguments[3]) : Optional.empty(), (P player) -> shops.openGui(player, arguments[2], Map.of(), Optional.empty()));
            }
            case "menu" -> {
                if (arguments.length < 3 || arguments.length > 4) { actor.tell("gui menu <menu> [player]"); return; }
                if (transport.unavailable() != null) { actor.tell("Menus are unavailable: " + transport.unavailable()); return; }
                deliver(actor, arguments.length == 4 ? Optional.of(arguments[3]) : Optional.empty(), (P player) -> openMenu(player, arguments[2], Map.of(), Optional.empty()));
            }
            case "close" -> deliver(actor, arguments.length == 3 ? Optional.of(arguments[2]) : Optional.empty(), (P player) -> {
                Viewer viewer = viewers.get(host.id(player));
                if (viewer != null) closeMenu(viewer);
            });
            case "import", "export" -> exchange.command(actor, arguments, () -> reloadAll(actor));
            default -> actor.tell("gui status | list [inventory|menu|hologram|animation] | open <gui> [player] | menu <menu> [player] | close [player] | reload | problems"
                    + " | import gloss|file ... | export gloss|file ...");
        }
    }

    private void list(CommandActor actor, String kind) {
        if (kind.equals("all") || kind.equals("inventory")) actor.tell("Chest GUIs: " + String.join(", ", shops.catalog().guis().keySet().stream().limit(50).toList()));
        if (kind.equals("all") || kind.equals("menu")) actor.tell("Menus: " + String.join(", ", catalog.menus().keySet().stream().limit(50).toList()));
        if (kind.equals("all") || kind.equals("hologram")) {
            actor.tell("Holograms: " + catalog.holograms().size());
            catalog.holograms().values().stream().limit(50).forEach((HologramDocument hologram) -> actor.tell(hologram.id() + " | " + hologram.world()
                    + " " + Math.round(hologram.x()) + "," + Math.round(hologram.y()) + "," + Math.round(hologram.z()) + " | revision " + hologram.revision()));
        }
        if (kind.equals("all") || kind.equals("animation")) actor.tell("Text animations: " + String.join(", ", catalog.animations().keySet().stream().limit(50).toList()));
    }

    private void reloadAll(CommandActor actor) {
        shops.reload((ShopCatalog chest) -> reload((HoloCatalog next) -> answer(actor, "Reloaded " + chest.guis().size() + " chest GUIs, " + chest.shops().size() + " shops, "
                + next.menus().size() + " menus, " + next.holograms().size() + " holograms and " + next.animations().size() + " animations; "
                + (chest.problems().size() + next.problems().size()) + " problems")));
    }

    private void deliver(CommandActor actor, Optional<String> named, Consumer<P> work) {
        Optional<UUID> target = named.isPresent() ? shops.playerNamed(named.get()) : actor.player();
        if (target.isEmpty()) { actor.tell(named.map((String name) -> "Unknown player: " + name).orElse("Name a player when running this from the console.")); return; }
        if (!living.players().deliver(target.get(), work)) actor.tell("That player is not online.");
    }

    private void answer(CommandActor actor, String message) { if (!closed && !actor.reply(() -> actor.tell(message))) log.accept(message); }

    private static List<String> matching(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter((String option) -> option.startsWith(lowered)).sorted().limit(100).toList();
    }

    /** Forgets every viewer and removes every display the transport still shows. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        viewers.clear();
        transport.close();
    }
}
