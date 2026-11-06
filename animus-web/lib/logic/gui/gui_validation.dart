/// Browser checks for GUI documents beyond the JSON schemas, matching what
/// the server refuses at load (wiki animus/11-guis-holograms-menus, wiki animus/10-shops): Gloss-only
/// actions, components and icons named at the exact field, expression
/// errors, `call` targets and loops, mask and slot bounds, shop grids, and a
/// note wherever a command needs the command allow-list.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'gui_catalog.dart';
import 'gui_expression.dart';
import 'gui_json.dart';

/// Codes the editors and tests look for.
const String refusedCode = 'refused';
const String expressionCode = 'expression';
const String allowListCode = 'command-allow-list';
const String layoutCode = 'layout';

List<Diagnostic> validateGui(DocumentKind kind, Object? json, {String? id}) {
  if (json is! Map<String, Object?>) return const <Diagnostic>[];
  final _Checker checker = _Checker(kind);
  switch (kind) {
    case DocumentKind.inventory:
      checker.inventory(json);
    case DocumentKind.menu:
      checker.menu(json);
    case DocumentKind.hologram:
      checker.hologram(json);
    case DocumentKind.shop:
      checker.shop(json);
    default:
      break;
  }
  return checker.out;
}

/// Drops schema errors inside a value the GUI checks already refused by
/// name, so a Gloss-only action shows one clear line instead of every shape
/// it fails to match.
List<Diagnostic> mergeGuiDiagnostics(List<Diagnostic> schema, List<Diagnostic> gui) {
  final List<String> refusedRoots = <String>[
    for (final Diagnostic diagnostic in gui)
      if (diagnostic.code == refusedCode) _parentOf(diagnostic.path),
  ];
  bool covered(String path) => refusedRoots.any((String root) => path == root || path.startsWith('$root.') || path.startsWith('$root['));
  final List<Diagnostic> kept = <Diagnostic>[
    for (final Diagnostic diagnostic in schema)
      if (!covered(diagnostic.path)) diagnostic,
  ];
  return <Diagnostic>[...kept, ...gui];
}

String _parentOf(String path) {
  final int dot = path.lastIndexOf('.');
  final int bracket = path.lastIndexOf('[');
  final int cut = dot > bracket ? dot : bracket;
  return cut <= 0 ? path : path.substring(0, cut);
}

final class _Checker {
  _Checker(this.kind);

  final DocumentKind kind;
  final List<Diagnostic> out = <Diagnostic>[];
  final GuiActionLists lists = GuiActionLists.instance;
  final Map<String, List<String>> _calls = <String, List<String>>{};
  Set<String> _named = const <String>{};
  bool _commandNoted = false;

  void error(String path, String message, {String code = refusedCode}) =>
      out.add(Diagnostic(DiagnosticSeverity.error, path, message, code: code));

  void warning(String path, String message, String code) => out.add(Diagnostic(DiagnosticSeverity.warning, path, message, code: code));

  // ---- shared pieces ----

  void template(Object? value, String path, ExpressionNames names) {
    if (value is! String) return;
    if (kind == DocumentKind.inventory && value.contains('|animation.')) {
      error(path, 'chest GUIs cannot show text animations (|animation.| tokens)');
    }
    for (final String problem in checkTemplate(value, names: names)) {
      error(path, problem, code: expressionCode);
    }
  }

  void expression(Object? value, String path, ExpressionNames names) {
    if (value is! String) return;
    final String? problem = checkExpression(value, names: names);
    if (problem != null) error(path, problem, code: expressionCode);
  }

  /// A `show` style condition: a boolean or an expression string.
  void condition(Object? value, String path, ExpressionNames names) => expression(value, path, names);

  void namedActions(JsonMap json, ExpressionNames names) {
    final JsonMap named = asMap(json['actions']);
    _named = named.keys.toSet();
    for (final MapEntry<String, Object?> entry in named.entries) {
      actions(entry.value, childPath(childPath(r'$', 'actions'), entry.key), names, owner: entry.key);
    }
  }

  void actions(Object? value, String path, ExpressionNames names, {String? owner}) {
    final JsonList list = asList(value);
    for (int index = 0; index < list.length; index++) {
      action(list[index], indexPath(path, index), names, owner: owner);
    }
  }

  void action(Object? value, String path, ExpressionNames names, {String? owner}) {
    if (value is! Map<String, Object?>) return;
    final String? type = asString(value['type']);
    if (type == null) return;
    final String typePath = childPath(path, 'type');
    if (lists.refused.contains(type)) {
      error(typePath, "action '$type' is not available in Animus");
      return;
    }
    if (!lists.isOffered(type)) {
      error(typePath, "unknown action '$type'");
      return;
    }
    if (kind == DocumentKind.hologram && hologramRefusedActions.contains(type)) {
      error(typePath, "action '$type' needs a window or named lists, which a hologram does not have");
      return;
    }
    if (kind == DocumentKind.hologram && type == 'navigate' && value['mode'] != null && value['mode'] != 'page') {
      error(childPath(path, 'mode'), "a hologram navigates only with mode page");
    }
    if (type == 'effect' && value['target'] != null && value['target'] != 'viewer') {
      error(childPath(path, 'target'), "target '${value['target']}' belongs to Gloss behaviors; Animus applies effects to the viewer");
    }
    if (type == 'particle') {
      final Object? at = value['at'];
      if (at == 'subject' || at == 'source') error(childPath(path, 'at'), "at '$at' belongs to Gloss behaviors; use viewer or location");
      if (at == 'location' && kind == DocumentKind.inventory) error(childPath(path, 'at'), 'a chest window has no location; use viewer');
    }
    expression(value['when'], childPath(path, 'when'), names);
    for (final GuiField field in actionFields[type] ?? const <GuiField>[]) {
      final String fieldPath = childPath(path, field.name);
      final Object? fieldValue = value[field.name];
      switch (field.kind) {
        case GuiFieldKind.template:
          template(fieldValue, fieldPath, names);
        case GuiFieldKind.expression:
          if (fieldValue is String) expression(fieldValue, fieldPath, names);
        case GuiFieldKind.actions:
          actions(fieldValue, fieldPath, names, owner: owner);
        case GuiFieldKind.cases:
          for (final MapEntry<String, Object?> entry in asMap(fieldValue).entries) {
            actions(entry.value, childPath(fieldPath, entry.key), names, owner: owner);
          }
        case GuiFieldKind.branches:
          final JsonList branches = asList(fieldValue);
          for (int index = 0; index < branches.length; index++) {
            actions(branches[index], indexPath(fieldPath, index), names, owner: owner);
          }
        case GuiFieldKind.icon:
          icon(fieldValue, fieldPath, names, chest: true);
        default:
          break;
      }
    }
    if (type == 'sequence') {
      for (final String refused in refusedSequenceFields) {
        if (value.containsKey(refused)) error(childPath(path, refused), "sequence field '$refused' is not available in Animus");
      }
    }
    if (type == 'call') {
      final String? target = asString(value['action']);
      if (target != null && !_named.contains(target)) {
        error(childPath(path, 'action'), "call '$target' names no list under actions", code: layoutCode);
      } else if (target != null && owner != null) {
        (_calls[owner] ??= <String>[]).add(target);
      }
    }
    if (type == 'command' && !_commandNoted) {
      _commandNoted = true;
      warning(
        childPath(path, 'command'),
        'runs a command: the server refuses this file unless its id or namespace is on commandBehaviors or commandNamespaces under [behaviors.actions]',
        allowListCode,
      );
    }
  }

  void callLoops() {
    for (final String start in _calls.keys) {
      final Set<String> seen = <String>{};
      final List<String> stack = <String>[start];
      while (stack.isNotEmpty) {
        final String next = stack.removeLast();
        for (final String target in _calls[next] ?? const <String>[]) {
          if (target == start) {
            error(childPath(childPath(r'$', 'actions'), start), "named action list '$start' calls itself through call", code: layoutCode);
            stack.clear();
            break;
          }
          if (seen.add(target)) stack.add(target);
        }
      }
    }
  }

  void icon(Object? value, String path, ExpressionNames names, {required bool chest}) {
    if (value is! Map<String, Object?>) return;
    final String? type = asString(value['type']);
    final List<String> refused = chest ? chestRefusedIconTypes : menuRefusedIconTypes;
    if (type != null && refused.contains(type)) {
      error(
        childPath(path, 'type'),
        chest ? "icon '$type' has no meaning in a chest window and is not available in Animus" : "icon '$type' is not available in Animus",
      );
      return;
    }
    for (final String field in const <String>['item', 'name', 'player', 'text']) {
      template(value[field], childPath(path, field), names);
    }
    final JsonList lore = asList(value['lore']);
    for (int index = 0; index < lore.length; index++) {
      template(lore[index], indexPath(childPath(path, 'lore'), index), names);
    }
  }

  /// A chest component (`keys`, `slots`, list templates) or the `data` of a
  /// menu component.
  void component(Object? value, String path, ExpressionNames names, {required bool chest}) {
    if (value is! Map<String, Object?>) return;
    final String? type = asString(value['type']);
    final List<String> refused = chest ? chestRefusedComponentTypes : menuRefusedComponentTypes;
    if (type != null && refused.contains(type)) {
      error(
        childPath(path, 'type'),
        chest ? "component '$type' is a holographic menu component and is not available in a chest window" : "component '$type' is not available in Animus",
      );
      return;
    }
    condition(value['show'], childPath(path, 'show'), names);
    icon(value['icon'], childPath(path, 'icon'), names, chest: chest);
    actions(value['actions'], childPath(path, 'actions'), names);
    if (type == 'toggle') {
      expression(value['condition'], childPath(path, 'condition'), names);
      icon(value['trueIcon'], childPath(path, 'trueIcon'), names, chest: chest);
      icon(value['falseIcon'], childPath(path, 'falseIcon'), names, chest: chest);
      actions(value['trueActions'], childPath(path, 'trueActions'), names);
      actions(value['falseActions'], childPath(path, 'falseActions'), names);
    }
    if (!chest && type == 'list') {
      final String? variable = asString(value['var']);
      expression(value['source'], childPath(path, 'source'), names);
      component(value['template'], childPath(path, 'template'), variable == null ? names : names.withLocal(variable), chest: false);
    }
  }

  // ---- chest GUIs ----

  void inventory(JsonMap json) {
    const ExpressionNames names = ExpressionNames.standard;
    final Object? version = json['schemaVersion'];
    if (version is num && version != inventorySchemaVersion) {
      error(r'$.schemaVersion', 'schemaVersion $version is not supported; Animus reads chest GUIs of version $inventorySchemaVersion');
    }
    if (json.containsKey('preset')) error(r'$.preset', 'Gloss presets are not available in Animus; copy the preset\'s fields in');
    namedActions(json, names);
    template(json['title'], r'$.title', names);
    condition(json['show'], r'$.show', names);
    final (int width, int rows) = chestSize(asString(json['resolution']));
    final JsonMap list = asMap(json['list']);
    final String? listVar = asString(list['var']);
    final ExpressionNames listNames = listVar == null ? names : names.withLocal(listVar);
    _chestPresentation(json, r'$', width, rows, names, area: asString(list['area']));
    if (list.isNotEmpty) {
      expression(list['source'], r'$.list.source', names);
      component(list['template'], r'$.list.template', listNames, chest: true);
      final String? area = asString(list['area']);
      final bool inMask = asList(json['mask']).any((Object? row) => row is String && area != null && row.contains(area));
      if (area != null && !inMask) error(r'$.list.area', "list area '$area' does not appear in the mask", code: layoutCode);
    }
    final JsonList variants = asList(json['variants']);
    final Set<String> ids = <String>{};
    for (int index = 0; index < variants.length; index++) {
      final String path = indexPath(r'$.variants', index);
      final JsonMap variant = asMap(variants[index]);
      final String? variantId = asString(variant['id']);
      if (variantId != null && !ids.add(variantId)) error(childPath(path, 'id'), "variant id '$variantId' is used twice", code: layoutCode);
      expression(variant['when'], childPath(path, 'when'), names);
      final JsonMap presentation = asMap(variant['presentation']);
      template(presentation['title'], childPath(childPath(path, 'presentation'), 'title'), names);
      _chestPresentation(presentation, childPath(path, 'presentation'), width, rows, names, area: asString(list['area']));
    }
    callLoops();
  }

  void _chestPresentation(JsonMap json, String path, int width, int rows, ExpressionNames names, {String? area}) {
    final JsonMap keys = asMap(json['keys']);
    for (final MapEntry<String, Object?> entry in keys.entries) {
      component(entry.value, childPath(childPath(path, 'keys'), entry.key), names, chest: true);
    }
    final JsonMap slots = asMap(json['slots']);
    for (final MapEntry<String, Object?> entry in slots.entries) {
      final String slotPath = childPath(childPath(path, 'slots'), entry.key);
      final int? slot = int.tryParse(entry.key);
      if (slot != null && slot >= width * rows) {
        error(slotPath, 'slot $slot is outside a ${width}x$rows window (slots 0 to ${width * rows - 1})', code: layoutCode);
      }
      component(entry.value, slotPath, names, chest: true);
    }
    final JsonList mask = asList(json['mask']);
    if (mask.length > rows) error(childPath(path, 'mask'), 'the mask has ${mask.length} rows; a ${width}x$rows window has $rows', code: layoutCode);
    final Set<String> missing = <String>{};
    for (int row = 0; row < mask.length; row++) {
      final String? line = asString(mask[row]);
      if (line == null) continue;
      if (line.length > width) error(indexPath(childPath(path, 'mask'), row), 'row is ${line.length} wide; the window is $width', code: layoutCode);
      for (final String char in line.split('')) {
        if (char != ' ' && char != area && !keys.containsKey(char) && path == r'$') missing.add(char);
      }
    }
    for (final String char in missing) {
      warning(childPath(path, 'mask'), "mask character '$char' has no key and draws nothing", layoutCode);
    }
  }

  // ---- holographic menus ----

  /// An enabled `box` and non-empty `particleLayers` (holograms and menus).
  void presentationExtras(JsonMap json, String path) {
    if (asMap(json['box'])['enabled'] == true) error(childPath(childPath(path, 'box'), 'enabled'), 'text boxes are not available in Animus');
    if (asList(json['particleLayers']).isNotEmpty) error(childPath(path, 'particleLayers'), 'particle layers are not available in Animus');
  }

  void menu(JsonMap json) {
    const ExpressionNames names = ExpressionNames.standard;
    presentationExtras(json, r'$');
    namedActions(json, names);
    condition(json['show'], r'$.show', names);
    final JsonList components = asList(json['components']);
    final Set<String> ids = <String>{};
    for (int index = 0; index < components.length; index++) {
      final String path = indexPath(r'$.components', index);
      final JsonMap entry = asMap(components[index]);
      final String? componentId = asString(entry['id']);
      if (componentId != null && !ids.add(componentId)) error(childPath(path, 'id'), "component id '$componentId' is used twice", code: layoutCode);
      condition(entry['show'], childPath(path, 'show'), names);
      component(entry['data'], childPath(path, 'data'), names, chest: false);
      final JsonMap icon = asMap(asMap(entry['data'])['icon']);
      presentationExtras(icon, childPath(childPath(path, 'data'), 'icon'));
    }
    callLoops();
  }

  // ---- holograms ----

  void hologram(JsonMap json) {
    const ExpressionNames names = ExpressionNames.standard;
    final Object? version = json['schemaVersion'];
    if (version is num && version != hologramSchemaVersion) {
      error(r'$.schemaVersion', 'schemaVersion $version is not supported; Animus reads holograms of version $hologramSchemaVersion');
    }
    if (json.containsKey('preset') && json['preset'] != null) {
      error(r'$.preset', 'Gloss presets are not available in Animus; copy the preset\'s fields in');
    }
    condition(json['show'], r'$.show', names);
    presentationExtras(json, r'$');
    _lines(json['lines'], r'$.lines', names);
    final JsonList pages = asList(json['pages']);
    for (int index = 0; index < pages.length; index++) {
      final String path = indexPath(r'$.pages', index);
      condition(asMap(pages[index])['show'], childPath(path, 'show'), names);
      _lines(asMap(pages[index])['lines'], childPath(path, 'lines'), names);
    }
    final JsonList variants = asList(json['variants']);
    for (int index = 0; index < variants.length; index++) {
      final String path = indexPath(r'$.variants', index);
      expression(asMap(variants[index])['when'], childPath(path, 'when'), names);
      presentationExtras(asMap(asMap(variants[index])['presentation']), childPath(path, 'presentation'));
      _lines(asMap(asMap(variants[index])['presentation'])['lines'], childPath(childPath(path, 'presentation'), 'lines'), names);
    }
    actions(json['actions'], r'$.actions', names);
  }

  void _lines(Object? value, String path, ExpressionNames names) {
    final JsonList lines = asList(value);
    for (int index = 0; index < lines.length; index++) {
      final Object? line = lines[index];
      final String linePath = indexPath(path, index);
      if (line is String) {
        template(line, linePath, names);
      } else if (line is Map<String, Object?>) {
        template(line['text'], childPath(linePath, 'text'), names);
        icon(line['item'], childPath(linePath, 'item'), names, chest: false);
      }
    }
  }

  // ---- shops ----

  void shop(JsonMap json) {
    final List<String> modes = <String>[for (final Object? mode in asList(json['modes'])) if (mode is String) mode];
    final List<String> enabled = modes.isEmpty ? shopModes : modes;
    final String? defaultMode = asString(json['defaultMode']);
    if (defaultMode != null && !enabled.contains(defaultMode)) {
      error(r'$.defaultMode', "defaultMode '$defaultMode' is not one of the enabled modes", code: layoutCode);
    }
    if (modes.toSet().length != modes.length) error(r'$.modes', 'each mode may appear once', code: layoutCode);
    final Map<String, String> entryIds = <String, String>{};
    final JsonList pages = asList(json['pages']);
    for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
      final JsonMap page = asMap(pages[pageIndex]);
      final String pagePath = indexPath(r'$.pages', pageIndex);
      final int rows = asInt(page['rows']) ?? 3;
      for (final String mode in shopModes) {
        final JsonMap grid = asMap(page[mode]);
        if (grid.isNotEmpty && !enabled.contains(mode)) {
          warning(childPath(pagePath, mode), "the $mode grid has entries but $mode is not in modes, so players never see it", layoutCode);
        }
        for (final MapEntry<String, Object?> slotEntry in grid.entries) {
          final String path = childPath(childPath(pagePath, mode), slotEntry.key);
          final JsonMap entry = asMap(slotEntry.value);
          final int? slot = int.tryParse(slotEntry.key);
          if (slot != null && slot >= rows * 9) {
            error(path, 'slot $slot is outside a $rows-row page (slots 0 to ${rows * 9 - 1})', code: layoutCode);
          } else if (slot != null) {
            final String? control = shopControlAt(slot, rows);
            if (control != null) warning(path, 'slot $slot sits under the $control control and is hidden while that control shows', layoutCode);
          }
          final String type = asString(entry['type']) ?? 'static';
          final List<String> grids = shopGridsFor(type);
          if (!grids.contains(mode)) {
            error(childPath(path, 'type'), "a $type entry belongs in the ${grids.join(' or ')} grid, not $mode", code: layoutCode);
          }
          final String? entryId = asString(entry['id']);
          if (entryId != null) {
            final String? first = entryIds[entryId];
            if (first != null) {
              error(childPath(path, 'id'), "entry id '$entryId' is already used at $first; limits and stock are kept under it", code: layoutCode);
            } else {
              entryIds[entryId] = path;
            }
          }
          if (asList(entry['commands']).isNotEmpty) {
            warning(
              childPath(path, 'commands'),
              'runs commands: the server refuses this shop unless its id or namespace is on commandBehaviors or commandNamespaces under [behaviors.actions]',
              allowListCode,
            );
          }
          final JsonMap discount = asMap(entry['discount']);
          final DateTime? start = DateTime.tryParse(asString(discount['start']) ?? '');
          final DateTime? end = DateTime.tryParse(asString(discount['end']) ?? '');
          if (start != null && end != null && !end.isAfter(start)) {
            error(childPath(childPath(path, 'discount'), 'end'), 'the discount ends before it starts', code: layoutCode);
          }
          final double? price = asDouble(entry['price']);
          if (price != null && (price * 10000).roundToDouble() != price * 10000) {
            error(childPath(path, 'price'), 'prices have at most 4 decimal places', code: layoutCode);
          }
        }
      }
    }
  }
}

/// Columns and rows of a chest `resolution` (default 9x3).
(int, int) chestSize(String? resolution) => switch (resolution) {
      '9x1' => (9, 1),
      '9x2' => (9, 2),
      '9x4' => (9, 4),
      '9x5' => (9, 5),
      '9x6' => (9, 6),
      '5x1' => (5, 1),
      '3x3' => (3, 3),
      _ => (9, 3),
    };

const List<String> shopModes = <String>['buy', 'sell', 'trade'];

/// Grids an entry type belongs in.
List<String> shopGridsFor(String type) => switch (type) {
      'buy' || 'command' => const <String>['buy'],
      'sell' => const <String>['sell'],
      'trade' => const <String>['trade'],
      _ => shopModes,
    };

/// The bottom-row control at [slot] of a page with [rows] rows, or null.
String? shopControlAt(int slot, int rows) {
  final int bottom = (rows - 1) * 9;
  return switch (slot - bottom) {
    2 => 'previous page',
    4 => 'close',
    6 => 'next page',
    8 => 'mode switch',
    _ => null,
  };
}
