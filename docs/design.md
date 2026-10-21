# 界面设计规格

这份文件是界面的唯一依据：改界面先改这里，再改代码。颜色只在
`ui/theme/Color.kt` 的 `DesignTokens` 里定义，字号只在 `ui/theme/Type.kt` 里定义，
页面里出现新的色值或字号就是回归。

方案 §4.1 定的基调是「博客的暖纸系配色，温暖、克制、精致的编辑器风格」。参考站
blog.lyrnox.com 的浅色底是 `#F1E7DA`、强调是 `#A13F04`，本规格沿用这两个值。

## 1. 色板

浅色与深色各一套，成对使用。深色不用纯黑，底是深暖棕。

| Token | 浅色 | 深色 | 用途 |
| --- | --- | --- | --- |
| `background` | `#F1E7DA` | `#1C1410` | 页面底色，暖纸 |
| `surface` | `#F8F1E7` | `#251A13` | 卡片、输入面、对话框 |
| `surfaceMuted` | `#EADCCC` | `#30231A` | 代码底、徽标底、次级容器 |
| `textPrimary` | `#3A2A1E` | `#EDE0D2` | 正文、标题 |
| `textSecondary` | `#6F5C4B` | `#B09A87` | 时间戳、提示、占位、次要说明 |
| `accent` | `#A13F04` | `#E2894A` | 可点击的文字与图标、当前状态 |
| `onAccent` | `#FDF6EE` | `#2B1607` | 强调色底上的文字 |
| `accentContainer` | `#F3D9C4` | `#4A2A12` | 选中胶囊的底 |
| `onAccentContainer` | `#3E1A02` | `#F3D9C4` | 选中胶囊上的文字 |
| `divider` | `#D8C8B4` | `#3C2C21` | 1dp 分割线（装饰） |
| `dividerStrong` | `#C6B29A` | `#4A382B` | 输入框边界等需要被看见的细线 |
| `error` | `#8C2F1F` | `#E59A88` | 失败、永久删除 |

规则：

- 强调色只表示「可点击」与「当前状态」。不拿它做大面积底色，不用它装饰。
- 不用渐变、玻璃拟态、大面积模糊、色块分区。层级靠留白、字号、颜色深浅和 1dp 细线。
- 页面底色与卡片底色只差一点点（浅色下 1.16:1），这是有意的：卡片靠 1dp 细线浮起来，
  不靠明度反差或阴影。

## 2. 字体

### 2.1 两个家族

| 家族 | 字体 | 用在哪 |
| --- | --- | --- |
| 衬线 `Serif` | 打包的霞鹜文楷 Regular（子集化） | 笔记标题、正文、Markdown 预览、页面标题、应用名 |
| 无衬线 `Sans` | 系统默认 | 按钮、胶囊、时间戳、保存状态、字段标签、对话框按钮 |

为什么小字用系统无衬线：12–13sp 上楷体的笔画收放会糊成一团，尤其 ColorOS 这类会在小字号
上做字重补偿的系统；时间戳、状态这类字要的是「立刻读清」而不是「有纸感」。参考站本身也是
正文衬线、导航与元信息无衬线的分工。

为什么正文用霞鹜文楷而不是系统衬线：ColorOS 部分版本的系统衬线对中文回退成黑体，
`FontFamily.Serif` 在这类机器上等于没有衬线（见 `docs/design-report.md` 的实机对比记录）。
打包子集可以保证每台机器上都是同一套字形。方案 §4.2 说「需要衬线风格时优先测试系统衬线，
确实有必要才打包」——这条实测结论就是「有必要」。

缺字由系统回退：字体是子集，GB2312 之外的生僻字、emoji 会落到系统字体，不会出现豆腐块。

体积（实测，release 包）：子集文件 3.31 MB，compress 后 APK 从 24.91 MB 涨到 26.94 MB，
增量 2.04 MB。字体文件本身超出「≤ 2.5 MB」的预算 0.81 MB，但安装包增量在「≤ 3 MB」之内。
取舍写在 `docs/design-report.md`：`scripts/subset-font.sh` 支持只截一级字（1.67 MB），一行
改动即可切回去。

打包只有 Regular 一个字重。标题要的 Medium 由平台合成加粗——为单个字重再加 3.3 MB 不值得。

### 2.2 字号表

行距按中文的 1.7–1.8 倍给。标题字重用 Medium，不用 SemiBold——纸感要的是稳，不是粗。

| 样式 | 家族 | 字重 | 字号 | 行距 | 用在哪 |
| --- | --- | --- | --- | --- | --- |
| `displayLarge` | Serif | Medium | 40sp | 52sp | 保留（未使用） |
| `displayMedium` | Serif | Medium | 32sp | 44sp | 保留（未使用） |
| `displaySmall` | Serif | Medium | 28sp | 38sp | 保留（未使用） |
| `headlineLarge` | Serif | Medium | 26sp | 36sp | 保留（未使用） |
| `headlineMedium` | Serif | Medium | 24sp | 34sp | 保留（未使用） |
| `headlineSmall` | Serif | Medium | 22sp | 32sp | 页面标题、应用名、编辑页标题输入 |
| `titleLarge` | Serif | Medium | 20sp | 30sp | 对话框标题 |
| `titleMedium` | Serif | Medium | 17sp | 26sp | 列表里的笔记标题、分区标题 |
| `titleSmall` | Serif | Medium | 15sp | 22sp | 卡片内小标题 |
| `bodyLarge` | Serif | Normal | 17sp | 30sp | 编辑正文、预览正文（1.76 倍） |
| `bodyMedium` | Serif | Normal | 15sp | 26sp | 摘要、说明段落 |
| `bodySmall` | Serif | Normal | 13sp | 21sp | 对话框里的补充说明 |
| `labelLarge` | Sans | Medium | 14sp | 20sp | 按钮文字 |
| `labelMedium` | Sans | Normal | 13sp | 18sp | 胶囊、字段标签 |
| `labelSmall` | Sans | Normal | 12sp | 17sp | 时间戳、保存状态、计数 |

预览用的 `TextView` 必须用同一个 Typeface 与同一组字号行距，否则「编辑」和「预览」看起来
是两份文档。

## 3. 间距

刻度只有 6 个值，页面里用它们，不要出现 7dp、13dp 这种。

| 值 | 用途 |
| --- | --- |
| 4dp | 图标与文字之间、紧邻元素 |
| 8dp | 同一组内元素间距 |
| 12dp | 卡片内行距、胶囊间距 |
| 16dp | 列表项上下内边距、卡片内边距的紧凑档 |
| 20dp | 页面左右边距、卡片内边距（默认） |
| 24dp | 编辑页正文左右边距、区块之间 |
| 32dp | 空状态与页边（唯一超出刻度的大值） |

版心：内容最大宽度 680dp，居中。手机上是满宽减边距，横屏与平板上就是一条居中的纸带，
和参考站的 680px 版心同构。

## 4. 圆角

| Token | 值 | 用途 |
| --- | --- | --- |
| `extraSmall` | 6dp | 行内代码、小徽标 |
| `small` | 8dp | 输入面 |
| `medium` | 12dp | 卡片、对话框 |
| `large` | 16dp | 大容器（少用） |
| 胶囊 | 50% | 标签胶囊、筛选胶囊、按钮 |

按钮与胶囊用胶囊形，卡片用 12dp。只有一个例外：FAB 是圆形。

## 5. 分割线与阴影

- 分割线 1dp，颜色 `divider`（浅色 1.34:1，深色 1.36:1）。它只负责「分开」，不负责「强调」。
- 需要被看见的边界（输入框）用 `dividerStrong`，1dp。
- **不使用阴影**。卡片、按钮、FAB、对话框一律 `elevation = 0`，浮起来靠底色差与细线。
  Material 默认的 elevation 会画出灰色投影，在这套暖纸配色上看起来像脏印子。

## 6. 图标

- 统一用 Material Outlined 一套，不混实心与线性。
- 尺寸 24dp（顶栏、列表动作）、20dp（胶囊内、行内）。
- 颜色：可点击时 `onBackground` 或 `accent`（当前状态），不可点击时 `onSurfaceVariant`。
- 图标不单独承担语义：备份状态、错误提示都是「图标 + 文字」，色盲用户不靠颜色也能读懂。
- 每个图标都要有 `contentDescription` 或明确的 `contentDescription = null`（纯装饰时）。

## 7. 动效

- 只允许两档时长：150ms（交叉淡入淡出、状态切换）、180ms（列表项进出）。
- 只用 `fadeIn`/`fadeOut`、`Crossfade`、`animateItem`。不用弹簧、不用视差、不用共享元素转场。
- 系统开启「移除动画」时，这些淡入淡出会被 Compose 自动跳过，不需要额外判断。

## 8. 对比度

由 `scripts/contrast.py` 直接从 `Color.kt` 读值计算，改色后必须重跑：

```bash
python3 scripts/contrast.py
```

正文要求 ≥ 7:1，次要文字与强调色文字 ≥ 4.5:1。分割线是装饰，要求 ≥ 1.3:1；输入框边界
≥ 1.6:1。

## Light

| 前景 | 背景 | 用途 | 色值 | 对比度 | 要求 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| `textPrimary` | `background` | 正文 / 页面底色 | #3A2A1E / #F1E7DA | 11.23:1 | ≥ 7.0:1 | ✅ |
| `textPrimary` | `surface` | 正文 / 卡片底 | #3A2A1E / #F8F1E7 | 12.24:1 | ≥ 7.0:1 | ✅ |
| `textPrimary` | `surfaceMuted` | 正文 / 弱底 | #3A2A1E / #EADCCC | 10.20:1 | ≥ 7.0:1 | ✅ |
| `textSecondary` | `background` | 次要文字 / 页面底色 | #6F5C4B / #F1E7DA | 5.19:1 | ≥ 4.5:1 | ✅ |
| `textSecondary` | `surface` | 次要文字 / 卡片底 | #6F5C4B / #F8F1E7 | 5.66:1 | ≥ 4.5:1 | ✅ |
| `accent` | `background` | 强调文字 / 页面底色 | #A13F04 / #F1E7DA | 5.32:1 | ≥ 4.5:1 | ✅ |
| `accent` | `surface` | 强调文字 / 卡片底 | #A13F04 / #F8F1E7 | 5.80:1 | ≥ 4.5:1 | ✅ |
| `onAccent` | `accent` | 强调按钮上的文字 | #FDF6EE / #A13F04 | 6.07:1 | ≥ 4.5:1 | ✅ |
| `onAccentContainer` | `accentContainer` | 选中胶囊上的文字 | #3E1A02 / #F3D9C4 | 11.48:1 | ≥ 4.5:1 | ✅ |
| `error` | `background` | 错误文字 / 页面底色 | #8C2F1F / #F1E7DA | 6.76:1 | ≥ 4.5:1 | ✅ |
| `error` | `surface` | 错误文字 / 卡片底 | #8C2F1F / #F8F1E7 | 7.37:1 | ≥ 4.5:1 | ✅ |
| `divider` | `background` | 分割线 / 页面底色（装饰） | #D8C8B4 / #F1E7DA | 1.34:1 | ≥ 1.3:1 | ✅ |
| `dividerStrong` | `background` | 输入框边界 / 页面底色 | #C6B29A / #F1E7DA | 1.68:1 | ≥ 1.6:1 | ✅ |

## Dark

| 前景 | 背景 | 用途 | 色值 | 对比度 | 要求 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| `textPrimary` | `background` | 正文 / 页面底色 | #EDE0D2 / #1C1410 | 14.00:1 | ≥ 7.0:1 | ✅ |
| `textPrimary` | `surface` | 正文 / 卡片底 | #EDE0D2 / #251A13 | 13.10:1 | ≥ 7.0:1 | ✅ |
| `textPrimary` | `surfaceMuted` | 正文 / 弱底 | #EDE0D2 / #30231A | 11.73:1 | ≥ 7.0:1 | ✅ |
| `textSecondary` | `background` | 次要文字 / 页面底色 | #B09A87 / #1C1410 | 6.76:1 | ≥ 4.5:1 | ✅ |
| `textSecondary` | `surface` | 次要文字 / 卡片底 | #B09A87 / #251A13 | 6.33:1 | ≥ 4.5:1 | ✅ |
| `accent` | `background` | 强调文字 / 页面底色 | #E2894A / #1C1410 | 6.84:1 | ≥ 4.5:1 | ✅ |
| `accent` | `surface` | 强调文字 / 卡片底 | #E2894A / #251A13 | 6.40:1 | ≥ 4.5:1 | ✅ |
| `onAccent` | `accent` | 强调按钮上的文字 | #2B1607 / #E2894A | 6.48:1 | ≥ 4.5:1 | ✅ |
| `onAccentContainer` | `accentContainer` | 选中胶囊上的文字 | #F3D9C4 / #4A2A12 | 9.52:1 | ≥ 4.5:1 | ✅ |
| `error` | `background` | 错误文字 / 页面底色 | #E59A88 / #1C1410 | 8.05:1 | ≥ 4.5:1 | ✅ |
| `error` | `surface` | 错误文字 / 卡片底 | #E59A88 / #251A13 | 7.53:1 | ≥ 4.5:1 | ✅ |
| `divider` | `background` | 分割线 / 页面底色（装饰） | #3C2C21 / #1C1410 | 1.36:1 | ≥ 1.3:1 | ✅ |
| `dividerStrong` | `background` | 输入框边界 / 页面底色 | #4A382B / #1C1410 | 1.64:1 | ≥ 1.6:1 | ✅ |

全部达标

## 9. 无障碍

- 触控目标不小于 48dp，宁可加大内边距也不缩小可点区域。
- 字号支持到 fontScale 2.0，360dp 宽度下不截断、不重叠、按钮不出屏。
- 滑动删除必须同时提供语义动作（`customActions`），键盘与 TalkBack 用户不能因为藏起删除
  图标就失去入口。
