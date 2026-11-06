/// The New page: every document kind with what it is for, and a guided
/// first step per kind that makes a valid document in the browser. No
/// server is needed for any of it.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/offline/new_documents.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../services/skin_images.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../gui/gui_fields.dart';
import 'offline_fields.dart';

/// Body types by plain name, for the NPC step.
const Map<String, String> bodyLabels = <String, String>{
  'minecraft:player': 'Player',
  'minecraft:armor_stand': 'Armor stand',
  'minecraft:horse': 'Horse',
  'minecraft:turtle': 'Turtle',
  'minecraft:frog': 'Frog',
  'minecraft:llama': 'Llama',
  'minecraft:armadillo': 'Armadillo',
  'minecraft:ender_dragon': 'Ender dragon',
};

enum _SkinSource { none, file, url, existing }

class NewDocumentPage extends StatefulWidget {
  const NewDocumentPage({required this.store, required this.workspace, required this.kind, required this.onNavigate, super.key});

  final EditorStore store;
  final WorkspaceSnapshot workspace;
  final DocumentKind? kind;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<NewDocumentPage> createState() => _NewDocumentPageState();
}

class _NewDocumentPageState extends State<NewDocumentPage> {
  bool _busy = false;
  String? _problem;

  // Shared.
  String _name = '';
  String? _id;
  String _title = '';

  // NPC.
  String _type = 'minecraft:player';
  String _group = 'local';
  String _world = 'world';
  num _x = 0.5, _y = 64, _z = 0.5, _yaw = 0;
  String _behavior = '';
  _SkinSource _skinSource = _SkinSource.none;
  String _skinUrl = '';
  String? _existingSkin;
  bool _slim = false;
  LoadedSkin? _skin;

  // Behavior.
  BehaviorTemplate _template = BehaviorTemplate.greeter;
  String? _shop;
  String? _attachTo;

  // Route.
  String _mode = 'walk';

  // Rig and animation.
  RigStarter _rigStarter = RigStarter.figure;
  bool _bodyShown = false;
  String? _rig;
  AnimationStarter _motion = AnimationStarter.bob;
  num _duration = 20;
  String _loop = 'loop';

  // Text animation.
  String _frames = '&eHello\n&6Hello\n&cHello';
  num _interval = 500;

  @override
  void didUpdateWidget(NewDocumentPage oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.kind != component.kind) {
      _id = null;
      _problem = null;
      _name = '';
      _title = '';
    }
  }

  List<AnimusDocument> _of(DocumentKind kind) => <AnimusDocument>[
        for (final AnimusDocument document in component.workspace.documents)
          if (document.kind == kind) document,
      ];

  Set<String> _taken(DocumentKind kind) => <String>{for (final AnimusDocument document in _of(kind)) document.id};

  String get _namespace => slug(component.workspace.name, fallback: 'local');

  /// The id the form proposes for [kind] until the user types one.
  String _proposed(DocumentKind kind) {
    final String base = slug(_name.isEmpty ? _title : _name, fallback: switch (kind) {
      DocumentKind.behavior => _template.name,
      DocumentKind.route => 'route',
      DocumentKind.pack => 'pack',
      DocumentKind.rig => 'rig',
      DocumentKind.animation => _motion == AnimationStarter.empty ? 'animation' : _motion.name,
      DocumentKind.textAnimation => 'blink',
      DocumentKind.npc => 'npc',
      _ => kind.wireName,
    });
    final String candidate = switch (kind) {
      DocumentKind.npc => '${slug(_group, fallback: 'local')}/$base',
      DocumentKind.route || DocumentKind.pack => base,
      DocumentKind.animation => '${_rig ?? '$_namespace:rig'}/$base',
      _ => '$_namespace:$base',
    };
    return freeId(candidate, _taken(kind));
  }

  String _idFor(DocumentKind kind) => (_id ?? _proposed(kind)).trim();

  String? _idProblem(DocumentKind kind) {
    final String id = _idFor(kind);
    if (!validDocumentId(kind, id)) return 'Use ${idHint(kind)}.';
    if (_taken(kind).contains(id)) return '${kind.label}: $id already exists.';
    return null;
  }

  Future<void> _write(List<DocumentChange> changes, DocumentKind kind, String id, {Map<String, Uint8List?> resources = const <String, Uint8List?>{}}) async {
    await component.store.commit(documents: changes, resources: resources, label: 'create $id');
    final AnimusDocument? made = component.store.document(kind, id);
    if (made == null) {
      setState(() => _problem = component.store.problem ??
          'The document was not created: the workspace changed before it could be written. Check the workspace, then press Create again.');
      return;
    }
    if (component.store.hasUnsavedEdit) {
      ArcaneSonner.warning('Created ${made.id} on this page, but the browser did not store it. The banner above says why.');
    } else if (!made.valid) {
      ArcaneSonner.warning('Created ${made.id} with ${made.diagnostics.length} problem(s); its page offers fixes.');
    } else {
      ArcaneSonner.success(kind == DocumentKind.presets
          ? 'Created gui/presets.json. Undo removes it.'
          : kind == DocumentKind.serverBehaviors
              ? 'Created server-behaviors.json. Undo removes it.'
              : 'Created ${_inSentence(kind)} ${guiPlainText(made.title)}. Undo removes it.');
    }
    component.onNavigate(DocumentRoute(component.workspace.id, kind, id));
    focusMainHeading();
  }

  DocumentChange _change(DocumentKind kind, String id, Object? json) => DocumentChange(kind, id, prettyJson(json), path: newDocumentPath(kind, id));

  /// A local skin document from the picked or fetched PNG, with the PNG kept
  /// as a workspace resource.
  (DocumentChange, String, Map<String, Uint8List?>)? _skinDocument() {
    final LoadedSkin? skin = _skin;
    if (skin == null) return null;
    final Uint8List png = SkinImages.pngBytes(skin);
    final String url = _skinUrl.trim();
    final ({String id, String source}) made = newSkin(slim: _slim, url: _skinSource == _SkinSource.url && isMojangSkinUrl(url) ? url : null, image: png);
    SkinImages.alias(made.id, skin);
    return (
      DocumentChange(DocumentKind.skin, made.id, made.source, path: newDocumentPath(DocumentKind.skin, made.id)),
      made.id,
      <String, Uint8List?>{skinImagePath(made.id): png},
    );
  }

  Future<void> _loadSkin({required bool fromUrl}) async {
    setState(() => _busy = true);
    try {
      final String key = 'new:${DateTime.now().microsecondsSinceEpoch}';
      final LoadedSkin? loaded = fromUrl ? await SkinImages.fetchUrl(key, _skinUrl) : await SkinImages.pickFile(key);
      if (loaded != null) setState(() => _skin = loaded);
    } on Object catch (failure) {
      ArcaneSonner.error('The skin did not load ($failure). Use a 64x64 skin PNG, or an address that allows loading from other sites.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _create(DocumentKind kind) async {
    final String? problem = kind == DocumentKind.skin || kind == DocumentKind.presets || kind == DocumentKind.serverBehaviors ? null : _idProblem(kind);
    if (problem != null) {
      setState(() => _problem = problem);
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      final String id = _idFor(kind);
      switch (kind) {
        case DocumentKind.npc:
          await _createNpc(id);
        case DocumentKind.behavior:
          final JsonMap behavior = behaviorFromTemplate(_template, id, shop: _shop, origin: _postOf(_attachTo));
          final List<DocumentChange> changes = <DocumentChange>[_change(kind, id, behavior)];
          final AnimusDocument? npc = _attachTo == null ? null : component.store.document(DocumentKind.npc, _attachTo!);
          if (npc != null && npc.json is Map<String, Object?>) {
            final JsonMap json = asMap(npc.json);
            changes.add(DocumentChange(DocumentKind.npc, npc.id, prettyJson(<String, Object?>{
              ...json,
              'behaviors': <String>{...stringList(json['behaviors']), id}.toList(),
            })));
          }
          await _write(changes, kind, id);
        case DocumentKind.pack:
          await _write(<DocumentChange>[_change(kind, id, newPack(id, name: _title, description: _name))], kind, id);
        case DocumentKind.route:
          await _write(<DocumentChange>[_change(kind, id, newRoute(origin: (_x.toDouble(), _y.toDouble(), _z.toDouble()), mode: _mode))], kind, id);
        case DocumentKind.shop:
        case DocumentKind.inventory:
        case DocumentKind.menu:
        case DocumentKind.hologram:
          await _write(<DocumentChange>[_change(kind, id, newGuiDocument(kind, id, title: _title))], kind, id);
        case DocumentKind.rig:
          await _write(<DocumentChange>[_change(kind, id, newRig(id, starter: _rigStarter, bodyShown: _bodyShown))], kind, id);
        case DocumentKind.animation:
          final AnimusDocument? rig = _rig == null ? null : component.store.document(DocumentKind.rig, _rig!);
          if (rig == null) {
            setState(() => _problem = 'Pick the rig this animation moves.');
            return;
          }
          final JsonMap animation = newRigAnimation(id, rig.id, asMap(rig.json), starter: _motion, durationTicks: _duration.toInt().clamp(1, 1200), loop: _loop);
          final String name = id.substring(id.lastIndexOf('/') + 1);
          final JsonMap rigJson = asMap(rig.json);
          final JsonMap listed = asMap(rigJson['animations']);
          await _write(<DocumentChange>[
            _change(kind, id, animation),
            if (!listed.containsKey(name) && RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$').hasMatch(name))
              DocumentChange(DocumentKind.rig, rig.id, prettyJson(<String, Object?>{...rigJson, 'animations': <String, Object?>{...listed, name: id}})),
          ], kind, id);
        case DocumentKind.skin:
          final (DocumentChange, String, Map<String, Uint8List?>)? skin = _skinDocument();
          if (skin == null) {
            setState(() => _problem = 'Load a 64x64 skin PNG first.');
            return;
          }
          if (component.store.document(DocumentKind.skin, skin.$2) != null) {
            component.onNavigate(DocumentRoute(component.workspace.id, kind, skin.$2));
            return;
          }
          await _write(<DocumentChange>[skin.$1], kind, skin.$2, resources: skin.$3);
        case DocumentKind.textAnimation:
          final List<String> frames = <String>[for (final String line in _frames.split('\n')) if (line.trim().isNotEmpty) line];
          await _write(<DocumentChange>[_change(kind, id, newTextAnimation(frames: frames.isEmpty ? const <String>['Hello'] : frames, intervalMs: _interval.toInt().clamp(50, 600000)))], kind, id);
        case DocumentKind.presets:
          if (component.store.document(kind, 'presets') != null) {
            component.onNavigate(DocumentRoute(component.workspace.id, kind, 'presets'));
            return;
          }
          await _write(<DocumentChange>[_change(kind, 'presets', newPresets())], kind, 'presets');
        case DocumentKind.serverBehaviors:
          const String listId = DocumentKind.serverBehaviorsId;
          if (component.store.document(kind, listId) != null) {
            component.onNavigate(DocumentRoute(component.workspace.id, kind, listId));
            return;
          }
          await _write(<DocumentChange>[_change(kind, listId, newServerBehaviors(_serverBehaviorIds))], kind, listId);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Behaviors in this workspace marked `"server": true`.
  List<String> get _serverBehaviorIds => <String>[
        for (final AnimusDocument document in _of(DocumentKind.behavior))
          if (asMap(document.json)['server'] == true) document.id,
      ];

  (double, double, double) _postOf(String? npcId) {
    final JsonMap position = asMap(asMap(npcId == null ? null : component.store.document(DocumentKind.npc, npcId)?.json)['position']);
    return (asDouble(position['x']) ?? 0, asDouble(position['y']) ?? 64, asDouble(position['z']) ?? 0);
  }

  Future<void> _createNpc(String id) async {
    final List<DocumentChange> changes = <DocumentChange>[];
    final Map<String, Uint8List?> resources = <String, Uint8List?>{};
    String? skin;
    if (_type == 'minecraft:player') {
      if (_skinSource == _SkinSource.existing) skin = _existingSkin;
      if (_skinSource == _SkinSource.file || _skinSource == _SkinSource.url) {
        final (DocumentChange, String, Map<String, Uint8List?>)? made = _skinDocument();
        if (made == null) {
          setState(() => _problem = 'Load the skin PNG, or pick Default skin.');
          return;
        }
        skin = made.$2;
        if (component.store.document(DocumentKind.skin, made.$2) == null) changes.add(made.$1);
        resources.addAll(made.$3);
      }
    }
    final List<String> behaviors = <String>[];
    if (_behavior.startsWith('new:')) {
      final BehaviorTemplate template = BehaviorTemplate.values.byName(_behavior.substring(4));
      final String behaviorId = freeId('$_namespace:${id.split('/').last}-${template.name}', _taken(DocumentKind.behavior));
      changes.add(_change(DocumentKind.behavior, behaviorId, behaviorFromTemplate(template, behaviorId, origin: (_x.toDouble(), _y.toDouble(), _z.toDouble()))));
      behaviors.add(behaviorId);
    } else if (_behavior.isNotEmpty) {
      behaviors.add(_behavior);
    }
    final List<String> parts = id.split('/');
    final NpcSeed seed = NpcSeed(
      name: _name.trim().isEmpty ? parts.last : _name.trim(),
      type: _type,
      group: parts.first,
      id: parts.last,
      skin: skin,
      world: _world,
      x: _x.toDouble(),
      y: _y.toDouble(),
      z: _z.toDouble(),
      yaw: _yaw.toDouble(),
      behaviors: behaviors,
    );
    changes.add(_change(DocumentKind.npc, id, newNpc(seed, uuid: newUuid())));
    await _write(changes, DocumentKind.npc, id, resources: resources);
  }

  @override
  Widget build(BuildContext context) {
    final DocumentKind? kind = component.kind;
    return dom.div(classes: 'animus-new-page${kind == null ? '' : ' has-kind'}', <Widget>[
      dom.nav(classes: 'animus-panel animus-kind-list', attributes: const <String, String>{'aria-label': 'Document kinds'}, <Widget>[
        const dom.h2(<Widget>[Component.text('New document')]),
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Everything here is made and checked in this browser. Pair a server later to send it.'),
        ]),
        dom.ul(attributes: const <String, String>{'role': 'list'}, <Widget>[
          for (final DocumentKind option in creatableKinds)
            dom.li(<Widget>[
              dom.a(
                href: NewDocumentRoute(component.workspace.id, option).format(),
                classes: 'animus-kind-link${option == kind ? ' is-selected' : ''}',
                attributes: <String, String>{if (option == kind) 'aria-current': 'page'},
                <Widget>[
                  dom.strong(<Widget>[Component.text(_singular(option))]),
                  dom.span(classes: 'animus-muted', <Widget>[Component.text(kindPurpose(option))]),
                ],
              ),
            ]),
        ]),
      ]),
      if (kind != null) _form(kind),
    ]);
  }

  Widget _form(DocumentKind kind) {
    final List<Widget> fields = switch (kind) {
      DocumentKind.npc => _npcFields(),
      DocumentKind.behavior => _behaviorFields(),
      DocumentKind.pack => <Widget>[
          _idField(kind),
          OfflineTextField(label: 'Name', value: _title, onChanged: (String value) => setState(() => _title = value)),
          OfflineTextField(label: 'Description', value: _name, onChanged: (String value) => setState(() => _name = value)),
        ],
      DocumentKind.route => <Widget>[
          OfflineTextField(label: 'Name', value: _name, onChanged: (String value) => setState(() => _name = value)),
          _idField(kind),
          FieldRow(
            label: 'Mode',
            child: GuiSelect(value: _mode, options: const <String>['walk', 'swim', 'fly'], onChanged: (String? value) => setState(() => _mode = value ?? 'walk')),
          ),
          _position(help: 'The first of four points in a square; drag them on the route grid afterwards.'),
        ],
      DocumentKind.shop || DocumentKind.inventory || DocumentKind.menu || DocumentKind.hologram => <Widget>[
          OfflineTextField(
            label: kind == DocumentKind.hologram ? 'First line' : 'Title',
            value: _title,
            help: '& colour codes work, such as &6Market.',
            onChanged: (String value) => setState(() => _title = value),
          ),
          _idField(kind),
        ],
      DocumentKind.rig => <Widget>[
          OfflineTextField(label: 'Name', value: _name, onChanged: (String value) => setState(() => _name = value)),
          _idField(kind),
          OptionCards<RigStarter>(
            label: 'Start from',
            value: _rigStarter,
            options: <CardOption<RigStarter>>[for (final RigStarter starter in RigStarter.values) CardOption<RigStarter>(starter, starter.label, starter.description)],
            onChanged: (RigStarter value) => setState(() => _rigStarter = value),
          ),
          GuiSwitch(value: _bodyShown, label: 'Show the NPC\'s own body under the rig', onChanged: (bool value) => setState(() => _bodyShown = value)),
        ],
      DocumentKind.animation => _animationFields(),
      DocumentKind.skin => <Widget>[..._skinFields(), if (_skin != null) _skinNote()],
      DocumentKind.textAnimation => <Widget>[
          OfflineTextField(label: 'Name', value: _name, onChanged: (String value) => setState(() => _name = value)),
          _idField(kind),
          FieldRow(
            label: 'Frames, one per line',
            child: GuiTextInput(value: _frames, multiline: true, label: 'Frames', onChanged: (String value) => setState(() => _frames = value)),
          ),
          FieldRow(
            label: 'Milliseconds per frame',
            child: GuiNumberInput(value: _interval, label: 'Milliseconds per frame', onChanged: (num? value) => setState(() => _interval = value ?? 500)),
          ),
        ],
      DocumentKind.presets => <Widget>[
          dom.p(classes: 'animus-muted', <Widget>[
            Component.text(component.store.document(kind, 'presets') == null
                ? 'A workspace has one presets document, gui/presets.json, with defaults for Gloss GUI documents.'
                : 'This workspace already has gui/presets.json; Open it to edit.'),
          ]),
        ],
      DocumentKind.serverBehaviors => <Widget>[
          dom.p(classes: 'animus-muted', <Widget>[
            Component.text(component.store.document(kind, DocumentKind.serverBehaviorsId) != null
                ? 'This workspace already has server-behaviors.json; open it to edit the list.'
                : _serverBehaviorIds.isEmpty
                    ? 'Creates server-behaviors.json with an empty list. No behavior here has "server": true yet; turn it on in a behavior\'s Document section, then add it to the list.'
                    : 'Creates server-behaviors.json listing the ${_serverBehaviorIds.length} server behavior${_serverBehaviorIds.length == 1 ? '' : 's'} in this workspace: ${_serverBehaviorIds.join(', ')}.'),
          ]),
        ],
    };
    final bool exists = (kind == DocumentKind.presets && component.store.document(kind, 'presets') != null) ||
        (kind == DocumentKind.serverBehaviors && component.store.document(kind, DocumentKind.serverBehaviorsId) != null);
    return dom.section(classes: 'animus-panel animus-new-form', attributes: <String, String>{'aria-label': 'New ${_inSentence(kind)}'}, <Widget>[
      dom.h2(<Widget>[Component.text('New ${_inSentence(kind)}')]),
      dom.p(classes: 'animus-muted', <Widget>[Component.text(kindPurpose(kind))]),
      ...fields,
      if (_problem != null) dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_problem!)]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.primary(
          label: exists ? 'Open ${_inSentence(kind)}' : 'Create ${_inSentence(kind)}',
          disabled: _busy,
          onPressed: () => _create(kind),
        ),
        Button.ghost(label: 'Cancel', onPressed: () => component.onNavigate(WorkspaceRoute(component.workspace.id))),
      ]),
    ]);
  }

  Widget _idField(DocumentKind kind) {
    final String? problem = _id == null ? null : _idProblem(kind);
    return OfflineTextField(
      label: kind == DocumentKind.npc ? 'File id' : 'Id',
      value: _idFor(kind),
      help: 'Written ${idHint(kind)}; it is also the file name.',
      error: problem,
      onChanged: (String value) => setState(() => _id = value),
    );
  }

  Widget _position({String? help}) => dom.fieldset(classes: 'animus-position', <Widget>[
        dom.legend(<Widget>[Component.text(help == null ? 'Position' : 'Position. $help')]),
        FieldRow(label: 'World', child: TextInput(value: _world, size: ComponentSize.sm, onChanged: (String value) => setState(() => _world = value))),
        dom.div(classes: 'animus-position-numbers', <Widget>[
          for (final (String axis, num value, void Function(num) set) in <(String, num, void Function(num))>[
            ('X', _x, (num value) => _x = value),
            ('Y', _y, (num value) => _y = value),
            ('Z', _z, (num value) => _z = value),
            if (component.kind == DocumentKind.npc) ('Yaw', _yaw, (num value) => _yaw = value),
          ])
            FieldRow(
              label: axis,
              child: GuiNumberInput(value: value, integer: false, label: axis, onChanged: (num? next) => setState(() => set(next ?? 0))),
            ),
        ]),
      ]);

  List<Widget> _npcFields() {
    final List<AnimusDocument> behaviors = _of(DocumentKind.behavior);
    return <Widget>[
      OfflineTextField(
        label: 'Name',
        value: _name,
        placeholder: 'Old Tom',
        help: 'Shown above the NPC; & colour codes work.',
        onChanged: (String value) => setState(() => _name = value),
      ),
      OfflineTextField(label: 'Folder', value: _group, help: 'NPCs in one folder are listed together, such as town.', onChanged: (String value) => setState(() => _group = value)),
      _idField(DocumentKind.npc),
      FieldRow(
        label: 'Body',
        child: GuiSelect(
          value: _type,
          options: mcBodyTypes,
          labels: bodyLabels,
          onChanged: (String? value) => setState(() => _type = value ?? 'minecraft:player'),
        ),
      ),
      if (_type == 'minecraft:player') ..._skinFields(withExisting: true),
      _position(),
      FieldRow(
        label: 'Behavior',
        help: 'What it does when clicked or approached. More can be added later.',
        child: GuiSelect(
          value: _behavior.isEmpty ? null : _behavior,
          allowNone: true,
          noneLabel: 'None yet',
          options: <String>[
            for (final BehaviorTemplate template in BehaviorTemplate.values) 'new:${template.name}',
            for (final AnimusDocument behavior in behaviors) behavior.id,
          ],
          labels: <String, String>{for (final BehaviorTemplate template in BehaviorTemplate.values) 'new:${template.name}': 'New from template: ${template.label}'},
          onChanged: (String? value) => setState(() => _behavior = value ?? ''),
        ),
      ),
    ];
  }

  List<Widget> _skinFields({bool withExisting = false}) {
    final List<AnimusDocument> skins = _of(DocumentKind.skin);
    if (!withExisting && (_skinSource == _SkinSource.none || _skinSource == _SkinSource.existing)) _skinSource = _SkinSource.file;
    final bool fromPng = _skinSource == _SkinSource.file || _skinSource == _SkinSource.url;
    return <Widget>[
      OptionCards<_SkinSource>(
        label: 'Skin',
        value: _skinSource,
        options: <CardOption<_SkinSource>>[
          if (withExisting) const CardOption<_SkinSource>(_SkinSource.none, 'Default skin', 'Steve or Alex'),
          const CardOption<_SkinSource>(_SkinSource.file, 'PNG file', 'A 64x64 skin from this computer'),
          const CardOption<_SkinSource>(_SkinSource.url, 'PNG address', 'A skin URL that allows cross-origin reads'),
          if (withExisting && skins.isNotEmpty) CardOption<_SkinSource>(_SkinSource.existing, 'Workspace skin', '${skins.length} in this workspace'),
        ],
        onChanged: (_SkinSource value) => setState(() => _skinSource = value),
      ),
      if (_skinSource == _SkinSource.existing)
        FieldRow(
          label: 'Workspace skin',
          child: GuiSelect(
            value: _existingSkin,
            options: <String>[for (final AnimusDocument skin in skins) skin.id],
            labels: <String, String>{for (final AnimusDocument skin in skins) skin.id: skin.id.substring(0, 12)},
            onChanged: (String? value) => setState(() => _existingSkin = value),
          ),
        ),
      if (_skinSource == _SkinSource.file)
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: _skin == null ? 'Choose skin PNG' : 'Choose another PNG', size: ButtonSize.small, disabled: _busy, onPressed: () => _loadSkin(fromUrl: false)),
          if (_skin != null) _face(_skin!),
        ]),
      if (_skinSource == _SkinSource.url) ...<Widget>[
        OfflineTextField(label: 'Skin PNG address', value: _skinUrl, placeholder: 'https://textures.minecraft.net/texture/...', onChanged: (String value) => setState(() => _skinUrl = value)),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Load skin', size: ButtonSize.small, disabled: _busy || _skinUrl.trim().isEmpty, onPressed: () => _loadSkin(fromUrl: true)),
          if (_skin != null) _face(_skin!),
        ]),
      ],
      if (fromPng) GuiSwitch(value: _slim, label: 'Slim arms (Alex model)', onChanged: (bool value) => setState(() => _slim = value)),
      if (withExisting && fromPng && _skin != null) _skinNote(),
    ];
  }

  Widget _face(LoadedSkin skin) => dom.span(
        classes: 'animus-skin-face',
        attributes: const <String, String>{'role': 'img', 'aria-label': 'Loaded skin face'},
        styles: dom.Styles(raw: <String, String>{'background-image': 'url("${skin.dataUrl}")'}),
        const <Widget>[],
      );

  Widget _skinNote() => dom.p(classes: 'animus-muted', <Widget>[
        Component.text(_skinSource == _SkinSource.url && isMojangSkinUrl(_skinUrl)
            ? 'The skin document names this textures.minecraft.net address without a signature. The preview wears the PNG; a server may need a signed texture to show it.'
            : 'The preview wears this PNG, kept in the workspace. A server shows the default skin until the skin document gets a signed texture.'),
      ]);

  List<Widget> _behaviorFields() {
    final List<AnimusDocument> shops = _of(DocumentKind.shop);
    final List<AnimusDocument> npcs = _of(DocumentKind.npc);
    return <Widget>[
      OptionCards<BehaviorTemplate>(
        label: 'Template',
        value: _template,
        options: <CardOption<BehaviorTemplate>>[
          for (final BehaviorTemplate template in BehaviorTemplate.values) CardOption<BehaviorTemplate>(template, template.label, template.description),
        ],
        onChanged: (BehaviorTemplate value) => setState(() => _template = value),
      ),
      _idField(DocumentKind.behavior),
      if (_template == BehaviorTemplate.shop && shops.isEmpty)
        dom.p(classes: 'animus-muted', <Widget>[
          const Component.text('This workspace has no shop yet, so the behavior opens example:shop. '),
          dom.a(href: NewDocumentRoute(component.workspace.id, DocumentKind.shop).format(), const <Widget>[Component.text('Make a shop')]),
          const Component.text(' and change it in the behavior editor.'),
        ])
      else if (_template == BehaviorTemplate.shop)
        FieldRow(
          label: 'Shop it opens',
          child: GuiSelect(
            value: _shop ??= shops.first.id,
            options: <String>[for (final AnimusDocument shop in shops) shop.id],
            onChanged: (String? value) => setState(() => _shop = value),
          ),
        ),
      FieldRow(
        label: 'Give it to an NPC',
        help: _template == BehaviorTemplate.patrol ? 'The patrol walks a square around this NPC\'s position.' : null,
        child: GuiSelect(
          value: _attachTo,
          allowNone: true,
          noneLabel: 'Not yet',
          options: <String>[for (final AnimusDocument npc in npcs) npc.id],
          labels: <String, String>{for (final AnimusDocument npc in npcs) npc.id: '${guiPlainText(npc.title)} (${npc.id})'},
          onChanged: (String? value) => setState(() => _attachTo = value),
        ),
      ),
    ];
  }

  List<Widget> _animationFields() {
    final List<AnimusDocument> rigs = _of(DocumentKind.rig);
    if (rigs.isEmpty) {
      return <Widget>[
        dom.p(<Widget>[
          const Component.text('An animation moves the bones of a rig, and this workspace has no rig yet. '),
          dom.a(href: NewDocumentRoute(component.workspace.id, DocumentKind.rig).format(), const <Widget>[Component.text('Make a rig first')]),
          const Component.text('.'),
        ]),
      ];
    }
    _rig ??= rigs.first.id;
    return <Widget>[
      FieldRow(
        label: 'Rig',
        child: GuiSelect(value: _rig, options: <String>[for (final AnimusDocument rig in rigs) rig.id], onChanged: (String? value) => setState(() => _rig = value)),
      ),
      OfflineTextField(label: 'Name', value: _name, placeholder: 'wave', onChanged: (String value) => setState(() => _name = value)),
      _idField(DocumentKind.animation),
      OptionCards<AnimationStarter>(
        label: 'Motion',
        value: _motion,
        options: <CardOption<AnimationStarter>>[
          for (final AnimationStarter starter in AnimationStarter.values) CardOption<AnimationStarter>(starter, starter.label, starter.description),
        ],
        onChanged: (AnimationStarter value) => setState(() => _motion = value),
      ),
      FieldRow(label: 'Length in ticks', child: GuiNumberInput(value: _duration, label: 'Length in ticks', onChanged: (num? value) => setState(() => _duration = value ?? 20))),
      FieldRow(
        label: 'Loop',
        child: GuiSelect(value: _loop, options: const <String>['loop', 'once', 'pingpong', 'hold'], onChanged: (String? value) => setState(() => _loop = value ?? 'loop')),
      ),
    ];
  }
}

String _singular(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'NPC',
      DocumentKind.inventory => 'Chest GUI',
      DocumentKind.textAnimation => 'Text animation',
      DocumentKind.presets => 'Presets',
      DocumentKind.serverBehaviors => 'Server behaviors list',
      _ => kind.label.endsWith('s') ? kind.label.substring(0, kind.label.length - 1) : kind.label,
    };

/// A kind's singular label inside a sentence: lowercase, except the
/// acronyms (NPC, GUI).
String _inSentence(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'NPC',
      DocumentKind.inventory => 'chest GUI',
      _ => _singular(kind).toLowerCase(),
    };

/// A kind's singular label, for buttons and headings.
String singularLabel(DocumentKind kind) => _singular(kind);
