/// The Advanced part of `animus:combat` in the Combat tab: who it fights in
/// detail (Targets, Ignores, Avoids), then how (Targeting, Melee, Ranged,
/// Pursuit, Avoidance, Guard, Squad, Greetings). Every section is closed
/// until it holds a value; each row is labelled in words, ticks also read as
/// time, and the engine key is only in the row's (i) tip. Keys the module
/// does not know stay under "Other fields".
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

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
import '../../ui/icon_button.dart';
import '../inspect/typed_values_editor.dart';
import '../mc/item_picker.dart';
import 'combat_fields.dart';
import 'combat_locations.dart';
import 'combat_rules.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';
import '../gui/gui_stack.dart';

const List<String> _commonWeapons = <String>[
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

/// Every section of the combat module, in reading order.
class CombatSettingsPart extends StatelessWidget {
  const CombatSettingsPart({
    required this.form,
    required this.schema,
    required this.diagnostics,
    this.npcIds = const <String>[],
    this.squads = const <String>[],
    this.origin,
    super.key,
  });

  final ModuleForm form;
  final SchemaView schema;
  final List<Diagnostic> diagnostics;
  final List<String> npcIds;
  final List<String> squads;
  final (double, double, double)? origin;

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
        for (final String name in combatRuleSections)
          CombatRules(
            key: ValueKey<String>('combat-rules:$name'),
            form: form,
            name: name,
            schema: schema,
            diagnostics: diagnostics,
            npcIds: npcIds,
            squads: squads,
          ),
        _section('targeting', <Widget>[
          for (final String key in const <String>['scanRange', 'scanIntervalTicks', 'reactionTicks', 'memoryTicks']) _number('targeting', key),
          choiceField(
            form,
            const <JsonStep>['targeting', 'lineOfSight'],
            combatWords('targeting.lineOfSight'),
            options: combatLineOfSight,
            labels: combatLineOfSightLabels,
            fallback: 'required',
          ),
          ..._switches('targeting'),
          switchField(form, const <JsonStep>['targetableByMobs'], combatWords('targetableByMobs'), fallback: false),
        ], also: 'targetableByMobs'),
        _section('melee', <Widget>[
          ..._switches('melee').take(1),
          for (final String key in const <String>['damage', 'reach', 'cooldownTicks', 'knockback']) _number('melee', key),
          ..._switches('melee').skip(1),
          _WeaponDamage(form: form),
          switchField(form, const <JsonStep>['victimDrops'], combatWords('victimDrops'), fallback: true),
        ], also: 'victimDrops'),
        _ranged(),
        _section('pursuit', <Widget>[
          for (final String key in const <String>['speed', 'giveUpRange', 'leashRange', 'replanTicks']) _number('pursuit', key),
          ..._switches('pursuit'),
          textField(form, const <JsonStep>['pursuit', 'region'], combatWords('pursuit.region')),
        ]),
        _section('avoidance', <Widget>[
          _number('avoidance', 'range'),
          ..._switches('avoidance'),
          _returnPoint(),
        ]),
        _section('guard', <Widget>[
          textField(form, const <JsonStep>['guard', 'player'], combatWords('guard.player'), placeholder: 'Player UUID'),
          textField(form, const <JsonStep>['guard', 'npc'], combatWords('guard.npc'), placeholder: 'NPC UUID'),
          for (final String key in const <String>['followDistance', 'pointRange', 'catchUpDistance', 'replanDistance']) _number('guard', key),
          ..._switches('guard'),
        ]),
        _section('squad', <Widget>[
          textField(
            form,
            const <JsonStep>['squad', 'name'],
            combatWords('squad.name'),
            suggestions: <FieldOption>[for (final String squad in squads) FieldOption(squad, description: 'squad in this workspace')],
          ),
          _number('squad', 'alertRange'),
        ]),
        _section('greetings', <Widget>[
          textField(form, const <JsonStep>['greetings', 'hostile'], combatWords('greetings.hostile')),
          textField(form, const <JsonStep>['greetings', 'friendly'], combatWords('greetings.friendly')),
          _number('greetings', 'range'),
          _number('greetings', 'cooldownTicks'),
        ]),
      ]);

  /// Section [name]; [also] is a root key it shows, which opens it too.
  Widget _section(String name, List<Widget> children, {String? also}) {
    final Object? value = form.read(<JsonStep>[name]);
    final KeyWords words = combatWords(name);
    final int set = asMap(value).length + (also != null && form.map.containsKey(also) ? 1 : 0);
    return CombatSection(
      key: ValueKey<String>('combat:$name'),
      title: words.label,
      tip: words.tip,
      docs: Docs.combat,
      count: set == 0 ? null : set,
      open: form.problems(<JsonStep>[name], nested: true).isNotEmpty,
      part: name,
      children: value != null && value is! Map<String, Object?>
          ? <Widget>[keptField(form, <JsonStep>[name], words)]
          : <Widget>[
              problemLines(form.problems(<JsonStep>[name])),
              ...children,
              otherFields(form, <JsonStep>[name], (combatSectionKeys[name] ?? const <String>[]).toSet()),
            ],
    );
  }

  Widget _number(String section, String key, {List<JsonStep>? steps}) {
    final CombatRange range = combatRange(section, key)!;
    return numberField(
      form,
      steps ?? <JsonStep>[section, key],
      combatWords('$section.$key'),
      fallback: range.fallback,
      minimum: range.minimum,
      maximum: range.maximum,
      integer: range.integer,
      ticks: key.endsWith('Ticks'),
    );
  }

  List<Widget> _switches(String section) => <Widget>[
        for (final MapEntry<String, bool> entry in (combatBooleans[section] ?? const <String, bool>{}).entries)
          switchField(form, <JsonStep>[section, entry.key], combatWords('$section.${entry.key}'), fallback: entry.value),
      ];

  Widget _ranged() {
    const List<JsonStep> at = <JsonStep>['ranged'];
    final Object? weapon = form.read(const <JsonStep>['ranged', 'weapon']);
    final Object? effect = form.read(const <JsonStep>['ranged', 'effect']);
    return _section('ranged', <Widget>[
      ..._switches('ranged').take(1),
      choiceField(
        form,
        const <JsonStep>['ranged', 'weapon'],
        combatWords('ranged.weapon'),
        options: combatWeapons,
        labels: combatWeaponLabels,
        fallback: 'arrow',
        onChanged: (String? next) => form.put(at, withRangedWeapon(asMap(form.read(at)), next)),
      ),
      if (weapon == 'potion' && (effect == null || effect is Map<String, Object?>)) ...<Widget>[
        _effect(),
        _number('effect', 'amplifier', steps: const <JsonStep>['ranged', 'effect', 'amplifier']),
        _number('effect', 'durationTicks', steps: const <JsonStep>['ranged', 'effect', 'durationTicks']),
      ] else if (effect != null)
        keptField(form, const <JsonStep>['ranged', 'effect'], combatWords('ranged.effect')),
      for (final String key in const <String>['damage', 'range', 'minimumRange', 'cooldownTicks', 'velocity', 'inaccuracy', 'knockback']) _number('ranged', key),
      ..._switches('ranged').skip(1),
    ]);
  }

  /// The status effect a thrown potion applies.
  Widget _effect() => textField(
        form,
        const <JsonStep>['ranged', 'effect', 'id'],
        combatWords('ranged.effect'),
        placeholder: 'minecraft:instant_health',
        profile: idProfile(const ValueRef.registry(RegistryKind.effect)),
        suggestions: <FieldOption>[
          for (final String id in _commonEffects) FieldOption(id, description: combatHelpfulEffects.contains(id) ? 'helpful, never an attack' : 'an attack'),
        ],
      );

  Widget _returnPoint() {
    const List<JsonStep> at = <JsonStep>['avoidance', 'returnPoint'];
    final Object? point = form.read(at);
    final KeyWords words = combatWords('avoidance.returnPoint');
    if (point != null && point is! Map<String, Object?>) return keptField(form, at, words);
    return CombatLocationsEditor(
      key: const ValueKey<String>('combat:returnPoint'),
      points: <Object?>[?point],
      maximum: 1,
      label: words.label,
      tip: fieldTip(words, at),
      noun: 'Return point',
      origin: origin,
      problems: (int index) => form.problems(at, nested: true),
      onChanged: (List<Object?> next) => form.put(at, next.isEmpty ? null : next.first),
    );
  }
}

/// `melee.weaponDamage`: a damage per held item, and a picker to add one.
class _WeaponDamage extends StatefulWidget {
  const _WeaponDamage({required this.form});

  final ModuleForm form;

  @override
  State<_WeaponDamage> createState() => _WeaponDamageState();
}

class _WeaponDamageState extends State<_WeaponDamage> {
  static const List<JsonStep> _at = <JsonStep>['melee', 'weaponDamage'];
  String _draft = '';

  @override
  Widget build(BuildContext context) {
    final ModuleForm form = component.form;
    final KeyWords words = combatWords('melee.weaponDamage');
    final Object? raw = form.read(_at);
    if (raw != null && raw is! Map<String, Object?>) return keptField(form, _at, words);
    final JsonMap map = asMap(raw);
    final List<String> items = map.keys.toList();
    final CombatRange range = combatRange('weaponDamage', '*')!;
    final String id = fieldId(form, _at);
    void add() {
      if (_draft.isEmpty || map.containsKey(_draft)) return;
      form.put(_at, <String, Object?>{...map, _draft: range.fallback});
      setState(() => _draft = '');
    }

    return Field(
      label: words.label,
      tip: fieldTip(words, _at, '0 to 100 per hit.'),
      forId: id,
      problems: form.problems(_at, nested: true),
      child: fieldStack(<Widget>[
        for (int index = 0; index < items.length; index++)
          fieldStack(key: ValueKey<String>('weapon-$index'), <Widget>[
            fieldLine(<Widget>[
              ItemPicker(
                value: items[index],
                label: 'Weapon ${index + 1}',
                preferred: _commonWeapons,
                onChanged: (String next) {
                  final String item = next.trim();
                  if (item.isEmpty || (item != items[index] && map.containsKey(item))) return;
                  form.put(_at, renameKey(map, items[index], item));
                },
              ),
              if (map[items[index]] == null || map[items[index]] is num)
                NumberInput(
                  value: map[items[index]] as num?,
                  integer: false,
                  digits: 5,
                  label: 'Weapon ${index + 1} damage',
                  placeholder: '${range.fallback}',
                  onChanged: (num? next) => form.put(<JsonStep>[..._at, items[index]], next),
                )
              else
                TypedValueEditor(
                  value: map[items[index]],
                  label: 'Weapon ${index + 1} damage',
                  onChanged: (Object? next) => form.put(<JsonStep>[..._at, items[index]], next),
                ),
              AppIconButton(icon: ArcaneIcon.x(), label: 'Remove ${items[index]}', onPressed: () => form.put(_at, asMap(writeIn(map, <JsonStep>[items[index]], null)))),
            ]),
          ]),
        fieldLine(<Widget>[
          ItemPicker(
            id: id,
            value: _draft,
            label: words.label,
            placeholder: 'minecraft:iron_sword',
            preferred: <String>[
              for (final String item in _commonWeapons)
                if (!map.containsKey(item)) item,
            ],
            onChanged: (String next) => setState(() => _draft = next.trim()),
          ),
          AppIconButton(icon: ArcaneIcon.plus(), label: 'Add weapon', disabled: _draft.isEmpty || map.containsKey(_draft), onPressed: add),
        ]),
      ]),
    );
  }
}
