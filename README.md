# Refresh165

ColorOS 17 的 165Hz / LTPO / 低亮度 DC 修复模块（LSPosed，基于 libxposed 新 API）。

- 版本：v1.0.3
- 开发者：对味
- 适配：ColorOS 17（OPPO / 一加，含 PLK110 一加 15）
- 限制：无机型限制、无系统版本限制、无隐藏开关

## 功能

| 场景 | 结果 |
|---|---|
| 设置 → 显示 → 屏幕刷新率 → 刷新率设定 | 显示「极速高刷 165 Hz」 |
| 桌面 / 应用 / 动画 / 后台滑动 / 锁屏 / 通知栏 | 165Hz |
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

- 需要全程 165Hz：打开「锁定 165Hz（关闭 LTPO）」，然后 **熄屏再亮屏一次** 生效
- 关闭同样需要熄屏亮屏一次
- 该开关打开时，所有窗口请求（含视频类应用自己发的 60）统一为 165；关闭时按系统原生策略

## 说明与边界

- 高温时系统会自行降到 90/120，这是 OPPO 温控保护，模块不干预
- 视频类应用默认跟随系统策略（多数不上报片源帧率，因此系统无法「按片源」决定刷新率）
- AOD 不改动
- 模块不写 `persist.*` 属性，也不修改 `/my_product` 分区文件

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

## 更新记录

见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

[MIT](LICENSE)

## 下载

见 [Releases](https://github.com/wanwei1028/Refresh165/releases)。
