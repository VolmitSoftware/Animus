import 'package:animus_editor/components/shell_sidebar.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:animus_editor/ui/editor_mode.dart';
import 'package:test/test.dart';

void main() {
  final NavJob rigs = navJobs.firstWhere((NavJob job) => job.id == 'rigs');
  final NavJob guis = navJobs.firstWhere((NavJob job) => job.id == 'guis');
  const Map<DocumentKind, int> counts = <DocumentKind, int>{DocumentKind.skin: 402, DocumentKind.menu: 1, DocumentKind.textAnimation: 12};

  test('should count rigs on the Rigs entry, or the tab it opens on when there are none', () {
    expect(jobCount(rigs, counts, EditorMode.advanced), 402, reason: 'Rigs opens on Skins (402)');
    expect(jobCount(rigs, const <DocumentKind, int>{}, EditorMode.advanced), 0);
    expect(jobCount(rigs, const <DocumentKind, int>{DocumentKind.rig: 2, DocumentKind.skin: 402}, EditorMode.advanced), 2);
  });

  test('should land on the first sub-kind that has documents', () {
    expect(jobRoute(rigs, 'ws', counts: counts), const KindListRoute('ws', DocumentKind.skin));
    expect(jobRoute(guis, 'ws', counts: counts), const KindListRoute('ws', DocumentKind.menu));
    expect(jobRoute(guis, 'ws'), const KindListRoute('ws', DocumentKind.inventory));
  });

  test('should name a sub-kind in the crumbs only when it differs from its job', () {
    expect(kindCrumb(DocumentKind.behavior), isNull);
    expect(kindCrumb(DocumentKind.serverBehaviors), isNull);
    expect(kindCrumb(DocumentKind.npc), isNull);
    expect(kindCrumb(DocumentKind.skin), 'Skins');
    expect(kindCrumb(DocumentKind.textAnimation), 'Text animations');
  });

  test('should name the behavior tabs by what they list', () {
    expect(subKindTab(DocumentKind.behavior), 'All behaviors');
    expect(subKindTab(DocumentKind.serverBehaviors), 'Server list');
    expect(subKindTab(DocumentKind.menu), 'Menus');
  });

  test('should show only the Simple jobs\' kinds in Simple', () {
    const Set<DocumentKind> simple = <DocumentKind>{
      DocumentKind.npc,
      DocumentKind.behavior,
      DocumentKind.shop,
      DocumentKind.route,
      DocumentKind.recording,
      DocumentKind.hologram,
    };
    for (final DocumentKind kind in DocumentKind.values) {
      expect(kindShown(kind, EditorMode.simple), simple.contains(kind), reason: kind.wireName);
      expect(kindShown(kind, EditorMode.advanced), isTrue, reason: kind.wireName);
    }
  });
}
