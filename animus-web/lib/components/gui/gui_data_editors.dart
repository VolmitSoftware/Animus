/// The Visual view for the GUI data kinds, text animations and the presets
/// document, for the document pane to show.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_presets.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'gui_fields.dart';
import 'presets_editor.dart';
import 'text_animation_editor.dart';

/// The editor for [document] when it is a text animation or the presets
/// document, otherwise null.
Widget? guiDataEditor(AnimusDocument document, JsonEdit onJson, List<AnimusDocument> workspace) => switch (document.kind) {
      DocumentKind.textAnimation => TextAnimationEditor(document: document, onJson: onJson, presets: workspacePresets(workspace)),
      DocumentKind.presets => PresetsEditor(document: document, onJson: onJson),
      _ => null,
    };
