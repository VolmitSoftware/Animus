package art.arcane.animus.core.recording;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Something the recorder did at a tick besides moving. Documents list events sorted by {@link #ORDER}. */
public sealed interface RecordingEvent permits RecordingEvent.Swing, RecordingEvent.Equipment, RecordingEvent.Use {
    /** The equipment slots a player has, the only ones a recording changes. */
    List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    /** Tick, then the type key. */
    Comparator<RecordingEvent> ORDER = Comparator.comparingInt(RecordingEvent::tick).thenComparing(RecordingEvent::type);
    int MAXIMUM_COORDINATE = 30_000_000;

    int tick();

    /** The {@code type} key: {@code swing}, {@code equipment} or {@code use}. */
    String type();

    /** The main arm swung. */
    record Swing(int tick) implements RecordingEvent {
        public Swing { requireTick(tick); }
        @Override public String type() { return "swing"; }
    }

    /** A slot changed to {@code item}, or was emptied when it is empty. The item never carries {@code imported} data. */
    record Equipment(int tick, EquipmentSlot slot, Optional<NpcItem> item) implements RecordingEvent {
        public Equipment {
            requireTick(tick);
            Objects.requireNonNull(slot); Objects.requireNonNull(item);
            if (!SLOTS.contains(slot)) throw new IllegalArgumentException("A recording changes only main_hand, off_hand, head, chest, legs or feet");
            if (item.flatMap(NpcItem::imported).isPresent()) throw new IllegalArgumentException("A recorded item carries no imported data");
        }
        @Override public String type() { return "equipment"; }
    }

    /** The recorder opened or closed the door, fence gate or trapdoor at the block position. */
    record Use(int tick, int x, int y, int z, String block) implements RecordingEvent {
        public Use {
            requireTick(tick);
            Objects.requireNonNull(block);
            if (Math.abs(x) > MAXIMUM_COORDINATE || Math.abs(y) > MAXIMUM_COORDINATE || Math.abs(z) > MAXIMUM_COORDINATE)
                throw new IllegalArgumentException("Use coordinates must be within ±30000000");
            if (!NpcItem.validId(block)) throw new IllegalArgumentException("Use block must be namespace:block");
        }
        @Override public String type() { return "use"; }
    }

    private static void requireTick(int tick) { if (tick < 0) throw new IllegalArgumentException("Event tick must not be negative"); }
}
