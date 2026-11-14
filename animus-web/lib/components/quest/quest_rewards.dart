/// The quest's Rewards tab: items, money and experience; commands behind
/// More; in Advanced the behavior flags of the quest's namespace. Each list
/// is rows with a "..." menu and an Add button under it.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart' show flagValue;
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/mode.dart';
import '../gui/gui_fields.dart';
import '../gui/gui_rows.dart';
import 'quest_fields.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import '../gui/gui_stack.dart';
import '../../ui/stack.dart';

const FieldProfile _commandProfile = FieldProfile(command: true, placeholders: <String, String>{'player': "The player's name"});

class QuestRewards extends StatelessWidget {
  const QuestRewards({required this.quest, required this.diagnostics, required this.onWrite, required this.advanced, required this.onShow, super.key});

  final JsonMap quest;
  final List<Diagnostic> diagnostics;
  final QuestWrite onWrite;

  /// Shows the Advanced fields (the mode, or Show on this page).
  final bool advanced;

  /// Show on the "advanced settings in use" line.
  final void Function() onShow;

  JsonMap get _rewards => asMap(quest['rewards']);

  void _list(String key, JsonList next) => onWrite(<JsonStep>['rewards', key], next.isEmpty ? null : next);

  void _remove(String key, int index) => _list(key, <Object?>[...asList(_rewards[key])]..removeAt(index));

  /// The problems at [path]; notes about server setup only in Advanced.
  List<Diagnostic> _problems(String path) => <Diagnostic>[
        for (final Diagnostic d in problemsAt(diagnostics, path))
          if (advanced || d.severity != DiagnosticSeverity.info || path == r'$.rewards.commands') d,
      ];

  /// A list of [rows] with an Add button after it.
  Widget _rows({required String label, required String add, required List<OrderedRow> rows, required String key, required int max, required void Function() onAdd}) =>
      stack(<Widget>[
        if (rows.isNotEmpty) OrderedRows(label: label, rows: rows, onDelete: (int index) => _remove(key, index)),
        ActionRow(
          dense: true,
          label: label,
          secondary: <RowAction>[RowAction(add, rows.length >= max ? null : onAdd, icon: ArcaneIcon.plus())],
        ),
      ]);

  @override
  Widget build(BuildContext context) {
    final JsonMap rewards = _rewards;
    final JsonList items = asList(rewards['items']);
    final JsonList commands = asList(rewards['commands']);
    final JsonList flags = asList(rewards['flags']);
    final int hiddenErrors = advanced ? 0 : problemsAt(diagnostics, r'$.rewards.flags').where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    return stack(spacing: 16, <Widget>[
      if (advanced)
        for (final Diagnostic note in diagnosticsAt(diagnostics, r'$.rewards')) questAlert(note),
      Field(
        label: 'Items',
        problems: _problems(r'$.rewards.items'),
        tip: 'Given on completion. What does not fit drops at the player\'s feet.',
        child: _rows(
          label: 'Reward items',
          add: 'Add item',
          key: 'items',
          max: 27,
          onAdd: () => _list('items', <Object?>[...items, <String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}]),
          rows: <OrderedRow>[
            for (int index = 0; index < items.length; index++)
              OrderedRow(
                key: 'item-$index',
                name: 'reward item ${index + 1}',
                attributes: <String, String>{'data-reward-item': '$index'},
                line: fieldLine(<Widget>[
                  QuestItemField(steps: <JsonStep>['rewards', 'items', index, 'item'], item: asMap(asMap(items[index])['item']), onWrite: onWrite, label: 'Reward item ${index + 1}'),
                  NumberInput(
                    value: asInt(asMap(items[index])['amount']),
                    label: 'Reward item ${index + 1} amount',
                    placeholder: '1',
                    digits: 4,
                    onChanged: (num? v) => onWrite(<JsonStep>['rewards', 'items', index, 'amount'], v?.toInt(), coalesce: 'rewards.items.$index.amount'),
                  ),
                ]),
              ),
          ],
        ),
      ),
      questRow('Money', r'$.rewards.money', diagnostics, (String id) => NumberInput(
            id: id,
            value: asDouble(rewards['money']),
            integer: false,
            label: 'Reward money',
            placeholder: 'None',
            digits: 10,
            onChanged: (num? v) => onWrite(const <JsonStep>['rewards', 'money'], v, coalesce: 'rewards.money'),
          ), tip: 'Paid through the server economy (Vault on Bukkit).'),
      questRow('Experience', r'$.rewards.experience', diagnostics, (String id) => NumberInput(
            id: id,
            value: asInt(rewards['experience']),
            label: 'Reward experience',
            placeholder: 'None',
            digits: 7,
            onChanged: (num? v) => onWrite(const <JsonStep>['rewards', 'experience'], v?.toInt(), coalesce: 'rewards.experience'),
          ), tip: 'Experience points, not levels.'),
      MoreFields(open: commands.isNotEmpty, children: <Widget>[
        Field(
          label: 'Commands',
          problems: _problems(r'$.rewards.commands'),
          tip: 'Without the slash. {player} becomes the player\'s name.',
          child: _rows(
            label: 'Reward commands',
            add: 'Add command',
            key: 'commands',
            max: 16,
            onAdd: () => _list('commands', <Object?>[...commands, <String, Object?>{'command': 'say {player} finished a quest'}]),
            rows: <OrderedRow>[
              for (int index = 0; index < commands.length; index++)
                OrderedRow(
                  key: 'command-$index',
                  name: 'reward command ${index + 1}',
                  attributes: <String, String>{'data-reward-command': '$index'},
                  line: fieldLine(<Widget>[
                    AutocompleteInput(
                      value: asString(asMap(commands[index])['command']) ?? '',
                      profile: _commandProfile,
                      label: 'Reward command ${index + 1}',
                      placeholder: 'give {player} diamond 1',
                      onChanged: (String v) => onWrite(<JsonStep>['rewards', 'commands', index, 'command'], v.startsWith('/') ? v.substring(1) : v, coalesce: 'rewards.commands.$index'),
                    ),
                    AnimusSelect.values(
                      hiddenLabel: 'Reward command ${index + 1} runs as',
                      value: asString(asMap(commands[index])['as']) ?? 'console',
                      values: const <String>['console', 'player'],
                      labels: const <String, String>{'console': 'Console', 'player': 'Player'},
                      onChanged: (String? v) => onWrite(<JsonStep>['rewards', 'commands', index, 'as'], v == 'player' ? 'player' : null),
                    ),
                  ]),
                ),
            ],
          ),
        ),
      ]),
      if (advanced)
        Field(
          label: 'Flags',
          problems: _problems(r'$.rewards.flags'),
          tip: 'Behavior flags of this quest\'s namespace. An empty value sets true.',
          child: _rows(
            label: 'Reward flags',
            add: 'Add flag',
            key: 'flags',
            max: 16,
            onAdd: () => _list('flags', <Object?>[...flags, <String, Object?>{'name': 'done'}]),
            rows: <OrderedRow>[
              for (int index = 0; index < flags.length; index++)
                OrderedRow(
                  key: 'flag-$index',
                  name: 'reward flag ${index + 1}',
                  attributes: <String, String>{'data-reward-flag': '$index'},
                  line: fieldLine(<Widget>[
                    AutocompleteInput(
                      value: asString(asMap(flags[index])['name']) ?? '',
                      profile: const FieldProfile(values: <ValueRef>[ValueRef.flags()]),
                      label: 'Reward flag ${index + 1} name',
                      placeholder: 'helped_baker',
                      onChanged: (String v) => onWrite(<JsonStep>['rewards', 'flags', index, 'name'], v.trim(), coalesce: 'rewards.flags.$index.name'),
                    ),
                    AutocompleteInput(
                      value: asMap(flags[index]).containsKey('value') ? jsonEncode(asMap(flags[index])['value']) : '',
                      label: 'Reward flag ${index + 1} value',
                      placeholder: 'true',
                      onChanged: (String v) => onWrite(<JsonStep>['rewards', 'flags', index, 'value'], flagValue(v), coalesce: 'rewards.flags.$index.value'),
                      profile: FieldProfile.none,
                    ),
                    AnimusSelect.values(
                      hiddenLabel: 'Reward flag ${index + 1} scope',
                      value: asString(asMap(flags[index])['scope']) ?? 'player',
                      values: const <String>['player', 'global'],
                      labels: const <String, String>{'player': 'Player', 'global': 'Global'},
                      onChanged: (String? v) => onWrite(<JsonStep>['rewards', 'flags', index, 'scope'], v == 'global' ? 'global' : null),
                    ),
                  ]),
                ),
            ],
          ),
        )
      else
        questHidden(hidden: flags.length, errors: hiddenErrors, onShow: onShow),
    ]);
  }
}
