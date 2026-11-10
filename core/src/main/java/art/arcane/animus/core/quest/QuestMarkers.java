package art.arcane.animus.core.quest;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A floating {@code !} over each NPC with a quest to offer the viewer and a {@code ?} over each NPC the viewer should
 * talk to, bring items to or turn a quest in at. Drawn per viewer with the hologram display transport, on the
 * viewer's owner, for NPCs within {@link #RANGE} blocks.
 */
final class QuestMarkers<P> {
    static final double RANGE = 48, LIFT = 0.75;
    static final String OFFER = "&e&l!", TASK = "&6&l?";
    private record Shown(int id, UUID uuid, String text, double x, double y, double z) {}
    private static final DisplayEntity.Style STYLE = new DisplayEntity.Style(DisplayEntity.Billboard.CENTER, 1.6f, 1.6f, 1.6f, 0, 0, 0, -1, 1, 0, 0, -1, 3);

    private final QuestService<P, ?> service;
    private final DisplayTransport<P> transport;
    private final ScreenHost<P, ?> host;
    private final java.util.function.Consumer<P> watch;
    private final Map<UUID, Map<UUID, Shown>> viewers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** {@code watch} makes the transport send this viewer's displays, which the hologram service owns. */
    QuestMarkers(QuestService<P, ?> service, DisplayTransport<P> transport, ScreenHost<P, ?> host, java.util.function.Consumer<P> watch) {
        this.service = service; this.transport = transport; this.host = host; this.watch = watch;
    }

    void tick(P player) {
        if (closed || transport.unavailable() != null) return;
        UUID viewer = host.id(player);
        Optional<ScreenHost.Pose> pose = host.pose(player);
        Map<UUID, String> wanted = pose.isEmpty() ? Map.of() : wanted(player, pose.get());
        Map<UUID, Shown> shown = viewers.computeIfAbsent(viewer, (UUID ignored) -> new HashMap<>());
        if (!wanted.isEmpty()) watch.accept(player);
        List<DisplayEntity> spawn = new ArrayList<>(), update = new ArrayList<>();
        List<Integer> remove = new ArrayList<>();
        for (Map.Entry<UUID, Shown> entry : List.copyOf(shown.entrySet()))
            if (!wanted.containsKey(entry.getKey())) { remove.add(entry.getValue().id()); shown.remove(entry.getKey()); }
        for (Map.Entry<UUID, String> entry : wanted.entrySet()) {
            Optional<VirtualNpcState.Snapshot> npc = service.living().state(entry.getKey());
            if (npc.isEmpty()) continue;
            double x = npc.get().position().point().x(), y = npc.get().bounds().max().y() + LIFT, z = npc.get().position().point().z();
            Shown before = shown.get(entry.getKey());
            if (before == null) {
                Shown next = new Shown(transport.reserve(), UUID.randomUUID(), entry.getValue(), x, y, z);
                shown.put(entry.getKey(), next);
                spawn.add(entity(next));
            } else if (!before.text().equals(entry.getValue()) || Math.abs(before.x() - x) + Math.abs(before.y() - y) + Math.abs(before.z() - z) > 0.05) {
                Shown next = new Shown(before.id(), before.uuid(), entry.getValue(), x, y, z);
                shown.put(entry.getKey(), next);
                update.add(entity(next));
            }
        }
        if (!remove.isEmpty()) transport.remove(player, remove.stream().mapToInt(Integer::intValue).toArray());
        if (!spawn.isEmpty()) transport.spawn(player, spawn);
        if (!update.isEmpty()) transport.update(player, update);
    }

    /** The marker each NPC near the viewer should carry: a task beats an offer. */
    private Map<UUID, String> wanted(P player, ScreenHost.Pose pose) {
        Map<UUID, String> wanted = new HashMap<>();
        QuestCatalog catalog = service.catalog();
        UUID viewer = host.id(player);
        QuestRecord record = service.store().record(viewer);
        for (Map.Entry<String, QuestRecord.Active> entry : record.active().entrySet()) {
            Optional<QuestDocument> quest = catalog.quest(entry.getKey());
            if (quest.isEmpty()) continue;
            QuestRecord.Active active = entry.getValue();
            if (active.ready()) { quest.get().turnIn().flatMap(service::npc).ifPresent((UUID npc) -> near(wanted, npc, TASK, pose)); continue; }
            Optional<QuestDocument.Stage> stage = QuestRules.stage(quest.get(), active);
            if (stage.isEmpty()) continue;
            for (int index : QuestRules.open(stage.get(), active)) {
                QuestDocument.Objective objective = stage.get().objectives().get(index);
                if (objective.type() == QuestDocument.Type.TALK || objective.type() == QuestDocument.Type.BRING)
                    objective.npc().flatMap(service::npc).ifPresent((UUID npc) -> near(wanted, npc, TASK, pose));
            }
        }
        if (!host.permitted(player, QuestPlay.USE)) return wanted;
        for (Map.Entry<String, List<String>> entry : catalog.byNpc().entrySet()) {
            Optional<UUID> npc = service.npc(entry.getKey());
            if (npc.isEmpty() || wanted.containsKey(npc.get()) || !near(npc.get(), pose)) continue;
            for (QuestDocument quest : catalog.offeredBy(entry.getKey())) {
                if (record.active(quest.id()).isPresent() || service.play().refusal(player, quest).isPresent()) continue;
                wanted.put(npc.get(), OFFER);
                break;
            }
        }
        return wanted;
    }

    private void near(Map<UUID, String> wanted, UUID npc, String text, ScreenHost.Pose pose) {
        if (!wanted.containsKey(npc) && near(npc, pose)) wanted.put(npc, text);
    }

    private boolean near(UUID npc, ScreenHost.Pose pose) {
        Optional<VirtualNpcState.Snapshot> state = service.living().state(npc);
        if (state.isEmpty() || !pose.in(state.get().position().world())) return false;
        double dx = state.get().position().point().x() - pose.x(), dz = state.get().position().point().z() - pose.z();
        return dx * dx + dz * dz <= RANGE * RANGE;
    }

    private static DisplayEntity entity(Shown shown) {
        return new DisplayEntity(shown.id(), shown.uuid(), DisplayEntity.Text.of(RichText.colorize(shown.text())), shown.x(), shown.y(), shown.z(), 0, 0, STYLE);
    }

    /** The client dropped every display, after a respawn or a world change; draw again on the next tick. */
    void reset(P player) { viewers.remove(host.id(player)); }

    void quit(P player) { viewers.remove(host.id(player)); }

    void close() {
        closed = true;
        viewers.clear();
    }
}
