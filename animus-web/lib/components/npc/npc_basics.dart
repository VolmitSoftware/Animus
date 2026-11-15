/// The Basics tab of an NPC: name, body, skin, spawned and where it stands.
/// Advanced adds who it is on disk (id, folder, UUID, owner), its tags and
/// trait groups, named facings and anchors, and the skin by hash.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/group_create.dart';
import '../../logic/npc/group_model.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_display.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/alert.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart';
import 'body_type_field.dart';
import 'body_type_parts.dart';
import 'group_parts.dart';
import 'npc_form.dart';
import 'npc_parts.dart';
import 'skin_field.dart';
import 'npc_layout.dart';
import '../suggest/autocomplete_input.dart';
import '../suggest/suggest_scope.dart';
import '../../ui/number_input.dart';
import '../gui/gui_stack.dart';

class NpcBasicsTab extends StatelessWidget {
  const NpcBasicsTab({required this.form, required this.extras, required this.onReveal, this.inUse = 0, super.key});

  final NpcForm form;

  /// Advanced, or Simple after Show: the extra fields are shown.
  final bool extras;
  final void Function() onReveal;

  /// The settings Simple counts on this tab's line.
  final int inUse;

  @override
  Widget build(BuildContext context) {
    final NpcForm form = this.form;
    final JsonMap doc = form.doc;
    final String type = form.type;
    final List<MisplacedField> misplaced = misplacedBodyFields(doc, type);
    return tabBody(<Widget>[
      _Name(form: form),
      Field(
        label: 'Body',
        forId: 'animus-npc-body',
        tip: 'What the NPC looks like: a player, a villager, a zombie.',
        docs: Docs.npcAppearance,
        problems: diagnosticsAt(form.diagnostics, r'$.type'),
        child: _BodyType(form: form),
      ),
      if (misplaced.isNotEmpty)
        MisplacedFields(
          fields: misplaced,
          onRemove: (String field) => form.write(<JsonStep>[field], null),
          onRemoveAll: () => form.onJson((Object? latest) => withoutMisplacedBodyFields(asMap(latest), type), coalesce: 'npc:misplaced'),
        ),
      if (type == 'minecraft:player') ...<Widget>[
        NpcSkinPart(form: form),
        if (!form.group) NpcSkinOptions(form: form, all: extras),
      ] else
        NoSkinNote(diagnostics: form.diagnostics),
      Field(
        label: 'Spawned',
        tip: 'Spawned: in the world. Despawned: saved, not in the world.',
        docs: Docs.npcActivation,
        problems: diagnosticsAt(form.diagnostics, r'$.spawned'),
        child: ArcaneToggleSwitch(value: doc['spawned'] == true, ariaLabel: 'Spawned', onChanged: (bool on) => form.write(const <JsonStep>['spawned'], on), size: ComponentSize.sm),
      ),
      NpcWhere(form: form),
      if (!extras) AdvancedInUse(count: inUse, onShow: onReveal),
      if (extras) ..._extras(form),
    ]);
  }

  List<Widget> _extras(NpcForm form) {
    final JsonMap doc = form.doc;
    return <Widget>[
      _HeadTilt(form: form),
      AppSection(
        title: 'Identity',
        open: false,
        tip: 'The file id and folder under npcs/, the UUID the server knows it by, and the owning player.',
        docs: Docs.npc,
        attributes: const <String, String>{'data-npc': 'identity'},
        children: <Widget>[
          NpcValueRow(label: 'Id', value: form.document.id, tip: 'The file id under npcs/. Change it from the page menu.'),
          NpcValueRow(label: 'Folder', value: 'npcs/${npcValueText(doc['group']) == '-' ? '' : '${doc['group']}/'}', tip: 'Where the file is kept. Sorting only.'),
          NpcValueRow(label: 'UUID', value: npcValueText(doc['uuid']), tip: 'Never changes.'),
          _Owner(form: form),
        ],
      ),
      NpcTagsField(
        tags: stringList(doc['tags']),
        known: () => <String>[for (final (String tag, int _) in npcTags(npcEntries(form.workspaceDocuments))) tag],
        diagnostics: form.diagnostics,
        onChanged: (List<String>? tags) => form.write(const <JsonStep>['tags'], tags),
      ),
      _TraitGroups(form: form),
      AppSection(
        title: 'Facings and anchors',
        open: doc.containsKey('poses') || doc.containsKey('anchors'),
        tip: 'Named directions the face action turns to, and named spots behaviors use.',
        docs: Docs.npcPoses,
        children: <Widget>[
          form.field('poses', label: 'Named facings'),
          form.field('anchors', label: 'Anchors'),
        ],
      ),
    ];
  }
}

/// The name shown above the NPC, with colour codes.
class _Name extends StatelessWidget {
  const _Name({required this.form});

  final NpcForm form;

  @override
  bool shouldRebuild(_Name newComponent) => newComponent.form.doc['name'] != form.doc['name'] || newComponent.form.diagnostics.length != form.diagnostics.length;

  @override
  Widget build(BuildContext context) => Field(
        label: 'Name',
        forId: 'animus-npc-name',
        tip: 'Shown above the NPC. & codes such as &a set the colour.',
        docs: Docs.npcText,
        problems: diagnosticsAt(form.diagnostics, r'$.name'),
        child: AutocompleteInput(
          id: 'animus-npc-name',
          value: asString(form.doc['name']) ?? '',
          label: 'Name',
          profile: FieldProfile.richText,
          onChanged: (String text) => form.write(const <JsonStep>['name'], text),
        ),
      );
}

/// Where the NPC stands: its world, the block it stands on and the way it
/// faces, rounded for reading (an untouched field keeps its stored value).
/// Keys of `position` this does not show are kept as written.
class NpcWhere extends StatelessWidget {
  const NpcWhere({required this.form, super.key});

  final NpcForm form;

  JsonMap get _position => asMap(form.doc['position']);

  @override
  bool shouldRebuild(NpcWhere newComponent) =>
      !identical(newComponent.form.doc['position'], form.doc['position']) || newComponent.form.diagnostics.length != form.diagnostics.length;

  Widget _axis(String key, String label) => namedAbove(
        label,
        NumberInput(
          id: 'animus-npc-$key',
          value: roundedForField(asDouble(_position[key]), 2),
          integer: false,
          label: label,
          onChanged: (num? value) => form.write(<JsonStep>['position', key], value),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final List<Diagnostic> problems = form.diagnostics;
    return Component.fragment(<Widget>[
      Field(
        label: 'World',
        forId: 'animus-npc-world',
        tip: 'The world the NPC stands in. Position is the block it stands on.',
        docs: Docs.npcPoses,
        problems: diagnosticsAt(problems, r'$.position.world'),
        child: AutocompleteInput(
          id: 'animus-npc-world',
          value: asString(_position['world']) ?? '',
          label: 'World',
          placeholder: 'world',
          onChanged: (String text) => form.write(const <JsonStep>['position', 'world'], text.trim()),
          profile: FieldProfile.none,
        ),
      ),
      Field(
        label: 'Position',
        tip: 'The block the NPC stands on. Y is the height.',
        problems: <Diagnostic>[
          for (final String key in const <String>['x', 'y', 'z']) ...diagnosticsAt(problems, '\$.position.$key'),
        ],
        child: equalLine(<Widget>[_axis('x', 'X'), _axis('y', 'Y'), _axis('z', 'Z')]),
      ),
      Field(
        label: 'Facing',
        forId: 'animus-npc-yaw',
        hint: 'Degrees: 0 south, 90 west, 180 north, -90 east.',
        problems: diagnosticsAt(problems, r'$.position.yaw'),
        child: NumberInput(digits: 7, id: 'animus-npc-yaw', value: roundedForField(asDouble(_position['yaw']), 1), integer: false, label: 'Facing', onChanged: (num? value) => form.write(const <JsonStep>['position', 'yaw'], value)),
      ),
    ]);
  }
}

/// The position's `pitch`: how far the head looks up or down.
class _HeadTilt extends StatelessWidget {
  const _HeadTilt({required this.form});

  final NpcForm form;

  @override
  Widget build(BuildContext context) => Field(
        label: 'Head tilt',
        forId: 'animus-npc-pitch',
        hint: 'Degrees: -90 looks up, 90 looks down.',
        problems: diagnosticsAt(form.diagnostics, r'$.position.pitch'),
        child: NumberInput(digits: 5, 
          id: 'animus-npc-pitch',
          value: roundedForField(asDouble(asMap(form.doc['position'])['pitch']), 1),
          integer: false,
          label: 'Head tilt',
          onChanged: (num? value) => form.write(const <JsonStep>['position', 'pitch'], value),
        ),
      );
}

/// The body type, with the question when a skin would block it.
class _BodyType extends StatelessWidget {
  const _BodyType({required this.form});

  final NpcForm form;

  @override
  bool shouldRebuild(_BodyType newComponent) => newComponent.form.doc['type'] != form.doc['type'] || newComponent.form.doc.containsKey('skin') != form.doc.containsKey('skin');

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = form.doc;
    final List<String> groups = skinGroupsOf(form.workspaceDocuments, doc);
    final SkinCommit? commit = form.skinEditing?.commit;
    return BodyTypeField(
      id: 'animus-npc-body',
      value: asString(doc['type']) ?? '',
      skinned: doc.containsKey('skin'),
      skinGroups: groups,
      otherMembers: otherGroupMembers(form.workspaceDocuments, form.document.id, groups),
      onChanged: (String value) => form.write(const <JsonStep>['type'], value),
      onChangeWithoutSkin: (String type) {
        if (commit != null) {
          commit(typeWithoutSkin(documents: form.workspaceDocuments, npcId: form.document.id, type: type));
        } else {
          form.onJson((Object? latest) => <String, Object?>{...asMap(latest), 'type': type}..remove('skin'));
        }
      },
    );
  }
}

/// The skin worn: an NPC's on Basics, a trait group's on Look.
class NpcSkinPart extends StatelessWidget {
  const NpcSkinPart({required this.form, super.key});

  final NpcForm form;

  @override
  bool shouldRebuild(NpcSkinPart newComponent) =>
      newComponent.form.doc['skin'] != form.doc['skin'] ||
      wearersMoved(form.workspaceDocuments, newComponent.form.workspaceDocuments, asString(form.doc['skin'])) ||
      newComponent.form.documents(DocumentKind.skin).length != form.documents(DocumentKind.skin).length ||
      !identical(newComponent.form.resources, form.resources) ||
      newComponent.form.diagnostics.length != form.diagnostics.length;

  @override
  Widget build(BuildContext context) => SkinField(
        target: SkinTarget(form.group ? DocumentKind.group : DocumentKind.npc, form.document.id),
        value: asString(form.doc['skin']),
        skins: form.documents(DocumentKind.skin),
        documents: form.workspaceDocuments,
        resources: form.resources,
        onCommit: form.skinEditing?.commit,
        sign: form.skinEditing?.sign,
        diagnostics: form.diagnostics,
        onLoaded: form.onSkinLoaded,
        onChanged: (String? value) => form.write(const <JsonStep>['skin'], value),
      );
}

/// `animus:skin`: Follow player (the skin came from a player name and the
/// server puts their new one on) and Skin from name (with no skin, the skin
/// of the account named like the NPC). Simple shows each one where it
/// applies or while it is on; [all] shows both.
class NpcSkinOptions extends StatelessWidget {
  const NpcSkinOptions({required this.form, required this.all, super.key});

  final NpcForm form;
  final bool all;

  static const String module = 'animus:skin';

  void _set(String key, bool on) {
    final JsonMap next = <String, Object?>{...asMap(form.modules[module])}..remove('enabled');
    if (on) {
      next[key] = true;
    } else {
      next.remove(key);
    }
    form.putModule(module, next.isEmpty ? null : next);
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap options = asMap(form.modules[module]);
    final bool follow = options['refresh'] == true, fromName = options['fromName'] == true;
    final String? skin = asString(form.doc['skin']);
    final AnimusDocument? worn = skin == null ? null : form.documents(DocumentKind.skin).where((AnimusDocument d) => d.id == skin).firstOrNull;
    final bool byName = worn != null && asMap(worn.json)['player'] is String;
    final List<Diagnostic> problems = diagnosticsAt(form.diagnostics, r'$.modules.animus:skin');
    return dom.div(attributes: const <String, String>{'data-npc': 'skin-options'}, <Widget>[
      if (all || follow || byName)
        Field(
          label: 'Follow player',
          tip: 'Wears the player\'s new skin when they change it. Works for a skin set by player name.',
          docs: Docs.npcSkins,
          problems: problems,
          child: ArcaneToggleSwitch(value: follow, ariaLabel: 'Follow player', onChanged: (bool on) => _set('refresh', on), size: ComponentSize.sm),
        ),
      if (all || fromName || skin == null)
        Field(
          label: 'Skin from name',
          tip: 'With no skin set, wears the skin of the Minecraft account named like the NPC.',
          docs: Docs.npcSkins,
          child: ArcaneToggleSwitch(value: fromName, ariaLabel: 'Skin from name', onChanged: (bool on) => _set('fromName', on), size: ComponentSize.sm),
        ),
    ]);
  }
}

class _Owner extends StatelessWidget {
  const _Owner({required this.form});

  final NpcForm form;

  @override
  Widget build(BuildContext context) {
    final String? owner = asString(form.doc['owner']);
    final List<Diagnostic> found = diagnosticsAt(form.diagnostics, r'$.owner');
    final String? name = owner == null ? null : SuggestScope.sourcesOf(context).owners[owner];
    return Field(
      label: 'Owner',
      hint: name,
      tip: 'The player who owns this NPC, as a lowercase UUID; none for a server NPC. Its owner can change it in game, and it can only do what their animus.owned nodes allow.',
      docs: Docs.npcOwner,
      problems: <Diagnostic>[
        ...found,
        if (owner != null && owner.isNotEmpty && !validOwner(owner) && found.isEmpty) const Diagnostic(DiagnosticSeverity.error, r'$.owner', 'Expected a lowercase UUID.'),
      ],
      child: AutocompleteInput(
        value: owner ?? '',
        label: 'Owner UUID',
        onChanged: (String text) => form.write(const <JsonStep>['owner'], text.trim().isEmpty ? null : text.trim()),
        profile: FieldProfile.none,
      ),
    );
  }
}

/// The NPC's trait groups in merge order, and what it takes from them.
class _TraitGroups extends StatelessWidget {
  const _TraitGroups({required this.form});

  final NpcForm form;

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = form.doc;
    final Map<String, JsonMap> groups = form.groups;
    final ConversationCommit? commit = form.onCommit;
    final NpcEffective? effective = npcGroups(doc).isEmpty ? null : effectiveNpc(doc, groups);
    return AppSection(
      title: 'Trait groups',
      open: npcGroups(doc).isNotEmpty,
      tip: "Shared settings this NPC takes, first to last. A later group wins over an earlier one; the NPC's own fields win over all.",
      docs: Docs.groupMerge,
      attributes: const <String, String>{'data-npc': 'trait-groups'},
      children: <Widget>[
        NpcGroupsList(
          npc: doc,
          groups: groups,
          diagnostics: form.diagnostics,
          onOpen: form.onOpen,
          onCreate: commit == null
              ? null
              : (String search) async {
                  final GroupCreation made = groupFromSearch(search, groups.keys.toSet());
                  await commit(made.changes(form.document.id), 'new group ${made.id}');
                  return made.id;
                },
          onChanged: (List<String>? next) => form.write(const <JsonStep>['groups'], next),
        ),
        if (effective != null) ...<Widget>[
          if (effective.missing.isNotEmpty)
            GuiNote(message: 'Not in this workspace: ${effective.missing.join(', ')}.', tone: AlertTone.warning),
          AppSection(
            title: 'What it takes',
            open: false,
            attributes: const <String, String>{'data-npc': 'effective'},
            tip: 'The definition the server runs after the groups merge, with where each value comes from.',
            children: <Widget>[EffectiveTable(effective: effective, known: groups.keys.toSet(), onOpen: form.onOpen)],
          ),
        ],
      ],
    );
  }
}
