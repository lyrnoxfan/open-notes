# 测试报告：三件事

这一轮只写测试和脚本，没有动生产代码。三项各自一个提交，下面每一项都带可复跑的命令和实测数字。

| 项 | 提交 | 结论 |
| --- | --- | --- |
| 一、canary 明文扫描 | `2b8d11e` | 私有目录与 logcat 零命中；三项 manifest 断言通过；「除 launcher 外无 exported」不成立（有权限挡着） |
| 二、卸载重装恢复 | `27f666f` | 409 条笔记跨真实 `adb uninstall` 逐字段还原；四种故障注入全部安全失败；事务中杀进程后干净回滚 |
| 三、输入法组合态 | `406bb33` | 有缺陷：组合区在状态回写时被丢弃。已定位到具体行，给出修法，未修 |

---

## 一、canary 明文扫描

命令：

```bash
./scripts/canary-scan.sh          # 会自己装包、跑 seed 测试、拉私有目录
SKIP_SEED=1 ./scripts/canary-scan.sh   # 复用已装机状态
```

产物在 `build/canary/`。

### 做法

`CanarySeedTest` 通过**真实编辑器**写入四个金丝雀——标题 `CANARY-冥王星-7f3a`、正文
`CANARY-BODY-冥王星-9c21`、标签 `CANARY-TAG`，再通过设置页同一条路径保存 WebDAV 密码
`CANARY-DAVPW`，然后做一次**带密码导出**。之后脚本 `am force-stop` 杀掉应用，用
`run-as ... tar` 把 `databases / shared_prefs / files / cache / no_backup` 整体拉到宿主机，
在**原始字节**里按 UTF-8、UTF-16LE、UTF-16BE 三种编码搜这四个串；logcat 同样搜一遍。

用编辑器输入而不是直接插库，是因为要覆盖 WAL、SharedPreferences 这些真正会落盘的路径。

### 结果

| 检查 | 结果 |
| --- | --- |
| 私有目录 | 11 个文件 / 492 KB，含 4 个 WAL/SHM（`private_notes.db-wal/-shm`、`workdb-wal/-shm`） |
| 四个金丝雀 × 三种编码 | **0 命中** |
| logcat（334,058 字节） | **0 命中** |
| 加密导出（867 字节） | 不含任何金丝雀，不可读 |
| 明文导出（`/sdcard/.../files/canary-plaintext.json`，808 字节） | 3/3 命中——**已知行为，不算失败**，单独列出 |

明文导出放在外部存储而不是私有目录，是刻意的：把它放进私有目录，扫描必然会命中测试自己写的
文件，那什么也证明不了。它模拟的是「用户选了一个备份目录」。

### release 包静态检查

`aapt2 dump xmltree` 读 release APK 的 manifest：

| 检查 | 结果 |
| --- | --- |
| `debuggable` | 不存在 ✅ |
| `allowBackup` | `false` ✅ |
| exported 组件 | **4 个**：`MainActivity`（launcher）、`SystemJobService`（`BIND_JOB_SERVICE`）、`DiagnosticsReceiver`（`DUMP`）、`ProfileInstallReceiver`（`DUMP`） |

**发现（P3，非漏洞）**：「除 launcher 外无 exported 组件」这条验收**不成立**。三个库组件都是
导出的，但都带签名/系统级权限（普通应用无法调用）。要用字面口径满足它，需要在 manifest 里加
`tools:node="remove"` 覆盖 WorkManager 的 `DiagnosticsReceiver` 等——那是生产改动，本轮不做。
脚本的判定因此区分「无权限保护的导出组件」（失败）与「有权限保护」（通过并列出）。

---

## 二、卸载重装恢复

命令：

```bash
./scripts/e2e-reinstall.sh        # 全程约 5 分钟，产物在 build/e2e/
```

### 数据

| 项 | 数量 |
| --- | --- |
| 笔记总数 | **409**（401 活跃 / 8 在回收站） |
| 最长正文 | 2,009,786 字节（中文 + 标点 + 换行） |
| 永久删除的笔记 | 1 条，其 id 不得随备份回来 |
| 标签 | 中文多标签，含 4 字以上长标签 |
| 明文导出 | 2,314,902 字节 |
| 加密导出 | 2,314,961 字节（密码 `E2E-PASS`） |

种子测试把每条的 **id、标题、正文 SHA-256 与字节长度、标签、createdAt/updatedAt/deletedAt**
写进 `e2e-manifest.json`（163,937 字节）。验证侧比对的是这份 manifest，不是再跑一遍种子逻辑——
否则两边可以一起错。

### 流程与结果

| 阶段 | 做了什么 | 结果 |
| --- | --- | --- |
| 1 | 全新安装 → 写 409 条 → 导出两份 → 写 manifest | 通过 |
| 2 | 把 manifest 与两份导出拉到宿主机 | 通过 |
| 3 | **`adb uninstall`** → `adb install` → 把文件推回新的私有目录 | 通过（`pm list packages` 确认卸载干净） |
| 4 | 事务中杀进程：导入 2.3 MB 备份，120 ms 时进程自杀 | **409 → 0 条**，干净回滚，无半条 |
| 5 | 正常导入明文导出，逐字段比对 | 通过 |
| 6 | 故障注入四种 | 全部安全失败、数据指纹不变 |
| 7 | 再卸载重装，用加密导出 + 原密码重复第 5 步 | 通过 |

第 4 步的做法值得说明：最初由宿主机 `am force-stop` 定时杀，每次都在提交之后落地，什么也没证明
（曾出现「409 of 409 已提交」这种无意义结论）。改成测试内部看门狗在合并开始后 120 ms 自杀，
才稳定落在事务内，得到 **0 of 409**——这才是「要么全有要么全无」。

第 6 步的四种故障，每一种都断言两件事：**报错**，且**数据库指纹与之前完全一致**
（id/标题/正文 hash/三个时间戳拼成的字符串）：

| 故障 | 结果 |
| --- | --- |
| 截断的加密包（1,157,480 字节，原包一半） | `BackupException`，kind 为 wrong-password-or-corrupt / invalid-format，数据不变 |
| 文件不存在 | 抛错，数据不变 |
| URI 已生成后备份目录被删 | 抛错，数据不变 |
| WebDAV 指向关闭端口 | 抛错，**最近成功时间戳不动**，数据不变 |

第 5 步比对字段：id、标题、正文（字节长度 + SHA-256）、标签集合、createdAt、updatedAt、
deletedAt，逐条 409 条；另外断言二次导入 0 插入 0 删除，永久删除的 id 不复活。

---

## 三、中文输入法组合态

### 先看代码

`NoteEditorScreen.kt:331`（`EditorBody`）每帧重建输入框的值：

```kotlin
BasicTextField(
    value = TextFieldValue(text = content, selection = selection),   // 没有 composition
    onValueChange = onValueChange,
    ...
)
```

而 `content` 来自 ViewModel：`onContentChange` → `_uiState.update`（`NoteEditorViewModel.kt:178-190`）
→ 重组。也就是说**每个按键都会让值重建一次**，重建用的是不带 `composition` 的构造函数。

### 结论：有缺陷

| 问题 | 判断 |
| --- | --- |
| 组合区（未上屏拼音）在状态回写时被丢弃 | **是**，代码级确定：`TextFieldValue(text, selection)` 的 `composition` 恒为 `null` |
| 文本经回写后被改动/重复 | 未发现：`EditorInputRoundTripTest`（真机，跨 1 秒防抖、追加、回读）逐字节相等 |
| 复现条件 | 组合态存在期间发生一次状态回写，即每次按键；真实键盘上表现为**停顿超过防抖窗口**时最明显（候选栏消失、只上屏最后一个字，或拼音与汉字一起留下） |
| 复现率 | **自动化无法给出**。原因见下：Compose 不把 `InputConnection` 交给测试进程，`setComposingText` 注入不进去 |

自动化的边界说明：试过两条路——直接 `ComposeView.onCreateInputConnection()`，以及先
`requestFocus()` 再取，API 34 上都返回 `null`。所以组合态本身只能真机手测，脚本见
`scripts/ime-composing-manual.md`（五个用例，含「未上屏停 1.5 秒」这条关键项和记录格式）。

现有自动化覆盖：
- `EditorCompositionTest`（JVM）：复现重建模式，断言「带 composition 的值重建后 composition 为
  null」。**这条断言就是缺陷本身**，修好之后它会失败，提醒改写。
- `EditorInputRoundTripTest`（真机）：跨防抖的文本完整性，通过。

### 建议修法（未实施）

把 `TextFieldValue` 留在编辑器本地，不再每次从字符串重建：

```kotlin
var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
    mutableStateOf(TextFieldValue(content, TextRange(content.length)))
}

// 外部状态变化时才同步，且只在没有组合态、且文本真的不同的时候覆盖
LaunchedEffect(content) {
    if (fieldValue.composition == null && content != fieldValue.text) {
        fieldValue = fieldValue.copy(text = content, selection = TextRange(content.length))
    }
}

BasicTextField(
    value = fieldValue,
    onValueChange = { value ->
        fieldValue = value                 // composition 一起留下
        viewModel.onContentChange(value.text)
    },
)
```

要点三条：

1. `composition` 必须跟着值一起走，任何 `TextFieldValue(text, selection)` 的重建都会清掉它。
2. 同步方向要单向：ViewModel 只有「外部载入」时才回灌编辑框，否则打字时会出现回写与输入的循环。
3. 顺带能修掉旋转后光标位置丢失（现在是 `remember { TextRange.Zero }`，不是 `rememberSaveable`）。

风险：`LaunchedEffect(content)` 的时序需要真机验证，尤其是 undo/粘贴这类一次性大改动。

---

## 四、本轮发现汇总

| # | 严重度 | 发现 | 状态 |
| --- | --- | --- | --- |
| 1 | **P1** | 编辑器重建值时不保留 IME 组合区，中文输入可能丢字/重复 | 已定位、给修法，未修 |
| 2 | P3 | `EditorTags.ADD_TAG` 常量还在，但标签胶囊上不再有该 testTag（换成自定义胶囊时丢的） | 未修，测试改按文本点击 |
| 3 | P3 | release 有 3 个库导出的组件（均有权限保护），字面口径「只有 launcher」不成立 | 记录，未改 manifest |
| 4 | P3 | 光标位置用 `remember`，旋转后丢失（README 未承诺，属观感问题） | 记录 |

## 五、这几项测试在 CI 里怎么跑

本轮新增的设备测试分两类：

| 类 | 去哪跑 | 原因 |
| --- | --- | --- |
| `CanarySeedTest`、`E2eSeedTest`、`E2eImportVerifyTest`、`E2eFaultTest`、`E2eSlowImportTest`/`E2eAtomicityTest`、`EditorInputRoundTripTest` | **只在脚本里跑**，标了 `@ScriptDriven` | 它们写几百行数据、依赖推入私有目录的文件，其中一个还故意杀自己进程；放进共享套件会让别的测试的行数依赖执行顺序 |
| `EditorCompositionTest` | `testDebugUnitTest`（CI 的 build job） | 纯 JVM，无副作用 |

CI 的 `connectedCheck` 加了
`-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.example.privatenotes.ScriptDriven`，
所以那 31 个原有设备测试照旧跑，脚本驱动的这些不参与。手动跑法就是本文各节的命令。

绿：<https://github.com/lyrnoxfan/open-notes/actions/runs/39713861513>（build + instrumented 均 success）。

过程中被取消的一次运行（`39713700168`）是 `concurrency: cancel-in-progress` 造成的：修完标注后
又推了一次，新推送取消了在跑的那次。

## 五、没做的事

- **没有改生产代码**，包括上面 P1 的修法：按本轮约定只写失败用例与报告。
- **没跑 `connectedCheck` 全量**：新增的三个设备测试类已单独跑过，完整套件在 CI 的
  `instrumented` job 里跑（见 `docs/design-report.md` §4.1）。
- **没在真机上测中文输入法**：沙箱里没有真机，也没有可注入的 IME。需要你在手机上按
  `scripts/ime-composing-manual.md` 走一遍，把复现率填回来——这个数字决定 P1 的优先级。
