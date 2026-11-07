/// The NPC definition's Visual view, read top down: identity, body,
/// attributes, position, appearance (skin, equipment, hologram, nameplate), behaviour
/// (behaviors, route, movement, inventory), modules (combat, shop, mount and
/// the rest), then fields kept as written. Each part's heading summarises it
/// while closed. Edits change the parsed definition and report it whole, so
/// unknown keys survive.
///
/// An NPC that lists trait groups also gets its Groups list under Identity
/// and an Effective part: the merged definition with each value's source.
/// With [NpcInspector.forGroup] the same parts edit a group's `values`
/// instead: no identity, position or inventory, a description, and a skin
/// pool beside the skin.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/citizens/definition_validation.dart' show effectiveActivationDiagnostics;
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/combat_settings.dart';
import '../../logic/npc/group_merge.dart';
import '../../logic/npc/group_model.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/npc_attributes.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/reference_options.dart';
import '../../logic/npc/route_copy.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart';
import '../npc/appearance_editor.dart';
import '../npc/attributes_editor.dart';
import '../npc/behavior_list.dart';
import '../npc/body_type_field.dart';
import '../npc/body_type_parts.dart';
import '../npc/combat_editor.dart';
import '../npc/equipment_editor.dart';
import '../npc/group_parts.dart';
import '../npc/inventory_editor.dart';
import '../npc/npc_modules.dart';
import '../npc/route_editor.dart' show routeRecordings;
import '../npc/npc_section.dart';
import '../npc/skin_picker.dart';
import '../info_tip.dart';
import 'inspect_fields.dart';
import 'typed_values_editor.dart';
import '../select/suggest_input.dart';

/// Keys the NPC inspector lays out itself; the rest show under kept fields.
const Set<String> _laidOut = <String>{
  'schemaVersion',
  'revision',
  'uuid',
  'id',
  'group',
  'name',
  'type',
  'spawned',
  'position',
  'skin',
  'behaviors',
  'modules',
  'tags',
  'equipment',
  'owner',
  'rig',
  'pose',
  'armorStand',
  'horse',
  'llama',
  'frog',
  'armadillo',
  'dragon',
  'hologram',
  'nameplate',
  'inventory',
  'poses',
  'movement',
  'groups',
  'skins',
  attributesField,
  ..._traits,
};

/// [diagnostics] of a group document moved from `$.values` to the root, so
/// the parts find them where an NPC's would be.
List<Diagnostic> _valuesDiagnostics(List<Diagnostic> diagnostics) => <Diagnostic>[
  for (final Diagnostic d in diagnostics)
    if (d.path == r'$.values')
      Diagnostic(d.severity, r'$', d.message, code: d.code)
    else if (d.path.startsWith(r'$.values.') || d.path.startsWith(r'$.values['))
      Diagnostic(d.severity, '\$${d.path.substring(8)}', d.message, code: d.code),
];

/// Body traits drawn as plain schema fields.
const List<String> _traits = <String>['glowing', 'teamColor', 'baby', 'usingItem', 'listed', 'gravity'];

/// Modules with their own editor here.
const Set<String> _typedModules = <String>{navigationModule, combatModule, shopModule, mountModule};

class NpcInspector extends StatefulWidget {
  const NpcInspector({
    required this.document,
    required this.onJson,
    required this.schemas,
    this.diagnostics = const <Diagnostic>[],
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onSkinLoaded,
    this.onOpen,
    this.onCommit,
    this.serverBehaviors = const <String>[],
    this.skinEditing,
    super.key,
  }) : forGroup = false;

  /// The inspector over a group document's `values`.
  const NpcInspector.forGroup({
    required this.document,
    required this.onJson,
    required this.schemas,
    this.diagnostics = const <Diagnostic>[],
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onSkinLoaded,
    this.onOpen,
    this.onCommit,
    this.serverBehaviors = const <String>[],
    this.skinEditing,
    super.key,
  }) : forGroup = true;

  final AnimusDocument document;

  /// Whether [document] is a trait group and the parts edit its `values`.
  final bool forGroup;
  final JsonEdit onJson;
  final InspectSchemas schemas;

  /// The browser's and the server's diagnostics together.
  final List<Diagnostic> diagnostics;

  /// Every document of the workspace: behaviors, skins, routes, shops, rigs
  /// and the other NPCs (their tags and squads) are offered from here.
  final List<AnimusDocument> workspaceDocuments;

  /// A skin PNG was loaded or a placeholder picked; the stage beside the
  /// inspector redraws.
  final void Function()? onSkinLoaded;

  /// Opens another document of the workspace.
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Writes several documents as one undoable change (copying a route for
  /// this NPC); null hides that action.
  final ConversationCommit? onCommit;

  /// Behavior ids in the connected server's catalog, offered beside the
  /// workspace's.
  final List<String> serverBehaviors;

  /// Skin edits that write several documents (a new skin, a model change, a
  /// body type without its skin); null leaves those out.
  final SkinEditing? skinEditing;

  @override
  State<NpcInspector> createState() => _NpcInspectorState();
}

class _NpcInspectorState extends State<NpcInspector> {
  String? _newModule;
  String _newTag = '';
  final Set<String> _asJson = <String>{};

  /// Phone widths open with only the identity part showing.
  final bool _narrow = npcInspectorNarrow;

  bool get _group => component.forGroup;
  JsonMap get _file => asMap(component.document.json);

  /// The definition fields the parts edit: the NPC, or the group's values.
  JsonMap get _doc => _group ? asMap(_file['values']) : _file;
  SchemaView get _schema => component.schemas.npc;
  List<Diagnostic> get _diagnostics =>
      _group ? _valuesDiagnostics(component.diagnostics) : <Diagnostic>[...component.diagnostics, ...effectiveActivationDiagnostics(_file, _groups)];

  /// A group has no body type; armor stand values or one body's own settings
  /// imply one, as the server's check assumes.
  String get _type {
    if (!_group) return asString(_doc['type']) ?? 'minecraft:player';
    if (_doc.containsKey('armorStand')) return 'minecraft:armor_stand';
    for (final MapEntry<String, String> settings in bodySettingsTypes.entries) {
      if (_doc.containsKey(settings.key)) return settings.value;
    }
    return 'minecraft:player';
  }

  /// Writes [value] at [steps] of the definition fields, which sit under
  /// `values` when editing a group.
  /// [from] is what the editor saw at [steps] when that is ahead of the
  /// document.
  void _emit(List<JsonStep> steps, Object? value, {required String coalesce, Object? from}) {
    final List<JsonStep> at = <JsonStep>[if (_group) 'values', ...steps];
    component.onJson(writeAt(from == null ? _file : writeIn(_file, at, from), at, value), coalesce: _group ? 'group:$coalesce' : coalesce);
  }

  List<AnimusDocument> _documents(DocumentKind kind) => <AnimusDocument>[
    for (final AnimusDocument document in component.workspaceDocuments)
      if (document.kind == kind) document,
  ];

  List<String> _ids(DocumentKind kind) => <String>[for (final AnimusDocument document in _documents(kind)) document.id];

  void _write(List<JsonStep> steps, Object? value) => _emit(steps, value, coalesce: 'npc:${steps.join('.')}');

  FieldSpec _spec(String key) {
    final List<FieldSpec> fields = _schema.fields(_schema.root, _doc);
    return fields.where((FieldSpec field) => field.key == key).firstOrNull ?? FieldSpec(key: key, kind: FieldKind.any);
  }

  Widget _field(String key, {List<FieldOption> suggestions = const <FieldOption>[], String? label, FieldProfile profile = FieldProfile.none}) => InspectField(
    key: ValueKey<String>('npc:$key'),
    profile: profile,
    field: _spec(key),
    value: _doc[key],
    path: childPath(r'$', key),
    diagnostics: _diagnostics,
    suggestions: suggestions,
    schema: _schema,
    label: label,
    onChanged: (Object? value) => _write(<JsonStep>[key], value),
  );

  Widget _readOnly(String key, String label, String help) {
    final FieldSpec spec = _spec(key);
    return GuiRow(
      label: label,
      help: <String>[if (spec.description != null) spec.description!, help].join(' '),
      problems: diagnosticsAt(_diagnostics, childPath(r'$', key)),
      child: dom.code(classes: 'animus-inspect-const', <Widget>[Component.text('${_doc[key] ?? '-'}')]),
    );
  }

  JsonMap get _modules => asMap(_doc['modules']);

  /// Writes module [key]; [from] is the module an editor worked [value] out
  /// from when that is ahead of the document.
  void _putModule(String key, Object? value, {JsonMap? from}) {
    final JsonMap next = asMap(writeIn(_modules, <JsonStep>[key], value));
    _emit(const <JsonStep>['modules'], next.isEmpty ? null : next, coalesce: 'npc:module:$key', from: from == null ? null : <String, Object?>{..._modules, key: from});
  }

  /// Parts the reader opened or closed, by section id.
  final Map<String, bool> _opened = <String, bool>{};

  bool _open(String id, bool first) => _opened[id] ?? (first || !_narrow);

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    return dom.section(
      classes: 'animus-panel animus-inspector animus-npc-inspector',
      attributes: <String, String>{'aria-label': _group ? 'Group inspector' : 'NPC inspector'},
      <Widget>[
        if (component.schemas.live) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Using server schemas.')]),
        if (_group) _groupPart() else ...<Widget>[_identity(doc), if (npcGroups(doc).isNotEmpty) _effective(doc)],
        _body(doc),
        _attributes(doc),
        if (!_group) _position(doc),
        _appearance(doc),
        _behaviour(doc),
        _modulesPart(doc),
        _kept(doc),
      ],
    );
  }

  // Identity -----------------------------------------------------------------

  Widget _identity(JsonMap doc) {
    final String? owner = asString(doc['owner']);
    return NpcSection(
      id: 'identity',
      title: 'Identity',
      summary: '${asString(doc['name']) ?? ''}, ${component.document.id}',
      open: _open('identity', true),
      onToggle: (bool open) => setState(() => _opened['identity'] = open),
      children: <Widget>[
        _field('name', profile: FieldProfile.richText),
        _readOnly('id', 'ID', ''),
        _readOnly('group', 'Folder', ''),
        _readOnly('uuid', 'UUID', 'Never changes.'),
        _field('revision'),
        _tags(doc),
        _groupsList(doc),
        GuiRow(
          label: 'Owner',
          help: 'Player UUID, lowercase.',
          problems: <Diagnostic>[
            ...diagnosticsAt(_diagnostics, r'$.owner'),
            if (owner != null && owner.isNotEmpty && !validOwner(owner) && diagnosticsAt(_diagnostics, r'$.owner').isEmpty)
              const Diagnostic(DiagnosticSeverity.error, r'$.owner', 'Expected a lowercase UUID.'),
          ],
          child: GuiTextInput(
            value: owner ?? '',
            label: 'Owner UUID',
            onChanged: (String text) => _write(<JsonStep>['owner'], text.trim().isEmpty ? null : text.trim()),
          ),
        ),
      ],
    );
  }

  Widget _tags(JsonMap doc) {
    final List<String> tags = stringList(doc['tags']);
    final List<String> known = <String>[
      for (final (String tag, int _) in npcTags(npcEntries(component.workspaceDocuments)))
        if (!tags.contains(tag)) tag,
    ];
    void put(List<String> next) => _write(<JsonStep>['tags'], next.isEmpty ? null : next);
    final String adding = _newTag;
    final bool valid = npcTagPattern.hasMatch(adding) && !tags.contains(adding) && tags.length < npcTagLimit;
    return GuiRow(
      label: 'Tags',
      help: 'Searchable. a-z, 0-9, _ and -',
      problems: diagnosticsAt(_diagnostics, r'$.tags', nested: true),
      child: dom.div(classes: 'animus-tag-editor', <Widget>[
        dom.div(classes: 'animus-npc-tag-cloud', <Widget>[
          for (final String tag in tags)
            dom.span(
              classes: 'animus-npc-tag',
              attributes: <String, String>{'data-tag': tag},
              <Widget>[
                Component.text(tag),
                GuiIconButton(
                  icon: ArcaneIcon.x(size: IconSize.sm),
                  label: 'Remove tag $tag',
                  onPressed: () => put(<String>[...tags]..remove(tag)),
                ),
              ],
            ),
        ]),
        dom.div(classes: 'animus-gui-line', <Widget>[
          SuggestInput(
            value: adding,
            label: 'New tag',
            placeholder: 'guard',
            suggestions: <FieldOption>[for (final String tag in known) FieldOption(tag)],
            onChanged: (String value) => setState(() => _newTag = value.trim().toLowerCase()),
            onSubmit: valid
                ? () {
                    put(<String>[...tags, adding]);
                    setState(() => _newTag = '');
                  }
                : null,
          ),
          Button.ghost(
            label: 'Add tag',
            size: ButtonSize.small,
            disabled: !valid,
            onPressed: () {
              put(<String>[...tags, adding]);
              setState(() => _newTag = '');
            },
          ),
        ]),
      ]),
    );
  }

  // Groups -------------------------------------------------------------------

  Map<String, JsonMap> get _groups => workspaceGroups(component.workspaceDocuments);

  /// The NPC's `groups`: merge order, reorder, remove, add from the workspace.
  Widget _groupsList(JsonMap doc) => NpcGroupsList(
        npc: doc,
        groups: _groups,
        diagnostics: _diagnostics,
        onOpen: component.onOpen,
        onChanged: (List<String>? groups) => _write(<JsonStep>['groups'], groups),
      );

  /// The merged definition the server runs, one row per field or module,
  /// each with the group or the NPC that set it.
  Widget _effective(JsonMap doc) {
    final NpcEffective? effective = effectiveNpc(doc, _groups);
    if (effective == null) return const dom.div(<Widget>[]);
    final List<EffectiveRow> rows = effective.rows;
    final int fromGroups = rows.where((EffectiveRow row) => row.source != groupMergeNpc).length;
      return NpcSection(
      id: 'effective',
      title: 'Effective',
      summary: '$fromGroups value${fromGroups == 1 ? '' : 's'} from ${effective.groups.join(', ')}',
      open: _open('effective', false),
      onToggle: (bool open) => setState(() => _opened['effective'] = open),
      children: <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text("The NPC's own fields override its groups."),
        ]),
        if (effective.missing.isNotEmpty)
          dom.p(classes: 'animus-gui-problem is-warning', <Widget>[
            Component.text('Not in this workspace: ${effective.missing.join(', ')}.'),
          ]),
        EffectiveTable(effective: effective, known: _groups.keys.toSet(), onOpen: component.onOpen),
      ],
    );
  }

  /// A group's own part: its id, description, members and tags.
  Widget _groupPart() {
    final JsonMap file = _file;
    final String id = component.document.id;
    final String description = asString(file['description']) ?? '';
    final List<NpcEntry> members = <NpcEntry>[
      for (final NpcEntry entry in npcEntries(component.workspaceDocuments))
        if (npcGroups(entry.json).contains(id)) entry,
    ];
    const String descriptionId = 'animus-group-description';
    return NpcSection(
      id: 'group',
      title: 'Group',
      summary: '$id, ${members.length} NPC${members.length == 1 ? '' : 's'}',
      open: _open('group', true),
      onToggle: (bool open) => setState(() => _opened['group'] = open),
      children: <Widget>[
        GuiRow(
          label: 'ID',
          help: 'Rename updates member NPCs.',
          child: dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(id)]),
        ),
        GuiRow(
          label: 'Description',
          forId: descriptionId,
          help: 'Shown in /animus groups list.',
          problems: diagnosticsAt(component.diagnostics, r'$.description'),
          child: GuiTextInput(
            id: descriptionId,
            value: description,
            label: 'Group description',
            placeholder: 'What NPCs in this group share',
            onChanged: (String text) =>
                component.onJson(writeAt(file, const <JsonStep>['description'], text.trim().isEmpty ? null : text), coalesce: 'group:description'),
          ),
        ),
        GuiRow(
          label: 'Members',
          help: 'Assign in bulk from Browse NPCs.',
          child: members.isEmpty
              ? const dom.p(classes: 'animus-muted', <Widget>[Component.text('No members yet.')])
              : dom.ul(classes: 'animus-group-members', attributes: const <String, String>{'data-group': 'members'}, <Widget>[
                  for (final NpcEntry entry in members.take(24))
                    dom.li(<Widget>[
                      if (component.onOpen != null)
                        Button.ghost(
                          label: entry.name,
                          size: ButtonSize.small,
                          attributes: <String, String>{'aria-label': 'Open NPC ${entry.key}'},
                          onPressed: () => component.onOpen!(DocumentKind.npc, entry.key),
                        )
                      else
                        dom.span(<Widget>[Component.text(entry.name)]),
                      dom.code(classes: 'animus-muted', <Widget>[Component.text(entry.key)]),
                    ]),
                  if (members.length > 24) dom.li(classes: 'animus-muted', <Widget>[Component.text('and ${members.length - 24} more')]),
                ]),
        ),
        _tags(_doc),
      ],
    );
  }

  /// A group's `skins`: hashes each member takes one of by its UUID.
  Widget _skinPool(JsonMap doc) => GroupSkinPool(
        pool: stringList(doc['skins']),
        skins: _ids(DocumentKind.skin),
        diagnostics: _diagnostics,
        onChanged: (List<String>? pool) => _write(<JsonStep>['skins'], pool),
      );

  // Body ---------------------------------------------------------------------

  Widget _body(JsonMap doc) {
    final String type = _type;
    final String? rig = asString(asMap(doc['rig'])['id']);
    final List<String> rigs = _ids(DocumentKind.rig);
    final List<String> poses = posesFor(type);
    final List<MisplacedField> misplaced = _group ? const <MisplacedField>[] : misplacedBodyFields(doc, type);
    final String summary = <String>[
      if (!_group) type.startsWith('minecraft:') ? type.substring(10).replaceAll('_', ' ') : type,
      if (!_group) doc['spawned'] == true ? 'spawned' : 'despawned',
      if (rig != null) 'rig $rig',
      if (asString(doc['pose']) case final String pose when pose != 'standing') pose,
    ].join(', ');
    const String rigId = 'animus-npc-rig';
    const String poseId = 'animus-npc-pose';
    return NpcSection(
      id: 'body',
      title: 'Body',
      summary: summary,
      open: _open('body', false),
      onToggle: (bool open) => setState(() => _opened['body'] = open),
      children: <Widget>[
        if (!_group) GuiRow(
          label: fieldLabel(_spec('type'), label: 'Type'),
          help: _spec('type').description,
          problems: fieldProblems(_spec('type'), doc['type'], r'$.type', _diagnostics),
          child: _bodyType(doc),
        ),
        if (misplaced.isNotEmpty)
          MisplacedFields(
            fields: misplaced,
            onRemove: (String field) => _write(<JsonStep>[field], null),
            onRemoveAll: () => component.onJson((Object? latest) => withoutMisplacedBodyFields(asMap(latest), type), coalesce: 'npc:misplaced'),
          ),
        if (!_group) _field('spawned'),
        InspectRow(
          label: 'Rig',
          labelFor: rigId,
          help: rigs.isEmpty
              ? 'No rigs. Import a Blockbench model.'
              : 'Replaces the body.',
          problems: diagnosticsAt(_diagnostics, r'$.rig', nested: true),
          child: dom.div(classes: 'animus-gui-line', <Widget>[
            GuiSelect(
              references: DocumentKind.rig,
              id: rigId,
              value: rig,
              options: rigs,
              allowNone: true,
              noneLabel: 'No rig',
              onChanged: (String? id) => _write(<JsonStep>['rig'], id == null ? null : <String, Object?>{'id': id}),
            ),
            if (rig != null && rigs.contains(rig) && component.onOpen != null)
              Button.ghost(label: 'Open rig', size: ButtonSize.small, onPressed: () => component.onOpen!(DocumentKind.rig, rig)),
          ]),
        ),
        if (rig != null && !rigs.contains(rig))
          const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not in this workspace; may be on the server.')]),
        if (_group || type == 'minecraft:player') InspectRow(
          label: 'Pose',
          labelFor: poseId,
          help: _group ? 'Player bodies only.' : '',
          problems: diagnosticsAt(_diagnostics, r'$.pose'),
          child: GuiSelect(
            id: poseId,
            value: asString(doc['pose']),
            options: poses,
            allowNone: true,
            noneLabel: 'standing (default)',
            onChanged: (String? pose) => _write(<JsonStep>['pose'], pose == 'standing' ? null : pose),
          ),
        ),
        if (type == 'minecraft:armor_stand') ...<Widget>[
          const NpcSubhead('Armor stand'),
          ArmorStandEditor(
            value: doc['armorStand'],
            schema: _schema,
            diagnostics: _diagnostics,
            onChanged: (Object? value) => _write(<JsonStep>['armorStand'], value),
          ),
        ],
        BodySettingsEditor(
          type: type,
          doc: doc,
          schema: _schema,
          diagnostics: _diagnostics,
          onChanged: (String field, Object? value) => _write(<JsonStep>[field], value),
        ),
        const NpcSubhead('Traits'),
        for (final String trait in _group ? _traits : traitsFor(type)) _field(trait),
        if (!_group)
          for (final String trait in _traits)
            if (!traitsFor(type).contains(trait) && doc.containsKey(trait)) _field(trait),
      ],
    );
  }

  /// The type, with the question when a skin would block it.
  Widget _bodyType(JsonMap doc) {
    final List<String> groups = skinGroupsOf(component.workspaceDocuments, doc);
    final SkinCommit? commit = component.skinEditing?.commit;
    return BodyTypeField(
      value: asString(doc['type']) ?? '',
      skinned: doc.containsKey('skin'),
      skinGroups: groups,
      otherMembers: otherGroupMembers(component.workspaceDocuments, component.document.id, groups),
      onChanged: (String value) => _write(<JsonStep>['type'], value),
      onChangeWithoutSkin: (String type) {
        if (commit != null) {
          commit(typeWithoutSkin(documents: component.workspaceDocuments, npcId: component.document.id, type: type));
        } else {
          component.onJson((Object? latest) => <String, Object?>{...asMap(latest), 'type': type}..remove('skin'));
        }
      },
    );
  }

  // Attributes ---------------------------------------------------------------

  Widget _attributes(JsonMap doc) {
    final JsonMap set = bareAttributes(doc[attributesField]);
    final String summary = set.isEmpty
        ? 'vanilla defaults'
        : set.entries.map((MapEntry<String, Object?> entry) => '${entry.key} ${entry.value is num ? formatAttribute((entry.value! as num).toDouble()) : entry.value}').join(', ');
    return NpcSection(
      id: 'attributes',
      title: 'Attributes',
      summary: summary,
      open: _open('attributes', false),
      onToggle: (bool open) => setState(() => _opened['attributes'] = open),
      children: <Widget>[
        dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text(<String>[
            _spec(attributesField).description ?? 'Vanilla entity attributes mapped to base values.',
            if (_group) "Members take each attribute set here unless they set it themselves.",
          ].join(' ')),
        ]),
        AttributesEditor(
          value: doc[attributesField],
          specs: attributeSpecs(_schema),
          bodyType: _group ? null : _type,
          diagnostics: _diagnostics,
          onChanged: (JsonMap? attributes) => _write(const <JsonStep>[attributesField], attributes),
        ),
      ],
    );
  }

  // Position -----------------------------------------------------------------

  Widget _position(JsonMap doc) {
    final FieldSpec position = _spec('position');
    final JsonMap at = asMap(doc['position']);
    String n(Object? value) => value is num ? (value == value.roundToDouble() ? '${value.toInt()}' : '$value') : '?';
    return NpcSection(
      id: 'position',
      title: 'Position',
      summary: '${asString(at['world']) ?? 'no world'} ${n(at['x'])}, ${n(at['y'])}, ${n(at['z'])}',
      open: _open('position', false),
      onToggle: (bool open) => setState(() => _opened['position'] = open),
      children: <Widget>[
        if (position.schema.isNotEmpty)
          InspectObject(
            schema: _schema,
            node: position.schema,
            value: at,
            path: r'$.position',
            diagnostics: _diagnostics,
            onChanged: (JsonMap value) => _write(<JsonStep>['position'], value),
          ),
        _field('poses', label: 'Named facings'),
      ],
    );
  }

  // Appearance ---------------------------------------------------------------

  Widget _appearance(JsonMap doc) {
    final String? skin = asString(doc['skin']);
    final int items = asMap(doc['equipment']).length;
    final int lines = asList(asMap(doc['hologram'])['lines']).length;
    final bool nameplate = asMap(doc['nameplate'])['visible'] != false;
    final int pool = stringList(doc['skins']).length;
    final String summary = <String>[
      if (pool > 0)
        'pool of $pool skin${pool == 1 ? '' : 's'}'
      else if (skin == null)
        _group ? 'no skin' : 'default skin'
      else
        'skin ${skin.substring(0, skin.length.clamp(0, 8))}',
      '$items item${items == 1 ? '' : 's'}',
      if (lines > 0) '$lines hologram line${lines == 1 ? '' : 's'}',
      if (!nameplate) 'name hidden',
    ].join(', ');
    return NpcSection(
      id: 'appearance',
      title: 'Appearance',
      summary: summary,
      open: _open('appearance', false),
      onToggle: (bool open) => setState(() => _opened['appearance'] = open),
      children: <Widget>[
        const NpcSubhead('Skin'),
        if (_group || _type == 'minecraft:player')
          SkinPicker(
            npcId: component.document.id,
            target: SkinTarget(_group ? DocumentKind.group : DocumentKind.npc, component.document.id),
            value: skin,
            workspaceDocuments: component.workspaceDocuments,
            resources: component.skinEditing?.resources ?? const <String, Uint8List>{},
            onCommit: component.skinEditing?.commit,
            diagnostics: _diagnostics,
            onChanged: (String? value) => _write(<JsonStep>['skin'], value),
            onLoaded: component.onSkinLoaded,
          )
        else
          NoSkinNote(diagnostics: _diagnostics),
        if (_group) _skinPool(doc),
        const NpcSubhead('Equipment'),
        EquipmentEditor(
          value: doc['equipment'],
          bodyType: _type,
          diagnostics: _diagnostics,
          onChanged: (Object? value) => _write(<JsonStep>['equipment'], value),
        ),
        const NpcSubhead('Hologram', help: 'Lines above the body.'),
        HologramLinesEditor(
          value: doc['hologram'],
          schema: _schema,
          diagnostics: _diagnostics,
          onChanged: (Object? value) => _write(<JsonStep>['hologram'], value),
        ),
        const NpcSubhead('Nameplate'),
        GuiRow(
          label: 'Show the name',
          help: 'Off hides it for everyone.',
          problems: diagnosticsAt(_diagnostics, r'$.nameplate', nested: true),
          child: GuiSwitch(
            value: nameplate,
            label: 'Nameplate visible',
            onChanged: (bool visible) => _write(<JsonStep>['nameplate'], visible ? null : <String, Object?>{'visible': false}),
          ),
        ),
      ],
    );
  }

  // Behaviour ----------------------------------------------------------------

  Widget _behaviour(JsonMap doc) {
    final List<String> listed = stringList(doc['behaviors']);
    final JsonMap navigation = asMap(_modules[navigationModule]);
    final String route = !_modules.containsKey(navigationModule)
        ? (_group ? 'no route' : 'stands still')
        : asString(navigation['route']) != null
        ? 'route ${asString(navigation['route'])}'
        : '${asList(navigation['points']).length} waypoints';
    return NpcSection(
      id: 'behaviour',
      title: 'Behaviour',
      summary: '${listed.length} behavior${listed.length == 1 ? '' : 's'}, $route',
      open: _open('behaviour', false),
      onToggle: (bool open) => setState(() => _opened['behaviour'] = open),
      children: <Widget>[
        _behaviors(doc),
        const NpcSubhead('Route'),
        _route(),
        const NpcSubhead('Movement'),
        _field('movement'),
        if (!_group) ...<Widget>[
          const NpcSubhead('Inventory'),
          NpcInventoryEditor(value: doc['inventory'], diagnostics: _diagnostics, onChanged: (Object? value) => _write(<JsonStep>['inventory'], value)),
        ],
      ],
    );
  }

  Widget _behaviors(JsonMap doc) => NpcBehaviorList(
    listed: stringList(doc['behaviors']),
    documents: component.workspaceDocuments,
    serverBehaviors: component.serverBehaviors,
    diagnostics: _diagnostics,
    help: _spec('behaviors').description,
    onOpen: component.onOpen,
    onChanged: (List<String> next) => _write(<JsonStep>['behaviors'], next.isEmpty ? null : next),
  );

  (double, double, double)? get _origin {
    final JsonMap position = asMap(_doc['position']);
    final double? x = asDouble(position['x']), y = asDouble(position['y']), z = asDouble(position['z']);
    return x == null || y == null || z == null ? null : (x, y, z);
  }

  Widget _route() => NavigationEditor(
    value: _modules[navigationModule],
    routes: _documents(DocumentKind.route),
    recordings: routeRecordings(component.workspaceDocuments),
    origin: _origin,
    imported: _doc.containsKey('migration'),
    diagnostics: _diagnostics,
    onOpen: component.onOpen,
    onCopyRoute: _copyRoute(),
    copyLabel: _group ? 'Make a copy for this group' : 'Make a copy for this NPC',
    onChanged: (Object? value, {JsonMap? from}) => _putModule(navigationModule, value, from: from),
  );

  /// Copies the referenced workspace route for this NPC in one commit; null
  /// when there is nothing to copy.
  void Function()? _copyRoute() {
    final ConversationCommit? commit = component.onCommit;
    final String? reference = asString(asMap(_modules[navigationModule])['route']);
    final AnimusDocument? route = reference == null ? null : _documents(DocumentKind.route).where((AnimusDocument d) => d.id == reference).firstOrNull;
    if (commit == null || route == null) return null;
    return () {
      final RouteCopy copy = copyRouteFor(component.document, route, component.workspaceDocuments);
      commit(copy.changes, 'copy route $reference to ${copy.id}');
    };
  }

  // Modules ------------------------------------------------------------------

  Widget _modulesPart(JsonMap doc) {
    final List<String> keys = _modules.keys.toList();
    return NpcSection(
      id: 'modules',
      title: 'Modules',
      summary: keys.isEmpty ? 'none' : keys.map((String key) => key.startsWith('animus:') ? key.substring(7) : key).join(', '),
      open: _open('modules', false),
      onToggle: (bool open) => setState(() => _opened['modules'] = open),
      children: <Widget>[_combat(doc), _shop(), _mount(), _otherModules()],
    );
  }

  Widget _combat(JsonMap doc) {
    final Object? raw = _modules[combatModule];
    if (raw == null) {
      return dom.div(classes: 'animus-npc-module', <Widget>[
        const NpcSubhead('Combat', help: 'Off: invulnerable, never fights.'),
        Button.secondary(label: 'Enable combat', size: ButtonSize.small, onPressed: () => _putModule(combatModule, <String, Object?>{'enabled': true})),
      ]);
    }
    final List<NpcEntry> npcs = npcEntries(component.workspaceDocuments);
    final Set<String> squads = <String>{
      for (final NpcEntry entry in npcs)
        if (asString(asMap(asMap(entry.modules[combatModule])['squad'])['name']) case final String name) name,
      for (final NpcEntry entry in npcs) ...stringList(asMap(asMap(entry.modules[combatModule])['targets'])['squads']),
    };
    return dom.div(classes: 'animus-npc-module', <Widget>[
      const NpcSubhead('Combat'),
      CombatEditor(
        value: raw,
        diagnostics: _diagnostics,
        npcIds: <String>[
          for (final NpcEntry entry in npcs)
            if (entry.key != component.document.id) entry.localId,
        ],
        squads: squads.toList()..sort(),
        imported: doc.containsKey('migration'),
        origin: _origin,
        schema: component.schemas.npc,
        onChanged: (Object? value) => _putModule(combatModule, value),
      ),
    ]);
  }

  Widget _shop() => ShopBindingEditor(
    value: _modules[shopModule],
    shops: shopPickerOptions(component.workspaceDocuments),
    imported: _doc.containsKey('migration'),
    diagnostics: _diagnostics,
    onOpen: component.onOpen,
    onChanged: (Object? value) => _putModule(shopModule, value),
  );

  Widget _mount() => MountEditor(
    value: _modules[mountModule],
    vehicles: <String>[
      for (final NpcEntry entry in npcEntries(component.workspaceDocuments))
        if (entry.key != component.document.id) entry.localId,
    ],
    diagnostics: _diagnostics,
    onChanged: (Object? value) => _putModule(mountModule, value),
  );

  Widget _otherModules() {
    final JsonMap modules = _modules;
    final FieldSpec spec = _spec('modules');
    final List<FieldSpec> known = spec.schema.isEmpty ? const <FieldSpec>[] : _schema.fields(spec.schema, modules);
    final List<String> addable = <String>[
      for (final FieldSpec field in known)
        if (!modules.containsKey(field.key) && !_typedModules.contains(field.key)) field.key,
    ];
    final List<MapEntry<String, Object?>> others = <MapEntry<String, Object?>>[
      for (final MapEntry<String, Object?> entry in modules.entries)
        if (!_typedModules.contains(entry.key)) entry,
    ];
    final String? adding = _newModule;
    const String addId = 'animus-npc-add-module';
    return dom.div(classes: 'animus-npc-module', <Widget>[
      const NpcSubhead('Other modules', help: 'Modules without an editor of their own. Kept as written.'),
      for (final MapEntry<String, Object?> entry in others)
        _module(entry.key, entry.value, known.where((FieldSpec field) => field.key == entry.key).firstOrNull),
      dom.div(classes: 'animus-row', <Widget>[
        if (addable.isNotEmpty) ...<Widget>[
          const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': addId}, <Widget>[Component.text('Add a known module')]),
          GuiSelect(
            id: addId,
            value: null,
            options: addable,
            noneLabel: 'Add a known module',
            onChanged: (String? key) {
              if (key != null) _putModule(key, <String, Object?>{'enabled': true});
            },
          ),
        ],
        GuiTextInput(value: adding ?? '', label: 'Module key', placeholder: 'namespace:name', onChanged: (String value) => setState(() => _newModule = value)),
        Button.ghost(
          label: 'Add',
          size: ButtonSize.small,
          disabled: adding == null || !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(adding) || modules.containsKey(adding),
          onPressed: () {
            _putModule(adding!, <String, Object?>{});
            setState(() => _newModule = null);
          },
        ),
      ]),
    ]);
  }

  Widget _module(String key, Object? value, FieldSpec? field) {
    final String path = childPath(r'$.modules', key);
    final bool typed = field != null && field.schema.isNotEmpty && value is Map<String, Object?> && !_asJson.contains(key);
    return dom.div(
      key: ValueKey<String>('module:$key'),
      classes: 'animus-inspect-item',
      attributes: <String, String>{'data-module': key},
      <Widget>[
        dom.div(classes: 'animus-gui-action-head', <Widget>[
          dom.code(<Widget>[withTip(Component.text(key), field?.description)]),
          if (field?.description?.isNotEmpty != true) const dom.span(classes: 'animus-gui-help', <Widget>[Component.text('Kept as written.')]),
          if (field != null && field.schema.isNotEmpty && value is Map<String, Object?>)
            Button.ghost(
              label: typed ? 'Edit as JSON' : 'Edit as fields',
              size: ButtonSize.small,
              onPressed: () => setState(() => typed ? _asJson.add(key) : _asJson.remove(key)),
            ),
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove module $key',
            onPressed: () => _putModule(key, null),
          ),
        ]),
        if (typed)
          InspectObject(
            schema: _schema,
            node: field.schema,
            value: value,
            path: path,
            diagnostics: _diagnostics,
            onChanged: (JsonMap next) => _putModule(key, next),
          )
        else ...<Widget>[
          if (value is Map<String, Object?>)
            KeyValueEditor(value: value, label: 'Module $key', onChanged: (JsonMap next) => _putModule(key, next))
          else
            TypedValueEditor(value: value, label: 'Module $key', onChanged: (Object? next) => _putModule(key, next)),
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, path, nested: true))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[
              dom.code(<Widget>[Component.text(problem.path)]),
              Component.text(' ${problem.message}'),
            ]),
        ],
      ],
    );
  }

  // Kept fields --------------------------------------------------------------

  Widget _kept(JsonMap doc) {
    final List<FieldSpec> rest = <FieldSpec>[
      for (final FieldSpec field in _schema.fields(_schema.root, doc))
        if (!_laidOut.contains(field.key)) field,
    ];
    final Set<String> known = <String>{for (final FieldSpec field in _schema.fields(_schema.root, doc)) field.key};
    final List<String> kept = <String>[
      for (final String key in doc.keys)
        if (!known.contains(key) && !_laidOut.contains(key)) key,
    ];
    if (rest.isEmpty && kept.isEmpty) return const dom.div(<Widget>[]);
    return NpcSection(
      id: 'kept',
      title: 'Kept fields',
      summary: <String>[...rest.map((FieldSpec field) => field.key), ...kept].join(', '),
      open: _open('kept', false),
      onToggle: (bool open) => setState(() => _opened['kept'] = open),
      children: <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('Fields without an editor. Kept as written.'),
        ]),
        InspectObject(
          schema: _schema,
          node: _schema.root,
          value: doc,
          path: r'$',
          diagnostics: _diagnostics,
          skip: _laidOut,
          onChanged: (JsonMap value) => _emit(const <JsonStep>[], value, coalesce: 'npc:other'),
        ),
      ],
    );
  }
}
