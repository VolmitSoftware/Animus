package art.arcane.animus.bukkit.integration.mythic;

import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.conditions.ISkillMetaCondition;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicConditionLoadEvent;
import io.lumine.mythic.bukkit.events.MythicTargeterLoadEvent;
import io.lumine.mythic.core.skills.SkillCondition;
import io.lumine.mythic.core.skills.targeters.ILocationSelector;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * What Mythic skill files can say about Animus NPCs: the targeter {@code @AnimusNPC{npc=guard}} aims at where the NPC
 * stands now, and the condition {@code animusnpc{npc=guard}} holds when the skill's caster is that NPC, or any Animus
 * NPC without {@code npc}.
 */
final class MythicExtensions implements Listener {
    static final String TARGETER = "animusnpc", CONDITION = "animusnpc";
    private final MythicHost host;

    MythicExtensions(MythicHost host) { this.host = host; }

    @EventHandler
    public void targeter(MythicTargeterLoadEvent event) {
        if (event.getTargeterName().toLowerCase(Locale.ROOT).equals(TARGETER)) event.register(new NpcTargeter(event.getConfig(), host));
    }

    @EventHandler
    public void condition(MythicConditionLoadEvent event) {
        if (event.getConditionName().toLowerCase(Locale.ROOT).equals(CONDITION)) event.register(new CasterCondition(event.getConfig()));
    }

    /** The NPC's current position, or nothing while it is not spawned. */
    static final class NpcTargeter extends ILocationSelector {
        private final String npc;
        private final MythicHost host;

        NpcTargeter(MythicLineConfig config, MythicHost host) {
            super(MythicBukkit.inst().getSkillManager(), config);
            npc = config.getString(new String[]{"npc", "id", "n"}, "");
            this.host = host;
        }

        @Override public Collection<AbstractLocation> getLocations(SkillMetadata meta) {
            return host.npc(npc).flatMap(MythicHost.Npc::location).map((org.bukkit.Location at) -> List.of(BukkitAdapter.adapt(at))).orElse(List.of());
        }
    }

    /** Whether the skill's caster is the named Animus NPC. */
    static final class CasterCondition extends SkillCondition implements ISkillMetaCondition {
        private final String npc;

        CasterCondition(MythicLineConfig config) {
            super(config.getLine());
            npc = config.getString(new String[]{"npc", "id", "n"}, "");
        }

        @Override public boolean check(SkillMetadata meta) {
            if (!(meta.getCaster().getEntity() instanceof MythicApiBridge.NpcEntity caster)) return false;
            return npc.isEmpty() || caster.id().equals(npc);
        }
    }
}
