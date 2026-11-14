/// The holographic menu page: its components as a reorderable list (a
/// click on one in the preview opens it), each with its place, what it
/// does and, under More, when it shows and how it moves in; the Menu tab
/// holds where the menu opens, what closes it, constants and named action
/// lists.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/diagnostic_text.dart';
import '../../logic/gui/action_targets.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/text_animation.dart';
import '../../mc/gui/gui_scene.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/tabs.dart';
import 'actions_editor.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import '../../ui/menu.dart';
import 'gui_rows.dart';
import 'gui_split.dart';
import 'gui_stage_preview.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../logic/suggest/field_profile.dart';
import '../../ui/number_input.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

GuiAnimationClock _clock(AnimusDocument document, Map<String, JsonMap> animations, int nowMs) => GuiAnimationClock(from: document.id, animations: animations, nowMs: nowMs);

/// What each component kind does, for the Add component menu.
const Map<String, String> componentDescriptions = <String, String>{
  'decoration': 'Text, an item or a block that does nothing.',
  'button': 'Runs actions when clicked.',
  'toggle': 'Switches between two looks, with actions for each.',
  'list': 'Repeats a template for every entry of a list.',
  'slider': 'Picks a number along a bar.',
  'tabs': 'A row of tabs; the picked one is remembered.',
};

/// A new menu component of [type] named [id].
JsonMap newMenuComponent(String type, String id) => <String, Object?>{
  'id': id,
  'offset': <double>[0, 0, 0],
  'data': switch (type) {
    'button' => <String, Object?>{
      'type': type,
      'icon': <String, Object?>{'type': 'text', 'text': '&aClick me'},
      'actions': <Object?>[],
    },
    'toggle' => <String, Object?>{
      'type': type,
      'condition': 'session.on == true',
      'expectedValue': 'true',
      'trueIcon': <String, Object?>{'type': 'text', 'text': '&aOn'},
      'falseIcon': <String, Object?>{'type': 'text', 'text': '&cOff'},
      'trueActions': <Object?>[],
      'falseActions': <Object?>[],
    },
    'list' => <String, Object?>{
      'type': type,
      'var': 'entry',
      'source': "['a', 'b', 'c']",
      'template': <String, Object?>{
        'type': 'decoration',
        'icon': <String, Object?>{'type': 'text', 'text': '{{ entry }}'},
      },
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
    _ => <String, Object?>{
      'type': 'decoration',
      'icon': <String, Object?>{'type': 'text', 'text': '&fText'},
    },
  },
};

class MenuEditor extends StatefulWidget {
  const MenuEditor({required this.document, required this.onJson, this.animations = const <String, JsonMap>{}, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's text animations by id.
  final Map<String, JsonMap> animations;

  @override
  State<MenuEditor> createState() => _MenuEditorState();
}

class _MenuEditorState extends State<MenuEditor> {
  int _tab = 0;

  /// The open component; the preview rings it.
  int? _selected;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<String> get _namedLists => asMap(_doc['actions']).keys.toList();

  ExpressionNames get _names => ExpressionNames(animations: animationCompletionIds(component.document.id, component.animations.keys));

  Widget _vector(List<JsonStep> steps, String label, {String? help, String? tip}) {
    final JsonList value = asList(readIn(_doc, steps));
    double axis(int i) => asDouble(value.elementAtOrNull(i)) ?? 0;
    return Field(
      label: label,
      help: help,
      tip: tip,
      problems: diagnosticsAt(_diagnostics, formatPath(steps), nested: true),
      child: axisLine(
        label: label,
        id: guiPathId(formatPath(steps)),
        values: <num?>[axis(0), axis(1), axis(2)],
        onChanged: (int i, num? v) => _write(steps, <double>[axis(0), axis(1), axis(2)]..[i] = (v ?? 0).toDouble(), coalesce: '${steps.join('.')}.$i'),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final JsonList components = asList(doc['components']);
    final int? selected = _selected != null && _selected! < components.length ? _selected : null;
    final String? selectedId = selected == null ? null : asString(asMap(components[selected])['id']);
    final int menuErrors = _diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error && !d.path.startsWith(r'$.components')).length;
    return GuiSplit(
      label: 'Menu',
      form: <Widget>[
        AppTabs(label: 'Menu', tabs: <String>['Components', menuErrors == 0 ? 'Menu' : 'Menu ($menuErrors)'], selected: _tab, onSelect: (int index) => setState(() => _tab = index)),
        if (_tab == 0) _components(components, selected) else _menu(doc),
      ],
      preview: (bool band) => GuiStagePreview(
        scene: menuScene(doc, selected: selectedId, clock: _clock(component.document, component.animations, DateTime.now().millisecondsSinceEpoch)),
        sceneAt: (int nowMs) => menuScene(doc, selected: selectedId, clock: _clock(component.document, component.animations, nowMs)),
        tickMs: animationTickMs(component.document.source, component.document.id, component.animations),
        signature: '${component.document.source}|$selectedId',
        label: 'Menu ${component.document.id}',
        selected: selectedId,
        band: band,
        onPick: (String id) {
          final int index = components.indexWhere((Object? c) => asString(asMap(c)['id']) == id);
          if (index >= 0) {
            setState(() {
              _selected = index;
              _tab = 0;
            });
          }
        },
      ),
    );
  }

  // ---- Components ----

  Widget _components(JsonList components, int? selected) {
    void add(String type) {
      final Set<String> ids = <String>{for (final Object? c in components) ?asString(asMap(c)['id'])};
      int n = components.length + 1;
      while (ids.contains('$type$n')) {
        n++;
      }
      _write(const <JsonStep>['components'], <Object?>[...components, newMenuComponent(type, '$type$n')]);
      setState(() => _selected = components.length);
    }

    return stack(spacing: 16, <Widget>[
      Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, gapSize: Gap.md, children: <Widget>[
        mutedText(components.length == 1 ? '1 component' : '${components.length} components'),
        AppMenu(
          id: 'animus-menu-add',
          label: 'Add component',
          icon: ArcaneIcon.plus(),
          primary: true,
          alignEnd: true,
          entries: <AppMenuEntry>[for (final MapEntry<String, String> kind in componentTypeLabels.entries) AppMenuEntry(kind.value, () => add(kind.key), description: componentDescriptions[kind.key])],
        ),
      ]),
      if (components.isEmpty)
        mutedText('No components yet.')
      else
        OrderedRows(
          label: 'Components',
          deleteNoun: 'component',
          rows: <OrderedRow>[for (int index = 0; index < components.length; index++) _componentRow(components, index, selected == index)],
          onMove: (int from, int to) {
            _write(const <JsonStep>['components'], moveItem(components, from, to));
            if (selected == from) setState(() => _selected = to);
          },
          onDuplicate: (int index) {
            final JsonMap copy = <String, Object?>{...asMap(deepCopy(components[index])), 'id': '${asString(asMap(components[index])['id']) ?? 'component'}_copy'};
            _write(const <JsonStep>['components'], <Object?>[...components]..insert(index + 1, copy));
            setState(() => _selected = index + 1);
          },
          onDelete: (int index) {
            if (selected == index) setState(() => _selected = null);
            _write(const <JsonStep>['components'], <Object?>[...components]..removeAt(index));
          },
        ),
    ]);
  }

  OrderedRow _componentRow(JsonList components, int index, bool open) {
    final JsonMap entry = asMap(components[index]);
    final String path = '\$.components[$index]';
    final String id = asString(entry['id']) ?? 'Component ${index + 1}';
    final String type = asString(asMap(entry['data'])['type']) ?? '';
    final int errors = diagnosticsAt(_diagnostics, path, nested: true).where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    return OrderedRow(
      key: '$index',
      name: id,
      open: open,
      attributes: <String, String>{'data-path': path},
      line: RowOpener(
        open: open,
        label: '$id, ${errors > 0 ? (errors == 1 ? '1 problem' : '$errors problems') : (componentTypeLabels[type] ?? type)}',
        onToggle: () => setState(() => _selected = open ? null : index),
        children: <Widget>[
          truncatedText(id),
          mutedText(errors > 0 ? (errors == 1 ? '1 problem' : '$errors problems') : (componentTypeLabels[type] ?? type)),
        ],
        body: open ? _componentForm(entry, index, path) : null,
      ),
    );
  }

  Widget _componentForm(JsonMap entry, int index, String path) {
    final JsonMap motion = asMap(entry['motion']);
    void setMotion(String key, Object? value) {
      final JsonMap next = withField(motion, key, value);
      _write(<JsonStep>['components', index, 'motion'], next.isEmpty ? null : next, coalesce: '$path.motion.$key');
    }

    Widget motionField(String key, String label, String help) => guiFieldRow(
      label,
      '$path.motion.$key',
      _diagnostics,
      help: help,
      (String id) => GuiNumberOrExpression(id: id, value: motion[key], label: 'Motion $label', onChanged: (Object? v) => setMotion(key, v)),
    );
    return stack(<Widget>[
      guiFieldRow(
        'Name',
        '$path.id',
        _diagnostics,
        tip: 'Unique in the menu. Actions and the preview find the component by it.',
        (String id) => AutocompleteInput(id: id, value: asString(entry['id']) ?? '', label: 'Component name', onChanged: (String v) => _write(<JsonStep>['components', index, 'id'], v), profile: FieldProfile.none),
      ),
      _vector(<JsonStep>['components', index, 'offset'], 'Place', tip: 'Blocks from the menu center: right, up, toward the viewer.'),
      ComponentEditor(
        key: ValueKey<String>('$path.data'),
        component: asMap(entry['data']),
        path: '$path.data',
        diagnostics: _diagnostics,
        chest: false,
        namedLists: _namedLists,
        lists: menuListIds(_doc),
        names: _names,
        onChanged: (JsonMap next) => _write(<JsonStep>['components', index, 'data'], next),
      ),
      MoreFields(
        open: entry['show'] != null || motion.isNotEmpty,
        children: <Widget>[
          guiFieldRow(
            'Show when',
            '$path.show',
            _diagnostics,
            help: showWhenHelp,
            tip: showWhenTip,
            (String id) => AutocompleteInput(
              id: id,
              value: conditionText(entry['show']),
              profile: FieldProfile.guiExpression,
              gui: _names,
              label: 'Component show when',
              onChanged: (String v) => _write(<JsonStep>['components', index, 'show'], conditionValue(v)),
            ),
          ),
          AppSection(
            title: 'Opening motion',
            open: motion.isNotEmpty,
            tip: 'Where and how small it starts when the menu opens, and how long it takes to settle.',
            docs: Docs.menus,
            children: <Widget>[
              motionField('x', 'From x', 'Blocks, default 0'),
              motionField('y', 'From y', 'Blocks, default 0'),
              motionField('z', 'From z', 'Blocks, default 0'),
              motionField('scale', 'From scale', 'Default 1'),
              motionField('ticks', 'Ticks', '0 to 59, default 3'),
            ],
          ),
        ],
      ),
    ]);
  }

  // ---- Menu ----

  Widget _menu(JsonMap doc) {
    final List<Diagnostic> d = _diagnostics;
    Widget flag(String key, String label, String switchLabel) => Field(
      label: label,
      child: ArcaneToggleSwitch(value: doc[key] == true, label: switchLabel, onChanged: (bool on) => _write(<JsonStep>[key], on ? true : null), size: ComponentSize.sm),
    );
    return stack(spacing: 16, <Widget>[
      _vector(const <JsonStep>['offset'], 'Opens at', tip: 'Blocks from the viewer\'s feet: right, up, ahead.'),
      flag('followPlayer', 'Follows', 'Moves with the viewer'),
      flag('lockPosition', 'Holds still', 'Keeps the viewer in place'),
      guiFieldRow(
        'Closes after',
        r'$.maxDistance',
        d,
        help: 'Blocks walked away',
        (String id) => NumberInput(id: id, value: asDouble(doc['maxDistance']), integer: false, label: 'Closes after blocks', onChanged: (num? v) => _write(const <JsonStep>['maxDistance'], v)),
      ),
      flag('closeOnDeath', 'On death', 'Closes the menu'),
      flag('closeOnTeleport', 'On teleport', 'Closes the menu'),
      guiFieldRow(
        'Show when',
        r'$.show',
        d,
        help: showWhenHelp,
        tip: showWhenTip,
        (String id) => AutocompleteInput(
          id: id,
          value: conditionText(doc['show']),
          profile: FieldProfile.guiExpression,
          gui: _names,
          label: 'Show when',
          onChanged: (String v) => _write(const <JsonStep>['show'], conditionValue(v)),
        ),
      ),
      _vars(doc),
      NamedActionLists(named: asMap(doc['actions']), diagnostics: d, lists: menuListIds(doc), onChanged: (JsonMap? next) => _write(const <JsonStep>['actions'], next)),
    ]);
  }

  /// Menu constants: names every expression in the menu can read, each a
  /// constant expression.
  Widget _vars(JsonMap doc) {
    final JsonMap vars = asMap(doc['vars']);
    final List<String> names = vars.keys.toList();
    void setAll(JsonMap next) => _write(const <JsonStep>['vars'], next.isEmpty ? null : next, coalesce: 'vars-structure');
    return AppSection(
      title: 'Constants',
      count: vars.length,
      open: vars.isNotEmpty,
      tip: 'Named values such as 5 or \'gold\' that every expression in the menu can read. Names use a-z, 0-9 and _.',
      docs: Docs.guiExpressions,
      children: <Widget>[
        stack(<Widget>[
          if (names.isNotEmpty)
            OrderedRows(
              label: 'Constants',
              dense: false,
              rows: <OrderedRow>[
                for (final String name in names)
                  OrderedRow(
                    // By position: renaming keeps the row and its field.
                    key: '${names.indexOf(name)}',
                    name: 'constant $name',
                    open: diagnosticsAt(_diagnostics, childPath(r'$.vars', name)).isNotEmpty,
                    attributes: <String, String>{'data-path': childPath(r'$.vars', name)},
                    line: equalLine(flex: const <int>[1, 2], <Widget>[
                      AutocompleteInput(
                        value: name,
                        label: 'Constant name',
                        onChanged: (String next) {
                          if (!RegExp(r'^[a-z][a-z0-9_]*$').hasMatch(next) || vars.containsKey(next)) return;
                          setAll(renameKey(vars, name, next));
                        },
                        profile: FieldProfile.none,
                      ),
                      AutocompleteInput(
                        value: asString(vars[name]) ?? '',
                        profile: FieldProfile.guiExpression,
                        label: 'Value of $name',
                        onChanged: (String v) => _write(<JsonStep>['vars', name], v, coalesce: 'vars.$name'),
                      ),
                    ]),
                    body: diagnosticsAt(_diagnostics, childPath(r'$.vars', name)).isEmpty
                        ? null
                        : stack(<Widget>[
                            for (final Diagnostic problem in diagnosticsAt(_diagnostics, childPath(r'$.vars', name))) AppAlert(tone: AlertTone.warning, message: diagnosticSentence(problem)),
                          ]),
                  ),
              ],
              onDelete: (int index) => setAll(<String, Object?>{...vars}..remove(names[index])),
            ),
          ActionRow(
            dense: true,
            label: 'Constants',
            secondary: <RowAction>[
              RowAction('Add constant', () {
                int n = vars.length + 1;
                while (vars.containsKey('value$n')) {
                  n++;
                }
                setAll(<String, Object?>{...vars, 'value$n': '0'});
              }, icon: ArcaneIcon.plus()),
            ],
          ),
        ]),
      ],
    );
  }
}
