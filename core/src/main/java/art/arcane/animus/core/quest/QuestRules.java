package art.arcane.animus.core.quest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The platform-free rules of a quest: which objectives count now, when a stage is done and when a quest repeats. */
public final class QuestRules {
    private QuestRules() {}

    /** The objectives of the active stage that count progress now: the first unfinished one of an ordered stage, else every unfinished one. */
    public static List<Integer> open(QuestDocument.Stage stage, QuestRecord.Active active) {
        List<Integer> open = new ArrayList<>();
        for (int index = 0; index < stage.objectives().size(); index++) {
            if (active.count(index) >= stage.objectives().get(index).amount()) continue;
            open.add(index);
            if (stage.ordered()) break;
        }
        return open;
    }

    public static boolean done(QuestDocument.Stage stage, QuestRecord.Active active) { return open(stage, active).isEmpty(); }

    /** The active stage, or empty when the stage index is past the quest's stages (its file lost stages since). */
    public static Optional<QuestDocument.Stage> stage(QuestDocument quest, QuestRecord.Active active) {
        return active.stage() < quest.stages().size() ? Optional.of(quest.stages().get(active.stage())) : Optional.empty();
    }

    /**
     * When the quest may be taken again, after {@code finished}: never for a one-time quest, otherwise the cooldown's end
     * or the next local midnight. Empty when it may be taken now.
     */
    public static Optional<Instant> waitUntil(QuestDocument quest, Optional<QuestRecord.Finished> finished, Instant now, ZoneId zone) {
        if (finished.isEmpty()) return Optional.empty();
        Instant last = Instant.ofEpochMilli(finished.get().last());
        Instant next = switch (quest.repeat().mode()) {
            case NEVER -> Instant.MAX;
            case COOLDOWN -> last.plusSeconds(quest.repeat().seconds());
            case DAILY -> LocalDate.ofInstant(last, zone).plusDays(1).atStartOfDay(zone).toInstant();
        };
        return now.isBefore(next) ? Optional.of(next) : Optional.empty();
    }

    /** {@code 1h 5m}, {@code 3m 10s} or {@code 20s}: the time left, for messages and the journal. */
    public static String duration(long seconds) {
        long left = Math.max(1, seconds);
        long days = left / 86_400, hours = left % 86_400 / 3600, minutes = left % 3600 / 60, rest = left % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + rest + "s";
        return rest + "s";
    }
}
