package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import java.util.Optional;
import org.betonquest.betonquest.api.CountingObjective;
import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.identifier.NpcIdentifier;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.profile.Profile;
import org.betonquest.betonquest.api.quest.objective.Objective;
import org.betonquest.betonquest.api.quest.objective.ObjectiveFactory;
import org.betonquest.betonquest.api.quest.objective.service.ObjectiveService;
import org.betonquest.betonquest.api.service.npc.NpcRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** {@code animusnpckill <npc> [amount:<n>]}: the player kills the BetonQuest NPC, an Animus NPC, that many times. */
final class AnimusNpcKillObjective extends CountingObjective {
    static final String TYPE = "animusnpckill";
    private final Argument<NpcIdentifier> npc;
    private final NpcRegistry registry;
    private final QuestNpcHost host;

    private AnimusNpcKillObjective(ObjectiveService service, Argument<Number> amount, Argument<NpcIdentifier> npc, NpcRegistry registry, QuestNpcHost host) throws QuestException {
        super(service, amount, "objective.mobs_to_kill");
        this.npc = npc; this.registry = registry; this.host = host;
    }

    private void killed(VirtualNpcDeathEvent event, Profile profile) throws QuestException {
        Optional<NpcInfo> killed = host.npcs().byUuid(event.getNpcId());
        if (killed.isEmpty() || !registry.getIdentifier(new AnimusBqNpc(killed.get(), host), profile.getOnlineProfile().orElse(null)).contains(npc.getValue(profile))) return;
        getCountingData(profile).progress();
        completeIfDoneOrNotify(profile);
    }

    private static Player killer(VirtualNpcDeathEvent event) {
        if (!event.getKillerType().filter("player"::equals).isPresent()) return null;
        return event.getKiller().map(Bukkit::getPlayer).orElse(null);
    }

    static final class Factory implements ObjectiveFactory {
        private final NpcRegistry registry;
        private final QuestNpcHost host;

        Factory(NpcRegistry registry, QuestNpcHost host) { this.registry = registry; this.host = host; }

        @Override public Objective parseInstruction(Instruction instruction, ObjectiveService service) throws QuestException {
            Argument<NpcIdentifier> npc = instruction.identifier(NpcIdentifier.class).get();
            Argument<Number> amount = instruction.number().atLeast(1).get("amount", 1);
            AnimusNpcKillObjective objective = new AnimusNpcKillObjective(service, amount, npc, registry, host);
            service.request(VirtualNpcDeathEvent.class).handler(objective::killed).player(AnimusNpcKillObjective::killer).subscribe(false);
            return objective;
        }
    }
}
