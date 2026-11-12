package com.volmit.animuscamera.motion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CursorStateTest {
	private static final long MS = 1_000_000L;

	@Test
	void hiddenUntilAScreenOpens() {
		CursorState cursor = new CursorState(1L);
		assertFalse(cursor.visible());
		cursor.screenOpened(480.0, 270.0);
		assertTrue(cursor.visible());
		cursor.screenClosed();
		assertFalse(cursor.visible());
	}

	@Test
	void firstScreenStartsInTheMiddle() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		assertEquals(new Point(240.0, 135.0), cursor.position());
	}

	@Test
	void samplingBeforeAnyScreenDoesNotPinTheCursorToTheCorner() {
		CursorState cursor = new CursorState(1L);
		cursor.sample(5L * MS);
		cursor.screenOpened(480.0, 270.0);
		assertEquals(new Point(240.0, 135.0), cursor.position());
	}

	@Test
	void nextMoveStartsWhereTheLastOneEnded() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		long first = cursor.moveTo(100.0, 60.0, 0L, 0L);
		assertEquals(new Point(100.0, 60.0), cursor.sample(first * MS));
		long start = first * MS + 500L * MS;
		cursor.moveTo(400.0, 200.0, start, 0L);
		assertEquals(new Point(100.0, 60.0), cursor.sample(start), "no jump to a parking spot");
		Point early = cursor.sample(start + 20L * MS);
		assertTrue(early.distance(new Point(100.0, 60.0)) < 10.0, "leaves from the last target: " + early);
	}

	@Test
	void interruptedMoveContinuesFromWhereTheCursorIs() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		long millis = cursor.moveTo(400.0, 40.0, 0L, 0L);
		Point halfway = cursor.sample(millis / 2L * MS);
		cursor.moveTo(20.0, 250.0, millis / 2L * MS, 0L);
		assertEquals(halfway, cursor.sample(millis / 2L * MS));
	}

	@Test
	void newScreenKeepsTheLastPosition() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		long millis = cursor.moveTo(90.0, 70.0, 0L, 0L);
		cursor.sample(millis * MS);
		cursor.screenClosed();
		cursor.screenOpened(480.0, 270.0);
		assertEquals(new Point(90.0, 70.0), cursor.position());
		assertTrue(cursor.visible());
	}

	@Test
	void screenChangeWithoutCloseKeepsThePosition() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		long millis = cursor.moveTo(300.0, 30.0, 0L, 0L);
		cursor.sample(millis * MS);
		cursor.screenOpened(480.0, 270.0);
		assertEquals(new Point(300.0, 30.0), cursor.position());
	}

	@Test
	void smallerScreenPullsTheCursorInside() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		long millis = cursor.moveTo(470.0, 260.0, 0L, 0L);
		cursor.sample(millis * MS);
		cursor.screenClosed();
		cursor.screenOpened(200.0, 100.0);
		Point inside = cursor.position();
		assertTrue(inside.x() < 200.0 && inside.y() < 100.0, inside.toString());
	}

	@Test
	void requestedDurationOverridesTheComputedOne() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		assertEquals(250L, cursor.moveTo(10.0, 10.0, 0L, 250L));
		assertEquals(CursorPath.durationMillis(new Point(10.0, 10.0).distance(new Point(400.0, 200.0))), cursor.moveTo(400.0, 200.0, 250L * MS, 0L));
	}

	@Test
	void sameSeedRepeatsTheSameMotion() {
		CursorState first = new CursorState(77L);
		CursorState second = new CursorState(1L);
		second.reseed(77L);
		for (CursorState cursor : new CursorState[]{first, second}) {
			cursor.screenOpened(480.0, 270.0);
		}

		double[][] targets = {{60.0, 40.0}, {400.0, 220.0}, {120.0, 200.0}};
		long now = 0L;
		for (double[] target : targets) {
			long a = first.moveTo(target[0], target[1], now, 0L);
			long b = second.moveTo(target[0], target[1], now, 0L);
			assertEquals(a, b);
			for (int i = 0; i <= 10; i++) {
				long at = now + a * MS * i / 10L;
				assertEquals(first.sample(at), second.sample(at));
			}

			now += a * MS + 100L * MS;
		}
	}

	@Test
	void movingReportsTheGlide() {
		CursorState cursor = new CursorState(1L);
		cursor.screenOpened(480.0, 270.0);
		long millis = cursor.moveTo(10.0, 10.0, 0L, 0L);
		assertTrue(cursor.moving(MS));
		assertFalse(cursor.moving(millis * MS + MS));
	}
}
