# Refresh165

ColorOS 17 的 165Hz / LTPO / 低亮度 DC 修复模块（LSPosed，基于 libxposed 新 API）。

- 版本：见 `version.properties`（当前 v1.0.11）
- 开发者：对味
- 已验证设备：一加 15（PLK110），ColorOS 17 `PLK110_17.0.0.105(CN01)`
- 兼容范围：依赖 ColorOS 私有接口和刷新率 ID 映射；其它机型或系统版本未经验证，不能保证可用

## 功能

| 场景 | 结果 |
|---|---|
| 设置 → 显示 → 屏幕刷新率 → 刷新率设定 | 显示「极速高刷 165 Hz」 |
| 桌面 / SystemUI / 控制中心转场 | 可使用 165Hz |
| 普通应用 | 遵循应用请求和系统策略；已配置固定帧率优先 |
| 游戏内容（全屏/小窗） | 保留游戏自身帧率请求，不因小窗/控制中心转场强制 165Hz |
| 指纹解锁 | 165Hz |
| 静止 | 1Hz（LTPO 保留） |
| 低亮度 / 高刷下的 DC | 保留 DC 资格，全亮度 DC 不被写回 |
| 新增开关 | 设置 → 屏幕刷新率 → 「锁定 165Hz（关闭 LTPO）」 |

## 安装

1. 下载 `Refresh165-vX.Y.Z.apk`（见 Releases）
2. LSPosed / Vector 中启用本模块
3. 作用域勾选：`android`、`system`、`com.android.settings`（三个都要）
4. 重启

> 重装 APK 后若功能不生效，重启一次让管理器的模块路径缓存刷新。

## 使用

- 需要全局常驻 165Hz：打开「锁定 165Hz（关闭 LTPO）」，策略会立即重算
- 关闭后同样立即按系统策略恢复
- 视频应用单独设置的固定帧率优先于全局常驻；未设置固定帧率时才由全局开关或应用白名单提升

## 说明与边界

- 高温时系统会自行降到 90/120，这是 OPPO 温控保护，模块不干预
- 视频类应用默认跟随系统策略（多数不上报片源帧率，因此系统无法「按片源」决定刷新率）
- 视频帧率策略目前仅对 system_server 的主用户生效
- AOD 不改动
- 模块不写 `persist.*` 属性，也不修改 `/my_product` 分区文件
- v1.0.11：白名单应用逐应用固定 165；游戏保留自身帧率（勾选则固定 165）；保留厂商 app-switch boost 并把目标改写为 165；去除 144 闪帧；90Hz 应用提升；锁定 165 使用保存/恢复面板表的方式实时生效且不污染 120/90/60 三张表

## 版本号管理

版本号只有一个来源：`version.properties`

```
versionName=1.0.11
versionCode=111
```

构建时由 `build.sh` 注入到 `AndroidManifest.xml`，仓库里的 manifest 仅作模板，不需要手改。

```bash
./scripts/bump.sh patch    # 1.0.4 -> 1.0.5
./scripts/bump.sh minor    # 1.0.4 -> 1.1.0
./scripts/bump.sh major    # 1.0.4 -> 2.0.0
```

CI 在打 tag 时会校验 `version.properties` 与 tag 一致，不一致直接失败。

## 从源码构建

依赖：JDK 17+、Android SDK（build-tools 34+、platform android-34+）。

```bash
git clone https://github.com/wanwei1028/Refresh165.git
cd Refresh165
ANDROID_SDK=$HOME/Library/Android/sdk ./build.sh
# 产物：out/Refresh165.apk
```

可用环境变量：

| 变量 | 说明 |
|---|---|
| `ANDROID_SDK` / `ANDROID_HOME` | Android SDK 路径 |
| `BUILD_TOOLS` | 指定 build-tools 目录 |
| `ANDROID_JAR` | 指定 android.jar |
| `KEYSTORE` / `KS_PASS` / `KS_ALIAS` | 签名密钥；缺省会在本地生成自签名调试密钥 |
| `OUT` / `APK_NAME` | 输出目录与文件名 |

## 源码结构

```
module/
├── AndroidManifest.xml
├── assets/META-INF/xposed/      # java_init.list / scope.list / module.prop
├── res/values/                  # 名称、说明、默认作用域
└── src/com/refresh165/
    ├── LibEntry.java            # libxposed API 入口（XposedModule）
    ├── Hook.java                # 系统侧 Hook 主体
    ├── SystemViewPolicy.java    # 视频窗口策略
    ├── Force165Tile.java        # 快捷设置磁贴
    └── xp/                      # 极薄的 libxposed 适配层（旧接口 → 新 API）
libs/libxposed-api-102.jar       # io.github.libxposed:api:102.0.0
build.sh                         # 一键构建
```

## 签名说明

Release 里的 APK 使用固定密钥签名，可直接覆盖升级。

从源码自行构建时，`build.sh` 默认使用本地 `keystore.jks`（未提交，首次运行自动生成）。**自签包与 Release 包签名不同，无法互相覆盖**，需要先卸载其中一个。

## 更新记录

见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

[MIT](LICENSE)

## 下载

见 [Releases](https://github.com/wanwei1028/Refresh165/releases)。
