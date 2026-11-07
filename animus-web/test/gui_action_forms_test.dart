import 'package:animus_editor/logic/gui/action_targets.dart';
import 'package:animus_editor/logic/gui/gui_catalog.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

/// A value of [field]'s kind, as its form writes one.
Object _sample(GuiField field) => switch (field.kind) {
      GuiFieldKind.template || GuiFieldKind.expression || GuiFieldKind.text => 'sample_${field.name}',
      GuiFieldKind.integer => 7,
      GuiFieldKind.number => 2.5,
      GuiFieldKind.boolean => true,
      GuiFieldKind.choice => field.choices.last,
      GuiFieldKind.multiChoice => <String>[field.choices.first],
      GuiFieldKind.actions => <Object?>[newAction('message')],
      GuiFieldKind.cases => <String, Object?>{'a': <Object?>[newAction('close')]},
      GuiFieldKind.branches => <Object?>[<Object?>[newAction('stop')]],
      GuiFieldKind.icon => <String, Object?>{'item': 'minecraft:bread', 'count': 2},
      GuiFieldKind.object => <String, Object?>{'page': '{{ page }}'},
    };

void main() {
  group('action fields', () {
    for (final MapEntry<String, List<GuiField>> type in actionFields.entries) {
      test('should write and read back every ${type.key} field', () {
        JsonMap action = newAction(type.key);
        for (final GuiField field in <GuiField>[...type.value, ...commonActionFields]) {
          final Object value = _sample(field);
          action = withActionField(action, field.name, value);
          expect(action[field.name], value, reason: field.name);
          expect(action['type'], type.key);
        }
        for (final GuiField field in type.value) {
          expect(withActionField(action, field.name, null).containsKey(field.name), isFalse);
          expect(withActionField(action, field.name, '').containsKey(field.name), isFalse);
        }
      });
    }

    test('should keep keys the form does not know', () {
      final JsonMap action = withActionField(<String, Object?>{'type': 'effect', 'effect': 'minecraft:speed', 'target': 'viewer'}, 'ticks', 40);
      expect(action, <String, Object?>{'type': 'effect', 'effect': 'minecraft:speed', 'target': 'viewer', 'ticks': 40});
    });
  });

  group('inventory args', () {
    test('should accept the names the server accepts', () {
      expect(argumentNameProblem('page'), isNull);
      expect(argumentNameProblem('pageCount_2'), isNull);
      expect(argumentNameProblem('Page'), isNotNull);
      expect(argumentNameProblem('2page'), isNotNull);
      expect(argumentNameProblem('my-page'), isNotNull);
    });

    test('should read args as text and write them back', () {
      final JsonMap args = inventoryArgs(<String, Object?>{'page': '{{ page }}', 'count': 3, 'on': true});
      expect(args, <String, Object?>{'page': '{{ page }}', 'count': '3', 'on': 'true'});
      expect(inventoryArgs('nope'), isEmpty);
    });
  });

  group('action targets', () {
    test('should offer named lists to call', () {
      final List<FieldOption> options = callTargetOptions(const <String>['main', 'confirm']);
      expect(<String>[for (final FieldOption option in options) option.label], <String>['main', 'confirm']);
    });

    test('should find the list components of a menu', () {
      final JsonMap menu = <String, Object?>{
        'components': <Object?>[
          <String, Object?>{'id': 'shop', 'data': <String, Object?>{'type': 'list'}},
          <String, Object?>{'id': 'title', 'data': <String, Object?>{'type': 'decoration'}},
          <String, Object?>{'id': 'more', 'data': <String, Object?>{'type': 'list'}},
          <String, Object?>{'data': <String, Object?>{'type': 'list'}},
          'junk',
        ],
      };
      expect(menuListIds(menu), <String>['shop', 'more']);
      expect(menuListIds(const <String, Object?>{}), isEmpty);
    });

    test('should offer page steps for mode page and documents otherwise', () {
      List<String> labels(String? mode) => <String>[
            for (final FieldOption option in navigateTargetOptions(mode: mode, ids: const <String>['town:hub'])) option.label,
          ];
      expect(labels('page'), <String>['next', 'prev']);
      expect(labels('push'), <String>['town:hub']);
      expect(labels(null), <String>['town:hub']);
      expect(labels('back'), isEmpty);
    });

    test('should name the document kind an action field refers to', () {
      expect(actionReference('inventory', 'id', DocumentKind.menu), DocumentKind.inventory);
      expect(actionReference('animus:open-shop', 'shop', DocumentKind.inventory), DocumentKind.shop);
      expect(actionReference('navigate', 'target', DocumentKind.menu), DocumentKind.menu);
      expect(actionReference('navigate', 'target', DocumentKind.inventory), DocumentKind.inventory);
      expect(actionReference('navigate', 'target', DocumentKind.shop), DocumentKind.inventory);
      expect(actionReference('message', 'message', DocumentKind.menu), isNull);
    });
  });
}
