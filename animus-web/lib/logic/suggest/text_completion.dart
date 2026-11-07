/// Completion at the caret of a text field: works out which context the
/// caret is in for the field's [FieldProfile] (an expression, a `{{ }}`
/// hole, a `{placeholder}`, a MiniMessage tag, a `%papi%` placeholder, a
/// command or a whole-field id), then fuzzy-ranks that context's rows.
library;

import '../gui/gui_catalog.dart';
import '../gui/gui_expression.dart';
import '../inspect/behavior_language.dart';
import '../inspect/completion_catalog.dart';
import 'completion_item.dart';
import 'completion_sources.dart';
import 'field_profile.dart';
import 'fuzzy.dart';
import 'minimessage_tags.dart';

const int completionLimit = 50;

/// Which source applies at the caret.
enum CaretSource { none, expression, flagName, questName, animation, placeholder, tag, tagColor, closingTag, papi, command, value }

/// The context at the caret: its source, the typed query and the span a
/// completion replaces.
class CaretSpot {
  const CaretSpot(this.source, this.query, this.from, this.to, {this.syntax = ExpressionSyntax.none, this.fresh = false});

  static const CaretSpot none = CaretSpot(CaretSource.none, '', 0, 0);

  final CaretSource source;
  final String query;
  final int from;
  final int to;

  /// The expression language, for [CaretSource.expression].
  final ExpressionSyntax syntax;

  /// Right after an opener (`{{`, `(`, an operator) with nothing typed.
  final bool fresh;
}

final RegExp _identifierTail = RegExp(r'[A-Za-z_][A-Za-z0-9_.]*$');
final RegExp _identifierChar = RegExp(r'[A-Za-z0-9_.]');
final RegExp _openerTail = RegExp(r'(\{\{|[(,+\-*/%<>=!&|?:\[])\s*$');
final RegExp _stateCall = RegExp(r'''\b(flag|has_flag|player_flag|player_has_flag|quest)\(\s*(?:['"][a-z]*['"]\s*,\s*)?['"]([a-z0-9_-]*)$''');
final RegExp _animationTail = RegExp(r'\|animation\.([a-z0-9_:/-]*)$');
final RegExp _papiTail = RegExp(r'%([A-Za-z0-9_]*)$');
final RegExp _tagTail = RegExp(r'<(/?)([A-Za-z0-9_#:]*)$');
final RegExp _placeholderTail = RegExp(r'(?<!\{)\{([A-Za-z0-9_.]*)$');
final RegExp _commandHead = RegExp(r'^\s*/?([A-Za-z0-9_:.-]*)$');

int _extend(String text, int at, RegExp char) {
  int to = at;
  while (to < text.length && char.hasMatch(text[to])) {
    to++;
  }
  return to;
}

/// Whether [segment] ends inside a quoted string.
bool _inString(String segment) {
  String? quote;
  for (int index = 0; index < segment.length; index++) {
    final String glyph = segment[index];
    if (quote != null) {
      if (glyph == r'\') {
        index++;
      } else if (glyph == quote) {
        quote = null;
      }
    } else if (glyph == "'" || glyph == '"') {
      quote = glyph;
    }
  }
  return quote != null;
}

/// The context at [caret] in [text] for a field of [profile].
CaretSpot caretSpot(String text, int caret, FieldProfile profile) {
  final int at = caret.clamp(0, text.length);
  final String before = text.substring(0, at);
  if (profile.expression != ExpressionSyntax.none) return _expressionSpot(text, at, 0, profile.expression);
  if (profile.holes != ExpressionSyntax.none) {
    final int open = before.lastIndexOf('{{');
    if (open >= 0 && before.lastIndexOf('}}') < open) return _expressionSpot(text, at, open + 2, profile.holes);
    if (profile.holes == ExpressionSyntax.gui) {
      final RegExpMatch? animation = _animationTail.firstMatch(before);
      if (animation != null) {
        int to = _extend(text, at, RegExp(r'[a-z0-9_:/-]'));
        if (to < text.length && text[to] == '|') to++;
        return CaretSpot(CaretSource.animation, animation.group(1)!, at - animation.group(1)!.length, to);
      }
    }
  }
  if (profile.papi) {
    final RegExpMatch? papi = _papiTail.firstMatch(before);
    // An odd count of % up to here means this one opens a placeholder.
    if (papi != null && '%'.allMatches(before).length.isOdd) {
      int to = _extend(text, at, RegExp('[A-Za-z0-9_]'));
      if (to < text.length && text[to] == '%') to++;
      return CaretSpot(CaretSource.papi, papi.group(1)!, papi.start + 1, to);
    }
  }
  if (profile.miniMessage) {
    final RegExpMatch? tag = _tagTail.firstMatch(before);
    if (tag != null) {
      final String body = tag.group(2)!;
      int to = _extend(text, at, RegExp('[A-Za-z0-9_#]'));
      if (to < text.length && text[to] == '>') to++;
      final int colon = body.indexOf(':');
      if (tag.group(1)!.isEmpty && colon > 0) {
        final String head = body.substring(0, colon).toLowerCase();
        if (head != 'color' && head != 'colour') return CaretSpot.none;
        final String value = body.substring(colon + 1);
        return CaretSpot(CaretSource.tagColor, value, at - value.length, to);
      }
      if (colon >= 0) return CaretSpot.none;
      return CaretSpot(tag.group(1)!.isEmpty ? CaretSource.tag : CaretSource.closingTag, body, at - body.length, to);
    }
  }
  if (profile.placeholders.isNotEmpty || profile.holes != ExpressionSyntax.none) {
    final RegExpMatch? placeholder = _placeholderTail.firstMatch(before);
    if (placeholder != null) {
      int to = _extend(text, at, _identifierChar);
      if (to < text.length && text[to] == '}') to++;
      return CaretSpot(CaretSource.placeholder, placeholder.group(1)!, placeholder.start + 1, to, syntax: profile.holes);
    }
  }
  if (profile.command) {
    final RegExpMatch? command = _commandHead.firstMatch(before);
    if (command != null) {
      final String name = command.group(1)!;
      return CaretSpot(CaretSource.command, name, at - name.length, _extend(text, at, RegExp(r'[A-Za-z0-9_:.-]')));
    }
  }
  if (profile.values.isNotEmpty || profile.options.isNotEmpty) {
    final int start = text.length - text.trimLeft().length;
    if (at < start) return CaretSpot(CaretSource.value, '', start, text.length);
    return CaretSpot(CaretSource.value, text.substring(start, at), start, text.length);
  }
  return CaretSpot.none;
}

CaretSpot _expressionSpot(String text, int at, int start, ExpressionSyntax syntax) {
  final String segment = text.substring(start, at);
  if (_inString(segment)) {
    final RegExpMatch? call = _stateCall.firstMatch(segment);
    if (call == null) return CaretSpot.none;
    final String name = call.group(2)!;
    final int to = _extend(text, at, RegExp('[a-z0-9_-]'));
    return CaretSpot(call.group(1) == 'quest' ? CaretSource.questName : CaretSource.flagName, name, at - name.length, to);
  }
  final String prefix = _identifierTail.firstMatch(segment)?.group(0) ?? '';
  final bool fresh = prefix.isEmpty && (segment.trim().isEmpty || _openerTail.hasMatch(segment));
  if (prefix.isEmpty && !fresh) return CaretSpot.none;
  return CaretSpot(CaretSource.expression, prefix, at - prefix.length, _extend(text, at, _identifierChar), syntax: syntax, fresh: fresh);
}

/// Rows for the caret in [text] of a field of [profile]. The list opens on
/// its own when [CompletionResult.auto]; otherwise Ctrl+Space opens it.
CompletionResult completeText(String text, int caret, FieldProfile profile, CompletionEnv env) {
  final CaretSpot spot = caretSpot(text, caret, profile);
  final (List<CompletionItem> items, String title) = switch (spot.source) {
    CaretSource.none => (const <CompletionItem>[], ''),
    CaretSource.expression => (_expressionItems(spot, env), 'Names and functions'),
    CaretSource.flagName => (_names(env.sources.flags, CompletionKind.flag, 'flag', 'Used in this workspace'), 'Flags'),
    CaretSource.questName => (_names(env.sources.quests, CompletionKind.quest, 'quest', 'Used in this workspace'), 'Quests'),
    CaretSource.animation => (
        <CompletionItem>[for (final String id in env.gui.animations) CompletionItem(label: id, insert: '$id|', kind: CompletionKind.document, type: 'animation', detail: 'Text animation')],
        'Text animations'
      ),
    CaretSource.placeholder => (_placeholderItems(spot, profile, env), 'Placeholders'),
    CaretSource.tag => (_tagItems(spot.query), 'Tags'),
    CaretSource.tagColor => (miniMessageColorItems(), 'Colors'),
    CaretSource.closingTag => (_closingItems(text.substring(0, spot.from)), 'Closing tags'),
    CaretSource.papi => (
        <CompletionItem>[
          for (final String id in env.sources.papi) CompletionItem(label: id, insert: '${id}_\$1%', kind: CompletionKind.papi, type: 'expansion', detail: 'PlaceholderAPI. Not filled on the server yet'),
        ],
        'PlaceholderAPI, not filled on the server yet'
      ),
    CaretSource.command => (
        <CompletionItem>[for (final String name in env.sources.commands) CompletionItem(label: name, insert: '$name ', kind: CompletionKind.command, type: 'command', detail: 'On the server')],
        'Commands'
      ),
    CaretSource.value => (valueItems(profile, env.sources), 'Values'),
  };
  if (items.isEmpty) return CompletionResult.none;
  final List<CompletionItem> ranked = fuzzyRank(items, spot.query, (CompletionItem item) => item.label, limit: completionLimit)
      .where((CompletionItem item) => !(item.label == spot.query && spot.source == CaretSource.expression))
      .toList();
  if (ranked.isEmpty) return CompletionResult.none;
  final bool auto = switch (spot.source) {
    CaretSource.expression => spot.query.isNotEmpty || (spot.fresh && text.substring(0, spot.from).trim().isNotEmpty),
    CaretSource.value || CaretSource.command => spot.query.isNotEmpty,
    _ => true,
  };
  return CompletionResult(ranked, spot.from, spot.to, title: title, auto: auto);
}

List<CompletionItem> _names(List<String> names, CompletionKind kind, String type, String detail) => <CompletionItem>[
      for (final String name in names) CompletionItem(label: name, insert: name, kind: kind, type: type, detail: detail),
    ];

List<CompletionItem> _expressionItems(CaretSpot spot, CompletionEnv env) => switch (spot.syntax) {
      ExpressionSyntax.behavior => _behaviorItems(spot.query, env.behavior),
      ExpressionSyntax.gui => _guiItems(spot.query, env.gui),
      ExpressionSyntax.none => const <CompletionItem>[],
    };

List<CompletionItem> _behaviorItems(String query, BehaviorNames? names) {
  if (names == null) return const <CompletionItem>[];
  final ExpressionScope scope = names.scope;
  final ExpressionLanguage language = scope.language;
  final List<CompletionItem> out = <CompletionItem>[];
  final int dot = query.indexOf('.');
  if (dot > 0) {
    final String head = query.substring(0, dot);
    if (head == 'context') {
      for (final String key in scope.contextKeys) {
        out.add(CompletionItem(label: 'context.$key', insert: 'context.$key', kind: CompletionKind.fact, detail: 'From the event'));
      }
    } else if (!(head == 'npc' && scope.server)) {
      for (final String fact in language.facts[head] ?? const <String>[]) {
        out.add(CompletionItem(label: '$head.$fact', insert: '$head.$fact', kind: CompletionKind.fact, detail: '${_capital(head)} fact'));
      }
    }
    return out;
  }
  for (final MapEntry<String, String> variable in scope.variables.entries) {
    if (!language.readable(variable.key)) continue;
    final String where = names.variableScopes[variable.key] ?? '';
    out.add(CompletionItem(
      label: variable.key,
      insert: variable.key,
      kind: CompletionKind.variable,
      type: variable.value,
      detail: where.isEmpty ? 'Declared variable' : '${_capital(where)} variable',
    ));
  }
  for (final String root in language.roots) {
    if (root == 'npc' && scope.server) continue;
    if (root == 'context' && scope.contextKeys.isEmpty) continue;
    out.add(CompletionItem(label: root, insert: '$root.', kind: CompletionKind.fact, type: 'root', detail: root == 'context' ? 'Values the event carries' : '${_capital(root)} facts', reopen: true));
  }
  if (query.isNotEmpty) {
    for (final MapEntry<String, List<String>> facts in language.facts.entries) {
      if (facts.key == 'npc' && scope.server) continue;
      for (final String fact in facts.value) {
        out.add(CompletionItem(label: '${facts.key}.$fact', insert: '${facts.key}.$fact', kind: CompletionKind.fact, detail: '${_capital(facts.key)} fact'));
      }
    }
    for (final String key in scope.contextKeys) {
      out.add(CompletionItem(label: 'context.$key', insert: 'context.$key', kind: CompletionKind.fact, detail: 'From the event'));
    }
  }
  for (final String function in <String>{...language.functions, ...language.standardFunctions}) {
    out.add(CompletionItem(label: '$function()', insert: '$function(\$1)', kind: CompletionKind.function, detail: 'Function'));
  }
  return out;
}

List<CompletionItem> _guiItems(String query, ExpressionNames names) {
  final List<CompletionItem> out = <CompletionItem>[
    for (final String local in names.locals) CompletionItem(label: local, insert: local, kind: CompletionKind.variable, detail: 'List entry'),
  ];
  final int dot = query.indexOf('.');
  final String? head = dot > 0 ? query.substring(0, dot) : null;
  final Set<String> roots = <String>{};
  for (final MapEntry<String, String> name in guiExpressionNames.entries) {
    final int split = name.key.indexOf('.');
    if (split > 0) roots.add(name.key.substring(0, split));
    if (head != null && !name.key.startsWith('$head.')) continue;
    if (head == null && query.isEmpty && split > 0) continue;
    out.add(CompletionItem(label: name.key, insert: name.key, kind: CompletionKind.fact, detail: name.value));
  }
  if (head == null) {
    for (final String root in roots) {
      out.add(CompletionItem(label: root, insert: '$root.', kind: CompletionKind.fact, type: 'root', detail: '${_capital(root)} values', reopen: true));
    }
  }
  for (final String namespace in guiOpenNamespaces) {
    if (head == null || namespace == '$head.') {
      out.add(CompletionItem(label: namespace, insert: namespace, kind: CompletionKind.fact, type: 'root', detail: 'Values passed at run time'));
    }
  }
  if (head == null) {
    for (final String function in guiExpressionFunctions) {
      out.add(CompletionItem(label: '$function()', insert: '$function(\$1)', kind: CompletionKind.function, detail: 'Function'));
    }
  }
  return out;
}

List<CompletionItem> _placeholderItems(CaretSpot spot, FieldProfile profile, CompletionEnv env) {
  final List<CompletionItem> out = <CompletionItem>[
    for (final MapEntry<String, String> token in profile.placeholders.entries)
      CompletionItem(label: '{${token.key}}', insert: '${token.key}}', kind: CompletionKind.placeholder, detail: token.value),
  ];
  // After one `{` in a field with holes, offer whole `{{ name }}` holes.
  final List<CompletionItem> names = switch (spot.syntax) {
    ExpressionSyntax.behavior => _behaviorItems(spot.query, env.behavior),
    ExpressionSyntax.gui => _guiItems(spot.query, env.gui),
    ExpressionSyntax.none => const <CompletionItem>[],
  };
  for (final CompletionItem name in names) {
    if (name.kind == CompletionKind.function || name.reopen) continue;
    out.add(CompletionItem(label: '{{ ${name.label} }}', insert: '{ ${name.insert} }}', kind: name.kind, type: name.type, detail: name.detail));
  }
  return out;
}

List<CompletionItem> _tagItems(String query) {
  if (query.startsWith('#')) {
    final String digits = query.substring(1);
    final bool whole = RegExp(r'^[0-9A-Fa-f]{6}$').hasMatch(digits);
    return <CompletionItem>[
      CompletionItem(
        label: whole ? query : '#RRGGBB',
        insert: whole ? '$query>' : r'#${1:FF5FA2}>',
        kind: CompletionKind.color,
        detail: 'Hex colour',
        swatch: whole ? query : null,
      ),
    ];
  }
  return miniMessageTagItems();
}

/// `</name>` for each tag left open in [before], most recent first.
List<CompletionItem> _closingItems(String before) {
  final List<String> open = <String>[];
  for (final RegExpMatch match in RegExp(r'<(/?)([A-Za-z0-9_#]+)(?::[^>]*)?>').allMatches(before)) {
    final String name = match.group(2)!.toLowerCase();
    if (!miniMessageKnows(name) || name == 'reset') continue;
    if (match.group(1)!.isEmpty) {
      open.add(name);
    } else {
      open.remove(name);
    }
  }
  return <CompletionItem>[
    for (final String name in open.reversed.toSet())
      CompletionItem(label: name, insert: '$name>', kind: CompletionKind.tag, detail: 'Closes <$name>', swatch: miniMessageSwatch(name)),
  ];
}

/// Rows for a whole-field id: the profile's fixed options, then each
/// source's ids.
List<CompletionItem> valueItems(FieldProfile profile, CompletionSources sources) {
  final List<CompletionItem> out = <CompletionItem>[
    for (final ValueOption option in profile.options) CompletionItem(label: option.value, insert: option.value, kind: CompletionKind.value, detail: option.detail),
  ];
  final Set<String> seen = <String>{for (final ValueOption option in profile.options) option.value};
  void add(CompletionItem item) {
    if (seen.add(item.label)) out.add(item);
  }

  for (final ValueRef ref in profile.values) {
    switch (ref.source) {
      case ValueSource.registry:
        for (final String id in sources.registry(ref.registry!)) {
          add(CompletionItem(label: id, insert: id, kind: CompletionKind.registry, type: ref.type, detail: sources.paired ? 'On the server' : 'Minecraft 26.3'));
        }
      case ValueSource.document:
        for (final String id in sources.ids(ref.document!)) {
          add(CompletionItem(label: id, insert: id, kind: CompletionKind.document, type: ref.type, detail: '${ref.document!.label} in this workspace'));
        }
      case ValueSource.flag:
        for (final String name in sources.flags) {
          add(CompletionItem(label: name, insert: name, kind: CompletionKind.flag, type: 'flag', detail: 'Used in this workspace'));
        }
      case ValueSource.quest:
        for (final String name in sources.quests) {
          add(CompletionItem(label: name, insert: name, kind: CompletionKind.quest, type: 'quest', detail: 'Used in this workspace'));
        }
      case ValueSource.capability:
        final CompletionCatalog? catalog = sources.catalog;
        if (catalog == null) break;
        for (final CapabilitySpec spec in catalog.capabilitiesFor(ref.label ?? 'action')) {
          add(CompletionItem(label: spec.id, insert: spec.id, kind: CompletionKind.capability, type: spec.kind, detail: spec.description));
        }
      case ValueSource.event:
        final CompletionCatalog? catalog = sources.catalog;
        if (catalog == null) break;
        for (final TriggerSpec spec in catalog.triggers.values) {
          add(CompletionItem(label: spec.event, insert: spec.event, kind: CompletionKind.event, type: spec.reach, detail: spec.description));
        }
      case ValueSource.command:
        for (final String name in sources.commands) {
          add(CompletionItem(label: name, insert: name, kind: CompletionKind.command, type: 'command', detail: 'On the server'));
        }
      case ValueSource.option:
        break;
    }
  }
  return out;
}

String _capital(String word) => word.isEmpty ? word : '${word[0].toUpperCase()}${word.substring(1)}';
