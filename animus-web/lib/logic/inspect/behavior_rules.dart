/// The behavior rules the server refuses that JSON Schema cannot express:
/// the compiler's (`BehaviorCompiler`, `TriggerCompiler`, `ArgumentTemplate`)
/// and the argument checks of the built-in capabilities, which the server
/// runs on every step whose arguments are written out in full. Each is an
/// error at the field the server names, so a behavior the editor calls
/// valid is one the server accepts. Expression types are checked as well:
/// a condition that gives a number compiles but fails every run.
library;

import '../../model/animus_document.dart';
import '../citizens/java_lang.dart' show javaIsIsoControl;
import '../gui/gui_json.dart';
import '../npc/npc_attributes.dart';
import 'behavior_language.dart';
import 'behavior_operands.dart' show operandKeys, slotFields;
import 'behavior_types.dart';
import 'behavior_words.dart' show argumentLabel, nodeLabel;
import 'completion_catalog.dart';
import 'inspect_schemas.dart';
import 'schema_fields.dart' show FieldKind, FieldSpec;

const int _maxDepth = 32, _maxNodes = 2048, _maxSlots = 32, _maxSegments = 16;

/// `maximumEntityRadius`: its default, and the most the setting allows.
const int defaultEntityRadius = 16, maximumEntityRadius = 32;

/// Triggers an NPC senses by looking around, bound by `maximumEntityRadius`;
/// speech is heard, not looked for, so `npc_speech` is not among them.
const Set<String> _sensed = <String>{'entity_proximity', 'item_dropped', 'leash_pass', 'npc_proximity'};
const Set<String> _reserved = <String>{'player', 'npc', 'server', 'context', 'true', 'false'};

/// What a variable type holds, in a sentence.
const Map<String, String> _typeWords = <String, String>{
  'boolean': 'true or false',
  'integer': 'a whole number',
  'decimal': 'a number',
  'string': 'text',
  'list': 'a list',
  'map': 'a map',
  'any': 'anything',
};

String _words(String? type) => _typeWords[type] ?? 'anything';

/// Every rule problem in behavior [json], read against [catalog]'s
/// expression language and trigger context keys.
List<Diagnostic> behaviorRuleDiagnostics(Object? json, {required CompletionCatalog catalog}) {
  if (json is! Map<String, Object?>) return const <Diagnostic>[];
  return (_Rules(json, catalog)..run()).out;
}

class _Rules {
  _Rules(this.document, this.catalog);

  final JsonMap document;
  final CompletionCatalog catalog;
  final List<Diagnostic> out = <Diagnostic>[];
  final Map<String, String> types = <String, String>{};
  final Set<String> scoped = <String>{};
  final List<(String, String)> calls = <(String, String)>[];
  final Map<String?, ExpressionScope> _scopes = <String?, ExpressionScope>{};
  int nodes = 0;
  bool inLoop = false, inParallel = false, budget = false;
  String? routine;

  JsonMap get routines => asMap(document['routines']);

  void error(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.error, path, message, code: 'behavior-rule'));

  void warn(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.warning, path, message, code: 'behavior-rule'));

  ExpressionScope get scope => _scopes.putIfAbsent(routine, () => behaviorScope(document, catalog, routine: routine));

  void run() {
    _variables();
    final String? entry = asString(document['entry']);
    if (entry != null && routines.isNotEmpty && !routines.containsKey(entry)) error(r'$.entry', _noRoutine(entry));
    for (final MapEntry<String, Object?> item in routines.entries) {
      routine = item.key;
      inLoop = false;
      inParallel = false;
      _node(item.value, '\$.routines.${item.key}', 0);
    }
    routine = null;
    for (final (String path, String name) in calls) {
      if (!routines.containsKey(name)) error('$path.routine', _noRoutine(name));
    }
    _triggers();
  }

  String _noRoutine(String name) => 'There is no routine named $name. Routines: ${routines.keys.join(', ')}.';

  void _variables() {
    for (final MapEntry<String, Object?> item in asMap(document['variables']).entries) {
      final String path = '\$.variables.${item.key}';
      final JsonMap declaration = asMap(item.value);
      if (_reserved.contains(item.key)) error(path, '${item.key} is a word expressions use; pick another name.');
      final String scope = asString(declaration['scope']) ?? 'execution';
      if (scope == 'execution' && declaration.containsKey('lifetime')) error('$path.lifetime', 'A variable for one run has no lifetime; remove it.');
      if (scope != 'execution' && !declaration.containsKey('lifetime')) error('$path.lifetime', 'Pick how long it is kept: until restart or saved.');
      types[item.key] = asString(declaration['type']) ?? 'any';
      if (scope != 'execution') scoped.add(item.key);
    }
  }

  void _triggers() {
    final List<Object?> triggers = asList(document['triggers']);
    final Set<String> ids = <String>{}, labels = <String>{};
    for (int index = 0; index < triggers.length; index++) {
      final String path = '\$.triggers[$index]';
      final JsonMap trigger = asMap(triggers[index]);
      final String? id = asString(trigger['id']);
      if (id != null && !ids.add(id)) error('$path.id', 'Another trigger already has the id $id.');
      final String? name = asString(trigger['routine']);
      if (name != null && routines.isNotEmpty && !routines.containsKey(name)) error('$path.routine', _noRoutine(name));
      if (trigger['event'] == 'command') {
        final String? command = asString(trigger['name']);
        if (command != null && !labels.add(command)) error('$path.name', 'Another command trigger of this behavior already uses /$command.');
        final List<Object?> aliases = asList(trigger['aliases']);
        for (int alias = 0; alias < aliases.length; alias++) {
          final String? word = asString(aliases[alias]);
          if (word != null && !labels.add(word)) error('$path.aliases[$alias]', 'Another command trigger of this behavior already uses /$word.');
        }
      }
      final Object? radius = trigger['radius'];
      if (_sensed.contains(trigger['event']) && radius is num && radius > defaultEntityRadius && radius <= maximumEntityRadius) {
        warn('$path.radius', 'Above $defaultEntityRadius blocks the server leaves this trigger off unless maximumEntityRadius in config.toml is raised; it goes up to $maximumEntityRadius.');
      }
      for (final String key in const <String>['consume', 'cancel']) {
        final String? source = asString(trigger[key]);
        if (source == null) continue;
        final TriggerSpec? spec = catalog.triggers[asString(trigger['event'])];
        final ExpressionScope own = ExpressionScope(
          language: catalog.expressions,
          contextKeys: spec?.context ?? const <String>[],
          contextTypes: spec?.contextTypes ?? const <String, String>{},
          contextKnown: spec != null,
          server: document['server'] == true,
        );
        final String? problem = checkBehaviorExpression(source, own);
        if (problem != null) {
          error('$path.$key', problem.startsWith('No variable named') ? '$problem Triggers cannot read variables.' : problem);
        } else {
          _notes('$path.$key', expressionNotes(source, own, expected: ValueType.boolean));
        }
      }
    }
  }

  void _notes(String path, List<ExpressionNote> notes) {
    for (final ExpressionNote note in notes) {
      out.add(Diagnostic(note.severity, path, note.message, code: 'behavior-rule'));
    }
  }

  void _node(Object? raw, String path, int depth) {
    if (raw is! Map<String, Object?>) return;
    if (depth > _maxDepth) {
      error(path, 'Steps are nested more than $_maxDepth deep.');
      return;
    }
    if (++nodes > _maxNodes) {
      if (!budget) error(path, 'A behavior holds at most $_maxNodes steps.');
      budget = true;
      return;
    }
    final String? type = asString(raw['type']);
    switch (type) {
      case 'sequence':
        final List<Object?> steps = asList(raw['steps']);
        for (int index = 0; index < steps.length; index++) {
          _node(steps[index], '$path.steps[$index]', depth + 1);
        }
      case 'action':
        _capability(raw, path, 'action');
        _declared(raw['result'], '$path.result');
        final String? kept = asString(raw['result']);
        final String? holds = kept == null ? null : types[kept];
        if (holds != null && holds != 'map' && holds != 'any') error('$path.result', '$kept holds ${_words(holds)}; the result is a map, so the run fails here.');
      case 'condition':
        if (raw.containsKey('expression')) {
          _expression(raw['expression'], '$path.expression', ValueType.boolean);
        } else {
          _capability(raw, path, 'condition');
        }
        _node(raw['then'], '$path.then', depth + 1);
        _node(raw['else'], '$path.else', depth + 1);
      case 'wait':
        if (raw['ticks'] is Map) _operand(raw['ticks'], 'integer', '$path.ticks');
      case 'repeat':
        if (raw['count'] is Map) _operand(raw['count'], 'integer', '$path.count');
        _loopVariable(raw['index'], '$path.index', const <String>['integer', 'decimal', 'any']);
        _body(raw['body'], '$path.body', depth);
      case 'foreach':
        if (raw.containsKey('list')) _operand(raw['list'], 'any', '$path.list');
        _loopVariable(raw['as'], '$path.as', const <String>[]);
        _loopVariable(raw['index'], '$path.index', const <String>['integer', 'decimal', 'any']);
        _loopVariable(raw['key'], '$path.key', const <String>['string', 'any']);
        _body(raw['body'], '$path.body', depth);
      case 'while':
        _expression(raw['expression'], '$path.expression', ValueType.boolean);
        _body(raw['body'], '$path.body', depth);
      case 'break' || 'continue':
        if (!inLoop) {
          error(path, inParallel
              ? '${nodeLabel(type)} cannot reach a loop from inside At the same time.'
              : '${nodeLabel(type)} works only inside a repeat, a for each or a repeat while of this routine.');
        }
      case 'return':
        if (inParallel) error(path, '${nodeLabel(type)} cannot be inside At the same time; it would end the other branches too.');
        if (raw.containsKey('value')) _operand(raw['value'], null, '$path.value');
      case 'parallel':
        final bool loop = inLoop, parallel = inParallel;
        inLoop = false;
        inParallel = true;
        final List<Object?> branches = asList(raw['branches']);
        for (int index = 0; index < branches.length; index++) {
          _node(branches[index], '$path.branches[$index]', depth + 1);
        }
        inLoop = loop;
        inParallel = parallel;
      case 'call':
        final String? name = asString(raw['routine']);
        if (name != null) calls.add((path, name));
        for (final MapEntry<String, Object?> argument in asMap(raw['arguments']).entries) {
          final String at = '$path.arguments.${argument.key}';
          if (!types.containsKey(argument.key)) {
            error(at, 'No variable named ${argument.key}. Declare it first.');
          } else {
            _operand(argument.value, types[argument.key], at);
          }
        }
        _declared(raw['result'], '$path.result');
        if (raw.containsKey('player')) _operand(raw['player'], 'string', '$path.player');
        if (raw.containsKey('npc')) _operand(raw['npc'], 'string', '$path.npc');
      case 'set':
        final String? name = asString(raw['variable']);
        if (name == null) return;
        if (!types.containsKey(name)) {
          error('$path.variable', 'No variable named $name. Declare it first.');
        } else if (raw.containsKey('value')) {
          _operand(raw['value'], types[name], '$path.value');
        }
    }
  }

  void _body(Object? body, String path, int depth) {
    final bool loop = inLoop;
    inLoop = true;
    _node(body, path, depth + 1);
    inLoop = loop;
  }

  void _declared(Object? name, String path) {
    if (name is String && !types.containsKey(name)) error(path, 'No variable named $name. Declare it first.');
  }

  void _loopVariable(Object? name, String path, List<String> allowed) {
    if (name is! String) return;
    if (!types.containsKey(name)) {
      error(path, 'No variable named $name. Declare it first.');
    } else if (scoped.contains(name)) {
      error(path, '$name is kept beyond one run; a loop needs a variable for one run.');
    } else if (allowed.isNotEmpty && !allowed.contains(types[name])) {
      error(path, '$name holds ${_words(types[name])}; this needs ${allowed.where((String type) => type != 'any').map(_words).join(' or ')}.');
    }
  }

  /// An operand for a target of [target] (null: any JSON, as return reads it).
  void _operand(Object? value, String? target, String field) {
    if (value is Map<String, Object?> && operandKeys.any(value.containsKey)) {
      final bool literalMap = (target == null || target == 'map' || target == 'any') && value.length > 1;
      if (!literalMap) {
        if (value.containsKey('variable')) {
          final String? name = asString(value['variable']);
          final String? other = name == null ? null : types[name];
          if (name == null) return;
          if (other == null) {
            error('$field.variable', 'No variable named $name. Declare it first.');
          } else if (!(target == null || target == 'any' || other == 'any' || other == target)) {
            error('$field.variable', '$name holds ${_words(other)}; this needs ${_words(target)}.');
          }
        } else if (value.containsKey('expression')) {
          _expression(value['expression'], '$field.expression', switch (target) {
            'boolean' => ValueType.boolean,
            'integer' || 'decimal' => ValueType.number,
            _ => ValueType.any,
          });
        } else if (target != null && target != 'string' && target != 'any') {
          error('$field.template', 'A template makes text, and this needs ${_words(target)}.');
        } else {
          _template(value['template'], '$field.template');
        }
        return;
      }
    }
    if (target == null) return;
    final String? problem = _literalProblem(value, target);
    if (problem != null) error(field, problem);
  }

  void _expression(Object? source, String path, ValueType expected) {
    if (source is! String || source.trim().isEmpty) return;
    final String? problem = checkBehaviorExpression(source, scope);
    if (problem != null) {
      error(path, problem);
    } else {
      _notes(path, expressionNotes(source, scope, expected: expected));
    }
  }

  void _template(Object? source, String path) {
    if (source is! String) return;
    final String? problem = checkBehaviorTemplate(source, scope);
    if (problem != null) {
      error(path, problem);
      return;
    }
    final (List<BehaviorHole> holes, int? _) = behaviorHoles(source);
    for (final BehaviorHole hole in holes) {
      for (final ExpressionNote note in expressionNotes(hole.source, scope)) {
        out.add(Diagnostic(note.severity, path, '{{ ${hole.source.trim()} }}: ${note.message}', code: 'behavior-rule'));
      }
    }
  }

  void _capability(JsonMap node, String path, String kind) {
    final String? id = asString(node['capability']);
    final JsonMap arguments = asMap(node['arguments']);
    final CapabilitySpec? spec = catalog.capability(id);
    int total = 0;
    for (final String field in slotFields) {
      total += asMap(node[field]).length;
    }
    if (asMap(node['bindings']).length > _maxSlots) error('$path.bindings', 'At most $_maxSlots arguments can come from variables.');
    if (total > _maxSlots) error('$path.${asMap(node['expressions']).isNotEmpty ? 'expressions' : 'templates'}', 'At most $_maxSlots arguments of one step can be filled when it runs.');
    final List<List<String>> claimed = <List<String>>[];
    for (final String field in slotFields) {
      for (final MapEntry<String, Object?> slot in asMap(node[field]).entries) {
        final String at = '$path.$field[${slot.key}]';
        final List<String>? pointer = _pointer(slot.key);
        if (pointer == null) {
          error(at, 'Name the argument as a JSON Pointer such as /text.');
          continue;
        }
        if (claimed.any((List<String> other) => _prefix(other, pointer) || _prefix(pointer, other))) {
          error(at, 'Another filled value already covers this argument.');
          continue;
        }
        claimed.add(pointer);
        switch (field) {
          case 'bindings':
            _declared(asMap(slot.value)['variable'], '$at.variable');
          case 'expressions':
            final FieldSpec? argument = pointer.length == 1 ? spec?.arguments.where((FieldSpec field) => field.key == pointer.first).firstOrNull : null;
            _expression(slot.value, at, switch (argument?.kind) {
              FieldKind.boolean => ValueType.boolean,
              FieldKind.integer || FieldKind.number => ValueType.number,
              _ => ValueType.any,
            });
          default:
            _template(slot.value, at);
        }
        final (bool found, Object? held) = _at(arguments, pointer);
        if (!found) {
          error(at, 'The arguments have nothing at ${slot.key} to fill. Add ${pointer.last} with the value null.');
        } else if (held != null) {
          error(at, 'The arguments hold a value at ${slot.key}; set it to null so this fills it.');
        }
      }
    }
    if (total > 0 || id == null || !id.startsWith('animus:')) return;
    for (final (String field, String message) in capabilityProblems(id, arguments)) {
      error('$path.arguments${field.substring(1)}', message);
    }
  }
}

/// The problem with literal [value] for a variable of [type], or null.
String? _literalProblem(Object? value, String type) {
  final bool fits = switch (type) {
    'boolean' => value is bool,
    'integer' => value is int || value is double && value.isFinite && value == value.roundToDouble(),
    'decimal' => value is num && value.isFinite,
    'string' => value is String,
    'list' => value is List<Object?>,
    'map' => value is Map<String, Object?>,
    _ => value != null,
  };
  return fits ? null : 'Expected ${_words(type)} here.';
}

List<String>? _pointer(String encoded) {
  if (!encoded.startsWith('/')) return null;
  final List<String> raw = encoded.substring(1).split('/');
  if (raw.length > _maxSegments) return null;
  final List<String> out = <String>[];
  for (final String segment in raw) {
    if (RegExp(r'~(?![01])').hasMatch(segment)) return null;
    out.add(segment.replaceAll('~1', '/').replaceAll('~0', '~'));
  }
  return out;
}

bool _prefix(List<String> left, List<String> right) {
  if (left.length > right.length) return false;
  for (int index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}

/// Whether [arguments] has something at [pointer], and what.
(bool, Object?) _at(JsonMap arguments, List<String> pointer) {
  Object? current = arguments;
  for (final String segment in pointer) {
    if (current is Map<String, Object?>) {
      if (!current.containsKey(segment)) return (false, null);
      current = current[segment];
    } else if (current is List<Object?>) {
      final int? index = RegExp(r'^(0|[1-9][0-9]{0,8})$').hasMatch(segment) ? int.parse(segment) : null;
      if (index == null || index >= current.length) return (false, null);
      current = current[index];
    } else {
      return (false, null);
    }
  }
  return (true, current);
}

final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final RegExp _identifier = RegExp(r'^[a-z0-9][a-z0-9_-]*$');
final RegExp _minecraftId = RegExp(r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$');
final RegExp _permission = RegExp(r'^[A-Za-z0-9_-]+(\.[A-Za-z0-9_*-]+)*$');
final RegExp _poseName = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');
final RegExp _shopId = RegExp(r'^[a-z0-9_-]{1,64}:[a-z0-9_/-]{1,191}$');
final RegExp _glossId = RegExp(r'^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$');
final RegExp _animationId = RegExp(r'^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$');
final RegExp _questId = RegExp(r'^([a-z0-9_-]{1,64}:)?[a-z0-9][a-z0-9_-]{0,63}$');
final RegExp _recordingId = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');
final RegExp _world = RegExp(r'^[A-Za-z0-9_./:-]{1,64}$');
final RegExp _schematic = RegExp(r'^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.(schem|nbt|schematic)$');
final RegExp _bossbarId = RegExp(r'^[a-z0-9_.-]{1,64}$');
final RegExp _skinHash = RegExp(r'^[a-f0-9]{64}$');
final RegExp _coordinate = RegExp(r'^\s*-?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][-+]?[0-9]+)?\s*$');
const Set<String> _colourNames = <String>{
  'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray', 'grey', 'dark_gray', 'dark_grey', 'blue', 'green', 'aqua', 'red',
  'light_purple', 'yellow', 'white',
};

bool _colour(String colour) {
  final String value = colour.trim().toLowerCase();
  if (RegExp(r'^#[0-9a-f]{6}$').hasMatch(value)) return true;
  if (value.length == 1 && '0123456789abcdef'.contains(value)) return true;
  return _colourNames.contains(value);
}

/// The argument checks of built-in capability [id] (`animus:say`, ...) for
/// arguments written out in full, as (field, message) with fields such as
/// `$.radius`; `$` is the arguments as a whole.
List<(String, String)> capabilityProblems(String id, JsonMap a) {
  final List<(String, String)> out = <(String, String)>[];
  String? text(String key) => a[key] is String ? a[key]! as String : null;
  bool flag(String key, bool fallback) => a[key] is bool ? a[key]! as bool : fallback;
  String label(String key) => argumentLabel(key);
  void add(String key, String message) => out.add((key.isEmpty ? r'$' : '\$.$key', message));
  void form(String key, RegExp pattern, String hint) {
    final String? value = text(key);
    if (value != null && !pattern.hasMatch(value)) add(key, hint);
  }

  void plain(String key) {
    final String? value = text(key);
    if (value != null && (value.trim().isEmpty || value.runes.any(javaIsIsoControl))) add(key, 'Write some text for ${label(key)}; blank text and control characters are refused.');
  }

  void display(String key) {
    final String? value = text(key);
    if (value != null && (value.isEmpty || value.runes.any(javaIsIsoControl))) add(key, '${label(key)} cannot be empty or hold control characters.');
  }

  void uuid(String key, String what) {
    final String? value = text(key);
    if (value != null && !_uuid.hasMatch(value)) add(key, 'Expected $what UUID in lowercase.');
  }

  void player() => uuid('player', 'a player');
  void npc() => uuid('npc', 'an NPC');
  void atPlayer() {
    uuid('atPlayer', 'a player');
    if (a.containsKey('atPlayer') && text('at') == 'npc') add('atPlayer', 'At player is used only when At is player.');
  }

  void item(String key) => form(key, _minecraftId, 'Write an item id such as minecraft:bread.');

  void flagPlayer() {
    player();
    final String scope = text('scope') ?? 'player';
    if (a.containsKey('player') && scope != 'player' && scope != 'relationship') add('player', 'Player names whose flag it is, so Scope must be player or relationship.');
    if (!a.containsKey('npc')) return;
    npc();
    if (scope != 'npc' && scope != 'relationship') add('npc', 'NPC names whose flag it is, so Scope must be npc or relationship.');
  }

  void quest() {
    form('quest', _questId, 'Write the quest id as namespace:name or name, in lowercase.');
    player();
  }

  void timeout() {
    if (a.containsKey('timeoutTicks') && !flag('wait', false)) add('timeoutTicks', 'Timeout ticks works only with Wait on.');
  }

  void gift() {
    final Object? stack = a['stack'];
    if (a.containsKey('stack') && const <String>['item', 'name', 'lore', 'enchantments'].any(a.containsKey)) add('stack', 'Give the item as a stack or by its fields, not both.');
    final JsonMap source = stack is Map<String, Object?> ? stack : a;
    final String at = stack is Map<String, Object?> ? 'stack.' : '';
    if (source['item'] is String && !_minecraftId.hasMatch(source['item']! as String)) add('${at}item', 'Write an item id such as minecraft:bread.');
    if (source['name'] is String) {
      final String name = source['name']! as String;
      if (name.trim().isEmpty || name.runes.any(javaIsIsoControl)) add('name', 'Write some text for Name; blank text and control characters are refused.');
    }
    for (final String key in asMap(source['enchantments']).keys) {
      if (!_minecraftId.hasMatch(key)) add('${at}enchantments', 'Enchantment $key is not an id such as minecraft:sharpness.');
    }
  }

  void charge() {
    final Object? money = a['money'];
    final Object? levels = a['levels'];
    final List<Object?> items = asList(a['items']);
    final int before = out.length;
    for (int index = 0; index < items.length; index++) {
      final Object? itemId = asMap(asMap(items[index])['item'])['id'];
      if (itemId is! String || !_minecraftId.hasMatch(itemId)) add('items[$index].item', 'Write an item id such as minecraft:bread.');
    }
    if (out.length == before && (money is! num || money <= 0) && (levels is! num || levels == 0) && items.isEmpty) add('', 'A charge takes money above 0, at least one level or an item.');
    player();
  }

  void location() {
    final String? value = text('location');
    if (value == null) return;
    final List<String> parts = value.split(',');
    final bool form = parts.length >= 3 &&
        parts.length <= 4 &&
        parts.take(3).every((String part) => _coordinate.hasMatch(part) && (double.tryParse(part.trim())?.abs() ?? double.infinity) <= 30000000) &&
        (parts.length == 3 || (parts[3].trim().isNotEmpty && !parts[3].runes.any(javaIsIsoControl)));
    if (!form) add('location', 'Write the location as x,y,z or x,y,z,world, such as 10,64,-5,world.');
    if (a.containsKey('x') || a.containsKey('y') || a.containsKey('z')) add('location', 'Give a location or X, Y and Z, not both.');
    if (form && parts.length == 4 && a.containsKey('world')) add('world', 'The location already names its world; remove World.');
  }

  void position(String what) {
    final int given = <String>['x', 'y', 'z'].where(a.containsKey).length;
    if (given != 0 && given != 3) add('x', 'Give X, Y and Z together, or none to $what.');
  }

  switch (id) {
    case 'animus:has-permission':
      form('permission', _permission, 'Write a permission node such as essentials.warp.');
    case 'animus:can-afford' || 'animus:charge':
      charge();
    case 'animus:flag-state':
      form('name', _identifier, 'Write the flag name in lowercase letters, digits, _ or -.');
      flagPlayer();
    case 'animus:quest-state' || 'animus:set-quest-state' || 'animus:advance-quest-state':
      form('name', _identifier, 'Write the quest stage name in lowercase letters, digits, _ or -.');
    case 'animus:say':
      plain('text');
      player();
    case 'animus:choices':
      plain('id');
      plain('prompt');
      final Set<String> seen = <String>{};
      for (final Object? raw in asList(a['options'])) {
        final JsonMap option = asMap(raw);
        final String? optionId = asString(option['id']);
        if (optionId == null) continue;
        if (!_identifier.hasMatch(optionId)) add('options', 'Answer ids use lowercase letters, digits, _ or -.');
        if (!seen.add(optionId)) add('options', 'Two answers have the id $optionId; each needs its own.');
        final String? optionText = asString(option['text']);
        if (optionText != null && (optionText.trim().isEmpty || optionText.runes.any(javaIsIsoControl))) add('options', 'Each answer needs text without control characters.');
        final String? colour = asString(option['color']);
        if (colour != null && !_colour(colour)) add('options', 'Colour is a name such as green, a code such as a, or #RRGGBB.');
        final String? style = asString(option['style']);
        if (style != null && !const <String>{'bold', 'italic', 'underlined', 'plain'}.contains(style)) add('options', 'Style is bold, italic, underlined or plain.');
        final String? hover = asString(option['hover']);
        if (hover != null && (hover.trim().isEmpty || hover.runes.any((int glyph) => glyph != 10 && javaIsIsoControl(glyph)))) add('options', 'Hover text cannot be blank or hold control characters other than new lines.');
      }
    case 'animus:command':
      plain('command');
      player();
      if (text('command')?.startsWith('/') ?? false) add('command', 'Write the command without the leading /.');
      final bool granted = a.containsKey('permissions');
      for (final Object? node in asList(a['permissions'])) {
        if (node is String && !_permission.hasMatch(node)) add('permissions', 'Permissions are nodes such as essentials.warp.');
      }
      final bool asPlayer = text('as') == 'player';
      if (flag('asOperator', false) && !asPlayer) add('asOperator', 'As operator makes the player an operator for the command; set As to player.');
      if ((granted || a.containsKey('permissionTicks')) && !asPlayer) {
        add('permissions', 'Permissions are given to the player; set As to player.');
      } else if (a.containsKey('permissionTicks') && !granted) {
        add('permissionTicks', 'Permission ticks needs Permissions.');
      }
    case 'animus:navigate':
      location();
      timeout();
      form('anchor', _poseName, 'Write the anchor name in lowercase letters, digits, _ or -.');
      if (a.containsKey('route')) {
        form('route', _identifier, 'Write the route id in lowercase letters, digits, _ or -.');
        if (a.containsKey('mode') || a.containsKey('speed')) add('route', 'A saved route keeps its own mode and speed; remove Mode and Speed.');
      }
    case 'animus:play-recording':
      timeout();
      form('recording', _recordingId, 'Write the recording id in lowercase letters, digits, _ or -.');
      if (flag('wait', false) && (text('playback') ?? 'once') != 'once') add('wait', 'Only a once playback finishes, so Wait needs Playback set to once.');
    case 'animus:look':
      npc();
      location();
    case 'animus:look-close' || 'animus:animate':
      npc();
    case 'animus:pose':
      form('pose', _poseName, 'Write the pose name in lowercase letters, digits, _ or -.');
    case 'animus:set-appearance':
      plain('name');
      form('skin', _skinHash, 'Skin is a 64 character texture hash.');
      if (a.containsKey('reset') && (a['reset'] != true || a.containsKey('name') || a.containsKey('skin'))) add('reset', 'Reset must be on, without Name or Skin.');
    case 'animus:set-equipment':
      item('item');
    case 'animus:set-attribute':
      final String? attribute = text('attribute');
      if (attribute == null) break;
      final AttributeSpec? spec = attributeTable(InspectSchemas.embedded.npc)[bareAttributeId(attribute)];
      if (spec == null) {
        add('attribute', 'There is no attribute $attribute. Pick one such as scale, gravity or movement_speed.');
        break;
      }
      if (a.containsKey('reset') && (a['reset'] != true || a.containsKey('value'))) add('reset', 'Reset must be on, without Value.');
      if (a['value'] is num && spec.problem(a['value']) != null) add('value', 'Use ${formatAttribute(spec.minimum)} to ${formatAttribute(spec.maximum)} for ${spec.id}.');
    case 'animus:play-sound':
      form('sound', _minecraftId, 'Write a sound id such as minecraft:block.note_block.chime.');
      player();
      atPlayer();
      npc();
    case 'animus:spawn-particle':
      form('particle', _minecraftId, 'Write a particle id such as minecraft:heart.');
      player();
      atPlayer();
      npc();
    case 'animus:combat-control':
      final String? operation = text('operation');
      if (operation == null) break;
      if (operation != 'guard' && (operation == 'target') != a.containsKey('target')) {
        add('target', operation == 'target' ? 'Target needs the entity to attack: player or an entity UUID.' : 'Only Target and Guard take a target.');
        break;
      }
      final String? target = text('target');
      if (target != null && target != 'player' && !_uuid.hasMatch(target)) add('target', 'Target is player or an entity UUID in lowercase.');
    case 'animus:open-shop':
      form('shop', _shopId, 'Write the shop id as namespace:name.');
      player();
    case 'animus:open-gui':
      form('gui', text('kind') == 'gloss' ? _glossId : _shopId, text('kind') == 'gloss' ? 'Write the Gloss menu id.' : 'Write the GUI id as namespace:name.');
      player();
    case 'animus:build':
      final bool start = text('operation') == 'start';
      location();
      for (final String key in const <String>['schematic', 'x', 'y', 'z', 'location', 'rotation', 'mirror', 'anchor', 'pattern', 'materials', 'excavate', 'ignoreAir', 'ignoreLiquids']) {
        if (!start && a.containsKey(key)) add(key, '${label(key)} is used only when Operation is start.');
      }
      position('build where the NPC stands');
      if (a['rotation'] is int && (a['rotation']! as int) % 90 != 0) add('rotation', 'Rotation is 0, 90, 180 or 270.');
      form('schematic', _schematic, 'Write a schematic file name such as hut.schem.');
    case 'animus:play-animation':
      form('animation', _animationId, 'Write the animation id as namespace:name.');
    case 'animus:set-flag':
      form('name', _identifier, 'Write the flag name in lowercase letters, digits, _ or -.');
      if (a.containsKey('clear') && (a.containsKey('value') || a.containsKey('expireSeconds'))) add('clear', 'Clear unsets the flag; remove Value and Expire seconds.');
      flagPlayer();
    case 'animus:start-quest' || 'animus:advance-quest' || 'animus:complete-quest' || 'animus:quest-status':
      quest();
    case 'animus:quest-objective':
      quest();
      form('objective', _identifier, 'Write the objective id in lowercase letters, digits, _ or -.');
    case 'animus:message':
      plain('text');
      player();
      final String to = text('to') ?? 'trigger';
      if (a.containsKey('player') && to != 'trigger') add('player', 'Player is used only when To is trigger.');
      if (a.containsKey('radius') && to != 'nearby') add('radius', 'Radius is used only when To is nearby.');
    case 'animus:title':
      display('title');
      display('subtitle');
      player();
    case 'animus:actionbar':
      display('text');
      player();
    case 'animus:bossbar':
      form('id', _bossbarId, 'Write the bar id in lowercase letters, digits, _, . or -.');
      display('title');
      if (text('operation') == 'remove' && const <String>['title', 'progress', 'color', 'style'].any(a.containsKey)) add('operation', 'A removed bar takes no Title, Progress, Color or Style.');
      player();
    case 'animus:sidebar':
      final String operation = text('operation') ?? 'show';
      if (operation == 'remove' && (a.containsKey('title') || a.containsKey('lines'))) add('operation', 'A removed sidebar takes no Title or Lines.');
      if (operation == 'show' && !a.containsKey('title')) add('title', 'A shown sidebar needs a Title.');
      if ((operation == 'set_line') != a.containsKey('line')) add('line', 'Line goes with Operation set_line only, which needs it.');
      if (operation == 'set_line' && asList(a['lines']).length != 1) add('lines', 'set_line takes one line of text.');
      display('title');
      player();
    case 'animus:ray-trace' || 'animus:read-inventory':
      player();
    case 'animus:explode':
      npc();
      location();
      form('world', _world, 'Write a world name such as world or world_nether.');
      position('explode at the NPC');
    case 'animus:teleport':
      player();
      location();
      if (a.containsKey('anchor')) {
        form('anchor', _poseName, 'Write the anchor name in lowercase letters, digits, _ or -.');
        if ((text('target') ?? 'player') != 'npc') add('anchor', 'An anchor is a place of the NPC; set Target to npc.');
      }
      final String target = text('target') ?? 'player';
      if (a.containsKey('to') && text('to') == target) add('to', 'To names the target itself.');
      if (a.containsKey('to') && (a.containsKey('world') || a.containsKey('yaw') || a.containsKey('pitch'))) add('to', 'World, Yaw and Pitch belong to an X, Y, Z or Location destination.');
      form('world', _world, 'Write a world name such as world or world_nether.');
    case 'animus:give-item' || 'animus:can-fit':
      gift();
      player();
    case 'animus:take-item':
      item('item');
      player();
    case 'animus:give-book':
      plain('title');
      plain('author');
      player();
  }
  return out;
}
