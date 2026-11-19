package art.arcane.animus.core.quest;

import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.plugin.LegacyLoreLayout;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** The words quests show players: objective lines, default messages and wrapped lore. {@code &} codes, coloured by the caller. */
public final class QuestText {
    public static final String HEADER = "&6Quest &8» &r";
    private static final int LORE_WIDTH = 36;

    private QuestText() {}

    /** The objective as one line: its own description, or one built from it. */
    public static String objective(QuestDocument.Objective objective, Function<String, String> npcName) {
        if (objective.description().isPresent()) return objective.description().get();
        String npc = objective.npc().map(npcName).orElse("");
        return switch (objective.type()) {
            case TALK -> "Talk to " + npc;
            case BRING -> "Bring " + objective.amount() + " " + item(objective.item().orElseThrow().id()) + " to " + npc;
            case KILL -> objective.entity().map((String entity) -> "Defeat " + objective.amount() + " " + item(entity)).orElse("Defeat " + npc);
            case REACH -> objective.point().map((QuestDocument.Point point) -> "Go to " + Math.round(point.x()) + ", " + Math.round(point.y()) + ", " + Math.round(point.z())).orElse("Go there");
            case COLLECT -> "Collect " + objective.amount() + " " + item(objective.item().orElseThrow().id());
            case CUSTOM -> objective.id().replace('_', ' ').replace('-', ' ');
        };
    }

    /** {@code 2/3} for counted objectives, empty for talk and reach. */
    public static String count(QuestDocument.Objective objective, int count) {
        if (objective.type() == QuestDocument.Type.TALK || objective.type() == QuestDocument.Type.REACH) return "";
        return " " + Math.min(count, objective.amount()) + "/" + objective.amount();
    }

    /** One journal or progress line, ticked when done. */
    public static String line(QuestDocument.Objective objective, int count, Function<String, String> npcName) {
        boolean done = count >= objective.amount();
        return (done ? "&a✔ &7&m" : "&e• &f") + objective(objective, npcName) + (done ? "" : "&7" + count(objective, count));
    }

    /** {@code minecraft:wheat_seeds} reads as {@code wheat seeds}. */
    public static String item(String id) {
        int colon = id.indexOf(':');
        return (colon < 0 ? id : id.substring(colon + 1)).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    public static String fill(String text, String player, QuestDocument quest) { return text.replace("{player}", player).replace("{quest}", RichText.plain(RichText.colorize(quest.title()))); }

    /**
     * Words wrapped at about {@value #LORE_WIDTH} columns, starting in {@code colour}; a colour the text sets carries onto
     * the lines after it. The lines come back section-coded.
     */
    public static List<String> wrap(String text, String colour) {
        if (text.isBlank()) return List.of();
        return LegacyLoreLayout.wrap(RichText.colorize(colour + String.join(" ", text.trim().split("\\s+"))), LORE_WIDTH);
    }
}
