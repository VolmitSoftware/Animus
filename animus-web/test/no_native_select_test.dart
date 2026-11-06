// The editor draws its own dropdowns (lib/components/select). This scans
// lib/ so a native select, datalist or arcane_jaspr's native select cannot
// come back.
import 'dart:io';

import 'package:test/test.dart';

final Map<String, RegExp> forbidden = <String, RegExp>{
  'dom.select': RegExp(r'\bdom\.select\s*\('),
  'dom.datalist': RegExp(r'\bdom\.datalist\s*\('),
  'dom.option': RegExp(r'\bdom\.option\s*\('),
  'ArcaneSelect': RegExp(r'\bArcaneSelect\s*\('),
  'ArcaneNativeSelect': RegExp(r'\bArcaneNativeSelect\b'),
  'a <select> or <datalist> in markup': RegExp(r'<\s*(select|datalist)[\s>]', caseSensitive: false),
  'a colour picker input': RegExp(r'InputType\.color\b'),
};

void main() {
  test('lib/ uses no native select, datalist or picker', () {
    final List<String> found = <String>[];
    for (final FileSystemEntity entity in Directory('lib').listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart') || entity.path.endsWith('.g.dart')) continue;
      final List<String> lines = entity.readAsLinesSync();
      for (int index = 0; index < lines.length; index++) {
        final String line = lines[index];
        if (line.trimLeft().startsWith('//')) continue;
        forbidden.forEach((String name, RegExp pattern) {
          if (pattern.hasMatch(line)) found.add('${entity.path}:${index + 1}: $name');
        });
      }
    }
    expect(found, isEmpty, reason: 'Use AnimusSelect, GuiSelect or SuggestInput from lib/components/select instead.');
  });

  test('the scan catches what it looks for', () {
    expect(forbidden['dom.select']!.hasMatch('return dom.select('), isTrue);
    expect(forbidden['dom.datalist']!.hasMatch('dom.datalist(id: list, <Widget>[])'), isTrue);
    expect(forbidden['ArcaneSelect']!.hasMatch('ArcaneSelect(size: ComponentSize.sm)'), isTrue);
    expect(forbidden['a <select> or <datalist> in markup']!.hasMatch("'<select name=a>'"), isTrue);
    expect(forbidden['dom.select']!.hasMatch('_select(name, args)'), isFalse);
  });
}
