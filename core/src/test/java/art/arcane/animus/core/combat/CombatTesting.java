package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcAttributes;
import com.google.gson.JsonParser;

/** Health for engine tests: combat NPCs that fight each other need an {@code animus:health} module to be hurt. */
final class CombatTesting {
    /** Killable with every default: 20 health, every attack source, a respawn after 100 ticks at home. */
    static final HealthSettings KILLABLE = health("{\"killable\":true}");

    private CombatTesting() {}

    /** The settings of an {@code animus:health} module written as JSON. */
    static HealthSettings health(String json) { return HealthSettings.parse(JsonParser.parseString(json).getAsJsonObject(), NpcAttributes.NONE); }
}
