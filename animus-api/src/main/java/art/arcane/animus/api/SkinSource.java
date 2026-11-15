package art.arcane.animus.api;

import java.util.Objects;

/**
 * Where an NPC's skin comes from. Animus saves every skin as a skin file once, signed, and the NPC wears that file:
 * a player name is looked up on Mojang, an image address is signed through MineSkin with the server's
 * {@code [skins] mineskinKey}, and a texture is used as given. Skins need a {@code minecraft:player} body.
 *
 * @since 1.3
 */
public sealed interface SkinSource permits SkinSource.Player, SkinSource.Image, SkinSource.Texture {
    /**
     * The skin of the Mojang account with this name. A name that has no account answers {@link Outcome.Status#NOT_FOUND}.
     *
     * @param name account name
     */
    record Player(String name) implements SkinSource { public Player { Objects.requireNonNull(name, "name"); } }

    /**
     * A PNG skin at a web address, signed through MineSkin. Without a MineSkin key the call answers
     * {@link Outcome.Status#UNAVAILABLE}.
     *
     * @param url http or https address of a 64x64 PNG
     * @param slim true for the slim arm model
     */
    record Image(String url, boolean slim) implements SkinSource { public Image { Objects.requireNonNull(url, "url"); } }

    /**
     * A texture property as a game profile carries it. An unsigned texture in an {@link NpcSpec} is signed through
     * MineSkin once a definition wears it; {@link NpcService#skin} needs a signed one.
     *
     * @param value base64 texture value
     * @param signature Mojang's signature of the value, or empty to have Animus sign it later
     */
    record Texture(String value, String signature) implements SkinSource {
        public Texture { Objects.requireNonNull(value, "value"); Objects.requireNonNull(signature, "signature"); }
    }

    /**
     * The skin of a Mojang account.
     *
     * @param name account name
     * @return the source
     */
    static SkinSource player(String name) { return new Player(name); }

    /**
     * A skin image at a web address, classic arms.
     *
     * @param url address of the PNG
     * @return the source
     */
    static SkinSource image(String url) { return new Image(url, false); }

    /**
     * A signed texture.
     *
     * @param value base64 texture value
     * @param signature its signature
     * @return the source
     */
    static SkinSource texture(String value, String signature) { return new Texture(value, signature); }
}
