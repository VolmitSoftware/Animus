package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class DisplayTransformTest {
    @Test
    void decompositionReproducesRotatedNonUniformAndShearedMatrices() {
        Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            Affine matrix = Affine.translation(random.nextDouble() * 4 - 2, random.nextDouble() * 4, random.nextDouble() * 4 - 2)
                    .times(Affine.rotation(new double[]{random.nextDouble() * 360, random.nextDouble() * 360, random.nextDouble() * 360}))
                    .times(Affine.scale(0.1 + random.nextDouble() * 2, 0.1 + random.nextDouble() * 2, 0.1 + random.nextDouble() * 2))
                    .times(Affine.rotation(new double[]{random.nextDouble() * 90, random.nextDouble() * 90, 0}))
                    .times(Affine.scale(1, 0.5 + random.nextDouble(), 1));
            DisplayTransform transform = DisplayTransform.of(matrix);
            assertTrue(transform.matrix().distance(matrix) < 1e-5, () -> matrix + " became " + transform.matrix());
        }
    }

    @Test
    void mirroredMatrixKeepsItsHandedness() {
        Affine mirrored = Affine.rotationY(30).times(Affine.scale(-1, 2, 0.5));
        assertTrue(DisplayTransform.of(mirrored).matrix().distance(mirrored) < 1e-5);
    }

    @Test
    void hiddenBoneHasZeroScaleAndIdentityRotations() {
        DisplayTransform hidden = DisplayTransform.hidden(Affine.translation(1, 2, 3));
        assertEquals(0, hidden.sx()); assertEquals(0, hidden.sy()); assertEquals(0, hidden.sz());
        assertEquals(1, hidden.lw()); assertEquals(1, hidden.rw());
        assertEquals(2, hidden.ty());
        DisplayTransform collapsed = DisplayTransform.of(Affine.translation(1, 2, 3).times(Affine.scale(0, 0, 0)));
        assertTrue(collapsed.matrix().distance(Affine.translation(1, 2, 3).times(Affine.scale(0, 0, 0))) < 1e-6);
    }

    @Test
    void offsetMovesOnlyTheTranslation() {
        DisplayTransform moved = DisplayTransform.of(Affine.rotationX(45)).offset(0, -1.8, 0);
        assertEquals(-1.8, moved.ty(), 1e-6);
        assertTrue(moved.matrix().linearDistance(Affine.rotationX(45)) < 1e-6);
    }
}
