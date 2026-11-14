package art.arcane.animus.core.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import art.arcane.animus.core.virtual.SpatialNpcIndex;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Which NPCs in range one viewer is shown when more are in range than {@code [npcs] maximumVisible} allows: the nearest.
 * A shown NPC, or one the last ranking let in that is still respawning, keeps its place until a left-out one is
 * {@link #MARGIN} blocks nearer, so NPCs at the edge do not swap in and out while the viewer shuffles about. Ranking is
 * not redone every update: on the first one, after a teleport of {@link #FAR} blocks, a move of {@link #RECHECK_DISTANCE}
 * blocks at most every {@link #MOVE_TURNS} updates, when the cap changes, when a shown NPC leaves while others wait, when
 * the bounded scan reaches the last free place, and every {@link #RECHECK_TURNS} updates while NPCs are left out, wait
 * for their chunk to reach the viewer, or the viewer is at the cap. One per viewer, on the thread that updates it. The
 * Paper and Folia presenters both rank and spawn through {@link #rank(long, Area, View, int, int)} and {@link #fill}.
 */
public final class NearestSelection {
    public static final double MARGIN = 4, RECHECK_DISTANCE = 2, FAR = 16;
    public static final int RECHECK_TURNS = 10, MOVE_TURNS = 4;
    /** A ranking reads every NPC in range; the candidate bound only matters past thousands of NPCs within view distance. */
    private static final SpatialNpcIndex.Budget RANKING = new SpatialNpcIndex.Budget(65536, 4096);

    /** An NPC in range the viewer may see: its distance, whether the viewer is shown it now, and the native IDs its body takes. */
    record Candidate(UUID id, double distance, boolean shown, IntSupplier ids) {}

    /** Where one update looks: the NPC index, the viewer's spot and view distance, and the chunks that reached the viewer. */
    public record Area(SpatialNpcIndex index, WorldPosition at, double viewDistance, Predicate<ChunkPosition> sent) {
        SpatialNpcIndex.Query query() { return new SpatialNpcIndex.Query(at, viewDistance); }
    }

    /** One presenter's NPCs as its viewer sees them; {@code T} is the platform's live NPC, read once per lookup. */
    public interface View<T> {
        /** The live NPC with this UUID, or null. */
        T npc(UUID id);
        /** Whether the viewer may be shown it, counting a chunk as there when {@code chunks} says so. */
        boolean eligible(T npc, Predicate<ChunkPosition> chunks);
        WorldPosition position(T npc);
        /** The native IDs its body takes once shown. */
        int ids(T npc);
        boolean showing(UUID id);
        /** The native IDs of the body the viewer is shown now. */
        int shownIds(UUID id);
        int shownCount();
        /** A body of this NPC is still leaving the viewer. */
        boolean retiring(UUID id);
        /** Whether {@code ids} more native IDs fit beside the viewer's bodies and previews. */
        boolean fits(int ids);
        /** Whether the presentation budget lets one more spawn go now. */
        boolean admit();
        void spawn(T npc);
        /** Whether the viewer's session is still the one this update began in. */
        boolean current();
    }

    private final Comparator<Candidate> nearer = Comparator.comparingDouble(this::ranked).thenComparing(Candidate::id);
    private final Deque<UUID> entering = new ArrayDeque<>();
    /** The NPCs the last ranking kept or let in. */
    private Set<UUID> held = Set.of();
    private WorldPosition rankedAt;
    private long rankedTurn;
    private int rankedMaximum, kept;
    private boolean crowded, stale, moving;
    /** Where the bounded scan resumes on the next update. */
    private SpatialNpcIndex.Cursor cursor;

    /** Whether to rank again on this update, for a viewer at {@code at} shown {@code shown} NPCs under a cap of {@code maximum}. */
    public boolean due(long turn, WorldPosition at, int shown, int maximum) {
        if (rankedAt == null || stale || maximum != rankedMaximum || !rankedAt.world().equals(at.world())) return true;
        double moved = rankedAt.point().distance(at.point());
        if (moved >= FAR) return true;
        // A flyer moves two blocks almost every update: it is ranked every few, and the scan waits for that ranking.
        moving = moved >= RECHECK_DISTANCE;
        if (moving && turn - rankedTurn >= MOVE_TURNS) return true;
        // A shown NPC left (out of range, despawned or respawning) while others wait: its place goes to the next nearest now.
        if (crowded && entering.isEmpty() && shown < kept) return true;
        return (crowded || shown >= maximum) && turn - rankedTurn >= RECHECK_TURNS;
    }

    /**
     * Ranks every NPC in range the viewer may see, as {@link #rank(long, WorldPosition, List, int, int, int)} does; one
     * whose chunk has not reached the viewer yet is counted, not ranked. Returns the shown NPCs that lose their place.
     */
    public <T> List<UUID> rank(long turn, Area area, View<T> view, int maximum, int idBudget) {
        WorldPosition at = area.at();
        Predicate<ChunkPosition> anyChunk = (ChunkPosition chunk) -> chunk.world().equals(at.world());
        List<VirtualNpcState.Snapshot> inRange = area.index().scan(area.query(), null, RANKING, anyChunk).candidates();
        List<Candidate> candidates = new ArrayList<>(inRange.size());
        int waiting = 0;
        for (VirtualNpcState.Snapshot snapshot : inRange) {
            UUID id = snapshot.npcId();
            T npc = view.npc(id);
            if (npc == null || !view.eligible(npc, anyChunk)) continue;
            WorldPosition position = view.position(npc);
            if (!area.sent().test(position.chunk())) { waiting++; continue; }
            boolean shown = view.showing(id);
            candidates.add(new Candidate(id, position.point().distance(at.point()), shown, shown ? () -> view.shownIds(id) : () -> view.ids(npc)));
        }
        return rank(turn, at, candidates, maximum, idBudget, waiting);
    }

    /**
     * Spawns up to {@code capacity} NPCs: those the last ranking let in, nearest first, then, while everything in range
     * fits, those a bounded scan finds that arrived since, resuming where the last update's scan stopped. The nearest
     * waiting one holds the rest back while its old body is still leaving or its IDs do not fit. Returns how many it spawned.
     */
    public <T> int fill(Area area, View<T> view, int capacity, int maximum) {
        int spawned = 0;
        for (UUID next = peek(); next != null && capacity > 0; next = peek()) {
            T npc = view.npc(next);
            if (npc == null || view.showing(next) || !view.eligible(npc, area.sent())) { poll(); continue; }
            if (view.retiring(next) || !view.fits(view.ids(npc)) || !view.admit()) return spawned;
            poll(); view.spawn(npc); spawned++; capacity--;
            if (!view.current()) return spawned;
        }
        if (capacity == 0 || peek() != null || !scanning()) return spawned;
        SpatialNpcIndex.Scan scan = area.index().scan(area.query(), cursor, new SpatialNpcIndex.Budget(32, Math.max(1, capacity * 2)), area.sent());
        cursor = scan.next().orElse(null);
        for (VirtualNpcState.Snapshot snapshot : scan.candidates()) {
            if (capacity == 0) break;
            UUID id = snapshot.npcId();
            T npc = view.npc(id);
            if (npc == null || view.showing(id) || view.retiring(id) || !view.fits(view.ids(npc)) || !view.eligible(npc, area.sent())) continue;
            if (!scans(view.shownCount(), maximum) || !view.admit()) break;
            view.spawn(npc); spawned++; capacity--;
            if (!view.current()) break;
        }
        return spawned;
    }

    /**
     * Ranks the candidates nearest first, one shown or {@link #held} counted {@link #MARGIN} nearer, and keeps the first
     * {@code maximum} whose IDs fit {@code idBudget}. {@code waiting} counts the NPCs in range the viewer may see once
     * their chunk reaches them; while they and the candidates are more than {@code maximum}, the selection stays crowded,
     * so the places fill through rankings, nearest first, as chunks arrive. Returns the shown NPCs that lose their place;
     * the left-out ones that gain one wait in {@link #poll} order, nearest first. While more are shown than
     * {@code maximum}, after the cap was lowered, nothing changes: a lowered cap only holds back new NPCs. Costs a heap of
     * {@code maximum} over the candidates, at the cadence {@link #due} sets.
     */
    List<UUID> rank(long turn, WorldPosition at, List<Candidate> candidates, int maximum, int idBudget, int waiting) {
        rankedAt = at; rankedTurn = turn; rankedMaximum = maximum; entering.clear();
        stale = false; moving = false;
        int shown = 0;
        for (Candidate candidate : candidates) if (candidate.shown()) shown++;
        if (shown > maximum) { crowded = candidates.size() + waiting > maximum; kept = shown; return List.of(); }
        PriorityQueue<Candidate> farthestFirst = new PriorityQueue<>(maximum + 1, nearer.reversed());
        for (Candidate candidate : candidates) {
            farthestFirst.add(candidate);
            if (farthestFirst.size() > maximum) farthestFirst.poll();
        }
        List<Candidate> order = new ArrayList<>(farthestFirst);
        order.sort(nearer);
        Set<UUID> selected = new HashSet<>();
        int ids = 0;
        for (Candidate candidate : order) {
            ids += candidate.ids().getAsInt();
            if (ids > idBudget) break;
            selected.add(candidate.id());
            if (!candidate.shown()) entering.addLast(candidate.id());
        }
        held = selected;
        kept = selected.size();
        crowded = kept < candidates.size() || candidates.size() + waiting > maximum;
        List<UUID> leaving = new ArrayList<>();
        for (Candidate candidate : candidates) if (candidate.shown() && !selected.contains(candidate.id())) leaving.add(candidate.id());
        return leaving;
    }

    /**
     * Whether the bounded scan, which finds NPCs that arrive between rankings in scan order, may show one more to a viewer
     * shown {@code shown} under a cap of {@code maximum}. Not while the selection is crowded or a ranking for a new spot
     * waits, and never the last free place: that one is left to the ranking this marks due, so a crowd that arrives at
     * once fills nearest first.
     */
    boolean scans(int shown, int maximum) {
        if (!scanning()) return false;
        if (shown + 1 < maximum) return true;
        stale = true;
        return false;
    }

    /**
     * Whether the bounded scan runs at all on this update: not while the last ranking left NPCs out, by the cap or the
     * native ID budget, or more wait for their chunk than places are free; see {@link #scans}.
     */
    boolean scanning() { return !crowded && !moving && !stale; }

    /** The nearest NPC still waiting to be shown, or null. */
    UUID peek() { return entering.peekFirst(); }
    UUID poll() { return entering.pollFirst(); }

    private double ranked(Candidate candidate) { return candidate.shown() || held.contains(candidate.id()) ? candidate.distance() - MARGIN : candidate.distance(); }
}
