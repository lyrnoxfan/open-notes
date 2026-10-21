# 安全存储说明

## 为什么用包裹而不是派生

由设备标识派生的口令是可复现的：能读到那些标识的一方就能重新派生出密钥，加密也就失去意义。
这里的口令是 `SecureRandom` 生成的 32 字节，只能通过 Android Keystore 中的不可导出密钥取得。

## 各道防护对应的失败

| 防护 | 阻止的失败 |
| --- | --- |
| `DatabaseFileGuard.requireNoPlaintextDatabase` | 旧版本或错误构建写出过明文库；静默采用它等于以明文读取笔记 |
| `DatabaseFileGuard.requireEncryptedDatabase` | Room 构建器漏挂 SQLCipher factory 时悄悄退化成明文 |
| `DatabaseKeyManager` 往返校验 | 用一个从未持久化的口令建库，得到一个永远打不开的数据库 |
| `WrappedSecret` 版本字节 | 将来更换封装格式时把旧密文当成新格式解析 |
| `EncryptedDatabaseProvider` 的密钥缺失分支 | 密钥丢失时默默新建密钥、重建空库，用户数据看似「消失」 |
| 备份排除规则 | 密文与包裹密钥一起被带离设备 |

## 已知限制：密钥失效

Keystore 条目被销毁后包裹口令不可恢复，现有数据库无法打开。当前行为是抛出
`DatabaseKeyException.KeyUnavailable`，界面显示错误并提示通过备份恢复，不自动重建空库、不删除
原文件。完整的恢复/导出流程属于阶段五（备份与恢复）。

## 日志

应用不写任何日志，没有 `android.util.Log` 调用。异常信息里不含口令、标题或正文：密钥相关异常只
说明 Keystore 别名缺失或认证失败，不含密钥内容。
