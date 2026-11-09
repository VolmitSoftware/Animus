package art.arcane.animus.core;

import art.arcane.animus.core.editor.EditorService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the startup banner reports: the subsystems that did not come up although config.toml turns them on. Behaviors load
 * on the file lane after the banner, so their failure is its own warning line instead.
 */
public final class StartupCheck {
    private StartupCheck() {}

    /**
     * @param npcsAvailable whether the NPC runtime runs
     * @param shopProblem why shop counters started without saved data, or empty
     * @param editor the running editor, or null when it could not start
     */
    public static SplashScreen.Startup check(AnimusRuntime runtime, boolean npcsAvailable, String shopProblem, EditorService editor, Consumer<String> warn) {
        List<String> failed = new ArrayList<>();
        int problems = runtime.npcs().diagnostics().size();
        if (problems > 0) {
            failed.add("NPC files");
            warn.accept(problems + (problems == 1 ? " NPC file has a problem" : " NPC files have problems") + " (listed above); /animus validate lists them again");
        }
        if (runtime.npcSettings().enabled() && !npcsAvailable) failed.add("NPCs");
        if (!shopProblem.isEmpty()) failed.add("shops");
        if (runtime.editorSettings().enabled() && (editor == null || editor.listenerFailed())) failed.add(editor == null ? "editor" : "editor listener");
        return SplashScreen.Startup.degraded(failed);
    }
}
