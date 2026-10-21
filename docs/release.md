# 发布流程

方案 §10 要求：CI 负责编译、单测、Lint 和测试包；推版本标签时构建混淆后的正式包、用固定密钥签名、
验证签名并发布。签名材料不进仓库。

## 流水线

| 工作流 | 触发 | 做什么 |
| --- | --- | --- |
| `.github/workflows/ci.yml` | push 到 main、PR、手动 | `testDebugUnitTest`、`lint`、`assembleDebug`，上传测试/Lint 报告与 debug APK |
| `.github/workflows/release.yml` | 推送 `v*` 标签 | 单测 → `assembleRelease`（R8 + 签名）→ `apksigner` 校验 → 发布 GitHub Release |

## 仓库需要配置的 Secrets

| Secret | 内容 |
| --- | --- |
| `OPEN_NOTES_KEYSTORE_BASE64` | 发布 keystore 的 base64（`base64 -w0 release.jks`） |
| `OPEN_NOTES_KEYSTORE_PASSWORD` | keystore 口令 |
| `OPEN_NOTES_KEY_ALIAS` | 密钥别名 |
| `OPEN_NOTES_KEY_PASSWORD` | 密钥口令 |
| `OPEN_NOTES_CERT_SHA256` | 期望的证书 SHA-256 指纹；与签名结果不一致时流水线拒绝发布 |

`OPEN_NOTES_CERT_SHA256` 的作用是防止换错密钥：用它签出来的包无法覆盖安装旧版本，而这一点在发布
之后才会被发现。指纹取值方式：

```bash
apksigner verify --print-certs app-release.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
```

## 版本号

标签 `vMAJOR.MINOR.PATCH` 决定 `versionName`（去掉前缀的 `v`），`versionCode` 由
`MAJOR*10000 + MINOR*100 + PATCH` 计算，例如 `v1.2.3` → `versionName=1.2.3`、`versionCode=10203`。
同一版本重复发布不会换号，跨版本单调递增。

## 本地复现正式构建

不配置签名时 `assembleRelease` 会直接失败并说明缺哪一项，而不是产出一个 unsigned 包——避免把问题
推到安装阶段。

```bash
export OPEN_NOTES_KEYSTORE_FILE=/path/to/release.jks
export OPEN_NOTES_KEYSTORE_PASSWORD=...
export OPEN_NOTES_KEY_ALIAS=...
export OPEN_NOTES_KEY_PASSWORD=...
./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=10000
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 首次发布前必须完成的验证

§10 明确写了：不能只确认构建成功。签名与覆盖安装必须在真机/模拟器上验证一次。

1. 用发布密钥构建 `v1.0.0`，安装到设备，新建几篇笔记与标签。
2. 用同一密钥构建 `v1.0.1`（`versionCode` 更大），覆盖安装。
3. 打开应用：原有笔记、标签、回收站内容都在；无需卸载。
4. 若第 2 步安装被拒绝（签名不一致），说明密钥换了，必须先解决再发布。

这一步依赖设备，和设备测试一起执行。
