/// Notes under the 3D stage settle like field problems: a note the stage
/// already shows stays while it holds, a note that went away goes at once,
/// and a new note waits for a pause in typing, so `minecraft:dia is not
/// drawn` does not flash up while `diamond_helmet` is still being typed.
library;

/// The notes of [next] to show at once over [shown]: those already shown.
List<String> keptNotes(List<String> shown, List<String> next) => <String>[
      for (final String note in next)
        if (shown.contains(note)) note,
    ];
