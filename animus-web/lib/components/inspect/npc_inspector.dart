/// The NPC definition's Visual view: identity and tags, body type,
/// presence, position, skin, equipment, behaviors (completed from the
/// workspace), the route, combat, the shop binding, other modules from
/// their schemas (or as JSON when no schema names them), and every other
/// schema field generically. Edits change the parsed definition and report
/// it whole, so unknown keys survive.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/combat_settings.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/route_model.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../npc/combat_editor.dart';
import '../npc/equipment_editor.dart';
import '../npc/route_editor.dart';
import '../npc/skin_picker.dart';
import 'inspect_fields.dart';

/// Keys the NPC inspector lays out itself; the rest render generically.
const Set<String> _laidOut = <String>{
  'schemaVersion', 'revision', 'uuid', 'id', 'group', 'name', 'type', 'spawned', 'position', 'skin', 'behaviors', 'modules', 'tags', 'equipment',
};

const String _shopModule = 'animus:shop';

/// Modules with their own editor here.
const Set<String> _typedModules = <String>{navigationModule, combatModule, _shopModule};

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

  /// Every document of the workspace: behaviors, skins, routes, shops and
  /// the other NPCs (their tags and squads) are offered from here.
  final List<AnimusDocument> workspaceDocuments;

  /// A skin PNG was loaded; the stage beside the inspector redraws.
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

  JsonMap get _doc => asMap(component.document.json);
  SchemaView get _schema => component.schemas.npc;
  List<Diagnostic> get _diagnostics => component.diagnostics;

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

  Widget _readOnly(String key, String help) {
    final FieldSpec spec = _spec(key);
    return GuiRow(
      label: key,
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

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final FieldSpec position = _spec('position');
    return dom.section(classes: 'animus-panel animus-inspector', attributes: const <String, String>{'aria-label': 'NPC inspector'}, <Widget>[
      if (component.schemas.live) const dom.p(classes: 'animus-muted', <Widget>[Component.text("Fields from the connected server's schemas.")]),
      GuiSection(title: 'Identity', children: <Widget>[
        _field('name'),
        _readOnly('id', 'Change it by moving the file.'),
        _readOnly('group', 'The folder under npcs/; move NPCs between folders from the NPC browser.'),
        _readOnly('uuid', 'Never changes.'),
        _field('revision'),
        _tags(doc),
      ]),
      GuiSection(title: 'Body', children: <Widget>[
        GuiRow(
          label: fieldLabel(_spec('type'), label: 'type'),
          help: '${_spec('type').description ?? ''} The preview draws ${mcBodyTypes.length} body types; others are kept and presented by the server.',
          problems: fieldProblems(_spec('type'), doc['type'], r'$.type', _diagnostics),
          child: SuggestInput(
            value: asString(doc['type']) ?? '',
            label: 'type',
            suggestions: <FieldOption>[for (final String type in mcBodyTypes) FieldOption(type)],
            onChanged: (String value) => _write(<JsonStep>['type'], value),
          ),
        ),
        _field('spawned'),
      ]),
      GuiSection(title: 'Position', children: <Widget>[
        if (position.schema.isNotEmpty)
          InspectObject(
            schema: _schema,
            node: position.schema,
            value: asMap(doc['position']),
            path: r'$.position',
            diagnostics: _diagnostics,
            onChanged: (JsonMap value) => _write(<JsonStep>['position'], value),
          ),
      ]),
      GuiSection(title: 'Skin', children: <Widget>[
        SkinPicker(
          value: asString(doc['skin']),
          skins: _documents(DocumentKind.skin),
          diagnostics: _diagnostics,
          onChanged: (String? value) => _write(<JsonStep>['skin'], value),
          onLoaded: component.onSkinLoaded,
        ),
      ]),
      GuiSection(title: 'Equipment', count: asMap(doc['equipment']).length, children: <Widget>[
        EquipmentEditor(
          value: doc['equipment'],
          bodyType: asString(doc['type']) ?? 'minecraft:player',
          diagnostics: _diagnostics,
          onChanged: (Object? value) => _write(<JsonStep>['equipment'], value),
        ),
      ]),
      _behaviors(doc),
      _route(),
      _combat(doc),
      _shop(),
      _modulesSection(),
      _other(doc),
    ]);
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
      label: 'tags',
      help: 'Labels for finding NPCs in the browser: up to $npcTagLimit of 1-32 a-z, 0-9, _ and -.',
      problems: diagnosticsAt(_diagnostics, r'$.tags', nested: true),
      child: dom.div(classes: 'animus-tag-editor', <Widget>[
        dom.div(classes: 'animus-npc-tag-cloud', <Widget>[
          for (final String tag in tags)
            dom.span(classes: 'animus-npc-tag', attributes: <String, String>{'data-tag': tag}, <Widget>[
              Component.text(tag),
              GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove tag $tag', onPressed: () => put(<String>[...tags]..remove(tag))),
            ]),
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

  Widget _behaviors(JsonMap doc) {
    final List<String> behaviorIds = _ids(DocumentKind.behavior);
    final List<String> listed = stringList(doc['behaviors']);
    final List<String> available = <String>[for (final String id in behaviorIds) if (!listed.contains(id)) id];
    void put(List<String> next) => _write(<JsonStep>['behaviors'], next.isEmpty ? null : next);
    return GuiSection(title: 'Behaviors', count: listed.length, children: <Widget>[
      if (_spec('behaviors').description != null) dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_spec('behaviors').description!)]),
      for (int index = 0; index < listed.length; index++)
        dom.div(key: ValueKey<String>('behavior-$index'), classes: 'animus-inspect-item', <Widget>[
          dom.div(classes: 'animus-gui-line', <Widget>[
            SuggestInput(
              value: listed[index],
              label: 'Behavior ${index + 1}',
              suggestions: <FieldOption>[for (final String id in behaviorIds) FieldOption(id)],
              onChanged: (String value) => put(<String>[...listed]..[index] = value),
            ),
            if (component.onOpen != null && behaviorIds.contains(listed[index]))
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
          if (!behaviorIds.contains(listed[index]))
            const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a behavior in this workspace; the server may have it.')]),
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, '\$.behaviors[$index]'))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
        ]),
      dom.div(classes: 'animus-row', <Widget>[
        if (available.isNotEmpty)
          GuiSelect(
            value: null,
            options: available,
            noneLabel: 'Add a workspace behavior',
            onChanged: (String? id) {
              if (id != null) put(<String>[...listed, id]);
            },
          ),
        Button.ghost(label: 'Add by id', size: ButtonSize.small, onPressed: () => put(<String>[...listed, ''])),
      ]),
    ]);
  }

  (double, double, double)? get _origin {
    final JsonMap position = asMap(_doc['position']);
    final double? x = asDouble(position['x']), y = asDouble(position['y']), z = asDouble(position['z']);
    return x == null || y == null || z == null ? null : (x, y, z);
  }

  Widget _route() {
    final Object? raw = _modules[navigationModule];
    final JsonMap module = asMap(raw);
    final String path = childPath(r'$.modules', navigationModule);
    final List<String> routeIds = _ids(DocumentKind.route);
    final String? reference = asString(module['route']);
    final bool imported = _doc.containsKey('migration');
    if (raw == null) {
      return GuiSection(title: 'Route', open: false, children: <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[Component.text('The NPC stands still. Give it waypoints of its own or a reusable route under routes/.')]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(
            label: 'Add waypoints',
            size: ButtonSize.small,
            onPressed: () {
              final (double, double, double)? origin = _origin;
              _putModule(navigationModule, <String, Object?>{
                'enabled': true,
                'points': <Object?>[
                  if (origin != null) <String, Object?>{'x': origin.$1.floor(), 'y': origin.$2.floor(), 'z': origin.$3.floor()},
                ],
              });
            },
          ),
          if (routeIds.isNotEmpty)
            GuiSelect(
              value: null,
              options: routeIds,
              noneLabel: 'Use a reusable route',
              onChanged: (String? id) {
                if (id != null) _putModule(navigationModule, <String, Object?>{'enabled': true, 'route': id});
              },
            ),
        ]),
      ]);
    }
    final AnimusDocument? routeDocument = reference == null ? null : _documents(DocumentKind.route).where((AnimusDocument d) => d.id == reference).firstOrNull;
    return GuiSection(title: 'Route', children: <Widget>[
      dom.div(classes: 'animus-row', <Widget>[
        GuiSwitch(value: module['enabled'] == true, label: 'Enabled', onChanged: (bool value) => _putModule(navigationModule, <String, Object?>{...module, 'enabled': value})),
        if (imported)
          GuiSwitch(
            value: module['reviewAccepted'] == true,
            label: 'Review accepted',
            onChanged: (bool value) => _putModule(navigationModule, <String, Object?>{...module, 'reviewAccepted': value}),
          ),
        GuiSelect(
          value: reference,
          options: <String>[...routeIds],
          allowNone: true,
          noneLabel: 'Waypoints of its own',
          onChanged: (String? id) {
            final JsonMap next = <String, Object?>{
              for (final MapEntry<String, Object?> entry in module.entries)
                if (!routeFields.contains(entry.key) && entry.key != 'route') entry.key: entry.value,
            };
            if (id != null) {
              next['route'] = id;
            } else {
              final (double, double, double)? origin = _origin;
              next['points'] = <Object?>[
                if (origin != null) <String, Object?>{'x': origin.$1.floor(), 'y': origin.$2.floor(), 'z': origin.$3.floor()},
              ];
            }
            _putModule(navigationModule, next);
          },
        ),
        GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove the route', onPressed: () => _putModule(navigationModule, null)),
      ]),
      if (reference != null) ...<Widget>[
        dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('Follows routes/$reference.json; edit the route there. '),
          if (routeDocument != null && component.onOpen != null)
            Button.ghost(label: 'Open route $reference', size: ButtonSize.small, onPressed: () => component.onOpen!(DocumentKind.route, reference)),
        ]),
        if (routeDocument == null)
          const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a route in this workspace; the server may have it.')])
        else
          RouteEditor(value: asMap(routeDocument.json), onChanged: (JsonMap _) {}, origin: _origin, readOnly: true),
      ] else
        RouteEditor(
          value: module,
          origin: _origin,
          path: path,
          diagnostics: _diagnostics,
          onChanged: (JsonMap value) => _putModule(navigationModule, value),
        ),
    ]);
  }

  Widget _combat(JsonMap doc) {
    final Object? raw = _modules[combatModule];
    if (raw == null) {
      return GuiSection(title: 'Combat', open: false, children: <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[Component.text('The NPC cannot be hurt and never fights.')]),
        Button.secondary(label: 'Enable combat', size: ButtonSize.small, onPressed: () => _putModule(combatModule, <String, Object?>{'enabled': true})),
      ]);
    }
    final List<NpcEntry> npcs = npcEntries(component.workspaceDocuments);
    final Set<String> squads = <String>{
      for (final NpcEntry entry in npcs)
        if (asString(asMap(asMap(entry.modules[combatModule])['squad'])['name']) case final String name) name,
      for (final NpcEntry entry in npcs) ...stringList(asMap(asMap(entry.modules[combatModule])['targets'])['squads']),
    };
    return GuiSection(title: 'Combat', children: <Widget>[
      CombatEditor(
        value: raw,
        diagnostics: _diagnostics,
        npcIds: <String>[for (final NpcEntry entry in npcs) if (entry.key != component.document.id) entry.localId],
        squads: squads.toList()..sort(),
        imported: doc.containsKey('migration'),
        onChanged: (Object? value) => _putModule(combatModule, value),
      ),
    ]);
  }

  Widget _shop() {
    final Object? raw = _modules[_shopModule];
    final List<String> shops = _ids(DocumentKind.shop);
    if (raw == null) {
      if (shops.isEmpty) return const dom.div(<Widget>[]);
      return GuiSection(title: 'Shop', open: false, children: <Widget>[
        GuiSelect(
          value: null,
          options: shops,
          noneLabel: 'Open a workspace shop on click',
          onChanged: (String? id) {
            if (id != null) _putModule(_shopModule, <String, Object?>{'shop': id});
          },
        ),
      ]);
    }
    final JsonMap module = asMap(raw);
    final String path = childPath(r'$.modules', _shopModule);
    final String? shop = asString(module['shop']);
    return GuiSection(title: 'Shop', children: <Widget>[
      GuiRow(
        label: 'shop',
        help: 'The shop a click opens. A sneaking administrator selects the NPC instead.',
        problems: diagnosticsAt(_diagnostics, '$path.shop'),
        child: dom.div(classes: 'animus-gui-line', <Widget>[
          SuggestInput(
            value: shop ?? '',
            label: 'shop',
            placeholder: 'town:baker',
            suggestions: <FieldOption>[for (final String id in shops) FieldOption(id)],
            onChanged: (String value) => _putModule(_shopModule, <String, Object?>{...module, 'shop': value.trim()}),
          ),
          if (shop != null && shops.contains(shop) && component.onOpen != null)
            Button.ghost(label: 'Open', size: ButtonSize.small, onPressed: () => component.onOpen!(DocumentKind.shop, shop)),
          GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove the shop', onPressed: () => _putModule(_shopModule, null)),
        ]),
      ),
      if (shop != null && !shops.contains(shop))
        const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a shop in this workspace; the server may have it.')]),
      GuiRow(
        label: 'openOn',
        help: 'Which click opens it: right (default), left or both.',
        problems: diagnosticsAt(_diagnostics, '$path.openOn'),
        child: GuiSelect(
          value: asString(module['openOn']),
          options: const <String>['right', 'left', 'both'],
          allowNone: true,
          noneLabel: 'right (default)',
          onChanged: (String? value) {
            final JsonMap next = <String, Object?>{...module};
            if (value == null) {
              next.remove('openOn');
            } else {
              next['openOn'] = value;
            }
            _putModule(_shopModule, next);
          },
        ),
      ),
      if (_doc.containsKey('migration'))
        GuiSwitch(
          value: module['reviewAccepted'] == true,
          label: 'Review accepted',
          onChanged: (bool value) => _putModule(_shopModule, <String, Object?>{...module, 'reviewAccepted': value}),
        ),
    ]);
  }

  Widget _modulesSection() {
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
    return GuiSection(title: 'Other modules', count: others.length, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[
        Component.text('${spec.description ?? 'Per-NPC module settings, kept as written.'} Modules the schema describes get typed fields; the rest are edited as JSON.'),
      ]),
      for (final MapEntry<String, Object?> entry in others) _module(entry.key, entry.value, known.where((FieldSpec field) => field.key == entry.key).firstOrNull),
      dom.div(classes: 'animus-row', <Widget>[
        if (addable.isNotEmpty)
          GuiSelect(
            value: null,
            options: addable,
            noneLabel: 'Add a known module',
            onChanged: (String? key) {
              if (key != null) _putModule(key, <String, Object?>{'enabled': true});
            },
          ),
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
    return dom.div(key: ValueKey<String>('module:$key'), classes: 'animus-inspect-item', attributes: <String, String>{'data-module': key}, <Widget>[
      dom.div(classes: 'animus-gui-action-head', <Widget>[
        dom.code(<Widget>[Component.text(key)]),
        dom.span(classes: 'animus-gui-help', <Widget>[Component.text(field?.description ?? 'No schema describes it; kept as written and checked by its module.')]),
        if (field != null && field.schema.isNotEmpty && value is Map<String, Object?>)
          Button.ghost(
            label: typed ? 'JSON' : 'Fields',
            size: ButtonSize.small,
            onPressed: () => setState(() => typed ? _asJson.add(key) : _asJson.remove(key)),
          ),
        GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove module $key', onPressed: () => _putModule(key, null)),
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
    ]);
  }

  Widget _other(JsonMap doc) {
    final List<FieldSpec> rest = <FieldSpec>[
      for (final FieldSpec field in _schema.fields(_schema.root, doc))
        if (!_laidOut.contains(field.key)) field,
    ];
    final Set<String> known = <String>{for (final FieldSpec field in _schema.fields(_schema.root, doc)) field.key};
    final List<String> kept = <String>[for (final String key in doc.keys) if (!known.contains(key) && !_laidOut.contains(key)) key];
    if (rest.isEmpty && kept.isEmpty) return const dom.div(<Widget>[]);
    return GuiSection(title: 'Other fields', count: rest.length + kept.length, children: <Widget>[
      InspectObject(
        schema: _schema,
        node: _schema.root,
        value: doc,
        path: r'$',
        diagnostics: _diagnostics,
        skip: _laidOut,
        onChanged: (JsonMap value) => component.onJson(value, coalesce: 'npc:other'),
      ),
    ]);
  }
}
