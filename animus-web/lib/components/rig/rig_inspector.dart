/// The bone inspector: pivot, rest rotation, the visual (item, block or
/// text display; rare display settings under More) and the rig's sockets,
/// one row per equipment slot. Text saves when the field is left or Enter
/// is pressed.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;

import '../../config/docs_links.dart';
import '../../logic/rig_edit.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import 'rig_inputs.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';

/// Equipment slots a bone can hold, with their names.
const Map<String, String> socketSlots = <String, String>{
  'main_hand': 'Main hand',
  'off_hand': 'Off hand',
  'head': 'Head',
  'chest': 'Chest',
  'legs': 'Legs',
  'feet': 'Feet',
  'body': 'Body',
  'saddle': 'Saddle',
};

/// How an item display is held, with its name.
const Map<String, String> itemContexts = <String, String>{
  'none': 'None',
  'fixed': 'Fixed',
  'head': 'On the head',
  'gui': 'In a slot',
  'ground': 'On the ground',
  'thirdperson_righthand': 'Right hand',
  'thirdperson_lefthand': 'Left hand',
  'firstperson_righthand': 'First person right',
  'firstperson_lefthand': 'First person left',
};

const Map<String, String> _visualTypes = <String, String>{'none': 'None', 'item': 'Item', 'block': 'Block', 'text': 'Text'};

const Map<String, String> _billboards = <String, String>{
  'fixed': 'Fixed',
  'vertical': 'Turns sideways',
  'horizontal': 'Tilts',
  'center': 'Faces the player',
};

final RegExp _background = RegExp(r'^#[0-9a-fA-F]{8}$');

typedef RigChange = void Function(JsonMap Function(JsonMap rig) change);

class RigInspector extends StatelessWidget {
  const RigInspector({required this.rig, required this.bone, required this.onEdit, super.key});

  final JsonMap rig;
  final String? bone;
  final RigChange onEdit;

  static List<double> _vector(Object? value, double fallback) => value is List<Object?> && value.length == 3
      ? <double>[for (final Object? each in value) each is num ? each.toDouble() : fallback]
      : <double>[fallback, fallback, fallback];

  @override
  Widget build(BuildContext context) {
    final String? selected = bone;
    final JsonMap? entry = selected == null ? null : boneById(rig, selected);
    if (entry == null) {
      return dom.div(styles: stackStyles(), <Widget>[
        const dom.h3(styles: groupHeadingStyles, <Widget>[Component.text('Bone')]),
        EmptyState(message: 'Select a bone to edit it.', icon: ArcaneIcon.mousePointer()),
      ]);
    }
    final String id = '${entry['id']}';
    final Object? rawVisual = entry['visual'];
    final JsonMap? visual = rawVisual is JsonMap ? rawVisual : null;
    final String type = visual == null ? 'none' : '${visual['type']}';
    void bones(void Function(JsonMap bone) change) => onEdit((JsonMap json) => updateBone(json, id, change));
    void setVisual(void Function(JsonMap visual) change) => bones((JsonMap bone) {
          final JsonMap next = bone['visual'] is JsonMap ? bone['visual']! as JsonMap : <String, Object?>{'type': 'none'};
          change(next);
          bone['visual'] = next;
        });
    void setItem(void Function(JsonMap item) change) => setVisual((JsonMap v) {
          final JsonMap next = v['item'] is JsonMap ? v['item']! as JsonMap : <String, Object?>{'id': 'minecraft:stick'};
          change(next);
          v['item'] = next;
        });
    final JsonMap item = visual?['item'] is JsonMap ? visual!['item']! as JsonMap : const <String, Object?>{};
    final Object? sockets = rig['sockets'];
    final int assigned = sockets is JsonMap ? sockets.length : 0;
    final List<Widget> more = <Widget>[
      if (visual != null && type == 'item') ...<Widget>[
        CommitField(
          key: ValueKey<String>('components:$id'),
          label: 'Components',
          id: 'rig-item-components',
          tip: 'Item components in command syntax, like [custom_model_data={floats:[1]}].',
          value: '${item['components'] ?? ''}',
          onCommit: (String value) {
            setItem((JsonMap next) => value.trim().isEmpty ? next.remove('components') : next['components'] = value);
            return null;
          },
        ),
        Field(
          label: 'Held as',
          forId: 'rig-item-context',
          child: AnimusSelect.values(
            id: 'rig-item-context',
            value: visual['itemDisplay'] is String ? visual['itemDisplay']! as String : 'none',
            values: itemContexts.keys.toList(),
            labels: itemContexts,
            onChanged: (String? value) => setVisual((JsonMap v) => value == null || value == 'none' ? v.remove('itemDisplay') : v['itemDisplay'] = value),
          ),
        ),
      ],
      if (visual != null && type == 'text') ...<Widget>[
        Field(
          label: 'Billboard',
          forId: 'rig-text-billboard',
          child: AnimusSelect.values(
            id: 'rig-text-billboard',
            value: visual['billboard'] is String ? visual['billboard']! as String : 'fixed',
            values: _billboards.keys.toList(),
            labels: _billboards,
            onChanged: (String? value) => setVisual((JsonMap v) => v['billboard'] = value ?? 'fixed'),
          ),
        ),
        CommitField(
          key: ValueKey<String>('background:$id'),
          label: 'Background',
          id: 'rig-text-background',
          tip: 'A colour as #AARRGGBB, like #40000000. Empty keeps the game\'s.',
          value: '${visual['background'] ?? ''}',
          onCommit: (String value) {
            final String trimmed = value.trim();
            if (trimmed.isNotEmpty && !_background.hasMatch(trimmed)) return 'Use #AARRGGBB, like #40000000, or leave it empty.';
            setVisual((JsonMap v) => trimmed.isEmpty ? v.remove('background') : v['background'] = trimmed);
            return null;
          },
        ),
        CommitField(
          key: ValueKey<String>('lineWidth:$id'),
          label: 'Line width',
          id: 'rig-text-width',
          number: true,
          hint: 'Pixels. Empty is 200.',
          placeholder: '200',
          value: '${visual['lineWidth'] ?? ''}',
          onCommit: (String value) {
            final String trimmed = value.trim();
            final int? width = int.tryParse(trimmed);
            if (trimmed.isNotEmpty && (width == null || width < 1 || width > 1024)) return 'Use 1 to 1024, or leave it empty for 200.';
            setVisual((JsonMap v) => width == null ? v.remove('lineWidth') : v['lineWidth'] = width);
            return null;
          },
        ),
        Field(
          label: 'Text shadow',
          forId: 'rig-text-shadow',
          child: ArcaneToggleSwitch(
            id: 'rig-text-shadow',
            value: visual['shadow'] == true,
            ariaLabel: 'Text shadow',
            onChanged: (bool on) => setVisual((JsonMap v) => on ? v['shadow'] = true : v.remove('shadow')),
          ),
        ),
      ],
    ];
    return dom.div(styles: stackStyles(), <Widget>[
      dom.h3(styles: groupHeadingStyles, <Widget>[Component.text('Bone $id')]),
      VectorField(
        key: ValueKey<String>('pivot:$id'),
        label: 'Pivot',
        tip: 'Where the bone turns, in blocks from the rig origin.',
        docs: Docs.rigs,
        value: _vector(entry['pivot'], 0),
        onCommit: (List<num> value) => bones((JsonMap bone) => bone['pivot'] = value),
      ),
      VectorField(
        key: ValueKey<String>('rotation:$id'),
        label: 'Rotation',
        tip: 'The rest pose\'s turn in degrees. Animations turn from here.',
        value: _vector(entry['rotation'], 0),
        onCommit: (List<num> value) => bones((JsonMap bone) => value.every((num each) => each == 0) ? bone.remove('rotation') : bone['rotation'] = value),
      ),
      const dom.h4(styles: groupHeadingStyles, <Widget>[
        TipLabel(
          label: Component.text('Visual'),
          tip: 'What the bone draws. Offset, rotation and scale move the drawing, not the bone.',
          docs: Docs.rigs,
        ),
      ]),
      Field(
        label: 'Type',
        forId: 'rig-visual-type',
        child: AnimusSelect.values(
          id: 'rig-visual-type',
          value: type,
          values: _visualTypes.keys.toList(),
          labels: _visualTypes,
          onChanged: (String? value) => bones((JsonMap bone) {
            if (value == null || value == 'none') {
              bone.remove('visual');
            } else {
              bone['visual'] = visualOfType(value, bone['visual'] is JsonMap ? bone['visual']! as JsonMap : null);
            }
          }),
        ),
      ),
      if (visual != null && type == 'item') ...<Widget>[
        CommitField(
          key: ValueKey<String>('item:$id'),
          label: 'Item',
          id: 'rig-item-id',
          placeholder: 'minecraft:stick',
          value: '${item['id'] ?? ''}',
          onCommit: (String value) {
            setItem((JsonMap next) => next['id'] = value.trim());
            return null;
          },
        ),
        CommitField(
          key: ValueKey<String>('count:$id'),
          label: 'Count',
          id: 'rig-item-count',
          number: true,
          value: '${item['count'] ?? 1}',
          onCommit: (String value) {
            final int? count = int.tryParse(value.trim());
            if (count == null || count < 1 || count > 99) return 'Use a whole number from 1 to 99.';
            setItem((JsonMap next) => count == 1 ? next.remove('count') : next['count'] = count);
            return null;
          },
        ),
      ],
      if (visual != null && type == 'block')
        CommitField(
          key: ValueKey<String>('block:$id'),
          label: 'Block',
          id: 'rig-block-state',
          tip: 'A block with its state, like minecraft:oak_stairs[facing=east].',
          value: '${visual['block'] ?? ''}',
          onCommit: (String value) {
            setVisual((JsonMap v) => v['block'] = value.trim());
            return null;
          },
        ),
      if (visual != null && type == 'text')
        CommitField(
          key: ValueKey<String>('text:$id'),
          label: 'Text',
          id: 'rig-text-text',
          value: '${visual['text'] ?? ''}',
          onCommit: (String value) {
            setVisual((JsonMap v) => v['text'] = value);
            return null;
          },
        ),
      if (visual != null && type != 'none') ...<Widget>[
        VectorField(
          key: ValueKey<String>('offset:$id'),
          label: 'Offset',
          name: 'Visual offset',
          value: _vector(visual['translation'], 0),
          onCommit: (List<num> value) => setVisual((JsonMap v) => v['translation'] = value),
        ),
        VectorField(
          key: ValueKey<String>('turn:$id'),
          label: 'Rotation',
          name: 'Visual rotation',
          value: _vector(visual['rotation'], 0),
          onCommit: (List<num> value) => setVisual((JsonMap v) => v['rotation'] = value),
        ),
        VectorField(
          key: ValueKey<String>('scale:$id'),
          label: 'Scale',
          name: 'Visual scale',
          value: _vector(visual['scale'], 1),
          onCommit: (List<num> value) => setVisual((JsonMap v) => v['scale'] = value),
        ),
      ],
      if (more.isNotEmpty) MoreFields(key: ValueKey<String>('more:$id:$type'), children: more),
      AppSection(
        title: 'Sockets',
        open: false,
        count: assigned,
        tip: 'Which bone holds each equipment slot. The NPC\'s item follows that bone\'s animation.',
        docs: Docs.rigs,
        children: <Widget>[
          for (final MapEntry<String, String> slot in socketSlots.entries)
            Field(
              label: slot.value,
              forId: 'rig-socket-${slot.key}',
              child: AnimusSelect.values(
                id: 'rig-socket-${slot.key}',
                value: sockets is JsonMap && sockets[slot.key] is String ? sockets[slot.key]! as String : '',
                values: <String>[for (final JsonMap each in bonesOf(rig)) '${each['id']}'],
                allowNone: true,
                noneLabel: 'None',
                onChanged: (String? value) => onEdit((JsonMap json) => _socket(json, slot.key, value)),
              ),
            ),
        ],
      ),
    ]);
  }

  static JsonMap _socket(JsonMap json, String slot, String? bone) {
    final JsonMap out = cloneJson(json);
    final JsonMap map = out['sockets'] is JsonMap ? out['sockets']! as JsonMap : <String, Object?>{};
    if (bone == null) {
      map.remove(slot);
    } else {
      map[slot] = bone;
    }
    if (map.isEmpty) {
      out.remove('sockets');
    } else {
      out['sockets'] = map;
    }
    return out;
  }
}
