/// The holographic menu editor: the menu's placement and behaviour, its
/// components with their offsets, motion, data, icons and actions, menu
/// constants and named action lists, drawn live in the 3D stage in front of
/// the viewer. A click on the stage selects the component under it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/text_animation.dart';
import '../../mc/gui/gui_scene.dart';
import '../../model/animus_document.dart';
import 'actions_editor.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'gui_stage_preview.dart';

GuiAnimationClock _clock(AnimusDocument document, Map<String, JsonMap> animations, int nowMs) =>
    GuiAnimationClock(from: document.id, animations: animations, nowMs: nowMs);

class MenuPreview extends StatelessWidget {
  const MenuPreview({required this.document, this.animations = const <String, JsonMap>{}, super.key});

  final AnimusDocument document;

  /// The workspace's text animations by id.
  final Map<String, JsonMap> animations;

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = asMap(document.json);
    return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
      GuiStagePreview(
        scene: menuScene(doc, clock: _clock(document, animations, DateTime.now().millisecondsSinceEpoch)),
        sceneAt: (int nowMs) => menuScene(doc, clock: _clock(document, animations, nowMs)),
        tickMs: animationTickMs(document.source, document.id, animations),
        signature: document.source,
        label: 'Menu ${document.id}',
      ),
    ]);
  }
}

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
  int _selected = 0;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) =>
      component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<String> get _namedLists => asMap(_doc['actions']).keys.toList();

  Widget _vector(List<JsonStep> steps, String label, {String? help}) {
    final JsonList value = asList(readIn(_doc, steps));
    double axis(int i) => asDouble(value.elementAtOrNull(i)) ?? 0;
    return GuiRow(
      label: label,
      help: help,
      problems: diagnosticsAt(_diagnostics, formatPath(steps), nested: true),
      child: dom.div(classes: 'animus-row animus-gui-xyz', <Widget>[
        for (int i = 0; i < 3; i++)
          GuiNumberInput(
            value: axis(i),
            integer: false,
            label: '$label ${'xyz'[i]}',
            onChanged: (num? v) => _write(steps, <double>[axis(0), axis(1), axis(2)]..[i] = (v ?? 0).toDouble(), coalesce: '${steps.join('.')}.$i'),
          ),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final JsonList components = asList(doc['components']);
    if (_selected >= components.length) _selected = components.isEmpty ? 0 : components.length - 1;
    final String? selectedId = components.isEmpty ? null : asString(asMap(components[_selected])['id']);
    return dom.div(classes: 'animus-gui-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-stage', <Widget>[
        GuiStagePreview(
          scene: menuScene(doc, selected: selectedId, clock: _clock(component.document, component.animations, DateTime.now().millisecondsSinceEpoch)),
          sceneAt: (int nowMs) => menuScene(doc, selected: selectedId, clock: _clock(component.document, component.animations, nowMs)),
          tickMs: animationTickMs(component.document.source, component.document.id, component.animations),
          signature: '${component.document.source}|$selectedId',
          label: 'Menu ${component.document.id}',
          selected: selectedId,
          onPick: (String id) {
            final int index = components.indexWhere((Object? c) => asString(asMap(c)['id']) == id);
            if (index >= 0) setState(() => _selected = index);
          },
        ),
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Click a component on the stage or in the list to edit it; the pink ring marks the one selected.'),
        ]),
        dom.ul(classes: 'animus-gui-component-list', attributes: const <String, String>{'role': 'list', 'aria-label': 'Components'}, <Widget>[
          for (int index = 0; index < components.length; index++)
            dom.li(<Widget>[
              Button.ghost(
                label: '${asString(asMap(components[index])['id']) ?? index} (${asString(asMap(asMap(components[index])['data'])['type']) ?? '?'})',
                size: ButtonSize.small,
                attributes: <String, String>{'aria-pressed': '${index == _selected}'},
                onPressed: () => setState(() => _selected = index),
              ),
            ]),
          dom.li(<Widget>[
            Button.secondary(
              label: 'Add component',
              size: ButtonSize.small,
              onPressed: () {
                final Set<String> ids = <String>{for (final Object? c in components) ?asString(asMap(c)['id'])};
                int n = components.length + 1;
                while (ids.contains('component$n')) {
                  n++;
                }
                _write(<JsonStep>['components'], <Object?>[
                  ...components,
                  <String, Object?>{
                    'id': 'component$n',
                    'offset': <double>[0, 0, 0],
                    'data': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'text', 'text': '&fText'}},
                  },
                ]);
                setState(() => _selected = components.length);
              },
            ),
          ]),
        ]),
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        if (components.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('This menu has no components. Add a component to put something in front of the viewer.')])
        else
          GuiSection(title: 'Component $selectedId', children: _component(components)),
        GuiSection(title: 'Menu', open: components.isEmpty, children: <Widget>[
          _vector(const <JsonStep>['offset'], 'Offset *', help: 'From the viewer\'s feet to the menu centre, in blocks.'),
          GuiRow(
            label: 'Movement',
            child: dom.div(classes: 'animus-row', <Widget>[
              GuiSwitch(value: doc['lockPosition'] == true, label: 'Lock the viewer', onChanged: (bool on) => _write(<JsonStep>['lockPosition'], on ? true : null)),
              GuiSwitch(value: doc['followPlayer'] == true, label: 'Follow the viewer', onChanged: (bool on) => _write(<JsonStep>['followPlayer'], on ? true : null)),
            ]),
          ),
          GuiRow(
            label: 'Max distance',
            help: 'Blocks before the menu closes.',
            child: GuiNumberInput(value: asDouble(doc['maxDistance']), integer: false, label: 'Max distance', onChanged: (num? v) => _write(<JsonStep>['maxDistance'], v)),
          ),
          GuiRow(
            label: 'Close on',
            child: dom.div(classes: 'animus-row', <Widget>[
              GuiSwitch(value: doc['closeOnDeath'] == true, label: 'Death', onChanged: (bool on) => _write(<JsonStep>['closeOnDeath'], on ? true : null)),
              GuiSwitch(value: doc['closeOnTeleport'] == true, label: 'Teleport', onChanged: (bool on) => _write(<JsonStep>['closeOnTeleport'], on ? true : null)),
            ]),
          ),
          guiFieldRow('Show when', r'$.show', _diagnostics, (String id) => GuiTextInput(
                id: id,
                value: conditionText(doc['show']),
                mode: GuiTextMode.expression,
                label: 'Show when',
                placeholder: 'always',
                onChanged: (String v) => _write(<JsonStep>['show'], conditionValue(v)),
              )),
        ]),
        GuiSection(title: 'Constants', count: asMap(doc['vars']).length, open: false, children: _vars(doc)),
        GuiSection(title: 'Named actions', count: _namedLists.length, open: false, children: _named(doc)),
      ]),
    ]);
  }

  List<Widget> _component(JsonList components) {
    final int index = _selected;
    final JsonMap entry = asMap(components[index]);
    final String path = '\$.components[$index]';
    return <Widget>[
      GuiRow(
        label: 'Id *',
        problems: diagnosticsAt(_diagnostics, '$path.id'),
        child: GuiTextInput(value: asString(entry['id']) ?? '', label: 'Component id', onChanged: (String v) => _write(<JsonStep>['components', index, 'id'], v)),
      ),
      _vector(<JsonStep>['components', index, 'offset'], 'Offset *', help: 'From the menu centre, in blocks.'),
      guiFieldRow('Show when', '$path.show', _diagnostics, (String id) => GuiTextInput(
            id: id,
            value: conditionText(entry['show']),
            mode: GuiTextMode.expression,
            label: 'Component show when',
            placeholder: 'always',
            onChanged: (String v) => _write(<JsonStep>['components', index, 'show'], conditionValue(v)),
          )),
      _motion(entry, index, path),
      ComponentEditor(
        key: ValueKey<String>('$path.data'),
        component: asMap(entry['data']),
        path: '$path.data',
        diagnostics: _diagnostics,
        chest: false,
        namedLists: _namedLists,
        names: ExpressionNames(animations: animationCompletionIds(component.document.id, component.animations.keys)),
        onChanged: (JsonMap next) => _write(<JsonStep>['components', index, 'data'], next),
      ),
      dom.div(classes: 'animus-row', <Widget>[
        ...guiMoveButtons(index: index, length: components.length, noun: 'component', onMove: (int to) {
          _write(<JsonStep>['components'], moveItem(components, index, to));
          setState(() => _selected = to);
        }),
        Button.ghost(
          label: 'Remove component',
          size: ButtonSize.small,
          onPressed: () => _write(<JsonStep>['components'], <Object?>[...components]..removeAt(index)),
        ),
      ]),
    ];
  }

  /// How the component eases into place when the menu opens: an offset and
  /// a scale it starts from, over a number of ticks. Each may be an
  /// expression.
  Widget _motion(JsonMap entry, int index, String path) {
    final JsonMap motion = asMap(entry['motion']);
    void set(String key, Object? value) {
      final JsonMap next = withField(motion, key, value);
      _write(<JsonStep>['components', index, 'motion'], next.isEmpty ? null : next, coalesce: '$path.motion.$key');
    }

    Widget field(String key, String label, String help) => guiFieldRow(label, '$path.motion.$key', _diagnostics, help: help,
        (String id) => GuiNumberOrExpression(id: id, value: motion[key], label: 'Motion $label', onChanged: (Object? v) => set(key, v)));
    return GuiSection(title: 'Motion', open: motion.isNotEmpty, children: <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[Component.text('A number or an expression for each; blank keeps the default.')]),
      field('x', 'X', 'Blocks; 0 by default.'),
      field('y', 'Y', 'Blocks; 0 by default.'),
      field('z', 'Z', 'Blocks; 0 by default.'),
      field('scale', 'Scale', '1 by default.'),
      field('ticks', 'Ticks', '0 to 59; 3 by default.'),
    ]);
  }

  /// Menu constants: names readable in this menu's expressions, each a
  /// constant expression.
  List<Widget> _vars(JsonMap doc) {
    final JsonMap vars = asMap(doc['vars']);
    void setAll(JsonMap next) => _write(<JsonStep>['vars'], next.isEmpty ? null : next, coalesce: 'vars-structure');
    return <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Each name holds a constant expression, such as 5 or \'gold\'. Names are lowercase letters, digits and _.'),
      ]),
      for (final MapEntry<String, Object?> entry in vars.entries)
        dom.div(classes: 'animus-gui-line', attributes: <String, String>{'data-path': childPath(r'$.vars', entry.key)}, <Widget>[
          GuiTextInput(
            value: entry.key,
            label: 'Constant name',
            onChanged: (String next) {
              if (!RegExp(r'^[a-z][a-z0-9_]*$').hasMatch(next) || vars.containsKey(next)) return;
              setAll(renameKey(vars, entry.key, next));
            },
          ),
          GuiTextInput(
            value: asString(entry.value) ?? '',
            mode: GuiTextMode.expression,
            label: 'Value of ${entry.key}',
            onChanged: (String v) => _write(<JsonStep>['vars', entry.key], v, coalesce: 'vars.${entry.key}'),
          ),
          GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove constant ${entry.key}', onPressed: () => setAll(<String, Object?>{...vars}..remove(entry.key))),
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, childPath(r'$.vars', entry.key)))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
        ]),
      Button.secondary(
        label: 'Add constant',
        size: ButtonSize.small,
        onPressed: () {
          int n = vars.length + 1;
          while (vars.containsKey('value$n')) {
            n++;
          }
          setAll(<String, Object?>{...vars, 'value$n': '0'});
        },
      ),
    ];
  }

  List<Widget> _named(JsonMap doc) {
    final JsonMap named = asMap(doc['actions']);
    return <Widget>[
      for (final MapEntry<String, Object?> entry in named.entries)
        GuiSection(title: entry.key, count: asList(entry.value).length, open: false, children: <Widget>[
          guiFieldRow('Name', childPath(r'$.actions', entry.key), _diagnostics, help: 'Letters, digits, _ and -; call uses it.', (String id) => GuiTextInput(
                id: id,
                value: entry.key,
                label: 'List name',
                onChanged: (String next) {
                  if (!RegExp(r'^[A-Za-z0-9_-]{1,64}$').hasMatch(next) || named.containsKey(next)) return;
                  _write(<JsonStep>['actions'], renameKey(named, entry.key, next));
                },
              )),
          ActionsEditor(
            actions: asList(entry.value),
            path: childPath(r'$.actions', entry.key),
            diagnostics: _diagnostics,
            namedLists: _namedLists,
            onChanged: (JsonList next) => _write(<JsonStep>['actions', entry.key], next),
          ),
          Button.ghost(
            label: 'Remove list ${entry.key}',
            size: ButtonSize.small,
            onPressed: () => _write(<JsonStep>['actions'], named.length == 1 ? null : (<String, Object?>{...named}..remove(entry.key))),
          ),
        ]),
      Button.secondary(
        label: 'Add named list',
        size: ButtonSize.small,
        onPressed: () {
          int n = named.length + 1;
          while (named.containsKey('list$n')) {
            n++;
          }
          _write(<JsonStep>['actions', 'list$n'], <Object?>[]);
        },
      ),
    ];
  }
}
