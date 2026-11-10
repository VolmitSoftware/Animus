package art.arcane.animus.bukkit.integration.mythic;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a Mythic mob file declares that bears on an NPC's look, read as plain text so the mapping needs no Mythic
 * classes: {@code Type}, {@code Display}, {@code Equipment}, the {@code Options} it sets, {@code Disguise} and its
 * {@code Skills} lines.
 *
 * @param name the mob type's internal name, such as {@code SkeletonKing}
 * @param type the entity type as Mythic writes it, such as {@code SKELETON} or {@code BABY_ZOMBIE}
 * @param options option values by name, such as {@code Glowing -> true}
 */
public record MobSource(String name, String type, Optional<String> display, List<String> equipment, Map<String, String> options,
                        Optional<String> disguise, List<String> skills) {
    public MobSource {
        Objects.requireNonNull(name); Objects.requireNonNull(type); Objects.requireNonNull(display); Objects.requireNonNull(disguise);
        equipment = List.copyOf(equipment); options = Map.copyOf(options); skills = List.copyOf(skills);
    }
}
