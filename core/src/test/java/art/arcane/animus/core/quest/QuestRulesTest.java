package art.arcane.animus.core.quest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestRulesTest {
    private static final ZoneId UTC = ZoneOffset.UTC;

    @Test void anyOrderStagesCountEveryUnfinishedObjective() {
        QuestDocument quest = QuestDocumentTest.herbs();
        QuestDocument.Stage stage = quest.stages().get(1);
        QuestRecord.Active active = new QuestRecord.Active(1, new int[2], 0, false, 1);
        assertEquals(List.of(0, 1), QuestRules.open(stage, active));
        assertEquals(List.of(1), QuestRules.open(stage, active.counted(0, 3)));
        assertTrue(QuestRules.done(stage, active.counted(0, 3).counted(1, 2)));
    }

    @Test void orderedStagesCountOneObjectiveAtATime() {
        QuestDocument quest = QuestDocument.parse("town:herbs", QuestDocumentTest.HERBS.replace("\"order\": \"any\"", "\"order\": \"ordered\"")).quest().orElseThrow();
        QuestDocument.Stage stage = quest.stages().get(1);
        QuestRecord.Active active = new QuestRecord.Active(1, new int[2], 0, false, 1);
        assertEquals(List.of(0), QuestRules.open(stage, active));
        assertEquals(List.of(1), QuestRules.open(stage, active.counted(0, 3)));
    }

    @Test void repeatModesDecideTheWait() {
        Instant finishedAt = Instant.parse("2026-11-09T20:00:00Z");
        Optional<QuestRecord.Finished> once = Optional.of(new QuestRecord.Finished(1, finishedAt.toEpochMilli(), 3));
        QuestDocument daily = QuestDocumentTest.herbs();
        assertEquals(Optional.of(Instant.parse("2026-11-10T00:00:00Z")), QuestRules.waitUntil(daily, once, finishedAt.plusSeconds(60), UTC));
        assertEquals(Optional.empty(), QuestRules.waitUntil(daily, once, Instant.parse("2026-11-10T00:00:01Z"), UTC));
        QuestDocument never = QuestDocument.parse("town:herbs", QuestDocumentTest.HERBS.replace("{\"mode\": \"daily\"}", "{\"mode\": \"never\"}")).quest().orElseThrow();
        assertEquals(Optional.of(Instant.MAX), QuestRules.waitUntil(never, once, finishedAt.plusSeconds(999_999), UTC));
        QuestDocument cooldown = QuestDocument.parse("town:herbs", QuestDocumentTest.HERBS.replace("{\"mode\": \"daily\"}", "{\"mode\": \"cooldown\", \"seconds\": 30}")).quest().orElseThrow();
        assertTrue(QuestRules.waitUntil(cooldown, once, finishedAt.plusSeconds(29), UTC).isPresent());
        assertEquals(Optional.empty(), QuestRules.waitUntil(cooldown, once, finishedAt.plusSeconds(30), UTC));
        assertEquals(Optional.empty(), QuestRules.waitUntil(never, Optional.empty(), finishedAt, UTC));
    }

    @Test void durationsReadShort() {
        assertEquals("20s", QuestRules.duration(20));
        assertEquals("3m 10s", QuestRules.duration(190));
        assertEquals("1h 5m", QuestRules.duration(3900));
        assertEquals("2d 1h", QuestRules.duration(2 * 86_400 + 3600));
    }

    @Test void recordsRoundTrip() {
        QuestRecord record = new QuestRecord();
        record.put("town:herbs", new QuestRecord.Active(1, new int[]{3, 1}, 42, true, 1));
        record.finish("town:intro", 1000, 3);
        record.finish("town:intro", 2000, 3);
        QuestRecord read = QuestRecord.read(record.write());
        assertEquals(record.active(), read.active());
        assertEquals(Optional.of(new QuestRecord.Finished(2, 2000, 3)), read.finished("town:intro"));
        assertThrows(IllegalArgumentException.class, () -> QuestRecord.read(new com.google.gson.JsonObject()));
    }
}
