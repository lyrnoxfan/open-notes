# 私密笔记（private-notes）

本地优先的 Android 私密笔记应用。所有内容存放在一台设备上，数据库从创建第一页起就是 SQLCipher 密文。

当前进度：**阶段一至阶段四已实现并通过既有验证；阶段五（备份与恢复）和阶段六（WebDAV 与自动备份）的代码已完成，位于 `feature/backup` 分支，等待构建、测试与设备端验收。**

## 技术栈

| 关注点 | 选择 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| 数据库 | Room 2.6.1 + SQLCipher（`net.zetetic:sqlcipher-android` 4.12.0） |
| 密钥 | Android Keystore（AES-256-GCM，不可导出） |
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

## 目录

```
app/src/main/java/com/example/privatenotes/
├── security/        Keystore 包裹、密钥管理、明文库防护
├── data/local/      AppDatabase、实体、DAO、加密开库入口、备份专用 DAO
├── data/repository/ NoteRepository、TimeSource、LIKE 转义
├── data/backup/     JSON 格式、加密信封、快照、合并、SAF、WebDAV、WorkManager
├── ui/theme/        Design Tokens（暖纸配色）、字体、圆角、主题
├── feature/notes/   笔记列表、编辑、自动保存
├── feature/trash/   回收站
└── feature/backup/  备份设置、导入、状态与手动操作
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


## 备份与恢复（阶段五）

备份使用独立的 `private-notes-backup` 格式，`schemaVersion` 与数据库版本分开管理。文件包含笔记（含回收站）、标签、标签关联和永久删除标记。

- 手动备份可选择密码加密。加密文件是 `PNBKP001` 二进制信封：PBKDF2-HMAC-SHA256（210,000 次）派生 256 位密钥，AES-256-GCM 加密，头部作为 AAD 认证；错误密码和篡改文件都会在写入数据库前被拒绝。
- 不加密时写出明文 JSON，界面会明确提示文件可被任何拿到它的人读取。
- 自动备份不接收密码，因此始终是明文 JSON；需要加密时使用“立即备份”或“立即上传”。
- 本地备份通过 SAF 持久化目录授权写入，文件名带 UTC 时间戳和随机后缀；写入后按 SHA-256 读回校验，保留最近 30 份。
- 导入先完整解密、解析和校验，再在本地目录可用时创建安全备份，最后在一个 Room 事务里合并。任何一步失败都不会留下半导入状态。
- 合并规则：较新的 `updatedAt` 胜出；同时间戳不同内容保留本地并记录冲突；删除标记阻止旧备份复活；标签按稳定 ID 合并，同名不同 ID 自动重映射；关联只在两端都存在时写入。

## WebDAV 与自动备份（阶段六）

- 只接受 HTTPS，使用系统证书校验，禁用重定向；用户名/密码使用 Basic Auth（UTF-8）。WebDAV 密码用 Android Keystore 包裹后存入 SharedPreferences，备份密码从不保存。
- 上传使用唯一文件名，PUT 成功后 GET 读回并比较 SHA-256；服务器拒绝 GET 时退化为 PROPFIND 长度校验。校验失败只删除刚上传的该文件，不触碰其他远端文件。
- 本地与远端各自使用独立的 WorkManager 周期任务（24 小时，1 小时 flex），并有独立的一次性补备份任务；远端任务要求网络连接。
- App 启动和回到前台时检查是否超过一天未成功，必要时安排补备份；失败按可重试性决定 WorkManager 重试或等待下一次前台检查。
- 本地与远端状态、最近成功时间和最近失败原因分开记录；成功时间只在写入和完整性校验都完成后更新。

## 构建与测试

```bash
./gradlew assembleDebug          # 构建
./gradlew testDebugUnitTest      # 主机端：密钥契约、明文防护、DAO 与 schema
./gradlew connectedDebugAndroidTest  # 设备端：真 SQLCipher 落盘加密、真 Keystore
```

加密结论只能在设备上验证：写盘字节由 SQLCipher 原生库产生。

## 验证状态

- 阶段一至阶段四的既有主机端/设备端测试结果保留；阶段五、六新增了格式、加密、合并、本地写入、导入和 WebDAV 的主机端测试。
- 阶段五、六的构建与测试尚未在当前分支执行，需按用户通知后再跑；README 不把未执行的测试写成已通过。
- 阶段一设备端结论：API 34 模拟器上 `databases/private_notes.db` 头 16 字节为随机盐；
  主库与 `-wal` 文件中均搜不到 `SQLite format 3`；SharedPreferences 中只有包裹后的密钥。

## 已知限制

- Keystore 条目被销毁（恢复出厂、部分锁屏凭据变更、数据迁到新设备）后，包裹密钥不可恢复，
  现有数据库无法打开。当前以错误状态呈现，不做自动重建。导出/恢复属于阶段五。
- 自动备份不加密：备份密码不落盘，后台任务无法取得密码；需要加密备份时请使用手动入口。
- WebDAV 只做单向备份，不提供自动双向同步，也不会未经确认用远端文件覆盖本地数据。
- 大型导入在本地备份目录未配置时没有安全备份可做；建议先配置本地目录再导入。
