package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.instruction.FlagArgument;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.profile.Profile;
import org.betonquest.betonquest.api.quest.npc.Npc;
import org.betonquest.betonquest.api.quest.npc.NpcFactory;
import org.betonquest.betonquest.api.quest.npc.NpcWrapper;
import org.jetbrains.annotations.Nullable;

/** The {@code animus} NPC type: {@code animus <id>}, or {@code animus <name> byName} for every NPC with that display name. */
final class AnimusBqFactory implements NpcFactory {
    private final QuestNpcHost host;

    AnimusBqFactory(QuestNpcHost host) { this.host = host; }

    @Override public NpcWrapper<NpcInfo> parseInstruction(Instruction instruction) throws QuestException {
        return new Wrapper(host, instruction.string().get(), instruction.bool().getFlag("byName", true));
    }

    private record Wrapper(QuestNpcHost host, Argument<String> reference, FlagArgument<Boolean> byName) implements NpcWrapper<NpcInfo> {
        @Override public Npc<NpcInfo> getNpc(@Nullable Profile profile) throws QuestException {
            List<NpcInfo> found = find(profile);
            if (found.size() > 1) throw new QuestException("Several Animus NPCs are named " + reference.getValue(profile));
            return new AnimusBqNpc(found.getFirst(), host);
        }

        @Override public Set<Npc<NpcInfo>> getNpcs(@Nullable Profile profile) throws QuestException {
            return find(profile).stream().map((NpcInfo npc) -> (Npc<NpcInfo>) new AnimusBqNpc(npc, host)).collect(Collectors.toSet());
        }

        private List<NpcInfo> find(@Nullable Profile profile) throws QuestException {
            String wanted = reference.getValue(profile);
            boolean named = byName.getValue(profile).orElse(false);
            List<NpcInfo> found = named ? host.npcs().byName(wanted) : host.npcs().byKey(wanted).map(List::of).orElse(List.of());
            if (found.isEmpty()) throw new QuestException("No Animus NPC with " + (named ? "name " : "id ") + wanted);
            return found;
        }
    }
}
