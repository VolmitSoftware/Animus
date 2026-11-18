import 'package:animus_editor/logic/gui/text_contrast.dart';
import 'package:test/test.dart';

/// The game window's grey and its slot grey.
const int windowGrey = 0xC6C6C6;
const int slotGrey = 0x8B8B8B;

void main() {
  group('contrastRatio', () {
    test('contrastRatio_blackOnWhite_is21', () {
      expect(contrastRatio(0x000000, 0xFFFFFF), closeTo(21, 0.01));
    });

    test('contrastRatio_whiteOnSlotGrey_isUnderAa', () {
      expect(contrastRatio(0xFFFFFF, slotGrey), closeTo(3.41, 0.01));
    });

    test('contrastRatio_order_doesNotMatter', () {
      expect(contrastRatio(0xFFAA00, windowGrey), contrastRatio(windowGrey, 0xFFAA00));
    });
  });

  group('readableOutline', () {
    test('readableOutline_titleGreyOnWindow_needsNone', () {
      expect(readableOutline(0x404040, windowGrey), isNull);
    });

    test('readableOutline_goldOnWindow_takesTheGameShadowShade', () {
      final int? outline = readableOutline(0xFFAA00, windowGrey);
      expect(outline, 0x3F2A00);
      expect(contrastRatio(0xFFAA00, outline!), greaterThanOrEqualTo(4.5));
    });

    test('readableOutline_darkPurpleOnWindow_takesWhiteWhenTheShadeIsTooDark', () {
      final int? outline = readableOutline(0xAA00AA, windowGrey);
      expect(outline, 0xFFFFFF);
      expect(contrastRatio(0xAA00AA, outline!), greaterThanOrEqualTo(4.5));
    });

    test('readableOutline_everyLegacyColourOnWindow_readsAtAa', () {
      const List<int> legacy = <int>[
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, //
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
      ];
      for (final int colour in legacy) {
        final int? outline = readableOutline(colour, windowGrey);
        final double ratio = contrastRatio(colour, outline ?? windowGrey);
        expect(ratio, greaterThanOrEqualTo(4.5), reason: colour.toRadixString(16));
      }
    });
  });

  group('sceneOutline', () {
    test('sceneOutline_lightText_takesTheGameShadowShade', () {
      expect(sceneOutline(0xFFFFFF), 0x3F3F3F);
      expect(sceneOutline(0xAAAAAA), 0x2A2A2A);
    });

    test('sceneOutline_darkText_takesWhite', () {
      expect(sceneOutline(0x0000AA), 0xFFFFFF);
      expect(sceneOutline(0x000000), 0xFFFFFF);
    });

    test('sceneOutline_everyLegacyColour_readsAtAaAgainstItsOutline', () {
      const List<int> legacy = <int>[
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, //
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
      ];
      for (final int colour in legacy) {
        expect(contrastRatio(colour, sceneOutline(colour)), greaterThanOrEqualTo(4.5), reason: colour.toRadixString(16));
      }
    });
  });

  test('outlineShadow_drawsOneColourOnEverySide', () {
    expect(
      outlineShadow(0x3F3F3F, 'calc(var(--mc-px) / 2)'),
      'calc(var(--mc-px) / 2) 0 #3f3f3f, calc(-1 * calc(var(--mc-px) / 2)) 0 #3f3f3f, '
      '0 calc(var(--mc-px) / 2) #3f3f3f, 0 calc(-1 * calc(var(--mc-px) / 2)) #3f3f3f',
    );
  });
}
