import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const ThanoxBridgeApp());

class ThanoxBridgeApp extends StatelessWidget {
  const ThanoxBridgeApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Thanox Bridge',
    theme: ThemeData(colorSchemeSeed: Colors.teal),
    darkTheme: ThemeData(
      colorSchemeSeed: Colors.teal,
      brightness: Brightness.dark,
    ),
    home: const AppListPage(),
  );
}

const _channel = MethodChannel('im.majo.thanox_bridge/permissions');

Map<Object?, Object?> _map(Object? value) {
  if (value is! Map) throw const FormatException('Expected a map');
  return Map<Object?, Object?>.from(value);
}

class InstalledApp {
  InstalledApp.fromMap(Map<Object?, Object?> value)
    : label = value['label'] as String,
      packageName = value['packageName'] as String,
      uid = value['uid'] as int,
      userId = value['userId'] as int,
      sources = value['sources'] == null
          ? const []
          : List<String>.from(value['sources'] as List);

  final String label;
  final String packageName;
  final int uid;
  final int userId;
  final List<String> sources;
}

class AppCatalog {
  AppCatalog.fromMap(Map<Object?, Object?> value)
    : apps = (value['apps'] as List)
          .map((item) => InstalledApp.fromMap(_map(item)))
          .toList(),
      notice = value['notice'] as String;

  final List<InstalledApp> apps;
  final String notice;
}

class DeclaredPermission {
  DeclaredPermission.fromMap(Map<Object?, Object?> value)
    : name = value['name'] as String,
      label = value['label'] as String,
      granted = value['granted'] as bool,
      protection = value['protection'] as String;

  final String name;
  final String label;
  final bool granted;
  final String protection;
}

class AppOperation {
  AppOperation.fromMap(Map<Object?, Object?> value)
    : code = value['code'] as int,
      name = value['name'] as String,
      permission = value['permission'] as String?,
      state = value['state'] as String,
      runtime = value['runtime'] as bool,
      allowedStates = List<String>.from(value['allowedStates'] as List);

  final int code;
  final String name;
  final String? permission;
  final String state;
  final bool runtime;
  final List<String> allowedStates;
}

class AppDetails {
  AppDetails.fromMap(Map<Object?, Object?> value)
    : app = InstalledApp.fromMap(value),
      identityToken = value['identityToken'] as String?,
      permissions = (value['permissions'] as List)
          .map((item) => DeclaredPermission.fromMap(_map(item)))
          .toList(),
      operations = (value['ops'] as List)
          .map((item) => AppOperation.fromMap(_map(item)))
          .toList(),
      serviceError = value['serviceError'] as String?,
      coverageNote = value['coverageNote'] as String?;

  final InstalledApp app;
  final String? identityToken;
  final List<DeclaredPermission> permissions;
  final List<AppOperation> operations;
  final String? serviceError;
  final String? coverageNote;
}

String _errorMessage(Object error) =>
    error is PlatformException ? error.message ?? '请求失败，请重试。' : '无法读取应用信息，请重试。';

String _stateLabel(String state) => switch (state) {
  'DEFAULT' => '默认',
  'NOT_SET' => '未设置',
  'DENY' => '拒绝',
  'IGNORE' => '忽略',
  'ASK' => '每次询问',
  'ALLOW_ALWAYS' => '始终允许',
  'ALLOW_FOREGROUND_ONLY' => '仅前台允许',
  'UNKNOWN' => '未知',
  _ => state,
};

String _protectionLabel(String protection) => switch (protection) {
  'dangerous' => '运行时权限',
  'normal' => '普通权限',
  'signature' => '签名权限',
  'other' => '其他权限',
  _ => '类型未知',
};

class AppListPage extends StatefulWidget {
  const AppListPage({super.key});
  @override
  State<AppListPage> createState() => _AppListPageState();
}

class _AppListPageState extends State<AppListPage> {
  Future<AppCatalog>? _apps;
  String _query = '';
  String _searchText = '';
  Future<({InstalledApp? app, String? error})>? _exactLookup;

  Future<AppCatalog> _loadApps() async => AppCatalog.fromMap(
    _map(await _channel.invokeMethod<Object?>('listApps')),
  );

  void _reload() {
    final next = _loadApps();
    // FutureBuilder subscribes on the next frame, so handle an earlier failure too.
    next.ignore();
    setState(() {
      _apps = next;
      _exactLookup = null;
    });
  }

  Future<({InstalledApp? app, String? error})> _findApp(
    String packageName,
  ) async {
    try {
      final value = await _channel.invokeMethod<Object?>('findApp', {
        'packageName': packageName,
      });
      return (
        app: value == null ? null : InstalledApp.fromMap(_map(value)),
        error: null,
      );
    } catch (error) {
      return (app: null, error: _errorMessage(error));
    }
  }

  void _lookupExactPackage() {
    final packageName = _searchText.trim();
    if (packageName.isEmpty) return;
    final next = _findApp(packageName);
    setState(() {
      _exactLookup = next;
    });
  }

  Widget _appTile(InstalledApp app) => ListTile(
    key: ValueKey('app-${app.packageName}-${app.userId}'),
    title: Text(app.label),
    subtitle: Text(
      '${app.packageName}\n用户 ${app.userId} · UID ${app.uid}'
      '${app.sources.isEmpty ? '' : '\n来源：${app.sources.join('、')}'}',
    ),
    trailing: const Icon(Icons.chevron_right),
    onTap: () => Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (context) => AppDetailsPage(app: app)),
    ),
  );

  Widget _exactSearchResult({required String message}) {
    final lookup = _exactLookup;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message),
          const SizedBox(height: 8),
          if (lookup != null)
            FutureBuilder<({InstalledApp? app, String? error})>(
              future: lookup,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const _Loading(message: '正在按包名查找…');
                }
                if (snapshot.hasError) {
                  return Text(_errorMessage(snapshot.error!));
                }
                final result = snapshot.data!;
                if (result.error != null) return Text(result.error!);
                return result.app == null
                    ? const Text('未找到或无权查看该应用。')
                    : _appTile(result.app!);
              },
            ),
          OutlinedButton(
            onPressed: _lookupExactPackage,
            child: const Text('按完整包名查找'),
          ),
        ],
      ),
    );
  }

  Widget _initialListPrompt() => ListView(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
        child: Column(
          children: [
            const Text('读取应用列表，以便选择应用查看其权限。设备可能询问是否允许读取应用列表。'),
            const SizedBox(height: 16),
            FilledButton(onPressed: _reload, child: const Text('读取应用列表')),
          ],
        ),
      ),
      if (_searchText.trim().isNotEmpty)
        _exactSearchResult(message: '可按完整包名单独查询。'),
    ],
  );

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Thanox Bridge'),
      actions: [
        IconButton(
          tooltip: '刷新应用列表',
          onPressed: _reload,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              key: const Key('app-search'),
              decoration: const InputDecoration(
                labelText: '搜索应用名称或包名',
                prefixIcon: Icon(Icons.search),
                border: OutlineInputBorder(),
              ),
              onChanged: (value) => setState(() {
                _searchText = value;
                _query = value.toLowerCase();
                _exactLookup = null;
              }),
              onSubmitted: (_) => _lookupExactPackage(),
            ),
          ),
          Expanded(
            child: _apps == null
                ? _initialListPrompt()
                : FutureBuilder<AppCatalog>(
                    future: _apps,
                    builder: (context, snapshot) {
                      if (snapshot.connectionState != ConnectionState.done) {
                        return const _Loading(message: '正在读取应用列表…');
                      }
                      if (snapshot.hasError) {
                        return ListView(
                          children: [
                            const Padding(
                              padding: EdgeInsets.fromLTRB(24, 24, 24, 0),
                              child: Text('应用列表不可用。'),
                            ),
                            _RequestError(
                              message: _errorMessage(snapshot.error!),
                              onRetry: _reload,
                            ),
                            if (_searchText.trim().isNotEmpty)
                              _exactSearchResult(message: '可按完整包名单独查询。'),
                          ],
                        );
                      }
                      final catalog = snapshot.data!;
                      final apps = catalog.apps
                          .where(
                            (app) =>
                                app.label.toLowerCase().contains(_query) ||
                                app.packageName.toLowerCase().contains(_query),
                          )
                          .toList();
                      final packageQuery = _searchText.trim();
                      final showExactLookup =
                          packageQuery.isNotEmpty &&
                          !apps.any((app) => app.packageName == packageQuery);
                      return Column(
                        children: [
                          _StatusNotice(message: catalog.notice),
                          Expanded(
                            child: apps.isEmpty && !showExactLookup
                                ? const Center(child: Text('没有可显示的应用。'))
                                : ListView.builder(
                                    itemCount:
                                        apps.length + (showExactLookup ? 1 : 0),
                                    itemBuilder: (context, index) {
                                      if (showExactLookup && index == 0) {
                                        return _exactSearchResult(
                                          message: apps.isNotEmpty
                                              ? '列表中没有完全匹配该包名的应用。'
                                              : '列表中没有匹配的可见应用。',
                                        );
                                      }
                                      return _appTile(
                                        apps[index - (showExactLookup ? 1 : 0)],
                                      );
                                    },
                                  ),
                          ),
                        ],
                      );
                    },
                  ),
          ),
        ],
      ),
    ),
  );
}

class AppDetailsPage extends StatefulWidget {
  const AppDetailsPage({super.key, required this.app});
  final InstalledApp app;
  @override
  State<AppDetailsPage> createState() => _AppDetailsPageState();
}

class _AppDetailsPageState extends State<AppDetailsPage> {
  AppDetails? _details;
  bool _loading = true;
  bool _changing = false;
  String? _loadError;
  String? _operationMessage;
  bool _operationFailed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final value = await _channel.invokeMethod<Object?>('getAppDetails', {
        'packageName': widget.app.packageName,
        'userId': widget.app.userId,
        'uid': widget.app.uid,
      });
      final details = AppDetails.fromMap(_map(value));
      if (mounted) setState(() => _details = details);
    } catch (error) {
      if (mounted)
        setState(() {
          _details = null;
          _loadError = _errorMessage(error);
        });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _refresh() {
    if (!_operationFailed && _operationMessage != null) {
      setState(() => _operationMessage = null);
    }
    _load();
  }

  Future<void> _selectMode(AppOperation operation, String identityToken) async {
    final mode = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '修改 ${operation.name}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            for (final state in operation.allowedStates)
              ListTile(
                title: Text(_stateLabel(state)),
                selected: state == operation.state,
                trailing: state == operation.state
                    ? const Icon(Icons.check)
                    : null,
                onTap: () => Navigator.pop(context, state),
              ),
          ],
        ),
      ),
    );
    if (!mounted || mode == null) return;
    await _changeMode(operation, mode, identityToken);
  }

  Future<void> _changeMode(
    AppOperation operation,
    String mode,
    String identityToken,
  ) async {
    if (_changing) return;
    setState(() {
      _changing = true;
      _operationMessage = null;
    });
    String? observedState;
    try {
      final value = await _channel.invokeMethod<Object?>('setOpMode', {
        'packageName': widget.app.packageName,
        'uid': widget.app.uid,
        'userId': widget.app.userId,
        'identityToken': identityToken,
        'code': operation.code,
        'mode': mode,
      });
      final result = _map(value);
      final requested = result['requestedState'] as String;
      final observed = result['observedState'] as String;
      final matched = result['matched'] as bool;
      observedState = observed;
      if (mounted)
        setState(() {
          _operationFailed = !matched;
          _operationMessage = matched
              ? '已读取实际状态：${_stateLabel(observed)}。'
              : '请求：${_stateLabel(requested)}；实际：${_stateLabel(observed)}。结果与请求不一致。';
        });
    } catch (error) {
      if (mounted)
        setState(() {
          _operationFailed = true;
          _operationMessage = _errorMessage(error);
        });
    }
    if (!mounted) return;
    await _load();
    if (!mounted) return;
    String? refreshedState;
    for (final current in _details?.operations ?? <AppOperation>[]) {
      if (current.code == operation.code) {
        refreshedState = current.state;
        break;
      }
    }
    final currentDetails = _details;
    final identityChanged =
        currentDetails != null && currentDetails.identityToken != identityToken;
    final writeReadback = observedState;
    setState(() {
      if (writeReadback != null &&
          (identityChanged || refreshedState != writeReadback)) {
        final warnings = <String>[
          if (identityChanged) '刷新后应用身份已变化或无法核验',
          if (refreshedState == null)
            '刷新后无法读取该操作'
          else if (refreshedState != writeReadback)
            '刷新后状态：${_stateLabel(refreshedState)}',
        ];
        final original = _operationFailed
            ? _operationMessage ?? ''
            : '操作后回读：${_stateLabel(writeReadback)}。';
        _operationMessage = '$original ${warnings.join('；')}，请核对当前状态。'.trim();
        _operationFailed = true;
      }
      _changing = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final details = _details;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.app.label),
        actions: [
          IconButton(
            tooltip: '刷新权限状态',
            onPressed: _loading || _changing ? null : _refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            if (_changing) const LinearProgressIndicator(),
            if (_operationMessage != null)
              _StatusNotice(
                message: _operationMessage!,
                error: _operationFailed,
              ),
            Expanded(
              child: _loading
                  ? const _Loading(message: '正在读取权限状态…')
                  : _loadError != null
                  ? _RequestError(message: _loadError!, onRetry: _refresh)
                  : details == null
                  ? const Center(child: Text('没有可显示的权限信息。'))
                  : CustomScrollView(
                      key: const PageStorageKey<String>('permission-scroll'),
                      slivers: [
                        SliverToBoxAdapter(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: SelectableText(
                              '${details.app.packageName}\n用户 ${details.app.userId} · UID ${details.app.uid}',
                            ),
                          ),
                        ),
                        if (details.serviceError != null)
                          SliverToBoxAdapter(
                            child: _StatusNotice(
                              message: 'Thanox 不可用：${details.serviceError}',
                              error: true,
                            ),
                          ),
                        if (details.coverageNote != null)
                          SliverToBoxAdapter(
                            child: _StatusNotice(
                              message: details.coverageNote!,
                            ),
                          ),
                        const SliverToBoxAdapter(
                          child: _SectionHeading(
                            title: 'Android 声明权限',
                            subtitle: 'Android 报告的授权状态；单项操作见下方 AppOps。',
                          ),
                        ),
                        if (details.permissions.isEmpty)
                          const SliverToBoxAdapter(
                            child: _EmptySection(message: '此应用没有声明权限。'),
                          ),
                        SliverList.builder(
                          itemCount: details.permissions.length,
                          itemBuilder: (context, index) {
                            final permission = details.permissions[index];
                            return ListTile(
                              title: Text(permission.label),
                              subtitle: Text(
                                '${permission.name}\n${permission.granted ? '已授予' : '未授予'} · ${_protectionLabel(permission.protection)}',
                              ),
                            );
                          },
                        ),
                        const SliverToBoxAdapter(
                          child: _SectionHeading(
                            title: 'Thanox AppOps',
                            subtitle: '显示 Thanox 观察到的状态与可用单项操作。',
                          ),
                        ),
                        if (details.operations.isEmpty)
                          const SliverToBoxAdapter(
                            child: _EmptySection(message: '没有可用的 AppOps 信息。'),
                          ),
                        SliverList.builder(
                          itemCount: details.operations.length,
                          itemBuilder: (context, index) {
                            final operation = details.operations[index];
                            final permissionLine = operation.permission == null
                                ? ''
                                : '${operation.permission}\n';
                            return ListTile(
                              title: Text(operation.name),
                              subtitle: Text(
                                '${permissionLine}Thanox 状态：${_stateLabel(operation.state)}${operation.runtime ? '\n关联运行时权限' : ''}',
                              ),
                              trailing:
                                  operation.allowedStates.isEmpty ||
                                      details.identityToken == null
                                  ? const Text('仅查看')
                                  : IconButton(
                                      key: ValueKey(
                                        'op-${operation.code}-change',
                                      ),
                                      tooltip: '修改 ${operation.name} 的状态',
                                      onPressed:
                                          _changing ||
                                              details.serviceError != null
                                          ? null
                                          : () => _selectMode(
                                              operation,
                                              details.identityToken!,
                                            ),
                                      icon: const Icon(Icons.edit_outlined),
                                    ),
                            );
                          },
                        ),
                        const SliverToBoxAdapter(child: SizedBox(height: 24)),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading({required this.message});
  final String message;
  @override
  Widget build(BuildContext context) => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const CircularProgressIndicator(),
        const SizedBox(height: 16),
        Text(message),
      ],
    ),
  );
}

class _RequestError extends StatelessWidget {
  const _RequestError({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message),
          const SizedBox(height: 16),
          FilledButton(onPressed: onRetry, child: const Text('重试')),
        ],
      ),
    ),
  );
}

class _StatusNotice extends StatelessWidget {
  const _StatusNotice({required this.message, this.error = false});
  final String message;
  final bool error;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      color: error ? Theme.of(context).colorScheme.errorContainer : null,
      child: Text(
        message,
        style: error
            ? TextStyle(color: Theme.of(context).colorScheme.onErrorContainer)
            : null,
      ),
    ),
  );
}

class _SectionHeading extends StatelessWidget {
  const _SectionHeading({required this.title, required this.subtitle});
  final String title;
  final String subtitle;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          header: true,
          child: Text(title, style: Theme.of(context).textTheme.titleLarge),
        ),
        const SizedBox(height: 8),
        Text(subtitle),
      ],
    ),
  );
}

class _EmptySection extends StatelessWidget {
  const _EmptySection({required this.message});
  final String message;
  @override
  Widget build(BuildContext context) =>
      Padding(padding: const EdgeInsets.all(16), child: Text(message));
}
