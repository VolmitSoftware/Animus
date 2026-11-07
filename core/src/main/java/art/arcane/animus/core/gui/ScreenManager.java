package art.arcane.animus.core.gui;

import art.arcane.animus.core.AnimusLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Every open Animus window, one per player. The server is authoritative: a click only runs a screen's own handler,
 * and no item moves between the window and the player except through a shop transaction or a {@code give} or
 * {@code take} action. Windows close on logout, death, world change, teleport (when the screen asks), despawn of the
 * NPC that opened them, and shutdown. All work for one player runs on that player's owner thread.
 *
 * @param <P> the platform's online player
 * @param <S> the platform's item stack
 */
public final class ScreenManager<P, S> {
    public static final int MAXIMUM_PENDING = 64, MAXIMUM_HISTORY = 16;
    private final ScreenHost<P, S> host;
    private final Predicate<UUID> npcActive;
    private final Consumer<String> warn;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();

    public ScreenManager(ScreenHost<P, S> host, Predicate<UUID> npcActive, Consumer<String> warn) {
        this.host = Objects.requireNonNull(host);
        this.npcActive = Objects.requireNonNull(npcActive);
        this.warn = Objects.requireNonNull(warn);
    }

    public ScreenHost<P, S> host() { return host; }

    /** One player's open screen and what it remembers between clicks. */
    public final class Viewer {
        private P player;
        private final UUID id;
        private final Optional<UUID> npc;
        private final Map<String, String> session = new HashMap<>();
        private final Map<String, Long> cooldowns = new HashMap<>();
        private final Deque<Screen> history = new ArrayDeque<>();
        private final List<Pending> pending = new ArrayList<>();
        private Screen screen;
        private ScreenFrame frame;
        private Object window;
        private long tick;
        private boolean closed;

        private Viewer(P player, Optional<UUID> npc) { this.player = player; this.id = host.id(player); this.npc = npc; }

        public P player() { return player; }
        public UUID id() { return id; }
        public Optional<UUID> npc() { return npc; }
        public ScreenHost<P, S> host() { return host; }
        public Map<String, String> session() { return session; }
        public long tick() { return tick; }
        public boolean open() { return !closed; }

        /** Shows another screen in this window without remembering the current one. */
        public void replace(Screen next) { if (!closed) { screen = next; render(true); } }
        /** Shows another screen and remembers the current one for {@code back}. */
        public void push(Screen next) {
            if (closed) return;
            if (screen != null) { history.push(screen); while (history.size() > MAXIMUM_HISTORY) history.removeLast(); }
            screen = next;
            render(true);
        }
        public void back() { if (closed) return; if (history.isEmpty()) close(); else { screen = history.pop(); render(true); } }
        public void home() { if (closed) return; if (history.isEmpty()) return; screen = history.getLast(); history.clear(); render(true); }
        public void close() { if (!closed) { Object open = window; forget(this); host.close(player, open); } }
        public void refresh() { if (!closed) render(false); }
        /** Hides the window while the player types somewhere else; the screen and its history stay for {@link #resume}. */
        public void suspend() {
            if (closed || window == null) return;
            Object open = window;
            window = null;
            frame = null;
            host.close(player, open);
        }
        /** Shows the screen again after {@link #suspend}; nothing happens while the window is still showing. */
        public void resume() { if (!closed && window == null) render(true); }
        public boolean suspended() { return !closed && window == null; }

        /** Runs the work after the given number of owner ticks while the window stays open. */
        public boolean schedule(int ticks, Runnable work) {
            if (closed) return false;
            if (pending.size() >= MAXIMUM_PENDING) {
                AnimusLog.shared().routine("screens dropped a delayed action at the limit", String.valueOf(id), "Screen for " + id + " has " + MAXIMUM_PENDING + " delayed actions; one was dropped", warn);
                return false;
            }
            pending.add(new Pending(tick + Math.max(1, ticks), work));
            return true;
        }

        /** True and starts the cooldown when the key is not cooling down. */
        public boolean cooldown(String key, int ticks) {
            Long until = cooldowns.get(key);
            if (until != null && until > tick) return false;
            cooldowns.put(key, tick + ticks);
            return true;
        }

        private void render(boolean reopen) {
            if (window == null && !reopen) return;
            ScreenFrame next;
            try { next = screen.render(); }
            catch (RuntimeException failure) { warn.accept("Screen failed to render for " + id + ": " + failure); close(); return; }
            if (!reopen && next.equals(frame)) return;
            Optional<Object> shown = host.show(player, next, window);
            if (shown.isEmpty()) { close(); return; }
            window = shown.get();
            frame = next;
        }
    }
    private record Pending(long at, Runnable work) {}

    /**
     * Opens a screen for the player, replacing any window this manager has open for them. The factory may tell the
     * player why it cannot open and return empty. Returns whether a window is now open.
     */
    public boolean open(P player, Optional<UUID> npc, Function<Viewer, Optional<Screen>> factory) {
        Viewer previous = viewers.get(host.id(player));
        Viewer viewer = new Viewer(player, npc);
        Optional<Screen> screen;
        try { screen = factory.apply(viewer); }
        catch (RuntimeException failure) { warn.accept("Screen could not open for " + viewer.id + ": " + failure); return false; }
        if (screen.isEmpty()) return false;
        if (previous != null) { viewer.window = previous.window; forget(previous); }
        viewers.put(viewer.id, viewer);
        viewer.screen = screen.get();
        viewer.render(true);
        return !viewer.closed;
    }

    public Optional<Viewer> viewer(UUID player) { return Optional.ofNullable(viewers.get(player)); }
    public Set<UUID> viewing() { return Set.copyOf(viewers.keySet()); }

    /** A click on a top-window slot. Clicks on a stale window are ignored. */
    public void click(P player, Object window, int slot, GuiModel.Click click) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer == null || viewer.window != window || viewer.closed) return;
        viewer.player = player;
        if (slot < 0 || viewer.frame == null || slot >= viewer.frame.size()) return;
        Screen before = viewer.screen;
        try { before.click(slot, click); }
        catch (RuntimeException failure) { warn.accept("Screen click failed for " + viewer.id + ": " + failure); viewer.close(); return; }
        if (!viewer.closed && viewer.screen == before) viewer.render(false);
    }

    /** The client or platform closed the window. Stale windows are ignored. */
    public void closed(P player, Object window) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer != null && viewer.window == window) forget(viewer);
    }

    /** The player left; nothing is sent to them. */
    public void quit(P player) { Viewer viewer = viewers.get(host.id(player)); if (viewer != null) forget(viewer); }
    public void died(P player) { close(player); }
    public void worldChanged(P player) { close(player); }
    public void teleported(P player) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer != null && viewer.screen.closeOnTeleport()) viewer.close();
    }
    public void close(P player) { Viewer viewer = viewers.get(host.id(player)); if (viewer != null) viewer.close(); }

    /** One owner tick for one viewer: delayed actions, refresh and the opening NPC's presence. */
    public void tick(P player) {
        Viewer viewer = viewers.get(host.id(player));
        if (viewer == null || viewer.closed) return;
        viewer.player = player;
        viewer.tick++;
        if (viewer.npc.isPresent() && !npcActive.test(viewer.npc.get())) { viewer.close(); return; }
        Iterator<Pending> due = viewer.pending.iterator();
        List<Runnable> run = new ArrayList<>();
        while (due.hasNext()) { Pending task = due.next(); if (task.at() <= viewer.tick) { run.add(task.work()); due.remove(); } }
        for (Runnable work : run) {
            if (viewer.closed) return;
            try { work.run(); }
            catch (RuntimeException failure) { warn.accept("Delayed screen action failed for " + viewer.id + ": " + failure); }
        }
        if (viewer.closed) return;
        boolean refresh;
        try { refresh = viewer.screen.tick(viewer.tick); }
        catch (RuntimeException failure) { warn.accept("Screen tick failed for " + viewer.id + ": " + failure); viewer.close(); return; }
        if (refresh) viewer.render(false);
    }

    /** Shutdown: forgets every window. The caller closes the platform windows on their owners. */
    public List<UUID> clear() {
        List<UUID> open = List.copyOf(viewers.keySet());
        for (Viewer viewer : List.copyOf(viewers.values())) forget(viewer);
        return open;
    }

    private void forget(Viewer viewer) {
        viewer.closed = true;
        viewer.pending.clear();
        viewers.remove(viewer.id, viewer);
    }
}
