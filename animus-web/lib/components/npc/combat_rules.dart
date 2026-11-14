/// Targets, Ignores and Avoids of `animus:combat`: who the NPC attacks,
/// never attacks and keeps away from. Each is one section, closed until it
/// holds a rule; the common rules show first and the rare ones (patterns,
/// held items, permissions, effects, combined rule sets, fights, chat) wait
/// behind one "More rules" disclosure.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/combat_forms.dart';
import '../../logic/npc/combat_labels.dart';
import '../../logic/npc/combat_settings.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../inspect/inspect_fields.dart';
import 'combat_fields.dart';

/// Rules of a rule set that wait behind "More rules", with their sub-keys
/// where they are objects of lists.
const Map<String, List<String>> _rareLists = <String, List<String>>{
  'patterns': combatPatternKeys,
  'items': combatItemKeys,
};

/// One rule section ([name] is `targets`, `ignores` or `avoids`).
class CombatRules extends StatelessWidget {
  const CombatRules({
    required this.form,
    required this.name,
    required this.schema,
    required this.diagnostics,
    this.npcIds = const <String>[],
    this.squads = const <String>[],
    super.key,
  });

  final ModuleForm form;
  final String name;
  final SchemaView schema;

  /// The document's diagnostics, for the rule sets typed from the schema.
  final List<Diagnostic> diagnostics;
  final List<String> npcIds;
  final List<String> squads;

  @override
  Widget build(BuildContext context) {
    final Object? value = form.read(<JsonStep>[name]);
    final JsonMap rules = asMap(value);
    final KeyWords words = combatWords(name);
    final List<FieldSpec> rare = combatAdvancedRuleFields(schema, name);
    final Set<String> known = <String>{...combatPlainRules, for (final FieldSpec field in rare) field.key};
    final bool rareSet = rare.any((FieldSpec field) => rules.containsKey(field.key));
    List<JsonStep> at(String key) => <JsonStep>[name, key];
    return CombatSection(
      key: ValueKey<String>('combat:$name'),
      title: words.label,
      tip: words.tip,
      docs: Docs.combatTargets,
      count: rules.isEmpty ? null : rules.length,
      open: form.problems(<JsonStep>[name], nested: true).isNotEmpty,
      part: name,
      children: value != null && value is! Map<String, Object?>
          ? <Widget>[keptField(form, <JsonStep>[name], words)]
          : <Widget>[
              problemLines(form.problems(<JsonStep>[name])),
              checksField(form, at('categories'), combatWords('rule.categories'), options: combatCategoryLabels),
              StringListField(
                form: form,
                steps: at('entityTypes'),
                words: combatWords('rule.entityTypes'),
                noun: 'mob type',
                placeholder: 'minecraft:zombie',
                profile: idProfile(const ValueRef.registry(RegistryKind.entity)),
                suggestions: <FieldOption>[for (final String type in combatCommonEntityTypes) FieldOption(type)],
              ),
              StringListField(form: form, steps: at('players'), words: combatWords('rule.players'), noun: 'player'),
              StringListField(
                form: form,
                steps: at('npcs'),
                words: combatWords('rule.npcs'),
                noun: 'NPC',
                suggestions: <FieldOption>[for (final String id in npcIds) FieldOption(id, description: 'NPC in this workspace')],
              ),
              StringListField(form: form, steps: at('names'), words: combatWords('rule.names'), noun: 'name'),
              StringListField(
                form: form,
                steps: at('squads'),
                words: combatWords('rule.squads'),
                noun: 'squad',
                suggestions: <FieldOption>[for (final String squad in squads) FieldOption(squad, description: 'squad in this workspace')],
              ),
              switchField(form, at('owner'), combatWords('rule.owner'), fallback: false, unsetAtDefault: true),
              for (final String key in const <String>['healthAbove', 'healthBelow'])
                numberField(form, at(key), combatWords('rule.$key'), fallback: 0, minimum: 0, maximum: 100),
              if (rare.isNotEmpty)
                MoreFields(
                  key: ValueKey<String>('combat:$name:more'),
                  label: 'More rules',
                  open: rareSet,
                  children: <Widget>[for (final FieldSpec field in rare) ..._rare(field, rules[field.key])],
                ),
              otherFields(form, <JsonStep>[name], known),
            ],
    );
  }

  List<Widget> _rare(FieldSpec field, Object? value) {
    final List<JsonStep> at = <JsonStep>[name, field.key];
    final List<String>? lists = _rareLists[field.key];
    if (lists != null && (value == null || value is Map<String, Object?>)) {
      return <Widget>[
        for (final String key in lists)
          StringListField(form: form, steps: <JsonStep>[...at, key], words: combatWords('${field.key}.$key'), noun: 'pattern'),
        otherFields(form, at, lists.toSet()),
      ];
    }
    if (field.key == 'events' && (value == null || value is Map<String, Object?>)) return _events(field, asMap(value));
    if (field.kind == FieldKind.array && field.key != 'all' && field.key != 'together') {
      return <Widget>[
        StringListField(
          form: form,
          steps: at,
          words: combatWords('rule.${field.key}'),
          noun: field.key == 'chat' ? 'word' : 'entry',
          profile: field.key == 'effects' ? idProfile(const ValueRef.registry(RegistryKind.effect)) : FieldProfile.none,
        ),
      ];
    }
    return <Widget>[_typed(field, at, combatWords('rule.${field.key}'))];
  }

  List<Widget> _events(FieldSpec field, JsonMap events) {
    final List<JsonStep> at = <JsonStep>[name, 'events'];
    final Map<String, FieldSpec> parts = <String, FieldSpec>{for (final FieldSpec part in schema.fields(field.schema, events)) part.key: part};
    return <Widget>[
      checksField(form, <JsonStep>[...at, 'kinds'], combatWords('events.kinds'), options: combatFightKindLabels),
      for (final String key in const <String>['playerAttacks', 'anyAttacks'])
        if (parts[key] case final FieldSpec part) _ruleSet(part, <JsonStep>[...at, key], combatWords('events.$key')),
      otherFields(form, at, <String>{'kinds', ...parts.keys}),
    ];
  }

  /// A nested rule set (an object), folded until it holds a rule, its rows
  /// in the form's own label column.
  Widget _ruleSet(FieldSpec field, List<JsonStep> at, KeyWords words) {
    final Object? value = form.read(at);
    return AppSection(
      key: ValueKey<String>('rules:${form.key}:${at.join('.')}'),
      title: words.label,
      tip: fieldTip(words, at),
      open: value != null,
      children: <Widget>[
        problemLines(form.problems(at)),
        InspectControl(
          field: field,
          value: value,
          path: combatPath(at),
          label: words.label,
          schema: schema,
          diagnostics: combatDiagnostics(diagnostics),
          onChanged: (Object? next) => form.put(at, next),
        ),
      ],
    );
  }

  /// A list of nested rule sets typed from the schema.
  Widget _typed(FieldSpec field, List<JsonStep> at, KeyWords words) {
    final Object? value = form.read(at);
    final String path = combatPath(at);
    final List<Diagnostic> problems = combatDiagnostics(diagnostics);
    return Field(
      key: ValueKey<String>('field:${form.key}:${at.join('.')}'),
      label: words.label,
      tip: fieldTip(words, at),
      forId: InspectControl.focusable(field, value) ? controlId(path) : null,
      problems: form.problems(at, nested: true),
      child: InspectControl(
        field: field,
        value: value,
        path: path,
        label: words.label,
        schema: schema,
        diagnostics: problems,
        onChanged: (Object? next) => form.put(at, next),
      ),
    );
  }
}
