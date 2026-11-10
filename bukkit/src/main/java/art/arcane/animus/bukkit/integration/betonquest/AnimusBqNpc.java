package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import art.arcane.animus.core.text.RichText;
import java.util.Objects;
import java.util.Optional;
import org.betonquest.betonquest.api.profile.OnlineProfile;
import org.betonquest.betonquest.api.quest.npc.Npc;
import org.bukkit.Location;

/** An Animus NPC as BetonQuest sees it. Two adapters of the same NPC are equal, so BetonQuest's sets match them. */
final class AnimusBqNpc implements Npc<NpcInfo> {
    private static final double EYES = 0.85;
    private final NpcInfo npc;
    private final QuestNpcHost host;

    AnimusBqNpc(NpcInfo npc, QuestNpcHost host) { this.npc = Objects.requireNonNull(npc); this.host = Objects.requireNonNull(host); }

    @Override public NpcInfo getOriginal() { return npc; }
    @Override public String getName() { return RichText.plain(npc.name()); }
    @Override public String getFormattedName() { return RichText.colorize(npc.name()); }
    @Override public Optional<Location> getLocation() { return host.location(npc.uuid()); }
    @Override public Optional<Location> getEyeLocation() {
        double height = host.height(npc.uuid()).orElse(1.8);
        return getLocation().map((Location at) -> at.add(0, height * EYES, 0));
    }
    @Override public void teleport(Location location) { host.teleport(npc.key(), location); }
    @Override public boolean isSpawned() { return host.npcs().byUuid(npc.uuid()).map(NpcInfo::spawned).orElse(false); }
    @Override public void spawn(Location location) {
        if (isSpawned()) return;
        host.teleport(npc.key(), location);
        host.spawned(npc.key(), true);
    }
    @Override public void despawn() { host.spawned(npc.key(), false); }
    @Override public void show(OnlineProfile profile) { host.hide(profile.getPlayer().getUniqueId(), npc.uuid(), false); }
    @Override public void hide(OnlineProfile profile) { host.hide(profile.getPlayer().getUniqueId(), npc.uuid(), true); }

    @Override public boolean equals(Object other) { return other instanceof AnimusBqNpc adapter && adapter.npc.uuid().equals(npc.uuid()); }
    @Override public int hashCode() { return npc.uuid().hashCode(); }
    @Override public String toString() { return "Animus NPC " + npc.key(); }
}
