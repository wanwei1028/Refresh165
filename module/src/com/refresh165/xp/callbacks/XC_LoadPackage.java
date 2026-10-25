package com.refresh165.xp.callbacks;

/** 兼容层：包加载参数。 */
public final class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;

        public LoadPackageParam() { }
        public LoadPackageParam(String packageName, ClassLoader classLoader) {
            this.packageName = packageName;
            this.classLoader = classLoader;
        }
    }
}
