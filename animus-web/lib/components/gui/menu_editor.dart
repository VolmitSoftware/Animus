/// The holographic menu editor: the menu's placement and behaviour, its
/// components with their offsets, data, icons and actions, and named action
/// lists, drawn live in the 3D stage in front of the viewer.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

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
      component.onJson(writeIn(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

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
        ),
        dom.ul(classes: 'animus-gui-component-list', attributes: const <String, String>{'role': 'list', 'aria-label': 'Components'}, <Widget>[
          for (int index = 0; index < components.length; index++)
            dom.li(<Widget>[
              Button(
                label: '${asString(asMap(components[index])['id']) ?? index} (${asString(asMap(asMap(components[index])['data'])['type']) ?? '?'})',
                size: ButtonSize.small,
                variant: index == _selected ? ButtonVariant.primary : ButtonVariant.ghost,
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
        if (components.isNotEmpty) GuiSection(title: 'Component $selectedId', children: _component(components)),
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
          GuiRow(
            label: 'Show when',
            problems: diagnosticsAt(_diagnostics, r'$.show'),
            child: GuiTextInput(
              value: doc['show'] is String ? doc['show']! as String : '',
              mode: GuiTextMode.expression,
              label: 'Show when',
              onChanged: (String v) => _write(<JsonStep>['show'], v.isEmpty ? null : v),
            ),
          ),
        ]),
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
      GuiRow(
        label: 'Show when',
        problems: diagnosticsAt(_diagnostics, '$path.show'),
        child: GuiTextInput(
          value: entry['show'] is String ? entry['show']! as String : '',
          mode: GuiTextMode.expression,
          label: 'Component show when',
          onChanged: (String v) => _write(<JsonStep>['components', index, 'show'], v.isEmpty ? null : v),
        ),
      ),
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
        GuiIconButton(
          icon: ArcaneIcon.arrowUp(size: IconSize.sm),
          label: 'Move component earlier',
          disabled: index == 0,
          onPressed: () {
            _write(<JsonStep>['components'], moveItem(components, index, index - 1));
            setState(() => _selected = index - 1);
          },
        ),
        Button.ghost(
          label: 'Remove component',
          size: ButtonSize.small,
          onPressed: () => _write(<JsonStep>['components'], <Object?>[...components]..removeAt(index)),
        ),
      ]),
    ];
  }

  List<Widget> _named(JsonMap doc) {
    final JsonMap named = asMap(doc['actions']);
    return <Widget>[
      for (final MapEntry<String, Object?> entry in named.entries)
        GuiSection(title: entry.key, count: asList(entry.value).length, open: false, children: <Widget>[
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
