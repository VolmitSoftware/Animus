/// The New page (`#/workspace/<id>/new[/<kind>]`): the fallback for making
/// any kind of document in the browser. One form under the page header: a
/// Kind picker when no kind is given (and in Advanced), the kind's few
/// common fields, the id and other rare fields under More, and Create last.
/// Each list's own New dialog is the usual way in; this page backs the
/// dropdowns' "+ New" trips and Search. No server is needed for any of it.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/gui/shop_entries.dart' show shopKinds;
import '../../logic/gui/shop_templates.dart';
import '../../logic/gui/shop_words.dart';
import '../../logic/npc/npc_paths.dart';
import '../../logic/npc/skin_edits.dart' show skinListing;
import '../../logic/offline/new_documents.dart';
import '../../logic/quest/quest_model.dart';
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../mc/rigs/mc_rigs.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../services/skin_images.dart';
import '../../state/document_return.dart';
import '../../state/skin_signing.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/dialog.dart';
import '../../ui/editor_mode.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../../ui/toggle_choice.dart';
import '../gui/gui_fields.dart';
import '../gui/shop_settings.dart' show chestLine;
import '../list_page.dart' show listNoun;
import '../shell_sidebar.dart' show kindTitle;
import '../quest/quest_fields.dart';
import '../select/skin_faces.dart';
import 'new_document_kinds.dart';
import '../select/animus_select.dart';
import '../suggest/suggest_scope.dart';
import '../select/person_picker.dart';
import '../../logic/suggest/person_names.dart' show PersonKind;
import '../../logic/suggest/completion_sources.dart' show CompletionSources;
import '../suggest/autocomplete_input.dart';
import '../../logic/suggest/field_profile.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';
import '../../mc/npc/npc_skin_failure.dart';

part 'new_document_fields.dart';

enum _SkinSource { none, file, url, existing }

class NewDocumentPage extends StatefulWidget {
  const NewDocumentPage({required this.store, required this.workspace, required this.kind, required this.onNavigate, this.folder, this.name, this.conversation = false, this.owner, this.players = const <String, String>{}, this.sign, this.onClose, super.key});

  /// Signs a new skin through the paired server; null makes an unsigned skin
  /// that keeps its PNG for the server it is applied on.
  final SkinSigner? sign;

  /// Shows the form as a list's New dialog, closed by this, instead of a page.
  final void Function()? onClose;

  /// Starts a behavior with the Conversation template picked.
  final bool conversation;

  /// The paired player's UUID, which a new NPC takes as its owner and a
  /// new chest shop offers first.
  final String? owner;

  /// Names of the players the paired server knows, by UUID: who a chest
  /// shop can belong to.
  final Map<String, String> players;

  final EditorStore store;
  final WorkspaceSnapshot workspace;
  final DocumentKind? kind;

  /// Where the new document goes: the NPC folder (such as `town/market`,
  /// empty for `npcs/` itself), the namespace of a namespaced kind, or the
  /// rig of an animation. Null for the default.
  final String? folder;

  /// The name the id starts from, typed into a dropdown's search.
  final String? name;
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
  late String _group = component.folder ?? 'local';
  String _world = 'world';
  num _x = 0.5, _y = 64, _z = 0.5, _yaw = 0;
  String _behavior = '';
  _SkinSource _skinSource = _SkinSource.none;
  String _skinUrl = '';
  String? _existingSkin;
  bool _slim = false;
  LoadedSkin? _skin;

  /// The workspace skins' names and faces, worked out once per skin list.
  List<AnimusDocument>? _skinsFor;
  ({Map<String, String> labels, Map<String, String> details, Map<String, String> faces})? _skinOptions;

  // Behavior.
  late BehaviorTemplate _template = component.conversation ? BehaviorTemplate.conversation : BehaviorTemplate.greeter;
  String? _shop;
  ShopTemplate _shopTemplate = ShopTemplate.blank;
  String? _shopOwner;
  String _shopKind = 'selling';
  JsonMap _chest = const <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0};
  String? _attachTo;

  // Quest.
  String? _giver;

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
  void initState() {
    super.initState();
    final DocumentKind? kind = component.kind;
    final String name = component.name?.trim() ?? '';
    if (kind == DocumentKind.animation && component.folder != null && component.store.document(DocumentKind.rig, component.folder!) != null) {
      _rig = component.folder;
    }
    if (kind == null || name.isEmpty) return;
    _name = name;
    if (!fixedIdKinds.contains(kind)) _id = _proposed(kind);
    if (kind == DocumentKind.pack) {
      _title = name;
      _name = '';
    }
  }

  @override
  void dispose() {
    // Leaving without creating ends a dropdown's "+ New" trip.
    final DocumentKind? kind = component.kind;
    if (kind != null) documentReturn.abandon(NewDocumentRoute.prefilled(component.workspace.id, kind, folder: component.folder, name: component.name));
    super.dispose();
  }

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

  String get _namespace => defaultNamespace(
        <String>[for (final AnimusDocument document in component.workspace.documents) document.id],
        component.workspace.name,
        server: component.workspace.server != null,
      );

  /// The namespace a dropdown's "+ New" asked for, when it is a valid one.
  String? get _folderNamespace {
    final String? folder = component.folder;
    if (folder == null || component.kind == DocumentKind.npc || component.kind == DocumentKind.animation) return null;
    return RegExp(r'^[a-z0-9_.-]+$').hasMatch(folder) ? folder : null;
  }

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
      DocumentKind.npc => npcIdIn(npcFolderSlug(_group), base),
      DocumentKind.route || DocumentKind.pack || DocumentKind.group => base,
      DocumentKind.animation => '${_rig ?? '$_namespace:rig'}/$base',
      _ => '${_folderNamespace ?? _namespace}:$base',
    };
    return freeId(candidate, _taken(kind));
  }

  String _idFor(DocumentKind kind) => (_id ?? _proposed(kind)).trim();

  String? _idProblem(DocumentKind kind) {
    final String id = _idFor(kind);
    if (!validDocumentId(kind, id)) return 'Use ${idHint(kind)}.';
    if (_taken(kind).contains(id)) return 'A ${listNoun(kind)} named $id already exists.';
    return null;
  }

  Future<void> _write(List<DocumentChange> changes, DocumentKind kind, String id, {Map<String, Uint8List?> resources = const <String, Uint8List?>{}}) async {
    await component.store.commit(documents: changes, resources: resources, label: 'create $id');
    final AnimusDocument? made = component.store.document(kind, id);
    if (made == null) {
      setState(() => _problem = component.store.problem ?? 'Not created: the workspace changed. Press Create again.');
      return;
    }
    // Its name as the lists show it: a shop by its title, never its id.
    final String name = component.store.libraryEntry(kind, id)?.name ?? guiPlainText(made.title);
    if (component.store.hasUnsavedEdit) {
      ArcaneSonner.warning('Created $name, but the browser did not store it.');
    } else if (!made.valid) {
      ArcaneSonner.warning('Created $name with ${made.diagnostics.length == 1 ? '1 problem' : '${made.diagnostics.length} problems'}.');
    } else {
      ArcaneSonner.success('Created ${listNoun(kind)} $name.');
    }
    final EditorRoute? back = documentReturn.complete(component.workspace.id, kind, id);
    if (back != null) {
      component.onNavigate(back);
      return;
    }
    component.onNavigate(DocumentRoute(
      component.workspace.id,
      kind,
      id,
      kind == DocumentKind.behavior && _template == BehaviorTemplate.conversation ? ViewMode.conversation : ViewMode.visual,
    ));
    focusMainHeading();
  }

  DocumentChange _change(DocumentKind kind, String id, Object? json) => DocumentChange(kind, id, prettyJson(json), path: newDocumentPath(kind, id));

  /// A skin document from the picked or fetched PNG, signed through the
  /// paired server when there is one, with the PNG kept as a workspace
  /// resource for the preview.
  Future<(DocumentChange, String, Map<String, Uint8List?>)?> _skinDocument() async {
    final LoadedSkin? skin = _skin;
    if (skin == null) return null;
    final Uint8List png = SkinImages.pngBytes(skin);
    final SkinSigner? sign = component.sign;
    if (sign != null) {
      final SignedSkin signed = await sign(png: png, slim: _slim);
      SkinImages.alias(signed.id, skin);
      return (
        DocumentChange(DocumentKind.skin, signed.id, signed.source, path: newDocumentPath(DocumentKind.skin, signed.id)),
        signed.id,
        <String, Uint8List?>{skinImagePath(signed.id): png},
      );
    }
    final String url = _skinUrl.trim();
    final ({String id, String source}) made = newSkin(slim: _slim, url: _skinSource == _SkinSource.url && isMojangSkinUrl(url) ? url : null, image: png);
    SkinImages.alias(made.id, skin);
    return (
      DocumentChange(DocumentKind.skin, made.id, made.source, path: newDocumentPath(DocumentKind.skin, made.id)),
      made.id,
      <String, Uint8List?>{skinImagePath(made.id): png},
    );
  }

  /// [_skinDocument], with a signing the server refused shown as the problem.
  Future<(DocumentChange, String, Map<String, Uint8List?>)?> _signedOrRefused() async {
    try {
      return await _skinDocument();
    } on Object catch (failure) {
      if (mounted) setState(() => _problem = signFailureText(failure));
      return null;
    }
  }

  Future<void> _loadSkin({required bool fromUrl}) async {
    setState(() => _busy = true);
    try {
      final String key = 'new:${DateTime.now().microsecondsSinceEpoch}';
      final LoadedSkin? loaded = fromUrl ? await SkinImages.fetchUrl(key, _skinUrl) : await SkinImages.pickFile(key);
      if (loaded != null) setState(() => _skin = loaded);
    } on Object catch (failure) {
      ArcaneSonner.error('Skin did not load. ${skinFailureText(failure)}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// The one-of-a-kind documents that already exist open instead of being made again.
  bool _exists(DocumentKind kind) => switch (kind) {
        DocumentKind.presets => component.store.document(kind, 'presets') != null,
        DocumentKind.serverBehaviors => component.store.document(kind, DocumentKind.serverBehaviorsId) != null,
        _ => false,
      };

  Future<void> _create(DocumentKind kind) async {
    final String? problem = fixedIdKinds.contains(kind) ? null : _idProblem(kind);
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
        case DocumentKind.group:
          await _write(<DocumentChange>[_change(kind, id, newGroup(id))], kind, id);
        case DocumentKind.route:
          await _write(<DocumentChange>[_change(kind, id, newRoute(origin: (_x.toDouble(), _y.toDouble(), _z.toDouble()), mode: _mode))], kind, id);
        case DocumentKind.recording:
          return;
        case DocumentKind.shop:
          ChestShopSeed? chest;
          if (_shopTemplate == ShopTemplate.chest) {
            chest = _chestSeed();
            if (chest == null) {
              setState(() => _problem = _shopOwnerChoices.isEmpty ? 'Pick the owner: pair a server to list its players, or type a UUID in Advanced.' : 'Pick the owner.');
              return;
            }
          }
          await _write(<DocumentChange>[_change(kind, id, shopFromTemplate(_shopTemplate, id, title: _title, chest: chest))], kind, id);
        case DocumentKind.quest:
          final JsonMap quest = newQuest(id, title: _title);
          await _write(<DocumentChange>[_change(kind, id, _giver == null ? quest : <String, Object?>{...quest, 'givers': <String>[_giver!]})], kind, id);
        case DocumentKind.inventory:
        case DocumentKind.menu:
        case DocumentKind.hologram:
          await _write(<DocumentChange>[_change(kind, id, newGuiDocument(kind, id, title: _title))], kind, id);
        case DocumentKind.rig:
          await _write(<DocumentChange>[_change(kind, id, newRig(id, starter: _rigStarter, bodyShown: _bodyShown))], kind, id);
        case DocumentKind.animation:
          await _createAnimation(id);
        case DocumentKind.skin:
          if (_skin == null) {
            setState(() => _problem = 'Load a 64x64 skin PNG first.');
            return;
          }
          final (DocumentChange, String, Map<String, Uint8List?>)? skin = await _signedOrRefused();
          if (skin == null) return;
          if (component.store.document(DocumentKind.skin, skin.$2) != null) {
            component.onNavigate(DocumentRoute(component.workspace.id, kind, skin.$2));
            return;
          }
          await _write(<DocumentChange>[skin.$1], kind, skin.$2, resources: skin.$3);
        case DocumentKind.textAnimation:
          final List<String> frames = <String>[for (final String line in _frames.split('\n')) if (line.trim().isNotEmpty) line];
          await _write(<DocumentChange>[_change(kind, id, newTextAnimation(frames: frames.isEmpty ? const <String>['Hello'] : frames, intervalMs: _interval.toInt().clamp(50, 600000)))], kind, id);
        case DocumentKind.presets:
          if (_exists(kind)) {
            component.onNavigate(DocumentRoute(component.workspace.id, kind, 'presets'));
            return;
          }
          await _write(<DocumentChange>[_change(kind, 'presets', newPresets())], kind, 'presets');
        case DocumentKind.serverBehaviors:
          const String listId = DocumentKind.serverBehaviorsId;
          if (_exists(kind)) {
            component.onNavigate(DocumentRoute(component.workspace.id, kind, listId));
            return;
          }
          await _write(<DocumentChange>[_change(kind, listId, newServerBehaviors(_serverBehaviorIds))], kind, listId);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _createAnimation(String id) async {
    final AnimusDocument? rig = _rig == null ? null : component.store.document(DocumentKind.rig, _rig!);
    if (rig == null) {
      setState(() => _problem = 'Pick a rig.');
      return;
    }
    final JsonMap rigJson = asMap(rig.json);
    final JsonMap animation = newRigAnimation(id, rig.id, rigJson, starter: _motion, durationTicks: _duration.toInt().clamp(1, 1200), loop: _loop);
    final String name = id.substring(id.lastIndexOf('/') + 1);
    final JsonMap listed = asMap(rigJson['animations']);
    await _write(<DocumentChange>[
      _change(DocumentKind.animation, id, animation),
      if (!listed.containsKey(name) && RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$').hasMatch(name))
        DocumentChange(DocumentKind.rig, rig.id, prettyJson(<String, Object?>{...rigJson, 'animations': <String, Object?>{...listed, name: id}})),
    ], DocumentKind.animation, id);
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
        if (_skin == null) {
          setState(() => _problem = 'Load the skin PNG, or pick Default.');
          return;
        }
        final (DocumentChange, String, Map<String, Uint8List?>)? made = await _signedOrRefused();
        if (made == null) return;
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
    final NpcSeed seed = NpcSeed(
      name: _name.trim().isEmpty ? npcLocalIdOf(id) : _name.trim(),
      type: _type,
      group: npcFolderOf(id),
      id: npcLocalIdOf(id),
      skin: skin,
      world: _world,
      x: _x.toDouble(),
      y: _y.toDouble(),
      z: _z.toDouble(),
      yaw: _yaw.toDouble(),
      behaviors: behaviors,
      owner: component.owner,
    );
    changes.add(_change(DocumentKind.npc, id, newNpc(seed, uuid: newUuid())));
    await _write(changes, DocumentKind.npc, id, resources: resources);
  }

  /// Who a new chest shop can belong to, by UUID: the paired player first,
  /// then the players the server knows. Labels are names; the paired
  /// player without a known name is "You".
  Map<String, String> get _shopOwnerChoices => <String, String>{
        if (component.owner case final String me) me: component.players[me] ?? 'You',
        for (final MapEntry<String, String> player in component.players.entries) player.key: player.value,
      };

  /// The owner a chest shop is made for: the picked player or a UUID typed
  /// in Advanced, else the paired player; null for a typed value that is not
  /// a UUID.
  String? get _chestOwner {
    final String typed = (_shopOwner ?? '').trim().toLowerCase();
    if (typed.isEmpty) return _shopOwnerChoices.keys.firstOrNull;
    return RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$').hasMatch(typed) ? typed : null;
  }

  ChestShopSeed? _chestSeed() {
    final String? owner = _chestOwner;
    if (owner == null) return null;
    return ChestShopSeed(
      owner: owner,
      ownerName: component.players[owner],
      world: asString(_chest['world']) ?? 'world',
      x: asInt(_chest['x']) ?? 0,
      y: asInt(_chest['y']) ?? 64,
      z: asInt(_chest['z']) ?? 0,
      kind: _shopKind,
    );
  }

  /// setState for the field builders in new_document_fields.dart.
  void _set(void Function() change) => setState(change);

  bool get _returning {
    final DocumentKind? kind = component.kind;
    return kind != null && documentReturn.returnsFor(component.workspace.id, kind) && documentReturn.origin != null;
  }

  void _cancel() {
    final void Function()? close = component.onClose;
    if (close != null) return close();
    component.onNavigate(_returning ? documentReturn.origin! : WorkspaceRoute(component.workspace.id));
  }

  /// Create, or Open when the id is already taken; the same word on every
  /// New dialog and page.
  RowAction _createAction(DocumentKind kind) => RowAction(
        _exists(kind) ? 'Open ${listNoun(kind)}' : 'Create',
        _busy ? null : () => _create(kind),
        loading: _busy,
        id: 'new-create',
      );

  @override
  Widget build(BuildContext context) {
    final DocumentKind? kind = component.kind;
    final EditorMode mode = ModeScope.of(context);
    final WorkspaceSnapshot workspace = component.workspace;
    final List<Widget> fields = kind == null ? const <Widget>[] : _fields(kind, mode);
    final Widget? problem = _problem == null ? null : AppAlert(key: const ValueKey<String>('new-problem'), tone: AlertTone.error, message: _problem!, live: true);
    if (component.onClose != null && kind != null) {
      return AppDialog(
        open: true,
        id: 'new-dialog',
        title: 'New ${listNoun(kind)}',
        onClose: _cancel,
        footer: DialogFooter(cancel: RowAction('Cancel', _cancel), primary: kind == DocumentKind.recording ? null : _createAction(kind)),
        children: <Widget>[stack(spacing: 16, <Widget>[...fields, ?problem])],
      );
    }
    return stack(spacing: 16, <Widget>[
      PageHeader(
        title: kind == null ? 'New file' : 'New ${listNoun(kind)}',
        crumbs: <Crumb>[
          Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()),
          if (kind != null && kind != DocumentKind.presets && kind != DocumentKind.serverBehaviors) Crumb(kindTitle(kind), href: KindListRoute(workspace.id, kind).format()),
          const Crumb('New'),
        ],
      ),
      dom.div(classes: 'ui-doc-form', <Widget>[
        if (kind == null || mode == EditorMode.advanced) _kindField(kind, mode),
        if (_returning) const Text('Create takes you back and picks it there.', size: FontSize.sm, color: TextColor.muted, element: 'p'),
        ...fields,
        ?problem,
        if (kind != null && kind != DocumentKind.recording)
          FormFooter(cancel: RowAction('Cancel', _cancel), primary: _createAction(kind))
        else if (kind == DocumentKind.recording)
          FormFooter(cancel: RowAction('Back', _cancel)),
      ]),
    ]);
  }

  Widget _kindField(DocumentKind? kind, EditorMode mode) {
    final List<DocumentKind> kinds = newPageKinds(mode, current: kind);
    return Field(
      label: 'Kind',
      forId: 'new-kind',
      hint: kind == null ? 'Pick what to make.' : null,
      child: AnimusSelect.values(
        id: 'new-kind',
        value: kind?.wireName,
        noneLabel: 'Choose one',
        values: <String>[for (final DocumentKind each in kinds) each.wireName],
        labels: <String, String>{for (final DocumentKind each in kinds) each.wireName: _sentenceStart(listNoun(each))},
        details: <String, String>{for (final DocumentKind each in kinds) each.wireName: kindPurpose(each)},
        onChanged: (String? picked) {
          final DocumentKind? next = picked == null ? null : DocumentKind.parse(picked);
          if (next != null && next != kind) component.onNavigate(NewDocumentRoute(component.workspace.id, next));
        },
      ),
    );
  }
}

String _sentenceStart(String text) => text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}';
