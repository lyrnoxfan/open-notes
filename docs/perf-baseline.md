# 性能基线（优化前）

测量时间：2026-10-22。**这是模拟器数据，不是真机数据** —— 沙箱里没有 arm64 的 ColorOS 手机，
所以「真机基线」这一项本次无法交付，下面所有运行时数字都只能当参考，不能当验收。

| 项 | 值 |
| --- | --- |
| 设备 | Android Emulator `sdk_gphone64_x86_64`（AVD `opennotes`） |
| 系统 | API 34（Android 14），AOSP `google_apis` 镜像 |
| ABI | x86_64，4 核 / 1536 MB，GPU `swiftshader_indirect`（软件渲染） |
| 目标机（未测） | arm64 ColorOS 手机，需要你在真机上复测一遍 |
| 构建 | release（`assembleRelease`，R8 + 资源压缩），versionName `0.1.0` |

软件渲染的模拟器在布局与绘制上比真机慢一个数量级，尤其 `preview_render_200kb`；把它当
「相对变化」看，不要当绝对耗时。

## 1. 体积（release APK）

| 项 | 值 |
| --- | --- |
| 文件大小 | **28,289,143 B（26.98 MiB）** |
| 下载大小（估算） | 14,752,568 B |
| classes.dex | 3.35 MB（单 dex） |
| `res/` | 3.53 MB（其中 `res/35.ttf` 即霞鹜文楷子集 **3.37 MB**） |
| `resources.arsc` | 0.44 MB |

按 ABI 的 `libsqlcipher.so`：

| ABI | 体积 |
| --- | --- |
| arm64-v8a | 6.28 MB |
| x86_64 | 6.99 MB |
| x86 | 5.31 MB |
| armeabi-v7a | 3.93 MB |
| 合计 | **22.51 MB** |

四个 ABI 占了包体的绝大部分；个人使用只装 arm64 的机器。

R8 之后最大的 5 项（`unzip -l` 排序）：

| # | 条目 | 体积 |
| --- | --- | --- |
| 1 | `lib/x86_64/libsqlcipher.so` | 7.15 MB |
| 2 | `lib/arm64-v8a/libsqlcipher.so` | 6.42 MB |
| 3 | `lib/x86/libsqlcipher.so` | 5.42 MB |
| 4 | `lib/armeabi-v7a/libsqlcipher.so` | 4.01 MB |
| 5 | `classes.dex` | 3.43 MB |
| 6 | `res/35.ttf`（霞鹜文楷子集） | 3.37 MB |

图标类：`classes.dex` 3.35 MB 里 `material-icons-extended` 的未用图标已被 R8 裁掉（见下节测量），
没有出现「图标库残留几万个类」的情况。

## 2. 冷启动（release 包，`am start -W`，10 次）

| # | TotalTime |
| --- | --- |
| 1 | 4522 ms |
| 2 | 5579 ms |
| 3 | 6102 ms |
| 4 | 5013 ms |
| 5 | 5877 ms |
| 6 | 5461 ms |
| 7 | 4965 ms |
| 8 | 3792 ms |
| 9 | 5474 ms |
| 10 | 3470 ms |

- 中位数 **5013 ms**，p90 **5877 ms**。
- 这是软件渲染模拟器的数字，真机应在数百毫秒级。**没有真机数字，所以 Baseline Profile 这一项
  本次不做**（item 10 的前提是「先看冷启动数字，没有明显收益就不做」，而参考数字不可用于判断）。
- 首次启动包含 Room 建库 + SQLCipher 初始化；第 8、10 次明显偏低，是页缓存变热后的结果。

## 3. 数据库与列表（5,000 条笔记，平均 2 KB，其中 1 条 500 KB）

数据由 `PerfBaselineTest` 生成（只在 androidTest，不进 main），测量逐项取一次预热后的耗时：

| 指标 | 值 | 说明 |
| --- | --- | --- |
| `list_first_load` | **608 ms**（重复测 362 / 534 / 608 ms） | `observeActiveNotes()` 首次发射，含 500 KB 那条的完整正文 |
| `search_one_term` | **379 ms**（356 / 395 / 379） | 单次 `LIKE` 全表扫描 |
| `find_one_note` | 14 ms | 按 id 取一条 |
| `db_reopen_plus_first_query` | **369 ms** | `closeDatabase()` 后重新开库 + 首次查询 |
| `export_5000_plaintext` | **3,557 ms**（1,277 / 1,732 / 3,557） | 12,325,101 B 明文 JSON |
| `export_5000_encrypted` | **5,339 ms**（4,957 / 5,785 / 5,339） | 12,325,160 B，PBKDF2 + AES-GCM |
| `import_5000_plaintext` | **2,933 ms**（3,666 / 2,631 / 2,933） | 合并 5,000 条 |
| `preview_render_200kb` | **41,353 ms** | 200 KB Markdown 的 parse + render + measure + layout，主线程 |

模拟器噪声很大（同一项三次能差 70%），所以每项给了三次的值；报告里对比时用同一次会话内的
「前 / 后」，不要把不同会话的绝对值混着比。

## 4. CI 耗时

| Job | 耗时 |
| --- | --- |
| build（单元测试 + lint + debug APK） | 1.2 分钟 |
| instrumented（API 34 模拟器 + 31 个设备测试） | 3.6 分钟 |
| 合计 | 约 5 分钟 |

来源：<https://github.com/lyrnoxfan/open-notes/actions/runs/39714520846>。

## 5. 待确认的假设（优化前）

1. 列表读全文（item 5）：`list_first_load` 608 ms 里有多少是 500 KB 那条的正文？改成投影后应显著下降。
2. 搜索无防抖（item 6）：单次 379 ms，输入 4 个字就是 4 次；需要把「每键一次」变「停顿后一次」。
3. 预览主线程渲染（item 7）：41 s 的绝对数字不可信，但方向明确——它必须离开主线程。
4. 开库 369 ms > 300 ms，SQLCipher 的 PBKDF2 是主要成本（item 8，【需决策】，只记录）。
