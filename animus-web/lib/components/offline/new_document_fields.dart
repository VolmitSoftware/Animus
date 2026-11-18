part of 'new_document_page.dart';

/// The fields of each kind's form. The common fields come first; the id,
/// the folder and other rare fields sit under More. The id is Advanced
/// only: in Simple it follows the name.
extension _NewDocumentFields on _NewDocumentPageState {
  List<Widget> _fields(DocumentKind kind, EditorMode mode) {
    final bool advanced = mode == EditorMode.advanced;
    final List<Widget> more = <Widget>[
      // A behavior asks for its folder beside its name.
      if (namespacedKind(kind) && kind != DocumentKind.behavior) _folderField(kind),
      if (advanced && !fixedIdKinds.contains(kind)) _idField(kind),
    ];
    final List<Widget> fields = switch (kind) {
      DocumentKind.npc => const <Widget>[],
      DocumentKind.behavior => _behaviorFields(advanced),
      DocumentKind.pack => <Widget>[
          _text('Name', 'new-pack-name', _title, (String value) => _title = value, kind: kind),
          _text('Description', 'new-pack-description', _name, (String value) => _name = value, kind: kind),
        ],
      DocumentKind.group => <Widget>[if (!advanced) _idField(kind)],
      DocumentKind.route => <Widget>[
          _text('Name', 'new-route-name', _name, (String value) => _name = value, kind: kind),
          Field(
            label: 'Moves by',
            child: ToggleChoice<String>(
              label: 'Moves by',
              value: _mode,
              options: const <ChoiceOption<String>>[ChoiceOption<String>('walk', 'Walking'), ChoiceOption<String>('swim', 'Swimming'), ChoiceOption<String>('fly', 'Flying')],
              onChanged: (String value) => _set(() => _mode = value),
            ),
          ),
          MoreFields(label: 'Start point', children: _position()),
        ],
      DocumentKind.recording => <Widget>[
          const AppAlert(tone: AlertTone.info, title: 'Recorded in game', message: 'Run /animus record start <name> in game, move, then stop it. It shows up under Movement.'),
        ],
      DocumentKind.shop => <Widget>[
          _text('Title', 'new-shop-title', _title, (String value) => _title = value, kind: kind),
          Field(
            label: 'Start from',
            forId: 'new-shop-template',
            child: AnimusSelect.values(
              id: 'new-shop-template',
              value: _shopTemplate.name,
              values: <String>[for (final ShopTemplate template in ShopTemplate.values) template.name],
              labels: <String, String>{for (final ShopTemplate template in ShopTemplate.values) template.name: template.label},
              details: <String, String>{for (final ShopTemplate template in ShopTemplate.values) template.name: template.description},
              onChanged: (String? value) => _set(() => _shopTemplate = ShopTemplate.values.asNameMap()[value] ?? ShopTemplate.blank),
            ),
          ),
          if (_shopTemplate == ShopTemplate.chest) ..._chestShopFields(advanced),
        ],
      DocumentKind.quest => <Widget>[
          _text('Title', 'new-quest-title', _title, (String value) => _title = value, kind: kind),
          Field(
            label: 'Giver',
            forId: 'animus-new-quest-giver',
            hint: 'The NPC that offers it',
            child: npcPicker(
              id: 'animus-new-quest-giver',
              label: 'Giver',
              value: _giver,
              npcs: QuestNpcs(_of(DocumentKind.npc)),
              allowNone: true,
              noneLabel: 'None yet',
              onChanged: (String? value) => _set(() => _giver = value),
            ),
          ),
        ],
      DocumentKind.inventory || DocumentKind.menu => <Widget>[
          _text('Title', 'new-gui-title', _title, (String value) => _title = value, kind: kind),
        ],
      DocumentKind.hologram => <Widget>[
          _text('First line', 'new-hologram-line', _title, (String value) => _title = value, kind: kind),
        ],
      DocumentKind.rig => <Widget>[
          _text('Name', 'new-rig-name', _name, (String value) => _name = value, kind: kind),
          Field(
            label: 'Start from',
            child: ToggleChoice<RigStarter>(
              label: 'Start from',
              value: _rigStarter,
              options: <ChoiceOption<RigStarter>>[for (final RigStarter starter in RigStarter.values) ChoiceOption<RigStarter>(starter, starter.label)],
              onChanged: (RigStarter value) => _set(() => _rigStarter = value),
            ),
          ),
          Field(label: 'NPC body', child: ArcaneToggleSwitch(value: _bodyShown, label: 'Show the NPC body', onChanged: (bool value) => _set(() => _bodyShown = value), size: ComponentSize.sm)),
        ],
      DocumentKind.animation => _animationFields(),
      DocumentKind.skin => _skinFields(),
      DocumentKind.textAnimation => <Widget>[
          _text('Name', 'new-text-name', _name, (String value) => _name = value, kind: kind),
          Field(
            label: 'Frames',
            hint: 'One per line',
            child: AutocompleteInput(id: 'new-text-frames', value: _frames, multiline: true, label: 'Frames', onChanged: (String value) => _set(() => _frames = value), profile: FieldProfile.none),
          ),
          Field(
            label: 'Frame time',
            hint: 'Milliseconds',
            child: NumberInput(id: 'new-text-interval', value: _interval, label: 'Frame time', onChanged: (num? value) => _set(() => _interval = value ?? 500)),
          ),
        ],
      DocumentKind.presets => <Widget>[
          Text(_exists(kind) ? 'This workspace already has its presets.' : 'Shared GUI looks other GUIs build on.', size: FontSize.sm, color: TextColor.muted, element: 'p'),
        ],
      DocumentKind.serverBehaviors => <Widget>[
          Text(
            _exists(kind)
                ? 'This workspace already has its server behaviors list.'
                : _serverBehaviorIds.isEmpty
                    ? 'Starts empty. No behavior here runs on the server yet.'
                    : 'Starts with ${_serverBehaviorIds.length == 1 ? '1 server behavior' : '${_serverBehaviorIds.length} server behaviors'}.',
            size: FontSize.sm,
            color: TextColor.muted,
            element: 'p',
          ),
        ],
    };
    return <Widget>[
      ...fields,
      if (more.isNotEmpty) ...more,
    ];
  }

  /// A one-line text field; Enter creates.
  /// A chest shop's owner, kind and chest, as `/animus shop create` asks
  /// for them. Owners are picked by name; Advanced can also type a UUID.
  List<Widget> _chestShopFields(bool advanced) {
    final Map<String, String> owners = _shopOwnerChoices;
    final String? owner = _chestOwner;
    final String? typed = _shopOwner?.trim();
    return <Widget>[
      Field(
        label: 'Owner',
        forId: 'new-shop-owner',
        tip: 'The player who owns the chest and runs the shop in game.',
        docs: Docs.playerShops,
        error: typed != null && typed.isNotEmpty && owner == null ? 'Expected a player UUID, such as 069a79f4-44e9-4726-a5be-fca90e38aaf5.' : null,
        // The same picker as a shop's Settings, over the paired player and the players the server has seen.
        child: SuggestScope(
          sources: CompletionSources.empty.copyWith(players: owners),
          child: PersonPicker(
            id: 'new-shop-owner',
            kind: PersonKind.player,
            label: 'Owner',
            value: owner ?? typed,
            onChanged: (String? picked) => _set(() => _shopOwner = picked),
          ),
        ),
      ),
      Field(
        label: 'Kind',
        forId: 'new-shop-kind',
        tip: 'What the shop does with its chest. Change it later in Settings.',
        docs: Docs.playerShops,
        child: AnimusSelect.values(
          id: 'new-shop-kind',
          value: _shopKind,
          values: shopKinds,
          labels: shopKindLabels,
          details: shopKindLines,
          onChanged: (String? value) => _set(() => _shopKind = value ?? 'selling'),
        ),
      ),
      Field(
        label: 'Chest',
        tip: 'The chest that holds the stock and takes the earnings. Add more in Settings.',
        docs: Docs.chestShops,
        child: chestLine(
          id: 'new-shop-chest',
          label: 'Chest',
          chest: _chest,
          onChanged: (String key, Object? value) => _set(() => _chest = <String, Object?>{..._chest, key: value}),
        ),
      ),
    ];
  }

  Widget _text(String label, String id, String value, void Function(String) set, {required DocumentKind kind, String? hint, String? placeholder}) => Field(
        label: label,
        forId: id,
        hint: hint,
        child: TextInput(
          id: id,
          value: value,
          placeholder: placeholder,
          onChanged: (String next) => _set(() => set(next)),
          onSubmitted: (String _) {
            if (!_busy) _create(kind);
          },
        ),
      );

  /// The folder (namespace) of a namespaced kind, from the workspace's
  /// folders or a new one. Behaviors in one folder share flags and quests.
  Widget _folderField(DocumentKind kind) {
    final List<String> folders = workspaceNamespaces(<String>[for (final AnimusDocument document in component.workspace.documents) document.id]);
    return Field(
      label: 'Folder',
      forId: 'new-folder',
      tip: kind == DocumentKind.behavior ? 'Where the file goes. Behaviors in one folder share their flags and quests.' : 'Where the file goes, such as town.',
      child: SuggestInput(
        id: 'new-folder',
        value: _shownFolder,
        label: 'Folder',
        suggestions: <FieldOption>[for (final String folder in folders) FieldOption(folder)],
        onChanged: (String value) => _set(() {
          _folder = value.trim();
          final String? id = _id;
          if (id != null && id.contains(':')) _id = '${_folder!}:${id.substring(id.indexOf(':') + 1)}';
        }),
      ),
    );
  }

  Widget _idField(DocumentKind kind) {
    final String? problem = _id == null ? null : _idProblem(kind);
    return Field(
      label: 'Id',
      forId: 'new-id',
      hint: idHint(kind),
      error: problem,
      child: TextInput(
        id: 'new-id',
        value: _idFor(kind),
        onChanged: (String value) => _set(() {
          _id = value;
          if (namespacedKind(kind) && value.contains(':')) _folder = value.substring(0, value.indexOf(':'));
        }),
        onSubmitted: (String _) {
          if (!_busy) _create(kind);
        },
      ),
    );
  }

  List<Widget> _position() => <Widget>[
        Field(label: 'World', forId: 'new-world', child: TextInput(id: 'new-world', value: _world, onChanged: (String value) => _set(() => _world = value))),
        for (final (String axis, num value, void Function(num) set) in <(String, num, void Function(num))>[
          ('X', _x, (num value) => _x = value),
          ('Y', _y, (num value) => _y = value),
          ('Z', _z, (num value) => _z = value),
        ])
          Field(
            label: axis,
            forId: 'new-${axis.toLowerCase()}',
            child: NumberInput(id: 'new-${axis.toLowerCase()}', value: value, integer: false, label: axis, onChanged: (num? next) => _set(() => set(next ?? 0))),
          ),
      ];

  List<Widget> _skinFields() {
    final bool fromPng = _skinSource == _SkinSource.file || _skinSource == _SkinSource.url;
    return <Widget>[
      Field(
        label: 'Skin',
        child: ToggleChoice<_SkinSource>(
          label: 'Skin',
          value: _skinSource,
          options: <ChoiceOption<_SkinSource>>[
            const ChoiceOption<_SkinSource>(_SkinSource.file, 'PNG file'),
            const ChoiceOption<_SkinSource>(_SkinSource.url, 'Address'),
          ],
          onChanged: (_SkinSource value) => _set(() => _skinSource = value),
        ),
      ),
      if (_skinSource == _SkinSource.file)
        Field(
          label: 'PNG',
          child: ActionRow(
            leading: <Widget>[if (_skin != null) _face(_skin!)],
            secondary: <RowAction>[RowAction(_skin == null ? 'Choose PNG' : 'Choose another', _busy ? null : () => _loadSkin(fromUrl: false))],
          ),
        ),
      if (_skinSource == _SkinSource.url) ...<Widget>[
        Field(
          label: 'Address',
          forId: 'new-skin-url',
          child: TextInput(
            id: 'new-skin-url',
            value: _skinUrl,
            placeholder: 'https://textures.minecraft.net/texture/...',
            onChanged: (String value) => _set(() => _skinUrl = value),
            onSubmitted: (String _) {
              if (!_busy && _skinUrl.trim().isNotEmpty) _loadSkin(fromUrl: true);
            },
          ),
        ),
        Field(
          label: 'PNG',
          child: ActionRow(
            leading: <Widget>[if (_skin != null) _face(_skin!)],
            secondary: <RowAction>[RowAction('Load skin', _busy || _skinUrl.trim().isEmpty ? null : () => _loadSkin(fromUrl: true))],
          ),
        ),
      ],
      if (fromPng) Field(label: 'Arms', child: ArcaneToggleSwitch(value: _slim, label: 'Slim arms', onChanged: (bool value) => _set(() => _slim = value), size: ComponentSize.sm)),
      if (fromPng && _skin != null)
        Text(
          component.sign != null ? 'Signed on the server when you create it.' : 'Not paired: signed when applied on a server.',
          size: FontSize.sm,
          color: TextColor.muted,
          element: 'p',
        ),
    ];
  }

  Widget _face(LoadedSkin skin) => dom.span(
        classes: 'animus-skin-face',
        attributes: const <String, String>{'role': 'img', 'aria-label': 'Loaded skin face'},
        styles: dom.Styles(raw: <String, String>{'background-image': 'url("${skin.dataUrl}")'}),
        const <Widget>[],
      );

  List<Widget> _behaviorFields(bool advanced) {
    final List<AnimusDocument> shops = _of(DocumentKind.shop);
    final List<AnimusDocument> npcs = _of(DocumentKind.npc);
    final List<BehaviorTemplate> templates = <BehaviorTemplate>[
      for (final BehaviorTemplate template in BehaviorTemplate.values)
        if (advanced || !template.advancedOnly) template,
    ];
    return <Widget>[
      Field(
        label: 'Start from',
        forId: 'new-behavior-template',
        child: AnimusSelect.values(
          id: 'new-behavior-template',
          value: _template.name,
          values: <String>[for (final BehaviorTemplate template in templates) template.name],
          labels: <String, String>{for (final BehaviorTemplate template in templates) template.name: template.label},
          details: <String, String>{for (final BehaviorTemplate template in templates) template.name: template.description},
          onChanged: (String? value) => _set(() => _template = BehaviorTemplate.values.asNameMap()[value] ?? BehaviorTemplate.greeter),
        ),
      ),
      _text('Name', 'new-behavior-name', _name, (String value) => _name = value, kind: DocumentKind.behavior, placeholder: _template.label),
      _folderField(DocumentKind.behavior),
      if (_template == BehaviorTemplate.shop && shops.isEmpty)
        AppAlert(
          tone: AlertTone.info,
          message: 'No shop here yet, so it opens example:shop.',
          action: Button.outline(
            label: 'New shop',
            size: ButtonSize.sm,
            onPressed: () => component.onNavigate(NewDocumentRoute(component.workspace.id, DocumentKind.shop)),
          ),
        )
      else if (_template == BehaviorTemplate.shop)
        Field(
          label: 'Shop',
          forId: 'new-behavior-shop',
          child: AnimusSelect.values(
            id: 'new-behavior-shop',
            references: DocumentKind.shop,
            value: _shop ??= shops.first.id,
            values: <String>[for (final AnimusDocument shop in shops) shop.id],
            labels: <String, String>{for (final AnimusDocument shop in shops) shop.id: guiPlainText(shop.title)},
            onChanged: (String? value) => _set(() => _shop = value),
          ),
        ),
      if (_template != BehaviorTemplate.server)
        Field(
          label: 'Give to NPC',
          forId: 'new-behavior-npc',
          hint: _template == BehaviorTemplate.patrol ? 'Patrols around this NPC' : null,
          child: AnimusSelect.values(
            id: 'new-behavior-npc',
            value: _attachTo,
            allowNone: true,
            noneLabel: 'Not yet',
            values: <String>[for (final AnimusDocument npc in npcs) npc.id],
            labels: <String, String>{for (final AnimusDocument npc in npcs) npc.id: guiPlainText(npc.title)},
            onChanged: (String? value) => _set(() => _attachTo = value),
          ),
        ),
    ];
  }

  List<Widget> _animationFields() {
    final List<AnimusDocument> rigs = _of(DocumentKind.rig);
    if (rigs.isEmpty) {
      return <Widget>[
        AppAlert(
          tone: AlertTone.info,
          message: 'Animations need a rig.',
          action: Button.outline(
            label: 'New rig',
            size: ButtonSize.sm,
            onPressed: () => component.onNavigate(NewDocumentRoute(component.workspace.id, DocumentKind.rig)),
          ),
        ),
      ];
    }
    _rig ??= rigs.first.id;
    return <Widget>[
      Field(
        label: 'Rig',
        forId: 'new-animation-rig',
        child: AnimusSelect.values(
          id: 'new-animation-rig',
          references: DocumentKind.rig,
          value: _rig,
          values: <String>[for (final AnimusDocument rig in rigs) rig.id],
          onChanged: (String? value) => _set(() => _rig = value),
        ),
      ),
      _text('Name', 'new-animation-name', _name, (String value) => _name = value, kind: DocumentKind.animation, placeholder: 'wave'),
      Field(
        label: 'Motion',
        child: ToggleChoice<AnimationStarter>(
          label: 'Motion',
          value: _motion,
          options: <ChoiceOption<AnimationStarter>>[for (final AnimationStarter starter in AnimationStarter.values) ChoiceOption<AnimationStarter>(starter, starter.label)],
          onChanged: (AnimationStarter value) => _set(() => _motion = value),
        ),
      ),
      MoreFields(children: <Widget>[
        Field(
          label: 'Length',
          forId: 'new-animation-length',
          hint: 'Ticks, 20 a second',
          child: NumberInput(id: 'new-animation-length', value: _duration, label: 'Length', onChanged: (num? value) => _set(() => _duration = value ?? 20)),
        ),
        Field(
          label: 'Loop',
          forId: 'new-animation-loop',
          child: AnimusSelect.values(
            id: 'new-animation-loop',
            value: _loop,
            values: const <String>['loop', 'once', 'pingpong', 'hold'],
            labels: const <String, String>{'loop': 'Loop', 'once': 'Once', 'pingpong': 'Back and forth', 'hold': 'Hold the last frame'},
            onChanged: (String? value) => _set(() => _loop = value ?? 'loop'),
          ),
        ),
      ]),
    ];
  }
}
