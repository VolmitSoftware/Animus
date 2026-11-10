package art.arcane.animus.bukkit.integration.mythic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Everything Animus asks of MythicMobs, in Bukkit and Animus terms only, so nothing outside {@link MythicApiBridge}
 * links against Mythic classes. Calls that run skills must be made on the thread that owns {@code origin}.
 */
interface MythicBridge {
    /** The mob events an NPC passes on to its Mythic mob's skills. */
    enum Trigger { SPAWN, INTERACT, DAMAGED, ATTACK, DEATH }

    /** An Animus NPC acting as a Mythic caster: its UUID keeps its own cooldowns, its name fills {@code <caster.name>}. */
    record Caster(UUID npc, String id, String name) {
        static Caster of(MythicHost.Npc npc) { return new Caster(npc.uuid(), npc.id(), npc.name()); }
    }

    Optional<MobSource> mob(String type);

    List<String> mobTypes();

    /** A Mythic item, else a vanilla material, by name. */
    Optional<ItemStack> item(String name);

    boolean skillExists(String skill);

    /** Casts a skill from the NPC's position, with {@code trigger} as the skill's trigger and target. */
    boolean castFromNpc(String skill, Caster caster, Location origin, Optional<Entity> trigger, float power);

    /** Casts a skill with the player as caster, as {@code /mm test cast} does. */
    boolean castAsPlayer(String skill, Player player, float power);

    /** Runs the mob's skills for this event; false when the mob is unknown or has none. */
    boolean runMobSkills(String mob, Trigger trigger, Caster caster, Location origin, Optional<Entity> cause);

    /** Whether the mob has {@code ~onTimer} skills. */
    boolean hasTimers(String mob);

    /** Runs the mob's {@code ~onTimer:<ticks>} skills whose interval divides {@code tick}. */
    void runTimers(String mob, Caster caster, Location origin, long tick);
}
