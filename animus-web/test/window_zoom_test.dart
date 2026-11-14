// The zoom a game window gets in its frame: its natural size when it fits,
// otherwise the largest step of whole device pixels per game pixel that
// fits, never above 1; when that step would leave slots too small to tap,
// the window fills the room with whole device pixels per slot instead
// whenever that is larger.
// A height cap (the band above a form, or part of the window at the side)
// scales the window down the same way, so it is never cropped.
import 'package:animus_editor/components/gui/window_zoom.dart';
import 'package:test/test.dart';

void main() {
  test('should keep the natural size when the window fits', () {
    expect(windowZoom(natural: 534, room: 640, dpr: 1), 1);
    expect(windowZoom(natural: 534, room: 534, dpr: 2), 1);
  });

  test('should never scale above the natural size', () {
    for (final double room in <double>[100, 300, 533, 534, 1000, 5000]) {
      expect(windowZoom(natural: 534, room: room, dpr: 2), lessThanOrEqualTo(1));
    }
  });

  test('should step in whole device pixels per game pixel while slots stay tappable', () {
    // 3 CSS px per game pixel at the natural size.
    expect(windowZoom(natural: 534, room: 500, dpr: 1), closeTo(2 / 3, 1e-9));
    expect(windowZoom(natural: 534, room: 500, dpr: 2), closeTo(5 / 6, 1e-9));
    for (final double dpr in <double>[1, 1.25, 1.5, 2, 3]) {
      for (double room = 60; room < 534; room += 7) {
        final double zoom = windowZoom(natural: 534, room: room, dpr: dpr);
        if (zoom * mcSlotPixels < minSlotPixels) continue;
        final double devicePerGamePixel = zoom * mcGuiPixel * dpr;
        final double devicePerSlot = zoom * mcSlotPixels * dpr;
        final bool crisp = (devicePerGamePixel - devicePerGamePixel.round()).abs() < 1e-9;
        final bool filled = (devicePerSlot - devicePerSlot.round()).abs() < 1e-9 && (zoom + 1 / (mcSlotPixels * dpr)) * 534 > room;
        expect(crisp || filled, isTrue, reason: 'room $room at $dpr');
      }
    }
  });

  test('should keep slots tappable on a phone by filling the width', () {
    // 334 px (a 390 px phone): crisp steps would give 18, 27 or 30 px slots.
    for (final double dpr in <double>[1, 2, 3]) {
      final double zoom = windowZoom(natural: 534, room: 334, dpr: dpr);
      expect(zoom * mcSlotPixels, greaterThanOrEqualTo(minSlotPixels), reason: 'at $dpr');
      expect(534 * zoom, lessThanOrEqualTo(334));
      final double devicePerSlot = zoom * mcSlotPixels * dpr;
      expect((devicePerSlot - devicePerSlot.round()).abs(), lessThan(1e-9), reason: 'whole device pixels per slot at $dpr');
    }
  });

  test('should fit the room it is given', () {
    for (final double dpr in <double>[1, 2, 3]) {
      for (double room = 180; room < 534; room += 11) {
        expect(534 * windowZoom(natural: 534, room: room, dpr: dpr), lessThanOrEqualTo(room + 1e-9), reason: 'room $room at $dpr');
      }
    }
  });

  test('should keep one step when even that does not fit', () {
    expect(windowZoom(natural: 534, room: 40, dpr: 1), closeTo(1 / 3, 1e-9));
    expect(windowZoom(natural: 534, room: 0, dpr: 2), closeTo(1 / 6, 1e-9));
  });

  test('should fill the room when the crisp step leaves slots small', () {
    // 200 px: the crisp step gives 18 px slots, filling gives 20 px ones.
    expect(windowZoom(natural: 534, room: 200, dpr: 1), closeTo(20 / 54, 1e-9));
  });

  test('should leave an unmeasured window alone', () {
    expect(windowZoom(natural: 0, room: 300, dpr: 2), 1);
  });

  test('should fit a height cap as well as the width', () {
    // A trading screen of 18 offers: 1260 px tall, 534 wide.
    for (final double dpr in <double>[1, 2, 3]) {
      for (double cap = 120; cap < 1300; cap += 37) {
        final double zoom = windowZoom(natural: 534, room: 1000, naturalHeight: 1260, roomHeight: cap, dpr: dpr);
        final double oneStep = 1 / (mcGuiPixel * dpr);
        expect(1260 * zoom, lessThanOrEqualTo(cap < 1260 * oneStep ? 1260 * oneStep + 1e-9 : cap + 1e-9), reason: 'cap $cap at $dpr');
        expect(zoom, lessThanOrEqualTo(1));
      }
    }
  });

  test('should take the tighter of width and height', () {
    expect(windowZoom(natural: 534, room: 400, naturalHeight: 228, roomHeight: 1000, dpr: 1), windowZoom(natural: 534, room: 400, dpr: 1));
    expect(windowZoom(natural: 534, room: 1000, naturalHeight: 546, roomHeight: 460, dpr: 2), closeTo(5 / 6, 1e-9));
    expect(windowZoom(natural: 534, room: 1000, naturalHeight: 228, roomHeight: 500, dpr: 2), 1);
  });

  test('should give the window what the frame cap leaves after its header and status', () {
    expect(windowRoomHeight(band: true, viewportHeight: 1024, chrome: 80), bandFrameHeight - 80);
    expect(windowRoomHeight(band: false, viewportHeight: 900, chrome: 80), 900 * sideFrameShare - 80);
    expect(windowRoomHeight(band: true, viewportHeight: 1024, chrome: 400), minWindowRoom, reason: 'never less than a readable window');
  });
}
