/// Browser services: theme preference, hash routing, file picking, the keys,
/// focus and unload hooks the shell uses, and the cross-tab channel and
/// locks behind the workspace store.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart' show Brightness;
import 'package:web/web.dart' as web;

import '../config/brand.dart';
import '../logic/data_folder.dart';
import '../state/workspace_coordination.dart';

const String themeKey = 'animus.theme';

/// The stored theme, or the system's when nothing is stored (bootstrap.js
/// makes the same choice before paint).
Brightness loadBrightness() {
  try {
    final String? stored = web.window.localStorage.getItem(themeKey);
    if (stored == 'light') return Brightness.light;
    if (stored == 'dark') return Brightness.dark;
  } on Object {
    // Private mode: the default.
  }
  // Midnight (dark) until the light theme is picked.
  return Brightness.dark;
}

void saveBrightness(Brightness brightness) {
  final bool light = brightness == Brightness.light;
  try {
    web.window.localStorage.setItem(themeKey, light ? 'light' : 'dark');
  } on Object {
    // Private mode: the choice lasts for this page only.
  }
  // bootstrap.js stamps this class before paint, and the theme tokens key off it, so the toggle moves it too.
  final web.DOMTokenList classes = web.document.documentElement!.classList;
  classes.remove(light ? 'dark' : 'light');
  classes.add(light ? 'light' : 'dark');
  final web.Element? color = web.document.getElementById('animus-theme-color');
  color?.setAttribute('content', light ? brandLight.themeColor : brandDark.themeColor);
}

String currentHash() => web.window.location.hash;

void navigate(String hash) {
  if (web.window.location.hash != hash) web.window.location.hash = hash;
}

/// Moves to [hash] without leaving the current address in history (a
/// pairing link's secret, for one).
void replaceHash(String hash) {
  if (web.window.location.hash != hash) web.window.location.replace(hash);
}

/// Swaps the current history entry's address for [hash]. No hashchange
/// fires, so the open page stays as it is.
void replaceHistoryHash(String hash) {
  if (web.window.location.hash != hash) web.window.history.replaceState(null, '', hash);
}

/// Calls [listener] on every hash change; returns a function that stops it.
void Function() onHashChange(void Function(String hash) listener) {
  final JSFunction callback = ((web.Event _) => listener(web.window.location.hash)).toJS;
  web.window.addEventListener('hashchange', callback);
  return () => web.window.removeEventListener('hashchange', callback);
}

class PickedFiles {
  const PickedFiles(this.name, this.files);

  final String name;

  /// Path (relative to the picked folder, or inside the zip) to bytes.
  final Map<String, Uint8List> files;
}

/// Lets the user pick a zip. Null when the dialog is cancelled.
Future<PickedFiles?> pickZip() async {
  final List<web.File>? files = await _pick(accept: '.zip,application/zip', directory: false);
  if (files == null || files.isEmpty) return null;
  final web.File file = files.first;
  final Uint8List bytes = (await file.arrayBuffer().toDart).toDart.asUint8List();
  final String name = file.name.endsWith('.zip') ? file.name.substring(0, file.name.length - 4) : file.name;
  return PickedFiles(name, filesFromZip(bytes));
}

/// Lets the user pick a data folder. Null when the dialog is cancelled.
Future<PickedFiles?> pickDirectory() async {
  final List<web.File>? files = await _pick(accept: null, directory: true);
  if (files == null || files.isEmpty) return null;
  final Map<String, Uint8List> contents = <String, Uint8List>{};
  String name = 'Animus';
  for (final web.File file in files) {
    final String relative = file.webkitRelativePath.isEmpty ? file.name : file.webkitRelativePath;
    if (!relative.endsWith('.json') && !(relative.endsWith('.png') && (relative.contains('pack/assets/') || relative.contains('editor/skins/')))) {
      contents[relative] = Uint8List(0);
      continue;
    }
    contents[relative] = (await file.arrayBuffer().toDart).toDart.asUint8List();
  }
  final String first = files.first.webkitRelativePath;
  if (first.contains('/')) name = first.substring(0, first.indexOf('/'));
  return PickedFiles(name, contents);
}

Future<List<web.File>?> _pick({required String? accept, required bool directory, bool multiple = false}) {
  final Completer<List<web.File>?> completer = Completer<List<web.File>?>();
  final web.HTMLInputElement input = web.document.createElement('input') as web.HTMLInputElement;
  input.type = 'file';
  if (accept != null) input.accept = accept;
  if (multiple) input.multiple = true;
  if (directory) {
    input.setAttribute('webkitdirectory', '');
    input.multiple = true;
  }
  input.style.display = 'none';
  input.addEventListener('change', ((web.Event _) {
    final web.FileList? list = input.files;
    final List<web.File> files = <web.File>[];
    if (list != null) {
      for (int index = 0; index < list.length; index++) {
        final web.File? file = list.item(index);
        if (file != null) files.add(file);
      }
    }
    input.remove();
    if (!completer.isCompleted) completer.complete(files);
  }).toJS);
  input.addEventListener('cancel', ((web.Event _) {
    input.remove();
    if (!completer.isCompleted) completer.complete(null);
  }).toJS);
  web.document.body?.append(input);
  input.click();
  return completer.future;
}

/// Lets the user pick any number of files. Null when the dialog is cancelled.
Future<List<(String, Uint8List)>?> pickFiles(String accept) async {
  final List<web.File>? files = await _pick(accept: accept, directory: false, multiple: true);
  if (files == null || files.isEmpty) return null;
  return <(String, Uint8List)>[
    for (final web.File file in files) (file.name, (await file.arrayBuffer().toDart).toDart.asUint8List()),
  ];
}

/// Lets the user pick one file. Null when the dialog is cancelled.
Future<(String, Uint8List)?> pickFile(String accept) async {
  final List<web.File>? files = await _pick(accept: accept, directory: false);
  if (files == null || files.isEmpty) return null;
  final web.File file = files.first;
  return (file.name, (await file.arrayBuffer().toDart).toDart.asUint8List());
}

/// Saves [bytes] as a download named [name].
void downloadBytes(String name, Uint8List bytes, {String type = 'application/zip'}) {
  final web.Blob blob = web.Blob(<JSAny>[bytes.toJS].toJS, web.BlobPropertyBag(type: type));
  final String url = web.URL.createObjectURL(blob);
  final web.HTMLAnchorElement anchor = web.document.createElement('a') as web.HTMLAnchorElement
    ..href = url
    ..download = name
    ..style.display = 'none';
  web.document.body?.append(anchor);
  anchor.click();
  anchor.remove();
  Timer(const Duration(seconds: 30), () => web.URL.revokeObjectURL(url));
}

/// Calls [handler] for every key pressed in the page; returns a function
/// that stops it.
void Function() onKeyDown(void Function(web.KeyboardEvent event) handler) {
  final JSFunction callback = ((web.KeyboardEvent event) => handler(event)).toJS;
  web.window.addEventListener('keydown', callback);
  return () => web.window.removeEventListener('keydown', callback);
}

/// Lets the mouse wheel scroll the page over a focused number box. Chrome
/// otherwise steps the value on every wheel tick and keeps the page still,
/// so scrolling past a field changed it (Yaw counted down from 90 to 30).
/// The box gives up focus first; the wheel then scrolls as anywhere else.
void releaseNumberBoxesOnWheel() {
  web.document.addEventListener(
    'wheel',
    ((web.Event event) {
      final web.EventTarget? target = event.target;
      if (target == null || !target.isA<web.HTMLInputElement>()) return;
      final web.HTMLInputElement input = target as web.HTMLInputElement;
      if (input.type == 'number' && identical(web.document.activeElement, input)) input.blur();
    }).toJS,
    web.AddEventListenerOptions(capture: true, passive: true),
  );
}

/// True when [target] takes typing (a text box, text area, select or
/// editable element), where page shortcuts leave keys alone.
bool isTypingTarget(web.EventTarget? target) {
  if (target == null || !target.isA<web.HTMLElement>()) return false;
  final web.HTMLElement element = target as web.HTMLElement;
  if (element.isContentEditable) return true;
  final String tag = element.tagName.toUpperCase();
  if (tag == 'TEXTAREA' || tag == 'SELECT') return true;
  if (tag != 'INPUT') return false;
  const Set<String> nonText = <String>{'checkbox', 'radio', 'button', 'submit', 'reset', 'range', 'color', 'file'};
  return !nonText.contains((element as web.HTMLInputElement).type);
}

/// Focuses the element with [id] once it is on the page and is not
/// [previous] (the element that stood there before a re-render), giving up
/// after about a second and a half.
void focusWhenShown(String id, {web.Element? previous}) {
  int tries = 0;
  void attempt() {
    final web.Element? element = web.document.getElementById(id);
    if (element != null && !identical(element, previous)) {
      (element as web.HTMLElement).focus();
      return;
    }
    if (++tries > 90) {
      (element as web.HTMLElement?)?.focus();
      return;
    }
    web.window.setTimeout(attempt.toJS, 16.toJS);
  }

  web.window.setTimeout(attempt.toJS, 0.toJS);
}

/// The id every screen gives its main heading, which takes focus after a
/// create, rename, duplicate or delete.
const String mainHeadingId = 'animus-main-heading';

/// Moves focus to the main heading of the screen that is about to show.
void focusMainHeading() => focusWhenShown(mainHeadingId, previous: web.document.getElementById(mainHeadingId));

/// Focuses the element with [id] now, when it exists.
void focusById(String id) => (web.document.getElementById(id) as web.HTMLElement?)?.focus();

/// Whether [query] matches now, and a callback each time that changes;
/// returns the current answer and a function that stops watching.
(bool, void Function()) watchMedia(String query, void Function(bool matches) onChange) {
  try {
    final web.MediaQueryList list = web.window.matchMedia(query);
    final JSFunction callback = ((web.Event _) => onChange(list.matches)).toJS;
    list.addEventListener('change', callback);
    return (list.matches, () => list.removeEventListener('change', callback));
  } on Object {
    return (false, () {});
  }
}

/// Stops the page behind a drawer or dialog from scrolling.
void setScrollLocked(bool locked) {
  final web.DOMTokenList classes = web.document.documentElement!.classList;
  if (locked) {
    classes.add('animus-scroll-locked');
  } else {
    classes.remove('animus-scroll-locked');
  }
}

final List<bool Function()> _unloadGuards = <bool Function()>[];
bool _unloadInstalled = false;

/// While [blocked] returns true, closing or reloading the tab asks first.
/// Returns a function that removes the guard.
void Function() guardUnload(bool Function() blocked) {
  if (!_unloadInstalled) {
    _unloadInstalled = true;
    web.window.addEventListener('beforeunload', ((web.Event event) {
      if (!_unloadGuards.any((bool Function() guard) => guard())) return;
      event.preventDefault();
      // Older browsers ask only when returnValue is set.
      (event as JSObject)['returnValue'] = ''.toJS;
    }).toJS);
  }
  _unloadGuards.add(blocked);
  return () => _unloadGuards.remove(blocked);
}

bool _persistAsked = false;

/// Asks the browser once to keep this site's storage under pressure.
void requestPersistentStorage() {
  if (_persistAsked) return;
  _persistAsked = true;
  try {
    web.window.navigator.storage.persist().toDart.then((JSBoolean _) {}, onError: (Object _) {});
  } on Object {
    return;
  }
}

/// Save and delete notices between tabs over `BroadcastChannel`.
class BrowserWorkspaceEvents implements WorkspaceEvents {
  BrowserWorkspaceEvents._(this._channel);

  static const String channelName = 'animus.workspace';

  final web.BroadcastChannel _channel;

  /// The channel, or no notices when the browser has no BroadcastChannel.
  static WorkspaceEvents open() {
    try {
      return BrowserWorkspaceEvents._(web.BroadcastChannel(channelName));
    } on Object {
      return const SilentWorkspaceEvents();
    }
  }

  @override
  void announce(WorkspaceEvent event) {
    try {
      _channel.postMessage(event.toJson().jsify());
    } on Object {
      return;
    }
  }

  @override
  void listen(void Function(WorkspaceEvent event) listener) {
    _channel.addEventListener('message', ((web.MessageEvent message) {
      final WorkspaceEvent? event = WorkspaceEvent.fromJson(message.data.dartify());
      if (event != null) listener(event);
    }).toJS);
  }
}

/// Named exclusive locks over Web Locks (`navigator.locks`), held until
/// [release] or until the tab closes.
///
/// ```dart
/// final WorkspaceLocks locks = BrowserWorkspaceLocks.open();
/// if (await locks.tryHold('animus.workspace.ws-1')) { /* this tab owns it */ }
/// await locks.hold('animus.workspace.ws-1'); // waits for the owner to let go
/// locks.release('animus.workspace.ws-1');
/// ```
class BrowserWorkspaceLocks implements WorkspaceLocks {
  BrowserWorkspaceLocks._(this._manager);

  final web.LockManager _manager;
  final Map<String, Completer<void>> _held = <String, Completer<void>>{};
  final Map<String, web.AbortController> _waiting = <String, web.AbortController>{};

  /// The browser's locks, or locks every tab owns when Web Locks is missing.
  static WorkspaceLocks open() {
    try {
      final JSAny? manager = (web.window.navigator as JSObject)['locks'];
      if (manager == null || manager.isUndefinedOrNull) return const UnsharedWorkspaceLocks();
      return BrowserWorkspaceLocks._(manager as web.LockManager);
    } on Object {
      return const UnsharedWorkspaceLocks();
    }
  }

  JSFunction _granted(String name, void Function(bool granted) done) => ((JSAny? lock) {
        if (lock == null || lock.isUndefinedOrNull) {
          done(false);
          return null;
        }
        final Completer<void> release = Completer<void>();
        _held[name] = release;
        done(true);
        return release.future.toJS;
      }).toJS;

  @override
  Future<bool> tryHold(String name) {
    if (_held.containsKey(name)) return Future<bool>.value(true);
    final Completer<bool> result = Completer<bool>();
    void done(bool granted) {
      if (!result.isCompleted) result.complete(granted);
    }

    try {
      _manager.request(name, web.LockOptions(ifAvailable: true), _granted(name, done)).toDart.then((JSAny? _) {}, onError: (Object _) => done(false));
    } on Object {
      done(true);
    }
    return result.future;
  }

  @override
  Future<void> hold(String name) {
    if (_held.containsKey(name)) return Future<void>.value();
    final Completer<void> result = Completer<void>();
    final web.AbortController abort = web.AbortController();
    _waiting[name] = abort;
    void done(bool granted) {
      _waiting.remove(name);
      if (granted && !result.isCompleted) result.complete();
    }

    _manager.request(name, web.LockOptions(signal: abort.signal), _granted(name, done)).toDart.then((JSAny? _) {}, onError: (Object _) {});
    return result.future;
  }

  @override
  void release(String name) {
    _waiting.remove(name)?.abort();
    _held.remove(name)?.complete();
  }
}
