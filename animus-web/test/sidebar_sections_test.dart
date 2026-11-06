import 'package:animus_editor/logic/sidebar_sections.dart';
import 'package:test/test.dart';

String describe(List<TreeNode<String>> nodes) => nodes
    .map((TreeNode<String> node) => switch (node) {
          TreeLeaf<String>(:final String item) => item,
          TreeFolder<String>() => '${node.name}(${node.count})[${describe(node.children)}]',
        })
    .join(' ');

void main() {
  test('folderTree nests by folder and keeps the order of a sorted list', () {
    final List<TreeNode<String>> tree = folderTree<String>(
      <String>['crier', 'harbor/dockhand', 'town/baker', 'town/market/fishmonger', 'town/market/grocer', 'town/smith'],
      (String id) => id,
    );
    expect(describe(tree), 'crier harbor(1)[harbor/dockhand] town(4)[town/baker market(2)[town/market/fishmonger town/market/grocer] town/smith]');
  });

  test('folderTree makes missing parent folders', () {
    final List<TreeNode<String>> tree = folderTree<String>(<String>['a/b/c'], (String id) => id);
    expect(describe(tree), 'a(1)[b(1)[a/b/c]]');
  });

  test('collapsed keys round-trip, and unreadable storage collapses nothing', () {
    final Set<String> keys = <String>{sectionKey('npc'), folderKey('npc', 'town/market')};
    expect(decodeCollapsed(encodeCollapsed(keys)), keys);
    expect(decodeCollapsed(null), isEmpty);
    expect(decodeCollapsed('not json'), isEmpty);
    expect(decodeCollapsed('{"a": 1}'), isEmpty);
    expect(decodeCollapsed('["kind:rig", 3]'), <String>{'kind:rig'});
  });

  test('storage is per workspace', () {
    expect(collapsedStorageKey('one'), isNot(collapsedStorageKey('two')));
  });
}
