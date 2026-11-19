package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcAttributes;
import com.google.gson.JsonParser;

/** Health for engine tests: combat NPCs that fight each other need an {@code animus:health} module to be hurt. */
final class CombatTesting {
    /** Killable with every default: 20 health, every attack source, a respawn after 100 ticks at home. */
    static final HealthSettings KILLABLE = health("{\"killable\":true}");
    /** Ticks an archer that sees its target needs to draw in full and let its first arrow go, with a turn to spare. */
    static final int FIRST_ARROW = 22;
    /** Ticks between arrows when the cooldown is shorter than the draw: the release shows, then a full draw. */
    static final int NEXT_ARROW = DrawCycle.RELEASE_TICKS + 20;
    /** Ticks a crossbow needs to charge, be held loaded and aimed, and fire its first bolt, with a turn to spare. */
    static final int FIRST_BOLT = 25 + DrawCycle.AIM_TICKS + 2;

    private CombatTesting() {}

    /** A full-charge standing player hit of exactly this much, with nothing in hand. */
    static CombatWorld.DamageOutcome hit(CombatEngine engine, java.util.UUID npc, TargetRef player, art.arcane.animus.core.virtual.WorldPosition from, double amount) {
        return engine.playerAttack(npc, player, from, VanillaMelee.Swing.standing(amount), VanillaMelee.Weapon.EMPTY).outcome();
    }

    /** The settings of an {@code animus:health} module written as JSON. */
    static HealthSettings health(String json) { return HealthSettings.parse(JsonParser.parseString(json).getAsJsonObject(), NpcAttributes.NONE); }
}
