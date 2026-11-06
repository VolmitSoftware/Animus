/// The typed editor for an NPC's `modules["animus:combat"]`: rule lists
/// (advanced rules typed from the NPC schema), every settings section with
/// its range, locations on a grid, and the server's checks shown beside each
/// field. Edits report the whole module; keys the editor does not know are
/// kept under "Other fields", and a section left empty is removed.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/npc/combat_forms.dart';
import '../../logic/npc/combat_settings.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../inspect/typed_values_editor.dart';
import '../mc/item_picker.dart';
import '../select/suggest_input.dart';
import 'combat_locations.dart';

class CombatEditor extends StatefulWidget {
  const CombatEditor({
    required this.value,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    this.npcIds = const <String>[],
    this.squads = const <String>[],
    this.imported = false,
    this.origin,
    this.schema,
    super.key,
  });

  /// The module object.
  final Object? value;

  /// The whole new module; null removes it.
  final void Function(Object? value) onChanged;

  /// All of the document's diagnostics; the editor shows the module's.
  final List<Diagnostic> diagnostics;

  /// NPC ids in the workspace, offered for `npcs` rules.
  final List<String> npcIds;

  /// Squad names in the workspace, offered for `squads` rules and the squad name.
  final List<String> squads;

  /// Whether the NPC was imported, so combat also needs `reviewAccepted`.
  final bool imported;

  /// The NPC's x, y and z, drawn on the location grids.
  final (double, double, double)? origin;

  /// The NPC schema the advanced rules are typed from; the embedded one when null.
  final SchemaView? schema;

  @override
  State<CombatEditor> createState() => _CombatEditorState();
}

/// What each advanced rule holds, for its help line.
const Map<String, String> _advancedHelp = <String, String>{
  'patterns': 'players, npcs, names: regex lists.',
  'items': 'mainHand, offHand, worn: item id patterns.',
  'permissions': 'Permission nodes an online player has.',
  'groups': 'Permission groups an online player is in.',
  'effects': 'Status effect ids.',
  'all': 'One candidate matches every set.',
  'together': 'Sets matched by different candidates together.',
  'events': 'Attackers in nearby fights become targets.',
  'chat': 'Chat text that targets the speaker.',
};


const Map<String, String> _ruleHelp = <String, String>{
  'targets': 'Any matching rule makes a target.',
  'ignores': 'Never targeted. Beats targets.',
  'avoids': 'Runs from these within avoidance.range.',
};

const List<String> _commonItems = <String>[
  'minecraft:wooden_sword',
  'minecraft:stone_sword',
  'minecraft:iron_sword',
  'minecraft:golden_sword',
  'minecraft:diamond_sword',
  'minecraft:netherite_sword',
  'minecraft:iron_axe',
  'minecraft:diamond_axe',
  'minecraft:netherite_axe',
  'minecraft:trident',
  'minecraft:mace',
  'minecraft:bow',
  'minecraft:crossbow',
  'minecraft:stick',
  'minecraft:iron_ingot',
  'minecraft:gold_ingot',
  'minecraft:emerald',
  'minecraft:rotten_flesh',
  'minecraft:bone',
  'minecraft:arrow',
];

const List<String> _commonEffects = <String>[
  ...combatHelpfulEffects,
  'minecraft:instant_damage',
  'minecraft:poison',
  'minecraft:wither',
  'minecraft:slowness',
  'minecraft:weakness',
  'minecraft:mining_fatigue',
  'minecraft:nausea',
  'minecraft:blindness',
  'minecraft:hunger',
  'minecraft:levitation',
  'minecraft:glowing',
];

String _plain(num value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();

class _CombatEditorState extends State<CombatEditor> {
  /// Text typed into "add" fields, by the field's label.
  final Map<String, String> _drafts = <String, String>{};

  JsonMap get _module => asMap(component.value);
  SchemaView get _schema => component.schema ?? InspectSchemas.embedded.npc;
  List<Diagnostic> get _problems => combatDiagnostics(component.diagnostics);

  Object? _read(List<JsonStep> steps) => readIn(component.value, steps);

  /// Writes [value] at [steps]; an empty map or list under a key removes the
  /// key, and so does every map or list it leaves empty above it.
  void _put(List<JsonStep> steps, Object? value) {
    bool empty(Object? item) => (item is Map<String, Object?> && item.isEmpty) || (item is List<Object?> && item.isEmpty);
    Object? next = writeIn(_module, steps, steps.last is String && empty(value) ? null : value);
    for (int depth = steps.length - 1; depth >= 1; depth--) {
      final List<JsonStep> prefix = steps.sublist(0, depth);
      if (prefix.last is! String || !empty(readIn(next, prefix))) break;
      next = writeIn(next, prefix, null);
    }
    component.onChanged(next);
  }

  List<Diagnostic> _at(List<JsonStep> steps, {bool nested = false}) => diagnosticsAt(_problems, combatPath(steps), nested: nested);

  void _draft(String label, String text) => setState(() => _drafts[label] = text);

  @override
  Widget build(BuildContext context) {
    final Object? value = component.value;
    final int count = _problems.length;
    return dom.section(
      classes: 'animus-combat-editor',
      attributes: const <String, String>{'aria-label': 'Combat editor'},
      <Widget>[
        if (count > 0)
          dom.p(
            classes: 'animus-gui-problem is-error',
            attributes: const <String, String>{'role': 'status'},
            <Widget>[Component.text(count == 1 ? '1 combat setting needs attention.' : '$count combat settings need attention.')],
          ),
        if (value is! Map<String, Object?>)
          GuiRow(
            label: combatModule,
            help: 'The combat module must be an object.',
            problems: _at(const <JsonStep>[]),
            child: TypedValueEditor(value: value, label: combatModule, onChanged: component.onChanged),
          )
        else ...<Widget>[
          _enabled(),
          for (final String name in combatRuleSections) _rules(name),
          _targeting(),
          _avoidance(),
          _section('squad', 'Squad', 'Members never target each other.', <Widget>[
            _text(
              'squad',
              'name',
              suggestions: <FieldOption>[for (final String squad in component.squads) FieldOption(squad, description: 'squad in this workspace')],
            ),
            _number('squad', 'alertRange'),
          ]),
          _section('guard', 'Guard', 'Set player or npc, not both.', <Widget>[
            _text('guard', 'player', placeholder: '00000000-0000-0000-0000-000000000000', help: 'Lowercase UUID.'),
            _text('guard', 'npc', placeholder: '00000000-0000-0000-0000-000000000000', help: 'Lowercase UUID.'),
            for (final String key in const <String>['followDistance', 'pointRange', 'catchUpDistance', 'replanDistance']) _number('guard', key),
            _switches('guard'),
          ]),
          _section('pursuit', 'Pursuit', 'leashRange counts from the fight start.', <Widget>[
            for (final String key in const <String>['speed', 'giveUpRange', 'leashRange', 'replanTicks']) _number('pursuit', key),
            _text('pursuit', 'region', help: 'Protection region. Targets outside it are skipped.'),
            _switches('pursuit'),
          ]),
          _melee(),
          _ranged(),
          _section('defense', 'Defense', '', <Widget>[
            for (final String key in const <String>['maximumHealth', 'armor', 'protectFromRange', 'healRateTicks', 'invulnerableTicks'])
              _number('defense', key),
            _switches('defense'),
          ]),
          _death(),
          _respawn(),
          _section(
            'greetings',
            'Greetings',
            'Hostile to targets, friendly to others.',
            <Widget>[
              _text('greetings', 'friendly', help: 'Up to 256 characters.'),
              _text('greetings', 'hostile', help: 'Up to 256 characters.'),
              _number('greetings', 'range'),
              _number('greetings', 'cooldownTicks'),
            ],
          ),
        ],
      ],
    );
  }

  // Sections.

  Widget _enabled() {
    final JsonMap module = _module;
    final Set<String> edited = <String>{'enabled', 'reviewAccepted', ...combatRuleSections, ...combatSectionKeys.keys};
    return GuiSection(
      title: 'Enabled',
      children: <Widget>[
        _problemList(_at(const <JsonStep>[])),
        dom.div(classes: 'animus-combat-switches', <Widget>[
          _checkbox(
            label: 'enabled',
            value: module['enabled'] == true,
            description: 'Combat runs only when true.',
            problems: _at(const <JsonStep>['enabled']),
            onChanged: (bool next) => _put(const <JsonStep>['enabled'], next),
          ),
          if (component.imported || module.containsKey('reviewAccepted'))
            _checkbox(
              label: 'reviewAccepted',
              value: module['reviewAccepted'] == true,
              description: 'Imported NPCs fight only once reviewed.',
              problems: _at(const <JsonStep>['reviewAccepted']),
              onChanged: (bool next) => _put(const <JsonStep>['reviewAccepted'], next),
            ),
        ]),
        for (final MapEntry<String, Object?> entry in module.entries)
          if (!edited.contains(entry.key) && combatRootKeys.contains(entry.key)) _keptRow(<JsonStep>[entry.key], entry.key, known: true),
        _others(const <JsonStep>[], module, combatRootKeys.toSet()),
        dom.div(classes: 'animus-row', <Widget>[Button.ghost(label: 'Remove combat', size: ButtonSize.small, onPressed: () => component.onChanged(null))]),
      ],
    );
  }

  Widget _section(String name, String title, String help, List<Widget> children) {
    final Object? value = _module[name];
    final JsonMap fields = asMap(value);
    return GuiSection(
      key: ValueKey<String>('combat:$name'),
      title: title,
      count: fields.isEmpty ? null : fields.length,
      open: value != null,
      children: <Widget>[
        if (help.isNotEmpty) dom.p(classes: 'animus-gui-help', <Widget>[Component.text(help)]),
        if (value != null && value is! Map<String, Object?>)
          _keptRow(<JsonStep>[name], name)
        else ...<Widget>[
          _problemList(_at(<JsonStep>[name])),
          ...children,
          _others(<JsonStep>[name], fields, (combatSectionKeys[name] ?? const <String>[]).toSet()),
        ],
      ],
    );
  }

  Widget _rules(String name) {
    final Object? value = _module[name];
    final JsonMap rules = asMap(value);
    final List<FieldSpec> advanced = combatAdvancedRuleFields(_schema, name);
    final Set<String> typed = <String>{...combatPlainRules, for (final FieldSpec field in advanced) field.key};
    final int advancedCount = advanced.where((FieldSpec field) => rules.containsKey(field.key)).length;
    final String title = '${name[0].toUpperCase()}${name.substring(1)}';
    return GuiSection(
      key: ValueKey<String>('combat:$name'),
      title: title,
      count: rules.isEmpty ? null : rules.length,
      open: value != null,
      children: <Widget>[
        dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_ruleHelp[name]!)]),
        if (value != null && value is! Map<String, Object?>)
          _keptRow(<JsonStep>[name], name)
        else ...<Widget>[
          _problemList(_at(<JsonStep>[name])),
          _categories(name, rules),
          _strings(
            <JsonStep>[name, 'entityTypes'],
            'entityTypes',
            help: 'Such as minecraft:zombie.',
            suggestions: <FieldOption>[for (final String type in combatCommonEntityTypes) FieldOption(type)],
          ),
          _strings(<JsonStep>[name, 'players'], 'players', help: 'Names (any case) or UUIDs.'),
          _strings(
            <JsonStep>[name, 'npcs'],
            'npcs',
            help: 'NPC ids or display names.',
            suggestions: <FieldOption>[for (final String id in component.npcIds) FieldOption(id, description: 'NPC in this workspace')],
          ),
          _strings(<JsonStep>[name, 'names'], 'names', help: 'Custom names of mobs.'),
          _strings(
            <JsonStep>[name, 'squads'],
            'squads',
            help: "Other NPCs' squads.",
            suggestions: <FieldOption>[for (final String squad in component.squads) FieldOption(squad, description: 'squad in this workspace')],
          ),
          dom.div(classes: 'animus-combat-switches', <Widget>[
            _checkbox(
              label: '$name.owner',
              value: rules['owner'] == true,
              description: 'Matches the NPC owner.',
              problems: _at(<JsonStep>[name, 'owner']),
              onChanged: (bool next) => _put(<JsonStep>[name, 'owner'], next ? true : null),
            ),
          ]),
          _number(
            'rule',
            'healthAbove',
            steps: <JsonStep>[name, 'healthAbove'],
            label: '$name.healthAbove',
            help: 'Percent of max health, 0-100.',
          ),
          _number(
            'rule',
            'healthBelow',
            steps: <JsonStep>[name, 'healthBelow'],
            label: '$name.healthBelow',
            help: 'Percent of max health, 0-100.',
          ),
          GuiSection(
            key: ValueKey<String>('combat:$name:advanced'),
            title: 'Advanced rules',
            count: advancedCount == 0 ? null : advancedCount,
            open: advancedCount > 0,
            children: <Widget>[
              for (final FieldSpec field in advanced) _advancedRule(name, field, rules[field.key]),
            ],
          ),
          _others(<JsonStep>[name], rules, typed),
        ],
      ],
    );
  }

  Widget _targeting() {
    final Object? rule = _read(const <JsonStep>['targeting', 'lineOfSight']);
    return _section('targeting', 'Targeting', 'Capped by maximumScanRange in config.toml.', <Widget>[
      for (final String key in const <String>['scanRange', 'scanIntervalTicks', 'reactionTicks', 'memoryTicks']) _number('targeting', key),
      GuiRow(
        label: 'lineOfSight',
        help: 'field_of_view is a 140-degree cone plus line of sight.',
        problems: _at(const <JsonStep>['targeting', 'lineOfSight']),
        child: rule == null || rule is String
            ? _choice(
                'targeting.lineOfSight',
                rule as String?,
                combatLineOfSight,
                'required',
                (String? next) => _put(const <JsonStep>['targeting', 'lineOfSight'], next),
              )
            : TypedValueEditor(
                value: rule,
                label: 'targeting.lineOfSight',
                onChanged: (Object? next) => _put(const <JsonStep>['targeting', 'lineOfSight'], next),
              ),
      ),
      _switches('targeting'),
    ]);
  }

  Widget _avoidance() {
    const List<JsonStep> at = <JsonStep>['avoidance', 'returnPoint'];
    final Object? point = _read(at);
    return _section('avoidance', 'Avoidance', 'Runs toward returnPoint when set.', <Widget>[
      _number('avoidance', 'range'),
      GuiRow(
        label: 'returnPoint',
        tip: 'Where the NPC flees to. Click the grid or drag the point; arrows nudge it.',
        problems: _at(at),
        child: point != null && point is! Map<String, Object?>
            ? TypedValueEditor(value: point, label: 'avoidance.returnPoint', onChanged: (Object? next) => _put(at, next))
            : CombatLocationsEditor(
                points: <Object?>[?point],
                maximum: 1,
                label: 'Return point',
                noun: 'Return point',
                path: combatPath(at),
                origin: component.origin,
                diagnostics: _problems,
                onChanged: (List<Object?> next) => _put(at, next.isEmpty ? null : next.first),
              ),
      ),
      _switches('avoidance'),
    ]);
  }

  Widget _melee() {
    const List<JsonStep> at = <JsonStep>['melee', 'weaponDamage'];
    final Object? raw = _read(at);
    final JsonMap map = asMap(raw);
    final List<String> items = map.keys.toList();
    const String addLabel = 'melee.weaponDamage';
    final String draft = _drafts[addLabel] ?? '';
    return _section('melee', 'Melee', 'Reach counts from the body center.', <Widget>[
      for (final String key in const <String>['damage', 'reach', 'cooldownTicks', 'knockback']) _number('melee', key),
      _switches('melee'),
      GuiRow(
        label: 'weaponDamage',
        help: 'Damage per held item, 0-100. Overrides melee and ranged damage.',
        problems: _at(at),
        child: raw != null && raw is! Map<String, Object?>
            ? TypedValueEditor(value: raw, label: addLabel, onChanged: (Object? next) => _put(at, next))
            : dom.div(classes: 'animus-combat-list', <Widget>[
                for (int index = 0; index < items.length; index++)
                  dom.div(key: ValueKey<String>('weapon-$index'), classes: 'animus-combat-item', <Widget>[
                    dom.div(classes: 'animus-combat-fields', <Widget>[
                      ItemPicker(
                        value: items[index],
                        label: 'melee.weaponDamage item ${index + 1}',
                        preferred: _commonItems,
                        onChanged: (String next) {
                          if (next.isEmpty || (next != items[index] && map.containsKey(next))) return;
                          _put(at, renameKey(map, items[index], next));
                        },
                      ),
                      _numberField(<JsonStep>[...at, items[index]], 'melee.weaponDamage.${items[index]}', integer: false, placeholder: '1'),
                      GuiIconButton(
                        icon: ArcaneIcon.x(size: IconSize.sm),
                        label: 'Remove weapon ${items[index]}',
                        onPressed: () => _put(at, asMap(writeIn(map, <JsonStep>[items[index]], null))),
                      ),
                    ]),
                    _problemList(_at(<JsonStep>[...at, items[index]])),
                  ]),
                dom.div(classes: 'animus-combat-fields', <Widget>[
                  ItemPicker(
                    value: draft,
                    label: addLabel,
                    placeholder: 'minecraft:iron_sword',
                    preferred: <String>[
                      for (final String item in _commonItems)
                        if (!map.containsKey(item)) item,
                    ],
                    onChanged: (String next) => _draft(addLabel, next.trim()),
                  ),
                  Button.ghost(
                    label: 'Add weapon',
                    size: ButtonSize.small,
                    disabled: draft.isEmpty || map.containsKey(draft),
                    onPressed: () {
                      _put(at, <String, Object?>{...map, draft: 1});
                      setState(() => _drafts.remove(addLabel));
                    },
                  ),
                ]),
              ]),
      ),
    ]);
  }

  Widget _ranged() {
    const List<JsonStep> weaponAt = <JsonStep>['ranged', 'weapon'];
    const List<JsonStep> effectAt = <JsonStep>['ranged', 'effect'];
    final Object? weapon = _read(weaponAt);
    final Object? effect = _read(effectAt);
    final bool potion = weapon == 'potion';
    JsonMap ranged() => asMap(_module['ranged']);
    return _section('ranged', 'Ranged', 'Shots use the configured damage only.', <Widget>[
      GuiRow(
        label: 'weapon',
        help: 'Default arrow.',
        problems: _at(weaponAt),
        child: weapon == null || weapon is String
            ? _choice('ranged.weapon', weapon as String?, combatWeapons, 'arrow', (String? next) => _put(const <JsonStep>['ranged'], withRangedWeapon(ranged(), next)))
            : TypedValueEditor(value: weapon, label: 'ranged.weapon', onChanged: (Object? next) => _put(weaponAt, next)),
      ),
      if (effect is Map<String, Object?>) ...<Widget>[
        GuiRow(
          label: 'effect',
          help: potion ? 'Applied once on hit.' : null,
          tip: 'The potion effect a thrown potion applies.',
          problems: _at(effectAt),
          child: dom.div(classes: 'animus-combat-fields', <Widget>[
            if (effect['id'] == null || effect['id'] is String)
              SuggestInput(
                value: asString(effect['id']) ?? '',
                label: 'ranged.effect.id',
                placeholder: 'minecraft:instant_health',
                suggestions: <FieldOption>[
                  for (final String id in _commonEffects)
                    FieldOption(id, description: combatHelpfulEffects.contains(id) ? 'helpful, never an attack' : 'an attack'),
                ],
                onChanged: (String next) => _put(const <JsonStep>['ranged'], withRangedEffect(ranged(), 'id', next.trim().isEmpty ? null : next.trim())),
              )
            else
              TypedValueEditor(value: effect['id'], label: 'ranged.effect.id', onChanged: (Object? next) => _put(const <JsonStep>['ranged', 'effect', 'id'], next)),
            if (!potion) Button.ghost(label: 'Remove effect', size: ButtonSize.small, onPressed: () => _put(effectAt, null)),
          ]),
        ),
        _problemList(_at(const <JsonStep>['ranged', 'effect', 'id'])),
        _number('effect', 'amplifier', steps: const <JsonStep>['ranged', 'effect', 'amplifier'], label: 'ranged.effect.amplifier'),
        _number('effect', 'durationTicks', steps: const <JsonStep>['ranged', 'effect', 'durationTicks'], label: 'ranged.effect.durationTicks'),
        _others(effectAt, effect, const <String>{'id', 'amplifier', 'durationTicks'}),
      ] else if (effect != null)
        _keptRow(effectAt, 'ranged.effect'),
      for (final String key in const <String>['damage', 'range', 'minimumRange', 'cooldownTicks', 'velocity', 'inaccuracy', 'knockback'])
        _number('ranged', key),
      _switches('ranged'),
    ]);
  }

  Widget _death() {
    const List<JsonStep> at = <JsonStep>['death', 'drops'];
    final Object? raw = _read(at);
    final List<Object?> drops = asList(raw);
    void write(List<Object?> next) => _put(at, next);
    return _section('death', 'Death', 'Each drop rolls its own chance.', <Widget>[
      GuiRow(
        label: 'drops',
        help: 'Amount 1-64, chance 0-1.',
        problems: _at(at),
        child: raw != null && raw is! List<Object?>
            ? TypedValueEditor(value: raw, label: 'death.drops', onChanged: (Object? next) => _put(at, next))
            : dom.div(classes: 'animus-combat-list', <Widget>[
                for (int index = 0; index < drops.length; index++)
                  dom.div(
                    key: ValueKey<String>('drop-$index'),
                    classes: 'animus-combat-item',
                    attributes: <String, String>{'role': 'group', 'aria-label': 'Drop ${index + 1}'},
                    <Widget>[
                      if (drops[index] case final Map<String, Object?> drop) ...<Widget>[
                        dom.div(classes: 'animus-combat-fields', <Widget>[
                          ItemPicker(
                            value: asString(drop['item']) ?? '',
                            label: 'death.drops[$index].item',
                            placeholder: 'minecraft:iron_ingot',
                            preferred: _commonItems,
                            onChanged: (String next) => write(withDropField(drops, index, 'item', next.trim())),
                          ),
                          _numberField(<JsonStep>[...at, index, 'amount'], 'death.drops[$index].amount', integer: true, placeholder: 'amount 1'),
                          _numberField(<JsonStep>[...at, index, 'chance'], 'death.drops[$index].chance', integer: false, placeholder: 'chance 1'),
                          GuiIconButton(
                            icon: ArcaneIcon.x(size: IconSize.sm),
                            label: 'Remove drop ${index + 1}',
                            onPressed: () => write(<Object?>[...drops]..removeAt(index)),
                          ),
                        ]),
                        _others(<JsonStep>[...at, index], drop, const <String>{'item', 'amount', 'chance'}),
                      ] else
                        TypedValueEditor(value: drops[index], label: 'death.drops[$index]', onChanged: (Object? next) => _put(<JsonStep>[...at, index], next)),
                      _problemList(_at(<JsonStep>[...at, index], nested: true)),
                    ],
                  ),
                dom.div(classes: 'animus-row', <Widget>[
                  Button.ghost(
                    label: 'Add drop',
                    size: ButtonSize.small,
                    disabled: drops.length >= 32,
                    onPressed: () => write(<Object?>[...drops, newCombatDrop()]),
                  ),
                ]),
              ]),
      ),
      _number('death', 'experience'),
      _switches('death'),
    ]);
  }

  Widget _respawn() {
    const List<JsonStep> at = <JsonStep>['respawn', 'locations'];
    final Object? raw = _read(at);
    final Object? delay = _read(const <JsonStep>['respawn', 'delayTicks']);
    final bool deleting = _read(const <JsonStep>['respawn', 'deleteOnDeath']) == true;
    return _section(
      'respawn',
      'Respawn',
      'delayTicks -1 never respawns.',
      <Widget>[
        _number('respawn', 'delayTicks'),
        GuiRow(
          label: 'locations',
          help: 'Up to 16.',
          tip: 'One is picked at random on each respawn. Click the grid to move the picked location.',
          problems: _at(at),
          child: raw != null && raw is! List<Object?>
              ? TypedValueEditor(value: raw, label: 'respawn.locations', onChanged: (Object? next) => _put(at, next))
              : CombatLocationsEditor(
                  points: asList(raw),
                  label: 'Respawn locations',
                  noun: 'Location',
                  path: combatPath(at),
                  origin: component.origin,
                  diagnostics: _problems,
                  onChanged: (List<Object?> next) => _put(at, next),
                ),
        ),
        _switches(
          'respawn',
          disabled: <String>{if (delay != -1 && !deleting) 'deleteOnDeath'},
          notes: const <String, String>{'deleteOnDeath': 'Deletes the NPC on death; needs delayTicks -1.'},
        ),
      ],
    );
  }

  // Fields.

  Widget _number(String section, String key, {List<JsonStep>? steps, String? label, String? help}) {
    final List<JsonStep> at = steps ?? <JsonStep>[section, key];
    final CombatRange range = combatRange(section, key)!;
    return GuiRow(
      key: ValueKey<String>('combat:${at.join('.')}'),
      label: key,
      help: help ?? '${range.help}${range.integer ? ' (whole number)' : ''}.',
      problems: _at(at),
      child: _numberField(at, label ?? '$section.$key', integer: range.integer, placeholder: help == null ? _plain(range.fallback) : null),
    );
  }

  Widget _numberField(List<JsonStep> at, String label, {required bool integer, String? placeholder}) {
    final Object? value = _read(at);
    if (value != null && value is! num) {
      return TypedValueEditor(value: value, label: label, onChanged: (Object? next) => _put(at, next));
    }
    return GuiNumberInput(value: value as num?, integer: integer, label: label, placeholder: placeholder, onChanged: (num? next) => _put(at, next));
  }

  Widget _text(String section, String key, {List<FieldOption> suggestions = const <FieldOption>[], String? placeholder, String? help}) {
    final List<JsonStep> at = <JsonStep>[section, key];
    final Object? value = _read(at);
    return GuiRow(
      key: ValueKey<String>('combat:$section.$key'),
      label: key,
      help: help,
      problems: _at(at),
      child: value == null || value is String
          ? SuggestInput(
              value: value as String? ?? '',
              label: '$section.$key',
              placeholder: placeholder,
              suggestions: suggestions,
              onChanged: (String next) => _put(at, next.isEmpty ? null : next),
            )
          : TypedValueEditor(value: value, label: '$section.$key', onChanged: (Object? next) => _put(at, next)),
    );
  }

  /// The section's boolean fields; a field set to a non-boolean is kept as JSON.
  Widget _switches(String section, {Set<String> disabled = const <String>{}, Map<String, String> notes = const <String, String>{}}) {
    final Map<String, bool> defaults = combatBooleans[section] ?? const <String, bool>{};
    return dom.div(classes: 'animus-combat-switches', <Widget>[
      for (final MapEntry<String, bool> entry in defaults.entries)
        if (_read(<JsonStep>[section, entry.key]) case final Object? value when value == null || value is bool)
          _checkbox(
            label: '$section.${entry.key}',
            value: (value as bool?) ?? entry.value,
            description: '${notes[entry.key] ?? ''}${notes.containsKey(entry.key) ? ' ' : ''}Default ${entry.value ? 'on' : 'off'}.',
            disabled: disabled.contains(entry.key),
            problems: _at(<JsonStep>[section, entry.key]),
            onChanged: (bool next) => _put(<JsonStep>[section, entry.key], next),
          )
        else
          _keptRow(<JsonStep>[section, entry.key], '$section.${entry.key}'),
    ]);
  }

  Widget _checkbox({
    required String label,
    required bool value,
    required void Function(bool value) onChanged,
    String? description,
    bool disabled = false,
    List<Diagnostic> problems = const <Diagnostic>[],
  }) => dom.div(key: ValueKey<String>('switch:$label'), classes: 'animus-combat-switch', <Widget>[
    ArcaneCheckbox(checked: value, label: label, description: description, size: ComponentSize.sm, disabled: disabled, onChanged: onChanged),
    _problemList(problems),
  ]);

  Widget _choice(String label, String? value, List<String> options, String fallback, void Function(String? value) onChanged) => GuiSelect(
        value: value,
        options: options,
        allowNone: true,
        noneLabel: 'default ($fallback)',
        hiddenLabel: label,
        onChanged: onChanged,
      );

  Widget _categories(String name, JsonMap rules) {
    final List<JsonStep> at = <JsonStep>[name, 'categories'];
    final Object? raw = rules['categories'];
    final List<String> chosen = stringList(raw);
    return GuiRow(
      label: 'categories',
      help: 'A mob is in mobs plus monsters or animals.',
      problems: _at(at, nested: true),
      child: raw != null && (raw is! List<Object?> || raw.any((Object? item) => item is! String))
          ? TypedValueEditor(value: raw, label: '$name.categories', onChanged: (Object? next) => _put(at, next))
          : dom.div(
              classes: 'animus-combat-chips',
              attributes: <String, String>{'role': 'group', 'aria-label': '$name.categories'},
              <Widget>[
                for (final String category in combatCategories)
                  chosen.contains(category)
                      ? Button.primary(
                          label: category,
                          size: ButtonSize.small,
                          attributes: const <String, String>{'aria-pressed': 'true'},
                          onPressed: () => _put(at, <String>[
                            for (final String item in chosen)
                              if (item != category) item,
                          ]),
                        )
                      : Button.outline(
                          label: category,
                          size: ButtonSize.small,
                          attributes: const <String, String>{'aria-pressed': 'false'},
                          onPressed: () => _put(at, <String>[...chosen, category]),
                        ),
              ],
            ),
    );
  }

  /// An editable string list with an "add" field labelled [key] under its section.
  Widget _strings(List<JsonStep> at, String key, {String? help, List<FieldOption> suggestions = const <FieldOption>[]}) {
    final Object? raw = _read(at);
    final String label = '${at.first}.$key';
    final List<String> items = stringList(raw);
    final String draft = _drafts[label] ?? '';
    final bool kept = raw != null && (raw is! List<Object?> || raw.any((Object? item) => item is! String));
    return GuiRow(
      key: ValueKey<String>('combat:$label'),
      label: key,
      help: help,
      problems: _at(at, nested: true),
      child: kept
          ? TypedValueEditor(value: raw, label: label, onChanged: (Object? next) => _put(at, next))
          : dom.div(classes: 'animus-combat-list', <Widget>[
              for (int index = 0; index < items.length; index++)
                dom.div(key: ValueKey<String>('$label-$index'), classes: 'animus-combat-fields', <Widget>[
                  SuggestInput(
                    value: items[index],
                    label: '$label[$index]',
                    suggestions: suggestions,
                    onChanged: (String next) => _put(<JsonStep>[...at, index], next),
                  ),
                  GuiIconButton(
                    icon: ArcaneIcon.x(size: IconSize.sm),
                    label: 'Remove ${items[index]} from $label',
                    onPressed: () => _put(at, <String>[...items]..removeAt(index)),
                  ),
                ]),
              dom.div(classes: 'animus-combat-fields', <Widget>[
                SuggestInput(
                  value: draft,
                  label: label,
                  suggestions: <FieldOption>[
                    for (final FieldOption option in suggestions)
                      if (!items.contains(option.label)) option,
                  ],
                  onChanged: (String next) => _draft(label, next.trim()),
                ),
                Button.ghost(
                  label: 'Add',
                  size: ButtonSize.small,
                  disabled: draft.isEmpty || items.contains(draft),
                  attributes: <String, String>{'aria-label': 'Add to $label'},
                  onPressed: () {
                    _put(at, <String>[...items, draft]);
                    setState(() => _drafts.remove(label));
                  },
                ),
              ]),
            ]),
    );
  }

  /// One advanced rule, typed from the NPC schema: nested rule sets, pattern
  /// and item lists, fight events.
  Widget _advancedRule(String name, FieldSpec field, Object? value) {
    final List<JsonStep> at = <JsonStep>[name, field.key];
    final String path = combatPath(at);
    return InspectRow(
      key: ValueKey<String>('combat:$name:${field.key}'),
      label: field.key,
      labelFor: controlId(path),
      tip: _advancedHelp[field.key],
      problems: fieldProblems(field, value, path, _problems, nested: true),
      child: InspectControl(
        field: field,
        value: value,
        path: path,
        schema: _schema,
        diagnostics: _problems,
        onChanged: (Object? next) => _put(<JsonStep>[name], withCombatRule(asMap(_read(<JsonStep>[name])), field.key, next)),
      ),
    );
  }

  /// A known key whose value the editor has no field for: an imported record,
  /// or a setting of the wrong type, edited by kind.
  Widget _keptRow(List<JsonStep> at, String label, {bool known = false}) => GuiRow(
    key: ValueKey<String>('kept:${at.join('.')}'),
    label: '${at.last}',
    tip: known ? 'Kept from the import as written.' : 'The server expects another type here.',
    problems: _at(at, nested: true),
    child: dom.div(classes: 'animus-combat-fields', <Widget>[
      TypedValueEditor(value: _read(at), label: label, onChanged: (Object? next) => _put(at, next)),
      GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove $label', onPressed: () => _put(at, null)),
    ]),
  );

  /// Keys of [object] (at [at]) outside [known]: the server refuses them, so
  /// they are listed apart where they can be renamed, edited or removed.
  Widget _others(List<JsonStep> at, JsonMap object, Set<String> known) {
    final JsonMap unknown = unknownEntries(object, known);
    if (unknown.isEmpty) return const dom.span(<Widget>[]);
    final String label = at.isEmpty ? 'Other combat fields' : 'Other ${at.join('.')} fields';
    return GuiRow(
      key: ValueKey<String>('others:${at.join('.')}'),
      label: 'Other fields',
      tip: 'Not combat settings. The server refuses them; rename or remove them.',
      problems: <Diagnostic>[for (final String key in unknown.keys) ..._at(<JsonStep>[...at, key], nested: true)],
      child: KeyValueEditor(
        value: unknown,
        label: label,
        onChanged: (JsonMap next) {
          final JsonMap merged = withUnknownEntries(object, known, next);
          if (at.isEmpty) {
            component.onChanged(merged);
          } else {
            _put(at, merged);
          }
        },
      ),
    );
  }

  Widget _problemList(List<Diagnostic> problems) => problems.isEmpty
      ? const dom.span(<Widget>[])
      : dom.div(classes: 'animus-combat-problems', <Widget>[
          for (final Diagnostic problem in problems)
            dom.span(
              classes: 'animus-gui-problem is-${problem.severity.name}',
              attributes: const <String, String>{'role': 'note'},
              <Widget>[
                dom.code(<Widget>[Component.text(problem.path)]),
                Component.text(' ${problem.message}'),
              ],
            ),
        ]);
}
