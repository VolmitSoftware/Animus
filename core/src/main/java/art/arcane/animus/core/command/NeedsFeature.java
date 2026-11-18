package art.arcane.animus.core.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A server feature a Director command or group needs that not every platform has. Where the platform lacks it, help,
 * completion and the client command tree leave the node out, and typing it anyway answers why in one line.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NeedsFeature {
    Feature value();

    enum Feature {
        /** The packet previews of {@code /animus proof}: Paper and Spigot. */
        PACKET_PREVIEW,
        /** The stationary preview of {@code /animus proof folia}: Folia. */
        FOLIA_PREVIEW,
        /** Shops players make on their own chests: Paper, Spigot and Folia. */
        PLAYER_SHOPS
    }
}
