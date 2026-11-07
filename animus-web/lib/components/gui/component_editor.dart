/// Component and icon inspectors for chest GUIs and holographic menus. Each
/// offers only the component and icon types the surface accepts in Animus,
/// and the fields core reads for that surface; a refused type is flagged
/// with its field.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_catalog.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_validation.dart';
import '../../model/animus_document.dart';
import '../mc/item_picker.dart';
import 'actions_editor.dart';
import 'gui_fields.dart';
import 'style_editor.dart';

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

/// The text a `show` style condition reads as: an expression, `false`, or
/// blank for always.
String conditionText(Object? value) => value is String ? value : (value == false ? 'false' : '');

/// What a condition field writes: null for blank, a boolean for true and
/// false, otherwise the expression.
Object? conditionValue(String text) {
  final String trimmed = text.trim();
  if (trimmed.isEmpty) return null;
  if (trimmed == 'false') return false;
  if (trimmed == 'true') return true;
  return text;
}

/// Easings a menu button or toggle may hover with (core's MenuDocument).
const List<String> hoverEasings = <String>['linear', 'ease_out_cubic', 'ease_in_out_cubic', 'back_out'];

/// A labelled row whose label points at the control by a path-based id;
/// with [labelled] false (a list of lines) the label is plain text.
Widget guiFieldRow(String label, String path, List<Diagnostic> diagnostics, Widget Function(String id) child, {String? help, bool nested = false, bool labelled = true}) {
  final String id = guiPathId(path);
  return GuiRow(label: label, forId: labelled ? id : null, help: help, problems: diagnosticsAt(diagnostics, path, nested: nested), child: child(id));
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
    Widget text(String key, String label, {GuiTextMode mode = GuiTextMode.template, bool multiline = false, String? help}) => guiFieldRow(
          label,
          childPath(path, key),
          diagnostics,
          help: help,
          (String id) => GuiTextInput(
            id: id,
            value: asString(icon[key]) ?? '',
            mode: mode,
            names: names,
            multiline: multiline,
            label: label,
            onChanged: (String value) => onChanged(withField(icon, key, value)),
          ),
        );
    Widget number(String key, String label, {bool integer = true, String? help}) => guiFieldRow(
          label,
          childPath(path, key),
          diagnostics,
          help: help,
          (String id) => GuiNumberInput(
            id: id,
            value: integer ? asInt(icon[key]) : asDouble(icon[key]),
            integer: integer,
            label: label,
            onChanged: (num? value) => onChanged(withField(icon, key, value)),
          ),
        );
    final String chestIgnored = chest ? ' Ignored in chest windows.' : '';
    final JsonMap box = asMap(icon['box']);
    return dom.div(classes: 'animus-gui-icon', <Widget>[
      guiFieldRow('Icon', childPath(path, 'type'), diagnostics, (String id) => GuiSelect(
            id: id,
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
                if (icon['name'] != null && next != 'text' && next != 'entity') 'name': icon['name'],
                if (icon['lore'] != null && next != 'text' && next != 'entity') 'lore': icon['lore'],
                if (icon['style'] != null && next != 'entity') 'style': icon['style'],
              });
            },
          )),
      if (refused)
        dom.p(
          classes: 'animus-gui-refused',
          attributes: const <String, String>{'role': 'alert'},
          <Widget>[
            dom.code(<Widget>[Component.text(childPath(path, 'type'))]),
            Component.text(' ${refusedIconMessage(type, chest: chest)}.'),
          ],
        )
      else ...<Widget>[
        if (type == 'item') ...<Widget>[
          guiFieldRow(
            'Item',
            childPath(path, 'item'),
            diagnostics,
            help: 'Item id or expression',
            (String id) => ItemPicker(id: id, value: asString(icon['item']) ?? '', label: 'Item', onChanged: (String value) => onChanged(withField(icon, 'item', value))),
          ),
          number('count', 'Count', help: '1 to 99'),
          number('customModelValue', 'Custom model'),
        ],
        if (type == 'playerHead') ...<Widget>[
          text('player', 'Player', help: 'Player whose skin to show'),
          number('refreshTicks', 'Skin refresh', help: 'Ticks between skin checks.$chestIgnored'),
        ],
        if (type == 'item' || type == 'playerHead') text('countFormat', 'Count format', help: 'Such as {{ count }}x.$chestIgnored'),
        if (type == 'text') ...<Widget>[
          text('text', 'Text', multiline: true),
          number('refreshTicks', 'Refresh', help: '0 to 1200 ticks, default 10'),
        ],
        if (type == 'block') text('block', 'Block', mode: GuiTextMode.plain, help: 'Default minecraft:barrier'),
        if (type == 'entity') ...<Widget>[
          text('entity', 'Entity', mode: GuiTextMode.plain, help: 'Default minecraft:pig'),
          number('width', 'Width', integer: false, help: 'Click box, 0.01 to 64 blocks'),
          number('height', 'Height', integer: false, help: '0.01 to 64 blocks, default 1'),
        ],
        if (type != 'text' && type != 'entity') ...<Widget>[
          text('name', 'Name'),
          guiFieldRow(
            'Lore',
            childPath(path, 'lore'),
            diagnostics,
            nested: true,
            labelled: false,
            (String _) => GuiLinesEditor(lines: stringList(icon['lore']), names: names, onChanged: (List<String> lines) => onChanged(withField(icon, 'lore', lines))),
          ),
        ],
        if (type == 'text' && box.isNotEmpty)
          dom.p(classes: box['enabled'] == true ? 'animus-gui-refused' : 'animus-gui-note', <Widget>[
            dom.code(<Widget>[Component.text(childPath(path, 'box'))]),
            Component.text(box['enabled'] == true
                ? ' box panels are unsupported; use style.backgroundArgb. '
                : ' is off and has no effect. '),
            Button.ghost(label: 'Remove box', size: ButtonSize.small, onPressed: () => onChanged(withField(icon, 'box', null))),
          ]),
        if (!chest && type != 'entity')
          DisplayStyleEditor(
            style: asMap(icon['style']),
            path: childPath(path, 'style'),
            diagnostics: diagnostics,
            note: 'Default: billboard fixed, not see-through.',
            onChanged: (JsonMap? next, String _) => onChanged(withField(icon, 'style', next)),
          ),
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
    this.lists = const <String>[],
    super.key,
  });

  final JsonMap component;
  final void Function(JsonMap component) onChanged;
  final String path;
  final List<Diagnostic> diagnostics;
  final bool chest;
  final ExpressionNames names;
  final List<String> namedLists;

  /// Ids of the menu's list components, for `navigate.list`.
  final List<String> lists;

  @override
  Widget build(BuildContext context) {
    final JsonMap data = component;
    final String type = asString(data['type']) ?? 'decoration';
    final List<String> types = chest ? chestComponentTypes : menuComponentTypes;
    final bool refused = !types.contains(type);
    void set(String key, Object? value) => onChanged(withField(data, key, value));
    Widget actions(String key, String title) => GuiSection(
          title: title,
          count: asList(data[key]).length,
          tip: 'Run in order when the viewer clicks. A failed condition stops the rest.',
          docs: Docs.guiActions,
          children: <Widget>[
            ActionsEditor(
              actions: asList(data[key]),
              path: childPath(path, key),
              diagnostics: diagnostics,
              names: names,
              namedLists: namedLists,
              lists: lists,
              onChanged: (JsonList next) => onChanged(<String, Object?>{...data, key: next}),
            ),
          ],
        );
    Widget icon(String key, String title) => GuiSection(
          title: title,
          tip: chest ? 'The item shown in the slot, with its name and lore.' : 'The item or block shown for this component.',
          docs: chest ? Docs.chestGuis : Docs.menus,
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
    Widget text(String key, String label, {GuiTextMode mode = GuiTextMode.plain, String? help, ExpressionNames? scope}) => guiFieldRow(
          label,
          childPath(path, key),
          diagnostics,
          help: help,
          (String id) => GuiTextInput(id: id, value: asString(data[key]) ?? '', mode: mode, names: scope ?? names, label: label, onChanged: (String value) => set(key, value)),
        );
    Widget number(String key, String label, {bool integer = false, String? help}) => guiFieldRow(
          label,
          childPath(path, key),
          diagnostics,
          help: help,
          (String id) => GuiNumberInput(id: id, value: integer ? asInt(data[key]) : asDouble(data[key]), integer: integer, label: label, onChanged: (num? value) => set(key, value)),
        );
    return dom.div(classes: 'animus-gui-component', <Widget>[
      guiFieldRow('Type', childPath(path, 'type'), diagnostics, (String id) => GuiSelect(
            id: id,
            value: type,
            options: types,
            labels: <String, String>{if (refused) type: '$type (not available here)'},
            onChanged: (String? next) {
              if (next == null || next == type) return;
              onChanged(_convert(data, next));
            },
          )),
      if (refused)
        dom.p(
          classes: 'animus-gui-refused',
          attributes: const <String, String>{'role': 'alert'},
          <Widget>[
            dom.code(<Widget>[Component.text(childPath(path, 'type'))]),
            Component.text(
              chest
                  ? " component '$type' is a holographic menu component and has no meaning in a chest window."
                  : " component '$type' is not available in Animus.",
            ),
          ],
        )
      else ...<Widget>[
        // A menu component carries show beside its data, edited by the menu editor.
        if (chest)
          guiFieldRow(
            'Show when',
            childPath(path, 'show'),
            diagnostics,
            help: 'Blank always shows. false hides it; an expression such as viewer.level > 5 decides per viewer.',
            (String id) => GuiTextInput(
              id: id,
              value: conditionText(data['show']),
              mode: GuiTextMode.expression,
              names: names,
              label: 'Show when',
              placeholder: 'always',
              onChanged: (String value) => onChanged(withField(data, 'show', conditionValue(value))),
            ),
          ),
        if (type == 'button' || type == 'decoration') icon('icon', 'Icon'),
        if (type == 'button') actions('actions', 'Actions'),
        if (type == 'toggle') ...<Widget>[
          text('condition', 'Condition *', mode: GuiTextMode.expression),
          GuiRow(
            label: 'Expected',
            help: 'Value that shows the true side',
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
        if (!chest && (type == 'button' || type == 'toggle')) _hover(data, set),
        if (!chest && type == 'button') _tooltip(data),
        if (type == 'list') ...<Widget>[
          text('var', 'Variable *', help: 'Name for each entry'),
          text('source', 'Source *', mode: GuiTextMode.expression, help: 'Expression giving a list'),
          number('pageSize', 'Page size', integer: true, help: '1 to 64, default 6'),
          _flow(data),
          GuiSection(
            title: 'Template',
            tip: 'The component drawn once per entry. The entry is named by Variable.',
            docs: chest ? Docs.chestGuis : Docs.menus,
            children: <Widget>[
              ComponentEditor(
                component: asMap(data['template']),
                path: childPath(path, 'template'),
                diagnostics: diagnostics,
                chest: chest,
                names: names.withLocal(asString(data['var']) ?? 'entry'),
                namedLists: namedLists,
                lists: lists,
                onChanged: (JsonMap next) => onChanged(<String, Object?>{...data, 'template': next}),
              ),
            ],
          ),
        ],
        if (type == 'slider') ...<Widget>[
          text('var', 'Variable *', help: 'Stored in session.<variable>'),
          number('min', 'Minimum', help: 'Default 0'),
          number('max', 'Maximum', help: 'Default 100, above minimum'),
          number('step', 'Step', help: 'Default 1'),
          number('width', 'Width', help: '0.01 to 64 blocks, default 2'),
          text('label', 'Label', mode: GuiTextMode.template, help: 'Default {{ session.<variable> }}'),
          DisplayStyleEditor(style: asMap(data['style']), path: childPath(path, 'style'), diagnostics: diagnostics, onChanged: (JsonMap? next, String _) => set('style', next)),
        ],
        if (type == 'tabs') ...<Widget>[
          text('var', 'Variable *', help: 'Stores tab id, default tab'),
          number('spacing', 'Spacing', help: 'Gap in blocks, default 1'),
          _tabs(data),
          DisplayStyleEditor(style: asMap(data['style']), path: childPath(path, 'style'), diagnostics: diagnostics, onChanged: (JsonMap? next, String _) => set('style', next)),
        ],
      ],
    ]);
  }

  /// Hover feedback and the click box of a menu button or toggle.
  Widget _hover(JsonMap data, void Function(String key, Object? value) set) {
    final JsonMap hitbox = asMap(data['hitbox']);
    final String hitboxPath = childPath(path, 'hitbox');
    void setHitbox(String key, Object? value) {
      final JsonMap next = withField(hitbox, key, value);
      set('hitbox', next.isEmpty ? null : next);
    }

    final JsonList offset = asList(hitbox['offset']);
    double axis(int i) => asDouble(offset.elementAtOrNull(i)) ?? 0;
    return GuiSection(
      title: 'Hover and click box',
      tip: 'How the component reacts when looked at, and the box that takes the click.',
      docs: Docs.menus,
      open: data.containsKey('hitbox') || data.containsKey('highlightModifier') || data.containsKey('hoverEasing'),
      children: <Widget>[
        guiFieldRow('Highlight', childPath(path, 'highlightModifier'), diagnostics, help: 'Hover growth, -64 to 64',
            (String id) => GuiNumberInput(id: id, value: asDouble(data['highlightModifier']), integer: false, label: 'Highlight', onChanged: (num? v) => set('highlightModifier', v))),
        guiFieldRow('Hover time', childPath(path, 'hoverDurationTicks'), diagnostics, help: '0 to 40 ticks, default 4',
            (String id) => GuiNumberInput(id: id, value: asInt(data['hoverDurationTicks']), label: 'Hover time in ticks', onChanged: (num? v) => set('hoverDurationTicks', v))),
        guiFieldRow('Hover easing', childPath(path, 'hoverEasing'), diagnostics,
            (String id) => GuiSelect(id: id, value: asString(data['hoverEasing']), options: hoverEasings, allowNone: true, noneLabel: 'ease_out_cubic', onChanged: (String? v) => set('hoverEasing', v))),
        guiFieldRow('Box width', childPath(hitboxPath, 'width'), diagnostics, help: '0.01 to 64 blocks.',
            (String id) => GuiNumberInput(id: id, value: asDouble(hitbox['width']), integer: false, label: 'Click box width', onChanged: (num? v) => setHitbox('width', v))),
        guiFieldRow('Box height', childPath(hitboxPath, 'height'), diagnostics, help: '0.01 to 64 blocks.',
            (String id) => GuiNumberInput(id: id, value: asDouble(hitbox['height']), integer: false, label: 'Click box height', onChanged: (num? v) => setHitbox('height', v))),
        GuiRow(
          label: 'Box offset',
          help: 'From the anchor, within 64 blocks',
          problems: diagnosticsAt(diagnostics, childPath(hitboxPath, 'offset'), nested: true),
          child: dom.div(classes: 'animus-row animus-gui-xyz', <Widget>[
            for (int i = 0; i < 3; i++)
              GuiNumberInput(
                value: offset.isEmpty ? null : axis(i),
                integer: false,
                label: 'Click box offset ${'xyz'[i]}',
                placeholder: 'xyz'[i],
                onChanged: (num? v) => setHitbox('offset', <double>[axis(0), axis(1), axis(2)]..[i] = (v ?? 0).toDouble()),
              ),
          ]),
        ),
        guiFieldRow('Box anchor', childPath(hitboxPath, 'anchor'), diagnostics, help: 'button: on the component. menu: menu center.',
            (String id) => GuiSelect(id: id, value: asString(hitbox['anchor']), options: const <String>['button', 'menu'], allowNone: true, noneLabel: 'button', onChanged: (String? v) => setHitbox('anchor', v))),
      ],
    );
  }

  Widget _tooltip(JsonMap data) {
    final JsonMap tooltip = asMap(data['tooltip']);
    final String tooltipPath = childPath(path, 'tooltip');
    void setTooltip(String key, Object? value) {
      final JsonMap next = withField(tooltip, key, value is List<Object?> && value.isEmpty ? null : value);
      onChanged(withField(data, 'tooltip', next.isEmpty ? null : next));
    }

    return GuiSection(title: 'Tooltip', open: tooltip.isNotEmpty, tip: 'Text shown beside the button while the viewer looks at it.', docs: Docs.menus, children: <Widget>[
      guiFieldRow('Lines', childPath(tooltipPath, 'lines'), diagnostics, nested: true, labelled: false,
          (String _) => GuiLinesEditor(lines: stringList(tooltip['lines']), names: names, onChanged: (List<String> lines) => setTooltip('lines', lines))),
      guiFieldRow('Delay', childPath(tooltipPath, 'delayTicks'), diagnostics, help: 'Ticks before showing, default 10',
          (String id) => GuiNumberInput(id: id, value: asInt(tooltip['delayTicks']), label: 'Tooltip delay in ticks', onChanged: (num? v) => setTooltip('delayTicks', v))),
      DisplayStyleEditor(title: 'Tooltip style', style: asMap(tooltip['style']), path: childPath(tooltipPath, 'style'), diagnostics: diagnostics, onChanged: (JsonMap? next, String _) => setTooltip('style', next)),
    ]);
  }

  Widget _flow(JsonMap data) {
    final JsonMap flow = asMap(data['flow']);
    final String flowPath = childPath(path, 'flow');
    void setFlow(String key, num? value) {
      final JsonMap next = withField(flow, key, value);
      onChanged(withField(data, 'flow', next.isEmpty ? null : next));
    }

    Widget number(String key, String label, String help, {bool integer = false}) => guiFieldRow(label, childPath(flowPath, key), diagnostics, help: help,
        (String id) => GuiNumberInput(id: id, value: integer ? asInt(flow[key]) : asDouble(flow[key]), integer: integer, label: 'Flow $label', onChanged: (num? v) => setFlow(key, v)));
    return GuiSection(title: 'Flow', open: flow.isNotEmpty, tip: 'The grid the list lays its entries on, in blocks.', docs: Docs.menus, children: <Widget>[
      number('columns', 'Columns', '1 to 64, default 3', integer: true),
      number('spacingX', 'Spacing X', 'Column gap, default 1'),
      number('spacingY', 'Spacing Y', 'Row gap, default 0.5'),
      number('x', 'Start X', 'First cell, default 0'),
      number('y', 'Start Y', 'First cell, default 0'),
    ]);
  }

  /// Tabs with their id and label edited apart, so a label change keeps
  /// the id that session values and conditions use.
  Widget _tabs(JsonMap data) {
    final JsonList tabs = asList(data['tabs']);
    final String tabsPath = childPath(path, 'tabs');
    void setTabs(JsonList next) => onChanged(<String, Object?>{...data, 'tabs': next});
    return GuiSection(title: 'Tabs', count: tabs.length, tip: 'Each tab stores its id in Variable when picked. The label defaults to the id.', docs: Docs.menus, children: <Widget>[
      for (final Diagnostic problem in diagnosticsAt(diagnostics, tabsPath))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < tabs.length; index++)
        dom.div(classes: 'animus-gui-line', attributes: <String, String>{'data-path': indexPath(tabsPath, index)}, <Widget>[
          GuiTextInput(
            value: asString(asMap(tabs[index])['id']) ?? '',
            label: 'Tab ${index + 1} id',
            placeholder: 'id',
            onChanged: (String v) => setTabs(<Object?>[...tabs]..[index] = withField(asMap(tabs[index]), 'id', v)),
          ),
          GuiTextInput(
            value: asString(asMap(tabs[index])['label']) ?? '',
            mode: GuiTextMode.template,
            names: names,
            label: 'Tab ${index + 1} label',
            placeholder: 'label (defaults to id)',
            onChanged: (String v) => setTabs(<Object?>[...tabs]..[index] = withField(asMap(tabs[index]), 'label', v)),
          ),
          ...guiMoveButtons(index: index, length: tabs.length, noun: 'tab ${index + 1}', onMove: (int to) => setTabs(moveItem(tabs, index, to))),
          GuiIconButton(
            icon: ArcaneIcon.trash(size: IconSize.sm),
            label: 'Remove tab ${index + 1}',
            disabled: tabs.length <= 1,
            onPressed: () => setTabs(<Object?>[...tabs]..removeAt(index)),
          ),
        ]),
      Button.ghost(
        label: 'Add tab',
        size: ButtonSize.small,
        disabled: tabs.length >= 32,
        onPressed: () {
          final Set<String> ids = <String>{for (final Object? tab in tabs) ?asString(asMap(tab)['id'])};
          int n = tabs.length + 1;
          while (ids.contains('tab$n')) {
            n++;
          }
          setTabs(<Object?>[...tabs, <String, Object?>{'id': 'tab$n', 'label': 'Tab $n'}]);
        },
      ),
    ]);
  }

  /// A component of type [type] keeping what still applies.
  JsonMap _convert(JsonMap data, String type) {
    final JsonMap icon = asMap(data['icon']).isNotEmpty ? asMap(data['icon']) : asMap(data['trueIcon']);
    final JsonMap fallback = icon.isNotEmpty
        ? icon
        : <String, Object?>{'type': chest ? 'item' : 'text', if (chest) 'item': 'minecraft:stone' else 'text': 'Text'};
    final JsonMap hover = <String, Object?>{
      for (final String key in const <String>['highlightModifier', 'hoverDurationTicks', 'hoverEasing', 'hitbox'])
        if (data.containsKey(key) && (type == 'button' || type == 'toggle')) key: data[key],
    };
    return switch (type) {
      'button' => <String, Object?>{
          'type': type,
          if (data['show'] != null) 'show': data['show'],
          'icon': fallback,
          'actions': asList(data['actions']).isNotEmpty ? data['actions'] : asList(data['trueActions']),
          if (data['tooltip'] != null) 'tooltip': data['tooltip'],
          ...hover,
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
          ...hover,
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
