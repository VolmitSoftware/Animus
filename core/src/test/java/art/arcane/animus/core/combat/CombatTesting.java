package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcAttributes;
import com.google.gson.JsonParser;

/** Health for engine tests: combat NPCs that fight each other need an {@code animus:health} module to be hurt. */
final class CombatTesting {
    /** Killable with every default: 20 health, every attack source, a respawn after 100 ticks at home. */
    static final HealthSettings KILLABLE = health("{\"killable\":true}");

    private CombatTesting() {}

    /** A full-charge standing player hit of exactly this much, with nothing in hand. */
    static CombatWorld.DamageOutcome hit(CombatEngine engine, java.util.UUID npc, TargetRef player, art.arcane.animus.core.virtual.WorldPosition from, double amount) {
        return engine.playerAttack(npc, player, from, VanillaMelee.Swing.standing(amount), VanillaMelee.Weapon.EMPTY).outcome();
    }

    /** The settings of an {@code animus:health} module written as JSON. */
    static HealthSettings health(String json) { return HealthSettings.parse(JsonParser.parseString(json).getAsJsonObject(), NpcAttributes.NONE); }
}
