package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.bukkit.event.VirtualNpcDamageEvent;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.event.VirtualNpcDespawnEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.event.VirtualNpcSpawnEvent;
import art.arcane.animus.core.integration.MythicBinding;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Runs an NPC's Mythic mob skills on the matching NPC events, with the NPC as caster: {@code ~onSpawn} when it becomes
 * active, {@code ~onInteract} on a right-click, {@code ~onDamaged} on a left-click, {@code ~onAttack} when it hits an
 * entity, {@code ~onDeath} when its health reaches zero, and {@code ~onTimer:<ticks>} while it is active.
 */
final class MobSkills implements Listener {
    private final MythicBridge mythic;
    private final MythicHost host;
    private final Consumer<String> log;
    /**
     * Every active NPC, bound or not. Each tick walks the NPCs bound now and runs the timers of those active, so an
     * edit that adds, changes or removes a binding starts, changes or stops timer skills without a respawn.
     */
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private long tick;

    MobSkills(MythicBridge mythic, MythicHost host, Consumer<String> log) { this.mythic = mythic; this.host = host; this.log = log; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interacted(VirtualNpcInteractionEvent event) {
        if (event.isPreview()) return;
        MythicBridge.Trigger trigger = event.getAction() == VirtualNpcInteractionEvent.Action.INTERACT ? MythicBridge.Trigger.INTERACT : MythicBridge.Trigger.DAMAGED;
        run(event.getNpcId(), trigger, Optional.of(event.getViewer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void spawned(VirtualNpcSpawnEvent event) {
        active.add(event.getNpcId());
        Optional<MythicHost.Npc> npc = bound(event.getNpcId());
        if (npc.isEmpty()) return;
        Optional<Location> where = host.location(event.getPosition());
        where.ifPresent((Location at) -> host.at(at, () -> fire(npc.get(), MythicBridge.Trigger.SPAWN, at, Optional.empty())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void despawned(VirtualNpcDespawnEvent event) { active.remove(event.getNpcId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attacked(VirtualNpcDamageEvent event) { run(event.getNpcId(), MythicBridge.Trigger.ATTACK, Optional.of(event.getTarget())); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(VirtualNpcDeathEvent event) {
        active.remove(event.getNpcId());
        Optional<Location> where = host.location(event.getPosition());
        Optional<MythicHost.Npc> npc = bound(event.getNpcId());
        if (npc.isEmpty() || where.isEmpty()) return;
        host.at(where.get(), () -> {
            Optional<Entity> killer = event.getKiller().flatMap(host::entity);
            fire(npc.get(), MythicBridge.Trigger.DEATH, where.get(), killer);
        });
    }

    /** One server tick: runs the timer skills that are due. Call from one owner every tick. */
    void tick() {
        long now = ++tick;
        for (UUID id : host.bound()) {
            if (!active.contains(id)) continue;
            Optional<MythicHost.Npc> npc = bound(id).filter((MythicHost.Npc found) -> found.location().isPresent());
            if (npc.isEmpty()) continue;
            String mob = npc.get().binding().orElseThrow().mob();
            if (!mythic.hasTimers(mob)) continue;
            MythicBridge.Caster caster = MythicBridge.Caster.of(npc.get());
            Location where = npc.get().location().get();
            host.at(where, () -> guarded(() -> mythic.runTimers(mob, caster, where, now)));
        }
    }

    private void run(UUID npcId, MythicBridge.Trigger trigger, Optional<Entity> cause) {
        Optional<MythicHost.Npc> npc = bound(npcId).filter((MythicHost.Npc found) -> found.location().isPresent());
        if (npc.isEmpty()) return;
        Location where = npc.get().location().get();
        host.at(where, () -> fire(npc.get(), trigger, where, cause));
    }

    private void fire(MythicHost.Npc npc, MythicBridge.Trigger trigger, Location where, Optional<Entity> cause) {
        String mob = npc.binding().orElseThrow().mob();
        guarded(() -> mythic.runMobSkills(mob, trigger, MythicBridge.Caster.of(npc), where, cause));
    }

    /** The NPC when its definition binds an enabled Mythic mob. */
    private Optional<MythicHost.Npc> bound(UUID npcId) {
        return host.npc(npcId).filter((MythicHost.Npc npc) -> npc.binding().map(MythicBinding::enabled).orElse(false));
    }

    private void guarded(Runnable skills) {
        try { skills.run(); }
        catch (RuntimeException | LinkageError failure) { log.accept("A Mythic skill failed: " + failure); }
    }
}
