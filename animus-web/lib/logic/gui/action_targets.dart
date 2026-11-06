/// What an action field may name: a named list of the document for `call`,
/// a list component of the menu for `navigate.list`, page steps or a
/// document for `navigate.target`, and the document kinds `inventory.id`
/// and `animus:open-shop.shop` refer to. Also the `inventory` action's
/// `args`, which core reads as templates under lowercase names.
library;

import '../../model/document_kind.dart';
import '../inspect/schema_fields.dart';
import 'gui_json.dart';

final RegExp _argumentName = RegExp(r'^[a-z][a-zA-Z0-9_]*$');

/// Why core refuses `args.<name>`, or null.
String? argumentNameProblem(String name) => _argumentName.hasMatch(name) ? null : 'Start with a lowercase letter, then letters, digits or _.';

/// `args` as text by name: core reads a number or boolean as its text.
JsonMap inventoryArgs(Object? value) => <String, Object?>{
  for (final MapEntry<String, Object?> entry in asMap(value).entries) entry.key: entry.value is num || entry.value is bool ? '${entry.value}' : entry.value,
};

List<FieldOption> callTargetOptions(List<String> namedLists) => <FieldOption>[
  for (final String name in namedLists) FieldOption(name, description: 'named list'),
];

/// Ids of the menu's `list` components, which `navigate.list` names.
List<String> menuListIds(JsonMap menu) => <String>[
  for (final Object? component in asList(menu['components']))
    if (asString(asMap(asMap(component)['data'])['type']) == 'list')
      if (asString(asMap(component)['id']) case final String id) id,
];

/// `navigate.target` suggestions: page steps with mode page, the documents
/// [ids] for push and replace (the default), nothing for the other modes.
List<FieldOption> navigateTargetOptions({required String? mode, required List<String> ids}) => switch (mode) {
  'page' => const <FieldOption>[FieldOption('next', description: 'next page'), FieldOption('prev', description: 'previous page')],
  null || 'push' || 'replace' => <FieldOption>[for (final String id in ids) FieldOption(id)],
  _ => const <FieldOption>[],
};

/// The document kind field [field] of a [type] action names on a [surface]
/// document, or null for a field that names none.
DocumentKind? actionReference(String type, String field, DocumentKind surface) => switch ((type, field)) {
  ('inventory', 'id') => DocumentKind.inventory,
  ('animus:open-shop', 'shop') => DocumentKind.shop,
  ('navigate', 'target') => surface == DocumentKind.menu ? DocumentKind.menu : DocumentKind.inventory,
  _ => null,
};
