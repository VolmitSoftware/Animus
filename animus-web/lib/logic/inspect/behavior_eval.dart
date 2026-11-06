/// Evaluates behavior expressions and templates in the browser for the dry
/// run, with the behavior functions of the Java `BehaviorExpression` library
/// on top of the Gloss standard functions. Facts only a server knows
/// (player health, the NPC's world) come from [BehaviorFacts] samples, and
/// every fact read is recorded so the dry run can say it assumed them.
/// Randomness is seeded, so a replayed session takes the same branches.
library;

import 'dart:convert';
import 'dart:math' as math;

import '../gui/gui_expr.dart';
import '../gui/gui_expr_functions.dart';
import 'behavior_language.dart';

/// An evaluation that could not produce a value; the run fails there.
class BehaviorEvalFailure implements Exception {
  const BehaviorEvalFailure(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Sample answers to facts a server would give.
const Map<String, Object?> sampleFacts = <String, Object?>{
  'player.name': 'Player',
  'player.uuid': '00000000-0000-4000-8000-000000000000',
  'player.online': true,
  'player.health': 20.0,
  'player.max_health': 20.0,
  'player.food': 20.0,
  'player.level': 0.0,
  'player.world': 'world',
  'player.x': 0.0,
  'player.y': 64.0,
  'player.z': 0.0,
  'player.location': 'world, 0, 64, 0',
  'player.gamemode': 'survival',
  'player.op': false,
  'player.sneaking': false,
  'player.flying': false,
  'player.item_in_hand': 'minecraft:air',
  'player.item_in_hand_amount': 0.0,
  'npc.name': 'NPC',
  'npc.id': 'local/npc',
  'npc.uuid': '00000000-0000-4000-8000-000000000001',
  'npc.spawned': true,
  'npc.health': 20.0,
  'npc.world': 'world',
  'npc.x': 0.0,
  'npc.y': 64.0,
  'npc.z': 0.0,
  'npc.location': 'world, 0, 64, 0',
  'server.online_players': 1.0,
  'server.max_players': 20.0,
  'server.tick': 0.0,
  'server.time_millis': 0.0,
};

/// What one evaluation reads: variables, flags, quest stages and the
/// starting event's context.
class BehaviorEvalScope implements PExprScope {
  BehaviorEvalScope({
    required this.read,
    required this.flag,
    required this.quest,
    required this.random,
    this.context = const <String, Object?>{},
    this.server = false,
  });

  /// The JSON value of a declared variable, or null when it is not declared.
  final Object? Function(String name) read;

  /// A flag's JSON value, or null when unset.
  final Object? Function(String scope, String name) flag;
  final int Function(String scope, String name) quest;
  final math.Random random;
  final Map<String, Object?> context;
  final bool server;

  /// Facts read with their sample values, in order.
  final Map<String, Object?> factsRead = <String, Object?>{};

  /// Context keys read that the event did not carry; the dry run used ''.
  final Set<String> contextMissing = <String>{};

  int _work = 0;

  void _charge(int amount) {
    _work += math.max(1, amount);
    if (_work > 4096) throw const BehaviorEvalFailure('exceeds 4096 evaluation steps');
  }

  @override
  Object? variable(String dottedName) {
    final List<String> parts = dottedName.split('.');
    _charge(parts.length);
    Object? current;
    int start = 1;
    switch (parts.first) {
      case 'player' || 'npc' || 'server':
        if (parts.first == 'npc' && server) throw BehaviorEvalFailure('a server behavior has no NPC, so $dottedName is unavailable');
        final String key = '${parts[0]}.${parts.length > 1 ? parts[1] : ''}';
        if (!sampleFacts.containsKey(key)) throw BehaviorEvalFailure('unknown fact $key');
        current = sampleFacts[key];
        factsRead[key] = current;
        start = 2;
      case 'context':
        if (parts.length < 2) throw const BehaviorEvalFailure('context needs a key, as in context.message');
        if (!context.containsKey(parts[1])) contextMissing.add(parts[1]);
        current = expressionValue(context[parts[1]] ?? '');
        start = 2;
      default:
        final Object? value = read(parts.first);
        if (value == null) throw BehaviorEvalFailure("unknown variable '${parts.first}'");
        current = expressionValue(value);
    }
    for (int index = start; index < parts.length; index++) {
      if (current is! Map<String, Object?>) throw BehaviorEvalFailure("'${parts.take(index).join('.')}' is ${previewTypeName(current)}, not a map");
      final Object? next = current[parts[index]];
      if (next == null) throw BehaviorEvalFailure("no key '${parts[index]}' in '${parts.take(index).join('.')}'");
      current = next;
    }
    return current;
  }

  @override
  Object? call(String name, List<Object?> args) {
    final Object? behavior = _behaviorFunction(name, args);
    if (behavior != null) return behavior;
    return previewStdFunction(name, args);
  }

  Object? _behaviorFunction(String name, List<Object?> args) {
    void arity(int minimum, int maximum) {
      if (args.length < minimum || args.length > maximum) {
        throw BehaviorEvalFailure('$name takes ${minimum == maximum ? '$minimum' : '$minimum or $maximum'} arguments, got ${args.length}');
      }
    }

    List<Object?> list(int index) {
      final Object? value = args[index];
      if (value is List<Object?>) return value;
      throw BehaviorEvalFailure('$name: argument ${index + 1} must be a list, got ${previewTypeName(value)}');
    }

    Map<String, Object?> map(int index) {
      final Object? value = args[index];
      if (value is Map<String, Object?>) return value;
      throw BehaviorEvalFailure('$name: argument ${index + 1} must be a map, got ${previewTypeName(value)}');
    }

    int whole(Object? value) {
      if (value is double && value == value.roundToDouble()) return value.toInt();
      throw BehaviorEvalFailure('$name: expected a whole number, got ${expressionText(value)}');
    }

    double number(Object? value) {
      if (value is double) return value;
      if (value is String) {
        final double? parsed = double.tryParse(value.trim());
        if (parsed != null) return parsed;
      }
      throw BehaviorEvalFailure("'${expressionText(value)}' is not a number");
    }

    String scope(Object? value) {
      final String text = expressionText(value);
      if (!ExpressionLanguage.fallback.flagScopes.contains(text)) throw BehaviorEvalFailure("flag scope must be player, npc, relationship or global, got '$text'");
      return text;
    }

    switch (name) {
      case 'get':
        arity(2, 3);
        final Object? container = args[0];
        Object? found;
        if (container is List<Object?>) {
          final int index = whole(args[1]);
          found = index >= 0 && index < container.length ? container[index] : null;
        } else if (container is Map<String, Object?>) {
          found = container[expressionText(args[1])];
        } else {
          throw BehaviorEvalFailure('get: expected a list or map, got ${previewTypeName(container)}');
        }
        if (found != null) return found;
        if (args.length == 3) return args[2];
        throw BehaviorEvalFailure('get: no ${container is List<Object?> ? 'index' : 'key'} ${expressionText(args[1])}');
      case 'has':
        arity(2, 2);
        return map(0).containsKey(expressionText(args[1]));
      case 'keys':
        arity(1, 1);
        return <Object?>[...map(0).keys];
      case 'values':
        arity(1, 1);
        return <Object?>[...map(0).values];
      case 'put':
        arity(3, 3);
        return <String, Object?>{...map(0), expressionText(args[1]): args[2]};
      case 'remove':
        arity(2, 2);
        if (args[0] is List<Object?>) return <Object?>[...list(0)]..removeAt(whole(args[1]).clamp(0, list(0).length - 1));
        return <String, Object?>{...map(0)}..remove(expressionText(args[1]));
      case 'append':
        arity(2, 2);
        return <Object?>[...list(0), args[1]];
      case 'insert':
        arity(3, 3);
        return <Object?>[...list(0)]..insert(whole(args[1]).clamp(0, list(0).length), args[2]);
      case 'index_of':
        arity(2, 2);
        return list(0).indexWhere((Object? item) => _same(item, args[1])).toDouble();
      case 'first' || 'last':
        arity(1, 1);
        final List<Object?> items = list(0);
        if (items.isEmpty) throw BehaviorEvalFailure('$name: the list is empty');
        return name == 'first' ? items.first : items.last;
      case 'slice':
        arity(3, 3);
        final int size = args[0] is List<Object?> ? list(0).length : expressionText(args[0]).length;
        final int from = whole(args[1]).clamp(0, size), to = whole(args[2]).clamp(from, size);
        if (args[0] is List<Object?>) return list(0).sublist(from, to);
        return expressionText(args[0]).substring(from, to);
      case 'reverse':
        arity(1, 1);
        if (args[0] is List<Object?>) return list(0).reversed.toList();
        return String.fromCharCodes(expressionText(args[0]).runes.toList().reversed);
      case 'sort':
        arity(1, 1);
        final List<Object?> items = <Object?>[...list(0)];
        if (items.every((Object? item) => item is double)) {
          items.sort((Object? a, Object? b) => (a! as double).compareTo(b! as double));
        } else if (items.every((Object? item) => item is String)) {
          items.sort((Object? a, Object? b) => (a! as String).compareTo(b! as String));
        } else {
          throw const BehaviorEvalFailure('sort: the list must hold only numbers or only text');
        }
        return items;
      case 'sum':
        arity(1, 1);
        double total = 0;
        for (final Object? item in list(0)) {
          if (item is! double) throw const BehaviorEvalFailure('sum: every item must be a number');
          total += item;
        }
        return total;
      case 'join':
        arity(2, 2);
        return list(0).map(expressionText).join(expressionText(args[1]));
      case 'split':
        arity(2, 2);
        final String separator = expressionText(args[1]);
        if (separator.isEmpty) throw const BehaviorEvalFailure('split: the separator is empty');
        return <Object?>[...expressionText(args[0]).split(separator)];
      case 'range':
        arity(2, 2);
        final int from = whole(args[0]), to = whole(args[1]);
        if (to - from >= 256) throw const BehaviorEvalFailure('range: at most 256 items');
        return <Object?>[for (int value = from; value <= to; value++) value.toDouble()];
      case 'trim':
        arity(1, 1);
        return expressionText(args[0]).trim();
      case 'replace':
        arity(3, 3);
        final String find = expressionText(args[1]);
        if (find.isEmpty) throw const BehaviorEvalFailure('replace: the text to find is empty');
        return expressionText(args[0]).replaceAll(find, expressionText(args[2]));
      case 'starts_with':
        arity(2, 2);
        return expressionText(args[0]).startsWith(expressionText(args[1]));
      case 'ends_with':
        arity(2, 2);
        return expressionText(args[0]).endsWith(expressionText(args[1]));
      case 'num':
        arity(1, 1);
        return number(args[0]);
      case 'int':
        arity(1, 1);
        final double value = number(args[0]);
        return value < 0 ? value.ceilToDouble() : value.floorToDouble();
      case 'truthy':
        arity(1, 1);
        final Object? value = args[0];
        return value is bool ? value : (value is double ? value != 0 : value is String && value.toLowerCase() == 'true');
      case 'same':
        arity(2, 2);
        return _same(args[0], args[1]);
      case 'same_world':
        arity(2, 2);
        return _sampleWorld(expressionText(args[0])) == _sampleWorld(expressionText(args[1]));
      case 'text':
        arity(1, 1);
        return expressionText(args[0]);
      case 'random':
        arity(2, 2);
        final int minimum = whole(args[0]), maximum = whole(args[1]);
        if (maximum < minimum) throw const BehaviorEvalFailure('random: the maximum is below the minimum');
        return (minimum + random.nextInt(maximum - minimum + 1)).toDouble();
      case 'random_decimal':
        arity(0, 0);
        return random.nextDouble();
      case 'chance':
        arity(1, 1);
        return random.nextDouble() * 100 < number(args[0]);
      case 'pick':
        arity(1, 1);
        final List<Object?> items = list(0);
        if (items.isEmpty) throw const BehaviorEvalFailure('pick: the list is empty');
        return items[random.nextInt(items.length)];
      case 'flag':
        arity(2, 3);
        final Object? value = flag(scope(args[0]), expressionText(args[1]));
        if (value != null) return expressionValue(value);
        if (args.length == 3) return args[2];
        throw BehaviorEvalFailure("flag '${expressionText(args[1])}' is not set");
      case 'has_flag':
        arity(2, 2);
        return flag(scope(args[0]), expressionText(args[1])) != null;
      case 'quest':
        arity(2, 2);
        return quest(scope(args[0]), expressionText(args[1])).toDouble();
    }
    return null;
  }
}

/// A world as the sample server, whose main world is `world`, resolves it: the three vanilla dimension keys name its
/// folders, and any other world compares as written, ignoring case.
String _sampleWorld(String reference) => switch (reference.toLowerCase()) {
      'minecraft:overworld' || 'overworld' => 'world',
      'minecraft:the_nether' || 'the_nether' => 'world_nether',
      'minecraft:the_end' || 'the_end' => 'world_the_end',
      final String other => other,
    };

bool _same(Object? left, Object? right) {
  final double? a = left is double ? left : (left is String ? double.tryParse(left.trim()) : null);
  final double? b = right is double ? right : (right is String ? double.tryParse(right.trim()) : null);
  if (a != null && b != null) return a == b;
  return expressionText(left).toLowerCase() == expressionText(right).toLowerCase();
}

/// A JSON value as expressions see it: every number a double.
Object? expressionValue(Object? json) => switch (json) {
      final num number => number.toDouble(),
      final List<Object?> items => <Object?>[for (final Object? item in items) expressionValue(item)],
      final Map<String, Object?> entries => <String, Object?>{for (final MapEntry<String, Object?> entry in entries.entries) entry.key: expressionValue(entry.value)},
      _ => json,
    };

/// An expression result as JSON: whole numbers become integers.
Object? naturalValue(Object? value) => switch (value) {
      final double number when number == number.roundToDouble() && number.abs() < 9007199254740992 => number.toInt(),
      final List<Object?> items => <Object?>[for (final Object? item in items) naturalValue(item)],
      final Map<String, Object?> entries => <String, Object?>{for (final MapEntry<String, Object?> entry in entries.entries) entry.key: naturalValue(entry.value)},
      _ => value,
    };

/// Text the way the server writes a value into a template.
String expressionText(Object? value) => switch (value) {
      final double number => previewStringify(number),
      final int number => '$number',
      final List<Object?> items => '[${items.map(expressionText).join(', ')}]',
      final Map<String, Object?> entries => '{${entries.entries.map((MapEntry<String, Object?> entry) => '${entry.key}: ${expressionText(entry.value)}').join(', ')}}',
      null => 'null',
      _ => '$value',
    };

/// Evaluates [source]; throws [BehaviorEvalFailure].
Object? evaluateBehaviorExpression(String source, BehaviorEvalScope scope) {
  try {
    return evalPreviewExpr(parsePreviewExpr(source), scope);
  } on PExprException catch (problem) {
    throw BehaviorEvalFailure(problem.message);
  }
}

/// [text] with every hole evaluated.
String renderBehaviorTemplate(String text, BehaviorEvalScope scope) {
  final (List<BehaviorHole> holes, int? unclosed) = behaviorHoles(text);
  if (unclosed != null) throw BehaviorEvalFailure('unclosed {{ at column ${unclosed + 1}');
  final StringBuffer out = StringBuffer();
  int at = 0;
  for (final BehaviorHole hole in holes) {
    out
      ..write(text.substring(at, hole.start))
      ..write(expressionText(evaluateBehaviorExpression(hole.source, scope)));
    at = hole.end;
  }
  out.write(text.substring(at));
  return out.toString();
}

/// [value] converted to a variable of [type], as the server writes it; null
/// with a reason when it cannot be.
(Object?, String?) convertForType(Object? value, String? type) {
  final Object? natural = naturalValue(value);
  switch (type) {
    case 'boolean':
      return natural is bool ? (natural, null) : (null, 'expected true or false, got ${expressionText(value)}');
    case 'integer':
      return natural is int ? (natural, null) : (null, 'expected a whole number, got ${expressionText(value)}');
    case 'decimal':
      return value is num ? (value.toDouble(), null) : (null, 'expected a number, got ${expressionText(value)}');
    case 'string':
      return (value is String ? value : expressionText(value), null);
    case 'list':
      return natural is List<Object?> ? (natural, null) : (null, 'expected a list, got ${expressionText(value)}');
    case 'map':
      return natural is Map<String, Object?> ? (natural, null) : (null, 'expected a map, got ${expressionText(value)}');
    default:
      return (natural, null);
  }
}

/// A short display form of a JSON value.
String displayValue(Object? value) => value is String ? jsonEncode(value) : expressionText(value);
