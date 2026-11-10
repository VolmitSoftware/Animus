/// The quest's own fields: title, journal text and icon, the NPCs that offer
/// it and take it back, who may start it, how often, and the lines said at
/// each step.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import 'quest_fields.dart';

const FieldProfile _messageProfile = FieldProfile(miniMessage: true, placeholders: <String, String>{
  'player': "the player's name",
  'quest': 'the quest title',
});

const Map<String, String> _messageLabels = <String, String>{
  'offer': 'Offer',
  'accept': 'Accepted',
  'decline': 'Declined',
  'complete': 'Completed',
};

const Map<String, String> _messageTips = <String, String>{
  'offer': 'Said by the giver before the Accept and Decline buttons. Empty uses the description.',
  'accept': 'Said when the player accepts.',
  'decline': 'Said when the player declines.',
  'complete': 'Said when the quest is done.',
};

class QuestSettings extends StatelessWidget {
  const QuestSettings({required this.quest, required this.diagnostics, required this.npcs, required this.quests, required this.onWrite, super.key});

  final JsonMap quest;
  final List<Diagnostic> diagnostics;
  final QuestNpcs npcs;

  /// Other quest ids of the workspace, for requirements.
  final List<String> quests;
  final QuestWrite onWrite;

  void _set(List<JsonStep> steps, Object? value) => onWrite(steps, value, coalesce: questPath(steps));

  @override
  Widget build(BuildContext context) {
    final List<String> givers = stringList(quest['givers']);
    final JsonMap requirements = asMap(quest['requirements']);
    final List<String> required = stringList(requirements['quests']);
    final JsonMap repeat = asMap(quest['repeat']);
    final String mode = asString(repeat['mode']) ?? 'never';
    final JsonMap messages = asMap(quest['messages']);
    final JsonMap icon = asMap(quest['icon']);
    return dom.div(classes: 'animus-quest-settings', <Widget>[
      GuiSection(title: 'Quest', tip: 'What players read in the offer and the journal.', children: <Widget>[
        questRow('Title', r'$.title', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: asString(quest['title']) ?? '',
              mode: GuiTextMode.template,
              label: 'Quest title',
              placeholder: '&6Bread for the baker',
              onChanged: (String v) => _set(const <JsonStep>['title'], v),
            ), tip: 'Shown in offers, messages and the journal. & codes and MiniMessage work.'),
        questRow('Description', r'$.description', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: asString(quest['description']) ?? '',
              multiline: true,
              label: 'Quest description',
              placeholder: 'What the player is asked to do',
              onChanged: (String v) => _set(const <JsonStep>['description'], v.isEmpty ? null : v),
            ), tip: 'The offer text and the journal lore.'),
        GuiRow(
          label: 'Journal icon',
          forId: guiPathId(r'$.icon'),
          problems: problemsAt(diagnostics, r'$.icon'),
          tip: 'Empty shows a writable book.',
          child: QuestItemField(steps: const <JsonStep>['icon'], item: icon, label: 'Journal icon', placeholder: 'minecraft:writable_book', onWrite: (List<JsonStep> steps, Object? value, {String? coalesce}) {
            // Clearing the id clears the icon; the schema needs an id.
            if (value == null && steps.last == 'id') {
              onWrite(const <JsonStep>['icon'], null);
            } else {
              onWrite(steps, value, coalesce: coalesce);
            }
          }),
        ),
      ]),
      GuiSection(title: 'NPCs', count: givers.length, tip: 'NPC ids, the id field of each NPC.', children: <Widget>[
        GuiRow(
          label: 'Givers',
          problems: problemsAt(diagnostics, r'$.givers'),
          tip: 'NPCs that offer the quest on right click. None: it starts only by command or behavior.',
          child: dom.div(classes: 'animus-gui-lines', <Widget>[
            for (int index = 0; index < givers.length; index++)
              dom.div(classes: 'animus-gui-line', attributes: <String, String>{'data-giver': '$index'}, <Widget>[
                npcPicker(
                  id: guiPathId('\$.givers[$index]'),
                  label: 'Giver ${index + 1}',
                  value: givers[index],
                  npcs: npcs,
                  onChanged: (String? v) => _set(const <JsonStep>['givers'], <String>[...givers]..[index] = v ?? givers[index]),
                ),
                GuiIconButton(
                  icon: ArcaneIcon.x(size: IconSize.sm),
                  label: 'Remove giver ${index + 1}',
                  onPressed: () => _set(const <JsonStep>['givers'], givers.length == 1 ? null : (<String>[...givers]..removeAt(index))),
                ),
              ]),
            Button.ghost(
              label: '+ Add giver',
              size: ButtonSize.small,
              disabled: givers.length >= 16 || npcs.ids.every(givers.contains),
              onPressed: () => _set(const <JsonStep>['givers'], <String>[...givers, npcs.ids.firstWhere((String id) => !givers.contains(id))]),
            ),
          ]),
        ),
        questRow('Turn in', r'$.turnIn', diagnostics, (String id) => npcPicker(
              id: id,
              label: 'Turn in NPC',
              value: asString(quest['turnIn']),
              npcs: npcs,
              allowNone: true,
              noneLabel: 'Completes at the last objective',
              onChanged: (String? v) => _set(const <JsonStep>['turnIn'], v),
            ), tip: 'The NPC to return to after the last stage.'),
      ]),
      GuiSection(title: 'Who and how often', open: requirements.isNotEmpty || repeat.isNotEmpty, tip: 'All requirements must hold before a giver offers the quest.', children: <Widget>[
        questRow('Permission', r'$.requirements.permission', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: asString(requirements['permission']) ?? '',
              label: 'Required permission',
              placeholder: 'town.quests.bread',
              onChanged: (String v) => _set(const <JsonStep>['requirements', 'permission'], v.trim().isEmpty ? null : v.trim()),
            )),
        GuiRow(
          label: 'Finished first',
          problems: problemsAt(diagnostics, r'$.requirements.quests'),
          tip: 'Quests the player must have finished at least once.',
          child: dom.div(classes: 'animus-gui-lines', <Widget>[
            for (int index = 0; index < required.length; index++)
              dom.div(classes: 'animus-gui-line', <Widget>[
                GuiSelect(
                  hiddenLabel: 'Required quest ${index + 1}',
                  value: required[index],
                  options: quests,
                  onChanged: (String? v) => _set(const <JsonStep>['requirements', 'quests'], <String>[...required]..[index] = v ?? required[index]),
                ),
                GuiIconButton(
                  icon: ArcaneIcon.x(size: IconSize.sm),
                  label: 'Remove required quest ${index + 1}',
                  onPressed: () => _set(const <JsonStep>['requirements', 'quests'], required.length == 1 ? null : (<String>[...required]..removeAt(index))),
                ),
              ]),
            Button.ghost(
              label: '+ Add quest',
              size: ButtonSize.small,
              disabled: quests.every(required.contains),
              onPressed: () => _set(const <JsonStep>['requirements', 'quests'], <String>[...required, quests.firstWhere((String id) => !required.contains(id))]),
            ),
          ]),
        ),
        questRow('Condition', r'$.requirements.condition', diagnostics, (String id) => GuiTextInput(
              id: id,
              value: asString(requirements['condition']) ?? '',
              profile: FieldProfile.behaviorExpression,
              label: 'Requirement condition',
              placeholder: "flag('player', 'helped_mayor')",
              onChanged: (String v) => _set(const <JsonStep>['requirements', 'condition'], v.trim().isEmpty ? null : v),
            ), tip: 'A behavior expression. Flags and quests are those of this quest\'s namespace.'),
        questRow('Repeat', r'$.repeat', diagnostics, (String id) => GuiSelect(
              id: id,
              hiddenLabel: 'Repeat',
              value: mode,
              options: const <String>['never', 'cooldown', 'daily'],
              labels: const <String, String>{'never': 'Once per player', 'cooldown': 'After a cooldown', 'daily': 'Daily'},
              onChanged: (String? v) => _set(const <JsonStep>['repeat'], switch (v) {
                'cooldown' => <String, Object?>{'mode': 'cooldown', 'seconds': asInt(repeat['seconds']) ?? 3600},
                'daily' => <String, Object?>{'mode': 'daily'},
                _ => null,
              }),
            ), tip: 'Daily: again after the server\'s local midnight.', nested: false),
        if (mode == 'cooldown')
          questRow('Cooldown', r'$.repeat.seconds', diagnostics, (String id) => GuiNumberInput(
                id: id,
                value: asInt(repeat['seconds']),
                label: 'Cooldown seconds',
                placeholder: '3600',
                digits: 8,
                onChanged: (num? v) => _set(const <JsonStep>['repeat', 'seconds'], v?.toInt()),
              ), tip: 'Seconds after finishing before it can start again.'),
      ]),
      GuiSection(title: 'Messages', open: messages.isNotEmpty, tip: '{player} and {quest} are filled in. Empty uses the server default.', children: <Widget>[
        for (final String key in _messageLabels.keys)
          questRow(_messageLabels[key]!, '\$.messages.$key', diagnostics, (String id) => GuiTextInput(
                id: id,
                value: asString(messages[key]) ?? '',
                profile: _messageProfile,
                label: '${_messageLabels[key]} message',
                placeholder: 'Server default',
                onChanged: (String v) => _set(<JsonStep>['messages', key], v.isEmpty ? null : v),
              ), tip: _messageTips[key]),
      ]),
    ]);
  }
}
