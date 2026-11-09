package art.arcane.animus.core.editor;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * {@code /animus editor pair|tokens|revoke|status}, the same on every platform. {@code pair} takes the keyed options
 * {@code label=}, {@code scopes=a,b}, {@code groups=a,b} and {@code expires=7d} (also {@code h} and {@code m}). File work
 * runs on the shared file lane; replies go back on the actor's owner.
 */
public final class EditorCommands {
    /** Lets a player pair {@code apply:commands}; the console always may. */
    public static final String COMMANDS_PERMISSION = "animus.editor.commands";
    private final Supplier<EditorService> service;
    private final Executor files;

    public EditorCommands(Supplier<EditorService> service, Executor files) {
        this.service = Objects.requireNonNull(service);
        this.files = Objects.requireNonNull(files);
    }

    /** A player gets the headline with the full status in a hover; the console gets the full status. */
    public void status(CommandActor actor) {
        EditorService editor = running(actor);
        if (editor == null) return;
        if (actor.player().isEmpty()) editor.status().forEach(actor::tell);
        else actor.rich(ChatLine.reply(editor.headline()).details("Details", editor.status()));
    }

    /** A single-use pairing link. Blank options take their defaults. */
    public void pair(CommandActor actor, String label, String scopes, String groups, String expires) {
        EditorService editor = running(actor);
        if (editor == null) return;
        EditorTokens.Grant grant;
        try { grant = grant(actor, label, scopes, groups, expires, editor.settings().tokenDays()); }
        catch (IllegalArgumentException invalid) { actor.tell(Palette.error(invalid.getMessage())); return; }
        work(actor, () -> {
            EditorService.PairingLink link = editor.pair(grant);
            String token = link.pairing().tokenId();
            return List.of(ChatLine.success("Pairing link ready; open it once within " + EditorTokens.PAIRING_LIFETIME.toMinutes() + " minutes").link("Open editor", link.link()),
                    ChatLine.reply("The paired editor may " + String.join(" ", grant.scopes().stream().map(EditorScope::wireName).toList())
                            + (grant.groups().isEmpty() ? "" : " for groups " + String.join(",", grant.groups()))
                            + "; pairing lasts " + describe(grant.lifetime())).copy("[Token id]", token, "Click to copy the token id").suggest("Revoke", "/animus editor revoke " + token, "Revoke this token"));
        });
    }

    /** One line per token: id, label, scopes, groups, expiry, last use, client and open streams. */
    public Optional<List<String>> tokens(CommandActor actor) {
        EditorService editor = running(actor);
        if (editor == null) return Optional.empty();
        Optional<EditorApi> api = editor.api();
        if (api.isEmpty()) { actor.tell(Palette.error("Editor disabled; set [editor] enabled = true in config.toml")); return Optional.empty(); }
        return Optional.of(api.get().parts().tokens().list().stream().map((EditorTokens.Token token) -> token.id()
                + (token.label().isEmpty() ? "" : " \"" + token.label() + "\"")
                + " | " + String.join(" ", token.scopes().stream().map(EditorScope::wireName).toList())
                + (token.groups().isEmpty() ? "" : " | groups " + String.join(",", token.groups()))
                + " | expires " + EditorMessages.instant(token.expires())
                + " | last used " + token.lastUsed().map(EditorMessages::instant).orElse("never")
                + token.client().map((String client) -> " | " + client).orElse("")
                + " | streams " + api.get().parts().events().open(token.id())).toList());
    }

    /** {@code target} is a token id or {@code all}. */
    public void revoke(CommandActor actor, String target) {
        EditorService editor = running(actor);
        if (editor == null) return;
        work(actor, () -> {
            List<String> revoked = editor.revoke(target);
            return List.of(revoked.isEmpty() ? ChatLine.error("No token " + target) : ChatLine.success("Revoked " + revoked.size() + " token" + (revoked.size() == 1 ? "" : "s")
                    + "; their connections and previews closed"));
        });
    }

    /** Token ids and {@code all}, for completion. */
    public List<String> revocable() {
        EditorService editor = service.get();
        List<String> options = new ArrayList<>(List.of("all"));
        if (editor != null && editor.api().isPresent()) editor.api().get().parts().tokens().list().forEach((EditorTokens.Token token) -> options.add(token.id()));
        return options;
    }

    private EditorService running(CommandActor actor) {
        EditorService editor = service.get();
        if (editor == null) actor.tell(Palette.error("Editor not running; set [editor] enabled = true in config.toml, which applies when the file is saved"));
        return editor;
    }

    @FunctionalInterface private interface Work { List<ChatLine> run() throws IOException; }

    private void work(CommandActor actor, Work work) {
        try {
            files.execute(() -> {
                List<ChatLine> lines;
                try { lines = work.run(); }
                catch (IOException | RuntimeException failure) { lines = List.of(ChatLine.error("Editor: " + failure.getMessage())); }
                List<ChatLine> reply = lines;
                actor.reply(() -> reply.forEach(actor::rich));
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    /** Reads the pairing options; a blank option keeps its default. */
    static EditorTokens.Grant grant(CommandActor actor, String label, String scopes, String groups, String expires, int tokenDays) {
        List<EditorScope> granted = EditorScope.DEFAULT_PAIRING;
        if (!scopes.isBlank()) {
            List<EditorScope> parsed = new ArrayList<>();
            for (String name : scopes.split(",")) parsed.add(EditorScope.parse(name.strip()).orElseThrow(() -> new IllegalArgumentException("Unknown scope " + name
                    + "; scopes are " + String.join(", ", Arrays.stream(EditorScope.values()).map(EditorScope::wireName).toList()))));
            granted = List.copyOf(parsed);
        }
        if (granted.contains(EditorScope.APPLY_COMMANDS) && actor.player().isPresent() && !actor.permitted(COMMANDS_PERMISSION))
            throw new IllegalArgumentException("apply:commands lets the editor run console commands; it needs " + COMMANDS_PERMISSION + " or the console");
        List<String> grouped = Arrays.stream(groups.split(",")).map(String::strip).filter((String group) -> !group.isEmpty()).toList();
        Duration lifetime = expires.isBlank() ? Duration.ofDays(tokenDays) : duration(expires);
        Optional<UUID> admin = actor.player();
        return new EditorTokens.Grant(label.strip(), admin, granted, grouped, lifetime);
    }

    static Duration duration(String text) {
        if (!text.matches("[1-9][0-9]{0,5}[dhm]")) throw new IllegalArgumentException("expires takes a number with d, h or m, such as 7d");
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) { case 'd' -> Duration.ofDays(amount); case 'h' -> Duration.ofHours(amount); default -> Duration.ofMinutes(amount); };
    }

    private static String describe(Duration lifetime) {
        if (lifetime.toDays() > 0 && lifetime.equals(Duration.ofDays(lifetime.toDays()))) return lifetime.toDays() + "d";
        if (lifetime.toHours() > 0 && lifetime.equals(Duration.ofHours(lifetime.toHours()))) return lifetime.toHours() + "h";
        return lifetime.toMinutes() + "m";
    }
}
