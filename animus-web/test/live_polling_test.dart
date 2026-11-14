import 'package:animus_editor/logic/terrain/live_polling.dart';
import 'package:test/test.dart';

void main() {
  test('asks every 1.5 s while the stage is seen and the position just moved', () {
    expect(livePollDelay(seen: true, sinceChange: const Duration(seconds: 5)), const Duration(milliseconds: 1500));
  });

  test('backs off the longer the NPC stands still', () {
    expect(livePollDelay(seen: true, sinceChange: const Duration(seconds: 30)), const Duration(seconds: 5));
    expect(livePollDelay(seen: true, sinceChange: const Duration(minutes: 2)), const Duration(seconds: 15));
    expect(livePollDelay(seen: true, sinceChange: const Duration(minutes: 10)), const Duration(seconds: 30));
  });

  test('an NPC standing still for five minutes is asked about 30 times, not 85', () {
    Duration since = Duration.zero;
    int asked = 0;
    while (since < const Duration(minutes: 5)) {
      since += livePollDelay(seen: true, sinceChange: since)!;
      asked++;
    }
    expect(asked, lessThanOrEqualTo(35));
  });

  test('does not ask while the stage is out of view or the tab has no focus', () {
    expect(livePollDelay(seen: false, sinceChange: Duration.zero), isNull);
  });
}
