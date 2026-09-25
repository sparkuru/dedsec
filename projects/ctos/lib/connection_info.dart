import 'dart:convert';
import 'dart:typed_data';

final class AppIdentity {
  const AppIdentity({
    required this.label,
    required this.packageName,
    required this.applicationName,
    required this.processName,
    this.alias = '',
    this.userId = 0,
    this.iconBytes,
  });

  final String label;
  final String packageName;
  final String applicationName;
  final String processName;
  final String alias;
  final int userId;
  final Uint8List? iconBytes;

  String get searchText =>
      '$label $alias $packageName $applicationName $processName'.toLowerCase();

  bool matchesExactName(String query) => [
    label,
    alias,
    packageName,
    applicationName,
    processName,
  ].any((name) => name.isNotEmpty && name.toLowerCase() == query);

  String get displayName => alias.isEmpty ? label : alias;
  String get detail => userId == 0
      ? packageName
      : '${alias.isEmpty || alias == label ? '' : '$label · '}用户 $userId · $packageName';

  factory AppIdentity.fromJson(Map<String, dynamic> data) {
    Uint8List? iconBytes;
    final icon = data['icon'];
    if (icon is String) {
      try {
        iconBytes = base64Decode(icon);
      } on FormatException {
        // Keep searchable identity even if an icon cannot be decoded.
      }
    }
    return AppIdentity(
      label: data['label'] as String? ?? '',
      packageName: data['packageName'] as String? ?? '',
      applicationName: data['applicationName'] as String? ?? '',
      processName: data['processName'] as String? ?? '',
      alias: data['alias'] as String? ?? '',
      userId: data['userId'] as int? ?? 0,
      iconBytes: iconBytes,
    );
  }
}

final class ConnectionEntry {
  const ConnectionEntry({
    required this.protocol,
    required this.state,
    required this.recvQueue,
    required this.sendQueue,
    required this.local,
    required this.peer,
    required this.raw,
    this.uid,
    this.owner,
    this.apps = const [],
  });

  final String protocol;
  final String state;
  final int recvQueue;
  final int sendQueue;
  final String local;
  final String peer;
  final String raw;
  final int? uid;
  final String? owner;
  final List<AppIdentity> apps;

  String get searchText =>
      '$protocol $state $local $peer ${uid ?? ''} ${owner ?? ''} $raw '
              '${apps.map((app) => app.searchText).join(' ')}'
          .toLowerCase();

  ConnectionEntry withOwner(String value) => ConnectionEntry(
    protocol: protocol,
    state: state,
    recvQueue: recvQueue,
    sendQueue: sendQueue,
    local: local,
    peer: peer,
    raw: raw,
    uid: uid,
    owner: value,
    apps: apps,
  );
}

final class ConnectionReport {
  const ConnectionReport({required this.entries, required this.diagnostics});

  final List<ConnectionEntry> entries;
  final List<String> diagnostics;

  List<ConnectionEntry> search(String query) {
    final needle = query.trim().toLowerCase();
    if (needle.isEmpty) return entries;
    final exactApps = entries
        .where((entry) => entry.apps.any((app) => app.matchesExactName(needle)))
        .toList();
    if (exactApps.isNotEmpty) return exactApps;
    return entries.where((entry) => entry.searchText.contains(needle)).toList();
  }

  static final RegExp _columns = RegExp(r'\s+');
  static final RegExp _uid = RegExp(r'\buid:(\d+)\b');
  static const _protocols = {'tcp', 'udp', 'raw', 'sctp', 'dccp'};

  factory ConnectionReport.parse(
    String output, {
    Map<String, dynamic> apps = const {},
  }) {
    final entries = <ConnectionEntry>[];
    final diagnostics = <String>[];
    final appByUid = <int, List<AppIdentity>>{};
    for (final item in apps.entries) {
      final uid = int.tryParse(item.key);
      if (uid == null || item.value is! List) continue;
      appByUid[uid] = List.unmodifiable(
        (item.value as List).whereType<Map>().map(
          (value) => AppIdentity.fromJson(Map<String, dynamic>.from(value)),
        ),
      );
    }
    for (final sourceLine in output.split('\n')) {
      final line = sourceLine.trim();
      if (line.isEmpty || line.startsWith('Netid ')) continue;
      if (line.startsWith('↳')) {
        if (entries.isEmpty) {
          diagnostics.add(line);
        } else {
          entries[entries.length - 1] = entries.last.withOwner(
            line.substring(1).trim(),
          );
        }
        continue;
      }
      final columns = line.split(_columns);
      if (columns.length < 6 || !_protocols.contains(columns[0])) {
        diagnostics.add(line);
        continue;
      }
      final recvQueue = int.tryParse(columns[2]);
      final sendQueue = int.tryParse(columns[3]);
      if (recvQueue == null || sendQueue == null) {
        diagnostics.add(line);
        continue;
      }
      final uid = int.tryParse(_uid.firstMatch(line)?.group(1) ?? '');
      entries.add(
        ConnectionEntry(
          protocol: columns[0],
          state: columns[1],
          recvQueue: recvQueue,
          sendQueue: sendQueue,
          local: columns[4],
          peer: columns[5],
          uid: uid,
          apps: appByUid[uid] ?? const [],
          raw: line,
        ),
      );
    }
    return ConnectionReport(
      entries: List.unmodifiable(entries),
      diagnostics: List.unmodifiable(diagnostics),
    );
  }
}
