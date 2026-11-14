// The kit's pure parts: the Mode value, natural ordering, the library
// list's search, filters, sort and windowing at real scale, Recent, and
// every save bar state.
import 'package:animus_editor/ui/editor_mode.dart';
import 'package:animus_editor/ui/library_query.dart';
import 'package:animus_editor/ui/natural_order.dart';
import 'package:animus_editor/ui/recent.dart';
import 'package:animus_editor/ui/save_state.dart';
import 'package:animus_editor/ui/tone.dart';
import 'package:test/test.dart';

List<LibraryRow> manyRows(int count) => <LibraryRow>[
      for (int index = count; index > 0; index--)
        LibraryRow(
          key: 'npc:town/courier-$index',
          title: 'Courier $index',
          meta: index.isEven ? 'Villager, world' : 'Player, world_nether',
          tags: <String>{if (index % 10 == 0) 'problems', index.isEven ? 'spawned' : 'despawned'},
          badges: <LibraryBadge>[if (index % 10 == 0) const LibraryBadge('1 problem', tone: BadgeTone.error)],
        ),
    ];

void main() {
  group('Mode', () {
    test('should default to Simple and read only a stored Advanced', () {
      expect(parseMode(null), EditorMode.simple);
      expect(parseMode('advanced'), EditorMode.advanced);
      expect(parseMode('simple'), EditorMode.simple);
      expect(parseMode('Advanced'), EditorMode.simple);
      expect(parseMode('expert'), EditorMode.simple);
      expect(modeStorageKey, 'animus.mode');
      expect(EditorMode.values.map((EditorMode mode) => mode.label), <String>['Simple', 'Advanced']);
    });
  });

  group('natural order', () {
    test('should sort numbered names by their numbers', () {
      final List<String> names = <String>['Courier 19', 'Courier 4', 'Courier 14', 'Courier 9']..sort(naturalCompare);
      expect(names, <String>['Courier 4', 'Courier 9', 'Courier 14', 'Courier 19']);
    });

    test('should ignore case first and keep long digit runs exact', () {
      expect(naturalCompare('baker', 'Baker') != 0, isTrue);
      expect(naturalCompare('apple', 'Banana'), lessThan(0));
      expect(naturalCompare('id 99999999999999999998', 'id 99999999999999999999'), lessThan(0));
      expect(naturalCompare('v007', 'v7'), greaterThan(0));
      expect(naturalCompare('route', 'route 2'), lessThan(0));
    });
  });

  group('library rows', () {
    final List<LibraryRow> rows = manyRows(1500);

    test('should natural-sort 1500 rows by title', () {
      final List<LibraryRow> sorted = queryRows(rows);
      expect(sorted.take(4).map((LibraryRow row) => row.title), <String>['Courier 1', 'Courier 2', 'Courier 3', 'Courier 4']);
      expect(sorted.last.title, 'Courier 1500');
    });

    test('should match every search word and any picked filter', () {
      expect(queryRows(rows, search: 'courier 14').map((LibraryRow row) => row.title), contains('Courier 14'));
      expect(queryRows(rows, search: 'villager 150').every((LibraryRow row) => row.meta!.startsWith('Villager')), isTrue);
      expect(queryRows(rows, filters: <String>{'problems'}).length, 150);
      expect(queryRows(rows, filters: <String>{'spawned', 'despawned'}).length, 1500);
      expect(queryRows(rows, search: 'nobody'), isEmpty);
    });

    test('should sort by a named value and fall back to the title', () {
      final List<LibraryRow> some = <LibraryRow>[
        const LibraryRow(key: 'a', title: 'B', sortValues: <String, String>{'world': 'world_nether'}),
        const LibraryRow(key: 'b', title: 'A', sortValues: <String, String>{'world': 'world'}),
        const LibraryRow(key: 'c', title: 'C'),
      ];
      expect(queryRows(some, sort: 'world').map((LibraryRow row) => row.key), <String>['c', 'b', 'a']);
    });

    test('should filter and sort 1500 rows quickly', () {
      final Stopwatch watch = Stopwatch()..start();
      for (int round = 0; round < 10; round++) {
        queryRows(rows, search: 'courier ${round + 1}', filters: <String>{'spawned'});
      }
      expect(watch.elapsedMilliseconds / 10, lessThan(50), reason: 'one keystroke of filtering');
    });

    test('should draw only the rows near the viewport', () {
      final (int first, int end) = visibleRange(count: 1500, rowHeight: 56, scrollTop: 0, viewport: 900);
      expect(first, 0);
      expect(end - first, lessThan(60));
      final (int middle, int middleEnd) = visibleRange(count: 1500, rowHeight: 56, scrollTop: 56.0 * 700, viewport: 900);
      expect(middle, 694);
      expect(middleEnd - middle, lessThan(60));
      expect(visibleRange(count: 1500, rowHeight: 56, scrollTop: 56.0 * 1499, viewport: 900).$2, 1500);
      expect(visibleRange(count: 0, rowHeight: 56, scrollTop: 0, viewport: 900), (0, 0));
    });
  });

  group('recent', () {
    test('should keep the newest six without repeats', () {
      List<String> recent = const <String>[];
      for (final String key in <String>['npc:a', 'npc:b', 'shop:c', 'npc:a', 'npc:d', 'npc:e', 'npc:f', 'npc:g']) {
        recent = pushRecent(recent, key);
      }
      expect(recent, <String>['npc:g', 'npc:f', 'npc:e', 'npc:d', 'npc:a', 'shop:c']);
      expect(decodeRecent(encodeRecent(recent)), recent);
      expect(decodeRecent('not json'), isEmpty);
      expect(decodeRecent('{"a":1}'), isEmpty);
      expect(decodeRecent(null), isEmpty);
    });
  });

  group('save bar, local workspace', () {
    test('should say the edit is kept, saving, saved after Mod+S, or why not', () {
      expect(localSaveState(saving: false, unsaved: false).message, 'Saved in this browser');
      expect(localSaveState(saving: false, unsaved: false).primary, isNull);
      expect(localSaveState(saving: true, unsaved: false).message, 'Saving...');
      expect(localSaveState(saving: false, unsaved: false, flashed: true).message, 'Saved');
      final SaveBarState refused = localSaveState(saving: false, unsaved: true, problem: 'The browser is out of space.');
      expect(refused.message, 'Not saved: The browser is out of space.');
      expect(refused.tone, SaveTone.failed);
      expect(refused.primary, SaveAction.saveAgain);
      expect(refused.secondary, <SaveAction>[SaveAction.exportZip]);
      expect(localSaveState(saving: false, unsaved: false, canSend: true).secondary, <SaveAction>[SaveAction.sendToServer]);
    });
  });

  group('save bar, server workspace', () {
    test('should offer Apply for changes and say when it is up to date', () {
      expect(serverSaveState(link: ServerLink.online, changes: 0).message, 'Up to date');
      final SaveBarState three = serverSaveState(link: ServerLink.online, changes: 3);
      expect(three.message, '3 changes not applied');
      expect(three.primary, SaveAction.apply);
      expect(three.secondary, <SaveAction>[SaveAction.review]);
      expect(serverSaveState(link: ServerLink.online, changes: 1).message, '1 change not applied');
    });

    test('should offer Review only when there is something to review', () {
      expect(serverSaveState(link: ServerLink.online, changes: 0).secondary, isEmpty);
      expect(serverSaveState(link: ServerLink.online, changes: 0, reading: true).secondary, isEmpty);
      expect(serverSaveState(link: ServerLink.online, changes: 0, loadDone: 3, loadTotal: 9).secondary, isEmpty);
      expect(serverSaveState(link: ServerLink.online, changes: 0, undone: 1).secondary, isEmpty);
      expect(serverSaveState(link: ServerLink.reconnecting, changes: 0).secondary, isEmpty);
      expect(serverSaveState(link: ServerLink.reconnecting, changes: 2).secondary, <SaveAction>[SaveAction.review]);
      expect(serverSaveState(link: ServerLink.offline, changes: 0).secondary, isEmpty);
      expect(serverSaveState(link: ServerLink.online, changes: 0, applied: 2).secondary, <SaveAction>[SaveAction.undoApply]);
      expect(serverSaveState(link: ServerLink.online, changes: 0, history: true).secondary, <SaveAction>[SaveAction.review], reason: 'Advanced keeps the history and log in Review');
    });

    test('should report applying, applied with Undo, and a failure with Retry', () {
      expect(serverSaveState(link: ServerLink.online, changes: 2, applying: true).message, 'Applying...');
      final SaveBarState done = serverSaveState(link: ServerLink.online, changes: 0, applied: 3);
      expect(done.message, 'Applied 3 changes');
      expect(done.secondary.first, SaveAction.undoApply);
      final SaveBarState failed = serverSaveState(link: ServerLink.online, changes: 1, failure: 'The server refused the change set.');
      expect(failed.message, 'Apply failed: The server refused the change set.');
      expect(failed.tone, SaveTone.failed);
      expect(failed.primary, SaveAction.retry);
      expect(serverSaveState(link: ServerLink.online, changes: 0, failure: 'old').message, 'Up to date');
      final SaveBarState undone = serverSaveState(link: ServerLink.online, changes: 0, undone: 2);
      expect(undone.message, 'Undid 2 changes');
      expect(undone.secondary, isEmpty);
    });

    test('should offer no Retry for a refusal a retry cannot fix', () {
      final SaveBarState refused = serverSaveState(link: ServerLink.online, changes: 1, failure: 'This pairing lacks the Run commands permission.', retry: false);
      expect(refused.message, 'Apply failed: This pairing lacks the Run commands permission.');
      expect(refused.tone, SaveTone.failed);
      expect(refused.primary, isNull);
      expect(refused.secondary, <SaveAction>[SaveAction.review]);
    });

    test('should say it is reading the server until its catalog is here', () {
      final SaveBarState reading = serverSaveState(link: ServerLink.online, changes: 0, reading: true);
      expect(reading.message, 'Reading the server...');
      expect(reading.tone, SaveTone.busy);
      expect(reading.primary, isNull);
      final SaveBarState failed = serverSaveState(link: ServerLink.online, changes: 0, catalogFailed: true);
      expect(failed.message, 'Could not read the server');
      expect(failed.primary, SaveAction.retryCatalog);
      expect(serverSaveState(link: ServerLink.online, changes: 1, reading: true).primary, SaveAction.apply);
    });

    test('should keep changes through every connection state', () {
      final SaveBarState offline = serverSaveState(link: ServerLink.offline, changes: 2);
      expect(offline.message, 'Server offline. Changes are kept.');
      expect(offline.primary, SaveAction.reconnect);
      expect(serverSaveState(link: ServerLink.reconnecting, changes: 2, linkMessage: 'Server restarting. Reconnecting.').message, 'Server restarting. Reconnecting. 2 changes kept.');
      expect(serverSaveState(link: ServerLink.reconnecting, changes: 0).message, 'Reconnecting.');
      final SaveBarState ended = serverSaveState(link: ServerLink.needsPairing, changes: 2);
      expect(ended.primary, SaveAction.pairAgain);
      expect(ended.secondary, isEmpty);
      expect(ended.message, 'Pairing ended. 2 changes kept.');
      expect(ended.tone, SaveTone.failed, reason: 'no pink dot: nothing can be applied');
      final SaveBarState otherTab = serverSaveState(link: ServerLink.otherTab, changes: 4);
      expect(otherTab.message, 'Open in another tab.');
      expect(otherTab.primary, SaveAction.takeOver);
      expect(serverSaveState(link: ServerLink.online, changes: 1, stale: 1).message, '1 change conflicts with the server');
      expect(serverSaveState(link: ServerLink.online, changes: 3, stale: 2).message, '2 changes conflict with the server');
    });

    test('should compare equal when it would show the same bar', () {
      expect(serverSaveState(link: ServerLink.online, changes: 3), serverSaveState(link: ServerLink.online, changes: 3));
      expect(serverSaveState(link: ServerLink.online, changes: 3) == serverSaveState(link: ServerLink.online, changes: 4), isFalse);
    });

    test('should show one loading line and one result with Retry', () {
      final SaveBarState loading = serverSaveState(link: ServerLink.online, changes: 0, loadDone: 340, loadTotal: 1169);
      expect(loading.message, 'Loading 340 of 1169');
      expect(loading.tone, SaveTone.busy);
      expect(serverSaveState(link: ServerLink.online, changes: 2, loadDone: 3, loadTotal: 9).message, '2 changes not applied. Loading 3 of 9');
      expect(serverSaveState(link: ServerLink.online, changes: 0, loadDone: 9, loadTotal: 9).message, 'Up to date');
      final SaveBarState failed = serverSaveState(link: ServerLink.online, changes: 0, loadFailed: 2);
      expect(failed.message, '2 documents could not load');
      expect(failed.primary, SaveAction.retryLoads);
      expect(serverSaveState(link: ServerLink.online, changes: 1, loadFailed: 2).primary, SaveAction.apply);
    });
  });
}
