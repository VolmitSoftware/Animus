import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

void main() {
  final InspectSchemas schemas = InspectSchemas.embedded;

  test('every kind with a shared schema has a form schema', () {
    for (final DocumentKind kind in DocumentKind.values) {
      final bool shared = SharedSchemas.embedded.schemaFor(kind) != null;
      expect(schemas.forKind(kind) != null, shared || const <DocumentKind>{DocumentKind.npc, DocumentKind.behavior, DocumentKind.pack}.contains(kind), reason: kind.name);
    }
  });

  test('an animation form names its rig, length and tracks', () {
    final SchemaView animation = schemas.forKind(DocumentKind.animation)!;
    final List<String> keys = <String>[for (final FieldSpec field in animation.fields(animation.root, const <String, Object?>{})) field.key];
    expect(keys, containsAll(<String>['rig', 'durationTicks', 'tracks']));
  });

  test('items merged from conditional branches still read as objects', () {
    final SchemaView animation = schemas.forKind(DocumentKind.animation)!;
    final Map<String, Object?> track = animation.at(<Object>['tracks', 0], <String, Object?>{
      'tracks': <Object?>[
        <String, Object?>{'bone': 'head', 'target': 'visible', 'keyframes': <Object?>[]},
      ],
    })!;
    final FieldSpec keyframes = animation.fields(track, <String, Object?>{'target': 'visible'}).firstWhere((FieldSpec field) => field.key == 'keyframes');
    final FieldSpec item = animation.field('keyframe', keyframes.schema['items']);
    expect(item.kind, FieldKind.object);
    final List<String> keys = <String>[for (final FieldSpec field in animation.fields(item.schema, <String, Object?>{'tick': 0, 'value': true})) field.key];
    expect(keys, containsAll(<String>['tick', 'value']));
  });

  test('the same schema view is returned each time', () {
    expect(identical(schemas.forKind(DocumentKind.animation), schemas.forKind(DocumentKind.animation)), isTrue);
  });
}
