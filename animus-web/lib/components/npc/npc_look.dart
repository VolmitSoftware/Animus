/// The Look tab: what the NPC holds and wears, the text above it and how it
/// stands, plus its body's own settings (a villager's profession, an armor
/// stand's parts). Rare flags sit under More. Advanced adds the rig, the
/// skin layers and arm model, the boss bar, who sees it and click redirect.
/// A trait group's skin and skin pool sit here too, since a group has no
/// Basics tab.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/select/select_model.dart' show withDefault;
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/editor_mode.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import 'appearance_editor.dart';
import 'body_type_parts.dart';
import 'equipment_slots.dart';
import 'group_parts.dart';
import 'npc_basics.dart' show NpcSkinPart;
import 'npc_form.dart';
import 'npc_parts.dart';
import 'skin_field.dart';
import 'npc_layout.dart';
import '../select/animus_select.dart';

/// What each body trait reads as.
const Map<String, String> traitLabels = <String, String>{
  'glowing': 'Glowing',
  'teamColor': 'Glow colour',
  'baby': 'Baby',
  'usingItem': 'Using item',
  'listed': 'In tab list',
  'gravity': 'Gravity',
};

/// The object fields about who sees the NPC.
const List<(String, String, String)> _audience = <(String, String, String)>[
  ('bossBar', 'Boss bar', 'A boss bar shown to players near the NPC.'),
  ('viewers', 'Viewers', 'Which players see the NPC; others are never sent its body.'),
];

class NpcLookTab extends StatelessWidget {
  const NpcLookTab({required this.form, required this.extras, required this.onReveal, this.inUse = 0, super.key});

  final NpcForm form;
  final bool extras;
  final void Function() onReveal;

  /// The settings Simple counts on this tab's line.
  final int inUse;

  @override
  Widget build(BuildContext context) {
    final NpcForm form = this.form;
    final JsonMap doc = form.doc;
    final String type = form.type;
    final bool player = form.group || type == 'minecraft:player';
    return tabBody(<Widget>[
      if (form.group) ...<Widget>[
        NpcSkinPart(form: form),
        GroupSkinPool(
          pool: stringList(doc['skins']),
          skins: form.documents(DocumentKind.skin),
          resources: form.resources,
          diagnostics: form.diagnostics,
          onChanged: (List<String>? pool) => form.write(const <JsonStep>['skins'], pool),
        ),
      ],
      Field(
        label: 'Equipment',
        tip: 'What the NPC holds and wears. Pick a slot, then an item.',
        docs: Docs.npcAppearance,
        child: EquipmentSlots(
          value: doc['equipment'],
          bodyType: type,
          components: extras,
          diagnostics: form.diagnostics,
          onChanged: (Object? value, {Object? from}) => form.emit(const <JsonStep>['equipment'], value, coalesce: 'npc:equipment', from: from),
        ),
      ),
      Field(
        label: 'Hologram',
        tip: 'Lines of text above the NPC. & codes such as &a set the colour.',
        docs: Docs.npcText,
        child: HologramLinesEditor(
          value: doc['hologram'],
          schema: form.schema,
          diagnostics: form.diagnostics,
          onChanged: (Object? value) => form.write(const <JsonStep>['hologram'], value),
        ),
      ),
      if (player) _Pose(form: form),
      if (type == 'minecraft:armor_stand') ...<Widget>[
        const NpcHeading('Armor stand', tip: 'Which parts show, and each part\'s angle in degrees: x tilts forward, y turns, z tilts sideways.', docs: Docs.npcFlags),
        ArmorStandEditor(
          value: doc['armorStand'],
          schema: form.schema,
          diagnostics: form.diagnostics,
          onChanged: (Object? value) => form.write(const <JsonStep>['armorStand'], value),
        ),
      ],
      if (bodySettingsField(type) != 'player')
        // Show reveals what Simple keeps back here too, an item's component text.
        ModeScope(
          mode: extras ? EditorMode.advanced : ModeScope.of(context),
          onChanged: ModeScope.maybeOf(context)?.onChanged ?? (EditorMode _) {},
          child: BodySettingsEditor(
            type: type,
            doc: doc,
            schema: form.schema,
            diagnostics: form.diagnostics,
            onChanged: (String field, Object? value) => form.write(<JsonStep>[field], value),
          ),
        ),
      if (!extras) AdvancedInUse(count: inUse, onShow: onReveal),
      if (extras) ..._extras(form, player),
      MoreFields(children: <Widget>[
        _Nameplate(form: form),
        for (final String trait in form.group ? npcTraits : traitsFor(type)) form.field(trait, label: traitLabels[trait]),
        if (!form.group)
          for (final String trait in npcTraits)
            if (!traitsFor(type).contains(trait) && doc.containsKey(trait)) form.field(trait, label: traitLabels[trait]),
      ]),
    ]);
  }

  List<Widget> _extras(NpcForm form, bool player) {
    final JsonMap doc = form.doc;
    final String? skin = asString(doc['skin']);
    final AnimusDocument? worn = skin == null ? null : form.documents(DocumentKind.skin).where((AnimusDocument d) => d.id == skin).firstOrNull;
    return <Widget>[
      _Rig(form: form),
      if (player)
        AppSection(
          title: 'Skin layers',
          open: doc.containsKey('player'),
          tip: 'Which outer layers of the skin show (hat, jacket, sleeves, trousers, cape), and the arm model.',
          docs: Docs.npcAppearance,
          attributes: const <String, String>{'data-npc': 'skin-layers'},
          children: <Widget>[
            if (worn != null)
              SkinArmsField(
                skin: worn,
                target: SkinTarget(form.group ? DocumentKind.group : DocumentKind.npc, form.document.id),
                documents: form.workspaceDocuments,
                resources: form.resources,
                onCommit: form.skinEditing?.commit,
                onLoaded: form.onSkinLoaded,
              ),
            BodySettingsEditor(
              type: 'minecraft:player',
              doc: doc,
              schema: form.schema,
              diagnostics: form.diagnostics,
              heading: false,
              onChanged: (String field, Object? value) => form.write(<JsonStep>[field], value),
            ),
          ],
        ),
      for (final (String key, String title, String tip) in _audience)
        AppSection(
          title: title,
          open: doc.containsKey(key),
          tip: tip,
          docs: Docs.npcAppearance,
          children: <Widget>[
            if (form.spec(key).schema.isNotEmpty)
              InspectObject(
                key: ValueKey<String>('npc:$key'),
                schema: form.schema,
                node: form.spec(key).schema,
                value: asMap(doc[key]),
                path: childPath(r'$', key),
                diagnostics: form.diagnostics,
                onChanged: (JsonMap value) => form.write(<JsonStep>[key], value.isEmpty ? null : value),
              ),
          ],
        ),
      AppSection(
        title: 'Click redirect',
        open: doc.containsKey(clickRedirectField),
        tip: 'Clicking this NPC counts as clicking the NPC with this UUID while it is active.',
        docs: Docs.npcAppearance,
        children: <Widget>[form.field(clickRedirectField, label: 'Redirect to')],
      ),
    ];
  }
}

class _Pose extends StatelessWidget {
  const _Pose({required this.form});

  final NpcForm form;

  @override
  Widget build(BuildContext context) {
    const String poseId = 'animus-npc-pose';
    final List<String> poses = posesFor('minecraft:player');
    final String? pose = asString(form.doc['pose']);
    return Field(
      label: 'Pose',
      forId: poseId,
      hint: form.group ? 'Player bodies only.' : null,
      tip: 'How a player body stands, sits or lies. Other bodies always stand.',
      docs: Docs.npcPoses,
      problems: diagnosticsAt(form.diagnostics, r'$.pose'),
      child: AnimusSelect.values(
        id: poseId,
        value: withDefault(pose, poses, 'standing').value,
        values: withDefault(pose, poses, 'standing').options,
        labels: <String, String>{for (final String each in poses) each: '${each[0].toUpperCase()}${each.substring(1)}'},
        allowNone: true,
        noneLabel: 'Standing',
        onChanged: (String? next) => form.write(const <JsonStep>['pose'], next == 'standing' ? null : next),
      ),
    );
  }
}

class _Nameplate extends StatelessWidget {
  const _Nameplate({required this.form});

  final NpcForm form;

  @override
  Widget build(BuildContext context) {
    final bool visible = asMap(form.doc['nameplate'])['visible'] != false;
    return Field(
      label: 'Name shown',
      tip: 'Whether the name shows above the NPC. Hologram lines still show when it is off.',
      docs: Docs.npcText,
      problems: diagnosticsAt(form.diagnostics, r'$.nameplate', nested: true),
      child: ArcaneToggleSwitch(
        value: visible,
        label: 'Name shown',
        onChanged: (bool on) => form.write(const <JsonStep>['nameplate'], on ? null : <String, Object?>{'visible': false}),
        size: ComponentSize.sm,
      ),
    );
  }
}

/// A rig document whose model the NPC shows in place of its body.
class _Rig extends StatelessWidget {
  const _Rig({required this.form});

  final NpcForm form;

  @override
  Widget build(BuildContext context) {
    const String rigId = 'animus-npc-rig';
    final String? rig = asString(asMap(form.doc['rig'])['id']);
    final List<String> rigs = form.ids(DocumentKind.rig);
    final void Function(DocumentKind, String)? open = form.onOpen;
    return Field(
      label: 'Rig',
      forId: rigId,
      hint: rigs.isEmpty ? 'No rigs yet.' : null,
      tip: 'Custom model parts drawn in place of the body, with animations. Import a Blockbench model to make one.',
      docs: Docs.rigs,
      problems: <Diagnostic>[
        ...diagnosticsAt(form.diagnostics, r'$.rig', nested: true),
        if (rig != null && !rigs.contains(rig)) const Diagnostic(DiagnosticSeverity.warning, r'$.rig', 'Not in this workspace; the server may have it.'),
      ],
      child: ActionRow(
        label: 'Rig',
        leading: <Widget>[
          NpcPick(child: AnimusSelect.values(
            references: DocumentKind.rig,
            id: rigId,
            value: rig,
            values: rigs,
            allowNone: true,
            noneLabel: 'No rig',
            onChanged: (String? id) => form.write(const <JsonStep>['rig'], id == null ? null : <String, Object?>{'id': id}),
          )),
        ],
        secondary: <RowAction>[
          if (rig != null && rigs.contains(rig) && open != null) RowAction('Open rig', () => open(DocumentKind.rig, rig)),
        ],
      ),
    );
  }
}
