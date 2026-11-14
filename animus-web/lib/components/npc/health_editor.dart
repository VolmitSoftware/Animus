/// The Advanced part of `animus:health` in the Combat tab: how hits land
/// (Damage), what it drops (Death) and whether and where it comes back
/// (Respawn). Each section is closed until it holds a value. Max health and
/// "Can be hurt" sit above them, in the part Simple shows too.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/combat_labels.dart';
import '../../logic/npc/health_settings.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../inspect/typed_values_editor.dart';
import '../mc/item_picker.dart';
import 'combat_fields.dart';
import 'combat_locations.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';
import '../gui/gui_stack.dart';

/// The health part of the combat docs.
const DocLink healthDocs = DocLink('08-combat', 'health-and-death');

const List<String> _dropItems = <String>[
  'minecraft:iron_ingot',
  'minecraft:gold_ingot',
  'minecraft:emerald',
  'minecraft:diamond',
  'minecraft:bread',
  'minecraft:bone',
  'minecraft:rotten_flesh',
  'minecraft:arrow',
];

/// The most drops a module may list.
const int _maxDrops = 32;

/// Root keys the Damage section edits.
const Set<String> healthDamageKeys = <String>{
  'armor',
  'armorFromEquipment',
  'invulnerableTicks',
  'knockback',
  'regenerate',
  'regenerateTicks',
  'sources',
  'attackerPermission',
  'showHealth',
  'protectFromRange',
  'protectedFromIgnored',
};

/// Damage, Death and Respawn of the health module in [form].
class HealthSettingsPart extends StatelessWidget {
  const HealthSettingsPart({required this.form, this.origin, super.key});

  final ModuleForm form;

  /// The NPC's x, y and z, drawn on the respawn grid.
  final (double, double, double)? origin;

  @override
  Widget build(BuildContext context) {
    final JsonMap module = form.map;
    final int damage = module.keys.where(healthDamageKeys.contains).length;
    return Component.fragment(<Widget>[
      CombatSection(
        key: const ValueKey<String>('health:damage'),
        title: 'Damage',
        tip: 'How hits land: armor, cooldown, healing and who can hurt it.',
        docs: healthDocs,
        count: damage == 0 ? null : damage,
        open: <Diagnostic>[for (final String key in healthDamageKeys) ...form.problems(<JsonStep>[key], nested: true)].isNotEmpty,
        part: 'damage',
        children: <Widget>[
          _number('armor', fallback: 0, minimum: 0, maximum: 1),
          switchField(form, const <JsonStep>['armorFromEquipment'], healthWords('armorFromEquipment'), fallback: false),
          _number('invulnerableTicks', fallback: 10, minimum: 0, maximum: 100, integer: true, ticks: true),
          switchField(form, const <JsonStep>['knockback'], healthWords('knockback'), fallback: true),
          switchField(form, const <JsonStep>['regenerate'], healthWords('regenerate'), fallback: false),
          if (module['regenerate'] == true || module.containsKey('regenerateTicks'))
            _number('regenerateTicks', fallback: 100, minimum: 1, maximum: 72000, integer: true, ticks: true),
          _number('protectFromRange', fallback: 0, minimum: 0, maximum: 256),
          ..._sources(module),
          switchField(form, const <JsonStep>['protectedFromIgnored'], healthWords('protectedFromIgnored'), fallback: false),
          switchField(form, const <JsonStep>['showHealth'], healthWords('showHealth'), fallback: false),
          textField(form, const <JsonStep>['attackerPermission'], healthWords('attackerPermission')),
        ],
      ),
      _death(),
      _respawn(),
    ]);
  }

  Widget _number(String key, {required num fallback, required num minimum, required num maximum, bool integer = false, bool ticks = false, List<JsonStep>? steps}) =>
      numberField(
        form,
        steps ?? <JsonStep>[key],
        healthWords(steps == null ? key : steps.join('.')),
        fallback: fallback,
        minimum: minimum,
        maximum: maximum,
        integer: integer,
        ticks: ticks,
      );

  /// "Hurt by" (attacks, all on while `sources` is unset) and "Also hurt
  /// by" (fire, lava, falls, only when listed): one key, two rows.
  List<Widget> _sources(JsonMap module) {
    const List<JsonStep> at = <JsonStep>['sources'];
    final Set<String> on = healthSourcesOn(module);
    Widget row(String? label, Iterable<String> sources, {String extra = ''}) {
      final Set<String> mine = sources.toSet();
      return checksField(
        form,
        at,
        healthWords('sources'),
        label: label,
        extraTip: extra,
        options: <String, String>{for (final String source in mine) source: healthSourceLabels[source] ?? source},
        unset: on,
        showProblems: label == null,
        write: (Set<String> next) => form.onChanged(withHealthSources(form.map, <String>{...on.difference(mine), ...next.intersection(mine)})),
      );
    }

    return <Widget>[
      row(null, healthAttackSources),
      row('Also hurt by', healthSources.where((String source) => !healthAttackSources.contains(source)), extra: 'Off unless picked.'),
    ];
  }

  Widget _death() {
    final Object? value = form.read(const <JsonStep>['death']);
    final int set = asMap(value).length;
    return CombatSection(
      key: const ValueKey<String>('health:death'),
      title: healthWords('death').label,
      tip: healthWords('death').tip,
      docs: healthDocs,
      count: set == 0 ? null : set,
      open: form.problems(const <JsonStep>['death'], nested: true).isNotEmpty,
      part: 'death',
      children: value != null && value is! Map<String, Object?>
          ? <Widget>[keptField(form, const <JsonStep>['death'], healthWords('death'))]
          : <Widget>[
              problemLines(form.problems(const <JsonStep>['death'])),
              _HealthDrops(form: form),
              _number('experience', fallback: 0, minimum: 0, maximum: 10000, integer: true, steps: const <JsonStep>['death', 'experience']),
              switchField(form, const <JsonStep>['death', 'dropEquipment'], healthWords('death.dropEquipment'), fallback: false),
              otherFields(form, const <JsonStep>['death'], const <String>{'drops', 'experience', 'dropEquipment'}),
            ],
    );
  }

  Widget _respawn() {
    const List<JsonStep> points = <JsonStep>['respawn', 'points'];
    final Object? value = form.read(const <JsonStep>['respawn']);
    final String? after = asString(form.read(const <JsonStep>['respawn', 'afterDeath']));
    final bool comesBack = after != 'permadeath' && after != 'delete';
    final Object? raw = form.read(points);
    final int set = asMap(value).length;
    return CombatSection(
      key: const ValueKey<String>('health:respawn'),
      title: healthWords('respawn').label,
      tip: healthWords('respawn').tip,
      docs: healthDocs,
      count: set == 0 ? null : set,
      open: form.problems(const <JsonStep>['respawn'], nested: true).isNotEmpty,
      part: 'respawn',
      children: value != null && value is! Map<String, Object?>
          ? <Widget>[keptField(form, const <JsonStep>['respawn'], healthWords('respawn'))]
          : <Widget>[
              problemLines(form.problems(const <JsonStep>['respawn'])),
              choiceField(
                form,
                const <JsonStep>['respawn', 'afterDeath'],
                healthWords('respawn.afterDeath'),
                options: healthAfterDeath,
                labels: healthAfterDeathLabels,
                fallback: 'respawn',
              ),
              if (comesBack) ...<Widget>[
                _number('delayTicks', fallback: 100, minimum: 0, maximum: 72000, integer: true, ticks: true, steps: const <JsonStep>['respawn', 'delayTicks']),
                choiceField(
                  form,
                  const <JsonStep>['respawn', 'order'],
                  healthWords('respawn.order'),
                  options: healthOrders,
                  labels: healthOrderLabels,
                  fallback: 'in_order',
                ),
                if (raw != null && raw is! List<Object?>)
                  keptField(form, points, healthWords('respawn.points'))
                else
                  CombatLocationsEditor(
                    key: const ValueKey<String>('health:points'),
                    points: asList(raw),
                    label: healthWords('respawn.points').label,
                    tip: fieldTip(healthWords('respawn.points'), points, 'Up to 16.'),
                    noun: 'Point',
                    origin: origin,
                    problems: (int index) => form.problems(<JsonStep>[...points, index], nested: true),
                    onChanged: (List<Object?> next) => form.put(points, next),
                  ),
              ],
              otherFields(form, const <JsonStep>['respawn'], const <String>{'afterDeath', 'delayTicks', 'points', 'order'}),
            ],
    );
  }
}

/// `death.drops`: an item, its count, most and chance per drop.
class _HealthDrops extends StatelessWidget {
  const _HealthDrops({required this.form});

  final ModuleForm form;

  static const List<JsonStep> _at = <JsonStep>['death', 'drops'];

  @override
  Widget build(BuildContext context) {
    final KeyWords words = healthWords('death.drops');
    final Object? raw = form.read(_at);
    if (raw != null && raw is! List<Object?>) return keptField(form, _at, words);
    final List<Object?> drops = asList(raw);
    return Field(
      label: words.label,
      tip: fieldTip(words, _at, 'Up to $_maxDrops.'),
      problems: form.problems(_at),
      child: dom.div(styles: stackStyles(), attributes: const <String, String>{'data-drops': ''}, <Widget>[
        for (int index = 0; index < drops.length; index++) _drop(drops, index),
        if (drops.length < _maxDrops)
          ActionRow(label: words.label, secondary: <RowAction>[RowAction('Add drop', () => form.put(_at, <Object?>[...drops, newHealthDrop()]), icon: ArcaneIcon.plus())]),
      ]),
    );
  }

  Widget _drop(List<Object?> drops, int index) {
    final String name = 'Drop ${index + 1}';
    final String id = '${fieldId(form, _at)}-$index';
    final Object? raw = drops[index];
    void write(List<Object?> next) => form.put(_at, next);
    Widget cell(String key, Widget control) => namedAbove(healthWords('drop.$key').label, control, forId: '$id-$key');
    return dom.div(
      key: ValueKey<String>('drop:$index'),
      styles: stackStyles(),
      attributes: <String, String>{'role': 'group', 'aria-label': name},
      <Widget>[
        if (raw case final Map<String, Object?> drop) ...<Widget>[
          // The item with its number and Remove, then count, most and
          // chance, then components, each under the item.
          Row(gapSize: Gap.sm, crossAxisAlignment: CrossAxisAlignment.end, children: <Widget>[
            pointNumber(index + 1),
            Expanded(
              child: cell(
                'item',
                ItemPicker(
                  id: '$id-item',
                  value: asString(asMap(drop['item'])['id']) ?? '',
                  label: '$name item',
                  placeholder: 'minecraft:iron_ingot',
                  preferred: _dropItems,
                  onChanged: (String next) => write(withHealthDropItem(drops, index, 'id', next.trim())),
                ),
              ),
            ),
            AppIconButton(icon: ArcaneIcon.x(), label: 'Remove ${name.toLowerCase()}', onPressed: () => write(<Object?>[...drops]..removeAt(index))),
          ]),
          dom.div(styles: const dom.Styles(raw: <String, String>{'padding-left': '1.75rem'}), <Widget>[
            fieldStack(<Widget>[
              equalLine(<Widget>[
                cell(
                  'count',
                  NumberInput(
                    id: '$id-count',
                    value: asInt(asMap(drop['item'])['count']),
                    label: '$name count',
                    placeholder: '1',
                    onChanged: (num? next) => write(withHealthDropItem(drops, index, 'count', next?.toInt())),
                  ),
                ),
                cell(
                  'maxCount',
                  NumberInput(
                    id: '$id-maxCount',
                    value: asInt(drop['maxCount']),
                    label: '$name max count',
                    placeholder: 'Same',
                    onChanged: (num? next) => form.put(<JsonStep>[..._at, index, 'maxCount'], next?.toInt()),
                  ),
                ),
                cell(
                  'chance',
                  NumberInput(
                    id: '$id-chance',
                    value: asDouble(drop['chance']),
                    integer: false,
                    label: '$name chance',
                    placeholder: '1',
                    onChanged: (num? next) => form.put(<JsonStep>[..._at, index, 'chance'], next),
                  ),
                ),
              ]),
              cell(
                'components',
                AutocompleteInput(
                  id: '$id-components',
                  value: asString(asMap(drop['item'])['components']) ?? '',
                  label: '$name components',
                  profile: FieldProfile.none,
                  onChanged: (String next) => write(withHealthDropItem(drops, index, 'components', next.trim())),
                ),
              ),
            ]),
          ]),
        ] else
          TypedValueEditor(value: raw, label: name, onChanged: (Object? next) => form.put(<JsonStep>[..._at, index], next)),
        problemLines(form.problems(<JsonStep>[..._at, index], nested: true)),
      ],
    );
  }
}
