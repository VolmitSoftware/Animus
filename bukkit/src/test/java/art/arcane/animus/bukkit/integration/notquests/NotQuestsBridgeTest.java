package art.arcane.animus.bukkit.integration.notquests;

import art.arcane.animus.bukkit.api.NpcInfo;
import com.notquests.core.npc.NpcAttachments;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every NPC is animus:<id> to NotQuests; an imported one answers as the Citizens NPC its data names first. */
class NotQuestsBridgeTest {
    private static final NpcInfo NPC = new NpcInfo(UUID.randomUUID(), "citizens-5", "", "Guide", true);

    private static List<String> selectors(List<NotQuestsBridge.Identity> identities) {
        return identities.stream().map((NotQuestsBridge.Identity identity) -> NpcAttachments.selector(identity.type(), identity.id())).toList();
    }

    @Test void importedNpcAnswersAsItsCitizensNpcThenAsItself() {
        List<NotQuestsBridge.Identity> identities = NotQuestsBridge.identities(NPC, OptionalInt.of(5));
        assertEquals(List.of("citizens:5", "animus:citizens-5"), selectors(identities));
        assertEquals(5, identities.getFirst().id().getIntegerID());
    }

    @Test void animusNpcUsesItsId() {
        assertEquals(List.of("animus:guide"), selectors(NotQuestsBridge.identities(new NpcInfo(UUID.randomUUID(), "guide", "", "Guide", true), OptionalInt.empty())));
    }
}
