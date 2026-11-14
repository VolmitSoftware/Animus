/// How tabs of one browser keep a workspace in step: save and delete
/// notices between tabs, and a lock that says which tab owns a workspace.
/// The browser versions live in `services/browser.dart`; the ones here run
/// in tests and stand in where the browser has neither API.
library;

import 'dart:async';

/// What one tab tells the others: it saved or deleted a workspace, or it
/// asks the tab that owns a workspace to hand it over.
enum WorkspaceEventKind { saved, deleted, takeOver }

class WorkspaceEvent {
  const WorkspaceEvent(this.kind, this.workspaceId, {this.generation = 0, this.keys, this.resources = false});

  final WorkspaceEventKind kind;
  final String workspaceId;

  /// The generation now stored, for [WorkspaceEventKind.saved].
  final int generation;

  /// The document keys the save wrote, when it says; a tab one generation
  /// behind reads only those. Null: read the whole workspace.
  final List<String>? keys;

  /// The save also changed resources.
  final bool resources;

  Map<String, Object?> toJson() => <String, Object?>{
        'kind': kind.name,
        'id': workspaceId,
        'generation': generation,
        if (keys != null) 'keys': keys,
        if (resources) 'resources': true,
      };

  static WorkspaceEvent? fromJson(Object? json) {
    if (json is! Map) return null;
    final WorkspaceEventKind? kind = WorkspaceEventKind.values.asNameMap()[json['kind']];
    final Object? id = json['id'];
    final Object? generation = json['generation'];
    final Object? keys = json['keys'];
    if (kind == null || id is! String) return null;
    return WorkspaceEvent(
      kind,
      id,
      generation: generation is num ? generation.toInt() : 0,
      keys: keys is List ? <String>[for (final Object? key in keys) '$key'] : null,
      resources: json['resources'] == true,
    );
  }
}

/// Carries [WorkspaceEvent]s to the other tabs (never back to the sender).
abstract class WorkspaceEvents {
  void announce(WorkspaceEvent event);

  void listen(void Function(WorkspaceEvent event) listener);
}

/// No other tabs: announcements go nowhere.
class SilentWorkspaceEvents implements WorkspaceEvents {
  const SilentWorkspaceEvents();

  @override
  void announce(WorkspaceEvent event) {}

  @override
  void listen(void Function(WorkspaceEvent event) listener) {}
}

/// Tabs in one process, for tests: each [tab] hears every other tab.
class SharedWorkspaceEvents {
  final List<_TabEvents> _tabs = <_TabEvents>[];

  WorkspaceEvents tab() {
    final _TabEvents tab = _TabEvents(this);
    _tabs.add(tab);
    return tab;
  }
}

class _TabEvents implements WorkspaceEvents {
  _TabEvents(this._bus);

  final SharedWorkspaceEvents _bus;
  final List<void Function(WorkspaceEvent event)> _listeners = <void Function(WorkspaceEvent event)>[];

  @override
  void announce(WorkspaceEvent event) {
    for (final _TabEvents other in _bus._tabs) {
      if (identical(other, this)) continue;
      for (final void Function(WorkspaceEvent event) listener in List<void Function(WorkspaceEvent event)>.of(other._listeners)) {
        listener(event);
      }
    }
  }

  @override
  void listen(void Function(WorkspaceEvent event) listener) => _listeners.add(listener);
}

/// Exclusive named locks, one holder at a time across tabs.
abstract class WorkspaceLocks {
  /// Takes [name] if nobody holds it; true when this tab now holds it.
  Future<bool> tryHold(String name);

  /// Completes once this tab holds [name], waiting for the holder to let go.
  Future<void> hold(String name);

  /// Lets go of [name] (or stops waiting for it).
  void release(String name);
}

/// The lock a tab takes for the workspace it has open.
String workspaceLockName(String workspaceId) => 'animus.workspace.$workspaceId';

/// Every tab owns everything: for browsers without Web Locks.
class UnsharedWorkspaceLocks implements WorkspaceLocks {
  const UnsharedWorkspaceLocks();

  @override
  Future<bool> tryHold(String name) async => true;

  @override
  Future<void> hold(String name) async {}

  @override
  void release(String name) {}
}

/// Locks shared by the tabs of one process, for tests.
class SharedWorkspaceLocks {
  final Map<String, Object> _holders = <String, Object>{};
  final Map<String, List<(Object, Completer<void>)>> _waiting = <String, List<(Object, Completer<void>)>>{};

  WorkspaceLocks tab() => _TabLocks(this);

  void _release(Object tab, String name) {
    _waiting[name]?.removeWhere(((Object, Completer<void>) entry) => identical(entry.$1, tab));
    if (!identical(_holders[name], tab)) return;
    _holders.remove(name);
    final List<(Object, Completer<void>)>? queue = _waiting[name];
    if (queue == null || queue.isEmpty) return;
    final (Object next, Completer<void> done) = queue.removeAt(0);
    _holders[name] = next;
    done.complete();
  }
}

class _TabLocks implements WorkspaceLocks {
  _TabLocks(this._shared);

  final SharedWorkspaceLocks _shared;

  @override
  Future<bool> tryHold(String name) async {
    final Object? holder = _shared._holders[name];
    if (holder != null) return identical(holder, this);
    _shared._holders[name] = this;
    return true;
  }

  @override
  Future<void> hold(String name) {
    if (_shared._holders[name] == null) {
      _shared._holders[name] = this;
      return Future<void>.value();
    }
    if (identical(_shared._holders[name], this)) return Future<void>.value();
    final Completer<void> done = Completer<void>();
    _shared._waiting.putIfAbsent(name, () => <(Object, Completer<void>)>[]).add((this, done));
    return done.future;
  }

  @override
  void release(String name) => _shared._release(this, name);
}
