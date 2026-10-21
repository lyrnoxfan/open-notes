# 私密笔记（private-notes）

本地优先的 Android 私密笔记应用。所有内容存放在一台设备上，数据库从创建第一页起就是 SQLCipher 密文。

当前进度：**阶段一、二、四已实现**（工程基础与安全存储、笔记功能、应用锁与隐私），代码已提交，
按方案第 11 节的验收结果见文末「验证结果」。阶段三（Markdown、标签、搜索）尚未开始。

## 技术栈

| 关注点 | 选择 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| 数据库 | Room 2.6.1 + SQLCipher（`net.zetetic:sqlcipher-android` 4.12.0） |
| 密钥 | Android Keystore（AES-256-GCM，不可导出） |
| 应用锁 | `androidx.biometric` 1.1.0（BiometricPrompt + 设备凭据兜底） |
| 构建 | AGP 8.7.3 / Gradle 8.9 / Version Catalog |
| minSdk / targetSdk | 26 / 35 |

`sqlcipher-android` 固定在 4.12.0：这是仍依赖 `androidx.sqlite:2.2.0` 的最新版本，与 Room 2.6.1 的
组合风险最小。更高版本会带入 `androidx.sqlite` 2.6+/2.7+，属于方案 §2 明确要避免的随意升级。

## 加密模型

```
Android Keystore（不可导出 AES-256-GCM 密钥）
        │ 包裹
        ▼
32 字节随机口令 ──只以密文形式存于 SharedPreferences──► 应用私有目录
        │ 作为 SQLCipher 口令
        ▼
private_notes.db（密文）
```

代码强制的规则，不靠约定：

1. **唯一开库入口。** `EncryptedDatabaseProvider` 是构造 `AppDatabase` 的唯一地方，SQLCipher 的
   `SupportOpenHelperFactory` 在 Room 创建任何东西之前挂上，不存在明文的中间态。
2. **明文一律拒绝，不做迁移。** 开库前检查文件头，发现 `SQLite format 3\0` 直接抛异常。
3. **开库后复查。** Room 打开后重新读磁盘文件头，必须不是明文。
4. **密钥先证明可持久化，再交给数据库。** 生成 → 包裹 → 写入 → 读回 → 解包比对，全部通过才返回。
   否则写盘静默失败会造出一个永远打不开的库。
5. **密钥丢失不清库。** 库文件存在但密钥不可用时抛 `KeyUnavailable` 并提示通过备份恢复；
   不新建密钥、不删除、不重建原文件（§6.1）。
6. **备份带不走东西。** 自动备份与设备迁移排除数据库目录和持有包裹密钥的 SharedPreferences。

## 应用锁与隐私

```mermaid
flowchart LR
    A[进程回到前台] --> B{离开超过 30 秒?}
    B -- 否 --> C[保持解锁]
    B -- 是 --> D[锁定]
    D --> E[断开数据库连接]
    D --> F[只组合锁屏界面]
    F --> G[指纹 / 设备凭据]
    G -- 成功 --> H[解锁，重新开库]
    G -- 取消或失败 --> F
```

1. **只有认证能解锁。** 状态机初始即锁定（冷启动、进程被杀后重来都一样），唯一的出口是
   `AppLockManager.unlock()`。
2. **30 秒宽限。** 超过 30 秒才要求重新认证；刚好 30 秒返回不算。计时挂在
   `ProcessLifecycleOwner` 上，旋转屏幕、弹对话框不会误判为离开。
3. **系统界面不算离开。** 指纹弹窗、设备凭据页、文件选择器用 `beginSystemUi`／`endSystemUi`
   括起来；系统界面没回调时，下一次回到前台会清掉标记，不会永久免锁。
4. **锁定时不组合笔记界面。** `AppLockGate` 在锁定时只渲染锁屏，导航图与 ViewModel 一并销毁，
   不存在「先显示再遮挡」的中间帧。
5. **锁定时关库。** 锁定即断开 Room 连接，解锁后重新打开同一个密文库；不重建、不清空。
6. **截图与最近任务预览。** `FLAG_SECURE` 无条件设置在窗口上，锁屏界面也包含在内。
7. **没有锁屏凭据就不开锁。** 设备未设置 PIN/图案/密码时拒绝开启应用锁，并引导去系统安全设置，
   避免出现「锁得上、解不开」。

## 目录

```
app/src/main/java/com/example/privatenotes/
├── security/         Keystore 包裹、密钥管理、明文库防护、应用锁、FLAG_SECURE
├── data/local/       AppDatabase、实体、DAO、加密开库入口、migrations
├── data/repository/  NoteRepository、TimeSource、LIKE 转义
├── feature/notes/    笔记列表、编辑器（1 秒防抖自动保存）
├── feature/trash/    回收站、恢复、永久删除
├── feature/lock/     锁屏界面、应用锁开关
├── ui/               AppRoot（锁门）、navigation、theme（暖纸配色）
```

## 数据模型（schema v1）

| 表 | 说明 |
| --- | --- |
| `notes` | UUID 主键、标题、Markdown 正文、createdAt / updatedAt / deletedAt |
| `tags` | UUID 主键、名称（NOCASE 唯一）、createdAt / updatedAt |
| `note_tag_refs` | 以稳定 ID 关联笔记与标签 |
| `deletion_markers` | 永久删除的墓碑，阻止旧备份复活已删笔记 |

时间戳统一为 **UTC epoch 毫秒**。方案 §5.1 只写了「Unix 时间戳」，这里选毫秒：§9.5 依赖比较
`updatedAt` 决定导入冲突归属，秒级精度下同一秒内的两次编辑无法区分。

schema 由 Room 导出到 `app/schemas/`。迁移必须逐版本写在 `data/local/migrations`，没有
destructive fallback。

## 构建与测试

```bash
./gradlew assembleDebug               # 构建
./gradlew testDebugUnitTest           # 主机端：密钥、明文防护、DAO、自动保存、应用锁状态机
./gradlew connectedDebugAndroidTest   # 设备端：真 SQLCipher、真 Keystore、锁屏与 FLAG_SECURE
```

加密结论只能在设备上验证：写盘字节由 SQLCipher 原生库产生。应用锁同理——`BiometricPrompt` 是系统
界面，自动化只能验证状态机与它两侧的行为，真的指纹/PIN 交互需要人工确认。

## 验证结果

阶段一、二（历史记录）：主机端与设备端测试全部通过；API 34 模拟器上确认数据库首字节为随机盐，
主库与 `-wal` 中搜不到 `SQLite format 3`。

阶段四：**待运行**（按项目约定，构建与测试将在机器空闲时执行，结果随后补入本节）。需要覆盖的项：

- 主机端：`AppLockManagerTest`（冷启动锁定、30 秒边界、系统界面豁免、开关行为）、
  `AppLockSettingsTest`（开关持久化）。
- 设备端：`AppLockIntegrationTest`（FLAG_SECURE、真实 `ProcessLifecycleOwner` 下的 30 秒超时、
  锁定后不组合笔记列表、关库后仍能读回笔记）、`AppLockGateTest`（锁屏界面与认证结果分支）。
- 手工：开启应用锁后按 Home 停留 31 秒返回 → 出现锁屏；停留 5 秒返回 → 不出现锁屏；截图与最近
  任务预览为空白。

## 已知限制

- Keystore 条目被销毁（恢复出厂、部分锁屏凭据变更、数据迁到新设备）后，包裹密钥不可恢复，
  现有数据库无法打开。当前以错误状态呈现，不做自动重建。导出/恢复属于阶段五。
- 应用锁只保护正常使用路径：已取得设备控制权（Root、调试、注入）的一方可以绕过任何应用内界面锁，
  方案 §6.2 对此有说明。数据库加密与界面锁是两道独立的防线。
- 设备凭据被删除后，应用锁会停在「先设置锁屏密码」页面：按方案 §6.2 不做绕过，需要重新设置凭据
  才能进入。这是有意的取舍，避免把「删除锁屏密码」变成绕过应用锁的方法。
- 应用锁开关目前在笔记列表的更多菜单里；完整设置页属于阶段六，届时迁移过去。
- 阶段三的 Markdown、标签与搜索尚未实现，列表与编辑器里没有对应的假入口。
