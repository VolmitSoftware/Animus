package art.arcane.animus.core.runtime;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.DoorPassage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Doors a recording playback opened. A recorded {@code use} opens a closed door through the platform's protection
 * check, or closes one this playback opened; a door it did not open is never closed, and a refused door is ignored.
 * Owner thread only; at most {@value #MAXIMUM_HELD} doors are held, the oldest closing first.
 */
public final class RecordingDoors {
    static final int MAXIMUM_HELD = 16;
    private final UUID npc;
    private final Set<BlockPos> held = new LinkedHashSet<>();
    private String world;

    public RecordingDoors(UUID npc) { this.npc = Objects.requireNonNull(npc); }

    public void use(String inWorld, BlockPos at, DoorPassage.Doors doors) {
        if (world != null && !world.equals(inWorld)) closeAll(doors);
        world = inWorld;
        if (held.remove(at)) { close(at, doors); return; }
        boolean opened;
        try { opened = doors.open(npc, inWorld, at); }
        catch (RuntimeException failure) { opened = false; }
        if (!opened) return;
        if (held.size() >= MAXIMUM_HELD) { BlockPos oldest = held.iterator().next(); held.remove(oldest); close(oldest, doors); }
        held.add(at);
    }

    /** The playback stopped or ended: every door it holds open closes. */
    public void closeAll(DoorPassage.Doors doors) {
        List<BlockPos> all = new ArrayList<>(held);
        held.clear();
        for (BlockPos door : all) close(door, doors);
    }

    private void close(BlockPos at, DoorPassage.Doors doors) {
        try { doors.close(world, at); }
        catch (RuntimeException ignored) { /* The platform reports its own failures; a door it cannot close stays as it is. */ }
    }
}
