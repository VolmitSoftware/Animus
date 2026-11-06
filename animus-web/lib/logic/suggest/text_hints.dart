/// Hints under unknown names in a text field, with the same rules as the
/// server: an expression name or function the behavior compiler or the GUI
/// expression loader would refuse, a `{token}` the field does not replace,
/// a tag the text formatter leaves as text, and an id missing from a
/// complete list (a registry, the workspace's documents, the catalog).
library;

import '../gui/gui_catalog.dart';
import '../gui/gui_expression.dart';
import '../inspect/behavior_language.dart';
import 'completion_item.dart';
import 'completion_sources.dart';
import 'field_profile.dart';
import 'minimessage_tags.dart';
import 'text_completion.dart';

/// One underlined span and why.
class TextHint {
  const TextHint(this.start, this.end, this.message);

  final int start;
  final int end;
  final String message;

  @override
  String toString() => 'TextHint($start, $end, $message)';
}

final RegExp _identifierStart = RegExp('[A-Za-z_]');
final RegExp _identifierChar = RegExp(r'[A-Za-z0-9_.]');
final RegExp _guiHole = RegExp(r'\{\{([\s\S]*?)\}\}');
final RegExp _placeholder = RegExp(r'(?<!\{)\{([A-Za-z0-9_.]+)\}(?!\})');
final RegExp _tag = RegExp(r'<([^<>\s]+)>');

/// Hints for [text] in a field of [profile], in text order.
List<TextHint> textHints(String text, FieldProfile profile, CompletionEnv env) {
  final List<TextHint> out = <TextHint>[];
  if (profile.expression != ExpressionSyntax.none) {
    _expressionHints(text, 0, text.length, profile.expression, env, out);
    return out;
  }
  final List<(int, int)> holes = <(int, int)>[];
  switch (profile.holes) {
    case ExpressionSyntax.behavior:
      for (final BehaviorHole hole in behaviorHoles(text).$1) {
        holes.add((hole.start, hole.end));
        _expressionHints(text, hole.start + 2, hole.end - 2, ExpressionSyntax.behavior, env, out);
      }
    case ExpressionSyntax.gui:
      for (final RegExpMatch hole in _guiHole.allMatches(text)) {
        holes.add((hole.start, hole.end));
        _expressionHints(text, hole.start + 2, hole.end - 2, ExpressionSyntax.gui, env, out);
      }
    case ExpressionSyntax.none:
      break;
  }
  bool inHole(int at) => holes.any(((int, int) hole) => at >= hole.$1 && at < hole.$2);
  if (profile.placeholders.isNotEmpty) {
    for (final RegExpMatch match in _placeholder.allMatches(text)) {
      if (inHole(match.start) || profile.placeholders.containsKey(match.group(1))) continue;
      out.add(TextHint(match.start, match.end, 'Not a placeholder here. Sent as written.'));
    }
  }
  if (profile.miniMessage) {
    for (final RegExpMatch match in _tag.allMatches(text)) {
      if (inHole(match.start) || miniMessageKnows(match.group(1)!)) continue;
      out.add(TextHint(match.start, match.end, 'Not a supported tag. Shown as text.'));
    }
  }
  final String? value = _valueProblem(text, profile, env.sources);
  if (value != null) {
    final int start = text.length - text.trimLeft().length;
    out.add(TextHint(start, text.trimRight().length, value));
  }
  out.sort((TextHint a, TextHint b) => a.start.compareTo(b.start));
  return out;
}

void _expressionHints(String text, int start, int end, ExpressionSyntax syntax, CompletionEnv env, List<TextHint> out) {
  final BehaviorNames? behavior = env.behavior;
  if (syntax == ExpressionSyntax.behavior && behavior == null) return;
  String? quote;
  int index = start;
  while (index < end) {
    final String glyph = text[index];
    if (quote != null) {
      if (glyph == r'\') {
        index += 2;
        continue;
      }
      if (glyph == quote) quote = null;
      index++;
      continue;
    }
    if (glyph == "'" || glyph == '"') {
      quote = glyph;
      index++;
      continue;
    }
    final bool startsName = _identifierStart.hasMatch(glyph) && (index == start || !RegExp(r'[A-Za-z0-9_.]').hasMatch(text[index - 1]));
    if (!startsName) {
      index++;
      continue;
    }
    int stop = index;
    while (stop < end && _identifierChar.hasMatch(text[stop])) {
      stop++;
    }
    final String name = text.substring(index, stop);
    int after = stop;
    while (after < end && text[after] == ' ') {
      after++;
    }
    final bool call = after < end && text[after] == '(';
    final String? problem = name.endsWith('.') || const <String>{'true', 'false', 'null'}.contains(name)
        ? null
        : syntax == ExpressionSyntax.behavior
            ? _behaviorProblem(name, call, behavior!)
            : _guiProblem(name, call, env.gui);
    if (problem != null) out.add(TextHint(index, stop, problem));
    index = stop;
  }
}

String? _behaviorProblem(String name, bool call, BehaviorNames names) {
  if (call) return names.scope.language.knowsFunction(name) ? null : 'There is no function $name().';
  return checkBehaviorExpression(name, names.scope);
}

String? _guiProblem(String name, bool call, ExpressionNames names) {
  if (call) {
    if (guiExpressionFunctions.contains(name)) return null;
    return name == 'papi' ? 'papi(...) is not available yet.' : 'There is no function $name().';
  }
  return names.knows(name) ? null : 'Unknown name $name.';
}

/// Why the whole-field id [text] is wrong, when every source of [profile]
/// lists the whole set; null when it is known or cannot be told.
String? _valueProblem(String text, FieldProfile profile, CompletionSources sources) {
  final String value = text.trim();
  if (profile.values.isEmpty || value.isEmpty || value.contains('{') || value.contains('*')) return null;
  for (final ValueRef ref in profile.values) {
    if (!_complete(ref, sources)) return null;
  }
  final Set<String> known = <String>{for (final CompletionItem item in valueItems(profile, sources)) item.label};
  if (known.contains(value) || known.contains(stripMinecraft(value))) return null;
  final ValueRef first = profile.values.first;
  return switch (first.source) {
    ValueSource.registry => 'Unknown ${first.registry!.label}.',
    ValueSource.document => 'No ${first.document!.wireName} $value in this workspace.',
    ValueSource.capability => 'Unknown capability.',
    ValueSource.event => 'Unknown event.',
    ValueSource.command => 'No such command on the server.',
    _ => null,
  };
}

/// Module capabilities are only listed by a paired server.
bool _complete(ValueRef ref, CompletionSources sources) => switch (ref.source) {
      ValueSource.registry => sources.complete.contains(ref.registry),
      ValueSource.document => true,
      ValueSource.capability => sources.catalog != null && sources.paired,
      ValueSource.event => sources.catalog != null,
      ValueSource.command => sources.paired && sources.commands.isNotEmpty,
      ValueSource.flag || ValueSource.quest || ValueSource.option => false,
    };
