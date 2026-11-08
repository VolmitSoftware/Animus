/// The typed editor for an NPC's `modules["animus:health"]`: whether it can
/// be hurt and killed, how it takes damage, what it drops and how it comes
/// back. A permadeath NPC the server marked dead gets a Revive button, and a
/// combat module that still sets defense, death or respawn is flagged with a
/// fix, since the server refuses both.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/npc/health_settings.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/typed_values_editor.dart';
import '../mc/item_picker.dart';
import 'combat_locations.dart';
import 'npc_section.dart';

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

const Map<String, String> _orderLabels = <String, String>{'in_order': 'in order', 'random': 'random', 'nearest': 'nearest'};

class HealthEditor extends StatelessWidget {
  const HealthEditor({
    required this.value,
    required this.onChanged,
    this.combat,
    this.onCombatChanged,
    this.diagnostics = const <Diagnostic>[],
    this.origin,
    super.key,
  });

  /// The module object, or null.
  final Object? value;

  /// The whole new module; null removes it.
  final void Function(Object? value) onChanged;

  /// The NPC's combat module, checked for sections this one replaces.
  final Object? combat;
  final void Function(Object? value)? onCombatChanged;
  final List<Diagnostic> diagnostics;

  /// The NPC's x, y and z, drawn on the respawn grid.
  final (double, double, double)? origin;

  static final String _root = childPath(r'$.modules', healthModule);

  JsonMap get _module => asMap(value);

  void _put(List<JsonStep> steps, Object? next) => onChanged(withHealthField(_module, steps, next));

  List<Diagnostic> _at(List<JsonStep> steps, {bool nested = false}) => diagnosticsAt(diagnostics, formatPath(<JsonStep>['modules', healthModule, ...steps]), nested: nested);

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-npc-module', attributes: const <String, String>{'data-module': healthModule}, <Widget>[
    NpcSubhead(
      'Health and death',
      help: value == null ? 'Off: cannot be hurt.' : null,
      tip: 'Lets players, mobs and NPCs hurt and kill it without combat AI: health, drops and respawn. Replaces combat defense, death and respawn.',
      docs: Docs.combat,
    ),
    _body(),
  ]);

  Widget _body() {
    if (value == null) {
      return dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Make killable', size: ButtonSize.small, onPressed: () => onChanged(<String, Object?>{'killable': true})),
      ]);
    }
    if (value is! Map<String, Object?>) {
      return GuiRow(
        label: 'Health',
        help: 'Must be an object.',
        problems: diagnosticsAt(diagnostics, _root, nested: true),
        child: TypedValueEditor(value: value, label: healthModule, onChanged: onChanged),
      );
    }
    final JsonMap module = _module;
    final bool killable = module['killable'] == true;
    final List<String> conflicts = healthCombatConflicts(<String, Object?>{healthModule: module, 'animus:combat': ?combat});
    return dom.section(classes: 'animus-health-editor', attributes: const <String, String>{'aria-label': 'Health editor'}, <Widget>[
      if (module['dead'] == true)
        dom.div(classes: 'animus-health-notice', attributes: const <String, String>{'role': 'status'}, <Widget>[
          const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Dead. Stays gone until revived.')]),
          Button.secondary(label: 'Revive', size: ButtonSize.small, onPressed: () => onChanged(revivedHealth(module))),
        ]),
      if (conflicts.isNotEmpty)
        dom.div(classes: 'animus-health-notice', attributes: const <String, String>{'role': 'status'}, <Widget>[
          dom.span(classes: 'animus-gui-problem is-error', <Widget>[
            Component.text('Combat also sets ${conflicts.join(', ')}. Health replaces them; the server refuses both.'),
          ]),
          if (onCombatChanged != null)
            Button.secondary(
              label: 'Remove from combat',
              size: ButtonSize.small,
              onPressed: () => onCombatChanged!(withoutReplacedCombat(asMap(combat))),
            ),
        ]),
      _problems(diagnosticsAt(diagnostics, _root)),
      dom.div(classes: 'animus-combat-switches', <Widget>[
        _switch(const <JsonStep>['killable'], 'Killable', 'Can be hurt and killed.', fallback: false),
      ]),
      if (!killable)
        const dom.p(classes: 'animus-gui-help', <Widget>[Component.text('Cannot be hurt. Settings below are kept.')])
      else ...<Widget>[
        _damage(module),
        _death(module),
        _respawn(module),
      ],
      if (unknownEntries(module, healthKeys.toSet()) case final JsonMap unknown when unknown.isNotEmpty)
        GuiRow(
          label: 'Other fields',
          tip: 'Not health settings. The server refuses them.',
          child: KeyValueEditor(value: unknown, label: 'Other health fields', onChanged: (JsonMap next) => onChanged(withUnknownEntries(module, healthKeys.toSet(), next))),
        ),
      dom.div(classes: 'animus-row', <Widget>[Button.ghost(label: 'Remove health', size: ButtonSize.small, onPressed: () => onChanged(null))]),
    ]);
  }

  Widget _damage(JsonMap module) {
    final Set<String> on = healthSourcesOn(module);
    return GuiSection(
      key: const ValueKey<String>('health:damage'),
      title: 'Damage',
      tip: 'How much it takes before it dies, and who can hurt it.',
      children: <Widget>[
        _number(const <JsonStep>['maximumHealth'], 'Max health', 'Left out: the max_health attribute, else 20.', placeholder: '20', integer: false),
        _number(const <JsonStep>['armor'], 'Armor', 'Share of each hit absorbed, 0-1. Left out: the armor attributes.', placeholder: '0', integer: false),
        _number(const <JsonStep>['invulnerableTicks'], 'Hit cooldown', 'Ticks repeat hits are ignored, 0-100.', placeholder: '10'),
        if (module['regenerate'] == true) _number(const <JsonStep>['regenerateTicks'], 'Regen ticks', 'Ticks per 1 health healed, 1-72000.', placeholder: '100'),
        dom.div(classes: 'animus-combat-switches', <Widget>[
          _switch(const <JsonStep>['knockback'], 'Knockback', 'Hits push it back.', fallback: true),
          _switch(const <JsonStep>['regenerate'], 'Regenerate', 'Heals over time.', fallback: false),
          _switch(const <JsonStep>['armorFromEquipment'], 'Armor from equipment', 'Worn pieces set armor.', fallback: false),
          _switch(const <JsonStep>['showHealth'], 'Show health', "On the attacker's action bar.", fallback: false),
        ]),
        GuiRow(
          label: 'Hurt by',
          tip: 'What can damage it: player, mob and NPC melee, and shots from combat NPCs. All four by default.',
          problems: _at(const <JsonStep>['sources'], nested: true),
          child: dom.div(classes: 'animus-combat-chips', attributes: const <String, String>{'role': 'group', 'aria-label': 'Hurt by'}, <Widget>[
            for (final String source in healthSources)
              Button.ghost(
                label: source,
                size: ButtonSize.small,
                attributes: <String, String>{'aria-pressed': '${on.contains(source)}'},
                onPressed: () => onChanged(withHealthSource(module, source, !on.contains(source))),
              ),
          ]),
        ),
        GuiRow(
          label: 'Permission',
          forId: 'animus-health-permission',
          tip: 'Players need this node to hurt it.',
          problems: _at(const <JsonStep>['attackerPermission']),
          child: GuiTextInput(
            id: 'animus-health-permission',
            value: asString(module['attackerPermission']) ?? '',
            label: 'health.attackerPermission',
            placeholder: 'town.hit.guard',
            onChanged: (String next) => _put(const <JsonStep>['attackerPermission'], next.trim().isEmpty ? null : next.trim()),
          ),
        ),
      ],
    );
  }

  Widget _death(JsonMap module) {
    const List<JsonStep> at = <JsonStep>['death', 'drops'];
    final Object? raw = readIn(module, at);
    final List<Object?> drops = asList(raw);
    return GuiSection(
      key: const ValueKey<String>('health:death'),
      title: 'Death',
      tip: 'What it drops when it dies. Each drop rolls its own chance.',
      count: drops.isEmpty ? null : drops.length,
      children: <Widget>[
        GuiRow(
          label: 'Drops',
          tip: 'Count is the fewest that drop, max count the most. Chance 0-1. Up to 32.',
          problems: _at(at),
          child: raw != null && raw is! List<Object?>
              ? TypedValueEditor(value: raw, label: 'health.death.drops', onChanged: (Object? next) => _put(at, next))
              : dom.div(classes: 'animus-combat-list', <Widget>[
                  for (int index = 0; index < drops.length; index++) _drop(drops, index),
                  dom.div(classes: 'animus-row', <Widget>[
                    Button.ghost(
                      label: 'Add drop',
                      size: ButtonSize.small,
                      disabled: drops.length >= 32,
                      onPressed: () => _put(at, <Object?>[...drops, newHealthDrop()]),
                    ),
                  ]),
                ]),
        ),
        _number(const <JsonStep>['death', 'experience'], 'Experience', 'Orbs dropped, 0-10000.', placeholder: '0'),
        dom.div(classes: 'animus-combat-switches', <Widget>[
          _switch(const <JsonStep>['death', 'dropEquipment'], 'Drop equipment', 'Worn and held items drop too.', fallback: false),
        ]),
      ],
    );
  }

  Widget _drop(List<Object?> drops, int index) {
    const List<JsonStep> at = <JsonStep>['death', 'drops'];
    final String name = 'Drop ${index + 1}';
    final Object? raw = drops[index];
    void write(List<Object?> next) => _put(at, next);
    return dom.div(
      key: ValueKey<String>('health-drop-$index'),
      classes: 'animus-combat-item',
      attributes: <String, String>{'role': 'group', 'aria-label': name},
      <Widget>[
        if (raw case final Map<String, Object?> drop) ...<Widget>[
          dom.div(classes: 'animus-combat-fields', <Widget>[
            ItemPicker(
              value: asString(asMap(drop['item'])['id']) ?? '',
              label: '$name item',
              placeholder: 'minecraft:iron_ingot',
              preferred: _dropItems,
              onChanged: (String next) => write(withHealthDropItem(drops, index, 'id', next.trim())),
            ),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove ${name.toLowerCase()}',
              onPressed: () => write(<Object?>[...drops]..removeAt(index)),
            ),
          ]),
          dom.div(classes: 'animus-combat-fields', <Widget>[
            GuiNumberInput(
              value: asInt(asMap(drop['item'])['count']),
              label: '$name count',
              placeholder: 'count 1',
              onChanged: (num? next) => write(withHealthDropItem(drops, index, 'count', next?.toInt())),
            ),
            GuiNumberInput(
              value: asInt(drop['maxCount']),
              label: '$name max count',
              placeholder: 'max = count',
              onChanged: (num? next) => _put(<JsonStep>[...at, index, 'maxCount'], next?.toInt()),
            ),
            GuiNumberInput(
              value: asDouble(drop['chance']),
              integer: false,
              label: '$name chance',
              placeholder: 'chance 1',
              onChanged: (num? next) => _put(<JsonStep>[...at, index, 'chance'], next),
            ),
            GuiTextInput(
              value: asString(asMap(drop['item'])['components']) ?? '',
              label: '$name components',
              placeholder: '[enchantment_glint_override=true]',
              onChanged: (String next) => write(withHealthDropItem(drops, index, 'components', next.trim())),
            ),
          ]),
        ] else
          TypedValueEditor(value: raw, label: name, onChanged: (Object? next) => _put(<JsonStep>[...at, index], next)),
        _problems(_at(<JsonStep>[...at, index], nested: true)),
      ],
    );
  }

  Widget _respawn(JsonMap module) {
    const List<JsonStep> at = <JsonStep>['respawn', 'points'];
    final Object? raw = readIn(module, at);
    final String? after = asString(readIn(module, const <JsonStep>['respawn', 'afterDeath']));
    final bool permadeath = after == 'permadeath';
    return GuiSection(
      key: const ValueKey<String>('health:respawn'),
      title: 'Respawn',
      tip: 'Whether it comes back after death, after how long, and where.',
      children: <Widget>[
        GuiRow(
          label: 'After death',
          tip: 'permadeath keeps it dead until revived here or in game.',
          problems: _at(const <JsonStep>['respawn', 'afterDeath']),
          child: GuiSelect(
            value: after,
            options: healthAfterDeath,
            allowNone: true,
            noneLabel: 'respawn (default)',
            hiddenLabel: 'health.respawn.afterDeath',
            onChanged: (String? next) => _put(const <JsonStep>['respawn', 'afterDeath'], next),
          ),
        ),
        if (!permadeath) ...<Widget>[
          _number(const <JsonStep>['respawn', 'delayTicks'], 'Delay', 'Ticks before it comes back, 0-72000.', placeholder: '100'),
          GuiRow(
            label: 'Order',
            tip: 'How the next point is picked.',
            problems: _at(const <JsonStep>['respawn', 'order']),
            child: GuiSelect(
              value: asString(readIn(module, const <JsonStep>['respawn', 'order'])),
              options: healthOrders,
              labels: _orderLabels,
              allowNone: true,
              noneLabel: 'in order (default)',
              hiddenLabel: 'health.respawn.order',
              onChanged: (String? next) => _put(const <JsonStep>['respawn', 'order'], next),
            ),
          ),
          GuiRow(
            label: 'Points',
            tip: 'Where it comes back. None: its own position. Up to 16.',
            problems: _at(at),
            child: raw != null && raw is! List<Object?>
                ? TypedValueEditor(value: raw, label: 'health.respawn.points', onChanged: (Object? next) => _put(at, next))
                : CombatLocationsEditor(
                    points: asList(raw),
                    label: 'Respawn points',
                    noun: 'Point',
                    path: formatPath(const <JsonStep>['modules', healthModule, ...at]),
                    origin: origin,
                    diagnostics: diagnostics,
                    onChanged: (List<Object?> next) => _put(at, next),
                  ),
          ),
        ],
      ],
    );
  }

  Widget _number(List<JsonStep> steps, String label, String tip, {required String placeholder, bool integer = true}) {
    final Object? current = readIn(_module, steps);
    final String id = 'animus-health-${steps.join('-')}';
    return GuiRow(
      key: ValueKey<String>('health:${steps.join('.')}'),
      label: label,
      forId: current == null || current is num ? id : null,
      tip: tip,
      problems: _at(steps),
      child: current != null && current is! num
          ? TypedValueEditor(value: current, label: 'health.${steps.join('.')}', onChanged: (Object? next) => _put(steps, next))
          : GuiNumberInput(
              id: id,
              value: current as num?,
              integer: integer,
              label: 'health.${steps.join('.')}',
              placeholder: placeholder,
              onChanged: (num? next) => _put(steps, next),
            ),
    );
  }

  /// A boolean field; a non-boolean value is kept and edited by kind.
  Widget _switch(List<JsonStep> steps, String label, String description, {required bool fallback}) {
    final Object? current = readIn(_module, steps);
    if (current != null && current is! bool) {
      return GuiRow(label: label, problems: _at(steps), child: TypedValueEditor(value: current, label: label, onChanged: (Object? next) => _put(steps, next)));
    }
    return dom.div(key: ValueKey<String>('health-switch:${steps.join('.')}'), classes: 'animus-combat-switch', <Widget>[
      ArcaneCheckbox(
        checked: (current as bool?) ?? fallback,
        label: label,
        description: '$description Default ${fallback ? 'on' : 'off'}.',
        size: ComponentSize.sm,
        onChanged: (bool next) => _put(steps, next),
      ),
      _problems(_at(steps)),
    ]);
  }

  Widget _problems(List<Diagnostic> problems) => problems.isEmpty
      ? const dom.span(<Widget>[])
      : dom.div(classes: 'animus-combat-problems', <Widget>[
          for (final Diagnostic problem in problems)
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[
              dom.code(<Widget>[Component.text(problem.path)]),
              Component.text(' ${problem.message}'),
            ]),
        ]);
}
