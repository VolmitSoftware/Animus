package art.arcane.animus.core.combat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * One list of targeting rules. A target matches when any rule does. Names compare without case.
 *
 * @param players     player names or canonical UUIDs
 * @param npcs        NPC readable IDs or display names
 * @param names       custom names of real mobs
 * @param squads      squads of other NPCs
 * @param owner       the player that owns this NPC
 * @param healthAbove percentage of maximum health a target must exceed
 * @param healthBelow percentage of maximum health a target must be under
 * @param permissions permission nodes a player has
 * @param groups      permission groups a player is in
 * @param effects     status effect ids a target has
 * @param all         rule sets that one target must match completely
 * @param together    groups of rule sets that several distinct targets must match at once
 * @param events      fights nearby that make the attacker a target
 * @param chat        text in a nearby player's chat message that makes the player a target
 */
public record TargetRules(Set<TargetCategory> categories, Set<String> entityTypes, Set<String> players, Set<String> npcs, Set<String> names,
                          Set<String> squads, boolean owner, Patterns patterns, Items items, OptionalDouble healthAbove, OptionalDouble healthBelow,
                          Set<String> permissions, Set<String> groups, Set<String> effects, List<TargetRules> all, List<List<TargetRules>> together,
                          Fights events, Set<String> chat) {
    /** Name patterns by kind of target. */
    public record Patterns(List<NamePattern> players, List<NamePattern> npcs, List<NamePattern> names) {
        public static final Patterns NONE = new Patterns(List.of(), List.of(), List.of());
        public Patterns { players = List.copyOf(players); npcs = List.copyOf(npcs); names = List.copyOf(names); }
        boolean empty() { return players.isEmpty() && npcs.isEmpty() && names.isEmpty(); }
    }
    /** Item id patterns by where the target carries the item. */
    public record Items(List<NamePattern> mainHand, List<NamePattern> offHand, List<NamePattern> worn) {
        public static final Items NONE = new Items(List.of(), List.of(), List.of());
        public Items { mainHand = List.copyOf(mainHand); offHand = List.copyOf(offHand); worn = List.copyOf(worn); }
        boolean empty() { return mainHand.isEmpty() && offHand.isEmpty() && worn.isEmpty(); }
    }
    /**
     * Fight rules. The attacker becomes a target.
     *
     * @param playerAttacks a player hurts something that matches these rules
     * @param anyAttacks    a player or mob hurts something that matches these rules
     */
    public record Fights(Set<FightKind> kinds, Optional<TargetRules> playerAttacks, Optional<TargetRules> anyAttacks) {
        public static final Fights NONE = new Fights(Set.of(), Optional.empty(), Optional.empty());
        public Fights { kinds = Set.copyOf(kinds); }
        public boolean empty() { return kinds.isEmpty() && playerAttacks.isEmpty() && anyAttacks.isEmpty(); }
    }
    /** What rules need from the NPC that applies them. */
    public interface Context {
        Context NONE = new Context() {
            @Override public Optional<UUID> owner() { return Optional.empty(); }
            @Override public boolean permitted(TargetRef player, String node) { return false; }
        };
        Optional<UUID> owner();
        /** Whether this online player has the permission node. Called on the owner thread. */
        boolean permitted(TargetRef player, String node);
    }

    public static final TargetRules NONE = new TargetRules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false, Patterns.NONE, Items.NONE,
            OptionalDouble.empty(), OptionalDouble.empty(), Set.of(), Set.of(), Set.of(), List.of(), List.of(), Fights.NONE, Set.of());

    public TargetRules {
        categories = Set.copyOf(categories);
        entityTypes = Set.copyOf(entityTypes);
        players = folded(players);
        npcs = folded(npcs);
        names = folded(names);
        squads = Set.copyOf(squads);
        permissions = Set.copyOf(permissions);
        groups = Set.copyOf(groups);
        effects = Set.copyOf(effects);
        all = List.copyOf(all);
        together = together.stream().map(List::copyOf).toList();
        chat = folded(chat);
    }

    /** Whether nothing can ever match during a scan. Fight and chat rules are reported separately. */
    public boolean empty() { return !scans() && events.empty() && chat.isEmpty(); }

    /** Whether any rule applies to scanned candidates. */
    public boolean scans() {
        return !categories.isEmpty() || !entityTypes.isEmpty() || !players.isEmpty() || !npcs.isEmpty() || !names.isEmpty() || !squads.isEmpty() || owner
                || !patterns.empty() || !items.empty() || healthAbove.isPresent() || healthBelow.isPresent() || !permissions.isEmpty() || !groups.isEmpty()
                || !effects.isEmpty() || !all.isEmpty() || !together.isEmpty();
    }

    /** Any one rule matches. {@code together} groups are checked by the caller over a whole scan. */
    public boolean matches(CombatTarget target, Context context) {
        for (TargetCategory category : target.categories()) {
            if (categories.contains(category)) return true;
        }
        if (entityTypes.contains(target.type())) return true;
        if (owner && context.owner().map(target.ref().id()::equals).orElse(false)) return true;
        String name = fold(target.name());
        boolean named = switch (target.ref().kind()) {
            case PLAYER -> players.contains(name) || players.contains(target.ref().id().toString()) || any(patterns.players(), target.name());
            case MOB -> names.contains(name) || any(patterns.names(), mobName(target));
            case NPC -> npcs.contains(fold(target.handle())) || npcs.contains(name) || target.squad().map(squads::contains).orElse(false)
                    || any(patterns.npcs(), target.name()) || any(patterns.npcs(), target.handle());
        };
        if (named) return true;
        CombatTarget.Details details = target.details();
        if (any(items.mainHand(), details.mainHand()) || any(items.offHand(), details.offHand())) return true;
        for (String worn : details.worn()) if (any(items.worn(), worn)) return true;
        if (healthAbove.isPresent() && percent(target) > healthAbove.getAsDouble()) return true;
        if (healthBelow.isPresent() && percent(target) < healthBelow.getAsDouble()) return true;
        for (String effect : effects) if (details.effects().contains(effect)) return true;
        for (TargetRules part : all) if (part.matchesAll(target, context)) return true;
        if (target.ref().kind() == TargetKind.PLAYER) {
            for (String node : permissions) if (context.permitted(target.ref(), node)) return true;
            for (String group : groups) if (context.permitted(target.ref(), groupNode(group))) return true;
        }
        return false;
    }

    /** Every rule listed here matches this one target. A set with no rules matches nothing. */
    public boolean matchesAll(CombatTarget target, Context context) {
        if (!scans()) return false;
        for (TargetCategory category : categories) if (!target.categories().contains(category)) return false;
        for (String type : entityTypes) if (!type.equals(target.type())) return false;
        if (owner && !context.owner().map(target.ref().id()::equals).orElse(false)) return false;
        String name = fold(target.name());
        TargetKind kind = target.ref().kind();
        for (String player : players) if (kind != TargetKind.PLAYER || !(player.equals(name) || player.equals(target.ref().id().toString()))) return false;
        for (String npc : npcs) if (kind != TargetKind.NPC || !(npc.equals(name) || npc.equals(fold(target.handle())))) return false;
        for (String mob : names) if (kind != TargetKind.MOB || !mob.equals(name)) return false;
        for (String squad : squads) if (!target.squad().map(squad::equals).orElse(false)) return false;
        for (NamePattern pattern : patterns.players()) if (kind != TargetKind.PLAYER || !pattern.matches(target.name())) return false;
        for (NamePattern pattern : patterns.npcs()) if (kind != TargetKind.NPC || !(pattern.matches(target.name()) || pattern.matches(target.handle()))) return false;
        for (NamePattern pattern : patterns.names()) if (kind != TargetKind.MOB || !pattern.matches(mobName(target))) return false;
        CombatTarget.Details details = target.details();
        for (NamePattern pattern : items.mainHand()) if (!pattern.matches(details.mainHand())) return false;
        for (NamePattern pattern : items.offHand()) if (!pattern.matches(details.offHand())) return false;
        for (NamePattern pattern : items.worn()) if (details.worn().stream().noneMatch(pattern::matches)) return false;
        if (healthAbove.isPresent() && !(percent(target) > healthAbove.getAsDouble())) return false;
        if (healthBelow.isPresent() && !(percent(target) < healthBelow.getAsDouble())) return false;
        for (String effect : effects) if (!details.effects().contains(effect)) return false;
        for (TargetRules part : all) if (!part.matchesAll(target, context)) return false;
        for (String node : permissions) if (kind != TargetKind.PLAYER || !context.permitted(target.ref(), node)) return false;
        for (String group : groups) if (kind != TargetKind.PLAYER || !context.permitted(target.ref(), groupNode(group))) return false;
        return true;
    }

    /** The permission node a group rule checks. Permission plugins grant {@code group.<name>} to group members. */
    public static String groupNode(String group) { return "group." + group; }

    /** A mob with no custom name is named by its entity type, such as {@code zombie}. */
    private static String mobName(CombatTarget target) {
        if (!target.name().isEmpty()) return target.name();
        int colon = target.type().indexOf(':');
        return colon < 0 ? target.type() : target.type().substring(colon + 1);
    }
    private static double percent(CombatTarget target) {
        double maximum = target.details().maximumHealth();
        return maximum > 0 ? target.health() / maximum * 100 : Double.NaN;
    }
    private static boolean any(List<NamePattern> patterns, String text) {
        for (NamePattern pattern : patterns) if (pattern.matches(text)) return true;
        return false;
    }
    private static Set<String> folded(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) result.add(fold(value));
        return Set.copyOf(result);
    }
    private static String fold(String value) { return value.toLowerCase(Locale.ROOT); }
}
