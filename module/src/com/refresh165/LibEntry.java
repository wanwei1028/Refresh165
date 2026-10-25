package com.refresh165;

import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** libxposed API 102 入口：把包加载事件转给现有 Hook 逻辑。 */
public final class LibEntry extends XposedModule {

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(4, "Refresh165", "module loaded, systemServer=" + param.isSystemServer());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            XposedBridge.init(this, cl);
            XC_LoadPackage.LoadPackageParam lp =
                    new XC_LoadPackage.LoadPackageParam("android", cl);
            new Hook().handleLoadPackage(lp);
            log(4, "Refresh165", "system_server hooks installed");
        } catch (Throwable t) {
            log(6, "Refresh165", "onSystemServerStarting failed: " + t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            XposedBridge.init(this, cl);
            XC_LoadPackage.LoadPackageParam lp =
                    new XC_LoadPackage.LoadPackageParam(param.getPackageName(), cl);
            new Hook().handleLoadPackage(lp);
        } catch (Throwable t) {
            log(6, "Refresh165", "onPackageLoaded failed: " + t);
        }
    }
}
