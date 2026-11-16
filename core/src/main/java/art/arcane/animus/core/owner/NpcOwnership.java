package art.arcane.animus.core.owner;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.config.OwnerSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who may act on which NPC, as Citizens' owner trait decides it: the console, {@code animus.admin} and
 * {@code animus.npc.others} act on every NPC; any other player only on NPCs they own, whatever other nodes they hold. A
 * player makes as many NPCs as their highest {@code animus.npc.limit.<n>} allows, else the {@code [owners]} default; an
 * admin has no limit.
 */
public final class NpcOwnership {
    private NpcOwnership() {}

    /** {@code player} is empty for the console; {@code has} answers the actor's nodes. */
    public static boolean admin(Optional<UUID> player, Predicate<String> has) { return player.isEmpty() || has.test(PermissionNodes.ADMIN); }

    /** The console, {@code animus.admin} or {@code animus.npc.others}: whoever may act on NPCs they do not own. */
    public static boolean others(Optional<UUID> player, Predicate<String> has) { return admin(player, has) || has.test(PermissionNodes.NPC_OTHERS); }

    public static boolean mayAct(Optional<UUID> player, Predicate<String> has, NpcDefinition npc) {
        return others(player, has) || npc.owner().equals(player);
    }

    /** The one line a player hears when they try to act on an NPC they do not own. */
    public static String refusal(NpcDefinition npc) { return npc.id() + " is not yours; you can change only NPCs you own"; }

    /** The one line a player hears when they try to change who owns an NPC. */
    public static final String OWNER_REFUSAL = "Only an admin can change who owns an NPC";

    /** How many NPCs the actor may own; empty for an admin, who has no limit. */
    public static OptionalInt limit(Optional<UUID> player, Predicate<String> has, OwnerSettings settings) {
        if (admin(player, has)) return OptionalInt.empty();
        for (int limit = OwnerSettings.MAXIMUM_LIMIT; limit >= 0; limit--) if (has.test(PermissionNodes.LIMIT_PREFIX + limit)) return OptionalInt.of(limit);
        return OptionalInt.of(settings.defaultLimit());
    }

    public static long owned(Collection<NpcDefinition> npcs, UUID player) {
        Optional<UUID> wanted = Optional.of(player);
        return npcs.stream().filter((NpcDefinition npc) -> npc.owner().equals(wanted)).count();
    }

    /** Why the player may not make another NPC, or empty while they are under their limit. */
    public static Optional<String> full(Collection<NpcDefinition> npcs, UUID player, OptionalInt limit) { return full(npcs, player, limit, 1); }

    /** Why the player may not make {@code adding} more NPCs at once, or empty while those still fit their limit. */
    public static Optional<String> full(Collection<NpcDefinition> npcs, UUID player, OptionalInt limit, int adding) {
        if (limit.isEmpty()) return Optional.empty();
        long owned = owned(npcs, player);
        if (owned + adding <= limit.getAsInt()) return Optional.empty();
        if (adding > 1 && owned < limit.getAsInt()) return Optional.of("You own " + owned + " of your " + limit.getAsInt() + " NPCs; that makes " + adding + " more");
        return Optional.of(limit.getAsInt() == 0 ? "You cannot make NPCs; ask an admin for an " + PermissionNodes.LIMIT_PREFIX + "<n> node"
                : "You own " + owned + (owned == 1 ? " NPC" : " NPCs") + ", your limit; remove one to make another");
    }
}
