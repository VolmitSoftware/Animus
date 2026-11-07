/// What one text field accepts, which decides the completion sources at the
/// caret and the hints under unknown names: `{{ }}` holes or a whole
/// expression (behavior or GUI language), `{token}` placeholders, MiniMessage
/// tags, PlaceholderAPI `%placeholders%`, a command line, or an id from a
/// registry, the workspace or the catalog.
library;

import '../../model/document_kind.dart';
import '../gui/gui_expression.dart';
import '../inspect/behavior_language.dart';
import 'completion_sources.dart';

/// Which expression language a field's holes or whole text use.
enum ExpressionSyntax { none, behavior, gui }

/// Where an id field's values come from.
enum ValueSource { registry, document, flag, quest, capability, event, command, option }

/// One source of whole-field values.
class ValueRef {
  const ValueRef.registry(RegistryKind this.registry) : source = ValueSource.registry, document = null, label = null;
  const ValueRef.document(DocumentKind this.document) : source = ValueSource.document, registry = null, label = null;
  const ValueRef.flags() : source = ValueSource.flag, registry = null, document = null, label = null;
  const ValueRef.quests() : source = ValueSource.quest, registry = null, document = null, label = null;

  /// Capabilities of node kind [label] (`action` or `condition`).
  const ValueRef.capabilities(String this.label) : source = ValueSource.capability, registry = null, document = null;
  const ValueRef.events() : source = ValueSource.event, registry = null, document = null, label = null;
  const ValueRef.commands() : source = ValueSource.command, registry = null, document = null, label = null;

  final ValueSource source;
  final RegistryKind? registry;
  final DocumentKind? document;
  final String? label;

  /// The type word rows show.
  String get type => switch (source) {
        ValueSource.registry => registry!.label,
        ValueSource.document => document!.wireName,
        ValueSource.flag => 'flag',
        ValueSource.quest => 'quest',
        ValueSource.capability => label ?? 'capability',
        ValueSource.event => 'event',
        ValueSource.command => 'command',
        ValueSource.option => 'value',
      };
}

/// A fixed value a field offers besides its sources.
class ValueOption {
  const ValueOption(this.value, {this.detail = ''});

  final String value;
  final String detail;
}

class FieldProfile {
  const FieldProfile({
    this.holes = ExpressionSyntax.none,
    this.expression = ExpressionSyntax.none,
    this.placeholders = const <String, String>{},
    this.miniMessage = false,
    this.papi = false,
    this.command = false,
    this.values = const <ValueRef>[],
    this.options = const <ValueOption>[],
  });

  static const FieldProfile none = FieldProfile();

  /// Text with `{{ expr }}` holes, `&` codes and MiniMessage, in the GUI
  /// language (holograms, menus, chest GUIs).
  static const FieldProfile guiText = FieldProfile(holes: ExpressionSyntax.gui, miniMessage: true, papi: true);

  /// One GUI expression (`show`, `when`, `amount`).
  static const FieldProfile guiExpression = FieldProfile(expression: ExpressionSyntax.gui);

  /// One behavior expression (conditions, `cancel`, operands).
  static const FieldProfile behaviorExpression = FieldProfile(expression: ExpressionSyntax.behavior);

  /// A behavior template: `{{ }}` holes only.
  static const FieldProfile behaviorTemplate = FieldProfile(holes: ExpressionSyntax.behavior);

  /// Text the formatter colours, with no holes (NPC names).
  static const FieldProfile richText = FieldProfile(miniMessage: true);

  final ExpressionSyntax holes;

  /// The whole field is one expression.
  final ExpressionSyntax expression;

  /// `{token}` placeholders the server replaces in this field, with what
  /// each becomes.
  final Map<String, String> placeholders;
  final bool miniMessage;
  final bool papi;

  /// The first word is a server command.
  final bool command;

  /// The whole field is one id from these sources.
  final List<ValueRef> values;
  final List<ValueOption> options;

  bool get isNone =>
      holes == ExpressionSyntax.none &&
      expression == ExpressionSyntax.none &&
      placeholders.isEmpty &&
      !miniMessage &&
      !papi &&
      !command &&
      values.isEmpty &&
      options.isEmpty;

  /// Whether the field's text is written as code (monospace).
  bool get code => expression != ExpressionSyntax.none || command;

  FieldProfile copyWith({Map<String, String>? placeholders, bool? command, List<ValueRef>? values, List<ValueOption>? options}) => FieldProfile(
        holes: holes,
        expression: expression,
        placeholders: placeholders ?? this.placeholders,
        miniMessage: miniMessage,
        papi: papi,
        command: command ?? this.command,
        values: values ?? this.values,
        options: options ?? this.options,
      );
}

/// The behavior names a field can read, with each variable's scope.
class BehaviorNames {
  const BehaviorNames({required this.scope, this.variableScopes = const <String, String>{}});

  final ExpressionScope scope;

  /// Variable name to its `scope` (`execution`, `npc`, `player`, ...).
  final Map<String, String> variableScopes;
}

/// What completion and hints read besides the field: the shared sources
/// and the expression names in reach of this field.
class CompletionEnv {
  const CompletionEnv({this.sources = CompletionSources.empty, this.behavior, this.gui = ExpressionNames.standard});

  final CompletionSources sources;
  final BehaviorNames? behavior;
  final ExpressionNames gui;
}
