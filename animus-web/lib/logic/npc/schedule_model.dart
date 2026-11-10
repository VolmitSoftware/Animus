/// The NPC schedule module `modules["animus:schedule"]`: places the NPC goes
/// at set world times. The inspector edits it through the NPC schema; this
/// gives a new schedule its first location.
library;

import '../gui/gui_json.dart';

const String scheduleModule = 'animus:schedule';

/// World time of the first location: early morning, as `/time set day` gives.
const int scheduleStartTime = 1000;

/// A schedule with one location at the block the NPC stands on, so the
/// module is valid as soon as it is added.
JsonMap scheduleStarter((double, double, double)? origin) => <String, Object?>{
      'locations': <Object?>[
        <String, Object?>{
          'time': scheduleStartTime,
          'x': origin?.$1.floor() ?? 0,
          'y': origin?.$2.floor() ?? 64,
          'z': origin?.$3.floor() ?? 0,
        },
      ],
    };
