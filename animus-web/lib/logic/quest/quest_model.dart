/// Quest documents (`quests/<namespace>/<name>.json`, schema
/// quest-v1.schema.json): a valid starting quest, objective templates and
/// type changes, the default journal line of each objective, the checks the
/// editor adds to the schema, and the journal entry the preview draws.
library;

import 'dart:convert';

import '../../model/animus_document.dart';
import '../gui/gui_json.dart';

/// Objective types in the order the editor offers them.
const List<String> questObjectiveTypes = <String>['talk', 'bring', 'kill', 'reach', 'collect', 'custom'];

const Map<String, String> questObjectiveLabels = <String, String>{
  'talk': 'Talk to an NPC',
  'bring': 'Bring items to an NPC',
  'kill': 'Kill mobs or an NPC',
  'reach': 'Reach a place',
  'collect': 'Collect items',
  'custom': 'Custom',
};

/// The fields each objective type may hold besides `type`, `id` and
/// `description`.
const Map<String, Set<String>> _typeFields = <String, Set<String>>{
  'talk': <String>{'npc'},
  'bring': <String>{'npc', 'item', 'amount'},
  'kill': <String>{'entity', 'npc', 'amount'},
  'reach': <String>{'world', 'x', 'y', 'z', 'radius'},
  'collect': <String>{'item', 'amount'},
  'custom': <String>{'amount'},
};

const String questCheckCode = 'quest-check';

/// A quest that the schema accepts and that needs no NPC: one stage that
/// collects wheat, with an experience reward.
JsonMap newQuest(String id, {String? title}) => <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': id,
      'title': title == null || title.trim().isEmpty ? 'New quest' : title.trim(),
      'description': 'Bring the baker some wheat.',
      'stages': <Object?>[
        <String, Object?>{
          'description': 'Gather wheat',
          'objectives': <Object?>[newObjective('collect')],
        },
      ],
      'rewards': <String, Object?>{'experience': 50},
    };

/// A starting objective of [type]; [npc] fills the NPC of talk and bring.
JsonMap newObjective(String type, {String? npc}) => switch (type) {
      'talk' => <String, Object?>{'type': 'talk', 'npc': npc ?? 'npc'},
      'bring' => <String, Object?>{'type': 'bring', 'npc': npc ?? 'npc', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 1},
      'kill' => <String, Object?>{'type': 'kill', 'entity': 'minecraft:zombie', 'amount': 1},
      'reach' => <String, Object?>{'type': 'reach', 'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'radius': 3},
      'custom' => <String, Object?>{'type': 'custom', 'id': 'custom', 'amount': 1},
      _ => <String, Object?>{'type': 'collect', 'item': <String, Object?>{'id': 'minecraft:wheat'}, 'amount': 10},
    };

/// [objective] as [type]: its id and description stay, and every field the
/// new type also takes; the rest come from the type's template.
JsonMap retypeObjective(JsonMap objective, String type, {String? npc}) {
  final Set<String> allowed = _typeFields[type] ?? const <String>{};
  final JsonMap template = newObjective(type, npc: asString(objective['npc']) ?? npc);
  final JsonMap out = <String, Object?>{'type': type};
  for (final String key in <String>['id', 'description']) {
    if (objective[key] != null) out[key] = objective[key];
  }
  for (final MapEntry<String, Object?> field in template.entries) {
    if (field.key == 'type' || out.containsKey(field.key)) continue;
    out[field.key] = field.value;
  }
  for (final String key in allowed) {
    if (objective[key] != null) out[key] = objective[key];
  }
  // A kill objective names an entity type or an NPC, never both.
  if (type == 'kill' && out.containsKey('npc') && out.containsKey('entity')) out.remove(objective.containsKey('entity') ? 'npc' : 'entity');
  return out;
}

/// `minecraft:oak_log` as `oak log`.
String _words(String? id) {
  if (id == null || id.isEmpty) return 'item';
  final String path = id.contains(':') ? id.substring(id.indexOf(':') + 1) : id;
  return path.replaceAll('_', ' ').toLowerCase();
}

/// How many an objective asks for: its `amount`, 1 by default.
int objectiveAmount(JsonMap objective) => asInt(objective['amount']) ?? 1;

/// The journal line of [objective] as the server words it (QuestText): its
/// `description`, else one built from its fields. [npcName] turns an NPC id
/// into the name players see.
String objectiveLine(JsonMap objective, {String Function(String id)? npcName}) {
  final String? described = asString(objective['description']);
  if (described != null && described.trim().isNotEmpty) return described;
  String npc() {
    final String id = asString(objective['npc']) ?? '';
    return npcName == null ? id : npcName(id);
  }

  String round(Object? value) => value is num ? value.round().toString() : '?';
  final int amount = objectiveAmount(objective);
  final String item = _words(asString(asMap(objective['item'])['id']));
  return switch (asString(objective['type'])) {
    'talk' => 'Talk to ${npc()}',
    'bring' => 'Bring $amount $item to ${npc()}',
    'kill' => objective['entity'] != null ? 'Defeat $amount ${_words(asString(objective['entity']))}' : 'Defeat ${npc()}',
    'reach' => 'Go to ${round(objective['x'])}, ${round(objective['y'])}, ${round(objective['z'])}',
    'collect' => 'Collect $amount $item',
    'custom' => (asString(objective['id']) ?? '').replaceAll(RegExp(r'[_-]'), ' '),
    _ => 'Objective',
  };
}

/// The keys of the lines a quest says (`messages`), in the order the
/// editor lists them.
const List<String> questMessageKeys = <String>['offer', 'accept', 'decline', 'complete'];

/// [path] is [field] or a path inside it.
bool pathUnder(String path, String field) => path == field || path.startsWith('$field.') || path.startsWith('$field[');

/// The Quest tab's fields only Advanced shows: the NPC to return to, who
/// may start it, how often, and the messages.
const List<String> questSettingsAdvancedPaths = <String>[r'$.turnIn', r'$.requirements', r'$.repeat', r'$.messages'];

/// How many of the Quest tab's Advanced fields [quest] sets, each counted
/// once.
int questSettingsInUse(JsonMap quest) {
  final JsonMap requirements = asMap(quest['requirements']);
  final JsonMap messages = asMap(quest['messages']);
  return <bool>[
    quest['turnIn'] != null,
    requirements['permission'] != null,
    asList(requirements['quests']).isNotEmpty,
    requirements['condition'] != null,
    quest['repeat'] != null,
    for (final String key in questMessageKeys) messages[key] != null,
  ].where((bool on) => on).length;
}

/// The paths of the stage and objective fields only Advanced shows: each
/// stage's order and the ids of objectives that are not custom.
List<String> questStagesAdvancedPaths(JsonMap quest) {
  final List<String> out = <String>[];
  final JsonList stages = asList(quest['stages']);
  for (int stage = 0; stage < stages.length; stage++) {
    out.add('\$.stages[$stage].order');
    final JsonList objectives = asList(asMap(stages[stage])['objectives']);
    for (int index = 0; index < objectives.length; index++) {
      if (asString(asMap(objectives[index])['type']) != 'custom') out.add('\$.stages[$stage].objectives[$index].id');
    }
  }
  return out;
}

/// How many of the stage and objective fields only Advanced shows [quest]
/// sets.
int questStagesInUse(JsonMap quest) {
  int count = 0;
  for (final Object? stage in asList(quest['stages'])) {
    if (asString(asMap(stage)['order']) != null) count++;
    for (final Object? objective in asList(asMap(stage)['objectives'])) {
      if (asString(asMap(objective)['type']) != 'custom' && asString(asMap(objective)['id']) != null) count++;
    }
  }
  return count;
}

/// The name behaviors of the quest's namespace read its stage by.
String questStateName(String id) => id.contains(':') ? id.substring(id.indexOf(':') + 1) : id;

/// The behavior expression that reads a player's stage of quest [id]:
/// 0 before it starts, 1 to N while stage N is active, N + 1 once finished.
String questStageExpression(String id) => "quest('player', '${questStateName(id)}')";

/// Checks beside the schema: references the workspace lacks ([npcs] are NPC
/// ids, the `id` field; [quests] are quest ids; null skips that check),
/// items the registry lacks, repeated objective ids (the server refuses
/// those), rewards that need the apply:commands scope to apply, and reward
/// commands that need the command allow-list.
List<Diagnostic> questChecks(JsonMap quest, {Set<String>? npcs, Set<String>? quests, bool Function(String id)? knownItem}) {
  final List<Diagnostic> out = <Diagnostic>[];
  void warn(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.warning, path, message, code: questCheckCode));
  void npcAt(String path, Object? value) {
    final String? id = asString(value);
    if (npcs != null && id != null && id.isNotEmpty && !npcs.contains(id)) {
      warn(path, 'no NPC with id $id in this workspace; the quest waits for one');
    }
  }

  void itemAt(String path, Object? item) {
    final String? id = asString(asMap(item)['id']);
    if (knownItem != null && id != null && id.isNotEmpty && !knownItem(id.contains(':') ? id : 'minecraft:$id')) {
      warn('$path.id', '$id is not a known item');
    }
  }

  final JsonList givers = asList(quest['givers']);
  for (int index = 0; index < givers.length; index++) {
    npcAt('\$.givers[$index]', givers[index]);
  }
  npcAt(r'$.turnIn', quest['turnIn']);
  final JsonList required = asList(asMap(quest['requirements'])['quests']);
  for (int index = 0; index < required.length; index++) {
    final String? id = asString(required[index]);
    if (quests != null && id != null && !quests.contains(id)) warn('\$.requirements.quests[$index]', 'quest $id is not in this workspace');
  }
  final JsonList stages = asList(quest['stages']);
  for (int stage = 0; stage < stages.length; stage++) {
    final JsonList objectives = asList(asMap(stages[stage])['objectives']);
    final Set<String> ids = <String>{};
    for (int index = 0; index < objectives.length; index++) {
      final String path = '\$.stages[$stage].objectives[$index]';
      final JsonMap objective = asMap(objectives[index]);
      final String? id = asString(objective['id']);
      if (id != null && !ids.add(id)) {
        out.add(Diagnostic(DiagnosticSeverity.error, '$path.id', 'another objective of this stage is already $id', code: questCheckCode));
      }
      npcAt('$path.npc', objective['npc']);
      if (objective.containsKey('item')) itemAt('$path.item', objective['item']);
    }
  }
  final JsonMap rewards = asMap(quest['rewards']);
  final JsonList items = asList(rewards['items']);
  for (int index = 0; index < items.length; index++) {
    itemAt('\$.rewards.items[$index].item', asMap(items[index])['item']);
  }
  if (items.isNotEmpty || rewards['money'] != null || rewards['experience'] != null || asList(rewards['commands']).isNotEmpty) {
    out.add(const Diagnostic(
      DiagnosticSeverity.info,
      r'$.rewards',
      'items, money, experience and commands create value, so applying this quest from the editor needs the apply:commands scope',
      code: questCheckCode,
    ));
  }
  if (asList(rewards['commands']).isNotEmpty) {
    out.add(const Diagnostic(
      DiagnosticSeverity.info,
      r'$.rewards.commands',
      'run only when the quest or its namespace is on the command allow-list in config.toml',
      code: questCheckCode,
    ));
  }
  return out;
}

/// What the journal shows for a quest: its item name and lore lines, in `&`
/// codes.
class QuestJournalEntry {
  const QuestJournalEntry({required this.title, required this.lore, required this.icon});

  final String title;
  final List<String> lore;
  final String icon;
}

const int _loreWidth = 36;

/// Plain words wrapped at about 36 characters, each line starting in
/// [colour], as the server wraps journal lore.
List<String> wrapLore(String text, String colour) {
  final List<String> lines = <String>[];
  final StringBuffer line = StringBuffer();
  for (final String word in text.trim().split(RegExp(r'\s+'))) {
    if (word.isEmpty) continue;
    if (line.isNotEmpty && '$line $word'.replaceAll(RegExp(r'&[0-9a-fk-or]', caseSensitive: false), '').length > _loreWidth) {
      lines.add('$colour$line');
      line.clear();
    }
    if (line.isNotEmpty) line.write(' ');
    line.write(word);
  }
  if (line.isNotEmpty) lines.add('$colour$line');
  return lines;
}

/// The journal entry of [quest] while stage [stage] (0-based) is active with
/// nothing counted yet, or once [finished], laid out as the server's
/// QuestJournal lays it out.
QuestJournalEntry journalEntry(JsonMap quest, {int stage = 0, bool finished = false, String Function(String id)? npcName}) {
  final JsonList stages = asList(quest['stages']);
  final List<String> lore = wrapLore(asString(quest['description']) ?? '', '&7');
  if (lore.isNotEmpty) lore.add('');
  if (finished) {
    lore.add('&aFinished');
  } else if (stages.isNotEmpty) {
    final int shown = stage.clamp(0, stages.length - 1);
    final JsonMap current = asMap(stages[shown]);
    lore.add('&6Stage ${shown + 1}/${stages.length}');
    lore.addAll(wrapLore(asString(current['description']) ?? '', '&f'));
    for (final Object? objective in asList(current['objectives'])) {
      final JsonMap fields = asMap(objective);
      final String type = asString(fields['type']) ?? '';
      final String count = type == 'talk' || type == 'reach' ? '' : ' 0/${objectiveAmount(fields)}';
      lore.add('&e• &f${objectiveLine(fields, npcName: npcName)}${count.isEmpty ? '' : '&7$count'}');
    }
  }
  return QuestJournalEntry(
    title: asString(quest['title']) ?? asString(quest['id']) ?? 'Quest',
    lore: lore,
    icon: asString(asMap(quest['icon'])['id']) ?? (finished ? 'minecraft:book' : 'minecraft:writable_book'),
  );
}

/// What a giver says before the Accept and Decline buttons: the `offer`
/// message, else the description, else the server's default line, with
/// {player} and {quest} filled.
String offerLine(JsonMap quest, {String player = 'Steve'}) {
  final String? description = asString(quest['description']);
  final String text = asString(asMap(quest['messages'])['offer']) ?? (description == null || description.trim().isEmpty ? 'I have a task for you.' : description);
  final String title = (asString(quest['title']) ?? '').replaceAll(RegExp(r'&[0-9a-fk-or]', caseSensitive: false), '');
  return text.replaceAll('{player}', player).replaceAll('{quest}', title);
}

/// A flag value typed as text: JSON when it parses (true, 3, "x"), else the
/// text itself; empty is null, which leaves the default true.
Object? flagValue(String text) {
  final String trimmed = text.trim();
  if (trimmed.isEmpty) return null;
  try {
    return jsonDecode(trimmed);
  } on FormatException {
    return trimmed;
  }
}
