/// The quest's Stages tab: the stages in order, each a row that opens to
/// its journal text and objectives; the objectives are rows too, each
/// opening to the fields its type takes. Rows reorder by drag or by their
/// "..." menu, which also duplicates and deletes. Changing an objective's
/// type keeps what the new type also takes. Advanced adds a stage's order
/// and the objective ids.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/tone.dart';
import '../gui/gui_fields.dart';
import '../../ui/menu.dart';
import '../gui/gui_rows.dart';
import 'quest_fields.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import '../gui/gui_stack.dart';
import '../../ui/stack.dart';

const int _maxStages = 32;
const int _maxObjectives = 16;

/// One line about what each objective type does, for "Add objective".
const Map<String, String> questObjectiveHints = <String, String>{
  'talk': 'Done when the player right-clicks the NPC.',
  'bring': 'The NPC takes the items when they are handed in.',
  'kill': 'Counts mobs of one type, or one NPC.',
  'reach': 'Done when the player gets near a point.',
  'collect': 'Counts items while they are in the inventory.',
  'custom': 'Counted by a behavior or a command.',
};

/// [open] after the row at [from] moved to [to]: each open row stays open.
Set<int> movedOpen(Set<int> open, int from, int to) => <int>{
      for (final int index in open)
        if (index == from)
          to
        else if (from < to && index > from && index <= to)
          index - 1
        else if (from > to && index >= to && index < from)
          index + 1
        else
          index,
    };

class QuestStages extends StatefulWidget {
  const QuestStages({required this.quest, required this.diagnostics, required this.npcs, required this.onWrite, required this.advanced, required this.onShow, super.key});

  final JsonMap quest;
  final List<Diagnostic> diagnostics;
  final QuestNpcs npcs;
  final QuestWrite onWrite;

  /// Shows the Advanced fields (the mode, or Show on this page).
  final bool advanced;

  /// Show on the "advanced settings in use" line.
  final void Function() onShow;

  @override
  State<QuestStages> createState() => _QuestStagesState();
}

class _QuestStagesState extends State<QuestStages> {
  /// Open stages by index; the first is open at first.
  Set<int> _open = <int>{0};

  /// Open objectives as `stage.objective`.
  Set<String> _objectives = <String>{};

  JsonList get _stages => asList(component.quest['stages']);
  List<Diagnostic> get _diagnostics => component.diagnostics;
  QuestNpcs get _npcs => component.npcs;

  void _setStages(JsonList stages) => component.onWrite(const <JsonStep>['stages'], stages);

  void _toggle(int stage) => setState(() => _open = _open.contains(stage) ? (<int>{..._open}..remove(stage)) : <int>{..._open, stage});

  void _toggleObjective(String key) => setState(() => _objectives = _objectives.contains(key) ? (<String>{..._objectives}..remove(key)) : <String>{..._objectives, key});

  void _addStage() {
    final JsonList stages = _stages;
    _setStages(<Object?>[
      ...stages,
      <String, Object?>{'objectives': <Object?>[newObjective('talk', npc: _npcs.ids.firstOrNull)]},
    ]);
    setState(() => _open = <int>{..._open, stages.length});
  }

  @override
  Widget build(BuildContext context) {
    final JsonList stages = _stages;
    return stack(spacing: 16, <Widget>[
      ActionRow(
        dense: true,
        label: 'Stages',
        leading: <Widget>[mutedText(stages.length == 1 ? '1 stage' : '${stages.length} stages')],
        primary: RowAction('Add stage', stages.length >= _maxStages ? null : _addStage, icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-quest': 'add-stage'}),
      ),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, r'$.stages')) questAlert(problem),
      OrderedRows(
        label: 'Stages',
        deleteNoun: 'stage',
        rows: <OrderedRow>[for (int index = 0; index < stages.length; index++) _stage(index, asMap(stages[index]))],
        onMove: (int from, int to) {
          _setStages(moveItem(stages, from, to));
          setState(() {
            _open = movedOpen(_open, from, to);
            _objectives = <String>{};
          });
        },
        onDuplicate: stages.length >= _maxStages
            ? null
            : (int index) {
                _setStages(<Object?>[...stages.take(index + 1), deepCopy(stages[index]), ...stages.skip(index + 1)]);
                setState(() => _open = <int>{...movedOpen(_open, stages.length, index + 1), index + 1});
              },
        onDelete: stages.length <= 1
            ? null
            : (int index) {
                _setStages(<Object?>[...stages]..removeAt(index));
                setState(() {
                  _open = <int>{for (final int open in _open) if (open != index) open > index ? open - 1 : open};
                  _objectives = <String>{};
                });
              },
      ),
      if (!component.advanced) _hidden(),
    ]);
  }

  Widget _hidden() {
    final List<String> paths = questStagesAdvancedPaths(component.quest);
    final int errors = _diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error && paths.contains(d.path)).length;
    return questHidden(hidden: questStagesInUse(component.quest), errors: errors, onShow: component.onShow);
  }

  OrderedRow _stage(int index, JsonMap stage) {
    final List<JsonStep> at = <JsonStep>['stages', index];
    final String path = questPath(at);
    final String? about = asString(stage['description']);
    final bool open = _open.contains(index);
    final int errors = _diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error && (d.path == path || d.path.startsWith('$path.'))).length;
    final List<Diagnostic> own = <Diagnostic>[for (final Diagnostic d in _diagnostics) if (d.path == path || d.path == '$path.objectives') d];
    return OrderedRow(
      key: 'stage-$index',
      name: 'stage ${index + 1}',
      open: open,
      attributes: <String, String>{'data-stage': '$index'},
      line: questToggle(
        open: open,
        label: 'Stage ${index + 1}',
        detail: about == null || about.isEmpty ? null : about,
        errors: errors,
        onPressed: () => _toggle(index),
        body: open
            ? stack(spacing: 16, <Widget>[
                for (final Diagnostic problem in own) questAlert(problem),
                questRow('Journal text', '$path.description', _diagnostics, (String id) => AutocompleteInput(
                      id: id,
                      value: about ?? '',
                      label: 'Stage ${index + 1} journal text',
                      onChanged: (String v) => component.onWrite(<JsonStep>[...at, 'description'], v.isEmpty ? null : v, coalesce: '$path.description'),
                      profile: FieldProfile.none,
                    )),
                if (component.advanced)
                  questRow('Order', '$path.order', _diagnostics, (String id) => AnimusSelect.values(
                        id: id,
                        value: asString(stage['order']) ?? 'any',
                        values: const <String>['any', 'ordered'],
                        labels: const <String, String>{'any': 'All at once', 'ordered': 'One after another'},
                        onChanged: (String? v) => component.onWrite(<JsonStep>[...at, 'order'], v == null || v == 'any' ? null : v),
                      ), tip: 'All at once: objectives count in any order. One after another: each starts when the one before is done.'),
                _objectivesOf(index, at, asList(stage['objectives'])),
              ])
            : null,
      ),
    );
  }

  Widget _objectivesOf(int stage, List<JsonStep> at, JsonList objectives) {
    void setAll(JsonList next) => component.onWrite(<JsonStep>[...at, 'objectives'], next);
    return Field(
      label: 'Objectives',
      problems: const <Diagnostic>[],
      child: stack(<Widget>[
        OrderedRows(
          label: 'Stage ${stage + 1} objectives',
          deleteNoun: 'objective',
          rows: <OrderedRow>[for (int index = 0; index < objectives.length; index++) _objective(stage, at, index, asMap(objectives[index]))],
          onMove: (int from, int to) {
            setAll(moveItem(objectives, from, to));
            setState(() => _objectives = <String>{for (final int open in movedOpen(<int>{for (final String key in _objectives) if (key.startsWith('$stage.')) int.parse(key.substring('$stage.'.length))}, from, to)) '$stage.$open'});
          },
          onDuplicate: objectives.length >= _maxObjectives
              ? null
              : (int index) {
                  final JsonMap copy = asMap(deepCopy(objectives[index]));
                  // A copied id would repeat within the stage.
                  if (asString(copy['type']) != 'custom') copy.remove('id');
                  setAll(<Object?>[...objectives.take(index + 1), copy, ...objectives.skip(index + 1)]);
                },
          onDelete: objectives.length <= 1 ? null : (int index) => setAll(<Object?>[...objectives]..removeAt(index)),
        ),
        if (objectives.length < _maxObjectives)
          AppMenu(
            id: 'animus-quest-add-objective-$stage',
            label: 'Add objective',
            icon: ArcaneIcon.chevronDown(),
            outline: true,
            entries: <AppMenuEntry>[
              for (final String type in questObjectiveTypes)
                AppMenuEntry(questObjectiveLabels[type]!, () {
                  setAll(<Object?>[...objectives, newObjective(type, npc: _npcs.ids.firstOrNull)]);
                  setState(() => _objectives = <String>{..._objectives, '$stage.${objectives.length}'});
                }, description: questObjectiveHints[type]),
            ],
          ),
      ]),
    );
  }

  OrderedRow _objective(int stage, List<JsonStep> stageAt, int index, JsonMap objective) {
    final List<JsonStep> at = <JsonStep>[...stageAt, 'objectives', index];
    final String path = questPath(at);
    final String key = '$stage.$index';
    final bool open = _objectives.contains(key);
    final int errors = _diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error && (d.path == path || d.path.startsWith('$path.'))).length;
    return OrderedRow(
      key: 'objective-$key',
      name: 'objective ${index + 1}',
      open: open,
      attributes: <String, String>{'data-objective': key},
      line: questToggle(
        open: open,
        label: objectiveLine(objective, npcName: _npcs.nameOf),
        errors: errors,
        onPressed: () => _toggleObjective(key),
        body: open ? _objectiveFields(stage, at, index, objective) : null,
      ),
    );
  }

  Widget _objectiveFields(int stage, List<JsonStep> at, int index, JsonMap objective) {
    final String path = questPath(at);
    final String type = asString(objective['type']) ?? 'collect';
    final String noun = 'Stage ${stage + 1} objective ${index + 1}';
    void set(String key, Object? value) => component.onWrite(<JsonStep>[...at, key], value, coalesce: '$path.$key');
    return stack(spacing: 16, <Widget>[
      for (final Diagnostic problem in _diagnostics)
        if (problem.path == path) questAlert(problem),
      questRow('Type', '$path.type', _diagnostics, (String id) => AnimusSelect.values(
            id: id,
            value: type,
            values: questObjectiveTypes,
            labels: questObjectiveLabels,
            details: questObjectiveHints,
            onChanged: (String? v) {
              if (v != null && v != type) component.onWrite(at, retypeObjective(objective, v, npc: _npcs.ids.firstOrNull));
            },
          ), nested: false),
      if (type == 'talk' || type == 'bring')
        questRow('NPC', '$path.npc', _diagnostics, (String id) => npcPicker(id: id, label: '$noun NPC', value: asString(objective['npc']), npcs: _npcs, advanced: component.advanced, onChanged: (String? v) => set('npc', v))),
      if (type == 'kill') ..._killFields(at, objective, set, noun),
      if (type == 'bring' || type == 'collect')
        Field(
          label: 'Item',
          forId: guiPathId('$path.item'),
          problems: problemsAt(_diagnostics, '$path.item'),
          tip: type == 'bring' ? 'Taken from the player when they hand in.' : 'Counted while it is in the inventory.',
          child: QuestItemField(steps: <JsonStep>[...at, 'item'], item: asMap(objective['item']), onWrite: component.onWrite, label: '$noun item'),
        ),
      if (type != 'talk' && type != 'reach')
        questRow('Amount', '$path.amount', _diagnostics, (String id) => NumberInput(
              id: id,
              value: asInt(objective['amount']),
              label: '$noun amount',
              placeholder: '1',
              digits: 6,
              onChanged: (num? v) => set('amount', v?.toInt()),
            )),
      if (type == 'reach') ..._reachFields(path, objective, set, noun),
      if (type == 'custom' || component.advanced)
        questRow('Id', '$path.id', _diagnostics, (String id) => AutocompleteInput(
              id: id,
              value: asString(objective['id']) ?? '',
              label: '$noun id',
              placeholder: type == 'custom' ? 'ring_bell' : 'Optional',
              onChanged: (String v) => set('id', v.trim().isEmpty ? null : v.trim()),
              profile: FieldProfile.none,
            ), tip: type == 'custom' ? 'Behaviors and quest commands count this objective by its id.' : 'Unique within the stage.', nested: false),
      questRow('Journal text', '$path.description', _diagnostics, (String id) => AutocompleteInput(
            id: id,
            value: asString(objective['description']) ?? '',
            label: '$noun journal text',
            placeholder: objectiveLine(<String, Object?>{...objective, 'description': null}, npcName: _npcs.nameOf),
            onChanged: (String v) => set('description', v.isEmpty ? null : v),
            profile: FieldProfile.none,
          ), tip: 'Empty uses the line shown in grey.', nested: false),
    ]);
  }

  List<Widget> _killFields(List<JsonStep> at, JsonMap objective, void Function(String key, Object? value) set, String noun) {
    final String path = questPath(at);
    final bool npc = objective['npc'] != null;
    return <Widget>[
      questRow('Target', '$path.target', _diagnostics, (String id) => AnimusSelect.values(
            id: id,
            value: npc ? 'npc' : 'entity',
            values: const <String>['entity', 'npc'],
            labels: const <String, String>{'entity': 'Mob type', 'npc': 'An NPC'},
            onChanged: (String? v) {
              if (v == 'npc' && !npc) component.onWrite(at, <String, Object?>{...objective, 'npc': _npcs.ids.firstOrNull ?? 'npc'}..remove('entity'));
              if (v == 'entity' && npc) component.onWrite(at, <String, Object?>{...objective, 'entity': 'minecraft:zombie'}..remove('npc'));
            },
          ), tip: 'An NPC counts only when its health can reach zero.'),
      if (npc)
        questRow('NPC', '$path.npc', _diagnostics, (String id) => npcPicker(id: id, label: '$noun NPC', value: asString(objective['npc']), npcs: _npcs, advanced: component.advanced, onChanged: (String? v) => set('npc', v ?? 'npc')))
      else
        questRow('Mob type', '$path.entity', _diagnostics, (String id) => AutocompleteInput(
              id: id,
              value: asString(objective['entity']) ?? '',
              label: '$noun mob type',
              placeholder: 'minecraft:zombie',
              profile: const FieldProfile(values: <ValueRef>[ValueRef.registry(RegistryKind.entity)]),
              onChanged: (String v) => set('entity', v.trim().isEmpty ? null : v.trim()),
            )),
    ];
  }

  List<Widget> _reachFields(String path, JsonMap objective, void Function(String key, Object? value) set, String noun) => <Widget>[
        questRow('World', '$path.world', _diagnostics, (String id) => AutocompleteInput(
              id: id,
              value: asString(objective['world']) ?? '',
              label: '$noun world',
              placeholder: 'world',
              profile: const FieldProfile(values: <ValueRef>[ValueRef.registry(RegistryKind.world)]),
              onChanged: (String v) => set('world', v.trim().isEmpty ? null : v.trim()),
            )),
        Field(
          label: 'Position',
          problems: <Diagnostic>[for (final String axis in const <String>['x', 'y', 'z']) ...diagnosticsAt(_diagnostics, '$path.$axis')],
          child: equalLine(<Widget>[
            for (final String axis in const <String>['x', 'y', 'z'])
              NumberInput(
                value: asDouble(objective[axis]),
                integer: false,
                label: '$noun $axis',
                placeholder: axis,
                digits: 8,
                onChanged: (num? v) => set(axis, v),
              ),
          ]),
        ),
        questRow('Radius', '$path.radius', _diagnostics, (String id) => NumberInput(
              id: id,
              value: asDouble(objective['radius']),
              integer: false,
              label: '$noun radius',
              placeholder: '3',
              digits: 4,
              onChanged: (num? v) => set('radius', v),
            ), tip: 'Blocks around the point, 3 by default.'),
      ];
}

/// A row's line that opens and closes its form: the label, a muted detail
/// and an error count.
Widget questToggle({required bool open, required String label, String? detail, int errors = 0, required void Function() onPressed, Widget? body}) => RowOpener(
      open: open,
      onToggle: onPressed,
      body: body,
      children: <Widget>[
        Text.truncated(label),
        Expanded(child: dom.span(classes: 'animus-quest-toggle-detail', <Widget>[if (detail != null) blockTruncated(detail, muted: true)])),
        if (errors > 0) StatusBadge(errors == 1 ? '1 problem' : '$errors problems', tone: BadgeTone.error),
      ],
    );
