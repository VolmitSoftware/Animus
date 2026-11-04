package art.arcane.animus.core.combat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One list of targeting rules. A target matches when any rule does. Names compare without case.
 *
 * @param players player names or canonical UUIDs
 * @param npcs    NPC readable IDs or display names
 * @param names   custom names of real mobs
 * @param squads  squads of other NPCs
 */
public record TargetRules(Set<TargetCategory> categories, Set<String> entityTypes, Set<String> players,
                          Set<String> npcs, Set<String> names, Set<String> squads) {
    public static final TargetRules NONE = new TargetRules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    public TargetRules {
        categories = Set.copyOf(categories);
        entityTypes = Set.copyOf(entityTypes);
        players = folded(players);
        npcs = folded(npcs);
        names = folded(names);
        squads = Set.copyOf(squads);
    }

    public boolean empty() {
        return categories.isEmpty() && entityTypes.isEmpty() && players.isEmpty() && npcs.isEmpty() && names.isEmpty() && squads.isEmpty();
    }

    public boolean matches(CombatTarget target) {
        for (TargetCategory category : target.categories()) {
            if (categories.contains(category)) return true;
        }
        if (entityTypes.contains(target.type())) return true;
        String name = fold(target.name());
        return switch (target.ref().kind()) {
            case PLAYER -> players.contains(name) || players.contains(target.ref().id().toString());
            case MOB -> names.contains(name);
            case NPC -> npcs.contains(fold(target.handle())) || npcs.contains(name)
                    || target.squad().map(squads::contains).orElse(false);
        };
    }

    private static Set<String> folded(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) result.add(fold(value));
        return Set.copyOf(result);
    }
    private static String fold(String value) { return value.toLowerCase(Locale.ROOT); }
}
