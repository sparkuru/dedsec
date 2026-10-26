# Vector instantiates this class by the name in META-INF/xposed/java_init.list.
# These lifecycle callbacks are invoked through the framework-provided API.
-keep class im.majo.tacchi.module.TacchiModule {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
}
