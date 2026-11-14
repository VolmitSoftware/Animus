/// A behavior dry run in the browser: walks a routine node by node the way
/// the interpreter would and records what the NPC would say, ask, walk to
/// and change, without a server. Flags, quest stages and variables live in
/// the run's own state; expressions, loops and operands run through the
/// browser evaluator. Conditions only a server can answer (permissions,
/// health, who is near, facts read by an expression) use an assumption the
/// user can flip, and actions that reach the server (commands, combat,
/// building, items, shop trades and money) are listed as not run. Answers to
/// a prompt start the `dialogue_choice` trigger that matches them, as a new
/// run.
///
/// The run is a pure function of the behavior and the inputs (the start
/// trigger, each answer picked, each assumption), so changing an input
/// replays the whole session from the start.
library;

import 'dart:math' as math;

import '../../model/animus_document.dart';
import '../../model/display_name.dart';
import '../../model/document_kind.dart';
import '../../model/document_summary.dart' show plainName;
import '../gui/gui_json.dart';
import '../inspect/behavior_eval.dart';
import '../inspect/behavior_operands.dart';
import '../inspect/behavior_summary.dart';
import '../inspect/behavior_words.dart' show soundName;
import '../inspect/named_ids.dart' show particleName;
import '../item_ids.dart';
import '../inspect/server_behaviors.dart';

enum DryRunOutcome {
  /// Shown in the dry run: speech, prompts, movement, waits, state changes.
  previewed,

  /// A condition decided by an assumption, not by a server.
  assumed,

  /// Needs the server; nothing happened.
  serverOnly,

  /// Stopped the run (an unknown routine, a loop limit, a failed expression).
  problem,
}

class DryRunOption {
  const DryRunOption(this.id, this.text);

  final String id;
  final String text;
}

class DryRunStep {
  const DryRunStep({
    required this.path,
    required this.outcome,
    required this.text,
    this.depth = 0,
    this.speech,
    this.prompt,
    this.options = const <DryRunOption>[],
    this.dialogue,
    this.assumption,
    this.animation,
    this.target,
  });

  /// Where the node is in the behavior, as `$.routines.main.steps[1]`.
  final String path;
  final DryRunOutcome outcome;

  /// One plain line about what happens.
  final String text;
  final int depth;

  /// For `animus:say` and player messages: the line, with its colour codes.
  final String? speech;

  /// For `animus:choices`: the prompt, its options and its dialogue id.
  final String? prompt;
  final List<DryRunOption> options;
  final String? dialogue;

  /// For an assumed condition: the value used.
  final bool? assumption;

  /// For `animus:play-animation`: the animation id.
  final String? animation;

  /// For `animus:navigate`: the point walked to, when it names one.
  final (double, double, double)? target;
}

/// One run: what started it and every step it took.
class DryRunRun {
  const DryRunRun(this.title, this.steps);

  final String title;
  final List<DryRunStep> steps;

  /// The prompts this run left open, in order.
  List<DryRunStep> get prompts => <DryRunStep>[for (final DryRunStep step in steps) if (step.dialogue != null) step];
}

/// A trigger a dry run can start from.
class DryRunStart {
  const DryRunStart(this.id, this.label, this.routine, {this.carriesPlayer = true, this.context = const <String, Object?>{}});

  /// The trigger id, or null for the entry routine itself.
  final String? id;
  final String label;
  final String routine;
  final bool carriesPlayer;

  /// Sample values of the event, read as `context.<key>`.
  final Map<String, Object?> context;
}

/// One thing the user did in a dry run: started a trigger, or answered the
/// open prompt.
sealed class DryRunEvent {
  const DryRunEvent();
}

class DryRunTrigger extends DryRunEvent {
  const DryRunTrigger(this.id);

  /// A trigger id, or null for the entry routine.
  final String? id;
}

class DryRunAnswer extends DryRunEvent {
  const DryRunAnswer(this.dialogue, this.option);

  final String dialogue;
  final String option;
}

/// What the user decided: the triggers and answers in order, and each
/// assumption by node path. State carries from one run to the next, so a
/// second click sees the flags the first one set.
class DryRunInputs {
  const DryRunInputs({this.events = const <DryRunEvent>[], this.assumptions = const <String, bool>{}});

  /// Starts with one trigger (null: the entry routine).
  DryRunInputs.start(String? trigger) : this(events: <DryRunEvent>[DryRunTrigger(trigger)]);

  final List<DryRunEvent> events;
  final Map<String, bool> assumptions;

  DryRunInputs withTrigger(String? id) => DryRunInputs(events: <DryRunEvent>[...events, DryRunTrigger(id)], assumptions: assumptions);

  DryRunInputs withAnswer(String dialogue, String option) =>
      DryRunInputs(events: <DryRunEvent>[...events, DryRunAnswer(dialogue, option)], assumptions: assumptions);

  DryRunInputs withAssumption(String path, bool value) =>
      DryRunInputs(events: events, assumptions: <String, bool>{...assumptions, path: value});

  /// The inputs without the last event, for stepping back.
  DryRunInputs withoutLast() =>
      DryRunInputs(events: events.isEmpty ? events : events.sublist(0, events.length - 1), assumptions: assumptions);
}

class DryRunSession {
  const DryRunSession(this.runs, this.state, this.unanswered);

  final List<DryRunRun> runs;

  /// Flags, quest stages and variables after the last run, as display lines.
  final Map<String, Object?> state;

  /// The last prompt nobody has answered yet, or null.
  final DryRunStep? unanswered;

  int get stepCount => runs.fold<int>(0, (int total, DryRunRun run) => total + run.steps.length);

  List<DryRunStep> get serverOnly => <DryRunStep>[
        for (final DryRunRun run in runs)
          for (final DryRunStep step in run.steps)
            if (step.outcome == DryRunOutcome.serverOnly) step,
      ];
}

/// Capabilities whose effect only a server has.
const Map<String, String> serverOnlyCapabilities = <String, String>{
  'animus:command': 'Runs a server command',
  'animus:combat-control': 'Changes combat',
  'animus:build': 'Builds in the world',
  'animus:give-item': 'Gives the player items',
  'animus:take-item': 'Takes items from the player',
  'animus:give-book': 'Gives the player a book',
  'animus:teleport': 'Teleports the player',
};

/// Conditions a server answers; the dry run assumes them.
const Map<String, String> assumedConditions = <String, String>{
  'animus:has-permission': 'the player has a permission',
  'animus:npc-state': 'the NPC is spawned and its health is in range',
  'animus:player-in-range': 'a player is in range',
};

/// The most steps one session records, so a long repeat cannot hang the page.
const int dryRunStepLimit = 400;

/// The most passes one loop makes in a dry run.
const int dryRunLoopLimit = 1000;

/// Events that start a run without a player.
const Set<String> _playerless = <String>{'timer', 'spawn', 'despawn', 'waypoint', 'module', 'build_finished', 'build_paused', 'animation_finished', 'server_start', 'damage', 'death'};

/// Sample event values, read as `context.<key>`, per server event.
Map<String, Object?> sampleContext(JsonMap trigger) => switch (asString(trigger['event'])) {
      'player_join' => <String, Object?>{'first': trigger['first'] is bool ? trigger['first'] : true},
      'player_chat' => <String, Object?>{'message': (asString(trigger['pattern']) ?? 'hello').replaceAll('*', '')},
      'player_death' => <String, Object?>{'message': 'Player fell from a high place'},
      'player_world_change' => <String, Object?>{'from': asString(trigger['from']) ?? 'world', 'to': asString(trigger['to']) ?? 'world_nether'},
      'command' => <String, Object?>{'command': asString(trigger['name']) ?? 'command', 'alias': asString(trigger['name']) ?? 'command', 'args': <Object?>[], 'raw_args': ''},
      _ => const <String, Object?>{},
    };

/// Every way to start [behavior]: its triggers (dialogue answers start from
/// prompts instead) and the entry routine.
List<DryRunStart> dryRunStarts(JsonMap behavior) {
  final String entry = asString(behavior['entry']) ?? 'main';
  final List<DryRunStart> starts = <DryRunStart>[];
  for (final Object? raw in asList(behavior['triggers'])) {
    final JsonMap trigger = asMap(raw);
    final String? id = asString(trigger['id']);
    final String event = asString(trigger['event']) ?? '?';
    if (id == null || event == 'dialogue_choice') continue;
    starts.add(DryRunStart(id, '$id: ${_eventLine(trigger)}', asString(trigger['routine']) ?? entry,
        carriesPlayer: !_playerless.contains(event), context: sampleContext(trigger)));
  }
  starts.add(DryRunStart(null, 'Entry routine ($entry)', entry, carriesPlayer: behavior['server'] != true));
  return starts;
}

String _eventLine(JsonMap trigger) {
  final String event = asString(trigger['event']) ?? '?';
  return switch (event) {
    'interaction' => switch (asString(trigger['action'])) {
        'attack' => 'a player left-clicks the NPC',
        'any' => 'a player clicks the NPC',
        'sneak_interact' => 'a sneaking player right-clicks the NPC',
        'sneak_attack' => 'a sneaking player left-clicks the NPC',
        'drop' => 'a player presses drop at the NPC',
        'sneak_drop' => 'a sneaking player presses drop at the NPC',
        'swap_hands' => 'a player presses swap hands at the NPC',
        'sneak_swap_hands' => 'a sneaking player presses swap hands at the NPC',
        _ => 'a player right-clicks the NPC',
      },
    'proximity' => 'a player comes within ${_number(trigger['radius']) ?? '?'} blocks',
    'timer' => 'every ${_ticks(trigger['intervalTicks'])}',
    'spawn' => 'the NPC spawns',
    'despawn' => 'the NPC despawns',
    'damage' => 'the NPC is hurt',
    'death' => 'the NPC dies',
    'waypoint' => 'the NPC reaches a waypoint',
    'player_join' => trigger['first'] == true ? 'a player joins for the first time' : (trigger['first'] == false ? 'a returning player joins' : 'a player joins'),
    'player_quit' => 'a player leaves',
    'player_chat' => trigger['pattern'] is String ? 'a player says "${trigger['pattern']}"' : 'a player chats',
    'command' => 'someone runs /${asString(trigger['name']) ?? '?'}',
    'server_start' => 'the server starts',
    'player_death' => 'a player dies',
    'player_respawn' => 'a player respawns',
    'player_world_change' => 'a player changes world',
    _ => event.replaceAll('_', ' '),
  };
}

String? _number(Object? value) {
  if (value is! num) return null;
  return value == value.roundToDouble() ? '${value.toInt()}' : '$value';
}

String _ticks(Object? value) {
  final num ticks = value is num ? value : 0;
  final double seconds = ticks / 20;
  final String shown = seconds == seconds.roundToDouble() ? '${seconds.toInt()}' : seconds.toStringAsFixed(1);
  return '$ticks ticks (${shown}s)';
}

/// The one note the Test of [behavior] needs: a server behavior runs only
/// once the server list names it. Null otherwise.
String? testPaneNote(AnimusDocument behavior, List<AnimusDocument> workspace) =>
    asMap(behavior.json)['server'] == true && !listedServerBehaviors(workspace).contains(behavior.id) ? 'Not running on the server yet' : null;

/// Runs [behavior] with [inputs] from the start. An answer that does not
/// match the open prompt is ignored.
/// Runs [inputs] against [behavior]. [plain] words the steps for Simple:
/// flags by whose they are, items and documents by name ([names]),
/// branches as Yes and No; otherwise the engine's own words.
DryRunSession dryRun(JsonMap behavior, DryRunInputs inputs, {bool plain = false, DocumentNames names = idDocumentName}) {
  final _Machine machine = _Machine(behavior, inputs.assumptions, plain: plain, names: names);
  final List<DryRunStart> starts = dryRunStarts(behavior);
  final List<DryRunRun> runs = <DryRunRun>[];
  DryRunStep? open;
  for (final DryRunEvent event in inputs.events) {
    switch (event) {
      case DryRunTrigger(:final String? id):
        final DryRunStart start = starts.firstWhere((DryRunStart candidate) => candidate.id == id, orElse: () => starts.last);
        runs.add(machine.run(start.label, start.routine, start.context));
      case DryRunAnswer(:final String dialogue, :final String option):
        final DryRunStep? prompt = open;
        if (prompt == null || prompt.dialogue != dialogue) continue;
        final JsonMap? trigger = _answerTrigger(behavior, dialogue, option);
        final String optionText = prompt.options.firstWhere((DryRunOption candidate) => candidate.id == option, orElse: () => DryRunOption(option, option)).text;
        if (trigger == null) {
          runs.add(DryRunRun('Answer "$optionText"', <DryRunStep>[
            DryRunStep(path: r'$.triggers', outcome: DryRunOutcome.problem, text: 'No dialogue_choice trigger answers $dialogue / $option, so nothing runs.'),
          ]));
        } else {
          final String routine = asString(trigger['routine']) ?? asString(behavior['entry']) ?? 'main';
          runs.add(machine.run('Answer "$optionText" (${asString(trigger['id']) ?? 'trigger'})', routine, const <String, Object?>{}));
        }
    }
    open = runs.isEmpty || runs.last.prompts.isEmpty ? null : runs.last.prompts.last;
  }
  return DryRunSession(runs, machine.stateLines(), open);
}

JsonMap? _answerTrigger(JsonMap behavior, String dialogue, String option) {
  for (final Object? raw in asList(behavior['triggers'])) {
    final JsonMap trigger = asMap(raw);
    if (trigger['event'] == 'dialogue_choice' && trigger['dialogue'] == dialogue && trigger['choice'] == option) return trigger;
  }
  return null;
}

/// Control flow out of a node: a loop exit, a routine return, a failure.
sealed class _Jump implements Exception {
  const _Jump();
}

class _Break extends _Jump {
  const _Break();
}

class _Continue extends _Jump {
  const _Continue();
}

class _Return extends _Jump {
  const _Return(this.value);

  final Object? value;
}

class _Halt extends _Jump {
  const _Halt();
}

class _Machine {
  _Machine(this.behavior, this.assumptions, {this.plain = false, this.names = idDocumentName}) {
    for (final MapEntry<String, Object?> entry in _declared.entries) {
      final JsonMap declaration = asMap(entry.value);
      if ((asString(declaration['scope']) ?? 'execution') != 'execution') variables[entry.key] = declaration['initial'];
    }
  }

  final JsonMap behavior;
  final Map<String, bool> assumptions;

  /// Simple's words (see [dryRun]).
  final bool plain;
  final DocumentNames names;
  final Map<String, Object?> flags = <String, Object?>{};
  final Map<String, int> quests = <String, int>{};
  final Map<String, Object?> variables = <String, Object?>{};

  /// Seeded so a replay takes the same random branches.
  final math.Random _random = math.Random(7);
  int _total = 0;
  late List<DryRunStep> _steps;
  late Map<String, Object?> _execution;
  Map<String, Object?> _context = const <String, Object?>{};

  JsonMap get _routines => asMap(behavior['routines']);
  JsonMap get _declared => asMap(behavior['variables']);
  bool get _server => behavior['server'] == true;

  DryRunRun run(String title, String routine, Map<String, Object?> context) {
    _steps = <DryRunStep>[];
    _context = context;
    _execution = <String, Object?>{
      for (final MapEntry<String, Object?> entry in _declared.entries)
        if ((asString(asMap(entry.value)['scope']) ?? 'execution') == 'execution') entry.key: asMap(entry.value)['initial'],
    };
    if (!_routines.containsKey(routine)) {
      _add(DryRunStep(path: r'$.routines', outcome: DryRunOutcome.problem, text: 'There is no routine named $routine.'));
    } else {
      try {
        _node(_routines[routine], childPath(r'$.routines', routine), 0, <String>[routine]);
      } on _Return {
        // The routine ended early; the run is over.
      } on _Halt {
        // A problem step says why.
      } on _Break {
        // Placement problems are reported where they occur.
      } on _Continue {
        // Same as above.
      }
    }
    return DryRunRun(title, _steps);
  }

  bool get _full => _total >= dryRunStepLimit;

  void _add(DryRunStep step) {
    if (_full) return;
    _total++;
    _steps.add(step);
    if (_full) {
      _steps.add(DryRunStep(path: step.path, outcome: DryRunOutcome.problem, text: 'Stopped after $dryRunStepLimit steps.'));
    }
  }

  Never _fail(String path, int depth, String text) {
    _add(DryRunStep(path: path, outcome: DryRunOutcome.problem, depth: depth, text: text));
    throw const _Halt();
  }

  String _key(JsonMap arguments) => '${asString(arguments['name']) ?? '?'} (${asString(arguments['scope']) ?? 'player'})';

  /// A flag or quest in a step's text: its key, or in [plain] words whose
  /// it is ("met with this NPC").
  String _named(JsonMap arguments) {
    if (!plain) return _key(arguments);
    final String name = asString(arguments['name']) ?? '?';
    return switch (asString(arguments['scope'])) {
      'npc' => '$name of the NPC',
      'global' => '$name for everyone',
      'relationship' => '$name with this NPC',
      _ => name,
    };
  }

  /// A registry id in a step's text; [plain] drops `minecraft:` and reads
  /// underscores as spaces.
  String _item(Object? id) => plain && id is String ? itemName(id).toLowerCase() : '$id';

  /// The [kind] document [id] by name in Simple, by id otherwise.
  String _document(DocumentKind kind, Object? id) => plain && id is String ? names(kind, id) : '${id ?? '?'}';

  /// A key such as a slot name in [plain] words: `main_hand` reads "main hand".
  String _word(Object? key) => plain ? '$key'.replaceAll('_', ' ') : '$key';

  /// A value in a step's text; [plain] shows text without codes or quotes.
  String _value(Object? value) => plain && value is String ? plainName(value) ?? displayValue(value) : displayValue(value);

  /// What runs after a check.
  String _branch(bool result) => plain ? 'the ${result ? 'Yes' : 'No'} steps run' : '${result ? 'then' : 'else'} runs';

  Object? _read(String variable) => _execution.containsKey(variable) ? _execution[variable] : variables[variable];

  void _write(String variable, Object? value) {
    if (_execution.containsKey(variable)) {
      _execution[variable] = value;
    } else {
      variables[variable] = value;
    }
  }

  String? _typeOf(String? variable) => asString(asMap(_declared[variable])['type']);

  BehaviorEvalScope _scope() => BehaviorEvalScope(
        read: _read,
        flag: (String scope, String name) => flags['$name ($scope)'],
        quest: (String scope, String name) => quests['$name ($scope)'] ?? 0,
        random: _random,
        context: _context,
        server: _server,
      );

  String _factNote(BehaviorEvalScope scope) {
    final List<String> notes = <String>[
      for (final MapEntry<String, Object?> fact in scope.factsRead.entries) '${fact.key} = ${displayValue(fact.value)}',
      for (final String key in scope.contextMissing) 'context.$key = ""',
    ];
    return notes.isEmpty ? '' : ' Uses sample ${notes.join(', ')}.';
  }

  /// The value of an operand for a target of [type] (null: plain JSON).
  (Object?, String) _operand(Object? value, String? type, String path, int depth) {
    final BehaviorEvalScope scope = _scope();
    Object? result;
    try {
      switch (operandMode(value, type: type)) {
        case OperandMode.literal:
          return (value, '');
        case OperandMode.variable:
          final String name = operandText(value);
          result = _read(name);
          if (result == null) _fail(path, depth, 'Reads $name, which is not declared.');
          return (result, '');
        case OperandMode.expression:
          result = evaluateBehaviorExpression(operandText(value), scope);
        case OperandMode.template:
          result = renderBehaviorTemplate(operandText(value), scope);
      }
    } on BehaviorEvalFailure catch (failure) {
      _fail(path, depth, 'The expression failed: ${failure.message}. The server fails the run here.');
    }
    final (Object? converted, String? problem) = convertForType(result, type);
    if (problem != null) _fail(path, depth, 'The result does not fit: $problem. The server fails the run here.');
    return (converted, _factNote(scope));
  }

  /// Arguments with their placeholders filled from bindings, expressions
  /// and templates.
  JsonMap _arguments(JsonMap node, String path, int depth) {
    Object? arguments = deepCopy(asMap(node['arguments']));
    final BehaviorEvalScope scope = _scope();
    for (final String field in slotFields) {
      for (final MapEntry<String, Object?> entry in asMap(node[field]).entries) {
        final List<JsonStep> steps = <JsonStep>[
          for (final String raw in entry.key.split('/').skip(1)) int.tryParse(raw) ?? raw.replaceAll('~1', '/').replaceAll('~0', '~'),
        ];
        Object? value;
        try {
          value = switch (field) {
            'bindings' => _read(asString(asMap(entry.value)['variable']) ?? ''),
            'expressions' => naturalValue(evaluateBehaviorExpression(asString(entry.value) ?? '', scope)),
            _ => renderBehaviorTemplate(asString(entry.value) ?? '', scope),
          };
        } on BehaviorEvalFailure catch (failure) {
          _fail(path, depth, 'Filling ${entry.key} failed: ${failure.message}. The server fails the run here.');
        }
        if (steps.isNotEmpty) arguments = writeIn(arguments, steps, value);
      }
    }
    return asMap(arguments);
  }

  void _node(Object? raw, String path, int depth, List<String> calls) {
    if (_full) throw const _Halt();
    final JsonMap node = asMap(raw);
    switch (asString(node['type'])) {
      case 'sequence':
        final JsonList steps = asList(node['steps']);
        for (int index = 0; index < steps.length; index++) {
          _node(steps[index], indexPath(childPath(path, 'steps'), index), depth, calls);
        }
      case 'action':
        _action(node, path, depth);
      case 'condition':
        final bool result = _condition(node, path, depth);
        final Object? branch = result ? node['then'] : node['else'];
        if (branch != null) _node(branch, childPath(path, result ? 'then' : 'else'), depth + 1, calls);
      case 'wait':
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Waits ${_ticks(node['ticks'])}.'));
      case 'repeat':
        final (Object? counted, String note) = _operand(node['count'], 'integer', childPath(path, 'count'), depth);
        final int count = asInt(counted) ?? 0;
        final String? index = asString(node['index']);
        _loop(node, path, depth, calls, note: note, passes: count, describe: (int round) => 'Repeat ${round + 1} of $count.', before: (int round) {
          if (index != null) _write(index, round);
        });
      case 'foreach':
        final (Object? list, String note) = _operand(node['list'], null, childPath(path, 'list'), depth);
        final String item = asString(node['as']) ?? '';
        final String? index = asString(node['index']), key = asString(node['key']);
        if (list is List<Object?>) {
          _loop(node, path, depth, calls, note: note, passes: list.length, describe: (int round) => 'Item ${round + 1} of ${list.length}: $item is ${_value(list[round])}.', before: (int round) {
            _write(item, list[round]);
            if (index != null) _write(index, round);
          });
        } else if (list is Map<String, Object?>) {
          final List<String> keys = list.keys.toList();
          _loop(node, path, depth, calls, note: note, passes: keys.length, describe: (int round) => 'Entry ${round + 1} of ${keys.length}: ${keys[round]}.', before: (int round) {
            _write(item, list[keys[round]]);
            if (key != null) _write(key, keys[round]);
            if (index != null) _write(index, round);
          });
        } else {
          _fail(path, depth, 'foreach needs a list or map, and the value is ${displayValue(list)}. The server fails the run here.');
        }
      case 'while':
        final String expression = asString(node['expression']) ?? 'false';
        _loop(node, path, depth, calls, passes: null, describe: (int round) => 'Pass ${round + 1}: $expression is true.', test: () {
          final BehaviorEvalScope scope = _scope();
          try {
            final Object? result = evaluateBehaviorExpression(expression, scope);
            if (result is! bool) _fail(childPath(path, 'expression'), depth, '$expression gave ${displayValue(result)}, not true or false. The server fails the run here.');
            return result;
          } on BehaviorEvalFailure catch (failure) {
            _fail(childPath(path, 'expression'), depth, 'The expression failed: ${failure.message}. The server fails the run here.');
          }
        });
      case 'break':
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Leaves the loop.'));
        throw const _Break();
      case 'continue':
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Starts the next pass.'));
        throw const _Continue();
      case 'parallel':
        final JsonList branches = asList(node['branches']);
        for (int index = 0; index < branches.length && !_full; index++) {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Branch ${index + 1} of ${branches.length}, at the same time as the others.'));
          _node(branches[index], indexPath(childPath(path, 'branches'), index), depth + 1, calls);
        }
      case 'call':
        _call(node, path, depth, calls);
      case 'return':
        if (!node.containsKey('value')) {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Returns.'));
          throw const _Return(null);
        }
        final (Object? value, String note) = _operand(node['value'], null, childPath(path, 'value'), depth);
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Returns ${_value(value)}.$note'));
        throw _Return(value);
      case 'set':
        final String variable = asString(node['variable']) ?? '?';
        if (!_declared.containsKey(variable)) _fail(path, depth, 'Sets $variable, which is not declared.');
        final (Object? value, String note) = _operand(node['value'], _typeOf(variable), childPath(path, 'value'), depth);
        _write(variable, value);
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Sets $variable to ${_value(value)}.$note'));
      default:
        _add(DryRunStep(path: path, outcome: DryRunOutcome.problem, depth: depth, text: 'Unknown node type ${node['type']}.'));
    }
  }

  /// Runs a loop body [passes] times, or while [test] holds, with
  /// `break` and `continue`, and stops at the node's `maximum` the way the
  /// server does.
  void _loop(
    JsonMap node,
    String path,
    int depth,
    List<String> calls, {
    required int? passes,
    required String Function(int round) describe,
    String note = '',
    void Function(int round)? before,
    bool Function()? test,
  }) {
    final int maximum = asInt(node['maximum']) ?? 128;
    if (note.isNotEmpty) _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: note.trim()));
    int round = 0;
    while (passes == null ? test!() : round < passes) {
      if (round >= maximum) _fail(path, depth, 'The loop would pass more than $maximum times; the server fails the run here.');
      if (round >= dryRunLoopLimit) {
        _add(DryRunStep(path: path, outcome: DryRunOutcome.problem, depth: depth, text: 'Stopped after $dryRunLoopLimit passes of this loop.'));
        throw const _Halt();
      }
      before?.call(round);
      _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: describe(round)));
      try {
        _node(node['body'], childPath(path, 'body'), depth + 1, calls);
      } on _Break {
        return;
      } on _Continue {
        // Next pass.
      }
      round++;
      if (_full) throw const _Halt();
    }
    if (round == 0) _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: passes == null ? 'The loop does not run: its expression is false.' : 'The loop runs 0 times.'));
  }

  void _call(JsonMap node, String path, int depth, List<String> calls) {
    final String routine = asString(node['routine']) ?? '';
    if (calls.contains(routine) || calls.length >= 16) {
      _fail(path, depth, 'Calls $routine again inside itself; the dry run stops here.');
    } else if (!_routines.containsKey(routine)) {
      _fail(path, depth, 'Calls $routine, which does not exist.');
    }
    final List<String> assigned = <String>[];
    for (final MapEntry<String, Object?> argument in asMap(node['arguments']).entries) {
      final (Object? value, String _) = _operand(argument.value, _typeOf(argument.key), childPath(childPath(path, 'arguments'), argument.key), depth);
      _write(argument.key, value);
      assigned.add('${argument.key} = ${_value(value)}');
    }
    _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Calls routine $routine${assigned.isEmpty ? '' : ' with ${assigned.join(', ')}'}.'));
    Object? result;
    try {
      _node(_routines[routine], childPath(r'$.routines', routine), depth + 1, <String>[...calls, routine]);
    } on _Return catch (returned) {
      result = returned.value;
    }
    final String? into = asString(node['result']);
    if (into != null) {
      final (Object? converted, String? problem) = convertForType(result, _typeOf(into));
      if (problem != null) _fail(path, depth, '$routine returned ${displayValue(result)}, which $into cannot hold: $problem.');
      _write(into, converted);
      _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Stores the result in $into: ${_value(converted)}.'));
    }
  }

  bool _condition(JsonMap node, String path, int depth) {
    final Object? expression = node['expression'];
    if (expression is String) {
      final BehaviorEvalScope scope = _scope();
      Object? result;
      try {
        result = evaluateBehaviorExpression(expression, scope);
      } on BehaviorEvalFailure catch (failure) {
        _fail(childPath(path, 'expression'), depth, 'The expression failed: ${failure.message}. The server fails the run here.');
      }
      if (result is! bool) _fail(childPath(path, 'expression'), depth, '$expression gave ${displayValue(result)}, not true or false. The server fails the run here.');
      if (scope.factsRead.isEmpty) {
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Checks $expression: ${plain ? (result ? 'yes' : 'no') : result}, so ${_branch(result)}.'));
        return result;
      }
      final bool assumed = assumptions[path] ?? result;
      _add(DryRunStep(path: path, outcome: DryRunOutcome.assumed, depth: depth, assumption: assumed,
          text: 'Checks $expression, which reads ${scope.factsRead.keys.join(', ')} that only a server knows. Assumes ${plain ? (assumed ? 'yes' : 'no') : assumed}; ${_branch(assumed)}.'));
      return assumed;
    }
    final String capability = asString(node['capability']) ?? '?';
    final JsonMap arguments = _arguments(node, path, depth);
    switch (capability) {
      case 'animus:flag-state':
        final Object? expected = arguments.containsKey('value') ? arguments['value'] : true;
        final Object? actual = flags[_key(arguments)];
        // An unset flag reads as false, as on the server.
        final bool result = _equal(actual ?? false, expected);
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth,
            text: 'Checks flag ${_named(arguments)} is ${_value(expected)}: it is ${actual == null ? (plain ? 'not set' : 'unset') : _value(actual)}, so ${_branch(result)}.'));
        return result;
      case 'animus:quest-state':
        final String key = _key(arguments);
        final int stage = quests[key] ?? 0;
        final int? equals = asInt(arguments['equals']), atLeast = asInt(arguments['atLeast']), atMost = asInt(arguments['atMost']);
        final bool result = (equals == null || stage == equals) && (atLeast == null || stage >= atLeast) && (atMost == null || stage <= atMost);
        final String test = <String>[
          if (equals != null) '= $equals',
          if (atLeast != null) '>= $atLeast',
          if (atMost != null) '<= $atMost',
        ].join(' and ');
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth,
            text: 'Checks quest ${_named(arguments)} $test: the stage is $stage, so ${_branch(result)}.'));
        return result;
      default:
        final bool assumed = assumptions[path] ?? true;
        final String about = assumedConditions[capability] ?? '${capabilityWords(capability).toLowerCase()} holds';
        final String detail = capability == 'animus:has-permission' ? ' (${arguments['permission']})' : '';
        _add(DryRunStep(path: path, outcome: DryRunOutcome.assumed, depth: depth, assumption: assumed,
            text: plain
                ? 'Assumes $about$detail: ${assumed ? 'yes' : 'no'}; only a server can tell. ${_branch(assumed)[0].toUpperCase()}${_branch(assumed).substring(1)}.'
                : 'Assumes $about$detail is $assumed; only a server can tell. ${assumed ? 'then' : 'else'} runs.'));
        return assumed;
    }
  }

  static bool _equal(Object? left, Object? right) => displayValue(naturalValue(expressionValue(left))) == displayValue(naturalValue(expressionValue(right)));

  void _action(JsonMap node, String path, int depth) {
    final String capability = asString(node['capability']) ?? '?';
    final JsonMap arguments = _arguments(node, path, depth);
    DryRunStep step(String text, {DryRunOutcome outcome = DryRunOutcome.previewed}) => DryRunStep(path: path, outcome: outcome, depth: depth, text: text);
    String text(String key) => (asString(arguments[key]) ?? '').replaceAll('{player_name}', 'Player');
    switch (capability) {
      case 'animus:say':
        final String audience = asString(arguments['audience']) == 'nearby' ? 'everyone within ${_number(arguments['radius']) ?? '16'} blocks' : 'the player';
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, speech: text('text'), text: 'Says to $audience:'));
      case 'animus:message':
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, speech: text('text'), text: 'Messages ${asString(arguments['to']) == 'all' ? 'every player' : 'the player'}:'));
      case 'animus:actionbar':
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, speech: text('text'), text: 'Shows on the action bar:'));
      case 'animus:title':
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, speech: <String>[text('title'), text('subtitle')].where((String part) => part.isNotEmpty).join(' / '), text: 'Shows a title:'));
      case 'animus:choices':
        final String dialogue = asString(arguments['id']) ?? '?';
        _add(DryRunStep(
          path: path,
          outcome: DryRunOutcome.previewed,
          depth: depth,
          text: 'Asks the player ($dialogue):',
          prompt: text('prompt'),
          dialogue: dialogue,
          options: <DryRunOption>[
            for (final Object? raw in asList(arguments['options']))
              if (asString(asMap(raw)['id']) != null) DryRunOption(asString(asMap(raw)['id'])!, asString(asMap(raw)['text']) ?? asString(asMap(raw)['id'])!),
          ],
        ));
      case 'animus:navigate':
        final String? route = asString(arguments['route']);
        final bool wait = arguments['wait'] == true;
        final double? x = asDouble(arguments['x']), y = asDouble(arguments['y']), z = asDouble(arguments['z']);
        final String mode = asString(arguments['mode']) ?? 'walk';
        final String where = route != null ? 'follows route ${_document(DocumentKind.route, route)}' : '${mode}s to ${_number(x) ?? '?'}, ${_number(y) ?? '?'}, ${_number(z) ?? '?'}';
        _add(DryRunStep(
          path: path,
          outcome: DryRunOutcome.previewed,
          depth: depth,
          target: route == null && x != null && y != null && z != null ? (x, y, z) : null,
          text: '${where[0].toUpperCase()}${where.substring(1)}${wait ? ' and waits to arrive' : ''}. The server finds the path; blocked ground fails the run there.',
        ));
      case 'animus:stop-navigation':
        _add(step('Stops walking.'));
      case 'animus:look':
        _add(step(arguments['target'] == 'player'
            ? 'Turns to look at the player.'
            : 'Turns to look at ${_number(arguments['x']) ?? '?'}, ${_number(arguments['y']) ?? '?'}, ${_number(arguments['z']) ?? '?'}.'));
      case 'animus:play-sound':
        _add(step('Plays sound ${plain ? soundName('${arguments['sound']}').toLowerCase() : arguments['sound']}.'));
      case 'animus:spawn-particle':
        _add(step('Shows ${_number(arguments['count']) ?? '1'} ${plain && arguments['particle'] is String ? particleName(arguments['particle']! as String).toLowerCase() : arguments['particle']} particles.'));
      case 'animus:play-animation':
        final String animation = asString(arguments['animation']) ?? '?';
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, animation: animation,
            text: 'Plays animation ${_document(DocumentKind.animation, animation)} on layer ${_number(arguments['layer']) ?? '0'}.'));
      case 'animus:stop-animation':
        _add(step('Stops its animation and returns to idle.'));
      case 'animus:set-equipment':
        final String? item = asString(arguments['item']);
        _add(step(item == null ? 'Clears its ${_word(arguments['slot'])} slot.' : 'Holds ${_item(item)} in its ${_word(arguments['slot'])} slot.'));
      case 'animus:set-appearance':
        _add(step(arguments['reset'] == true
            ? 'Returns to its defined name and skin.'
            : 'Changes its ${<String>[if (arguments['name'] != null) 'name to ${arguments['name']}', if (arguments['skin'] != null) 'skin'].join(' and ')}.'));
      case 'animus:pose':
        _add(step('Takes the ${arguments['pose']} pose.'));
      case 'animus:set-flag':
        if (arguments['clear'] != null) {
          flags.remove(_key(arguments));
          _add(step('Clears flag ${_named(arguments)}.'));
        } else {
          final Object? value = arguments.containsKey('value') ? arguments['value'] : true;
          flags[_key(arguments)] = value;
          _add(step('Sets flag ${_named(arguments)} to ${_value(value)}.'));
        }
      case 'animus:set-quest-state':
        final String key = _key(arguments);
        final int value = asInt(arguments['value']) ?? 0;
        quests[key] = value;
        _add(step(arguments['random'] != null
            ? 'Sets quest ${_named(arguments)} to a random stage below ${arguments['random']}; the dry run uses $value.'
            : 'Sets quest ${_named(arguments)} to $value.'));
      case 'animus:advance-quest-state':
        final String key = _key(arguments);
        final int by = asInt(arguments['by']) ?? 1;
        final int? maximum = asInt(arguments['maximum']);
        int next = (quests[key] ?? 0) + by;
        if (maximum != null && next > maximum) next = maximum;
        quests[key] = next;
        _add(step('Advances quest ${_named(arguments)} to $next.'));
      case 'animus:open-gui':
        _add(step('Opens the ${asString(arguments['kind']) ?? 'inventory'} GUI ${_document(DocumentKind.inventory, arguments['gui'])} for the player. Its buttons run on the server.'));
      case 'animus:open-shop':
        _add(step('Would open shop ${_document(DocumentKind.shop, arguments['shop'])}. Buying, selling and trades use the server\'s economy and inventories; not run.',
            outcome: DryRunOutcome.serverOnly));
      default:
        final String? known = serverOnlyCapabilities[capability];
        final String detail = switch (capability) {
          'animus:command' => ': /${arguments['command']}',
          'animus:combat-control' => ': ${arguments['operation']}${arguments['target'] == null ? '' : ' ${arguments['target']}'}',
          'animus:build' => ': ${arguments['operation']}',
          'animus:give-item' || 'animus:take-item' => ': ${arguments['amount'] ?? 1} ${_item(arguments['item'])}',
          _ => '',
        };
        _add(step(known != null ? '$known$detail. Server only; not run.' : '${nodeSummary(node, names: names)}. A module capability; server only, not run.',
            outcome: DryRunOutcome.serverOnly));
    }
  }

  Map<String, Object?> stateLines() => <String, Object?>{
        for (final MapEntry<String, Object?> flag in flags.entries) 'flag ${flag.key}': flag.value,
        for (final MapEntry<String, int> quest in quests.entries) 'quest ${quest.key}': quest.value,
        for (final MapEntry<String, Object?> variable in variables.entries) 'variable ${variable.key}': variable.value,
      };
}
