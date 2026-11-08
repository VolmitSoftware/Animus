import 'dart:convert';
import 'dart:io';

import 'package:test/test.dart';

/// Every route and NPC navigation in the example packs and the fixtures
/// uses an `order` the route schema allows, so a recording or review made
/// from them starts valid.
void main() {
  test('should use only schema route orders in example packs and fixtures', () {
    final Map<String, Object?> schema = jsonDecode(File('../schema/generated/route.schema.json').readAsStringSync()) as Map<String, Object?>;
    final Map<String, Object?> properties = schema['properties']! as Map<String, Object?>;
    final Set<String> allowed = <String>{for (final Object? value in (properties['order']! as Map<String, Object?>)['enum']! as List<Object?>) value! as String};
    expect(allowed, contains('sequence'));
    final List<String> bad = <String>[];
    int checked = 0;
    void walk(Object? node, String file, String path, {required bool route}) {
      if (node is Map<String, Object?>) {
        final bool here = route || node.containsKey('points') || node.containsKey('wander');
        final Object? order = node['order'];
        if (here && order is String) {
          checked++;
          if (!allowed.contains(order)) bad.add('$file $path.order: $order');
        }
        for (final MapEntry<String, Object?> entry in node.entries) {
          walk(entry.value, file, '$path.${entry.key}', route: entry.key == 'navigation' || entry.key == 'route');
        }
      } else if (node is List<Object?>) {
        for (int index = 0; index < node.length; index++) {
          walk(node[index], file, '$path[$index]', route: false);
        }
      }
    }

    for (final String root in <String>['../examples/packs', 'test/fixtures', '../schema/fixtures']) {
      for (final FileSystemEntity file in Directory(root).listSync(recursive: true)) {
        if (file is! File || !file.path.endsWith('.json')) continue;
        final Object? json;
        try {
          json = jsonDecode(file.readAsStringSync());
        } on FormatException {
          continue;
        }
        walk(json, file.path, r'$', route: file.path.contains('/routes/') || file.path.contains('/route/'));
      }
    }
    expect(checked, greaterThan(5));
    expect(bad, isEmpty);
  });
}
