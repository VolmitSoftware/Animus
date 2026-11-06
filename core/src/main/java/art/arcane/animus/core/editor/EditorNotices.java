package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.config.EditorNotifySettings.Kind;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * In-game notices of what the web editor changed on the server. Events of one kind from one token merge until
 * {@link #QUIET} passes without another, or for at most {@link #LONGEST}, so a burst is one sound and one line. The editor
 * service calls {@link #tick} on its timer. With {@code /animus debug mode} on, each event is also logged as it arrives.
 */
public final class EditorNotices {
    public static final Duration QUIET = Duration.ofMillis(400), LONGEST = Duration.ofSeconds(3);
    private static final int SHOWN = 8;

    /** The token behind an event. {@code owner} is the player who paired it, empty for a console pairing. */
    public record Who(String tokenId, String label, Optional<UUID> owner) {
        public Who { Objects.requireNonNull(tokenId); Objects.requireNonNull(label); Objects.requireNonNull(owner); }

        public static Who of(EditorTokens.Token token) { return new Who(token.id(), token.label(), token.admin()); }
    }

    /**
     * One editor event. {@code verb} reads after the token, such as {@code applied}; {@code documents} are {@code kind:id}
     * names; {@code detail} stands in for them when there are none, such as a refusal code; {@code transaction} can be rolled
     * back with {@code /animus rollback}; {@code problems} are refusals the reload reported, or the reason for a refusal.
     */
    public record Event(Kind kind, Who who, String verb, String detail, List<String> documents, Optional<String> transaction, List<String> problems) {
        public Event {
            Objects.requireNonNull(kind); Objects.requireNonNull(who); Objects.requireNonNull(verb); Objects.requireNonNull(detail);
            documents = List.copyOf(documents);
            Objects.requireNonNull(transaction);
            problems = List.copyOf(problems);
        }
    }

    /** A refused server change, such as {@code apply refused: scope-missing}. */
    public static Event refusal(Who who, String action, String code, String message) {
        return new Event(Kind.REFUSED, who, action + " refused:", code, List.of(), Optional.empty(), message == null || message.isBlank() ? List.of() : List.of(message));
    }

    /** A refusal answered as a response: an error object, or a conflict or invalid change result naming its documents. */
    public static Event refusal(Who who, String action, EditorResponse response) {
        JsonObject body = response.body().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElseGet(JsonObject::new);
        if (body.has("error") && body.get("error").isJsonObject()) {
            JsonObject error = body.getAsJsonObject("error");
            return refusal(who, action, error.has("code") ? error.get("code").getAsString() : "error", error.has("message") ? error.get("message").getAsString() : "");
        }
        String status = body.has("status") && body.get("status").isJsonPrimitive() ? body.get("status").getAsString() : "HTTP " + response.status();
        List<String> problems = new ArrayList<>();
        if (body.has("conflicts") && body.get("conflicts").isJsonArray())
            for (JsonElement conflict : body.getAsJsonArray("conflicts")) problems.add("conflict: " + reference(conflict.getAsJsonObject()));
        if (body.has("documents") && body.get("documents").isJsonArray())
            for (JsonElement document : body.getAsJsonArray("documents")) {
                JsonObject entry = document.getAsJsonObject();
                if (entry.has("diagnostics") && !entry.getAsJsonArray("diagnostics").isEmpty() && entry.has("kind") && entry.has("id")) problems.add("invalid: " + reference(entry));
            }
        return new Event(Kind.REFUSED, who, action + " refused:", status, List.of(), Optional.empty(), problems);
    }

    /** {@code kind:id} of a document reference in a message. */
    static String reference(JsonObject document) { return document.get("kind").getAsString() + ":" + document.get("id").getAsString(); }

    private static final class Group {
        final Kind kind;
        final Who who;
        final String verb;
        final long first;
        long last;
        int events;
        final Set<String> details = new LinkedHashSet<>(), documents = new LinkedHashSet<>();
        final List<String> transactions = new ArrayList<>(), problems = new ArrayList<>();

        Group(Event event, long now) {
            kind = event.kind();
            who = event.who();
            verb = event.verb();
            first = now;
        }
    }

    private final Supplier<EditorNotifySettings> settings;
    private final EditorNoticeChannel channel;
    private final Consumer<String> log;
    private final LongSupplier nanos;
    private final BooleanSupplier debug;
    private final Map<String, Group> groups = new LinkedHashMap<>();

    public EditorNotices(Supplier<EditorNotifySettings> settings, EditorNoticeChannel channel, Consumer<String> log, LongSupplier nanos, BooleanSupplier debug) {
        this.settings = Objects.requireNonNull(settings);
        this.channel = Objects.requireNonNull(channel);
        this.log = Objects.requireNonNull(log);
        this.nanos = Objects.requireNonNull(nanos);
        this.debug = Objects.requireNonNull(debug);
    }

    /** Queues an event; any thread. */
    public void record(Event event) {
        if (debug.getAsBoolean()) log.accept(debugLine(event));
        long now = nanos.getAsLong();
        synchronized (this) {
            Group group = groups.computeIfAbsent(event.kind().wireName() + "\n" + event.who().tokenId() + "\n" + event.verb(), (String key) -> new Group(event, now));
            group.last = now;
            group.events++;
            if (!event.detail().isEmpty()) group.details.add(event.detail());
            group.documents.addAll(event.documents());
            event.transaction().ifPresent(group.transactions::add);
            group.problems.addAll(event.problems());
        }
    }

    /** Sends every group that has been quiet for {@link #QUIET} or open for {@link #LONGEST}. */
    public void tick() {
        long now = nanos.getAsLong();
        List<Group> due = new ArrayList<>();
        synchronized (this) {
            groups.values().removeIf((Group group) -> {
                boolean ready = now - group.last >= QUIET.toNanos() || now - group.first >= LONGEST.toNanos();
                if (ready) due.add(group);
                return ready;
            });
        }
        for (Group group : due) send(group);
    }

    private void send(Group group) {
        EditorNotifySettings current = settings.get();
        if (!current.enabled()) return;
        boolean failed = group.kind == Kind.REFUSED || !group.problems.isEmpty();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("who", who(group.who));
        values.put("verb", group.verb);
        String summary = summary(group);
        values.put("summary", summary);
        String suffix = group.transactions.size() == 1 ? "(tx " + shortId(group.transactions.getFirst()) + ")"
                : group.transactions.size() > 1 ? "(" + group.transactions.size() + " changes)"
                : group.events > 1 && group.documents.isEmpty() ? "(" + group.events + " times)" : "";
        values.put("suffix", suffix);
        values.put("problems", group.problems.size() + (group.problems.size() == 1 ? " problem" : " problems"));
        String head = (group.kind == Kind.REFUSED ? "&c" : "&f") + "{who} &7{verb}" + (summary.isEmpty() ? "" : " &f{summary}");
        String body = head + (group.problems.isEmpty() || group.kind == Kind.REFUSED ? "" : "&c; {problems}");
        String tail = suffix.isEmpty() ? "" : " &8{suffix}";
        if (current.console()) log.accept("Editor: " + plain(RichText.format(body + tail, values)));
        EditorNotifySettings.Sound sound = current.sound(failed ? Kind.REFUSED : group.kind);
        Optional<EditorNotice.Line> chat = Optional.empty();
        if (current.chat()) {
            Optional<String> newest = group.transactions.isEmpty() ? Optional.empty() : Optional.of(group.transactions.getLast());
            Optional<String> rollback = newest.map((String transaction) -> "/animus rollback " + transaction);
            Optional<EditorNotice.Copy> copy = newest.map((String transaction) -> new EditorNotice.Copy(RichText.format(tail, values), transaction,
                    RichText.format("&7Click to copy &f{tx}", Map.of("tx", transaction))));
            String text = RichText.format(Palette.PREFIX + body + (copy.isPresent() ? "" : tail), values);
            chat = Optional.of(new EditorNotice.Line(text, hover(group, rollback), rollback, copy));
        }
        channel.deliver(new EditorNotice(group.who.owner(), sound.silent() ? Optional.empty() : Optional.of(sound), chat,
                current.actionBar() ? Optional.of(RichText.format(head, values)) : Optional.empty()));
    }

    private String who(Who who) {
        Optional<String> name = who.owner().flatMap(channel::name);
        if (name.isPresent()) return name.get() + "'s editor";
        return who.label().isEmpty() ? "Editor " + who.tokenId() : "Editor \"" + who.label() + "\"";
    }

    /** {@code 3 NPCs, 1 behavior}; one spawned NPC by its id; otherwise the details. */
    private static String summary(Group group) {
        if (group.documents.isEmpty()) return String.join(", ", group.details.stream().limit(3).toList());
        if ((group.kind == Kind.SPAWN || group.kind == Kind.DESPAWN) && group.documents.size() == 1) {
            String only = group.documents.iterator().next();
            return only.substring(only.indexOf(':') + 1);
        }
        // Kinds in their catalog order: NPCs first, then behaviors, GUIs and rigs.
        Map<String, Integer> counts = new java.util.TreeMap<>(java.util.Comparator.comparingInt((String kind) -> DocumentKind.parse(kind).map(Enum::ordinal).orElse(Integer.MAX_VALUE))
                .thenComparing(java.util.Comparator.naturalOrder()));
        for (String document : group.documents) counts.merge(document.substring(0, Math.max(0, document.indexOf(':'))), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        counts.forEach((String kind, Integer count) -> parts.add(count + " " + noun(kind, count)));
        return String.join(", ", parts);
    }

    private static String noun(String kind, int count) {
        String singular = switch (kind) {
            case "npc" -> "NPC";
            case "inventory" -> "chest GUI";
            case "text-animation" -> "text animation";
            case "presets" -> "GUI preset file";
            case "server-behaviors" -> "server behavior list";
            default -> kind;
        };
        return count == 1 ? singular : singular + "s";
    }

    /** One hover line: the first problem, else the rollback the click suggests, else the documents touched. */
    private static Optional<String> hover(Group group, Optional<String> rollback) {
        if (!group.problems.isEmpty()) {
            String first = group.problems.getFirst();
            String more = group.problems.size() > 1 ? " &8+" + (group.problems.size() - 1) + " more" : "";
            return Optional.of(RichText.format("&c{problem}" + more, Map.of("problem", first.length() > 160 ? first.substring(0, 160) : first)));
        }
        if (rollback.isPresent()) return Optional.of(RichText.format(Palette.PINK_LIGHT + "Click to suggest {command}", Map.of("command", rollback.get())));
        if (group.documents.isEmpty()) return Optional.empty();
        String more = group.documents.size() > SHOWN ? " &8+" + (group.documents.size() - SHOWN) + " more" : "";
        return Optional.of(RichText.format("&7{documents}" + more, Map.of("documents", String.join(", ", group.documents.stream().limit(SHOWN).toList()))));
    }

    private static String debugLine(Event event) {
        StringBuilder line = new StringBuilder("Editor ").append(event.kind().wireName()).append(' ').append(event.who().tokenId());
        if (!event.documents().isEmpty()) {
            line.append(' ').append(String.join(",", event.documents().stream().limit(SHOWN).toList()));
            if (event.documents().size() > SHOWN) line.append(" +").append(event.documents().size() - SHOWN);
        }
        if (!event.detail().isEmpty()) line.append(' ').append(event.detail());
        event.transaction().ifPresent((String transaction) -> line.append(" tx ").append(transaction));
        if (!event.problems().isEmpty()) line.append(" problems ").append(event.problems().size());
        return line.toString();
    }

    private static String shortId(String transaction) { return transaction.length() > 8 ? transaction.substring(0, 8) : transaction; }

    private static String plain(String coloured) {
        StringBuilder out = new StringBuilder();
        for (RichText.Run run : RichText.sectionRuns(coloured)) out.append(run.text());
        return out.toString();
    }
}
