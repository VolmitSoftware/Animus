package art.arcane.animus.core.combat;

import java.util.Locale;
import java.util.Optional;

/** Broad target groups usable in combat rules. A real mob reports MOBS plus MONSTERS or ANIMALS. */
public enum TargetCategory {
    PLAYERS, NPCS, MOBS, MONSTERS, ANIMALS;

    public String jsonName() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<TargetCategory> parse(String value) {
        for (TargetCategory category : values()) {
            if (category.jsonName().equals(value)) return Optional.of(category);
        }
        return Optional.empty();
    }
}
