package art.arcane.animus.bukkit.integration.mythic;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.config.MythicConfig;
import io.lumine.mythic.api.mobs.GenericCaster;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.api.skills.Skill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillTrigger;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.adapters.VirtualEntity;
import io.lumine.mythic.core.skills.SkillMechanic;
import io.lumine.mythic.core.skills.SkillMetadataImpl;
import io.lumine.mythic.core.skills.SkillTriggers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@link MythicBridge} over the MythicMobs 5 API. Loaded only after the MythicMobs plugin is found enabled. */
final class MythicApiBridge implements MythicBridge {
    /** Options whose values the look reads. */
    private static final List<String> OPTIONS = List.of("Glowing", "Baby", "Adult", "Age");

    private final MythicBukkit mythic = MythicBukkit.inst();

    @Override public Optional<MobSource> mob(String type) {
        Optional<MythicMob> found = mythic.getMobManager().getMythicMob(type);
        if (found.isEmpty()) return Optional.empty();
        MythicMob mob = found.get();
        MythicConfig config = mob.getConfig();
        Map<String, String> options = new LinkedHashMap<>();
        for (String option : OPTIONS) if (config.isSet("Options." + option)) options.put(option, config.getString("Options." + option));
        return Optional.of(new MobSource(mob.getInternalName(), Optional.ofNullable(config.getString("Type")).orElse("ZOMBIE"),
                Optional.ofNullable(config.getString("Display")), config.getStringList("Equipment"), options,
                Optional.ofNullable(config.getString("Disguise")), config.getStringList("Skills")));
    }

    @Override public List<String> mobTypes() { return mythic.getMobManager().getMobNames().stream().sorted().toList(); }

    @Override public Optional<ItemStack> item(String name) {
        Optional<ItemStack> custom = mythic.getItemManager().getItemStack(name) instanceof ItemStack stack ? Optional.of(stack) : Optional.empty();
        if (custom.isPresent()) return custom;
        Material material = Material.matchMaterial(name);
        return material == null || !material.isItem() ? Optional.empty() : Optional.of(new ItemStack(material));
    }

    @Override public boolean skillExists(String skill) { return mythic.getSkillManager().getSkill(skill).isPresent(); }

    @Override public boolean castFromNpc(String skill, Caster caster, Location origin, Optional<Entity> trigger, float power) {
        Optional<Skill> found = mythic.getSkillManager().getSkill(skill);
        return found.isPresent() && found.get().executeIfUsable(metadata(SkillTriggers.API, caster, origin, trigger, power));
    }

    @Override public boolean castAsPlayer(String skill, Player player, float power) {
        return skillExists(skill) && mythic.getAPIHelper().castSkill(player, skill, power);
    }

    @Override public boolean runMobSkills(String mob, Trigger trigger, Caster caster, Location origin, Optional<Entity> cause) {
        Optional<MythicMob> found = mythic.getMobManager().getMythicMob(mob);
        SkillTrigger mythicTrigger = switch (trigger) {
            case SPAWN -> SkillTriggers.SPAWN;
            case INTERACT -> SkillTriggers.INTERACT;
            case DAMAGED -> SkillTriggers.DAMAGED;
            case ATTACK -> SkillTriggers.ATTACK;
            case DEATH -> SkillTriggers.DEATH;
        };
        if (found.isEmpty() || !found.get().hasSkills(mythicTrigger)) return false;
        for (SkillMechanic mechanic : found.get().getSkills(mythicTrigger))
            if (!MobLook.drawnByAnimus(mechanic.getClass().getName())) mechanic.execute(metadata(mythicTrigger, caster, origin, cause, 1f));
        return true;
    }

    @Override public boolean hasTimers(String mob) {
        return mythic.getMobManager().getMythicMob(mob).map((MythicMob found) -> !found.getTimerSkills().isEmpty()).orElse(false);
    }

    @Override public void runTimers(String mob, Caster caster, Location origin, long tick) {
        Optional<MythicMob> found = mythic.getMobManager().getMythicMob(mob);
        if (found.isEmpty()) return;
        List<SkillMechanic> due = new ArrayList<>();
        for (SkillMechanic mechanic : found.get().getTimerSkills())
            if (mechanic.getTimerInterval() > 0 && tick % mechanic.getTimerInterval() == 0 && !MobLook.drawnByAnimus(mechanic.getClass().getName())) due.add(mechanic);
        for (SkillMechanic mechanic : due) mechanic.execute(metadata(SkillTriggers.DEFAULT, caster, origin, Optional.empty(), 1f));
    }

    /** Skill state with the NPC as caster at {@code origin}, and {@code cause} as trigger and only target. */
    private static SkillMetadata metadata(SkillTrigger trigger, Caster caster, Location origin, Optional<Entity> cause, float power) {
        AbstractLocation at = BukkitAdapter.adapt(origin);
        Set<AbstractEntity> targets = new HashSet<>();
        cause.ifPresent((Entity entity) -> targets.add(BukkitAdapter.adapt(entity)));
        return new SkillMetadataImpl(trigger, new GenericCaster(new NpcEntity(caster, at)), cause.map(BukkitAdapter::adapt).orElse(null), at, targets, new HashSet<>(), power);
    }

    /** A Mythic caster standing where the NPC stands, with the NPC's UUID and name. */
    static final class NpcEntity extends VirtualEntity {
        private final Caster caster;
        NpcEntity(Caster caster, AbstractLocation at) { super(at); this.caster = caster; }
        @Override public UUID getUniqueId() { return caster.npc(); }
        @Override public String getName() { return caster.name(); }
        String id() { return caster.id(); }
    }
}
