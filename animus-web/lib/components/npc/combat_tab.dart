/// The Combat tab of an NPC or trait group: whether it fights and who, and
/// whether it can be hurt and how much. Simple shows those four rows and,
/// when the modules hold more, one "n advanced settings" line on top whose
/// Show opens the rest here. Advanced shows every setting of
/// `animus:combat` and `animus:health` in words, then one remove action per
/// module. Every edit writes by path, so keys a row does not show stay.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/combat_labels.dart';
import '../../logic/npc/combat_presets.dart';
import '../../logic/npc/combat_settings.dart';
import '../../logic/npc/health_settings.dart';
import '../../logic/npc/npc_attributes.dart' show attributeValue;
import '../../model/animus_document.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../inspect/typed_values_editor.dart';
import 'combat_editor.dart';
import 'combat_fields.dart';
import 'health_editor.dart';
import 'npc_parts.dart';
import '../select/animus_select.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';

class CombatTab extends StatelessWidget {
  const CombatTab({
    required this.modules,
    required this.onModule,
    required this.schema,
    this.diagnostics = const <Diagnostic>[],
    this.npcIds = const <String>[],
    this.squads = const <String>[],
    this.origin,
    this.group = false,
    this.attributes,
    super.key,
  });

  /// The definition's `modules` (an NPC's, or a trait group's `values.modules`).
  final JsonMap modules;

  /// Writes `modules[key]`; null removes that module.
  final void Function(String key, Object? value) onModule;

  /// `component.schemas.npc`: the rare target rules are typed from it.
  final SchemaView schema;

  /// The document's diagnostics, at NPC paths such as `$.modules["animus:combat"].targets`.
  final List<Diagnostic> diagnostics;

  /// Other NPC ids, offered for NPC targets.
  final List<String> npcIds;

  /// Squad names in the workspace.
  final List<String> squads;

  /// The NPC's position, drawn on the return and respawn grids.
  final (double, double, double)? origin;

  /// A trait group's values: no position and nothing to revive.
  final bool group;

  /// The definition's `attributes`: an unset Max health shows the
  /// `max_health` attribute it falls back to.
  final Object? attributes;

  @override
  Widget build(BuildContext context) => _CombatTabBody(tab: this, advanced: ModeScope.advanced(context));
}

class _CombatTabBody extends StatefulWidget {
  const _CombatTabBody({required this.tab, required this.advanced});

  final CombatTab tab;
  final bool advanced;

  @override
  State<_CombatTabBody> createState() => _CombatTabBodyState();
}

class _CombatTabBodyState extends State<_CombatTabBody> {
  /// Simple opened the advanced settings in this tab.
  bool _revealed = false;

  CombatTab get _tab => component.tab;

  @override
  Widget build(BuildContext context) {
    final CombatTab tab = _tab;
    final bool full = component.advanced || _revealed;
    final ModuleForm combat = ModuleForm(
      key: combatModule,
      value: tab.modules[combatModule],
      diagnostics: tab.diagnostics,
      onChanged: (Object? value) => _tab.onModule(combatModule, value),
    );
    final ModuleForm health = ModuleForm(
      key: healthModule,
      value: tab.modules[healthModule],
      diagnostics: tab.diagnostics,
      onChanged: (Object? value) => _tab.onModule(healthModule, value),
    );
    final bool hasCombat = combat.value is Map<String, Object?>;
    final bool hasHealth = health.value is Map<String, Object?>;
    final List<List<JsonStep>> hidden = component.advanced ? const <List<JsonStep>>[] : combatHiddenSettings(tab.modules);
    return dom.div(
      styles: stackStyles(),
      attributes: <String, String>{'data-combat': full ? 'advanced' : 'simple'},
      <Widget>[
        if (!tab.group && health.map['dead'] == true)
          AppAlert(
            tone: AlertTone.warning,
            message: 'Dead. It stays dead until revived.',
            action: Button.outline(label: 'Revive', size: ButtonSize.sm, onPressed: () => health.onChanged(revivedHealth(health.map))),
          ),
        if (hidden.isNotEmpty) _inUse(hidden),
        _fights(combat, full),
        if (full && hasCombat)
          CombatSettingsPart(
            form: combat,
            schema: tab.schema,
            diagnostics: tab.diagnostics,
            npcIds: tab.npcIds,
            squads: tab.squads,
            origin: tab.origin,
          ),
        if (full) _heading('Health'),
        _canBeHurt(health, full),
        if (full && hasHealth) HealthSettingsPart(form: health, origin: tab.origin),
        if (full) _more(combat, health),
        if (full && (combat.value != null || health.value != null))
          ActionRow(
            label: 'Remove modules',
            secondary: <RowAction>[
              if (combat.value != null) RowAction('Remove combat', () => _remove(combatModule, 'combat', 'Its targets, attacks and every other combat setting')),
              if (health.value != null) RowAction('Remove health', () => _remove(healthModule, 'health', 'Its health, drops and respawn settings')),
            ],
          ),
      ],
    );
  }

  Widget _heading(String text) => NpcHeading(text);

  /// The tip of a row: what it does, and in Advanced its engine key.
  String _tip(KeyWords words, String key, {String extra = ''}) =>
      component.advanced || _revealed ? fieldTip(words, <JsonStep>[key], extra) : <String>[words.tip, if (extra.isNotEmpty) extra].join(' ');

  /// A module that is not an object, edited as written.
  Widget _broken(ModuleForm form, String label) => Field(
        label: label,
        tip: 'The server expects an object here. Fix it or remove it.',
        problems: form.problems(const <JsonStep>[], nested: true),
        child: TypedValueEditor(value: form.value, label: label, onChanged: form.onChanged),
      );

  Widget _fights(ModuleForm combat, bool full) {
    if (combat.value != null && combat.value is! Map<String, Object?>) return _broken(combat, 'Combat');
    final bool fights = combatFights(combat.value);
    final KeyWords words = combatWords('enabled');
    final CombatPreset preset = combatPresetOf(combat.read(const <JsonStep>['targets']));
    final String presetId = fieldId(combat, const <JsonStep>['targets']);
    return Component.fragment(<Widget>[
      Field(
        key: const ValueKey<String>('combat:fights'),
        label: words.label,
        tip: full ? _tip(words, 'enabled') : null,
        docs: Docs.combat,
        problems: combat.problems(const <JsonStep>['enabled']),
        child: combatSwitch(value: fights, label: words.label, onChanged: (bool on) => combat.onChanged(withFights(combat.value, on))),
      ),
      if (fights)
        Field(
          key: const ValueKey<String>('combat:who'),
          label: 'Who it fights',
          forId: presetId,
          tip: 'Attackers only: it fights back and targets no one. Custom: rules a preset cannot hold.',
          docs: Docs.combatTargets,
          problems: full ? const <Diagnostic>[] : combat.problems(const <JsonStep>['targets'], nested: true),
          child: AnimusSelect.values(
            id: presetId,
            value: preset.name,
            values: <String>[
              for (final CombatPreset each in CombatPreset.values)
                if (each != CombatPreset.custom || preset == CombatPreset.custom) each.name,
            ],
            labels: <String, String>{for (final CombatPreset each in CombatPreset.values) each.name: each.label},
            unavailable: const <String>{'custom'},
            onChanged: (String? next) => _pick(combat, preset, CombatPreset.values.firstWhere((CombatPreset each) => each.name == next, orElse: () => preset)),
          ),
        ),
    ]);
  }

  Future<void> _pick(ModuleForm combat, CombatPreset current, CombatPreset next) async {
    if (next == current || next == CombatPreset.custom) return;
    if (current == CombatPreset.custom) {
      final bool replace = await askToConfirm(
        title: 'Replace the custom targets?',
        message: '${next.label} replaces the target rules set in Advanced.',
        confirmLabel: 'Replace',
        destructive: true,
      );
      if (!replace || !mounted) return;
    }
    combat.onChanged(withCombatPreset(asMap(_tab.modules[combatModule]), next));
  }

  Widget _canBeHurt(ModuleForm health, bool full) {
    if (health.value != null && health.value is! Map<String, Object?>) return _broken(health, 'Health');
    final bool hurt = healthCanBeHurt(health.value);
    final KeyWords words = healthWords('killable');
    final KeyWords maximum = healthWords('maximumHealth');
    const List<JsonStep> at = <JsonStep>['maximumHealth'];
    final Object? value = health.read(at);
    final num fallback = attributeValue(_tab.attributes, 'max_health');
    final String id = fieldId(health, at);
    return Component.fragment(<Widget>[
      Field(
        key: const ValueKey<String>('health:hurt'),
        label: words.label,
        tip: _tip(words, 'killable'),
        docs: healthDocs,
        problems: health.problems(const <JsonStep>['killable']),
        child: combatSwitch(value: hurt, label: words.label, onChanged: (bool on) => health.onChanged(withCanBeHurt(health.value, on))),
      ),
      if (hurt || (full && health.value != null))
        if (value == null || value is num)
          Field(
            key: const ValueKey<String>('health:max'),
            label: maximum.label,
            forId: id,
            tip: full ? _tip(maximum, 'maximumHealth', extra: '1 to 2048. Empty takes the max health attribute, else 20.') : null,
            hint: '${plainNumber(((value as num?) ?? fallback) / 2)} hearts',
            problems: health.problems(at),
            child: NumberInput(digits: 6, 
              id: id,
              value: value,
              integer: false,
              label: maximum.label,
              placeholder: plainNumber(fallback),
              onChanged: (num? next) => health.put(at, next),
            ),
          )
        else
          keptField(health, at, maximum),
    ]);
  }

  /// "n advanced settings" and Show, or Hide once shown.
  Widget _inUse(List<List<JsonStep>> hidden) => dom.div(attributes: const <String, String>{'data-combat': 'in-use'}, <Widget>[
        AdvancedInUse(
          count: hidden.length,
          problems: hiddenProblems(hidden, _tab.diagnostics).length,
          expanded: _revealed,
          onShow: () => setState(() => _revealed = !_revealed),
        ),
      ]);

  /// Imported records and keys the modules do not know, kept as written.
  Widget _more(ModuleForm combat, ModuleForm health) {
    final List<Widget> rows = <Widget>[
      for (final String key in combatImportRecords)
        if (combat.map.containsKey(key)) keptField(combat, <JsonStep>[key], combatWords(key)),
      if (combat.value is Map<String, Object?>) otherFields(combat, const <JsonStep>[], combatRootKeys.toSet()),
      if (health.value is Map<String, Object?>) otherFields(health, const <JsonStep>[], healthKeys.toSet()),
    ];
    final bool any = combatImportRecords.any(combat.map.containsKey) ||
        combat.map.keys.any((String key) => !combatRootKeys.contains(key)) ||
        health.map.keys.any((String key) => !healthKeys.contains(key));
    if (!any) return const Component.fragment(<Widget>[]);
    return MoreFields(key: const ValueKey<String>('combat:more'), children: rows);
  }

  Future<void> _remove(String key, String name, String what) async {
    final bool remove = await askToConfirm(
      title: 'Remove $name?',
      message: '$what are deleted. Undo brings them back.',
      confirmLabel: 'Remove $name',
      destructive: true,
    );
    if (remove && mounted) _tab.onModule(key, null);
  }
}
