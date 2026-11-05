package art.arcane.animus.core.gui;

import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.presentation.DisplayTransport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A display transport that keeps what each viewer would see, for tests. */
final class RecordingDisplays<P> implements DisplayTransport<P> {
    final Map<P, Map<Integer, DisplayEntity>> seen = new HashMap<>();
    final Map<P, Consumer<Click>> clicks = new HashMap<>();
    final List<String> log = new ArrayList<>();
    private int next = 1000;

    @Override public String unavailable() { return null; }
    @Override public int reserve() { return next++; }
    @Override public void watch(P viewer, Consumer<Click> consumer) { clicks.put(viewer, consumer); }
    @Override public void spawn(P viewer, List<DisplayEntity> entities) {
        for (DisplayEntity entity : entities) { seen.computeIfAbsent(viewer, (P ignored) -> new LinkedHashMap<>()).put(entity.id(), entity); log.add("spawn " + entity.id()); }
    }
    @Override public void update(P viewer, List<DisplayEntity> entities) {
        for (DisplayEntity entity : entities) {
            if (!seen.getOrDefault(viewer, Map.of()).containsKey(entity.id())) throw new AssertionError("update of an entity never spawned: " + entity.id());
            seen.get(viewer).put(entity.id(), entity);
            log.add("update " + entity.id());
        }
    }
    @Override public void remove(P viewer, int[] ids) {
        for (int id : ids) { if (seen.containsKey(viewer)) seen.get(viewer).remove(id); log.add("remove " + id); }
    }
    @Override public void forget(P viewer) { seen.remove(viewer); clicks.remove(viewer); }
    @Override public void close() {}

    List<DisplayEntity> of(P viewer) { return List.copyOf(seen.getOrDefault(viewer, Map.of()).values()); }

    /** The plain text of every text display the viewer sees. */
    List<String> texts(P viewer) {
        return of(viewer).stream().filter((DisplayEntity entity) -> entity.content() instanceof DisplayEntity.Text)
                .map((DisplayEntity entity) -> GuiText.plain(((DisplayEntity.Text) entity.content()).text())).toList();
    }

    List<DisplayEntity> hitboxes(P viewer) { return of(viewer).stream().filter((DisplayEntity entity) -> entity.content() instanceof DisplayEntity.Hitbox).toList(); }

    void click(P viewer, int id, boolean attack) { clicks.get(viewer).accept(new Click(id, attack, false, System.nanoTime())); }
}
