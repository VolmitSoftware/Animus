package com.volmit.animuscamera.motion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CursorPathTest {
	private static final double EPSILON = 1.0E-9;

	@Test
	void easingStartsAndEndsAtRest() {
		assertEquals(0.0, Easing.inOut(0.0), EPSILON);
		assertEquals(1.0, Easing.inOut(1.0), EPSILON);
		assertEquals(0.5, Easing.inOut(0.5), EPSILON);
		assertTrue(Easing.inOut(0.02) < 0.02, "slow start");
		assertTrue(Easing.inOut(0.98) > 0.98, "slow end");
	}

	@Test
	void easingIsMonotonicAndSymmetric() {
		double previous = 0.0;
		for (int i = 1; i <= 200; i++) {
			double t = i / 200.0;
			double value = Easing.inOut(t);
			assertTrue(value >= previous, "monotonic at " + t);
			assertEquals(1.0 - value, Easing.inOut(1.0 - t), EPSILON);
			previous = value;
		}
	}

	@Test
	void easingClampsOutsideTheUnitRange() {
		assertEquals(0.0, Easing.inOut(-1.0), EPSILON);
		assertEquals(1.0, Easing.inOut(3.0), EPSILON);
	}

	@Test
	void durationGrowsWithDistanceAndIsClamped() {
		long near = CursorPath.durationMillis(10.0);
		long middle = CursorPath.durationMillis(100.0);
		long far = CursorPath.durationMillis(400.0);
		assertTrue(near < middle && middle < far, near + " " + middle + " " + far);
		assertEquals(0L, CursorPath.durationMillis(0.2));
		assertEquals(CursorPath.MIN_MILLIS, CursorPath.durationMillis(1.0));
		assertEquals(CursorPath.MAX_MILLIS, CursorPath.durationMillis(1.0E6));
		assertTrue(middle >= 400L && middle <= 650L, "a slot-sized hop across a chest window reads as deliberate: " + middle);
	}

	@Test
	void pathStartsAtTheCursorAndEndsOnTheTarget() {
		CursorPath path = CursorPath.plan(new Point(10.0, 20.0), new Point(200.0, 90.0), 500L, new Random(3L));
		assertEquals(new Point(10.0, 20.0), path.at(0.0));
		assertEquals(new Point(200.0, 90.0), path.at(1.0));
		assertEquals(new Point(200.0, 90.0), path.at(1.5));
		assertEquals(new Point(10.0, 20.0), path.at(-0.5));
	}

	@Test
	void pathBendsGentlyAwayFromTheStraightLine() {
		Point from = new Point(0.0, 0.0);
		Point to = new Point(100.0, 0.0);
		for (long seed = 0L; seed < 20L; seed++) {
			CursorPath path = CursorPath.plan(from, to, 400L, new Random(seed));
			double widest = 0.0;
			for (int i = 0; i <= 100; i++) {
				widest = Math.max(widest, Math.abs(path.at(i / 100.0).y()));
			}

			assertTrue(widest > 1.0, "curved for seed " + seed + ": " + widest);
			assertTrue(widest <= 12.0, "gentle for seed " + seed + ": " + widest);
		}
	}

	@Test
	void shortMovesStopOnTheTarget() {
		CursorPath path = CursorPath.plan(new Point(0.0, 0.0), new Point(60.0, 0.0), 300L, new Random(1L));
		for (int i = 0; i <= 200; i++) {
			assertTrue(path.at(i / 200.0).x() <= 60.0 + EPSILON, "no overshoot at " + i);
		}
	}

	@Test
	void longMovesOvershootSlightlyAndSettle() {
		CursorPath path = CursorPath.plan(new Point(0.0, 0.0), new Point(300.0, 0.0), 800L, new Random(1L));
		double furthest = 0.0;
		for (int i = 0; i <= 400; i++) {
			furthest = Math.max(furthest, path.at(i / 400.0).x());
		}

		assertTrue(furthest > 301.0, "passes the target: " + furthest);
		assertTrue(furthest <= 300.0 + CursorPath.MAX_OVERSHOOT + EPSILON, "small overshoot: " + furthest);
		assertEquals(new Point(300.0, 0.0), path.at(1.0));
	}

	@Test
	void pathMovesSmoothlyWithoutJumps() {
		CursorPath path = CursorPath.plan(new Point(0.0, 0.0), new Point(300.0, 120.0), 800L, new Random(9L));
		Point previous = path.at(0.0);
		for (int i = 1; i <= 400; i++) {
			Point next = path.at(i / 400.0);
			assertTrue(previous.distance(next) < 4.0, "step " + i + " jumped " + previous.distance(next));
			previous = next;
		}
	}

	@Test
	void sameSeedGivesTheSamePath() {
		Point from = new Point(5.0, 5.0);
		Point to = new Point(250.0, 140.0);
		CursorPath first = CursorPath.plan(from, to, 600L, new Random(42L));
		CursorPath second = CursorPath.plan(from, to, 600L, new Random(42L));
		CursorPath other = CursorPath.plan(from, to, 600L, new Random(43L));
		boolean differs = false;
		for (int i = 0; i <= 50; i++) {
			assertEquals(first.at(i / 50.0), second.at(i / 50.0));
			differs |= !first.at(i / 50.0).equals(other.at(i / 50.0));
		}

		assertTrue(differs, "another seed bends another way");
	}

	@Test
	void zeroLengthMoveStaysPut() {
		CursorPath path = CursorPath.plan(new Point(40.0, 40.0), new Point(40.0, 40.0), 0L, new Random(1L));
		assertEquals(new Point(40.0, 40.0), path.at(0.5));
		assertEquals(0L, path.millis());
	}

	@Test
	void millisAreKept() {
		CursorPath path = CursorPath.plan(new Point(0.0, 0.0), new Point(80.0, 10.0), 333L, new Random(1L));
		assertEquals(333L, path.millis());
		assertNotEquals(path.at(0.25), path.at(0.75));
	}
}
