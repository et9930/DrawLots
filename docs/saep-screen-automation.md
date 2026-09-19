# SAEP 声明：屏幕自动化操作允许策略

> SAEP = 屏幕自动化操作声明协议（Screen Automation Operation Declaration Protocol）。
> 本文件声明 AI 助手在本项目上**被允许执行**的屏幕自动化操作范围、边界与收尾责任，供随时审计。
> 适用对象：用户通过 `adb` 显式连接的测试设备（下称"设备A""设备B"），仅限本应用包名 `com.drawlots.app`。

## 1. 允许清单（Allow list）

| 类别 | 具体操作 | 用途 |
| --- | --- | --- |
| 观察 | `screencap` + `adb pull` 截图；`logcat`（含 `-b crash`）、`dumpsys` 读取；`run-as com.drawlots.app cat/ls` 读取**本应用**私有存档 | 验证界面、抓崩溃/ANR、确认应用状态 |
| 交互 | `input tap` / `swipe` / `text` / `keyevent`；`am start` / `force-stop` 启停**本应用** | 走通界面流程、复现问题 |
| 权限 | `pm grant` / `revoke` **本应用**权限 | 验证权限申请与拒绝分支 |
| 安装 | `adb install -r` 本应用（debug 或 release） | 装最新构建做真机验证 |
| 文件 | 推送测试文件到 `/sdcard/Download`、`/data/local/tmp`；经 `run-as` 写本应用私有目录 | 构造测试数据（图片签、预置存档） |

## 2. 破坏性操作（每次执行前必须先声明并获得同意）

- `pm clear com.drawlots.app` —— 清空本应用数据（**会丢失签池与结果**）。
- `adb uninstall com.drawlots.app` —— 卸载（签名不一致时是覆盖安装的唯一途径）。
- `git push --force` / `git rebase` / 改写已推送历史。
- 任何**非本应用**包的操作。

## 3. 明确不做（无需询问也不会做）

- 不读取个人数据（相册/短信/通讯录/其它应用数据）；需要选图时只**点击系统选择器交给用户选**，或使用我推送的测试图片。
- 不尝试解锁安全锁屏（密码/图案/指纹）——做不到，会请用户解锁。
- 不输入、不代持、不要求用户提供任何密钥、口令或 token；签名口令只留在用户本机 `keystore.properties`（不入库）。
- 不修改系统设置、不 root、不绕过应用沙箱去读其它包。

## 4. 状态变更与收尾责任（如实记录）

| 项 | 本会话实际发生 | 收尾处理 |
| --- | --- | --- |
| 截图/日志 | 落在工作区 `.verify/`（已在 `.gitignore`） | 每轮结束删除 ✓ |
| 测试数据 | 多次 `pm clear`、写入 `/sdcard/Download/*.png`、`/data/local/tmp/*`、预置存档与图片 | 已删除设备上的临时文件 ✓；`pm clear` 后的数据按新安装态保留 |
| 权限 | 为验证联机/扫码，曾 `pm grant`：`CAMERA`、`BLUETOOTH_SCAN`、`BLUETOOTH_ADVERTISE`、`BLUETOOTH_CONNECT` | **未 revoke**（应用正常工作需要）；如需恢复原状，可执行 `pm revoke` 后重新申请 |
| 安装形态 | 设备A 为签名 release 包、设备B 为 debug 包 | 保持；如需统一，先卸载再装（签名不同无法覆盖） |
| 未授权动作 | **无** | — |

## 5. 声明与确认

- 以上允许清单**由用户显式声明**后生效；扩大范围（新设备、新包名、新增破坏性动作）需重新声明。
- 每次执行破坏性操作前，助手必须在对话中说明"将要做什么、会丢什么"，得到同意后才执行。
- 本声明不覆盖仓库操作策略（后者见 `README` 的构建/发布说明与 CI 配置）。
