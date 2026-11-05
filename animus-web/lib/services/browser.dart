/// Browser services: theme preference, hash routing and file picking.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart' show Brightness;
import 'package:web/web.dart' as web;

import '../logic/data_folder.dart';

const String themeKey = 'animus.theme';

Brightness loadBrightness() {
  try {
    return web.window.localStorage.getItem(themeKey) == 'light' ? Brightness.light : Brightness.dark;
  } on Object {
    return Brightness.dark;
  }
}

void saveBrightness(Brightness brightness) {
  final bool light = brightness == Brightness.light;
  try {
    web.window.localStorage.setItem(themeKey, light ? 'light' : 'dark');
  } on Object {
    // Private mode: the choice lasts for this page only.
  }
  final web.Element? color = web.document.getElementById('animus-theme-color');
  color?.setAttribute('content', light ? '#f6f5f0' : '#111310');
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
    if (!relative.endsWith('.json') && !(relative.endsWith('.png') && relative.contains('pack/assets/'))) {
      contents[relative] = Uint8List(0);
      continue;
    }
    contents[relative] = (await file.arrayBuffer().toDart).toDart.asUint8List();
  }
  final String first = files.first.webkitRelativePath;
  if (first.contains('/')) name = first.substring(0, first.indexOf('/'));
  return PickedFiles(name, contents);
}

Future<List<web.File>?> _pick({required String? accept, required bool directory}) {
  final Completer<List<web.File>?> completer = Completer<List<web.File>?>();
  final web.HTMLInputElement input = web.document.createElement('input') as web.HTMLInputElement;
  input.type = 'file';
  if (accept != null) input.accept = accept;
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
