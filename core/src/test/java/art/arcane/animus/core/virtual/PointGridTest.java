package art.arcane.animus.core.virtual;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointGridTest {
    @Test
    void aPointIsFoundOnlyWithinItsOwnReachNearestFirst() {
        PointGrid<String> grid = new PointGrid<>(List.of(
                new PointGrid.Point<>("world", 0, 64, 0, 10, "near"),
                new PointGrid.Point<>("world", 40, 64, 0, 48, "far but long reach"),
                new PointGrid.Point<>("world", 30, 64, 0, 8, "far and short reach"),
                new PointGrid.Point<>("nether", 0, 64, 0, 64, "other world")));
        assertEquals(List.of("near", "far but long reach"), grid.reaching("world", 2, 64, 0, 10));
        assertEquals(List.of("near"), grid.reaching("world", 2, 64, 0, 1), "the limit keeps the nearest");
        assertEquals(List.of(), grid.reaching("end", 0, 64, 0, 10));
        assertEquals(List.of("far but long reach", "far and short reach"), grid.reaching("world", 36, 64, 0, 10));
    }

    @Test
    void negativeCoordinatesAndChunkEdgesMatchADirectCheck() {
        Random random = new Random(7);
        List<PointGrid.Point<Integer>> points = new ArrayList<>();
        for (int i = 0; i < 400; i++)
            points.add(new PointGrid.Point<>("world", random.nextDouble(-300, 300), 64, random.nextDouble(-300, 300), random.nextDouble(4, 64), i));
        PointGrid<Integer> grid = new PointGrid<>(points);
        for (int probe = 0; probe < 200; probe++) {
            double x = random.nextDouble(-320, 320), z = random.nextDouble(-320, 320);
            List<Integer> expected = new ArrayList<>();
            for (PointGrid.Point<Integer> point : points) {
                double dx = point.x() - x, dz = point.z() - z;
                if (dx * dx + dz * dz <= point.reach() * point.reach()) expected.add(point.value());
            }
            List<Integer> found = new ArrayList<>(grid.reaching("world", x, 64, z, 1000));
            found.sort(Integer::compare); expected.sort(Integer::compare);
            assertEquals(expected, found, "probe at " + x + ", " + z);
        }
    }

    @Test
    void anInvalidPointIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PointGrid.Point<>("world", Double.NaN, 0, 0, 4, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PointGrid.Point<>("world", 0, 0, 0, -1, "x"));
    }
}
