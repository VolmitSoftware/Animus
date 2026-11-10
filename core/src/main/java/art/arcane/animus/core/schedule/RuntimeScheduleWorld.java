package art.arcane.animus.core.schedule;

import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Schedules on a persisted NPC runtime; the platform adds the world clock, its items and the console. */
public final class RuntimeScheduleWorld<I> implements ScheduleWorld {
    /** The platform's part, called on the NPC's owner. */
    public interface Host<I> {
        Optional<Clock> clock(String world);
        /** The platform item for a definition item, or the empty stack for none. */
        I item(Optional<NpcItem> item);
        void command(String command);
    }

    private final NpcRuntime<?, I> living;
    private final Host<I> host;

    public RuntimeScheduleWorld(NpcRuntime<?, I> living, Host<I> host) { this.living = Objects.requireNonNull(living); this.host = Objects.requireNonNull(host); }

    @Override public boolean onOwner(UUID npc, Runnable work, Runnable refused) { return living.onOwner(npc, work, (String reason) -> refused.run()); }
    @Override public Optional<WorldPosition> position(UUID npc) {
        return living.active(npc).map((NpcRuntime.ActiveNpc active) -> active.state().position());
    }
    @Override public Optional<Clock> clock(String world) { return host.clock(world); }
    @Override public void walk(String id, WorldPosition goal, double speed) { living.navigate(id, goal, MovementProfile.Mode.WALK, speed); }
    @Override public CompletableFuture<Void> arrival(String id) { return living.awaitRoute(id); }
    @Override public void look(String id, WorldPosition target) { living.lookAt(id, target); }
    @Override public void pose(String id, NpcPose pose) { living.rest(id, pose); }
    @Override public void equip(String id, EquipmentSlot slot, Optional<NpcItem> item) { living.setEquipment(id, slot, host.item(item)); }
    @Override public void skin(String id, String skin) { living.setAppearance(id, Optional.empty(), Optional.of(skin), false); }
    @Override public void command(String command) { host.command(command); }
}
