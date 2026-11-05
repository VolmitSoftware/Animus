/// Text animations (`gui/text-animations/<namespace>/<path>.json`) and the
/// `|animation.<id>|` tokens that show them, chosen exactly as core's
/// `TextAnimation.frame` does (schema/fixtures/gui/text-animation-frames.json
/// pins both sides).
///
/// The random mode is Java 64-bit arithmetic. Dart on the web has no 64-bit
/// integers, so the mix runs on 16/32-bit limbs kept below 2^53, the same way
/// Gloss Web's playback does.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'gui_expression.dart';
import 'gui_json.dart';

final RegExp _token = RegExp(r'\|animation\.([a-z0-9_:/-]{1,256})\|');

/// The animation id a `|animation.<token>|` names from the document [from]:
/// an id without a namespace is in [from]'s namespace (core's GuiIds.relative).
String resolveAnimationId(String from, String token) {
  if (token.contains(':')) return token;
  final int colon = from.indexOf(':');
  return colon < 0 ? token : '${from.substring(0, colon + 1)}$token';
}

/// Every animation id [text] shows, resolved from [from], in order.
List<String> animationReferences(String text, String from) => <String>[
      for (final RegExpMatch match in _token.allMatches(text)) resolveAnimationId(from, match.group(1)!),
    ];

/// [text] with each `|animation.<id>|` replaced by the frame that animation
/// shows at [nowMs]. A token naming no animation in [animations] stays as
/// written, so the preview shows the author what is missing.
String renderAnimationTokens(String text, String from, Map<String, JsonMap> animations, int nowMs) {
  if (!text.contains('|animation.')) return text;
  return text.replaceAllMapped(_token, (Match match) {
    final String id = resolveAnimationId(from, match.group(1)!);
    final JsonMap? animation = animations[id];
    return animation == null ? match.group(0)! : textAnimationFrame(id, animation, nowMs);
  });
}

/// How the animations [ids] are written from the document [from]: just the
/// path for one in the same namespace, otherwise the whole id.
Set<String> animationCompletionIds(String from, Iterable<String> ids) {
  final int colon = from.indexOf(':');
  final String namespace = colon < 0 ? '' : from.substring(0, colon + 1);
  return <String>{
    for (final String id in ids) namespace.isNotEmpty && id.startsWith(namespace) ? id.substring(namespace.length) : id,
  };
}

/// The workspace's text animations by id, for previews.
Map<String, JsonMap> workspaceAnimations(Iterable<AnimusDocument> documents) => <String, JsonMap>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.textAnimation && document.json is Map<String, Object?>) document.id: document.json! as Map<String, Object?>,
    };

/// How often a preview of [source] needs a new frame: the shortest frame
/// interval among the animations it shows, at least 50 ms; null when it
/// shows none that change.
int? animationTickMs(String source, String from, Map<String, JsonMap> animations) {
  int? tick;
  for (final String id in animationReferences(source, from)) {
    final JsonMap? animation = animations[id];
    if (animation == null || asList(animation['frames']).length < 2) continue;
    final Object? raw = animation['frameIntervalMs'];
    final int interval = raw is num && raw >= 1 ? raw.toInt() : 1000;
    tick = tick == null || interval < tick ? interval : tick;
  }
  return tick == null ? null : (tick < 50 ? 50 : tick);
}

/// The frame text [document] shows at [nowMs]; empty when its `show` is false.
String textAnimationFrame(String id, JsonMap document, int nowMs) {
  if (document.containsKey('show') && !sampleCondition(document['show'])) return '';
  final List<Object?> frames = asList(document['frames']);
  if (frames.isEmpty) return '';
  final Object? frame = frames[textAnimationFrameIndex(id, document, nowMs)];
  return frame is String ? frame : '';
}

/// The frame index at [nowMs]: `tick = floorDiv(nowMs, frameIntervalMs)`,
/// then ascend, descend, ascend_descend or random.
int textAnimationFrameIndex(String id, JsonMap document, int nowMs) {
  final int count = asList(document['frames']).length;
  if (count <= 1) return 0;
  final Object? rawInterval = document['frameIntervalMs'];
  final int interval = rawInterval is num && rawInterval >= 1 ? rawInterval.toInt() : 1000;
  final int tick = (nowMs / interval).floor();
  return switch (asString(document['mode']) ?? 'ascend') {
    'descend' => count - 1 - _floorMod(tick, count),
    'ascend_descend' => _pingPong(tick, count),
    'random' => _scrambled(tick, id, count),
    _ => _floorMod(tick, count),
  };
}

int _floorMod(int value, int modulus) {
  final int rem = value % modulus;
  return rem < 0 ? rem + modulus : rem;
}

int _pingPong(int tick, int count) {
  final int cycle = _floorMod(tick, count * 2);
  return cycle < count ? cycle : count * 2 - 1 - cycle;
}

typedef _U64 = ({int hi, int lo});

const int _pow32 = 0x100000000;
const _U64 _mixC1 = (hi: 0xFF51AFD7, lo: 0xED558CCD);
const _U64 _mixC2 = (hi: 0xC4CEB9FE, lo: 0x1A85EC53);

/// `tick ^ ((long) id.hashCode() << 32)`, then the fmix64 steps and floorMod.
int _scrambled(int tick, String id, int count) {
  _U64 mixed = (hi: _xor32((tick ~/ _pow32) % _pow32, _javaStringHash(id)), lo: tick % _pow32);
  mixed = _mul(_xor(mixed, _shr33(mixed)), _mixC1);
  mixed = _mul(_xor(mixed, _shr33(mixed)), _mixC2);
  mixed = _xor(mixed, _shr33(mixed));
  return _floorMod64(mixed, count);
}

/// `String.hashCode` as its unsigned 32-bit pattern.
int _javaStringHash(String value) {
  int hash = 0;
  for (int i = 0; i < value.length; i++) {
    hash = (hash * 31 + value.codeUnitAt(i)) % _pow32;
  }
  return hash;
}

_U64 _xor(_U64 a, _U64 b) => (hi: _xor32(a.hi, b.hi), lo: _xor32(a.lo, b.lo));

int _xor32(int a, int b) => ((a ~/ 0x10000) ^ (b ~/ 0x10000)) * 0x10000 + ((a % 0x10000) ^ (b % 0x10000));

_U64 _shr33(_U64 value) => (hi: 0, lo: value.hi ~/ 2);

_U64 _mul(_U64 a, _U64 b) {
  final List<int> x = <int>[a.lo % 0x10000, a.lo ~/ 0x10000, a.hi % 0x10000, a.hi ~/ 0x10000];
  final List<int> y = <int>[b.lo % 0x10000, b.lo ~/ 0x10000, b.hi % 0x10000, b.hi ~/ 0x10000];
  final List<int> out = <int>[0, 0, 0, 0];
  for (int i = 0; i < 4; i++) {
    int carry = 0;
    for (int j = 0; i + j < 4; j++) {
      final int sum = out[i + j] + x[i] * y[j] + carry;
      out[i + j] = sum % 0x10000;
      carry = sum ~/ 0x10000;
    }
  }
  return (hi: out[3] * 0x10000 + out[2], lo: out[1] * 0x10000 + out[0]);
}

/// `Math.floorMod(signed64, count)`.
int _floorMod64(_U64 value, int count) {
  int rem = (value.hi ~/ 0x10000) % count;
  rem = (rem * 0x10000 + value.hi % 0x10000) % count;
  rem = (rem * 0x10000 + value.lo ~/ 0x10000) % count;
  rem = (rem * 0x10000 + value.lo % 0x10000) % count;
  if (value.hi >= 0x80000000) {
    int pow64 = 1;
    for (int i = 0; i < 4; i++) {
      pow64 = (pow64 * 0x10000) % count;
    }
    rem = (rem - pow64) % count;
    if (rem < 0) rem += count;
  }
  return rem;
}
