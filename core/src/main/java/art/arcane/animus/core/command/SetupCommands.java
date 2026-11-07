package art.arcane.animus.core.command;

import art.arcane.animus.core.onboarding.Onboarding;
import art.arcane.animus.core.onboarding.SetupGuide;
import art.arcane.animus.core.onboarding.SetupState;
import art.arcane.animus.core.text.Palette;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code /animus setup}: the guided first steps, read on the file lane and answered on the actor's owner. Running it, or
 * {@code dismiss=true} from the join hint, turns the join hint off for the server.
 */
final class SetupCommands extends CommandGroup {
    static final String DISMISSED = "Join hint off for every admin; /animus setup still shows the steps";

    SetupCommands(CommandServices services) { super(services); }

    void run(CommandActor actor, boolean dismiss) {
        Onboarding onboarding = Onboarding.of(runtime());
        boolean enabled = runtime().editorSettings().enabled();
        try {
            services.files().execute(() -> {
                SetupState state = null;
                String failure = null;
                try {
                    onboarding.dismiss();
                    if (!dismiss) state = onboarding.state(enabled, Math.max(0, services.editor().revocable().size() - 1));
                } catch (IOException | RuntimeException problem) { failure = String.valueOf(problem.getMessage()); }
                SetupState shown = state;
                String refused = failure;
                if (!stopping()) actor.reply(() -> {
                    if (stopping()) return;
                    if (refused != null) actor.tell(Palette.error("Setup unavailable: " + refused));
                    else if (shown == null) actor.tell(Palette.success(DISMISSED));
                    else reply(actor, shown);
                });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    /** A player gets the click chips; the console gets the header as a reply and the steps as plain lines. */
    static void reply(CommandActor actor, SetupState state) {
        if (actor.player().isPresent()) { SetupGuide.player(state).forEach(actor::rich); return; }
        List<String> lines = SetupGuide.console(state);
        actor.tell(lines.getFirst());
        lines.subList(1, lines.size()).forEach(actor::plain);
    }
}
