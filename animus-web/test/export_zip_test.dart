import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder_export.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

DateTime _entryTime(Uint8List zip) {
  final ByteData data = ByteData.sublistView(zip);
  final int time = data.getUint16(10, Endian.little);
  final int date = data.getUint16(12, Endian.little);
  return DateTime(1980 + (date >> 9), (date >> 5) & 0xf, date & 0x1f, time >> 11, (time >> 5) & 0x3f, (time & 0x1f) * 2);
}

void main() {
  group('zipFiles', () {
    final Map<String, Uint8List> files = <String, Uint8List>{'a.json': Uint8List.fromList(utf8.encode('{}'))};

    test('should stamp entries with the given export time', () {
      expect(_entryTime(zipFiles(files, modified: DateTime(2026, 5, 1, 10, 20, 30))), DateTime(2026, 5, 1, 10, 20, 30));
    });

    test('should stamp entries with the current time by default', () {
      final DateTime before = DateTime.now().subtract(const Duration(seconds: 2));
      final DateTime stamped = _entryTime(zipFiles(files));
      expect(stamped.isAfter(before), isTrue);
      expect(stamped.year, DateTime.now().year);
    });
  });

  group('exportSummary', () {
    Uint8List bytes() => Uint8List.fromList(<int>[1]);

    test('should count pack files that are exported', () {
      final WorkspaceSnapshot workspace = WorkspaceSnapshot(
        id: 'w',
        name: 'w',
        generation: 1,
        documents: const [],
        resources: <String, Uint8List>{'pack/assets/animus/items/a.json': bytes(), 'pack/assets/animus/models/item/a.json': bytes()},
      );
      expect(exportSummary('w.zip', workspace), 'Saved w.zip: 0 documents, 2 pack files. Unzip over plugins/Animus.');
    });

    test('should say pack file for one and leave it out for none', () {
      final WorkspaceSnapshot one = WorkspaceSnapshot(id: 'w', name: 'w', generation: 1, documents: const [], resources: <String, Uint8List>{'pack/assets/x.png': bytes()});
      expect(exportSummary('w.zip', one), contains('1 pack file.'));
      expect(exportSummary('w.zip', const WorkspaceSnapshot(id: 'w', name: 'w', generation: 1, documents: [])), isNot(contains('pack')));
    });
  });
}
