/// The NPC definition's Visual view, read top down: identity, body,
/// position, appearance (skin, equipment, hologram, nameplate), behaviour
/// (behaviors, route, movement, inventory), modules (combat, shop, mount and
/// the rest), then fields kept as written. Each part's heading summarises it
/// while closed. Edits change the parsed definition and report it whole, so
/// unknown keys survive.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/combat_settings.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/route_model.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../npc/appearance_editor.dart';
import '../npc/combat_editor.dart';
import '../npc/equipment_editor.dart';
import '../npc/inventory_editor.dart';
import '../npc/npc_modules.dart';
import '../npc/route_editor.dart' show routeRecordings;
import '../npc/npc_section.dart';
import '../npc/skin_picker.dart';
import 'inspect_fields.dart';

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
  'hologram',
  'nameplate',
  'inventory',
  'poses',
  'movement',
  ..._traits,
};

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
    super.key,
  });

  final AnimusDocument document;
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

  @override
  State<NpcInspector> createState() => _NpcInspectorState();
}

class _NpcInspectorState extends State<NpcInspector> {
  String? _newModule;
  String _newTag = '';
  final Set<String> _asJson = <String>{};

  /// Phone widths open with only the identity part showing.
  final bool _narrow = npcInspectorNarrow;

  JsonMap get _doc => asMap(component.document.json);
  SchemaView get _schema => component.schemas.npc;
  List<Diagnostic> get _diagnostics => component.diagnostics;
  String get _type => asString(_doc['type']) ?? 'minecraft:player';

  List<AnimusDocument> _documents(DocumentKind kind) => <AnimusDocument>[
    for (final AnimusDocument document in component.workspaceDocuments)
      if (document.kind == kind) document,
  ];

  List<String> _ids(DocumentKind kind) => <String>[for (final AnimusDocument document in _documents(kind)) document.id];

  void _write(List<JsonStep> steps, Object? value) => component.onJson(writeIn(_doc, steps, value), coalesce: 'npc:${steps.join('.')}');

  FieldSpec _spec(String key) {
    final List<FieldSpec> fields = _schema.fields(_schema.root, _doc);
    return fields.where((FieldSpec field) => field.key == key).firstOrNull ?? FieldSpec(key: key, kind: FieldKind.any);
  }

  Widget _field(String key, {List<FieldOption> suggestions = const <FieldOption>[], String? label}) => InspectField(
    key: ValueKey<String>('npc:$key'),
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

  void _putModule(String key, Object? value) {
    final JsonMap next = asMap(writeIn(_modules, <JsonStep>[key], value));
    component.onJson(writeIn(_doc, <JsonStep>['modules'], next.isEmpty ? null : next), coalesce: 'npc:module:$key');
  }

  /// Parts the reader opened or closed, by section id.
  final Map<String, bool> _opened = <String, bool>{};

  bool _open(String id, bool first) => _opened[id] ?? (first || !_narrow);

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    return dom.section(
      classes: 'animus-panel animus-inspector animus-npc-inspector',
      attributes: const <String, String>{'aria-label': 'NPC inspector'},
      <Widget>[
        if (component.schemas.live) const dom.p(classes: 'animus-muted', <Widget>[Component.text("Fields from the connected server's schemas.")]),
        _identity(doc),
        _body(doc),
        _position(doc),
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
        _field('name'),
        _readOnly('id', 'ID', 'Change it by moving the file.'),
        _readOnly('group', 'Folder', 'The folder under npcs/; move NPCs between folders from the NPC browser.'),
        _readOnly('uuid', 'UUID', 'Never changes.'),
        _field('revision'),
        _tags(doc),
        GuiRow(
          label: 'Owner',
          help: "A player's UUID, lowercase with dashes. Modules may use it for their own permission rules; leave it empty for none.",
          problems: <Diagnostic>[
            ...diagnosticsAt(_diagnostics, r'$.owner'),
            if (owner != null && owner.isNotEmpty && !validOwner(owner) && diagnosticsAt(_diagnostics, r'$.owner').isEmpty)
              const Diagnostic(DiagnosticSeverity.error, r'$.owner', 'Expected a lowercase UUID such as 0f6a3b8e-6a55-4b1e-9d8a-1f2e3d4c5b6a.'),
          ],
          child: GuiTextInput(
            value: owner ?? '',
            label: 'Owner UUID',
            placeholder: '0f6a3b8e-6a55-4b1e-9d8a-1f2e3d4c5b6a',
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
      help: 'Labels for finding NPCs in the browser: up to $npcTagLimit of 1-32 a-z, 0-9, _ and -.',
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

  // Body ---------------------------------------------------------------------

  Widget _body(JsonMap doc) {
    final String type = _type;
    final String? rig = asString(asMap(doc['rig'])['id']);
    final List<String> rigs = _ids(DocumentKind.rig);
    final List<String> poses = posesFor(type);
    final String summary = <String>[
      type.startsWith('minecraft:') ? type.substring(10).replaceAll('_', ' ') : type,
      if (doc['spawned'] == true) 'spawned' else 'despawned',
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
        GuiRow(
          label: fieldLabel(_spec('type'), label: 'Type'),
          help: '${_spec('type').description ?? ''} The preview draws ${mcBodyTypes.length} body types; others are kept and presented by the server.',
          problems: fieldProblems(_spec('type'), doc['type'], r'$.type', _diagnostics),
          child: SuggestInput(
            value: asString(doc['type']) ?? '',
            label: 'Body type',
            suggestions: <FieldOption>[for (final String body in mcBodyTypes) FieldOption(body)],
            onChanged: (String value) => _write(<JsonStep>['type'], value),
          ),
        ),
        _field('spawned'),
        InspectRow(
          label: 'Rig',
          labelFor: rigId,
          help: rigs.isEmpty
              ? 'No rigs in this workspace. Import a Blockbench model from the home page to give the body a rig.'
              : 'A rig under rigs/ replaces the body; the stage draws it.',
          problems: diagnosticsAt(_diagnostics, r'$.rig', nested: true),
          child: dom.div(classes: 'animus-gui-line', <Widget>[
            GuiSelect(
              id: rigId,
              value: rig,
              options: rigs,
              allowNone: true,
              noneLabel: 'No rig: the body type shows',
              onChanged: (String? id) => _write(<JsonStep>['rig'], id == null ? null : <String, Object?>{'id': id}),
            ),
            if (rig != null && rigs.contains(rig) && component.onOpen != null)
              Button.ghost(label: 'Open rig', size: ButtonSize.small, onPressed: () => component.onOpen!(DocumentKind.rig, rig)),
          ]),
        ),
        if (rig != null && !rigs.contains(rig))
          const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a rig in this workspace; the server may have it.')]),
        InspectRow(
          label: 'Pose',
          labelFor: poseId,
          help: type == 'minecraft:player' ? 'How the body stands, sits or lies.' : 'Only player bodies take other poses; this body stands.',
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
        ] else if (doc.containsKey('armorStand'))
          dom.div(classes: 'animus-row', <Widget>[
            const dom.span(classes: 'animus-gui-problem is-error', <Widget>[Component.text('armorStand applies only to minecraft:armor_stand bodies.')]),
            Button.ghost(label: 'Remove armorStand', size: ButtonSize.small, onPressed: () => _write(<JsonStep>['armorStand'], null)),
          ]),
        const NpcSubhead('Traits'),
        for (final String trait in _traits) _field(trait),
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
    final String summary = <String>[
      skin == null ? 'default skin' : 'skin ${skin.substring(0, skin.length.clamp(0, 8))}',
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
        SkinPicker(
          npcId: component.document.id,
          value: skin,
          skins: _documents(DocumentKind.skin),
          diagnostics: _diagnostics,
          onChanged: (String? value) => _write(<JsonStep>['skin'], value),
          onLoaded: component.onSkinLoaded,
        ),
        const NpcSubhead('Equipment'),
        EquipmentEditor(
          value: doc['equipment'],
          bodyType: _type,
          diagnostics: _diagnostics,
          onChanged: (Object? value) => _write(<JsonStep>['equipment'], value),
        ),
        const NpcSubhead('Hologram', help: 'Text lines above the body; every viewer sees the same.'),
        HologramLinesEditor(
          value: doc['hologram'],
          schema: _schema,
          diagnostics: _diagnostics,
          onChanged: (Object? value) => _write(<JsonStep>['hologram'], value),
        ),
        const NpcSubhead('Nameplate'),
        GuiRow(
          label: 'Show the name',
          help: 'The name above the body. Off hides it for every viewer.',
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
        ? 'stands still'
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
        const NpcSubhead('Movement', help: 'How this NPC walks wherever it walks.'),
        _field('movement'),
        const NpcSubhead('Inventory'),
        NpcInventoryEditor(value: doc['inventory'], diagnostics: _diagnostics, onChanged: (Object? value) => _write(<JsonStep>['inventory'], value)),
      ],
    );
  }

  Widget _behaviors(JsonMap doc) {
    final List<String> behaviorIds = npcBehaviorIds(component.workspaceDocuments);
    final Set<String> serverWide = <String>{
      for (final String id in _ids(DocumentKind.behavior))
        if (!behaviorIds.contains(id)) id,
    };
    final List<String> listed = stringList(doc['behaviors']);
    final List<String> available = <String>[
      for (final String id in behaviorIds)
        if (!listed.contains(id)) id,
    ];
    void put(List<String> next) => _write(<JsonStep>['behaviors'], next.isEmpty ? null : next);
    const String addId = 'animus-npc-add-behavior';
    return dom.div(
      classes: 'animus-npc-behaviors',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Behaviors'},
      <Widget>[
        NpcSubhead('Behaviors', help: _spec('behaviors').description),
        if (listed.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No behaviors: clicks and events do nothing. Add one below.')]),
        for (int index = 0; index < listed.length; index++)
          dom.div(key: ValueKey<String>('behavior-$index'), classes: 'animus-inspect-item', <Widget>[
            dom.div(classes: 'animus-gui-line', <Widget>[
              SuggestInput(
                value: listed[index],
                label: 'Behavior ${index + 1}',
                suggestions: <FieldOption>[for (final String id in behaviorIds) FieldOption(id)],
                onChanged: (String value) => put(<String>[...listed]..[index] = value),
              ),
              if (component.onOpen != null && _ids(DocumentKind.behavior).contains(listed[index]))
                Button.ghost(label: 'Open', size: ButtonSize.small, onPressed: () => component.onOpen!(DocumentKind.behavior, listed[index])),
              GuiIconButton(
                icon: ArcaneIcon.arrowUp(size: IconSize.sm),
                label: 'Move behavior ${index + 1} up',
                disabled: index == 0,
                onPressed: () => put(<String>[for (final Object? id in moveItem(listed, index, index - 1)) id! as String]),
              ),
              GuiIconButton(
                icon: ArcaneIcon.x(size: IconSize.sm),
                label: 'Remove behavior ${index + 1}',
                onPressed: () => put(<String>[...listed]..removeAt(index)),
              ),
            ]),
            if (serverWide.contains(listed[index]))
              const dom.span(classes: 'animus-gui-problem is-error', <Widget>[
                Component.text('A server-wide behavior has no NPC; list it in server-behaviors.json instead.'),
              ])
            else if (!behaviorIds.contains(listed[index]))
              const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a behavior in this workspace; the server may have it.')]),
            for (final Diagnostic problem in diagnosticsAt(_diagnostics, '\$.behaviors[$index]'))
              dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
          ]),
        dom.div(classes: 'animus-row', <Widget>[
          if (available.isNotEmpty) ...<Widget>[
            const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': addId}, <Widget>[Component.text('Add a workspace behavior')]),
            GuiSelect(
              id: addId,
              value: null,
              options: available,
              noneLabel: 'Add a workspace behavior',
              onChanged: (String? id) {
                if (id != null) put(<String>[...listed, id]);
              },
            ),
          ],
          Button.ghost(label: 'Add by id', size: ButtonSize.small, onPressed: () => put(<String>[...listed, ''])),
        ]),
      ],
    );
  }

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
    onChanged: (Object? value) => _putModule(navigationModule, value),
  );

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
        const NpcSubhead('Combat', help: 'The NPC cannot be hurt and never fights.'),
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
        onChanged: (Object? value) => _putModule(combatModule, value),
      ),
    ]);
  }

  Widget _shop() => ShopBindingEditor(
    value: _modules[shopModule],
    shops: _ids(DocumentKind.shop),
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
      const NpcSubhead('Other modules', help: 'Modules the schema describes get typed fields; the rest are edited as JSON and kept.'),
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
          dom.code(<Widget>[Component.text(key)]),
          dom.span(classes: 'animus-gui-help', <Widget>[Component.text(field?.description ?? 'Kept as written; the server and its module read it.')]),
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
          KeptJsonEditor(value: value, label: 'Module $key', onChanged: (Object? next) => _putModule(key, next)),
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
          Component.text('Fields the editor does not lay out. They stay as written and the server reads them.'),
        ]),
        InspectObject(
          schema: _schema,
          node: _schema.root,
          value: doc,
          path: r'$',
          diagnostics: _diagnostics,
          skip: _laidOut,
          onChanged: (JsonMap value) => component.onJson(value, coalesce: 'npc:other'),
        ),
      ],
    );
  }
}
