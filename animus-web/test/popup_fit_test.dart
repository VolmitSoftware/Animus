import 'package:animus_editor/logic/select/popup_fit.dart';
import 'package:test/test.dart';

void main() {
  // A 1440 x 600 window with a 52 px top bar.
  PopupFit fit(double top, double bottom, {double wanted = 256, bool scroll = true}) => fitPopup(
        PopupBox(top: top, bottom: bottom, viewHeight: 600, topInset: 52),
        wanted,
        allowScroll: scroll,
      );

  test('fitPopup opens below when the list fits under the field', () {
    final PopupFit placed = fit(100, 136);
    expect(placed.up, isFalse);
    expect(placed.scroll, 0);
    expect(placed.maxHeight, 256);
  });

  test('fitPopup flips above only when it fits under the top bar', () {
    final PopupFit placed = fit(400, 436);
    expect(placed.up, isTrue);
    expect(placed.scroll, 0);
    // The list's top edge stays at or below the top bar.
    expect(400 - 2 - placed.maxHeight, greaterThanOrEqualTo(52));
  });

  test('fitPopup scrolls the field up when neither side has room', () {
    // Field near the bottom with a list too tall for the space above.
    final PopupFit placed = fit(300, 336, wanted: 360);
    expect(placed.up, isFalse);
    expect(placed.scroll, greaterThan(0));
    // After the scroll the field is still below the top bar.
    expect(300 - placed.scroll, greaterThanOrEqualTo(52));
  });

  test('fitPopup opens a field at the bottom edge fully on screen', () {
    final PopupFit placed = fit(560, 596);
    expect(placed.up, isTrue);
    expect(placed.maxHeight, 256);
    expect(560 - 2 - placed.maxHeight, greaterThanOrEqualTo(52));
  });

  test('fitPopup without scrolling never covers the top bar', () {
    for (double top = 60; top < 560; top += 20) {
      final PopupFit placed = fit(top, top + 36, wanted: 360, scroll: false);
      expect(placed.scroll, 0);
      if (placed.up) {
        expect(top - 2 - placed.maxHeight, greaterThanOrEqualTo(52), reason: 'top $top');
      } else {
        expect(top + 36 + 2 + placed.maxHeight, lessThanOrEqualTo(600), reason: 'top $top');
      }
    }
  });
}
