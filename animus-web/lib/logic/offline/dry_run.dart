/// A behavior dry run in the browser: walks a routine node by node the way
/// the interpreter would and records what the NPC would say, ask, walk to
/// and change, without a server. Flags, quest stages and variables live in
/// the run's own state. Conditions only a server can answer (permissions,
/// health, who is near) use an assumption the user can flip, and actions
/// that reach the server (commands, combat, building, shop trades and money)
/// are listed as not run. Answers to a prompt start the `dialogue_choice`
/// trigger that matches them, as a new run.
///
/// The run is a pure function of the behavior and the inputs (the start
/// trigger, each answer picked, each assumption), so changing an input
/// replays the whole session from the start.
library;

import 'dart:convert';

import '../gui/gui_json.dart';

enum DryRunOutcome {
  /// Shown in the dry run: speech, prompts, movement, waits, state changes.
  previewed,

  /// A condition decided by an assumption, not by a server.
  assumed,

  /// Needs the server; nothing happened.
  serverOnly,

  /// Stopped the run (an unknown routine, a loop limit).
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

  /// For `animus:say`: the line, with its colour codes.
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
  const DryRunStart(this.id, this.label, this.routine, {this.carriesPlayer = true});

  /// The trigger id, or null for the entry routine itself.
  final String? id;
  final String label;
  final String routine;
  final bool carriesPlayer;
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
};

/// Conditions a server answers; the dry run assumes them.
const Map<String, String> assumedConditions = <String, String>{
  'animus:has-permission': 'the player has a permission',
  'animus:npc-state': 'the NPC is spawned and its health is in range',
  'animus:player-in-range': 'a player is in range',
};

/// The most steps one session records, so a long repeat cannot hang the page.
const int dryRunStepLimit = 400;

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
    starts.add(DryRunStart(id, '$id: ${_eventLine(trigger)}', asString(trigger['routine']) ?? entry, carriesPlayer: !const <String>{'timer', 'spawn', 'despawn', 'waypoint', 'module', 'build_finished', 'build_paused', 'animation_finished'}.contains(event)));
  }
  starts.add(DryRunStart(null, 'Entry routine ($entry)', entry));
  return starts;
}

String _eventLine(JsonMap trigger) {
  final String event = asString(trigger['event']) ?? '?';
  return switch (event) {
    'interaction' => switch (asString(trigger['action'])) {
        'attack' => 'a player left-clicks the NPC',
        'any' => 'a player clicks the NPC',
        _ => 'a player right-clicks the NPC',
      },
    'proximity' => 'a player comes within ${_number(trigger['radius']) ?? '?'} blocks',
    'timer' => 'every ${_ticks(trigger['intervalTicks'])}',
    'spawn' => 'the NPC spawns',
    'despawn' => 'the NPC despawns',
    'damage' => 'the NPC is hurt',
    'death' => 'the NPC dies',
    'waypoint' => 'the NPC reaches a waypoint',
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

/// Runs [behavior] with [inputs] from the start. An answer that does not
/// match the open prompt is ignored.
DryRunSession dryRun(JsonMap behavior, DryRunInputs inputs) {
  final _Machine machine = _Machine(behavior, inputs.assumptions);
  final List<DryRunStart> starts = dryRunStarts(behavior);
  final List<DryRunRun> runs = <DryRunRun>[];
  DryRunStep? open;
  for (final DryRunEvent event in inputs.events) {
    switch (event) {
      case DryRunTrigger(:final String? id):
        final DryRunStart start = starts.firstWhere((DryRunStart candidate) => candidate.id == id, orElse: () => starts.last);
        runs.add(machine.run(start.label, start.routine));
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
          runs.add(machine.run('Answer "$optionText" (${asString(trigger['id']) ?? 'trigger'})', routine));
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

class _Machine {
  _Machine(this.behavior, this.assumptions) {
    for (final MapEntry<String, Object?> entry in asMap(behavior['variables']).entries) {
      final JsonMap declaration = asMap(entry.value);
      if ((asString(declaration['scope']) ?? 'execution') != 'execution') variables[entry.key] = declaration['initial'];
    }
  }

  final JsonMap behavior;
  final Map<String, bool> assumptions;
  final Map<String, bool> flags = <String, bool>{};
  final Map<String, int> quests = <String, int>{};
  final Map<String, Object?> variables = <String, Object?>{};
  int _total = 0;
  late List<DryRunStep> _steps;
  late Map<String, Object?> _execution;

  JsonMap get _routines => asMap(behavior['routines']);

  DryRunRun run(String title, String routine) {
    _steps = <DryRunStep>[];
    _execution = <String, Object?>{
      for (final MapEntry<String, Object?> entry in asMap(behavior['variables']).entries)
        if ((asString(asMap(entry.value)['scope']) ?? 'execution') == 'execution') entry.key: asMap(entry.value)['initial'],
    };
    if (!_routines.containsKey(routine)) {
      _add(DryRunStep(path: r'$.routines', outcome: DryRunOutcome.problem, text: 'There is no routine named $routine.'));
    } else {
      _node(_routines[routine], childPath(r'$.routines', routine), 0, <String>[routine]);
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

  String _key(JsonMap arguments) => '${asString(arguments['name']) ?? '?'} (${asString(arguments['scope']) ?? 'player'})';

  void _node(Object? raw, String path, int depth, List<String> calls) {
    if (_full) return;
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
        final int count = asInt(node['count']) ?? 0;
        for (int round = 0; round < count && !_full; round++) {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Repeat ${round + 1} of $count.'));
          _node(node['body'], childPath(path, 'body'), depth + 1, calls);
        }
      case 'parallel':
        final JsonList branches = asList(node['branches']);
        for (int index = 0; index < branches.length && !_full; index++) {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Branch ${index + 1} of ${branches.length}, at the same time as the others.'));
          _node(branches[index], indexPath(childPath(path, 'branches'), index), depth + 1, calls);
        }
      case 'call':
        final String routine = asString(node['routine']) ?? '';
        if (calls.contains(routine) || calls.length >= 16) {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.problem, depth: depth, text: 'Calls $routine again inside itself; the dry run stops here.'));
        } else if (!_routines.containsKey(routine)) {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.problem, depth: depth, text: 'Calls $routine, which does not exist.'));
        } else {
          _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Calls routine $routine.'));
          _node(_routines[routine], childPath(r'$.routines', routine), depth + 1, <String>[...calls, routine]);
        }
      case 'set':
        final String variable = asString(node['variable']) ?? '?';
        final Object? value = node['value'];
        final Object? resolved = value is Map<String, Object?> && value['variable'] is String ? _read(value['variable']! as String) : value;
        if (_execution.containsKey(variable)) {
          _execution[variable] = resolved;
        } else {
          variables[variable] = resolved;
        }
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, text: 'Sets $variable to ${jsonEncode(resolved)}.'));
      default:
        _add(DryRunStep(path: path, outcome: DryRunOutcome.problem, depth: depth, text: 'Unknown node type ${node['type']}.'));
    }
  }

  Object? _read(String variable) => _execution.containsKey(variable) ? _execution[variable] : variables[variable];

  bool _condition(JsonMap node, String path, int depth) {
    final String capability = asString(node['capability']) ?? '?';
    final JsonMap arguments = asMap(node['arguments']);
    switch (capability) {
      case 'animus:flag-state':
        final bool expected = arguments['value'] is bool ? arguments['value']! as bool : true;
        final bool actual = flags[_key(arguments)] ?? false;
        final bool result = actual == expected;
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth,
            text: 'Checks flag ${_key(arguments)} is $expected: it is $actual, so ${result ? 'then' : 'else'} runs.'));
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
            text: 'Checks quest $key $test: the stage is $stage, so ${result ? 'then' : 'else'} runs.'));
        return result;
      default:
        final bool assumed = assumptions[path] ?? true;
        final String about = assumedConditions[capability] ?? 'module condition $capability holds';
        final String detail = capability == 'animus:has-permission' ? ' (${arguments['permission']})' : '';
        _add(DryRunStep(path: path, outcome: DryRunOutcome.assumed, depth: depth, assumption: assumed,
            text: 'Assumes $about$detail is $assumed; only a server can tell. ${assumed ? 'then' : 'else'} runs.'));
        return assumed;
    }
  }

  void _action(JsonMap node, String path, int depth) {
    final String capability = asString(node['capability']) ?? '?';
    final JsonMap arguments = asMap(node['arguments']);
    DryRunStep step(String text, {DryRunOutcome outcome = DryRunOutcome.previewed}) => DryRunStep(path: path, outcome: outcome, depth: depth, text: text);
    switch (capability) {
      case 'animus:say':
        final String text = (asString(arguments['text']) ?? '').replaceAll('{player_name}', 'Player');
        final String audience = asString(arguments['audience']) == 'nearby' ? 'everyone within ${_number(arguments['radius']) ?? '16'} blocks' : 'the player';
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, speech: text, text: 'Says to $audience:'));
      case 'animus:choices':
        final String dialogue = asString(arguments['id']) ?? '?';
        _add(DryRunStep(
          path: path,
          outcome: DryRunOutcome.previewed,
          depth: depth,
          text: 'Asks the player ($dialogue):',
          prompt: (asString(arguments['prompt']) ?? '').replaceAll('{player_name}', 'Player'),
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
        final String where = route != null ? 'follows route $route' : '${mode}s to ${_number(x) ?? '?'}, ${_number(y) ?? '?'}, ${_number(z) ?? '?'}';
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
        _add(step('Plays sound ${arguments['sound']}.'));
      case 'animus:spawn-particle':
        _add(step('Shows ${_number(arguments['count']) ?? '1'} ${arguments['particle']} particles.'));
      case 'animus:play-animation':
        final String animation = asString(arguments['animation']) ?? '?';
        _add(DryRunStep(path: path, outcome: DryRunOutcome.previewed, depth: depth, animation: animation,
            text: 'Plays animation $animation on layer ${_number(arguments['layer']) ?? '0'}.'));
      case 'animus:stop-animation':
        _add(step('Stops its animation and returns to idle.'));
      case 'animus:set-equipment':
        final String? item = asString(arguments['item']);
        _add(step(item == null ? 'Clears its ${arguments['slot']} slot.' : 'Holds $item in its ${arguments['slot']} slot.'));
      case 'animus:set-appearance':
        _add(step(arguments['reset'] == true
            ? 'Returns to its defined name and skin.'
            : 'Changes its ${<String>[if (arguments['name'] != null) 'name to ${arguments['name']}', if (arguments['skin'] != null) 'skin'].join(' and ')}.'));
      case 'animus:set-flag':
        final bool value = arguments['value'] is bool ? arguments['value']! as bool : true;
        flags[_key(arguments)] = value;
        _add(step('Sets flag ${_key(arguments)} to $value.'));
      case 'animus:set-quest-state':
        final String key = _key(arguments);
        final int value = asInt(arguments['value']) ?? 0;
        quests[key] = value;
        _add(step(arguments['random'] != null
            ? 'Sets quest $key to a random stage below ${arguments['random']}; the dry run uses $value.'
            : 'Sets quest $key to $value.'));
      case 'animus:advance-quest-state':
        final String key = _key(arguments);
        final int by = asInt(arguments['by']) ?? 1;
        final int? maximum = asInt(arguments['maximum']);
        int next = (quests[key] ?? 0) + by;
        if (maximum != null && next > maximum) next = maximum;
        quests[key] = next;
        _add(step('Advances quest $key to $next.'));
      case 'animus:open-gui':
        _add(step('Opens the ${asString(arguments['kind']) ?? 'inventory'} GUI ${arguments['gui']} for the player. Its buttons run on the server.'));
      case 'animus:open-shop':
        _add(step('Would open shop ${arguments['shop']}. Buying, selling and trades use the server\'s economy and inventories; not run.',
            outcome: DryRunOutcome.serverOnly));
      default:
        final String? known = serverOnlyCapabilities[capability];
        final String detail = switch (capability) {
          'animus:command' => ': /${arguments['command']}',
          'animus:combat-control' => ': ${arguments['operation']}${arguments['target'] == null ? '' : ' ${arguments['target']}'}',
          'animus:build' => ': ${arguments['operation']}',
          _ => '',
        };
        _add(step(known != null ? '$known$detail. Server only; not run.' : 'Runs $capability, a module capability. Server only; not run.',
            outcome: DryRunOutcome.serverOnly));
    }
  }

  Map<String, Object?> stateLines() => <String, Object?>{
        for (final MapEntry<String, bool> flag in flags.entries) 'flag ${flag.key}': flag.value,
        for (final MapEntry<String, int> quest in quests.entries) 'quest ${quest.key}': quest.value,
        for (final MapEntry<String, Object?> variable in variables.entries) 'variable ${variable.key}': variable.value,
      };
}
