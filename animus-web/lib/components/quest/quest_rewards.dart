/// What finishing the quest gives: items, money, experience, commands and
/// behavior flags of the quest's namespace.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart' show flagValue;
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import 'quest_fields.dart';

const FieldProfile _commandProfile = FieldProfile(command: true, placeholders: <String, String>{'player': "The player's name"});

class QuestRewards extends StatelessWidget {
  const QuestRewards({required this.quest, required this.diagnostics, required this.onWrite, super.key});

  final JsonMap quest;
  final List<Diagnostic> diagnostics;
  final QuestWrite onWrite;

  JsonMap get _rewards => asMap(quest['rewards']);

  void _list(String key, JsonList next) => onWrite(<JsonStep>['rewards', key], next.isEmpty ? null : next);

  Widget _remove(String key, int index, String label) => GuiIconButton(
        icon: ArcaneIcon.x(size: IconSize.sm),
        label: 'Remove $label ${index + 1}',
        onPressed: () => _list(key, <Object?>[...asList(_rewards[key])]..removeAt(index)),
      );

  @override
  Widget build(BuildContext context) {
    final JsonMap rewards = _rewards;
    final JsonList items = asList(rewards['items']);
    final JsonList commands = asList(rewards['commands']);
    final JsonList flags = asList(rewards['flags']);
    return dom.div(classes: 'animus-quest-rewards', <Widget>[
      for (final Diagnostic note in diagnosticsAt(diagnostics, r'$.rewards'))
        dom.p(classes: 'animus-gui-problem is-${note.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(note.message)]),
      GuiRow(
        label: 'Items',
        problems: diagnosticsAt(diagnostics, r'$.rewards.items'),
        tip: 'Given on completion. What does not fit drops at the player\'s feet.',
        child: dom.div(classes: 'animus-gui-lines', <Widget>[
          for (int index = 0; index < items.length; index++)
            dom.div(classes: 'animus-gui-line animus-quest-reward-item', attributes: <String, String>{'data-reward-item': '$index'}, <Widget>[
              QuestItemField(steps: <JsonStep>['rewards', 'items', index, 'item'], item: asMap(asMap(items[index])['item']), onWrite: onWrite, label: 'Reward item ${index + 1}'),
              GuiNumberInput(
                value: asInt(asMap(items[index])['amount']),
                label: 'Reward item ${index + 1} amount',
                placeholder: '1',
                digits: 4,
                onChanged: (num? v) => onWrite(<JsonStep>['rewards', 'items', index, 'amount'], v?.toInt(), coalesce: 'rewards.items.$index.amount'),
              ),
              _remove('items', index, 'reward item'),
              for (final Diagnostic problem in problemsAt(diagnostics, '\$.rewards.items[$index]'))
                dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(problem.message)]),
            ]),
          Button.ghost(
            label: '+ Add item',
            size: ButtonSize.small,
            disabled: items.length >= 27,
            onPressed: () => _list('items', <Object?>[...items, <String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}]),
          ),
        ]),
      ),
      questRow('Money', r'$.rewards.money', diagnostics, (String id) => GuiNumberInput(
            id: id,
            value: asDouble(rewards['money']),
            integer: false,
            label: 'Reward money',
            placeholder: 'None',
            digits: 10,
            onChanged: (num? v) => onWrite(const <JsonStep>['rewards', 'money'], v, coalesce: 'rewards.money'),
          ), tip: 'Paid through the server economy (Vault on Bukkit).'),
      questRow('Experience', r'$.rewards.experience', diagnostics, (String id) => GuiNumberInput(
            id: id,
            value: asInt(rewards['experience']),
            label: 'Reward experience',
            placeholder: 'None',
            digits: 7,
            onChanged: (num? v) => onWrite(const <JsonStep>['rewards', 'experience'], v?.toInt(), coalesce: 'rewards.experience'),
          ), tip: 'Experience points, not levels.'),
      GuiRow(
        label: 'Commands',
        problems: problemsAt(diagnostics, r'$.rewards.commands'),
        tip: 'Without the slash. {player} becomes the player\'s name.',
        child: dom.div(classes: 'animus-gui-lines', <Widget>[
          for (int index = 0; index < commands.length; index++)
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(
                value: asString(asMap(commands[index])['command']) ?? '',
                profile: _commandProfile,
                label: 'Reward command ${index + 1}',
                placeholder: 'give {player} diamond 1',
                onChanged: (String v) => onWrite(<JsonStep>['rewards', 'commands', index, 'command'], v.startsWith('/') ? v.substring(1) : v, coalesce: 'rewards.commands.$index'),
              ),
              GuiSelect(
                hiddenLabel: 'Reward command ${index + 1} runs as',
                value: asString(asMap(commands[index])['as']) ?? 'console',
                options: const <String>['console', 'player'],
                labels: const <String, String>{'console': 'Console', 'player': 'Player'},
                onChanged: (String? v) => onWrite(<JsonStep>['rewards', 'commands', index, 'as'], v == 'player' ? 'player' : null),
              ),
              _remove('commands', index, 'reward command'),
            ]),
          Button.ghost(
            label: '+ Add command',
            size: ButtonSize.small,
            disabled: commands.length >= 16,
            onPressed: () => _list('commands', <Object?>[...commands, <String, Object?>{'command': 'say {player} finished a quest'}]),
          ),
        ]),
      ),
      GuiRow(
        label: 'Flags',
        problems: problemsAt(diagnostics, r'$.rewards.flags'),
        tip: 'Behavior flags of this quest\'s namespace. An empty value sets true.',
        child: dom.div(classes: 'animus-gui-lines', <Widget>[
          for (int index = 0; index < flags.length; index++)
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(
                value: asString(asMap(flags[index])['name']) ?? '',
                profile: const FieldProfile(values: <ValueRef>[ValueRef.flags()]),
                label: 'Reward flag ${index + 1} name',
                placeholder: 'helped_baker',
                onChanged: (String v) => onWrite(<JsonStep>['rewards', 'flags', index, 'name'], v.trim(), coalesce: 'rewards.flags.$index.name'),
              ),
              GuiTextInput(
                value: asMap(flags[index]).containsKey('value') ? jsonEncode(asMap(flags[index])['value']) : '',
                label: 'Reward flag ${index + 1} value',
                placeholder: 'true',
                onChanged: (String v) => onWrite(<JsonStep>['rewards', 'flags', index, 'value'], flagValue(v), coalesce: 'rewards.flags.$index.value'),
              ),
              GuiSelect(
                hiddenLabel: 'Reward flag ${index + 1} scope',
                value: asString(asMap(flags[index])['scope']) ?? 'player',
                options: const <String>['player', 'global'],
                labels: const <String, String>{'player': 'Player', 'global': 'Global'},
                onChanged: (String? v) => onWrite(<JsonStep>['rewards', 'flags', index, 'scope'], v == 'global' ? 'global' : null),
              ),
              _remove('flags', index, 'reward flag'),
            ]),
          Button.ghost(
            label: '+ Add flag',
            size: ButtonSize.small,
            disabled: flags.length >= 16,
            onPressed: () => _list('flags', <Object?>[...flags, <String, Object?>{'name': 'done'}]),
          ),
        ]),
      ),
    ]);
  }
}
