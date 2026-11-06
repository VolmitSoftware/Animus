/// Reads a behavior document as a [Conversation] and writes one back.
///
/// A behavior fits when it is an NPC behavior whose routines hold only
/// lines (`wait` then `animus:say`, with an optional sound and animation
/// right after), `animus:choices`, conditions, other actions, a `call` as a
/// list's last step and a bare `return`; whose triggers are at most one
/// start (`interaction` or `proximity` enter) plus one `dialogue_choice` per
/// asked option. Anything else is a [ConversationMisfit] naming the first
/// field that does not fit.
library;

import '../gui/gui_json.dart';
import 'conversation_model.dart';

sealed class ConversationRead {
  const ConversationRead();
}

class ConversationFits extends ConversationRead {
  const ConversationFits(this.conversation);

  final Conversation conversation;
}

class ConversationMisfit extends ConversationRead {
  const ConversationMisfit(this.path, this.reason);

  /// The first field that does not fit, as `$.routines.main.steps[2]`.
  final String path;
  final String reason;
}

/// The expression a sneak start checks.
const String sneakingExpression = 'context.sneaking';

/// Placeholders the editor offers, and the template holes they become where
/// the server does not replace them itself.
const String playerNamePlaceholder = '{player_name}';
const String npcNamePlaceholder = '{npc.name}';

final RegExp _npcNameHole = RegExp(r'\{\{\s*npc\.name\s*\}\}');
final RegExp _playerNameHole = RegExp(r'\{\{\s*player\.name\s*\}\}');

String _fromTemplate(String template) => template.replaceAll(_npcNameHole, npcNamePlaceholder).replaceAll(_playerNameHole, playerNamePlaceholder);

/// Whether [text] needs a template: it names the NPC, or [player] is set
/// (the prompt) and it names the player, or it already has holes.
bool _needsTemplate(String text, {required bool player}) =>
    text.contains(npcNamePlaceholder) || text.contains('{{') || (player && text.contains(playerNamePlaceholder));

String _toTemplate(String text, {required bool player}) {
  final String named = text.replaceAll(npcNamePlaceholder, '{{ npc.name }}');
  return player ? named.replaceAll(playerNamePlaceholder, '{{ player.name }}') : named;
}

class _Misfit implements Exception {
  const _Misfit(this.path, this.reason);

  final String path;
  final String reason;
}

/// Reads [behavior] as a conversation.
ConversationRead readConversation(JsonMap behavior) {
  try {
    return ConversationFits(_Reader(behavior).read());
  } on _Misfit catch (misfit) {
    return ConversationMisfit(misfit.path, misfit.reason);
  }
}

/// [behavior] as a conversation, or null when it does not fit.
Conversation? conversationOf(Object? behavior) => switch (readConversation(asMap(behavior))) {
      ConversationFits(:final Conversation conversation) => conversation,
      ConversationMisfit() => null,
    };

class _Reader {
  _Reader(this.behavior);

  final JsonMap behavior;

  Conversation read() {
    if (behavior['server'] == true) throw const _Misfit(r'$.server', 'A server behavior has no NPC to talk.');
    final JsonMap routines = asMap(behavior['routines']);
    final JsonList triggers = asList(behavior['triggers']);
    String entry = asString(behavior['entry']) ?? (routines.isEmpty ? 'main' : routines.keys.first);
    ConversationStart start = ConversationStart(ConversationStartKind.manual);
    final List<(int, JsonMap)> answers = <(int, JsonMap)>[];
    for (int index = 0; index < triggers.length; index++) {
      final JsonMap trigger = asMap(triggers[index]);
      final String path = '\$.triggers[$index]';
      switch (trigger['event']) {
        case 'dialogue_choice':
          if (trigger['choice'] is! String) throw _Misfit('$path.choice', 'Answers any option of its prompt.');
          answers.add((index, trigger));
        case 'interaction' || 'proximity':
          if (start.kind != ConversationStartKind.manual) throw _Misfit(path, 'A second start.');
          start = _start(trigger, path, routines);
          final String? routine = asString(trigger['routine']);
          if (start.gate != null) {
            entry = asString(asMap(asMap(routines[start.gate])['then'])['routine'])!;
          } else if (routine != null) {
            entry = routine;
          }
        default:
          throw _Misfit('$path.event', 'Starts on ${trigger['event']}.');
      }
    }
    final Map<String, StepList> blocks = <String, StepList>{
      for (final MapEntry<String, Object?> routine in routines.entries)
        if (routine.key != start.gate) routine.key: _list(routine.value, childPath(r'$.routines', routine.key)),
    };
    if (!blocks.containsKey(entry)) throw _Misfit(r'$.entry', 'The entry routine $entry is missing.');
    final Conversation conversation = Conversation(entry: entry, start: start, blocks: blocks);
    _link(conversation, answers);
    return conversation;
  }

  ConversationStart _start(JsonMap trigger, String path, JsonMap routines) {
    if (trigger['event'] == 'proximity') {
      if (trigger['phase'] != 'enter') throw _Misfit('$path.phase', 'Starts on leaving.');
      return ConversationStart(ConversationStartKind.proximity, radius: trigger['radius'] is num ? trigger['radius']! as num : 6, trigger: trigger);
    }
    final String action = asString(trigger['action']) ?? 'interact';
    if (action != 'interact') throw _Misfit('$path.action', 'Starts on $action.');
    final String? routine = asString(trigger['routine']);
    final JsonMap gate = asMap(routines[routine]);
    final JsonMap call = asMap(gate['then']);
    final bool gated = gate['type'] == 'condition' &&
        gate['expression'] == sneakingExpression &&
        gate['else'] == null &&
        gate.keys.every((String key) => const <String>{'type', 'expression', 'then'}.contains(key)) &&
        call['type'] == 'call' &&
        call['routine'] is String &&
        call.keys.every((String key) => key == 'type' || key == 'routine');
    if (gated) return ConversationStart(ConversationStartKind.sneakClick, trigger: trigger, gate: routine);
    if (trigger.containsKey('consume')) throw _Misfit('$path.consume', 'Claims clicks.');
    return ConversationStart(ConversationStartKind.click, trigger: trigger);
  }

  /// Joins each answer trigger to its option.
  void _link(Conversation conversation, List<(int, JsonMap)> answers) {
    final Map<String, ConversationChoices> asked = <String, ConversationChoices>{};
    for (final MapEntry<String, StepList> block in conversation.blocks.entries) {
      for (final ConversationChoices choices in choicesIn(block.value)) {
        if (asked.containsKey(choices.dialogue)) throw _Misfit(childPath(r'$.routines', block.key), 'Two prompts share the id ${choices.dialogue}.');
        asked[choices.dialogue] = choices;
      }
    }
    for (final (int index, JsonMap trigger) in answers) {
      final String path = '\$.triggers[$index]';
      final ConversationChoices? choices = asked[trigger['dialogue']];
      final ConversationOption? option = choices?.options.where((ConversationOption option) => option.id == trigger['choice']).firstOrNull;
      if (option == null) throw _Misfit(path, 'Answers ${trigger['dialogue']}/${trigger['choice']}, which no line asks.');
      if (option.trigger != null) throw _Misfit(path, 'A second answer to ${trigger['dialogue']}/${option.id}.');
      final String routine = asString(trigger['routine']) ?? conversation.entry;
      if (!conversation.blocks.containsKey(routine)) throw _Misfit('$path.routine', 'Runs $routine, which is not a routine here.');
      option
        ..next = routine
        ..trigger = asString(trigger['id'])
        ..triggerRaw = trigger;
    }
  }

  StepList _list(Object? value, String path) {
    final JsonMap node = asMap(value);
    if (node['type'] != 'sequence') return StepList(_steps(<(Object?, String)>[(node, path)]), sequence: false);
    if (node.keys.any((String key) => key != 'type' && key != 'steps')) throw _Misfit(path, 'Extra fields on a sequence.');
    return StepList(_steps(_flatten(node, path)));
  }

  /// A sequence's children, nested sequences opened in place.
  List<(Object?, String)> _flatten(JsonMap sequence, String path) {
    final JsonList children = asList(sequence['steps']);
    return <(Object?, String)>[
      for (int index = 0; index < children.length; index++)
        if (asMap(children[index])['type'] == 'sequence' && asMap(children[index]).keys.every((String key) => key == 'type' || key == 'steps'))
          ..._flatten(asMap(children[index]), '$path.steps[$index]')
        else
          (children[index], '$path.steps[$index]'),
    ];
  }

  List<ConversationStep> _steps(List<(Object?, String)> nodes) {
    final List<ConversationStep> steps = <ConversationStep>[];
    for (int index = 0; index < nodes.length; index++) {
      final (Object? value, String path) = nodes[index];
      final JsonMap node = asMap(value);
      final bool last = index == nodes.length - 1;
      switch (node['type']) {
        case 'wait':
          final JsonMap say = index + 1 < nodes.length ? asMap(nodes[index + 1].$1) : const <String, Object?>{};
          if (!_isAction(say, sayCapability) || node.keys.any((String key) => key != 'type' && key != 'ticks') || asInt(node['ticks']) == null) {
            throw _Misfit(path, 'A wait that does not come before a line.');
          }
          final ConversationLine line = _line(say, nodes[index + 1].$2)..delayTicks = asInt(node['ticks']);
          index += 1 + _extras(line, nodes, index + 2);
          steps.add(line);
        case 'action':
          final String capability = asString(node['capability']) ?? '';
          if (capability == sayCapability) {
            final ConversationLine line = _line(node, path);
            index += _extras(line, nodes, index + 1);
            steps.add(line);
          } else if (capability == choicesCapability) {
            steps.add(_choices(node, path));
          } else {
            _plain(node, path, const <String>{'type', 'capability', 'arguments'});
            steps.add(ConversationAction(capability, arguments: asMap(deepCopy(node['arguments'])), raw: node));
          }
        case 'condition':
          _plain(node, path, const <String>{'type', 'capability', 'arguments', 'expression', 'then', 'else'});
          steps.add(ConversationBranch(
            capability: asString(node['capability']),
            arguments: asMap(deepCopy(node['arguments'])),
            expression: asString(node['expression']),
            then: _list(node['then'], '$path.then'),
            otherwise: node['else'] == null ? null : _list(node['else'], '$path.else'),
            raw: node,
          ));
        case 'call':
          if (!last) throw _Misfit(path, 'A call before other steps.');
          if (node.keys.any((String key) => key != 'type' && key != 'routine')) throw _Misfit(path, 'A call with arguments or a result.');
          steps.add(ConversationGoto(asString(node['routine']) ?? '', raw: node));
        case 'return':
          if (!last) throw _Misfit(path, 'A return before other steps.');
          if (node.containsKey('value')) throw _Misfit('$path.value', 'A return with a value.');
          steps.add(ConversationEnd(raw: node));
        default:
          throw _Misfit(path, 'Uses ${node['type'] ?? 'a node without a type'}.');
      }
    }
    return steps;
  }

  static bool _isAction(JsonMap node, String capability) => node['type'] == 'action' && node['capability'] == capability;

  /// Rejects bindings, expressions and other keys outside [allowed].
  static void _plain(JsonMap node, String path, Set<String> allowed) {
    for (final String key in node.keys) {
      if (!allowed.contains(key)) throw _Misfit(childPath(path, key), 'Uses $key.');
    }
  }

  ConversationLine _line(JsonMap say, String path) {
    _plain(say, path, const <String>{'type', 'capability', 'arguments', 'templates'});
    final JsonMap arguments = asMap(say['arguments']);
    final JsonMap templates = asMap(say['templates']);
    if (templates.keys.any((String key) => key != '/text')) throw _Misfit('$path.templates', 'Fills arguments other than the text.');
    final String? text = asString(arguments['text']) ?? (templates['/text'] is String ? _fromTemplate(templates['/text']! as String) : null);
    if (text == null) throw _Misfit('$path.arguments.text', 'A line without text.');
    return ConversationLine(text, raw: say);
  }

  /// Folds a plain sound and animation that follow a line into it; returns
  /// how many nodes it took.
  int _extras(ConversationLine line, List<(Object?, String)> nodes, int from) {
    int taken = 0;
    bool only(JsonMap node, String key) =>
        node.keys.every((String field) => field == 'type' || field == 'capability' || field == 'arguments') &&
        asMap(node['arguments']).length == 1 &&
        asMap(node['arguments'])[key] is String;
    if (from < nodes.length) {
      final JsonMap next = asMap(nodes[from].$1);
      if (_isAction(next, playSoundCapability) && only(next, 'sound')) {
        line.sound = asString(asMap(next['arguments'])['sound']);
        taken++;
      }
    }
    if (from + taken < nodes.length) {
      final JsonMap next = asMap(nodes[from + taken].$1);
      if (_isAction(next, playAnimationCapability) && only(next, 'animation')) {
        line.animation = asString(asMap(next['arguments'])['animation']);
        taken++;
      }
    }
    return taken;
  }

  ConversationChoices _choices(JsonMap node, String path) {
    _plain(node, path, const <String>{'type', 'capability', 'arguments', 'templates'});
    final JsonMap arguments = asMap(node['arguments']);
    final JsonMap templates = asMap(node['templates']);
    if (templates.keys.any((String key) => key != '/prompt')) throw _Misfit('$path.templates', 'Fills arguments other than the prompt.');
    final String prompt = asString(arguments['prompt']) ?? (templates['/prompt'] is String ? _fromTemplate(templates['/prompt']! as String) : '');
    final JsonList options = asList(arguments['options']);
    return ConversationChoices(
      asString(arguments['id']) ?? '',
      prompt,
      <ConversationOption>[
        for (final Object? raw in options)
          ConversationOption(
            asString(asMap(raw)['id']) ?? '',
            asString(asMap(raw)['text']) ?? '',
            color: asString(asMap(raw)['color']),
            hover: asString(asMap(raw)['hover']),
            style: asString(asMap(raw)['style']),
            raw: asMap(raw),
          ),
      ],
      raw: node,
    );
  }
}

/// [behavior] with its routines, entry and triggers rewritten from
/// [conversation]. Other keys, and the keys each step was read with, stay.
JsonMap writeConversation(Conversation conversation, JsonMap behavior) {
  final JsonMap routines = <String, Object?>{};
  final ConversationStart start = conversation.start;
  final String? gate = start.kind == ConversationStartKind.sneakClick ? (start.gate ?? _free('sneak-start', conversation.blocks.keys)) : null;
  if (gate != null) {
    routines[gate] = <String, Object?>{
      'type': 'condition',
      'expression': sneakingExpression,
      'then': <String, Object?>{'type': 'call', 'routine': conversation.entry},
    };
  }
  for (final MapEntry<String, StepList> block in conversation.blocks.entries) {
    routines[block.key] = _writeList(block.value);
  }
  final List<Object?> triggers = <Object?>[
    if (start.kind != ConversationStartKind.manual) _writeStart(start, gate, conversation.entry),
    for (final StepList block in conversation.blocks.values)
      for (final ConversationChoices choices in choicesIn(block))
        for (final ConversationOption option in choices.options)
          if (option.next != null)
            <String, Object?>{
              ...option.triggerRaw,
              'id': option.trigger ?? '${choices.dialogue}-${option.id}',
              'event': 'dialogue_choice',
              'dialogue': choices.dialogue,
              'choice': option.id,
              'routine': option.next,
            },
  ];
  final JsonMap out = <String, Object?>{};
  for (final MapEntry<String, Object?> entry in behavior.entries) {
    switch (entry.key) {
      case 'entry':
        out['entry'] = conversation.entry;
      case 'routines':
        out['routines'] = routines;
      case 'triggers':
        if (triggers.isNotEmpty) out['triggers'] = triggers;
      default:
        out[entry.key] = entry.value;
    }
  }
  out.putIfAbsent('entry', () => conversation.entry);
  out.putIfAbsent('routines', () => routines);
  if (triggers.isNotEmpty) out.putIfAbsent('triggers', () => triggers);
  return out;
}

String _free(String base, Iterable<String> taken) {
  final Set<String> used = taken.toSet();
  if (!used.contains(base)) return base;
  for (int index = 2;; index++) {
    if (!used.contains('$base-$index')) return '$base-$index';
  }
}

JsonMap _writeStart(ConversationStart start, String? gate, String entry) {
  final JsonMap raw = start.trigger;
  final bool proximity = start.kind == ConversationStartKind.proximity;
  final bool sameEvent = raw['event'] == (proximity ? 'proximity' : 'interaction');
  final JsonMap out = <String, Object?>{
    'id': asString(raw['id']) ?? 'start',
    'event': proximity ? 'proximity' : 'interaction',
    if (sameEvent)
      for (final MapEntry<String, Object?> entry in raw.entries)
        if (!const <String>{'id', 'event', 'routine', 'phase', 'radius', 'consume'}.contains(entry.key)) entry.key: entry.value,
    if (!sameEvent && raw['cooldownTicks'] != null) 'cooldownTicks': raw['cooldownTicks'],
  };
  if (gate != null) {
    out['routine'] = gate;
    out['consume'] = sneakingExpression;
  } else if (raw.containsKey('routine') && raw['routine'] != start.gate) {
    out['routine'] = entry;
  }
  if (proximity) {
    out['phase'] = 'enter';
    out['radius'] = start.radius;
  }
  return out;
}

Object? _writeList(StepList list) {
  final List<JsonMap> nodes = <JsonMap>[for (final ConversationStep step in list.steps) ..._writeStep(step)];
  if (nodes.length == 1 && !list.sequence) return nodes.single;
  return <String, Object?>{'type': 'sequence', 'steps': nodes};
}

JsonMap _arguments(JsonMap raw, Map<String, Object?> set) {
  final JsonMap out = <String, Object?>{...asMap(raw['arguments'])};
  for (final MapEntry<String, Object?> entry in set.entries) {
    if (entry.value == null) {
      out.remove(entry.key);
    } else {
      out[entry.key] = entry.value;
    }
  }
  return out;
}

/// A node from [raw] whose argument [key] holds [text], as a template when
/// a placeholder needs one.
JsonMap _textNode(JsonMap raw, String capability, String key, String text, Map<String, Object?> arguments, {required bool player}) {
  final bool template = _needsTemplate(text, player: player);
  final JsonMap templates = <String, Object?>{...asMap(raw['templates'])}..remove('/$key');
  if (template) templates['/$key'] = _toTemplate(text, player: player);
  final JsonMap args = _arguments(raw, arguments);
  args[key] = template ? null : text;
  return <String, Object?>{
    ...raw,
    'type': 'action',
    'capability': capability,
    'arguments': args,
    if (templates.isNotEmpty) 'templates': templates,
  }..removeWhere((String field, Object? _) => field == 'templates' && templates.isEmpty);
}

List<JsonMap> _writeStep(ConversationStep step) => switch (step) {
      ConversationLine() => <JsonMap>[
          if (step.delayTicks != null && step.delayTicks! > 0) <String, Object?>{'type': 'wait', 'ticks': step.delayTicks},
          _textNode(step.raw, sayCapability, 'text', step.text, const <String, Object?>{}, player: false),
          if (step.sound != null && step.sound!.isNotEmpty)
            <String, Object?>{'type': 'action', 'capability': playSoundCapability, 'arguments': <String, Object?>{'sound': step.sound}},
          if (step.animation != null && step.animation!.isNotEmpty)
            <String, Object?>{'type': 'action', 'capability': playAnimationCapability, 'arguments': <String, Object?>{'animation': step.animation}},
        ],
      ConversationChoices() => <JsonMap>[
          _textNode(step.raw, choicesCapability, 'prompt', step.prompt, <String, Object?>{
            'id': step.dialogue,
            'options': <Object?>[
              for (final ConversationOption option in step.options)
                <String, Object?>{
                  ...option.raw,
                  'id': option.id,
                  'text': option.text,
                  'color': option.color,
                  'hover': option.hover,
                  'style': option.style,
                }..removeWhere((String key, Object? value) => value == null),
            ],
          }, player: true),
        ],
      ConversationBranch() => <JsonMap>[
          <String, Object?>{
            for (final MapEntry<String, Object?> entry in step.raw.entries)
              if (!const <String>{'capability', 'arguments', 'expression', 'then', 'else'}.contains(entry.key)) entry.key: entry.value,
            'type': 'condition',
            if (step.expression != null) 'expression': step.expression else ...<String, Object?>{
              'capability': step.capability,
              if (step.arguments.isNotEmpty || step.raw.containsKey('arguments')) 'arguments': step.arguments,
            },
            'then': _writeList(step.then),
            if (step.otherwise.steps.isNotEmpty) 'else': _writeList(step.otherwise),
          },
        ],
      ConversationAction() => <JsonMap>[
          <String, Object?>{
            ...step.raw,
            'type': 'action',
            'capability': step.capability,
            if (step.arguments.isNotEmpty || step.raw.containsKey('arguments')) 'arguments': step.arguments,
          },
        ],
      ConversationGoto() => <JsonMap>[<String, Object?>{...step.raw, 'type': 'call', 'routine': step.target}],
      ConversationEnd() => <JsonMap>[<String, Object?>{...step.raw, 'type': 'return'}],
    };
