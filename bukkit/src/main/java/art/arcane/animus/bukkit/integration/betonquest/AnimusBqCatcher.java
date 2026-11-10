package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import java.util.Optional;
import java.util.Set;
import org.betonquest.betonquest.api.bukkit.event.npc.NpcInteractEvent;
import org.betonquest.betonquest.api.identifier.NpcIdentifier;
import org.betonquest.betonquest.api.profile.OnlineProfile;
import org.betonquest.betonquest.api.profile.ProfileProvider;
import org.betonquest.betonquest.api.service.npc.NpcRegistry;
import org.betonquest.betonquest.quest.objective.interact.Interaction;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Turns clicks on Animus NPCs into BetonQuest's NPC interaction event, which starts {@code npc_conversations} and drives
 * {@code npcinteract}. When BetonQuest cancels it, as a conversation start does, Animus's own click handling is skipped too.
 */
final class AnimusBqCatcher implements Listener {
    private final ProfileProvider profiles;
    private final NpcRegistry registry;
    private final QuestNpcHost host;

    AnimusBqCatcher(ProfileProvider profiles, NpcRegistry registry, QuestNpcHost host) { this.profiles = profiles; this.registry = registry; this.host = host; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void clicked(VirtualNpcInteractionEvent event) {
        if (event.isPreview()) return;
        Optional<NpcInfo> npc = host.npcs().byUuid(event.getNpcId());
        if (npc.isEmpty()) return;
        AnimusBqNpc adapter = new AnimusBqNpc(npc.get(), host);
        OnlineProfile profile = profiles.getProfile(event.getViewer());
        Set<NpcIdentifier> ids = registry.getIdentifier(adapter, profile);
        Interaction interaction = event.getAction() == VirtualNpcInteractionEvent.Action.ATTACK ? Interaction.LEFT : Interaction.RIGHT;
        NpcInteractEvent quest = new NpcInteractEvent(profile, adapter, ids, interaction, !Bukkit.isPrimaryThread());
        Bukkit.getPluginManager().callEvent(quest);
        if (quest.isCancelled()) event.setCancelled(true);
    }
}
