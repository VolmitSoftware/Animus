package art.arcane.animus.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who hears an NPC: one player, the players within a radius of it, or everyone near it. Players the NPC is hidden from
 * never hear it. A radius outside 1 to 128 blocks answers {@link Outcome.Status#INVALID}. On Folia a radius also reaches
 * players another region owns, by where their region last saw them, through their own region's thread.
 *
 * @param player the one player, if any
 * @param radius blocks around the NPC; ignored for one player and for everyone
 * @param all every player within 128 blocks of the NPC
 * @since 1.3
 */
public record Audience(Optional<UUID> player, double radius, boolean all) {
    public Audience { Objects.requireNonNull(player, "player"); }

    /**
     * One player, wherever they stand, as long as the NPC is shown to them.
     *
     * @param player player UUID
     * @return the audience
     */
    public static Audience player(UUID player) { return new Audience(Optional.of(Objects.requireNonNull(player)), 0, false); }

    /**
     * Players within {@code radius} blocks of the NPC.
     *
     * @param radius blocks, 1 to 128
     * @return the audience
     */
    public static Audience within(double radius) { return new Audience(Optional.empty(), radius, false); }

    /**
     * Every player within 128 blocks of the NPC, as far as an NPC is shown.
     *
     * @return the audience
     */
    public static Audience everyone() { return new Audience(Optional.empty(), 0, true); }
}
