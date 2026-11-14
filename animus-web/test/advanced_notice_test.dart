import 'package:animus_editor/components/advanced_notice.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:test/test.dart';

ArrivedDocument _doc(DocumentKind kind, String id) => (kind: kind, id: id);

void main() {
  group('advancedNotice', () {
    test('should say nothing when Simple lists every arrived document', () {
      expect(advancedNotice(Arrival.imported, const <ArrivedDocument>[]), isNull);
      expect(advancedNotice(Arrival.imported, <ArrivedDocument>[_doc(DocumentKind.npc, 'town/baker'), _doc(DocumentKind.shop, 'town:bakery')]), isNull);
      expect(advancedNotice(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.hologram, 'town:sign')]), isNull);
    });

    test('should count an import Simple hides entirely and land on the one document', () {
      final AdvancedNotice one = advancedNotice(Arrival.imported, <ArrivedDocument>[_doc(DocumentKind.inventory, 'town:counter')])!;
      expect(one.message, '1 imported document is in Advanced.');
      expect(one.landing('ws'), const DocumentRoute('ws', DocumentKind.inventory, 'town:counter'));

      final AdvancedNotice two = advancedNotice(Arrival.imported, <ArrivedDocument>[_doc(DocumentKind.quest, 'town:hunt'), _doc(DocumentKind.inventory, 'town:counter')])!;
      expect(two.message, '2 imported documents are in Advanced.');
      expect(two.landing('ws'), const KindListRoute('ws', DocumentKind.quest), reason: 'the list of the first hidden kind that arrived');
    });

    test('should say how many of a mixed import Simple hides', () {
      expect(
        advancedNotice(Arrival.imported, <ArrivedDocument>[_doc(DocumentKind.npc, 'a'), _doc(DocumentKind.group, 'guards'), _doc(DocumentKind.shop, 'b:c')])!.message,
        '1 of 3 imported documents is in Advanced.',
      );
      expect(
        advancedNotice(Arrival.imported, <ArrivedDocument>[_doc(DocumentKind.npc, 'a'), _doc(DocumentKind.group, 'guards'), _doc(DocumentKind.rig, 'r:golem')])!.message,
        '2 of 3 imported documents are in Advanced.',
      );
    });

    test('should count what the server brought that Simple hides, server behaviors included', () {
      expect(advancedNotice(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.npc, 'a'), _doc(DocumentKind.menu, 'town:hub')])!.message, '1 new document is in Advanced.');
      expect(
        advancedNotice(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.serverBehaviors, 'server-behaviors'), _doc(DocumentKind.skin, 'abc')])!.message,
        '2 new documents are in Advanced.',
      );
    });
  });

  test('should name how many documents only Advanced lists', () {
    expect(hiddenDocumentsText(1), '1 document is in Advanced.');
    expect(hiddenDocumentsText(4), '4 documents are in Advanced.');
  });

  test('should name how many search matches only Advanced lists', () {
    expect(hiddenMatchesText(1), '1 match is in Advanced.');
    expect(hiddenMatchesText(3), '3 matches are in Advanced.');
  });

  group('hiddenOnly', () {
    test('should count hidden documents only when Simple lists none', () {
      expect(hiddenOnly(const <DocumentKind, int>{}), 0);
      expect(hiddenOnly(const <DocumentKind, int>{DocumentKind.inventory: 3, DocumentKind.quest: 2}), 5);
      expect(hiddenOnly(const <DocumentKind, int>{DocumentKind.inventory: 3, DocumentKind.npc: 1}), 0);
      expect(hiddenOnly(const <DocumentKind, int>{DocumentKind.npc: 0, DocumentKind.menu: 1}), 1);
    });

    test('should land on the first hidden kind in list order', () {
      expect(hiddenLanding('ws', const <DocumentKind, int>{DocumentKind.menu: 1, DocumentKind.group: 2}), const KindListRoute('ws', DocumentKind.group));
      expect(hiddenLanding('ws', const <DocumentKind, int>{DocumentKind.npc: 1}), isNull);
    });
  });

  group('ArrivalNotices', () {
    late DateTime now;
    late ArrivalNotices notices;

    setUp(() {
      now = DateTime(2026, 11, 14, 12);
      notices = ArrivalNotices(window: const Duration(seconds: 1), clock: () => now);
    });

    test('should count server news that comes in quick succession as one arrival', () {
      expect(notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.npc, 'a')]), isNull);
      now = now.add(const Duration(milliseconds: 200));
      expect(notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.menu, 'town:hub')])!.message, '1 new document is in Advanced.');
      now = now.add(const Duration(milliseconds: 200));
      final AdvancedNotice notice = notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.menu, 'town:shop')])!;
      expect(notice.message, '2 new documents are in Advanced.');
      expect(notice.landing('ws'), const KindListRoute('ws', DocumentKind.menu));
    });

    test('should count a document heard twice once', () {
      notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.quest, 'town:hunt')]);
      expect(notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.quest, 'town:hunt')])!.message, '1 new document is in Advanced.');
    });

    test('should start over after a pause', () {
      notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.quest, 'town:hunt')]);
      now = now.add(const Duration(seconds: 2));
      expect(notices.add(Arrival.server, <ArrivedDocument>[_doc(DocumentKind.rig, 'town:golem')])!.landing('ws'), const DocumentRoute('ws', DocumentKind.rig, 'town:golem'));
    });
  });
}
