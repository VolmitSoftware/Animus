import 'package:animus_editor/logic/npc/npc_display.dart';
import 'package:test/test.dart';

void main() {
  group('roundedForField', () {
    test('should round a facing to one decimal and a position to two', () {
      expect(roundedForField(-94.1972, 1), -94.2);
      expect(roundedForField(371.8506, 2), 371.85);
      expect(roundedForField(65.0625, 2), 65.06);
    });

    test('should leave whole numbers and null alone', () {
      expect(roundedForField(90, 1), 90);
      expect(roundedForField(-90.0, 1), -90.0);
      expect(roundedForField(null, 1), isNull);
    });
  });

  group('shownNumber', () {
    test('should show a coordinate to two decimals without trailing zeros', () {
      expect(shownNumber(71.24459649525852), '71.24');
      expect(shownNumber(68.0), '68');
      expect(shownNumber(8.5), '8.5');
      expect(shownNumber(-0.25), '-0.25');
    });
  });

  group('pageOf', () {
    test('should show a short list on one page', () {
      expect(pageOf(12, 0), (from: 0, to: 12, page: 0, pages: 1));
      expect(pageOf(0, 0), (from: 0, to: 0, page: 0, pages: 1));
    });

    test('should page a long list by 30 and clamp the page', () {
      expect(pageOf(64, 0), (from: 0, to: 30, page: 0, pages: 3));
      expect(pageOf(64, 2), (from: 60, to: 64, page: 2, pages: 3));
      expect(pageOf(64, 9), (from: 60, to: 64, page: 2, pages: 3));
      expect(pageOf(64, -1), (from: 0, to: 30, page: 0, pages: 3));
    });
  });
}
