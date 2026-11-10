package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.core.text.RichText;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.betonquest.betonquest.api.identifier.NpcIdentifier;
import org.betonquest.betonquest.api.profile.OnlineProfile;
import org.betonquest.betonquest.api.quest.npc.Npc;
import org.betonquest.betonquest.api.quest.npc.NpcReverseIdentifier;
import org.jetbrains.annotations.Nullable;

/** Finds the BetonQuest ids written as {@code animus <id>} or {@code animus <name> byName} for an Animus NPC. */
final class AnimusBqIdentifier implements NpcReverseIdentifier {
    static final String TYPE = "animus";
    private final Map<String, Set<NpcIdentifier>> byInstruction = new ConcurrentHashMap<>();

    @Override public Set<NpcIdentifier> getIdsFromNpc(Npc<?> npc, @Nullable OnlineProfile profile) {
        if (!(npc.getOriginal() instanceof NpcInfo info)) return Set.of();
        Set<NpcIdentifier> found = new HashSet<>(byInstruction.getOrDefault(key(info.key()), Set.of()));
        found.addAll(byInstruction.getOrDefault(key(RichText.plain(info.name()) + " byname"), Set.of()));
        return found;
    }

    @Override public void addID(NpcIdentifier npcId, String instruction) {
        String[] words = instruction.strip().split("\\s+", 2);
        if (words.length < 2 || !words[0].equalsIgnoreCase(TYPE)) return;
        byInstruction.computeIfAbsent(key(words[1]), (String ignored) -> ConcurrentHashMap.newKeySet()).add(npcId);
    }

    @Override public void reset() { byInstruction.clear(); }

    private static String key(String rest) { return rest.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
