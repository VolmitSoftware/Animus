package art.arcane.animus.core.skin;

import art.arcane.animus.core.presentation.BodySkin;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a skin PNG, or the address of one, into textures Mojang signed, which clients need before they draw a skin.
 * The answer completes off the server thread; a refusal completes exceptionally with a {@link Refused} whose message is
 * one line for a player.
 */
public interface SkinSigner {
    /** Signing is off: every request is refused with why. */
    static SkinSigner off(String why) {
        return new SkinSigner() {
            @Override public Optional<String> unavailable() { return Optional.of(why); }
            @Override public CompletableFuture<Signed> sign(Request request) { return CompletableFuture.failedFuture(new Refused(why)); }
        };
    }

    /** Why signing is off, such as a missing API key; empty when it works. */
    Optional<String> unavailable();

    CompletableFuture<Signed> sign(Request request);

    /** A PNG to upload, or an address to fetch it from. */
    sealed interface Source permits Image, Address {}

    record Image(byte[] png) implements Source {
        public Image { Objects.requireNonNull(png); }
    }

    record Address(String url) implements Source {
        public Address { Objects.requireNonNull(url); }
    }

    /** @param name a name MineSkin may show for the skin; empty for none */
    record Request(Source source, boolean slim, String name) {
        public Request { Objects.requireNonNull(source); name = name == null ? "" : name; }
    }

    /** The signed textures, their Mojang texture address and the arm model they carry. */
    record Signed(BodySkin skin, String url, boolean slim) {
        public Signed { Objects.requireNonNull(skin); if (skin.signature() == null) throw new IllegalArgumentException("unsigned textures"); }
    }

    /** The line to show for a failed signing: a refusal's own message, else what went wrong. */
    static String message(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof Refused ? cause.getMessage() : "MineSkin could not sign the skin: " + cause.getClass().getSimpleName();
    }

    /** A skin's label from a PNG file name or address: its last part without {@code .png}, in the characters MineSkin takes. */
    static String label(String source) {
        String path = source.strip();
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) last = last.substring(0, last.length() - 4);
        last = last.replaceAll("[^A-Za-z0-9_. -]", "").strip();
        if (last.length() > 32) last = last.substring(0, 32).strip();
        return last.isEmpty() ? "Signed skin" : last;
    }

    /** A request MineSkin or the server refused, with a message to show as it is. */
    final class Refused extends RuntimeException {
        public Refused(String message) { super(message, null, false, false); }
    }
}
