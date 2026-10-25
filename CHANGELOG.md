# 更新记录

## v1.0.4
- 版本号改为单一来源 `version.properties`，构建时自动注入 AndroidManifest
- 新增 `scripts/bump.sh` 版本自增脚本
- CI 增加「tag 与版本号一致性」校验

## v1.0.3
- 修正设置页「刷新率设定」文案：没有 `persist.performance.rapid.refresh_rate.level` 的机型不再显示 `120 Hz`，改为显示设置页自带的「极速高刷 165 Hz」
- 该修正只 hook 渲染入口 `refreshRateSettingSummary(int,int)`，不影响实际刷新率与 LTPO

## v1.0.2
- 迁移到 libxposed 新 API（`io.github.libxposed:api:102.0.0`），入口改为 `XposedModule`
- 修正 `META-INF/xposed/*` 打包位置（必须位于 APK 根目录）
- 修复设置页开关被重复注入到所有设置页面的问题
- 档位误设为 8（185 档，本机不支持）时自动纠偏为 7

## v1.0.1
- 新增档位纠偏；移除视频应用默认档提升，保持原生策略

## v1.0.0
- 首个版本：165Hz 解锁、LTPO 1Hz、低亮度 DC、锁屏/动画/指纹 165、设置页「锁定 165Hz（关闭 LTPO）」开关
