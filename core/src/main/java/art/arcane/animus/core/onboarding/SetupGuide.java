package art.arcane.animus.core.onboarding;

import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /animus setup} reply: a header, then numbered steps in the order a new server needs them (a found migration
 * first, then the first NPC, the toolkit and the web editor) and the quick-start page last. A step already done shows as
 * done without a click. A player gets click chips; the console gets each command written out.
 */
public final class SetupGuide {
    public static final String DOCS = ChatLine.docsUrl("20-tutorial-introduction", "");
    public static final int MAXIMUM_LINES = 6;
    static final String SETUP = "/animus setup";
    static final String CREATE = "/animus create guard";

    private enum Click { RUN, SUGGEST }

    /** {@code label} is null for a step without a chip; {@code inGame} tells the console the command needs a player. */
    private record Step(String text, boolean done, String label, Click click, String command, String hover, boolean inGame) {
        static Step done(String text) { return new Step(text, true, null, null, null, null, false); }
        static Step note(String text) { return new Step(text, false, null, null, null, null, false); }
    }

    private SetupGuide() {}

    public static List<ChatLine> player(SetupState state) {
        List<ChatLine> lines = new ArrayList<>(List.of(ChatLine.reply(header(state))));
        List<Step> steps = steps(state);
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            String number = (index + 1) + ". ";
            ChatLine line = step.done() ? ChatLine.of(Palette.DARK_GREY + number + Palette.GREY + step.text())
                    : ChatLine.of(Palette.PINK + number + Palette.TEXT + step.text());
            if (step.label() != null) line = step.click() == Click.RUN ? line.run(step.label(), step.command(), step.hover()) : line.suggest(step.label(), step.command(), step.hover());
            lines.add(line);
        }
        lines.add(ChatLine.of(Palette.GREY + "Every step in the wiki").open("Docs", DOCS, "Open the Animus wiki"));
        return lines;
    }

    /** Plain lines: the header first, each step with its command after a colon, then the quick-start address. */
    public static List<String> console(SetupState state) {
        List<String> lines = new ArrayList<>(List.of(header(state)));
        List<Step> steps = steps(state);
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            lines.add((index + 1) + ". " + step.text() + (step.command() == null ? "" : ": " + step.command() + (step.inGame() ? ", in game" : "")));
        }
        lines.add("Every step in the wiki: " + DOCS);
        return lines;
    }

    static String header(SetupState state) {
        String npcs = state.npcs() == 0 ? "no NPCs" : count(state.npcs());
        String editor = !state.editorEnabled() ? "off" : state.editorTokens() > 0 ? "paired" : "not paired";
        return "Setup | " + npcs + " | editor " + editor;
    }

    private static List<Step> steps(SetupState state) {
        List<Step> steps = new ArrayList<>();
        CitizensDropIn.Progress migration = state.migration();
        switch (migration.phase()) {
            case NONE -> {}
            case FOUND -> steps.add(new Step("Import " + migration.source().orElse("the Citizens data"), false, "Preview", Click.RUN,
                    "/animus import citizens preview", "Check what converts; nothing installs until apply", false));
            case STAGED -> steps.add(new Step("Install the staged import", false, "Apply", Click.SUGGEST,
                    "/animus import citizens apply", "Installs the NPCs and turns on what converted; press Enter to run", false));
            case DONE -> steps.add(Step.done("Citizens import done"));
        }
        if (state.npcs() == 0) steps.add(new Step("Create an NPC where you stand", false, "Create", Click.SUGGEST, CREATE, "Pick an id, then press Enter", true));
        else steps.add(Step.done(count(state.npcs()) + " ready"));
        steps.add(new Step("Set a skin and placement", false, "Toolkit", Click.RUN, "/animus tools", "Open the in-game toolkit", true));
        if (!state.editorEnabled()) steps.add(Step.note("Web editor is off; set editor.enabled = true in config.toml"));
        else if (state.editorTokens() > 0) steps.add(Step.done("Web editor paired, " + state.editorTokens() + (state.editorTokens() == 1 ? " token" : " tokens")));
        else steps.add(new Step("Edit NPCs in your browser", false, "Pair editor", Click.RUN, "/animus editor pair", "Make a single-use pairing link", false));
        return steps;
    }

    private static String count(int npcs) { return npcs + (npcs == 1 ? " NPC" : " NPCs"); }
}
