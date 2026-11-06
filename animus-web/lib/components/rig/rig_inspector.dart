/// The bone inspector: pivot, rest rotation, the visual (item, block or
/// text display, with every field the rig format allows) and the rig's
/// sockets. Text fields commit on Enter or blur.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/rig_edit.dart';

const List<String> socketSlots = <String>['main_hand', 'off_hand', 'head', 'chest', 'legs', 'feet', 'body', 'saddle'];

const List<String> itemContexts = <String>[
  'none',
  'fixed',
  'head',
  'gui',
  'ground',
  'thirdperson_righthand',
  'thirdperson_lefthand',
  'firstperson_righthand',
  'firstperson_lefthand',
];

final RegExp _background = RegExp(r'^#[0-9a-fA-F]{8}$');

typedef RigChange = void Function(JsonMap Function(JsonMap rig) change);

class RigInspector extends StatefulWidget {
  const RigInspector({required this.rig, required this.bone, required this.onEdit, super.key});

  final JsonMap rig;
  final String? bone;
  final RigChange onEdit;

  @override
  State<RigInspector> createState() => _RigInspectorState();
}

class _RigInspectorState extends State<RigInspector> {
  final Map<String, String> _drafts = <String, String>{};
  final Map<String, String> _errors = <String, String>{};

  static List<double> _vector(Object? value, double fallback) =>
      value is List<Object?> && value.length == 3
          ? <double>[for (final Object? component in value) component is num ? component.toDouble() : fallback]
          : <double>[fallback, fallback, fallback];

  static num _round(double value) {
    final double rounded = (value * 100000).roundToDouble() / 100000;
    return rounded == rounded.roundToDouble() ? rounded.round() : rounded;
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap rig = component.rig;
    final String? selected = component.bone;
    final JsonMap? bone = selected == null ? null : boneById(rig, selected);
    if (bone == null) {
      return const dom.div(classes: 'animus-rig-inspector', <Widget>[
        dom.p(classes: 'animus-muted', <Widget>[Component.text('Select a bone in the tree or on the stage to edit it.')]),
      ]);
    }
    final String id = '${bone['id']}';
    final Object? rawVisual = bone['visual'];
    final JsonMap? visual = rawVisual is JsonMap ? rawVisual : null;
    final String type = visual == null ? 'none' : '${visual['type']}';
    void bones(void Function(JsonMap entry) change) => component.onEdit((JsonMap json) => updateBone(json, id, change));
    void setVisual(void Function(JsonMap visual) change) => bones((JsonMap entry) {
      final JsonMap next = entry['visual'] is JsonMap ? entry['visual']! as JsonMap : <String, Object?>{'type': 'none'};
      change(next);
      entry['visual'] = next;
    });
    final JsonMap item = visual?['item'] is JsonMap ? visual!['item']! as JsonMap : const <String, Object?>{};
    final Object? sockets = rig['sockets'];
    return dom.div(classes: 'animus-rig-inspector', <Widget>[
      dom.h3(<Widget>[Component.text('Bone $id')]),
      _vectorField('Pivot', 'pivot:$id', bone['pivot'], 0, (List<num> value) => bones((JsonMap entry) => entry['pivot'] = value)),
      _vectorField('Rest rotation', 'rotation:$id', bone['rotation'], 0, (List<num> value) => bones((JsonMap entry) {
        if (value.every((num component) => component == 0)) {
          entry.remove('rotation');
        } else {
          entry['rotation'] = value;
        }
      })),
      dom.label(classes: 'animus-rig-field', <Widget>[
        const dom.span(<Widget>[Component.text('Visual')]),
        ArcaneSelect(
          size: ComponentSize.sm,
          value: type,
          options: const <ArcaneSelectOption>[
            ArcaneSelectOption(label: 'None (pivot only)', value: 'none'),
            ArcaneSelectOption(label: 'Item display', value: 'item'),
            ArcaneSelectOption(label: 'Block display', value: 'block'),
            ArcaneSelectOption(label: 'Text display', value: 'text'),
          ],
          onChanged: (String value) => bones((JsonMap entry) {
            if (value == 'none') {
              entry.remove('visual');
            } else {
              entry['visual'] = visualOfType(value, entry['visual'] is JsonMap ? entry['visual']! as JsonMap : null);
            }
          }),
        ),
      ]),
      if (visual != null && type == 'item') ...<Widget>[
        _textField('Item', 'item:$id', '${item['id'] ?? ''}', (String value) => setVisual((JsonMap v) {
          final JsonMap next = v['item'] is JsonMap ? v['item']! as JsonMap : <String, Object?>{};
          next['id'] = value;
          v['item'] = next;
        })),
        _textField('Count', 'count:$id', '${item['count'] ?? 1}', (String value) {
          final int? count = int.tryParse(value.trim());
          if (count == null || count < 1 || count > 99) {
            setState(() => _errors['count:$id'] = 'Use a whole number from 1 to 99.');
            return;
          }
          setVisual((JsonMap v) {
            final JsonMap next = v['item'] is JsonMap ? v['item']! as JsonMap : <String, Object?>{'id': 'minecraft:stick'};
            if (count == 1) {
              next.remove('count');
            } else {
              next['count'] = count;
            }
            v['item'] = next;
          });
        }, number: true),
        _textField('Components', 'components:$id', '${item['components'] ?? ''}', (String value) => setVisual((JsonMap v) {
          final JsonMap next = v['item'] is JsonMap ? v['item']! as JsonMap : <String, Object?>{'id': 'minecraft:stick'};
          if (value.trim().isEmpty) {
            next.remove('components');
          } else {
            next['components'] = value;
          }
          v['item'] = next;
        })),
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Item context')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: visual['itemDisplay'] is String ? visual['itemDisplay']! as String : 'none',
            options: <ArcaneSelectOption>[for (final String context in itemContexts) ArcaneSelectOption(label: context, value: context)],
            onChanged: (String value) => setVisual((JsonMap v) {
              if (value == 'none') {
                v.remove('itemDisplay');
              } else {
                v['itemDisplay'] = value;
              }
            }),
          ),
        ]),
      ],
      if (visual != null && type == 'block')
        _textField('Block state', 'block:$id', '${visual['block'] ?? ''}', (String value) => setVisual((JsonMap v) => v['block'] = value)),
      if (visual != null && type == 'text') ...<Widget>[
        _textField('Text', 'text:$id', '${visual['text'] ?? ''}', (String value) => setVisual((JsonMap v) => v['text'] = value)),
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Billboard')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: visual['billboard'] is String ? visual['billboard']! as String : 'fixed',
            options: <ArcaneSelectOption>[
              for (final String mode in <String>['fixed', 'vertical', 'horizontal', 'center']) ArcaneSelectOption(label: mode, value: mode),
            ],
            onChanged: (String value) => setVisual((JsonMap v) => v['billboard'] = value),
          ),
        ]),
        _textField('Background (#AARRGGBB)', 'background:$id', '${visual['background'] ?? ''}', (String value) {
          final String trimmed = value.trim();
          if (trimmed.isNotEmpty && !_background.hasMatch(trimmed)) {
            setState(() => _errors['background:$id'] = 'Use # and eight hex digits, alpha first, like #40000000. Leave it empty for the default.');
            return;
          }
          setVisual((JsonMap v) {
            if (trimmed.isEmpty) {
              v.remove('background');
            } else {
              v['background'] = trimmed;
            }
          });
        }),
        _textField('Line width (pixels)', 'lineWidth:$id', '${visual['lineWidth'] ?? ''}', (String value) {
          final String trimmed = value.trim();
          final int? width = int.tryParse(trimmed);
          if (trimmed.isNotEmpty && (width == null || width < 1 || width > 1024)) {
            setState(() => _errors['lineWidth:$id'] = 'Use a whole number from 1 to 1024, or leave it empty for 200.');
            return;
          }
          setVisual((JsonMap v) {
            if (width == null) {
              v.remove('lineWidth');
            } else {
              v['lineWidth'] = width;
            }
          });
        }, number: true),
        dom.label(classes: 'animus-rig-field is-check', <Widget>[
          dom.input(
            type: dom.InputType.checkbox,
            checked: visual['shadow'] == true,
            events: <String, void Function(web.Event)>{
              'change': (web.Event _) => setVisual((JsonMap v) {
                if (v['shadow'] == true) {
                  v.remove('shadow');
                } else {
                  v['shadow'] = true;
                }
              }),
            },
          ),
          const dom.span(<Widget>[Component.text('Text shadow')]),
        ]),
      ],
      if (visual != null && type != 'none') ...<Widget>[
        _vectorField('Visual offset', 'vt:$id', visual['translation'], 0, (List<num> value) => setVisual((JsonMap v) => v['translation'] = value)),
        _vectorField('Visual rotation', 'vr:$id', visual['rotation'], 0, (List<num> value) => setVisual((JsonMap v) => v['rotation'] = value)),
        _vectorField('Visual scale', 'vs:$id', visual['scale'], 1, (List<num> value) => setVisual((JsonMap v) => v['scale'] = value)),
      ],
      const dom.h3(<Widget>[Component.text('Sockets')]),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Equipment slots that hang from a bone. The stage draws the NPC\'s item there, or a sample item in the rig editor.'),
      ]),
      for (final String slot in socketSlots)
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          dom.span(<Widget>[Component.text(slot)]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: sockets is JsonMap && sockets[slot] is String ? sockets[slot]! as String : '',
            options: <ArcaneSelectOption>[
              const ArcaneSelectOption(label: '(none)', value: ''),
              for (final JsonMap entry in bonesOf(rig)) ArcaneSelectOption(label: '${entry['id']}', value: '${entry['id']}'),
            ],
            onChanged: (String value) => component.onEdit((JsonMap json) {
              final JsonMap out = cloneJson(json);
              final JsonMap map = out['sockets'] is JsonMap ? out['sockets']! as JsonMap : <String, Object?>{};
              if (value.isEmpty) {
                map.remove(slot);
              } else {
                map[slot] = value;
              }
              if (map.isEmpty) {
                out.remove('sockets');
              } else {
                out['sockets'] = map;
              }
              return out;
            }),
          ),
        ]),
    ]);
  }

  Widget _textField(String label, String key, String value, void Function(String value) onCommit, {bool number = false}) {
    void commit() {
      final String? draft = _drafts.remove(key);
      _errors.remove(key);
      if (draft != null && draft != value) onCommit(draft);
      setState(() {});
    }

    final String? error = _errors[key];
    return dom.label(classes: 'animus-rig-field', <Widget>[
      dom.span(<Widget>[Component.text(label)]),
      TextInput(
        size: ComponentSize.sm,
        type: number ? TextInputType.number : TextInputType.text,
        value: _drafts[key] ?? value,
        attributes: <String, String>{'aria-label': label, if (error != null) 'aria-invalid': 'true'},
        onChanged: (String next) => _drafts[key] = next,
        onSubmitted: (String _) => commit(),
        onBlur: commit,
      ),
      if (error != null) dom.span(classes: 'animus-field-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(error)]),
    ]);
  }

  Widget _vectorField(String label, String key, Object? value, double fallback, void Function(List<num> value) onCommit) {
    final List<double> current = _vector(value, fallback);
    void commit() {
      final List<double> next = List<double>.of(current);
      bool changed = false;
      for (int axis = 0; axis < 3; axis++) {
        final String? draft = _drafts.remove('$key:$axis');
        final double? parsed = draft == null ? null : double.tryParse(draft.trim());
        if (parsed != null && parsed.isFinite && parsed.abs() <= 1024 && parsed != next[axis]) {
          next[axis] = parsed;
          changed = true;
        }
      }
      if (changed) onCommit(<num>[for (final double component in next) _round(component)]);
      setState(() {});
    }

    return dom.div(classes: 'animus-rig-field', <Widget>[
      dom.span(<Widget>[Component.text(label)]),
      dom.div(classes: 'animus-rig-vector', <Widget>[
        for (int axis = 0; axis < 3; axis++)
          TextInput(
            size: ComponentSize.sm,
            type: TextInputType.number,
            value: _drafts['$key:$axis'] ?? '${_round(current[axis])}',
            attributes: <String, String>{'aria-label': '$label ${const <String>['X', 'Y', 'Z'][axis]}', 'step': 'any'},
            onChanged: (String next) => _drafts['$key:$axis'] = next,
            onSubmitted: (String _) => commit(),
            onBlur: commit,
          ),
      ]),
    ]);
  }
}
