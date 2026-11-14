/// The one short line under a 3D stage and the tip beside it: at most two
/// phrases on the line, the sentences that explain them first in the tip,
/// then what else is worth knowing, then how to move the camera. The tip
/// shows two sentences, so the order is the priority.
library;

/// One phrase of a stage's status line and the sentence its tip gives.
final class StageNote {
  const StageNote(this.line, [this.detail]);

  final String line;
  final String? detail;
}

/// Phrases shown on the line at most.
const int stageStatusPhrases = 2;

final class StageStatus {
  const StageStatus(this.notes, {this.unrendered = const <String>[], this.extra = const <String>[]});

  /// The phrases, most important first; empty ones are skipped.
  final List<StageNote> notes;

  /// What the renderer or the scene could not draw, one sentence each;
  /// counted on the line, listed in the tip.
  final List<String> unrendered;

  /// Details with no phrase of their own (the skin worn, where the NPC is).
  final List<String> extra;

  List<StageNote> get _shown {
    final List<StageNote> said = <StageNote>[
      for (final StageNote note in notes)
        if (note.line.isNotEmpty) note,
      if (unrendered.isNotEmpty) StageNote('${unrendered.length} ${unrendered.length == 1 ? 'part' : 'parts'} not drawn', unrendered.join(' ')),
    ];
    return said.take(stageStatusPhrases).toList();
  }

  /// The line under the canvas; "Drag to turn" when there is nothing to say.
  String get line {
    final List<StageNote> shown = _shown;
    return shown.isEmpty ? 'Drag to turn' : shown.map((StageNote note) => note.line).join(' · ');
  }

  /// The tip: the shown phrases' details, [extra], then [controls].
  String tip(String controls) => <String>[
        for (final StageNote note in _shown)
          if (note.detail != null && note.detail!.isNotEmpty) note.detail!,
        ...extra,
        controls,
      ].join(' ');
}
