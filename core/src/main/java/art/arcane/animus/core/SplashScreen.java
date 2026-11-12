package art.arcane.animus.core;

import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The startup banner: block-letter ANIMUS art with a details column beside it. Bukkit logs it as MiniMessage through the
 * server's component logger; mod loaders log through log4j, so they get ANSI escapes or plain text.
 */
public final class SplashScreen {
    public static final String TAGLINE = "Virtual NPCs, behaviors & shops";
    public static final String EDITOR_URL = "https://animus.volmitsoftware.com";
    static final String FILL = "#b0185f";
    static final String EDGE = "#ff4fa3";
    static final String META = "#9a9a9a";
    static final String ACCENT = "#ff8cc6";
    static final String TRAIN = "#e0457b";
    static final String READY = "#55ff55";
    static final String DEGRADED = "#ff5555";
    static final List<String> ART = List.of(
            " █████╗ ███╗   ██╗██╗███╗   ███╗██╗   ██╗███████╗",
            "██╔══██╗████╗  ██║██║████╗ ████║██║   ██║██╔════╝",
            "███████║██╔██╗ ██║██║██╔████╔██║██║   ██║███████╗",
            "██╔══██║██║╚██╗██║██║██║╚██╔╝██║██║   ██║╚════██║",
            "██║  ██║██║ ╚████║██║██║ ╚═╝ ██║╚██████╔╝███████║",
            "╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝╚═╝     ╚═╝ ╚═════╝ ╚══════╝");

    /** How colour reaches the console. */
    public enum Colors {
        /** MiniMessage colour tags, for a component logger. */
        MINIMESSAGE,
        /** ANSI 24-bit escapes for a log4j terminal. */
        ANSI,
        PLAIN;

        /** ANSI for an interactive terminal unless {@code NO_COLOR} is set, plain otherwise. */
        public static Colors forTerminal(boolean terminal, String noColor) {
            return terminal && (noColor == null || noColor.isEmpty()) ? ANSI : PLAIN;
        }

        String paint(String hex) {
            return switch (this) {
                case MINIMESSAGE -> "<" + hex + ">";
                case ANSI -> {
                    int rgb = Integer.parseInt(hex.substring(1), 16);
                    yield "\u001b[38;2;" + (rgb >> 16 & 0xff) + ';' + (rgb >> 8 & 0xff) + ';' + (rgb & 0xff) + 'm';
                }
                case PLAIN -> "";
            };
        }

        /** Server-supplied text, escaped so MiniMessage never reads it as a tag. */
        String text(String value) {
            return this == MINIMESSAGE ? value.replace("\\", "\\\\").replace("<", "\\<") : value;
        }

        String reset() {
            return switch (this) {
                case MINIMESSAGE -> "<reset>";
                case ANSI -> "\u001b[0m";
                case PLAIN -> "";
            };
        }
    }

    /**
     * How the start went: {@code READY}, {@code DEGRADED} naming each subsystem that failed, or {@code FAILED} when the
     * plugin did not start at all.
     */
    public record Startup(boolean started, List<String> failed) {
        public static final Startup READY = new Startup(true, List.of());
        public static final Startup FAILED = new Startup(false, List.of());

        public Startup { failed = List.copyOf(failed); }

        /** Started, with the subsystems that did not come up. */
        public static Startup degraded(List<String> failed) { return new Startup(true, failed); }

        public boolean ready() { return started && failed.isEmpty(); }

        /** {@code READY}, {@code DEGRADED (behaviors, editor)} or {@code FAILED (the error is above)}. */
        public String label() {
            if (!started) return "FAILED (the error is above)";
            return failed.isEmpty() ? "READY" : "DEGRADED (" + String.join(", ", failed) + ")";
        }
    }

    /** @param server server software and version, such as {@code Paper 26.3-12-abc1234} */
    public record Details(String version, String server, Startup startup, int javaMajor, String date) {
        public Details {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(startup, "startup");
            Objects.requireNonNull(date, "date");
        }

        /** This build's version, Java and today's date. */
        public static Details current(String server, Startup startup) {
            return new Details(BuildIdentity.VERSION, server, startup, SplashScreenSupport.javaMajorVersion(), SplashScreenSupport.startupDate());
        }
    }

    private SplashScreen() {}

    /** The supported Minecraft range, from the first to the last build target. */
    public static String supportedMinecraft() {
        List<String> targets = BuildIdentity.MINECRAFT_VERSIONS;
        return targets.size() == 1 ? targets.getFirst() : targets.getFirst() + " - " + targets.getLast();
    }

    /** One line per art row, the details column beside rows two to six. */
    public static List<String> lines(Details details, Colors colors) {
        String meta = colors.paint(META);
        String accent = colors.paint(ACCENT);
        String status = colors.paint(details.startup().ready() ? READY : DEGRADED) + colors.text(details.startup().label());
        List<String> column = List.of("",
                accent + "   Animus, " + meta + TAGLINE + " " + colors.paint(TRAIN) + "[" + colors.text(SplashScreenSupport.releaseTrain(details.version())) + " RELEASE]",
                meta + "   Version: " + accent + colors.text(details.version()),
                meta + "   By: " + accent + "Volmit Software (Arcane Arts)" + meta + " | Web Editor: " + accent + EDITOR_URL + meta + " | Startup: " + status,
                meta + "   Server: " + accent + colors.text(details.server()) + meta + " | MC Support: " + accent + supportedMinecraft(),
                meta + "   Java: " + accent + details.javaMajor() + meta + " | Date: " + accent + details.date());
        List<String> lines = new ArrayList<>(ART.size());
        for (int row = 0; row < ART.size(); row++)
            lines.add(colorize(ART.get(row), colors.paint(FILL), colors.paint(EDGE)) + column.get(row) + colors.reset());
        return lines;
    }

    /** The whole banner as one log message; it starts on a new line so the logger prefix stands alone. */
    public static String render(Details details, Colors colors) {
        return "\n" + String.join("\n", lines(details, colors));
    }

    /** Paints each run of {@code █} with {@code fill} and each run of box-drawing glyphs with {@code edge}. */
    static String colorize(String row, String fill, String edge) {
        StringBuilder out = new StringBuilder(row.length() * 2);
        boolean inFill = false;
        boolean inEdge = false;
        for (int i = 0; i < row.length(); i++) {
            char glyph = row.charAt(i);
            if (glyph == '█') {
                if (!inFill) {
                    out.append(fill);
                    inFill = true;
                    inEdge = false;
                }
            } else if (glyph != ' ' && !inEdge) {
                out.append(edge);
                inEdge = true;
                inFill = false;
            }
            out.append(glyph);
        }
        return out.toString();
    }
}
