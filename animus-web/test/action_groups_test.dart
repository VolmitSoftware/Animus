import 'package:animus_editor/logic/inspect/action_groups.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:test/test.dart';

void main() {
  final CompletionCatalog catalog = InspectSchemas.embedded.catalog;

  test('should give every built-in capability a named group and a label', () {
    for (final CapabilitySpec spec in catalog.capabilities) {
      expect(actionGroupOrder, contains(actionGroup(spec.id)), reason: '${spec.id} needs a group');
      expect(actionLabel(spec.id), isNot(contains('-')), reason: spec.id);
    }
    expect(actionGroup('example:wave'), 'Module example');
  });

  test('should find an action or step for the words people search with', () {
    List<String> found(String query) => <String>[
          for (final CapabilitySpec spec in catalog.capabilitiesFor('action'))
            if (<String>[actionLabel(spec.id), ...actionKeywords(spec.id, spec.description)].any((String word) => word.toLowerCase().contains(query))) spec.id,
          for (final String type in flowTypes)
            if (flowKeywords(type).any((String word) => word.toLowerCase().contains(query))) 'step:$type',
        ];
    expect(found('attack'), contains('animus:combat-control'));
    expect(found('walk'), contains('animus:navigate'));
    expect(found('face'), contains('animus:look'));
    expect(found('reward'), contains('animus:give-item'));
    expect(found('wait'), contains('step:wait'));
    expect(found('looks at'), contains('animus:ray-trace'), reason: 'descriptions are searched too');
  });
}
