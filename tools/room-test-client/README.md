# 电脑侧联机验证工具

真机验证「手机当房主」时，用电脑当第二个设备。两个都是单文件 Java 程序，用 JDK 直接跑（不需要额外依赖；`QrDecode` 需要 zxing core 的 jar，从 Gradle 缓存里拿）。

## 1. HelloProbe：加入房间并打印握手结果

验证「成员 `hello` → 房主回 `welcome`（含完整状态）→ 广播 `state`」。

```powershell
# IP / 端口从房主屏幕的二维码里看（用下面的 QrDecode 解码截图，或直接看房间列表）
& '<JDK>\bin\java.exe' tools\room-test-client\HelloProbe.java <房主IP> 37835 电脑客户端
```

期望输出：第一帧是 `welcome`（`members` 里包含刚加入的名字与房主的名字），第二帧是 `state`；
`state` 里能看到签池与结果（`version:3`，结果带 `note` / `drawnBy` 字段）。

## 2. QrDecode：解码房主屏幕截图里的二维码

验证二维码内容与界面一致（`t=lan`、`h` 是 Wi-Fi 地址、`p` 端口、`c` 6 位房间码）。

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.zxing\core" -Recurse -Filter 'core-*.jar' |
  Select-Object -First 1 -ExpandProperty FullName

& '<JDK>\bin\java.exe' --class-path $jar tools\room-test-client\QrDecode.java 截图.png
# 整图找不到时，可以再给裁剪区域：左 上 宽 高
& '<JDK>\bin\java.exe' --class-path $jar tools\room-test-client\QrDecode.java 截图.png 300 860 850 880
```

实测样例输出：

```
QR CONTENT: drawlots://v1?t=lan&h=<房主IP>&p=37835&c=M37HGJ&r=测试机A+%E7%9A%84%E6%8A%BD%E7%AD%BE
```

（`r=` 是 URL 编码的房间名，`+` 表示空格；`c=` 必须与房主屏幕上显示的房间码一致。）
