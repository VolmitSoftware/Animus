import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:test/test.dart';

CapabilitySpec spec(List<FieldSpec> arguments, {List<List<String>> oneOf = const <List<String>>[]}) => CapabilitySpec(
      id: 'test:pick',
      kind: 'action',
      description: '',
      privileged: false,
      available: true,
      reason: 'available',
      arguments: arguments,
      oneOfRequired: oneOf,
    );

void main() {
  test('firstToFill_requiredArgument_isTheFirstRequiredOne', () {
    final CapabilitySpec say = spec(<FieldSpec>[
      const FieldSpec(key: 'bubble', kind: FieldKind.integer),
      const FieldSpec(key: 'text', kind: FieldKind.string, required: true),
    ]);
    expect(say.firstToFill?.key, 'text');
  });

  test('firstToFill_oneOfGroups_isTheFirstKeyOfTheFirstGroup', () {
    final CapabilitySpec give = spec(<FieldSpec>[
      const FieldSpec(key: 'item', kind: FieldKind.string),
      const FieldSpec(key: 'amount', kind: FieldKind.integer),
      const FieldSpec(key: 'stack', kind: FieldKind.object),
    ], oneOf: <List<String>>[<String>['item'], <String>['stack']]);
    expect(give.firstToFill?.key, 'item');
  });

  test('firstToFill_nothingNeeded_isNull', () {
    expect(spec(<FieldSpec>[const FieldSpec(key: 'range', kind: FieldKind.integer)]).firstToFill, isNull);
  });

  test('firstToFill_giveItemInTheCatalog_isItem', () {
    expect(InspectSchemas.embedded.catalog.capability('animus:give-item')?.firstToFill?.key, 'item');
  });
}
