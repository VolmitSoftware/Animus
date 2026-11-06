/// The typed editor for an NPC's `modules["animus:combat"]`: rule lists,
/// every settings section with its range, and the server's checks shown
/// beside each field. Edits report the whole module; keys the editor does
/// not know are kept and edited as JSON, and a section left empty is removed.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../logic/npc/combat_settings.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../select/suggest_input.dart';

class CombatEditor extends StatefulWidget {
  const CombatEditor({
    required this.value,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    this.npcIds = const <String>[],
    this.squads = const <String>[],
    this.imported = false,
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

const List<String> _advancedRules = <String>['patterns', 'items', 'permissions', 'groups', 'effects', 'all', 'together', 'events', 'chat'];

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
            child: KeptJsonEditor(value: value, label: combatModule, onChanged: component.onChanged),
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
          if (!edited.contains(entry.key)) _keptRow(<JsonStep>[entry.key], entry.key, known: combatRootKeys.contains(entry.key)),
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
          for (final String key in fields.keys)
            if (!(combatSectionKeys[name] ?? const <String>[]).contains(key)) _keptRow(<JsonStep>[name, key], '$name.$key'),
        ],
      ],
    );
  }

  Widget _rules(String name) {
    final Object? value = _module[name];
    final JsonMap rules = asMap(value);
    final bool seeking = name != 'ignores';
    final List<String> advanced = <String>[
      for (final String key in _advancedRules)
        if (seeking || !combatScanRules.contains(key)) key,
    ];
    final Set<String> typed = <String>{...combatPlainRules, ...advanced};
    final int advancedCount = advanced.where(rules.containsKey).length;
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
              const dom.p(classes: 'animus-gui-help', <Widget>[
                Component.text('Edited as JSON. null removes a rule.'),
              ]),
              for (final String key in advanced)
                GuiRow(
                  key: ValueKey<String>('combat:$name:$key'),
                  label: key,
                  help: _advancedHelp[key],
                  problems: _at(<JsonStep>[name, key], nested: true),
                  child: KeptJsonEditor(value: rules[key], label: '$name.$key', onChanged: (Object? next) => _put(<JsonStep>[name, key], next)),
                ),
            ],
          ),
          for (final String key in rules.keys)
            if (!typed.contains(key)) _keptRow(<JsonStep>[name, key], '$name.$key'),
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
            : KeptJsonEditor(
                value: rule,
                label: 'targeting.lineOfSight',
                onChanged: (Object? next) => _put(const <JsonStep>['targeting', 'lineOfSight'], next),
              ),
      ),
      _switches('targeting'),
    ]);
  }

  Widget _avoidance() {
    final Object? point = _read(const <JsonStep>['avoidance', 'returnPoint']);
    const List<JsonStep> at = <JsonStep>['avoidance', 'returnPoint'];
    return _section('avoidance', 'Avoidance', 'Runs toward returnPoint when set.', <Widget>[
      _number('avoidance', 'range'),
      GuiRow(
        label: 'returnPoint',
        help: 'Where the NPC flees to.',
        problems: _at(at, nested: true),
        child: point == null
            ? dom.div(classes: 'animus-row', <Widget>[
                Button.ghost(
                  label: 'Set return point',
                  size: ButtonSize.small,
                  onPressed: () => _put(at, <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0}),
                ),
              ])
            : point is Map<String, Object?>
            ? _location(at, 'avoidance.returnPoint', onRemove: () => _put(at, null))
            : KeptJsonEditor(value: point, label: 'avoidance.returnPoint', onChanged: (Object? next) => _put(at, next)),
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
            ? KeptJsonEditor(value: raw, label: addLabel, onChanged: (Object? next) => _put(at, next))
            : dom.div(classes: 'animus-combat-list', <Widget>[
                for (int index = 0; index < items.length; index++)
                  dom.div(key: ValueKey<String>('weapon-$index'), classes: 'animus-combat-item', <Widget>[
                    dom.div(classes: 'animus-combat-fields', <Widget>[
                      SuggestInput(
                        value: items[index],
                        label: 'melee.weaponDamage item ${index + 1}',
                        profile: idProfile(const ValueRef.registry(RegistryKind.item)),
                        suggestions: <FieldOption>[for (final String item in _commonItems) FieldOption(item)],
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
                  SuggestInput(
                    value: draft,
                    label: addLabel,
                    placeholder: 'minecraft:iron_sword',
                    suggestions: <FieldOption>[
                      for (final String item in _commonItems)
                        if (!map.containsKey(item)) FieldOption(item),
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
    final Object? weapon = _read(const <JsonStep>['ranged', 'weapon']);
    final Object? effect = _read(const <JsonStep>['ranged', 'effect']);
    final bool potion = weapon == 'potion';
    void choose(String? next) {
      JsonMap ranged = asMap(writeIn(asMap(_module['ranged']), <JsonStep>['weapon'], next));
      if (next == 'potion' && !ranged.containsKey('effect')) {
        ranged = <String, Object?>{
          ...ranged,
          'effect': <String, Object?>{'id': 'minecraft:instant_health'},
        };
      } else if (next != 'potion') {
        ranged = asMap(writeIn(ranged, <JsonStep>['effect'], null));
      }
      _put(const <JsonStep>['ranged'], ranged);
    }

    return _section('ranged', 'Ranged', 'Shots use the configured damage only.', <Widget>[
      GuiRow(
        label: 'weapon',
        help: 'Default arrow.',
        problems: _at(const <JsonStep>['ranged', 'weapon']),
        child: weapon == null || weapon is String
            ? _choice('ranged.weapon', weapon as String?, combatWeapons, 'arrow', choose)
            : KeptJsonEditor(value: weapon, label: 'ranged.weapon', onChanged: (Object? next) => _put(const <JsonStep>['ranged', 'weapon'], next)),
      ),
      if (potion || effect != null)
        GuiRow(
          label: 'effect',
          help: potion ? 'Applied once on hit.' : 'Only a potion has an effect.',
          problems: _at(const <JsonStep>['ranged', 'effect']),
          child: effect is Map<String, Object?> && potion
              ? dom.div(classes: 'animus-combat-fields', <Widget>[
                  SuggestInput(
                    value: asString(effect['id']) ?? '',
                    label: 'ranged.effect.id',
                    profile: idProfile(const ValueRef.registry(RegistryKind.effect)),
                    placeholder: 'minecraft:instant_health',
                    suggestions: <FieldOption>[
                      for (final String id in _commonEffects)
                        FieldOption(id, description: combatHelpfulEffects.contains(id) ? 'helpful, never an attack' : 'an attack'),
                    ],
                    onChanged: (String next) => _put(const <JsonStep>['ranged', 'effect', 'id'], next.isEmpty ? null : next),
                  ),
                ])
              : KeptJsonEditor(value: effect, label: 'ranged.effect', onChanged: (Object? next) => _put(const <JsonStep>['ranged', 'effect'], next)),
        ),
      if (potion && effect is Map<String, Object?>) ...<Widget>[
        _problemList(_at(const <JsonStep>['ranged', 'effect', 'id'])),
        _number('effect', 'amplifier', steps: const <JsonStep>['ranged', 'effect', 'amplifier'], label: 'ranged.effect.amplifier'),
        _number('effect', 'durationTicks', steps: const <JsonStep>['ranged', 'effect', 'durationTicks'], label: 'ranged.effect.durationTicks'),
        for (final String key in effect.keys)
          if (!const <String>['id', 'amplifier', 'durationTicks'].contains(key)) _keptRow(<JsonStep>['ranged', 'effect', key], 'ranged.effect.$key'),
      ],
      for (final String key in const <String>['damage', 'range', 'minimumRange', 'cooldownTicks', 'velocity', 'inaccuracy', 'knockback'])
        _number('ranged', key),
      _switches('ranged'),
    ]);
  }

  Widget _death() {
    const List<JsonStep> at = <JsonStep>['death', 'drops'];
    final Object? raw = _read(at);
    final List<Object?> drops = asList(raw);
    return _section('death', 'Death', 'Each drop rolls its own chance.', <Widget>[
      GuiRow(
        label: 'drops',
        help: 'Amount 1-64, chance 0-1.',
        problems: _at(at),
        child: raw != null && raw is! List<Object?>
            ? KeptJsonEditor(value: raw, label: 'death.drops', onChanged: (Object? next) => _put(at, next))
            : dom.div(classes: 'animus-combat-list', <Widget>[
                for (int index = 0; index < drops.length; index++)
                  dom.div(key: ValueKey<String>('drop-$index'), classes: 'animus-combat-item', <Widget>[
                    if (drops[index] is Map<String, Object?>)
                      dom.div(classes: 'animus-combat-fields', <Widget>[
                        SuggestInput(
                          value: asString(asMap(drops[index])['item']) ?? '',
                          label: 'death.drops[$index].item',
                          profile: idProfile(const ValueRef.registry(RegistryKind.item)),
                          placeholder: 'minecraft:iron_ingot',
                          suggestions: <FieldOption>[for (final String item in _commonItems) FieldOption(item)],
                          onChanged: (String next) => _put(<JsonStep>[...at, index, 'item'], next.isEmpty ? null : next),
                        ),
                        _numberField(<JsonStep>[...at, index, 'amount'], 'death.drops[$index].amount', integer: true, placeholder: 'amount 1'),
                        _numberField(<JsonStep>[...at, index, 'chance'], 'death.drops[$index].chance', integer: false, placeholder: 'chance 1'),
                        GuiIconButton(
                          icon: ArcaneIcon.x(size: IconSize.sm),
                          label: 'Remove drop ${index + 1}',
                          onPressed: () => _put(<JsonStep>[...at, index], null),
                        ),
                      ])
                    else
                      KeptJsonEditor(value: drops[index], label: 'death.drops[$index]', onChanged: (Object? next) => _put(<JsonStep>[...at, index], next)),
                    _problemList(_at(<JsonStep>[...at, index], nested: true)),
                  ]),
                dom.div(classes: 'animus-row', <Widget>[
                  Button.ghost(
                    label: 'Add drop',
                    size: ButtonSize.small,
                    disabled: drops.length >= 32,
                    onPressed: () => _put(at, <Object?>[
                      ...drops,
                      <String, Object?>{'item': 'minecraft:iron_ingot', 'amount': 1, 'chance': 1},
                    ]),
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
    final List<Object?> locations = asList(raw);
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
          problems: _at(at),
          child: raw != null && raw is! List<Object?>
              ? KeptJsonEditor(value: raw, label: 'respawn.locations', onChanged: (Object? next) => _put(at, next))
              : dom.div(classes: 'animus-combat-list', <Widget>[
                  for (int index = 0; index < locations.length; index++)
                    dom.div(key: ValueKey<String>('location-$index'), classes: 'animus-combat-item', <Widget>[
                      if (locations[index] is Map<String, Object?>)
                        _location(<JsonStep>[...at, index], 'respawn.locations[$index]', onRemove: () => _put(<JsonStep>[...at, index], null))
                      else
                        KeptJsonEditor(
                          value: locations[index],
                          label: 'respawn.locations[$index]',
                          onChanged: (Object? next) => _put(<JsonStep>[...at, index], next),
                        ),
                      _problemList(_at(<JsonStep>[...at, index], nested: true)),
                    ]),
                  dom.div(classes: 'animus-row', <Widget>[
                    Button.ghost(
                      label: 'Add location',
                      size: ButtonSize.small,
                      disabled: locations.length >= 16,
                      onPressed: () => _put(at, <Object?>[
                        ...locations,
                        <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0},
                      ]),
                    ),
                  ]),
                ]),
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
      return KeptJsonEditor(value: value, label: label, onChanged: (Object? next) => _put(at, next));
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
          : KeptJsonEditor(value: value, label: '$section.$key', onChanged: (Object? next) => _put(at, next)),
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
          ? KeptJsonEditor(value: raw, label: '$name.categories', onChanged: (Object? next) => _put(at, next))
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
          ? KeptJsonEditor(value: raw, label: label, onChanged: (Object? next) => _put(at, next))
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

  Widget _location(List<JsonStep> at, String label, {required void Function() onRemove}) {
    final JsonMap point = asMap(_read(at));
    final Object? world = point['world'];
    return dom.div(classes: 'animus-combat-fields animus-combat-location', <Widget>[
      if (world == null || world is String)
        SuggestInput(
          value: world as String? ?? '',
          label: '$label.world',
          profile: idProfile(const ValueRef.registry(RegistryKind.world)),
          placeholder: 'world',
          onChanged: (String next) => _put(<JsonStep>[...at, 'world'], next.isEmpty ? null : next),
        )
      else
        KeptJsonEditor(value: world, label: '$label.world', onChanged: (Object? next) => _put(<JsonStep>[...at, 'world'], next)),
      for (final String axis in const <String>['x', 'y', 'z']) _numberField(<JsonStep>[...at, axis], '$label.$axis', integer: false, placeholder: axis),
      GuiIconButton(
        icon: ArcaneIcon.x(size: IconSize.sm),
        label: 'Remove $label',
        onPressed: onRemove,
      ),
      for (final MapEntry<String, Object?> entry in point.entries)
        if (!const <String>['world', 'x', 'y', 'z'].contains(entry.key)) _keptRow(<JsonStep>[...at, entry.key], '$label.${entry.key}'),
    ]);
  }

  /// A key the editor has no field for, kept and edited as JSON.
  Widget _keptRow(List<JsonStep> at, String label, {bool known = false}) => GuiRow(
    key: ValueKey<String>('kept:${at.join('.')}'),
    label: '${at.last}',
    help: known ? 'Kept from the import as written.' : 'Not a combat setting; kept as written. Remove it with null.',
    problems: _at(at, nested: true),
    child: KeptJsonEditor(value: _read(at), label: label, onChanged: (Object? next) => _put(at, next)),
  );

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
