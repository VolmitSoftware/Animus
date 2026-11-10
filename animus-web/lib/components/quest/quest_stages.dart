/// The quest's stages in order, each with its journal text, whether its
/// objectives count at once or one after another, and its objectives. An
/// objective's fields follow its type; changing the type keeps what the new
/// type also takes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../menu_button.dart';
import 'quest_fields.dart';

const int _maxStages = 32;
const int _maxObjectives = 16;

class QuestStages extends StatelessWidget {
  const QuestStages({required this.quest, required this.diagnostics, required this.npcs, required this.onWrite, super.key});

  final JsonMap quest;
  final List<Diagnostic> diagnostics;
  final QuestNpcs npcs;
  final QuestWrite onWrite;

  JsonList get _stages => asList(quest['stages']);

  void _setStages(JsonList stages) => onWrite(const <JsonStep>['stages'], stages);

  @override
  Widget build(BuildContext context) {
    final JsonList stages = _stages;
    return dom.div(classes: 'animus-quest-stages', <Widget>[
      for (int index = 0; index < stages.length; index++) _stage(index, asMap(stages[index]), stages.length),
      Button.secondary(
        label: '+ Add stage',
        size: ButtonSize.small,
        disabled: stages.length >= _maxStages,
        attributes: const <String, String>{'data-quest': 'add-stage'},
        onPressed: () => _setStages(<Object?>[
          ...stages,
          <String, Object?>{'objectives': <Object?>[newObjective('talk', npc: npcs.ids.firstOrNull)]},
        ]),
      ),
    ]);
  }

  Widget _stage(int index, JsonMap stage, int count) {
    final List<JsonStep> at = <JsonStep>['stages', index];
    final String path = questPath(at);
    final JsonList objectives = asList(stage['objectives']);
    final String? about = asString(stage['description']);
    final List<Diagnostic> own = <Diagnostic>[for (final Diagnostic d in diagnostics) if (d.path == path || d.path == '$path.objectives') d];
    return dom.section(
      classes: 'animus-quest-stage',
      attributes: <String, String>{'aria-label': 'Stage ${index + 1}', 'data-stage': '$index'},
      <Widget>[
        dom.div(classes: 'animus-quest-stage-head', <Widget>[
          dom.h3(classes: 'animus-quest-stage-title', <Widget>[
            Component.text('Stage ${index + 1}${about == null || about.isEmpty ? '' : ': $about'}'),
          ]),
          dom.div(classes: 'animus-row animus-quest-tools', <Widget>[
            ...guiMoveButtons(index: index, length: count, noun: 'stage ${index + 1}', onMove: (int to) => _setStages(moveItem(_stages, index, to))),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove stage ${index + 1}',
              disabled: count <= 1,
              onPressed: () => _setStages(<Object?>[..._stages]..removeAt(index)),
            ),
          ]),
        ]),
        for (final Diagnostic problem in own)
          dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(problem.message)]),
        questRow('Journal text', '$path.description', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: about ?? '',
              label: 'Stage ${index + 1} journal text',
              placeholder: 'Find the lost cat',
              onChanged: (String v) => onWrite(<JsonStep>[...at, 'description'], v.isEmpty ? null : v, coalesce: '$path.description'),
            )),
        questRow('Order', '$path.order', diagnostics, (String id) => GuiSelect(
              id: id,
              hiddenLabel: 'Stage ${index + 1} order',
              value: asString(stage['order']) ?? 'any',
              options: const <String>['any', 'ordered'],
              labels: const <String, String>{'any': 'All at once', 'ordered': 'One after another'},
              onChanged: (String? v) => onWrite(<JsonStep>[...at, 'order'], v == null || v == 'any' ? null : v),
            ), tip: 'All at once: objectives count in any order. One after another: each starts when the one before is done.'),
        dom.ol(classes: 'animus-quest-objectives', <Widget>[
          for (int item = 0; item < objectives.length; item++)
            dom.li(classes: 'animus-quest-objective', attributes: <String, String>{'data-objective': '$index.$item'}, <Widget>[
              _objective(at, item, asMap(objectives[item]), objectives),
            ]),
        ]),
        MenuButton(
          id: 'animus-quest-add-objective-$index',
          label: '+ Add objective',
          disabled: objectives.length >= _maxObjectives,
          entries: <MenuEntry>[
            for (final String type in questObjectiveTypes)
              MenuEntry(questObjectiveLabels[type]!, () => onWrite(<JsonStep>[...at, 'objectives'], <Object?>[...objectives, newObjective(type, npc: npcs.ids.firstOrNull)])),
          ],
        ),
      ],
    );
  }

  Widget _objective(List<JsonStep> stage, int index, JsonMap objective, JsonList all) {
    final List<JsonStep> at = <JsonStep>[...stage, 'objectives', index];
    final String path = questPath(at);
    final String type = asString(objective['type']) ?? 'collect';
    final String noun = 'stage ${(stage.last as int) + 1} objective ${index + 1}';
    void set(String key, Object? value) => onWrite(<JsonStep>[...at, key], value, coalesce: '$path.$key');
    final List<Widget> fields = <Widget>[
      if (type == 'talk' || type == 'bring')
        questRow('NPC', '$path.npc', diagnostics, (String id) => npcPicker(id: id, label: '${_cap(noun)} NPC', value: asString(objective['npc']), npcs: npcs, onChanged: (String? v) => set('npc', v))),
      if (type == 'kill') ..._killFields(at, objective, set, noun),
      if (type == 'bring' || type == 'collect')
        GuiRow(
          label: 'Item',
          forId: guiPathId('$path.item'),
          problems: problemsAt(diagnostics, '$path.item'),
          tip: type == 'bring' ? 'Taken from the player when they hand in.' : 'Counted while it is in the inventory.',
          child: QuestItemField(steps: <JsonStep>[...at, 'item'], item: asMap(objective['item']), onWrite: onWrite, label: '${_cap(noun)} item'),
        ),
      if (type != 'talk' && type != 'reach')
        questRow('Amount', '$path.amount', diagnostics, (String id) => GuiNumberInput(
              id: id,
              value: asInt(objective['amount']),
              label: '${_cap(noun)} amount',
              placeholder: '1',
              digits: 6,
              onChanged: (num? v) => set('amount', v?.toInt()),
            )),
      if (type == 'reach') ..._reachFields(path, objective, set, noun),
      questRow(type == 'custom' ? 'Id' : 'Id (optional)', '$path.id', diagnostics, (String id) => GuiTextInput(
            id: id,
            value: asString(objective['id']) ?? '',
            label: '${_cap(noun)} id',
            placeholder: type == 'custom' ? 'ring_bell' : 'Optional',
            onChanged: (String v) => set('id', v.trim().isEmpty ? null : v.trim()),
          ), tip: type == 'custom' ? 'Behaviors and /animus quest commands count this objective by its id.' : 'Unique within the stage.', nested: false),
      questRow('Journal text', '$path.description', diagnostics, (String id) => GuiTextInput(
            id: id,
            value: asString(objective['description']) ?? '',
            label: '${_cap(noun)} journal text',
            placeholder: objectiveLine(<String, Object?>{...objective, 'description': null}, npcName: npcs.nameOf),
            onChanged: (String v) => set('description', v.isEmpty ? null : v),
          ), tip: 'Empty uses the line shown as the placeholder.', nested: false),
    ];
    return dom.div(classes: 'animus-quest-objective-body', <Widget>[
      dom.div(classes: 'animus-quest-objective-head', <Widget>[
        GuiSelect(
          id: guiPathId('$path.type'),
          hiddenLabel: '${_cap(noun)} type',
          value: type,
          options: questObjectiveTypes,
          labels: questObjectiveLabels,
          onChanged: (String? v) {
            if (v != null && v != type) onWrite(at, retypeObjective(objective, v, npc: npcs.ids.firstOrNull));
          },
        ),
        dom.div(classes: 'animus-row animus-quest-tools', <Widget>[
          ...guiMoveButtons(index: index, length: all.length, noun: noun, onMove: (int to) => onWrite(<JsonStep>[...at.take(at.length - 1)], moveItem(all, index, to))),
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove $noun',
            disabled: all.length <= 1,
            onPressed: () => onWrite(<JsonStep>[...at.take(at.length - 1)], <Object?>[...all]..removeAt(index)),
          ),
        ]),
      ]),
      for (final Diagnostic problem in diagnostics)
        if (problem.path == path)
          dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(problem.message)]),
      ...fields,
    ]);
  }

  List<Widget> _killFields(List<JsonStep> at, JsonMap objective, void Function(String key, Object? value) set, String noun) {
    final String path = questPath(at);
    final bool npc = objective['npc'] != null;
    return <Widget>[
      questRow('Target', '$path.target', diagnostics, (String id) => GuiSelect(
            id: id,
            hiddenLabel: '${_cap(noun)} target',
            value: npc ? 'npc' : 'entity',
            options: const <String>['entity', 'npc'],
            labels: const <String, String>{'entity': 'Mob type', 'npc': 'An NPC'},
            onChanged: (String? v) {
              if (v == 'npc' && !npc) onWrite(at, <String, Object?>{...objective, 'npc': npcs.ids.firstOrNull ?? 'npc'}..remove('entity'));
              if (v == 'entity' && npc) onWrite(at, <String, Object?>{...objective, 'entity': 'minecraft:zombie'}..remove('npc'));
            },
          ), tip: 'An NPC counts only when its health can reach zero.'),
      if (npc)
        questRow('NPC', '$path.npc', diagnostics, (String id) => npcPicker(id: id, label: '${_cap(noun)} NPC', value: asString(objective['npc']), npcs: npcs, onChanged: (String? v) => set('npc', v ?? 'npc')))
      else
        questRow('Mob type', '$path.entity', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: asString(objective['entity']) ?? '',
              label: '${_cap(noun)} mob type',
              placeholder: 'minecraft:zombie',
              profile: const FieldProfile(values: <ValueRef>[ValueRef.registry(RegistryKind.entity)]),
              onChanged: (String v) => set('entity', v.trim().isEmpty ? null : v.trim()),
            )),
    ];
  }

  List<Widget> _reachFields(String path, JsonMap objective, void Function(String key, Object? value) set, String noun) => <Widget>[
        questRow('World', '$path.world', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: asString(objective['world']) ?? '',
              label: '${_cap(noun)} world',
              placeholder: 'world',
              profile: const FieldProfile(values: <ValueRef>[ValueRef.registry(RegistryKind.world)]),
              onChanged: (String v) => set('world', v.trim().isEmpty ? null : v.trim()),
            )),
        GuiRow(
          label: 'Position',
          problems: <Diagnostic>[for (final String axis in const <String>['x', 'y', 'z']) ...diagnosticsAt(diagnostics, '$path.$axis')],
          child: dom.div(classes: 'animus-quest-xyz', <Widget>[
            for (final String axis in const <String>['x', 'y', 'z'])
              GuiNumberInput(
                value: asDouble(objective[axis]),
                integer: false,
                label: '${_cap(noun)} $axis',
                placeholder: axis,
                digits: 8,
                onChanged: (num? v) => set(axis, v),
              ),
          ]),
        ),
        questRow('Radius', '$path.radius', diagnostics, (String id) => GuiNumberInput(
              id: id,
              value: asDouble(objective['radius']),
              integer: false,
              label: '${_cap(noun)} radius',
              placeholder: '3',
              digits: 4,
              onChanged: (num? v) => set('radius', v),
            ), tip: 'Blocks around the point, 3 by default.'),
      ];

  static String _cap(String text) => text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}';
}
