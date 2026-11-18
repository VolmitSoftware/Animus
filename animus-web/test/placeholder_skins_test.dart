import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/npc/placeholder_skins.dart';
import 'package:test/test.dart';

const String _folder = 'web/$placeholderSkinBase';

String _entry(String fields) => '{"skins": [{$fields}]}';

void main() {
  group('placeholder index', () {
    test('should match the built-in list in the committed index.json', () {
      final List<PlaceholderSkin> committed = parsePlaceholderIndex(File('$_folder/index.json').readAsStringSync());
      expect(committed, placeholderSkins);
    });

    test('should list every look in both arm models with a 64x64 PNG on disk', () {
      expect(placeholderLooks.length, 8);
      for (final PlaceholderLook look in placeholderLooks) {
        for (final bool slim in <bool>[false, true]) {
          final PlaceholderSkin skin = placeholderSkinFor(look, slim: slim);
          expect(skin.arms, slim ? 'slim' : 'classic');
          final Uint8List png = File('$_folder/${skin.file}').readAsBytesSync();
          final ByteData header = ByteData.sublistView(png);
          expect(png.sublist(1, 4), 'PNG'.codeUnits, reason: skin.file);
          expect(header.getUint32(16), 64, reason: '${skin.file} width');
          expect(header.getUint32(20), 64, reason: '${skin.file} height');
        }
      }
    });

    test('should round-trip through placeholderIndexJson', () {
      expect(parsePlaceholderIndex(placeholderIndexJson(placeholderSkins)), placeholderSkins);
    });

    test('should read an entry with slim arms', () {
      final PlaceholderSkin skin =
          parsePlaceholderIndex(_entry('"id": "a-slim", "look": "a", "label": "A", "arms": "slim", "file": "a-slim.png"')).single;
      expect(skin.slim, isTrue);
      expect(skin.textureId, 'placeholder:a-slim');
      expect(skin.url, '$placeholderSkinBase/a-slim.png');
    });

    test('should refuse unknown arms, a missing field, a path as file and a repeated id', () {
      expect(() => parsePlaceholderIndex(_entry('"id": "a", "look": "a", "label": "A", "arms": "wide", "file": "a.png"')), throwsFormatException);
      expect(() => parsePlaceholderIndex(_entry('"id": "a", "look": "a", "arms": "classic", "file": "a.png"')), throwsFormatException);
      expect(() => parsePlaceholderIndex(_entry('"id": "a", "look": "a", "label": "A", "arms": "classic", "file": "../a.png"')), throwsFormatException);
      const String entry = '{"id": "a", "look": "a", "label": "A", "arms": "classic", "file": "a.png"}';
      expect(() => parsePlaceholderIndex('{"skins": [$entry, $entry]}'), throwsFormatException);
      expect(() => parsePlaceholderIndex('[]'), throwsFormatException);
    });
  });
}
