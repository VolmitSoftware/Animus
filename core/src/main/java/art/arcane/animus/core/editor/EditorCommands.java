package art.arcane.animus.core.editor;

import art.arcane.animus.core.command.AnimusCommands;
import art.arcane.animus.core.command.CommandActor;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * {@code /animus editor pair|tokens|revoke|status}, the same on every platform. {@code pair} takes an optional label and
 * keyed options {@code scopes=a,b}, {@code groups=a,b} and {@code expires=7d} (also {@code h} and {@code m}). File work runs
 * on the shared file lane; replies go back on the actor's owner.
 */
public final class EditorCommands implements AnimusCommands.Extra {
    public static final List<String> OPERATIONS = List.of("pair", "tokens", "revoke", "status");
    private final Supplier<EditorService> service;
    private final Executor files;

    public EditorCommands(Supplier<EditorService> service, Executor files) {
        this.service = Objects.requireNonNull(service);
        this.files = Objects.requireNonNull(files);
    }

    @Override
    public void execute(CommandActor actor, String[] arguments) {
        EditorService editor = service.get();
        if (editor == null) { actor.tell("The editor is not running."); return; }
        String operation = arguments.length < 2 ? "status" : arguments[1].toLowerCase(Locale.ROOT);
        try {
            switch (operation) {
                case "status" -> {
                    if (arguments.length > 2) throw new IllegalArgumentException("editor status");
                    editor.status().forEach(actor::tell);
                }
                case "pair" -> {
                    EditorTokens.Grant grant = grant(actor, Arrays.copyOfRange(arguments, 2, arguments.length), editor.settings().tokenDays());
                    work(actor, () -> {
                        EditorService.PairingLink link = editor.pair(grant);
                        return List.of("Open this link in a browser within 10 minutes to pair it (single use):", link.link(),
                                "Token " + link.pairing().tokenId() + " | scopes " + String.join(" ", grant.scopes().stream().map(EditorScope::wireName).toList())
                                        + (grant.groups().isEmpty() ? "" : " | groups " + String.join(",", grant.groups()))
                                        + " | expires after " + describe(grant.lifetime()));
                    });
                }
                case "tokens" -> {
                    if (arguments.length > 3) throw new IllegalArgumentException("editor tokens [page]");
                    int page = arguments.length == 3 ? Integer.parseInt(arguments[2]) : 1;
                    if (page < 1) throw new IllegalArgumentException("Page must be a positive integer");
                    EditorApi api = editor.api().orElseThrow(() -> new IllegalArgumentException("The editor is disabled."));
                    List<EditorTokens.Token> tokens = api.parts().tokens().list();
                    actor.tell("Editor tokens: " + tokens.size() + "; page " + page);
                    tokens.stream().skip((page - 1L) * 20).limit(20).forEach((EditorTokens.Token token) -> actor.tell(token.id()
                            + (token.label().isEmpty() ? "" : " \"" + token.label() + "\"")
                            + " | " + String.join(" ", token.scopes().stream().map(EditorScope::wireName).toList())
                            + (token.groups().isEmpty() ? "" : " | groups " + String.join(",", token.groups()))
                            + " | expires " + EditorMessages.instant(token.expires())
                            + " | last used " + token.lastUsed().map(EditorMessages::instant).orElse("never")
                            + token.client().map((String client) -> " | " + client).orElse("")
                            + " | streams " + api.parts().events().open(token.id())));
                }
                case "revoke" -> {
                    if (arguments.length != 3) throw new IllegalArgumentException("editor revoke <token-id|all>");
                    String target = arguments[2];
                    work(actor, () -> {
                        List<String> revoked = editor.revoke(target);
                        return List.of(revoked.isEmpty() ? "No token " + target + "." : "Revoked " + revoked.size() + " token" + (revoked.size() == 1 ? "" : "s")
                                + "; their connections and previews were closed.");
                    });
                }
                default -> throw new IllegalArgumentException("editor pair [label] [scopes=a,b] [groups=a,b] [expires=7d] | tokens [page] | revoke <token-id|all> | status");
            }
        } catch (IllegalArgumentException invalid) { actor.tell("Animus editor: " + invalid.getMessage()); }
    }

    @FunctionalInterface private interface Work { List<String> run() throws IOException; }

    private void work(CommandActor actor, Work work) {
        try {
            files.execute(() -> {
                List<String> lines;
                try { lines = work.run(); }
                catch (IOException | RuntimeException failure) { lines = List.of("Animus editor: " + failure.getMessage()); }
                List<String> reply = lines;
                actor.reply(() -> reply.forEach(actor::tell));
            });
        } catch (RejectedExecutionException full) { actor.tell("Animus file queue is full; retry shortly."); }
    }

    /** Reads {@code [label] [scopes=..] [groups=..] [expires=..]}. */
    static EditorTokens.Grant grant(CommandActor actor, String[] options, int tokenDays) {
        String label = "";
        List<EditorScope> scopes = EditorScope.DEFAULT_PAIRING;
        List<String> groups = List.of();
        Duration lifetime = Duration.ofDays(tokenDays);
        for (String option : options) {
            int equals = option.indexOf('=');
            if (equals < 0) {
                if (!label.isEmpty()) throw new IllegalArgumentException("Give one label; options are key=value");
                label = option;
                continue;
            }
            String key = option.substring(0, equals).toLowerCase(Locale.ROOT), value = option.substring(equals + 1);
            switch (key) {
                case "scopes" -> {
                    List<EditorScope> parsed = new ArrayList<>();
                    for (String name : value.split(",")) parsed.add(EditorScope.parse(name.strip()).orElseThrow(() -> new IllegalArgumentException("Unknown scope " + name
                            + "; scopes are " + String.join(", ", Arrays.stream(EditorScope.values()).map(EditorScope::wireName).toList()))));
                    scopes = List.copyOf(parsed);
                }
                case "groups" -> groups = Arrays.stream(value.split(",")).map(String::strip).filter((String group) -> !group.isEmpty()).toList();
                case "expires" -> lifetime = duration(value);
                default -> throw new IllegalArgumentException("Unknown option " + key + "; use scopes=, groups= or expires=");
            }
        }
        Optional<java.util.UUID> admin = actor.player();
        return new EditorTokens.Grant(label, admin, scopes, groups, lifetime);
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

    @Override
    public List<String> complete(CommandActor actor, String[] arguments) {
        if (arguments.length == 2) return OPERATIONS.stream().filter((String option) -> option.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        EditorService editor = service.get();
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("revoke") && editor != null && editor.api().isPresent()) {
            List<String> options = new ArrayList<>(List.of("all"));
            editor.api().get().parts().tokens().list().forEach((EditorTokens.Token token) -> options.add(token.id()));
            return options.stream().filter((String option) -> option.startsWith(arguments[2])).limit(100).toList();
        }
        if (arguments.length >= 3 && arguments[1].equalsIgnoreCase("pair"))
            return List.of("scopes=", "groups=", "expires=").stream().filter((String option) -> option.startsWith(arguments[arguments.length - 1])).toList();
        return List.of();
    }
}
