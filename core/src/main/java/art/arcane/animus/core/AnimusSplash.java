package art.arcane.animus.core;

import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;

import java.util.List;
import java.util.Objects;

public final class AnimusSplash {
    private static final List<String> LOGO = List.of(
            " █████╗ ███╗   ██╗██╗███╗   ███╗██╗   ██╗███████╗",
            "██╔══██╗████╗  ██║██║████╗ ████║██║   ██║██╔════╝",
            "███████║██╔██╗ ██║██║██╔████╔██║██║   ██║███████╗",
            "██╔══██║██║╚██╗██║██║██║╚██╔╝██║██║   ██║╚════██║",
            "██║  ██║██║ ╚████║██║██║ ╚═╝ ██║╚██████╔╝███████║",
            "╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝╚═╝     ╚═╝ ╚═════╝ ╚══════╝"
    );

    private AnimusSplash() {
    }

    public static String render(RuntimeEnvironment environment, String serverDescription, boolean ready) {
        Objects.requireNonNull(environment, "environment");
        String server = SplashScreenSupport.serverVersionWithoutMcSuffix(
                Objects.requireNonNull(serverDescription, "serverDescription"));
        List<String> supported = BuildIdentity.MINECRAFT_VERSIONS;
        String supportRange = String.join(", ", supported);
        String[] details = {
                "Platform: " + environment.platform() + " | Minecraft: " + environment.minecraftVersion(),
                BuildIdentity.NAME + ", NPC Manager ["
                        + SplashScreenSupport.releaseTrain(BuildIdentity.VERSION) + " RELEASE]",
                "Version: " + BuildIdentity.VERSION,
                "By: Volmit Software (Arcane Arts) | VolmitSoftware.com | Startup: " + (ready ? "READY" : "FAILED"),
                "Server: " + server + " | MC Support: " + supportRange,
                "Java: " + SplashScreenSupport.javaMajorVersion() + " | Date: " + SplashScreenSupport.startupDate()
        };
        StringBuilder output = new StringBuilder("\n");
        for (int row = 0; row < LOGO.size(); row++) {
            output.append(LOGO.get(row)).append("   ").append(details[row]).append('\n');
        }
        return output.toString();
    }

    /**
     * The splash in Animus colours for a console that shows legacy codes: pink letters, a dark grey shadow, grey details and
     * a green READY. Legacy codes because the Spigot console renders no hex.
     */
    public static String colored(String splash) {
        StringBuilder colored = new StringBuilder();
        Palette previous = null;
        for (int index = 0; index < splash.length(); index++) {
            char glyph = splash.charAt(index);
            Palette tone = glyph == '\u2588' ? Palette.PINK : glyph >= '\u2500' && glyph <= '\u257f' ? Palette.DARK_GREY : Palette.GREY;
            if (tone != previous && !Character.isWhitespace(glyph)) {
                colored.append(RichText.SECTION).append(tone.legacy());
                previous = tone;
            }
            colored.append(glyph);
        }
        return colored.toString().replace("Startup: READY", "Startup: " + RichText.SECTION + "aREADY" + RichText.SECTION + Palette.GREY.legacy())
                + RichText.SECTION + "r";
    }
}
