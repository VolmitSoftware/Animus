/// Component and icon inspectors for chest GUIs and holographic menus. Each
/// offers only the component and icon types the surface accepts in Animus;
/// a refused type is flagged with its field.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_catalog.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../model/animus_document.dart';
import 'actions_editor.dart';
import 'gui_fields.dart';

/// Writes [value] at [key] of [map], removing the key for null or empty text.
JsonMap withField(JsonMap map, String key, Object? value) {
  final JsonMap out = <String, Object?>{...map};
  if (value == null || (value is String && value.isEmpty) || (value is List<Object?> && value.isEmpty && key == 'lore')) {
    out.remove(key);
  } else {
    out[key] = value;
  }
  return out;
}

class IconEditor extends StatelessWidget {
  const IconEditor({
    required this.icon,
    required this.onChanged,
    required this.path,
    required this.diagnostics,
    required this.chest,
    this.names = ExpressionNames.standard,
    super.key,
  });

  final JsonMap icon;
  final void Function(JsonMap icon) onChanged;
  final String path;
  final List<Diagnostic> diagnostics;

  /// Chest windows draw only item and player head icons.
  final bool chest;
  final ExpressionNames names;

  @override
  Widget build(BuildContext context) {
    final String type = asString(icon['type']) ?? 'item';
    final List<String> types = chest ? chestIconTypes : menuIconTypes;
    final bool refused = !types.contains(type);
    Widget text(String key, String label, {GuiTextMode mode = GuiTextMode.template, bool multiline = false, String? help}) => GuiRow(
      label: label,
      help: help,
      problems: diagnosticsAt(diagnostics, childPath(path, key)),
      child: GuiTextInput(
        value: asString(icon[key]) ?? '',
        mode: mode,
        names: names,
        multiline: multiline,
        label: label,
        onChanged: (String value) => onChanged(withField(icon, key, value)),
      ),
    );
    return dom.div(classes: 'animus-gui-icon', <Widget>[
      GuiRow(
        label: 'Icon',
        problems: diagnosticsAt(diagnostics, childPath(path, 'type')),
        child: GuiSelect(
          value: type,
          options: types,
          labels: <String, String>{if (refused) type: '$type (not available here)'},
          onChanged: (String? next) {
            if (next == null) return;
            onChanged(<String, Object?>{
              'type': next,
              if (next == 'item') 'item': asString(icon['item']) ?? 'minecraft:stone',
              if (next == 'playerHead') 'player': '{{ viewer.name }}',
              if (next == 'text') 'text': asString(icon['name']) ?? 'Text',
              if (next == 'block') 'block': 'minecraft:stone',
              if (next == 'entity') 'entity': 'minecraft:pig',
              if (icon['name'] != null && next != 'text') 'name': icon['name'],
              if (icon['lore'] != null && next != 'text') 'lore': icon['lore'],
            });
          },
        ),
      ),
      if (refused)
        dom.p(
          classes: 'animus-gui-refused',
          attributes: const <String, String>{'role': 'alert'},
          <Widget>[
            dom.code(<Widget>[Component.text(childPath(path, 'type'))]),
            Component.text(
              chest ? " icon '$type' has no meaning in a chest window and is not available in Animus." : " icon '$type' is not available in Animus.",
            ),
          ],
        )
      else ...<Widget>[
        if (type == 'item') ...<Widget>[
          text('item', 'Item', help: 'An item id such as minecraft:bread; expressions work.'),
          GuiRow(
            label: 'Count',
            problems: diagnosticsAt(diagnostics, childPath(path, 'count')),
            child: GuiNumberInput(value: asInt(icon['count']), label: 'Count', onChanged: (num? value) => onChanged(withField(icon, 'count', value))),
          ),
          GuiRow(
            label: 'Custom model',
            child: GuiNumberInput(
              value: asInt(icon['customModelValue']),
              label: 'Custom model value',
              onChanged: (num? value) => onChanged(withField(icon, 'customModelValue', value)),
            ),
          ),
        ],
        if (type == 'playerHead') text('player', 'Player', help: 'A name; the head shows the skin on Bukkit for an online player.'),
        if (type == 'text') text('text', 'Text', multiline: true),
        if (type == 'block') text('block', 'Block', mode: GuiTextMode.plain),
        if (type == 'entity') text('entity', 'Entity', mode: GuiTextMode.plain),
        if (type != 'text' && type != 'entity') ...<Widget>[
          text('name', 'Name'),
          GuiRow(
            label: 'Lore',
            problems: diagnosticsAt(diagnostics, childPath(path, 'lore'), nested: true),
            child: GuiLinesEditor(lines: stringList(icon['lore']), names: names, onChanged: (List<String> lines) => onChanged(withField(icon, 'lore', lines))),
          ),
        ],
      ],
    ]);
  }
}

/// A chest key, slot or list template, or a menu component's `data`.
class ComponentEditor extends StatelessWidget {
  const ComponentEditor({
    required this.component,
    required this.onChanged,
    required this.path,
    required this.diagnostics,
    required this.chest,
    this.names = ExpressionNames.standard,
    this.namedLists = const <String>[],
    super.key,
  });

  final JsonMap component;
  final void Function(JsonMap component) onChanged;
  final String path;
  final List<Diagnostic> diagnostics;
  final bool chest;
  final ExpressionNames names;
  final List<String> namedLists;

  @override
  Widget build(BuildContext context) {
    final JsonMap data = component;
    final String type = asString(data['type']) ?? 'decoration';
    final List<String> types = chest ? chestComponentTypes : menuComponentTypes;
    final bool refused = !types.contains(type);
    Widget actions(String key, String title) => GuiSection(
      title: title,
      count: asList(data[key]).length,
      children: <Widget>[
        ActionsEditor(
          actions: asList(data[key]),
          path: childPath(path, key),
          diagnostics: diagnostics,
          names: names,
          namedLists: namedLists,
          onChanged: (JsonList next) => onChanged(<String, Object?>{...data, key: next}),
        ),
      ],
    );
    Widget icon(String key, String title) => GuiSection(
      title: title,
      children: <Widget>[
        IconEditor(
          icon: asMap(data[key]),
          path: childPath(path, key),
          diagnostics: diagnostics,
          chest: chest,
          names: names,
          onChanged: (JsonMap next) => onChanged(<String, Object?>{...data, key: next}),
        ),
      ],
    );
    return dom.div(classes: 'animus-gui-component', <Widget>[
      GuiRow(
        label: 'Type',
        problems: diagnosticsAt(diagnostics, childPath(path, 'type')),
        child: GuiSelect(
          value: type,
          options: types,
          labels: <String, String>{if (refused) type: '$type (not available here)'},
          onChanged: (String? next) {
            if (next == null || next == type) return;
            onChanged(_convert(data, next));
          },
        ),
      ),
      if (refused)
        dom.p(
          classes: 'animus-gui-refused',
          attributes: const <String, String>{'role': 'alert'},
          <Widget>[
            dom.code(<Widget>[Component.text(childPath(path, 'type'))]),
            Component.text(
              chest
                  ? " component '$type' is a holographic menu component and is not available in a chest window."
                  : " component '$type' is not available in Animus.",
            ),
          ],
        )
      else ...<Widget>[
        // A menu component carries show beside its data, edited by the menu editor.
        if (chest)
          GuiRow(
            label: 'Show when',
            help: 'Blank always shows. An expression such as viewer.level > 5.',
            problems: diagnosticsAt(diagnostics, childPath(path, 'show')),
            child: GuiTextInput(
              value: data['show'] is String ? data['show']! as String : (data['show'] == false ? 'false' : ''),
              mode: GuiTextMode.expression,
              names: names,
              label: 'Show when',
              onChanged: (String value) => onChanged(withField(data, 'show', value == 'false' ? false : value)),
            ),
          ),
        if (type == 'button' || type == 'decoration') icon('icon', 'Icon'),
        if (type == 'button') actions('actions', 'Actions'),
        if (type == 'toggle') ...<Widget>[
          GuiRow(
            label: 'Condition *',
            problems: diagnosticsAt(diagnostics, childPath(path, 'condition')),
            child: GuiTextInput(
              value: asString(data['condition']) ?? '',
              mode: GuiTextMode.expression,
              names: names,
              label: 'Condition',
              onChanged: (String value) => onChanged(withField(data, 'condition', value)),
            ),
          ),
          GuiRow(
            label: 'Expected',
            help: 'The true side shows when the condition gives this.',
            child: GuiSwitch(
              value: data['expectedValue'] != false && data['expectedValue'] != 'false',
              label: 'true',
              onChanged: (bool on) => onChanged(<String, Object?>{...data, 'expectedValue': chest ? on : '$on'}),
            ),
          ),
          icon('trueIcon', 'True icon'),
          actions('trueActions', 'True actions'),
          icon('falseIcon', 'False icon'),
          actions('falseActions', 'False actions'),
        ],
        if (type == 'list') ...<Widget>[
          GuiRow(
            label: 'Variable *',
            child: GuiTextInput(value: asString(data['var']) ?? '', label: 'Variable', onChanged: (String value) => onChanged(withField(data, 'var', value))),
          ),
          GuiRow(
            label: 'Source *',
            problems: diagnosticsAt(diagnostics, childPath(path, 'source')),
            child: GuiTextInput(
              value: asString(data['source']) ?? '',
              mode: GuiTextMode.expression,
              names: names,
              label: 'Source',
              onChanged: (String value) => onChanged(withField(data, 'source', value)),
            ),
          ),
          GuiRow(
            label: 'Page size',
            child: GuiNumberInput(value: asInt(data['pageSize']), label: 'Page size', onChanged: (num? value) => onChanged(withField(data, 'pageSize', value))),
          ),
          GuiRow(
            label: 'Columns',
            child: GuiNumberInput(
              value: asInt(asMap(data['flow'])['columns']),
              label: 'Columns',
              onChanged: (num? value) => onChanged(<String, Object?>{...data, 'flow': withField(asMap(data['flow']), 'columns', value)}),
            ),
          ),
          GuiSection(
            title: 'Template',
            children: <Widget>[
              ComponentEditor(
                component: asMap(data['template']),
                path: childPath(path, 'template'),
                diagnostics: diagnostics,
                chest: chest,
                names: names.withLocal(asString(data['var']) ?? 'entry'),
                namedLists: namedLists,
                onChanged: (JsonMap next) => onChanged(<String, Object?>{...data, 'template': next}),
              ),
            ],
          ),
        ],
        if (type == 'slider') ...<Widget>[
          GuiRow(
            label: 'Variable *',
            help: 'The value lives in session.<variable>.',
            child: GuiTextInput(value: asString(data['var']) ?? '', label: 'Variable', onChanged: (String value) => onChanged(withField(data, 'var', value))),
          ),
          for (final String key in const <String>['min', 'max', 'step', 'width'])
            GuiRow(
              label: key,
              problems: diagnosticsAt(diagnostics, childPath(path, key)),
              child: GuiNumberInput(
                value: asDouble(data[key]),
                integer: false,
                label: 'Slider $key',
                onChanged: (num? value) => onChanged(withField(data, key, value)),
              ),
            ),
          GuiRow(
            label: 'Label',
            child: GuiTextInput(
              value: asString(data['label']) ?? '',
              mode: GuiTextMode.template,
              names: names,
              label: 'Slider label',
              onChanged: (String value) => onChanged(withField(data, 'label', value)),
            ),
          ),
        ],
        if (type == 'tabs') ...<Widget>[
          GuiRow(
            label: 'Variable *',
            child: GuiTextInput(value: asString(data['var']) ?? '', label: 'Variable', onChanged: (String value) => onChanged(withField(data, 'var', value))),
          ),
          GuiRow(
            label: 'Tabs',
            help: 'One tab id per line; the label is the id.',
            child: GuiLinesEditor(
              lines: <String>[for (final Object? tab in asList(data['tabs'])) asString(asMap(tab)['label']) ?? asString(asMap(tab)['id']) ?? ''],
              addLabel: 'Add tab',
              onChanged: (List<String> lines) => onChanged(<String, Object?>{
                ...data,
                'tabs': <Object?>[
                  for (int i = 0; i < lines.length; i++) <String, Object?>{'id': _tabId(lines[i], i), 'label': lines[i]},
                ],
              }),
            ),
          ),
        ],
      ],
    ]);
  }

  static String _tabId(String label, int index) {
    final String id = label.toLowerCase().replaceAll(RegExp(r'[^a-z0-9_]+'), '_').replaceAll(RegExp(r'^_+|_+$'), '');
    return id.isEmpty ? 'tab${index + 1}' : id;
  }

  /// A component of type [type] keeping what still applies.
  JsonMap _convert(JsonMap data, String type) {
    final JsonMap icon = asMap(data['icon']).isNotEmpty ? asMap(data['icon']) : asMap(data['trueIcon']);
    final JsonMap fallback = icon.isNotEmpty
        ? icon
        : <String, Object?>{'type': chest ? 'item' : 'text', if (chest) 'item': 'minecraft:stone' else 'text': 'Text'};
    return switch (type) {
      'button' => <String, Object?>{
        'type': type,
        if (data['show'] != null) 'show': data['show'],
        'icon': fallback,
        'actions': asList(data['actions']).isNotEmpty ? data['actions'] : asList(data['trueActions']),
      },
      'decoration' => <String, Object?>{'type': type, if (data['show'] != null) 'show': data['show'], 'icon': fallback},
      'toggle' => <String, Object?>{
        'type': type,
        if (data['show'] != null) 'show': data['show'],
        'condition': 'session.on == true',
        'expectedValue': chest ? true : 'true',
        'trueIcon': fallback,
        'falseIcon': fallback,
        'trueActions': asList(data['actions']),
        'falseActions': const <Object?>[],
      },
      'list' => <String, Object?>{
        'type': type,
        'var': 'entry',
        'source': "['a', 'b', 'c']",
        'template': <String, Object?>{'type': 'decoration', 'icon': fallback},
      },
      'slider' => <String, Object?>{'type': type, 'var': 'volume', 'min': 0, 'max': 10, 'step': 1, 'label': '&fVolume {{ session.volume }}'},
      'tabs' => <String, Object?>{
        'type': type,
        'var': 'tab',
        'tabs': <Object?>[
          <String, Object?>{'id': 'one', 'label': 'One'},
          <String, Object?>{'id': 'two', 'label': 'Two'},
        ],
      },
      _ => <String, Object?>{'type': type},
    };
  }
}
