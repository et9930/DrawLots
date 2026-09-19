# 多机联机（房间）设计规格

> 状态：**已实现**（2026-09-19）。实现与本文档的 4 处偏差、验证结果与残余风险见文末「实现结果」一节。
> 适用版本：DrawLots 1.1（单机功能保持 1.0 行为不变）
> 决策来源：与用户逐项确认（传输、权威模型、加入方式、图片、权限、署名）

---

## 1. 目标与非目标

**目标**：多台安卓手机联机做同一场抓阄。一台手机当房主（权威），其他人通过**扫二维码 / 局域网自动发现 / 手输房间码**加入；所有人实时看到同一份签池和抽签结果；每台手机可以给自己起名字；**抽出的签自动署名抽签人**。

**非目标（v1 明确不做）**：
- 房主转移 / 状态接管（房主退出即房间结束）
- 增量补帧、操作日志（只做「全量状态 + revision」与落后重同步）
- 公网/远程联机、跨网段穿透（仅同一局域网或蓝牙直连范围）
- 房间状态服务端持久化（房主进程结束即结束）
- 房主侧前台服务（v1 只用 `FLAG_KEEP_SCREEN_ON` 降低被冻结概率；常驻通知的前台服务留待后续）
- 聊天、语音、投票
- 加入方细粒度权限（只有一个 `allowEdit` 开关）
- iOS / 桌面端

---

## 2. 已定决策与理由

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 传输 | 局域网 + 蓝牙**都做**，抽象成可替换传输 | 用户要求两者都要；局域网可端到端验证，蓝牙覆盖无网场景 |
| 权威模型 | **房主权威星型**：房主持有真实状态，其他人发请求 | 无冲突合并、无两套真相；代价是房主退出即结束（已列入非目标） |
| 同步方式 | **全量状态广播 + `revision`** | 幂等、自愈；几百条结果也只有几 KB（图片走独立通道）；不做增量补帧 |
| 加入方式 | 二维码显示 + **扫码** + **UDP 自动发现** + 手输房间码兜底 | 广播可能被 AP 隔离拦掉，扫码/房间码必须first-class 兜底 |
| 图片签 | **按需拉取**（要显示时才向房主取，本地缓存） | 广播带图会让每帧上百 KB；按需拉取流量可控且功能完整 |
| 加入方权限 | 默认只有**抽签**和**写备注**；**撤销/放回**由房主开关 `allowRecover` 控制（默认关）；**签池编辑**由 `allowEdit` 控制（默认关）；**重置/清空**永远仅房主 | 落入「默认最小权限」：聚会现场不会被别人误清结果或抽走的签；需要协作时房主再放权 |
| 署名 | **独立字段 `drawnBy`**，不占用手写备注 | 自动化内容与用户自由文本互不干扰 |

---

## 3. 术语

| 术语 | 含义 |
| --- | --- |
| 房间（Room） | 一次联机会话，由房主创建，有房间名与 6 位房间码 |
| 房主（Host） | 创建房间的设备，持有权威 `DrawSession` |
| 成员（Client） | 加入房间的设备，持有 `DrawSession` 的只读副本 |
| revision | 房主状态的单调递增版本号，每次成功应用变更 +1 |
| 离线状态 | 单机模式下的本地签池与结果（进入房间前的状态） |

**注意**：本设计刻意不引入第二个状态源。房主的 `DrawSession` 是唯一真相；成员的 `DrawSession` 是副本，只由房主的广播覆盖。

---

## 4. 架构与模块边界

新增包 `com.drawlots.app.room`（协议与传输与 Android 解耦，可纯 JVM 测试）：

```
room/
├─ RoomProtocol.kt        帧编解码 + 消息数据类（纯 Kotlin，org.json）
├─ RoomTransport.kt       传输接口：start/stop/sendFrame/收到的帧流/连接事件
├─ LanTransport.kt        TCP 服务端与客户端（Java socket）
├─ LanDiscovery.kt        UDP 广播信标发送与监听（仅局域网）
├─ BluetoothTransport.kt  BLE GATT 服务端/客户端 + MTU 分片
├─ RoomHost.kt            收请求 → 校验 → 应用到 DrawSession → 广播 state
├─ RoomClient.kt          连接/重连、装载 state、把本地操作变成 request
├─ RoomController.kt      房间生命周期与对外状态（被 AppViewModel 持有）
├─ RoomCode.kt            房间码生成/校验 + 二维码 URI 编解码（纯 Kotlin）
└─ QrCode.kt              用 zxing core 生成二维码位图（Android 薄封装）

ui/room/
├─ RoomSheet.kt           建房 / 加入面板（名称、房间名、传输、allowEdit）
├─ RoomLobby.kt           二维码 + 房间码 + 成员列表 + 结束房间
├─ QrScannerScreen.kt     CameraX + zxing 扫码页
└─ RoomBanner.kt          房间状态条（房主/成员、人数、连接状态）
```

复用（不新建重复 owner）：

| 现有 owner | 复用方式 |
| --- | --- |
| `DrawSession` | 房主=权威状态；成员=副本（同一套抽签/放回/备注逻辑） |
| `PoolCodec` | 状态负载序列化；拆出 `JSONObject` 版本供协议嵌套使用（schema 仍只有一个 owner） |
| `PoolStore` | 成员副本与离线状态都用它持久化 |
| `LocalImage` | 增加可选远端取图钩子（本地文件不存在时向房间请求） |
| `AppViewModel` | 新增 `RoomController`；动作按角色分派（本地执行 or 发请求） |

---

## 5. 协议规格

### 5.1 帧格式

```
[4 字节大端长度 N][1 字节帧类型][N-1 字节帧体]
帧类型：0x01 = JSON（UTF-8），0x02 = 二进制分片
```
- 单帧上限：JSON 1 MiB，二进制分片 4 KiB（BLE 取 `min(4096, mtu-3)`）。
- `PROTOCOL_VERSION = 1`；版本不一致 → 房主回 `error{code="version_mismatch"}` 并断开，客户端提示「版本不一致，请两台手机安装同一版本」。

### 5.2 消息

| 方向 | 消息 | 字段 |
| --- | --- | --- |
| C→H | `hello` | `protocolVersion:Int, deviceId:String, name:String` |
| H→C | `welcome` | `roomName, hostName, allowRecover:Bool, allowEdit:Bool, revision:Long, members:[String], state:Object` |
| H→C | `state` | `revision:Long, allowRecover:Bool, allowEdit:Bool, members:[String], state:Object` |
| C→H | `request` | `id:String, action:Object` |
| H→C | `ack` | `id:String, revision:Long` |
| H→C | `error` | `id:String?, code:String, message:String` |
| C→H | `resync` | —（客户端发现 revision 落后或收到未知 revision） |
| C→H | `imageRequest` | `id:String, imageKey:String` |
| H→C | `imageBegin` | `id:String, imageKey:String, size:Int` |
| 双向 | `imageChunk`（0x02 帧） | `id:String, offset:Int` + 原始字节 |
| 双向 | `imageEnd` | `id:String` |
| C→H | `ping` | `ts:Long` |
| H→C | `pong` | `ts:Long` |
| H→C | `bye` | `reason:String` |

`action` 取值：

| action | 字段 | 谁能发 |
| --- | --- | --- |
| `draw` | `count:Int` | 所有人（房主应用时写入抽签人署名） |
| `setNote` | `seq:Int, note:String` | 所有人 |
| `undo` | — | 仅 `allowRecover=true` |
| `putBack` | `seq:Int` | 仅 `allowRecover=true` |
| `resetPool` | — | **仅房主**（清空全房间结果属于破坏性操作，任何开关都不对成员开放） |
| `setMode` | `mode:String` | **仅房主**（放回/不放回是房间共享规则，不给成员改） |
| `addLot` / `updateLot` / `removeLot` / `setQuantity` / `clearPool` | 对应 `Lot` 字段 | 仅 `allowEdit=true` |

错误码：`version_mismatch` / `not_allowed` / `bad_request` / `not_found` / `too_large` / `busy`。

### 5.3 状态负载

- `state` 直接嵌套 `PoolCodec` 的 JSON 对象（`version/lots/results/mode`），不再二次编码成字符串；`PoolCodec` 增加 `encodeToJson(snapshot): JSONObject` 与 `decodeFromJson(obj): PoolSnapshot`，原字符串 API 保留为薄包装。
- `revision` 由房主维护，不进 `PoolCodec`。

### 5.4 署名规则

- `draw` 请求由房主应用时写入 `drawnBy = 请求方的 name`（裁剪空白、截断 12 字、空则「某位」）。
- 房主自己在房间里抽签，`drawnBy = 房主自己的名字`。
- 实现方式：`DrawSession.draw(count: Int = 1, drawnBy: String = "")` / `drawOne(drawnBy: String = "")`；
  单机路径不传（得到空串），房间路径由房主传入。**不引入 session 级的隐藏「当前抽签人」状态**。
- **单机模式不写 `drawnBy`**（避免离线抽签也挂一个「@我」）。
- 现有手写备注 `note` 与 `drawnBy` 互不影响。
- 成员内部用 `deviceId` 标识，展示用 `name`；允许重名（列表里就显示两行同名）。

---

## 6. 房间生命周期

### 6.1 建房（房主）
1. 面板填房间名（默认「XX 的抽签」）+ 传输方式（局域网/蓝牙）+ 两个放权开关：
   `允许其他人放回/撤销`（`allowRecover`，默认**关**）、`允许其他人编辑签池`（`allowEdit`，默认**关**）。
2. 局域网：先取本机 **Wi-Fi 接口的 IPv4 地址**（从 `ConnectivityManager` 的当前网络 `LinkProperties` 里筛出 Wi-Fi、非回环地址；拿不到就提示「请先连接 Wi-Fi 或开热点」），再绑定临时 TCP 端口（默认 47001，占用则顺延），启动 UDP 信标（固定发现端口 **47002**，每秒广播；信标只在有房间时发送）。
3. 蓝牙：创建 GATT Server，用随机 `serviceUuid`（房间内唯一）开始广播。
4. 生成 6 位房间码，界面展示二维码 + 房间码 + 成员列表。
5. 房主的一切本地操作照常直接应用到 `DrawSession`，然后广播（`revision++`）。
6. 建房期间活动窗口加 `FLAG_KEEP_SCREEN_ON`（免权限），避免房主息屏后被系统冻结导致房间中断；停止房间时移除。v1 不做前台服务（列入非目标）。

### 6.2 加入（成员）
1. 扫码：解析 `drawlots://v1?...` → 局域网填 `host:port`，蓝牙用 `serviceUuid` 扫描连接。
2. 自动发现：**只在「加入房间」面板打开期间**监听 47002（同时发一次广播探测），列出房间（房主名/房间名/房间码），3 s 没有信标就从列表移除；点选加入。
3. 手输房间码：先在已发现列表里按码匹配；找不到则允许补填 `IP:端口`。
4. 连接成功后发 `hello` → 收 `welcome` → **暂存离线状态**（`PoolStore` 快照写入 `offline_backup` 键）→ 用 `welcome.state` 覆盖本地副本。
5. 失败/被拒：提示原因，保持在面板（不改动本地状态）。

### 6.3 心跳与超时
- 成员每 5 s 发 `ping`，房主回 `pong` 并更新 `lastSeen`。
- 成员 `lastSeen` 超过 15 s 未更新 → 房主从成员列表移除并广播新成员列表。
- 成员侧连续 3 次 `ping` 无 `pong`（约 15 s）视为断线。

### 6.4 重连
- 成员断线后自动重连（退避 1s/2s/4s/8s，上限 15s），重连即重新 `hello`，用 `welcome` 全量覆盖副本。
- 局域网换网/房主换 IP → 重连失败后提示「重新扫码或输入房间码」。
- 房主侧成员断线不影响房间；成员重新连接后按新连接处理。

### 6.5 退出与结束
- 成员主动退出 / 房间界面关闭：断开传输 → **恢复离线状态**（用 `offline_backup` 覆盖副本）→ 清除备份。
- 房主结束房间：广播 `bye{reason="host_closed"}` → 停止传输 → 房主的 `DrawSession` 保留（就是本地状态）。
- 成员收到 `bye`：显示「房间已结束」→ 恢复离线状态。
- **进程重启**：房间不跨进程存活。App 启动时若发现 `offline_backup` 存在，视为「上次房间已结束」，直接恢复离线状态并清除备份；否则沿用当前持久化状态。

### 6.6 并发
- 房主在单个串行队列里处理请求（一次一条，应用完再广播），因此不存在并发写入竞态。
- `revision` 单调递增；成员收到 `revision <= 本地` 的 `state` 直接忽略（幂等）。

---

## 7. 权限与兼容

| 权限 | 用途 | 申请时机 |
| --- | --- | --- |
| `INTERNET` | TCP/UDP | 安装即授予（普通权限） |
| `ACCESS_NETWORK_STATE` | 判断是否在 Wi-Fi | 安装即授予 |
| `CAMERA` | 扫二维码 | 进入扫码页时申请；拒绝则只能手输房间码 |
| `BLUETOOTH_ADVERTISE` | 蓝牙建房（API 31+） | 选择蓝牙建房时申请 |
| `BLUETOOTH_CONNECT` | 蓝牙连接与名称（API 31+） | 蓝牙建房/加入时申请 |
| `BLUETOOTH_SCAN`（`neverForLocation`） | 扫描蓝牙房间（API 31+） | 蓝牙加入时申请 |

- **蓝牙联机仅 Android 12+（API 31+）**：更低版本 BLE 扫描需要 `ACCESS_FINE_LOCATION`，与「零权限」现状冲突过大；老设备走局域网。
- `uses-feature android:hardware.bluetooth_le` 声明为 `required="false"`；相机同理。
- **不进房间时行为与 1.0 完全一致**：不申请任何运行时权限、不发起网络请求。运行时权限只在对应入口申请。
- 旧存档（无 `drawnBy`）读入为空串；存档版本升到 3；向前兼容（多出的键被旧版本忽略）。

---

## 8. 数据模型变更

1. `DrawRecord` 新增 `drawnBy: String = ""`（结构化署名）。
2. `PoolCodec` 版本 3：`results[].drawnBy`；解码缺字段 → `""`。
3. `PoolStore` 新增 `offline_backup` 键，只在成员加入房间时写入、退出时读取并清除。
4. 导出文本：`1. 张三（小明抽到）`；若有手写备注则 `1. 张三（小明抽到）—— 备注内容`（`drawnBy` 与 `note` 都在时两段都出）。
5. 卡片展示：`drawnBy` 显示为 `@小明` 小标（`tertiaryContainer` 配色），手写备注仍是独立的 `secondaryContainer` 小条，两者可同时出现（上下两行）。

### 8.1 新增依赖（已探针验证，无需升级 AGP / compileSdk）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.google.zxing:core` | 3.5.4 | 纯 Java，二维码生成 + 从 YUV/位图解码；无 AAR 元数据约束 |
| `androidx.camera:camera-core` / `camera-camera2` / `camera-lifecycle` / `camera-view` | 1.6.0 | AAR 元数据 `minCompileSdk=36 / minAGP=8.9.1`，与当前 `compileSdk 36 + AGP 8.13.2` 兼容（已实测确认） |

不引入 AppCompat、不引入 Google Play 服务（扫码页用 CameraX 预览 + zxing 自己解码）。

---

## 9. 图片按需拉取与缓存

- `imageKey = SHA-256(房主侧图片绝对路径)`（路径在房主设备上稳定，导入后不再变化）。
- 成员显示某张图时：本地 `filesDir/room-images/<imageKey>.jpg` 命中 → 直接解码；未命中 → 发 `imageRequest`。
- 房主收到请求：读 `files/lots` 下对应文件（≤5 MiB），回 `imageBegin` + 分片 + `imageEnd`；文件不存在回 `error{code="not_found"}`。
- 成员写入 `room-images/` 后交给现有 `LocalImage` 解码；缓存上限 50 MiB，按最后访问时间淘汰。
- 传输失败/超时（局域网 10 s、蓝牙 60 s）→ 显示占位图 + 可点重试。
- 图片不走状态广播，因此签池再大也不会拖慢同步。

---

## 10. UI 变更清单

1. **顶栏「联机」按钮**（未进房间时）→ `RoomSheet`：
   - 我的名称（默认设备型号，可改）
   - 创建房间：房间名 / 传输方式（局域网·蓝牙）/ 允许其他人放回·撤销（默认关）/ 允许其他人编辑签池（默认关）
   - 加入房间：扫码 / 房间列表（自动发现）/ 手输房间码
2. **`RoomLobby`（房主）**：二维码 + 房间码 + 成员列表 + 传输方式 + 结束房间。
3. **`RoomBanner`（房间中，常驻顶部）**：房间名 · 房主/成员 · N 人 · 连接状态（已连接/重连中/已断开）+ 退出。
4. **抽签页**：成员模式下按钮文案带抽签人名（如「抽 1 个签（小明）」）；点抽签 → 本地滚动动画 0.7 s → 发请求 → 收到广播落定（等待期间显示「等待房主…」，5 s 超时提示）；结果卡片显示 `@名字`；**成员在 `allowRecover=false` 时看不到「撤销」，结果弹窗里也没有「放回这个签」**，结果区顶部提示「房主未开放放回」；成员永远看不到「重置」。
5. **签池页**：`allowEdit=false` 时成员隐藏添加/编辑/删除/数量控件，顶部提示「房主未开放编辑签池」。
6. **扫码页**：取景框 + 提示 + 「从相册选二维码」兜底；相机权限被拒时直接给手输房间码入口。
7. **未进房间**：完全不出现房间相关 UI，除顶栏入口。

---

## 11. 风险与边界

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 路由器开启 AP 隔离 | 局域网连不上 | 扫码/房间码兜底 + 提示「请连同一个 Wi-Fi 或让房主开热点」 |
| 系统后台限制切断 socket | 房间中途掉线 | 心跳 + 自动重连；UI 明确显示连接状态 |
| BLE 带宽低（几十 KB/s） | 蓝牙下图片传输慢 | 进度提示；建议蓝牙房间以文字签为主 |
| GATT Server 并发连接上限 | 超过约 6 台不稳定 | 文档写明建议 ≤6 台；加入失败给出提示 |
| 房间无鉴权（只有房间码/二维码） | 同网段的人可加入 | 已接受（聚会工具）；后续可选房间 PIN |
| **蓝牙双机对连无法在本环境验证** | 蓝牙链路存在未知缺陷 | 协议层用假传输做 JVM 测试；真机只验证建房/广播/权限；交付时明确标注 |

---

## 12. 验收标准（可观察、可验证）

| # | 验收项 | 验证方式 |
| --- | --- | --- |
| 1 | 不进房间时行为与 1.0 一致，不申请运行时权限、不发网络请求 | 既有 40 个测试全绿 + 真机确认无权限弹窗 |
| 2 | 建房后显示二维码、房间码、成员列表；二维码内容可解析出 host/port/房间码且与界面一致 | 截图 + 电脑侧解码二维码比对 |
| 3 | JVM 协议客户端 `hello` 后收到 `welcome`，其 `state` 与房主一致；房主成员列表出现该名称 | 回环集成测试 + 真机（电脑客户端加入手机房间） |
| 4 | 客户端 `draw` → 房主广播 → 手机界面出现新结果且带 `@客户端名`，双方 `revision` 一致 | 真机 + 回环测试 |
| 5 | `draw` 产生的记录 `drawnBy` = 抽签人名称；导出文本含「（XX抽到）」 | 单元测试 + 真机分享文本 |
| 6 | `allowEdit=false` 时客户端的 `editPool` 请求被拒（`error not_allowed`）且房主状态不变；开启后生效 | 单元测试 + 真机 |
| 7 | 客户端请求图片 → 收到分片 → 落盘到 `room-images/` 并可显示 | 回环测试 + 真机（电脑客户端取图） |
| 8 | 断开后自动重连并全量同步，`revision` 追平房主 | 单元测试（假传输）+ 真机（关 Wi-Fi 再开） |
| 9 | 成员静默 15 s 后从房主成员列表移除 | 假时钟单元测试 |
| 10 | 房主结束房间 → 成员收到 `bye`、房间 UI 关闭、**恢复房间前的离线签池** | 单元测试 + 真机 |
| 11 | 旧存档（无 `drawnBy`）能读能写；v3 往返一致 | 单元测试 |
| 12 | 蓝牙：真机可建房（GATT 广播）、可打开扫描页并正确申请权限；分片/重组由假传输测试覆盖 | 真机（单机）+ 单元测试；**双机对连未验证** |
| 13 | 成员的 `resetPool` 请求被拒（`error not_allowed`），房主自己的「重置」正常 | 单元测试 + 真机 |
| 14 | 进程重启后不残留「半房间」状态：上次加入过房间 → 重启后显示自己的离线签池 | 单元测试（`offline_backup` 规则）+ 真机杀进程重启 |
| 15 | `allowRecover=false` 时成员的 `undo`/`putBack` 被拒且房主状态不变、成员 UI 不显示这两个入口；房主打开开关后立即生效 | 单元测试 + 真机 |

---

## 13. 验证方案

1. **纯 JVM**：帧编解码（含坏帧/超长帧/版本不符）、房间码与二维码 URI 解析、`RoomHost`（假传输 + 假时钟：署名写入、越权拒绝、revision 递增、成员超时、落后重同步）、`RoomClient`（副本一致性、请求超时）。
2. **回环集成**：同一 JVM 内真 `ServerSocket` ↔ 真 socket 客户端跑完整流程（建房/加入/抽签/署名/图片/退出）。
3. **真机**：手机当房主开局域网房间 + 电脑跑协议客户端 → 验证实时同步、`@客户端名` 署名、成员列表、图片拉取、UDP 发现列表、二维码内容解码。
4. **Robolectric**：联机面板/房间条/只读签池的渲染与文案（注意：含输入框的界面不能进 Robolectric 交互测试，沿用现有约定）。
5. **蓝牙**：真机单机验证（建房广播、权限流程、扫描页 UI）；协议与分片由假传输测试覆盖；双机对连作为已知残余风险交付。

---

## 14. 交付后的架构同步

实现完成后需要回写本文件的状态（已实现/偏差），并在 README 增加「联机」一节（用法、权限说明、建议人数、已知限制）。

---

## 15. 实现结果（2026-09-19）

### 15.1 与本文档的偏差

| # | 偏差 | 说明 |
| --- | --- | --- |
| 1 | 协议 **新增 `setMode` action** | 放回/不放回是房间共享规则，做成**仅房主**可改（与 `resetPool` 同类）。已在 §5.2 补上 |
| 2 | 成员**即使被放开 `allowEdit` 也只能加文字签** | 图片文件在房主设备上，v1 不做反向上传；房主对成员的 `AddLot(IMAGE)` 一律回 `not_allowed`，UI 对成员隐藏图库/拍照入口 |
| 3 | 局域网发现做成**被动广播 + 主动探测双路径** | 房主每秒广播信标，同时响应探测包并**单播**回信标；加入方优先绑约定端口 47002，绑不上退临时端口。真机实测：PC 当房主时纯广播到不了手机（PC 侧网络栈/VPN 因素），单播路径立刻被发现——探测路径不是多余的兜底，是必需的 |
| 4 | 房间码采用 **Crockford Base32** 子集 | `0123456789ABCDEFGHJKMNPQRSTVWXYZ`（去掉 I/L/O/U），手输做 `I/L→1`、`O→0` 容错 |

### 15.2 验证结果

- **单元/集成测试 136 个全绿**（`room` 包 85 个）：协议编解码与坏帧、房间码与二维码 URI、真 socket 回环（TCP 多成员/二进制分片/断开）、房主权限矩阵与署名、成员副本一致性与重连、控制器建/加入/退出与离线备份恢复、BLE 分片往返、二维码解码（含相机行距）。
- **Robolectric 界面流程**：单机不显示房间条、顶栏有联机入口、抽签/撤销/放回/重置/清空全链路。
- **真机端到端（手机当房主 + 电脑 JVM 客户端）15/15 PASS**：welcome/成员表、默认不放权、schema v3、抽签 +1 且署名 `drawnBy=PC-Client`、revision 递增、备注写入、未放权时撤销/加签/重置全被拒、图片分片字节一致 11137/11137 且为 JPEG。
- **真机（手机当成员）**：自动发现列表出现房间并可点击加入、房间条显示「成员 · 2 人」、签池只读、**模式显示「由房主设定」不可点**、结果区提示「房主未开放放回」且**操作行没有撤销/重置**、成员抽签后结果带上自己的 `@名字`、`offline_backup` 在加入时写入、**进程重启后恢复自己的签池并清除备份**。
- **真机二维码**：局域网与蓝牙房间的二维码都能在电脑上解码核对（`t=lan&h=<Wi-Fi 地址>&p=…&c=…&r=…` / `t=bt&sid=…&c=…`），房间码与界面一致。
- **真机蓝牙**：可创建蓝牙房间，系统日志确认 BLE 广播启动。
- **真机发现并修复的缺陷**：① 阻塞式 socket I/O 跑在主线程导致 ANR（发现模块与连接建立都改到 IO 线程）；② `DrawSession.lots/results` 暴露可变内部列表导致并发修改异常（改为返回不可变快照）。

### 15.3 残余风险（交付时如实标注）

1. **BLE 双机对连未验证**：只有一台手机，且同一设备扫不到自己的广播。已验证分片/重组、GATT 服务注册、广播启动、`t=bt` 邀请内容；未验证真实两端连接、MTU 协商、通知收发。
2. **用相机扫「活着的」二维码未验证**：需要第二块屏幕（手机没法把大厅二维码给自拍镜头看）。已验证相机预览/权限流程、解码核心（单测 + 电脑侧同管道复现）、相册兜底解码并触发加入。
3. **`bye` → 成员恢复签池**：逻辑由单元测试覆盖（`RoomControllerTest.hostClosingTheRoomMakesTheClientRestoreItsOwnPool`），真机上验证的是等价路径（进程重启恢复离线备份）。
4. 同一 Wi-Fi 下的 AP 隔离、系统后台冻结等环境因素仍可能导致连接失败；已有心跳、自动重连与扫码/房间码兜底，但没有做前台服务。
