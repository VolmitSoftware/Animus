import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/workspace_route.dart';
import 'npc_preview.dart';

/// One document with its Visual, Preview, Code and Split views.
class DocumentPane extends StatefulWidget {
  const DocumentPane({
    required this.document,
    required this.view,
    required this.onView,
    required this.onSource,
    this.skinDocument,
    this.editor,
    super.key,
  });

  /// A structured editor (the rig editor) shown as Visual and Preview, and
  /// beside the code in Split.
  final Widget? editor;

  final AnimusDocument document;

  /// For an NPC: the skin document its `skin` names, when present.
  final AnimusDocument? skinDocument;
  final ViewMode view;
  final void Function(ViewMode view) onView;
  final Future<void> Function(String source) onSource;

  @override
  State<DocumentPane> createState() => _DocumentPaneState();
}

class _DocumentPaneState extends State<DocumentPane> {
  String? _draft;
  String? _draftError;

  static const Map<ViewMode, String> _labels = <ViewMode, String>{
    ViewMode.visual: 'Visual',
    ViewMode.preview: 'Preview',
    ViewMode.code: 'Code',
    ViewMode.split: 'Split',
  };

  void _edit(String value) {
    String? error;
    try {
      jsonDecode(value);
    } on FormatException catch (invalid) {
      error = invalid.message;
    }
    setState(() {
      _draft = value;
      _draftError = error;
    });
  }

  Future<void> _apply() async {
    final String? draft = _draft;
    if (draft == null || _draftError != null) return;
    await component.onSource(draft);
    if (mounted) setState(() => _draft = null);
  }

  @override
  Widget build(BuildContext context) {
    final AnimusDocument document = component.document;
    final ViewMode view = component.view;
    return dom.div(classes: 'animus-document', <Widget>[
      dom.header(classes: 'animus-document-header', <Widget>[
        dom.div(<Widget>[
          dom.span(classes: 'animus-eyebrow', <Widget>[Component.text(document.kind.label)]),
          dom.h2(<Widget>[Component.text(document.title)]),
          dom.code(classes: 'animus-muted', <Widget>[Component.text(document.path)]),
        ]),
        dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'tablist'}, <Widget>[
          for (final ViewMode option in ViewMode.values)
            Button(
              label: _labels[option],
              variant: option == view ? ButtonVariant.primary : ButtonVariant.ghost,
              size: ButtonSize.small,
              attributes: <String, String>{'role': 'tab', 'aria-selected': '${option == view}'},
              onPressed: () => component.onView(option),
            ),
        ]),
      ]),
      _diagnostics(document),
      dom.div(classes: 'animus-views is-${view.name}${component.editor != null ? ' has-editor' : ''}', <Widget>[
        if (component.editor != null && view != ViewMode.code)
          component.editor!
        else if (view == ViewMode.visual || (view == ViewMode.split && !_staged(document)))
          _visual(document),
        if (component.editor == null && (view == ViewMode.preview || (view == ViewMode.split && _staged(document)))) _preview(document),
        if (view == ViewMode.code || view == ViewMode.split) _code(document),
      ]),
    ]);
  }

  Widget _diagnostics(AnimusDocument document) {
    if (document.diagnostics.isEmpty) {
      return const dom.p(classes: 'animus-ok', <Widget>[Component.text('Valid against the shared schema.')]);
    }
    return dom.ul(classes: 'animus-diagnostics', <Widget>[
      for (final Diagnostic diagnostic in document.diagnostics.take(50))
        dom.li(classes: 'is-${diagnostic.severity.name}', <Widget>[
          dom.code(<Widget>[Component.text(diagnostic.path)]),
          Component.text(' ${diagnostic.message}'),
        ]),
    ]);
  }

  Widget _visual(AnimusDocument document) {
    final Object? json = document.json;
    if (json is! Map<String, Object?>) {
      return const dom.section(classes: 'animus-panel', <Widget>[Component.text('The document is not a JSON object.')]);
    }
    return dom.section(classes: 'animus-panel animus-fields', <Widget>[
      dom.table(<Widget>[
        dom.tbody(<Widget>[
          for (final MapEntry<String, Object?> field in json.entries)
            dom.tr(<Widget>[
              dom.th(attributes: const <String, String>{'scope': 'row'}, <Widget>[Component.text(field.key)]),
              dom.td(<Widget>[Component.text(summarize(field.value))]),
            ]),
        ]),
      ]),
    ]);
  }

  /// Kinds whose Preview is the 3D stage, which Split shows beside the code.
  bool _staged(AnimusDocument document) => document.kind == DocumentKind.npc && document.json is Map<String, Object?>;

  Widget _preview(AnimusDocument document) {
    if (_staged(document)) {
      return NpcPreview(key: ValueKey<String>('npc-preview:${document.id}'), document: document, skinDocument: component.skinDocument);
    }
    final Object? json = document.json;
    final Map<String, Object?> object = json is Map<String, Object?> ? json : const <String, Object?>{};
    final List<(String, String)> facts = previewFacts(document.kind, object);
    return dom.section(classes: 'animus-panel animus-preview', <Widget>[
      if (document.kind == DocumentKind.inventory) _chestGrid(object),
      dom.dl(<Widget>[
        for (final (String label, String value) in facts) ...<Widget>[
          dom.dt(<Widget>[Component.text(label)]),
          dom.dd(<Widget>[Component.text(value)]),
        ],
      ]),
    ]);
  }

  Widget _chestGrid(Map<String, Object?> json) {
    final String resolution = json['resolution'] is String ? json['resolution']! as String : '9x3';
    final int width = int.tryParse(resolution.split('x').first) ?? 9;
    final List<String> mask = <String>[
      for (final Object? row in json['mask'] is List<Object?> ? json['mask']! as List<Object?> : const <Object?>[]) '$row',
    ];
    return dom.div(
      classes: 'animus-chest',
      styles: dom.Styles(raw: <String, String>{'grid-template-columns': 'repeat($width, 2rem)'}),
      <Widget>[
        for (final String row in mask)
          for (int column = 0; column < width; column++)
            dom.span(classes: 'animus-chest-slot', <Widget>[
              Component.text(column < row.length && row[column] != ' ' ? row[column] : ''),
            ]),
      ],
    );
  }

  Widget _code(AnimusDocument document) {
    final String text = _draft ?? document.source;
    return dom.section(classes: 'animus-panel animus-code', <Widget>[
      TextArea(
        value: text,
        rows: 28,
        minHeight: '24rem',
        fullWidth: true,
        onChanged: _edit,
      ),
      dom.div(classes: 'animus-row', <Widget>[
        Button.primary(
          label: 'Apply to draft',
          size: ButtonSize.small,
          disabled: _draft == null || _draftError != null,
          onPressed: _apply,
        ),
        if (_draft != null)
          Button.ghost(label: 'Discard', size: ButtonSize.small, onPressed: () => setState(() => _draft = null)),
        if (_draftError != null) dom.span(classes: 'animus-error', <Widget>[Component.text('Invalid JSON: $_draftError')]),
      ]),
    ]);
  }
}

/// A one-line description of a JSON value for the Visual table.
String summarize(Object? value) {
  if (value is Map<String, Object?>) {
    return value.isEmpty ? '{}' : '{ ${value.keys.take(6).join(', ')}${value.length > 6 ? ', ...' : ''} }';
  }
  if (value is List<Object?>) return '${value.length} item${value.length == 1 ? '' : 's'}';
  if (value is String) return value;
  return '$value';
}

/// Labelled facts the Preview shows for a kind, until the 3D stage renders it.
List<(String, String)> previewFacts(DocumentKind kind, Map<String, Object?> json) {
  String text(Object? value) => value == null ? '-' : summarize(value);
  int count(Object? value) => value is Map<String, Object?> ? value.length : (value is List<Object?> ? value.length : 0);
  switch (kind) {
    case DocumentKind.npc:
      final Object? position = json['position'];
      final Map<String, Object?> at = position is Map<String, Object?> ? position : const <String, Object?>{};
      return <(String, String)>[
        ('Name', text(json['name'])),
        ('Type', text(json['type'])),
        ('Spawned', text(json['spawned'])),
        ('Position', '${text(at['world'])} ${text(at['x'])}, ${text(at['y'])}, ${text(at['z'])}'),
        ('Behaviors', json['behaviors'] is List<Object?> ? (json['behaviors']! as List<Object?>).join(', ') : '-'),
        ('Modules', json['modules'] is Map<String, Object?> ? (json['modules']! as Map<String, Object?>).keys.join(', ') : '-'),
      ];
    case DocumentKind.behavior:
      return <(String, String)>[
        ('Entry', text(json['entry'])),
        ('Routines', '${count(json['routines'])}'),
        ('Triggers', '${count(json['triggers'])}'),
        ('Variables', '${count(json['variables'])}'),
      ];
    case DocumentKind.shop:
      final List<Object?> pages = json['pages'] is List<Object?> ? json['pages']! as List<Object?> : const <Object?>[];
      int entries = 0;
      for (final Object? page in pages) {
        if (page is! Map<String, Object?>) continue;
        for (final String mode in const <String>['buy', 'sell', 'trade']) {
          entries += count(page[mode]);
        }
      }
      return <(String, String)>[
        ('Title', text(json['title'])),
        ('Modes', json['modes'] is List<Object?> ? (json['modes']! as List<Object?>).join(', ') : 'buy, sell, trade'),
        ('Pages', '${pages.length}'),
        ('Entries', '$entries'),
      ];
    case DocumentKind.inventory:
      return <(String, String)>[
        ('Title', text(json['title'])),
        ('Resolution', text(json['resolution'] ?? '9x3')),
        ('Keys', '${count(json['keys'])}'),
        ('Slots', '${count(json['slots'])}'),
      ];
    case DocumentKind.rig:
      return <(String, String)>[
        ('Body', text(json['body'] ?? 'hidden')),
        ('Bones', '${count(json['bones'])}'),
        ('Animations', json['animations'] is Map<String, Object?> ? (json['animations']! as Map<String, Object?>).keys.join(', ') : '-'),
      ];
    case DocumentKind.animation:
      return <(String, String)>[
        ('Rig', text(json['rig'])),
        ('Duration', '${text(json['durationTicks'])} ticks'),
        ('Loop', text(json['loop'] ?? 'once')),
        ('Tracks', '${count(json['tracks'])}'),
      ];
    default:
      return <(String, String)>[for (final MapEntry<String, Object?> field in json.entries.take(8)) (field.key, text(field.value))];
  }
}
