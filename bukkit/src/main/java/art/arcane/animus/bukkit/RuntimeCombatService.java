package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.combat.*;
import art.arcane.animus.bukkit.runtime.PersistedNpcService;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.combat.AttackProfile;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicit administrator control of real combat. Preview services never invoke this bridge. */
final class RuntimeCombatService implements AutoCloseable {
    private record Registration(String id, VirtualNpcState state, BukkitCombatBridge.Combatant combatant) {}
    private final JavaPlugin plugin;
    private final AnimusRuntime runtime;
    private final PersistedNpcService living;
    private final BukkitCombatBridge bridge;
    private final Map<UUID, Registration> registrations = new HashMap<>();
    private final Map<UUID, BukkitCombatBridge.MobLease> leases = new HashMap<>();

    RuntimeCombatService(JavaPlugin plugin, AnimusRuntime runtime, PersistedNpcService living, String version, boolean folia) {
        this.plugin = plugin; this.runtime = runtime; this.living = living;
        bridge = new BukkitCombatBridge(plugin, version, folia, CombatLimits.DEFAULT,
                (action, npc, entity) -> !worldGuardPresent());
    }

    String status() {
        var capability = bridge.capabilities();
        if (!bridge.available()) return "Combat unavailable: " + capability.unavailableReason();
        if (worldGuardPresent()) return "Combat unavailable: WorldGuard is installed; its virtual-actor protection adapter is not implemented yet";
        prune();
        return "Combat ready | registered=" + bridge.combatantCount() + " | controlled-mobs=" + bridge.controlledMobCount()
                + " | mob-types=" + String.join(",", capability.controlledMobTypes())
                + (capability.mobControlUnavailableReason() == null ? "" : " | " + capability.mobControlUnavailableReason());
    }

    String execute(String[] args) {
        if (args.length > 0 && args[0].equals("combat") && (args.length == 1 || args.length == 2 && args[1].equals("status"))) return status();
        if (!bridge.available()) throw new IllegalStateException(status());
        prune();
        if (args.length == 3 && args[0].equals("target") && args[2].equals("stop")) {
            int released = 0;
            for (Registration registration : List.copyOf(registrations.values())) if (registration.id().equals(args[1])) {
                for (var entry : List.copyOf(leases.entrySet())) if (entry.getValue().npcId().equals(registration.combatant().npcId())) {
                    BukkitCombatBridge.MobLease previous = entry.getValue();
                    previous.close(); leases.remove(entry.getKey(), previous); released++;
                }
            }
            return "Released " + released + " mob control leases for " + args[1];
        }
        if (worldGuardPresent()) throw new IllegalStateException(status());
        if (args.length < 3 || args.length > 4 || args[0].equals("target") && args.length != 3)
            throw new IllegalArgumentException("attack <npc-id> <target-uuid> [damage] | target <npc-id> <zombie-uuid|stop> | combat status");
        UUID targetId;
        try { targetId = UUID.fromString(args[2]); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Target must be an existing real entity UUID"); }
        Entity entity = plugin.getServer().getEntity(targetId);
        if (!(entity instanceof LivingEntity target)) throw new IllegalArgumentException("Target must be a loaded living entity");
        if (args[0].equals("attack")) {
            double amount = args.length == 4 ? Double.parseDouble(args[3]) : AttackProfile.DEFAULT.damage();
            AttackProfile attack = new AttackProfile(amount, AttackProfile.DEFAULT.reach(), AttackProfile.DEFAULT.cooldownTicks());
            Registration npc = registration(args[1]);
            var result = npc.combatant().attack(target, attack);
            return "Attack " + args[1] + ": " + result.status() + " applied=" + result.appliedDamage()
                    + " health-before=" + result.healthBefore() + " health-after=" + result.healthAfter();
        }
        if (!args[0].equals("target") || !(target instanceof Mob mob)) throw new IllegalArgumentException("Target control requires an existing supported mob");
        Registration npc = registration(args[1]);
        BukkitCombatBridge.MobLease lease = npc.combatant().control(mob, MobProfile.DEFAULT);
        leases.put(targetId, lease);
        return "Mob control started npc=" + args[1] + " mob=" + targetId + " lifetime-ticks=" + MobProfile.DEFAULT.lifetimeTicks();
    }

    private Registration registration(String id) {
        runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        CombatPolicy policy = new CombatPolicy(id);
        if (!policy.allowed()) throw new IllegalStateException("NPC combat is disabled or its migration review has not been accepted");
        var binding = living.combat(id).orElseThrow(() -> new IllegalStateException("NPC must have an active runtime before combat"));
        UUID uuid = binding.state().snapshot().npcId();
        Registration previous = registrations.get(uuid);
        if (previous != null && previous.state() == binding.state() && previous.combatant().active()) return previous;
        if (previous != null) previous.combatant().close();
        Registration next = new Registration(id, binding.state(), bridge.register(binding.state(),
                () -> binding.current().getAsBoolean() && policy.allowed() && !worldGuardPresent(), binding.changed()));
        registrations.put(uuid, next);
        return next;
    }

    private static boolean combatAllowed(NpcDefinition definition) {
        if (definition == null) return false;
        JsonObject document = definition.json();
        if (!document.has("modules") || !document.getAsJsonObject("modules").has("animus:combat")) return true;
        JsonElement value = document.getAsJsonObject("modules").get("animus:combat");
        return value.isJsonObject() && enabled(value.getAsJsonObject(), "enabled") && enabled(value.getAsJsonObject(), "reviewAccepted");
    }

    /** Immutable publication identity avoids copying module JSON on every combat tick. */
    private final class CombatPolicy {
        private final String id;
        private NpcDefinition observed;
        private boolean accepted;
        private CombatPolicy(String id) { this.id = id; }
        private boolean allowed() {
            NpcDefinition current = runtime.npcs().find(id).orElse(null);
            if (current != observed) { accepted = combatAllowed(current); observed = current; }
            return current != null && accepted;
        }
    }

    private static boolean enabled(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    private boolean worldGuardPresent() { return plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard"); }
    private void prune() {
        for (var entry : List.copyOf(registrations.entrySet())) if (!entry.getValue().combatant().active()) {
            Registration previous = entry.getValue();
            previous.combatant().close(); registrations.remove(entry.getKey(), previous);
        }
        leases.values().removeIf(lease -> !lease.active());
    }
    @Override public void close() {
        bridge.close(); registrations.clear(); leases.clear();
    }
}
