/// The quest's Quest tab. Simple: the title, journal text and icon, and
/// the NPCs that offer it. Advanced adds the NPC to return to, who may
/// start it and how often, and the lines said at each step.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../ui/mode.dart';
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

const FieldProfile _messageProfile = FieldProfile(miniMessage: true, placeholders: <String, String>{'player': "the player's name", 'quest': 'the quest title'});

const Map<String, String> _messageLabels = <String, String>{'offer': 'Offer', 'accept': 'Accepted', 'decline': 'Declined', 'complete': 'Completed'};

const Map<String, String> _messageTips = <String, String>{
  'offer': 'Said by the giver before the Accept and Decline buttons. Empty uses the description.',
  'accept': 'Said when the player accepts.',
  'decline': 'Said when the player declines.',
  'complete': 'Said when the quest is done.',
};

/// The paths of the fields the Quest tab shows in Simple.
const List<String> _simplePaths = <String>[r'$.title', r'$.description', r'$.icon', r'$.givers'];

class QuestSettings extends StatelessWidget {
  const QuestSettings({required this.quest, required this.diagnostics, required this.npcs, required this.quests, required this.onWrite, required this.advanced, required this.onShow, super.key});

  final JsonMap quest;

  /// The page's diagnostics; this tab shows those outside stages and rewards.
  final List<Diagnostic> diagnostics;
  final QuestNpcs npcs;

  /// Other quest ids of the workspace, for requirements.
  final List<String> quests;
  final QuestWrite onWrite;

  /// Shows the Advanced fields (the mode, or Show on this page).
  final bool advanced;

  /// Show on the "advanced settings in use" line.
  final void Function() onShow;

  void _set(List<JsonStep> steps, Object? value) => onWrite(steps, value, coalesce: questPath(steps));

  @override
  Widget build(BuildContext context) {
    final JsonMap icon = asMap(quest['icon']);
    final List<Diagnostic> mine = <Diagnostic>[
      for (final Diagnostic d in diagnostics)
        if (!pathUnder(d.path, r'$.stages') && !pathUnder(d.path, r'$.rewards')) d,
    ];
    // Problems no field here shows: the document itself, its id and version.
    final List<Diagnostic> loose = <Diagnostic>[
      for (final Diagnostic d in mine)
        if (!_simplePaths.any((String field) => pathUnder(d.path, field)) && !questSettingsAdvancedPaths.any((String field) => pathUnder(d.path, field))) d,
    ];
    final int hiddenErrors = advanced ? 0 : mine.where((Diagnostic d) => d.severity == DiagnosticSeverity.error && questSettingsAdvancedPaths.any((String field) => pathUnder(d.path, field))).length;
    return stack(spacing: 16, <Widget>[
      for (final Diagnostic problem in loose) questAlert(problem),
      questRow(
        'Title',
        r'$.title',
        diagnostics,
        (String id) => AutocompleteInput(
          id: id,
          value: asString(quest['title']) ?? '',
          profile: FieldProfile.guiText,
          preview: templateSample,
          label: 'Quest title',
          onChanged: (String v) => _set(const <JsonStep>['title'], v),
        ),
        tip: 'Shown in the offer, messages and the journal. Colour codes such as &6 work.',
      ),
      questRow(
        'Description',
        r'$.description',
        diagnostics,
        (String id) => AutocompleteInput(
          id: id,
          value: asString(quest['description']) ?? '',
          multiline: true,
          label: 'Quest description',
          placeholder: 'What the player is asked to do',
          onChanged: (String v) => _set(const <JsonStep>['description'], v.isEmpty ? null : v),
          profile: FieldProfile.none,
        ),
      ),
      Field(
        label: 'Journal icon',
        forId: guiPathId(r'$.icon'),
        problems: problemsAt(diagnostics, r'$.icon'),
        child: QuestItemField(
          steps: const <JsonStep>['icon'],
          item: icon,
          label: 'Journal icon',
          placeholder: 'minecraft:writable_book',
          onWrite: (List<JsonStep> steps, Object? value, {String? coalesce}) {
            // Clearing the id clears the icon; the schema needs an id.
            if (value == null && steps.last == 'id') {
              onWrite(const <JsonStep>['icon'], null);
            } else {
              onWrite(steps, value, coalesce: coalesce);
            }
          },
        ),
      ),
      _givers(),
      if (advanced) ..._advanced() else questHidden(hidden: questSettingsInUse(quest), errors: hiddenErrors, onShow: onShow),
    ]);
  }

  Widget _givers() {
    final List<String> givers = stringList(quest['givers']);
    void setGivers(List<String> next) => _set(const <JsonStep>['givers'], next.isEmpty ? null : next);
    return Field(
      label: 'Givers',
      forId: guiPathId(r'$.givers'),
      problems: problemsAt(diagnostics, r'$.givers'),
      tip: 'NPCs that offer the quest on right click. With none it starts only by command or behavior.',
      child: stack(<Widget>[
        if (givers.isNotEmpty)
          OrderedRows(
            label: 'Givers',
            rows: <OrderedRow>[
              for (int index = 0; index < givers.length; index++)
                OrderedRow(
                  key: givers[index],
                  name: npcs.nameOf(givers[index]),
                  attributes: <String, String>{'data-giver': '$index'},
                  line: Row(gapSize: Gap.sm, crossAxisAlignment: CrossAxisAlignment.baseline, children: <Widget>[
                    Text.truncated(npcs.nameOf(givers[index])),
                    if (npcs.detailOf(givers[index], advanced: advanced) case final String detail) truncatedText(detail, muted: true),
                  ]),
                  entries: <AppMenuEntry>[if (npcs.documentOf(givers[index]) != null) AppMenuEntry('Open NPC', () => openQuestNpc(npcs, givers[index]))],
                ),
            ],
            onDelete: (int index) => setGivers(<String>[...givers]..removeAt(index)),
          ),
        if (givers.length < 16)
          npcPicker(
            id: guiPathId(r'$.givers'),
            label: 'Add giver',
            value: null,
            npcs: npcs,
            advanced: advanced,
            allowNone: true,
            noneLabel: 'Add giver',
            exclude: givers.toSet(),
            onChanged: (String? id) {
              if (id != null && !givers.contains(id)) setGivers(<String>[...givers, id]);
            },
          ),
      ]),
    );
  }

  List<Widget> _advanced() {
    final JsonMap requirements = asMap(quest['requirements']);
    final List<String> required = stringList(requirements['quests']);
    final JsonMap repeat = asMap(quest['repeat']);
    final String mode = asString(repeat['mode']) ?? 'never';
    final JsonMap messages = asMap(quest['messages']);
    void setRequired(List<String> next) => _set(const <JsonStep>['requirements', 'quests'], next.isEmpty ? null : next);
    return <Widget>[
      questRow(
        'Turn in',
        r'$.turnIn',
        diagnostics,
        (String id) => npcPicker(
          id: id,
          label: 'Turn in NPC',
          value: asString(quest['turnIn']),
          npcs: npcs,
          advanced: true,
          allowNone: true,
          noneLabel: 'Done at the last objective',
          onChanged: (String? v) => _set(const <JsonStep>['turnIn'], v),
        ),
        tip: 'The NPC to return to after the last stage.',
      ),
      groupHeading('Who and how often'),
      questRow(
        'Permission',
        r'$.requirements.permission',
        diagnostics,
        (String id) => AutocompleteInput(
          id: id,
          value: asString(requirements['permission']) ?? '',
          label: 'Required permission',
          onChanged: (String v) => _set(const <JsonStep>['requirements', 'permission'], v.trim().isEmpty ? null : v.trim()),
          profile: FieldProfile.none,
        ),
        tip: 'Needed before a giver offers the quest.',
      ),
      if (required.isNotEmpty || quests.isNotEmpty)
        Field(
          label: 'Finished first',
          forId: guiPathId(r'$.requirements.quests'),
          problems: problemsAt(diagnostics, r'$.requirements.quests'),
          tip: 'Quests the player must have finished at least once.',
          child: stack(<Widget>[
            if (required.isNotEmpty)
              OrderedRows(
                label: 'Finished first',
                rows: <OrderedRow>[
                  for (int index = 0; index < required.length; index++)
                    OrderedRow(
                      key: required[index],
                      name: required[index],
                      line: Text.truncated(required[index]),
                    ),
                ],
                onDelete: (int index) => setRequired(<String>[...required]..removeAt(index)),
              ),
            if (quests.any((String id) => !required.contains(id)))
              AnimusSelect.values(
                id: guiPathId(r'$.requirements.quests'),
                value: null,
                hiddenLabel: 'Add required quest',
                values: <String>[
                  for (final String id in quests)
                    if (!required.contains(id)) id,
                ],
                allowNone: true,
                noneLabel: 'Add quest',
                onChanged: (String? id) {
                  if (id != null) setRequired(<String>[...required, id]);
                },
              ),
          ]),
        ),
      questRow(
        'Condition',
        r'$.requirements.condition',
        diagnostics,
        (String id) => AutocompleteInput(
          id: id,
          value: asString(requirements['condition']) ?? '',
          profile: FieldProfile.behaviorExpression,
          label: 'Requirement condition',
          placeholder: "flag('player', 'helped_mayor')",
          onChanged: (String v) => _set(const <JsonStep>['requirements', 'condition'], v.trim().isEmpty ? null : v),
        ),
        tip: 'A behavior expression that must be true. Flags and quests are those of this quest\'s namespace.',
      ),
      questRow(
        'Repeat',
        r'$.repeat',
        diagnostics,
        (String id) => AnimusSelect.values(
          id: id,
          value: mode,
          values: const <String>['never', 'cooldown', 'daily'],
          labels: const <String, String>{'never': 'Once per player', 'cooldown': 'After a cooldown', 'daily': 'Daily'},
          onChanged: (String? v) => _set(
            const <JsonStep>['repeat'],
            switch (v) {
              'cooldown' => <String, Object?>{'mode': 'cooldown', 'seconds': asInt(repeat['seconds']) ?? 3600},
              'daily' => <String, Object?>{'mode': 'daily'},
              _ => null,
            },
          ),
        ),
        tip: 'Daily: again after the server\'s local midnight.',
        nested: false,
      ),
      if (mode == 'cooldown')
        questRow(
          'Cooldown',
          r'$.repeat.seconds',
          diagnostics,
          (String id) => NumberInput(
            id: id,
            value: asInt(repeat['seconds']),
            label: 'Cooldown seconds',
            placeholder: '3600',
            digits: 8,
            onChanged: (num? v) => _set(const <JsonStep>['repeat', 'seconds'], v?.toInt()),
          ),
          tip: 'Seconds after finishing before it can start again.',
        ),
      MoreFields(
        label: 'Messages',
        open: messages.isNotEmpty,
        children: <Widget>[
          for (final String key in questMessageKeys)
            questRow(
              _messageLabels[key]!,
              '\$.messages.$key',
              diagnostics,
              (String id) => AutocompleteInput(
                id: id,
                value: asString(messages[key]) ?? '',
                profile: _messageProfile,
                label: '${_messageLabels[key]} message',
                placeholder: 'Server default',
                onChanged: (String v) => _set(<JsonStep>['messages', key], v.isEmpty ? null : v),
              ),
              tip: _messageTips[key],
            ),
        ],
      ),
    ];
  }
}
