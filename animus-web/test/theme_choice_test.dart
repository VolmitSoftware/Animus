import 'dart:io';

import 'package:animus_editor/logic/theme_choice.dart';
import 'package:test/test.dart';

void main() {
  group('opensLight', () {
    test('should follow the system while no theme was picked', () {
      expect(opensLight(stored: null, systemLight: true), isTrue);
      expect(opensLight(stored: null, systemLight: false), isFalse);
      expect(opensLight(stored: 'garbage', systemLight: true), isTrue, reason: 'an unreadable choice counts as none');
    });

    test('should keep a picked theme whatever the system says', () {
      expect(opensLight(stored: 'dark', systemLight: true), isFalse);
      expect(opensLight(stored: 'light', systemLight: false), isTrue);
    });
  });

  test('should make the same choice before paint in bootstrap.js', () {
    final String bootstrap = File('web/bootstrap.js').readAsStringSync();
    expect(bootstrap, contains("matchMedia('(prefers-color-scheme: light)')"));
    expect(bootstrap, isNot(contains("mode = 'dark'; }")), reason: 'no fixed dark default before a theme is picked');
  });
}
