package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.core.integration.MythicBinding;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** A MythicMobs stand-in that records what Animus asked of it, and a host over a fixed set of NPCs. */
class FakeMythic implements MythicBridge, MythicHost {
    final List<String> calls = new ArrayList<>();
    final Map<UUID, Npc> npcs = new HashMap<>();
    final Set<String> skills;
    final Set<String> timedMobs;
    boolean ownerRefuses;

    FakeMythic(Set<String> skills, Set<String> timedMobs) { this.skills = skills; this.timedMobs = timedMobs; }

    Npc add(String id, Optional<MythicBinding> binding, boolean active) {
        Npc npc = new Npc(UUID.nameUUIDFromBytes(id.getBytes()), id, id.toUpperCase(), binding, active ? Optional.of(new Location(null, 1, 64, 2)) : Optional.empty());
        npcs.put(npc.uuid(), npc);
        return npc;
    }

    static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "Steve";
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[Steve]";
            default -> null;
        });
    }

    private static String who(Optional<? extends Entity> entity) { return entity.map((Entity found) -> found.getUniqueId().toString().substring(0, 4)).orElse("none"); }

    @Override public Optional<MobSource> mob(String type) { return Optional.empty(); }
    @Override public List<String> mobTypes() { return List.of(); }
    @Override public Optional<ItemStack> item(String name) { return Optional.empty(); }
    @Override public boolean skillExists(String skill) { return skills.contains(skill); }
    @Override public boolean castFromNpc(String skill, Caster caster, Location origin, Optional<Entity> trigger, float power) {
        calls.add("npc-cast " + skill + " by " + caster.id() + " at " + origin.getBlockX() + "," + origin.getBlockY() + " for " + who(trigger) + " x" + power);
        return true;
    }
    @Override public boolean castAsPlayer(String skill, Player player, float power) { calls.add("player-cast " + skill + " by " + player.getName()); return true; }
    @Override public boolean runMobSkills(String mob, Trigger trigger, Caster caster, Location origin, Optional<Entity> cause) {
        calls.add(trigger + " " + mob + " by " + caster.id() + " for " + who(cause));
        return true;
    }
    @Override public boolean hasTimers(String mob) { return timedMobs.contains(mob); }
    @Override public void runTimers(String mob, Caster caster, Location origin, long tick) { calls.add("timer " + mob + " by " + caster.id() + " at tick " + tick); }

    @Override public Optional<Npc> npc(UUID npc) { return Optional.ofNullable(npcs.get(npc)); }
    @Override public Set<UUID> bound() {
        Set<UUID> bound = new java.util.LinkedHashSet<>();
        for (Npc npc : npcs.values()) if (npc.binding().map(MythicBinding::enabled).orElse(false)) bound.add(npc.uuid());
        return bound;
    }
    @Override public Optional<Npc> npc(String id) { return npcs.values().stream().filter((Npc npc) -> npc.id().equals(id)).findFirst(); }
    @Override public Optional<Entity> entity(UUID id) { return Optional.of(player(id)); }
    @Override public Optional<Location> location(art.arcane.animus.bukkit.api.NpcPosition position) { return Optional.of(new Location(null, position.x(), position.y(), position.z())); }
    @Override public boolean at(Location location, Runnable work) { if (ownerRefuses) return false; work.run(); return true; }
    @Override public boolean player(UUID player, Consumer<Player> work) { work.accept(player(player)); return true; }
}
