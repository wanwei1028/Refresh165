# 安全说明

## 签名密钥

发布用的签名密钥**不在仓库里**，只以加密 Secret 的形式存放：

| Secret | 用途 |
|---|---|
| `KS_BASE64` | keystore 文件的 base64 |
| `KS_PASS` | keystore 密码 |
| `KS_ALIAS` | 密钥别名 |

- 仓库、提交历史、Release 附件中都不包含 keystore
- 工作流不会打印密钥内容；解码后的 keystore 只存在于 runner 的临时目录，任务结束后销毁
- 工作流只在 `push`（main / tag）和手动触发时运行，不接受 `pull_request`，避免同仓 PR 读取 Secret

## 自签构建

从源码构建时，`build.sh` 默认在本地生成 `keystore.jks`（已在 `.gitignore` 中忽略）。自签包与 Release 包签名不同，无法互相覆盖。

## 报告问题

发现安全问题请通过 [Issues](https://github.com/wanwei1028/Refresh165/issues) 反馈，或直接联系仓库维护者。
