package com.refresh165.xp;

import com.refresh165.xp.callbacks.XC_LoadPackage;

/** 兼容层：入口接口，实际由 LibEntry 调用。 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
