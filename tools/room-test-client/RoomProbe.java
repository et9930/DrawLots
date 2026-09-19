import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * 电脑侧协议探针（真机端到端验证用）。两种模式：
 *
 *   java --class-path <org.json.jar> RoomProbe.java client <房主IP> <端口> <我的名字>
 *       —— 加入手机开的房间，做抽签/备注/取图/越权尝试，逐项打印 PASS/FAIL
 *
 *   java --class-path <org.json.jar> RoomProbe.java host <监听端口> <房间名> <房主名> <房间码>
 *       —— 当一个极简房主，让手机以「成员」身份加入；用于验证成员界面与「房主结束房间」后的恢复
 */
public class RoomProbe {

    // ---------- 分帧（与 App 的 RoomProtocol 一致） ----------

    static void writeJson(DataOutputStream out, JSONObject json) throws IOException {
        byte[] payload = json.toString().getBytes("UTF-8");
        out.writeInt(1 + payload.length);
        out.writeByte(0x01);
        out.write(payload);
        out.flush();
    }

    static JSONObject readJson(DataInputStream in) throws IOException {
        while (true) {
            int length = in.readInt();
            int type = in.readByte();
            byte[] body = new byte[length - 1];
            in.readFully(body);
            if (type == 0x01) {
                return new JSONObject(new String(body, "UTF-8"));
            }
            // 0x02 二进制分片在需要的地方单独处理
        }
    }

    static JSONObject request(DataInputStream in, DataOutputStream out, String id, JSONObject action)
            throws IOException {
        writeJson(out, new JSONObject().put("type", "request").put("id", id).put("action", action));
        while (true) {
            JSONObject message = readJson(in);
            String type = message.optString("type");
            if (type.equals("ack") && message.optString("id").equals(id)) {
                return new JSONObject().put("ack", true).put("revision", message.optLong("revision"));
            }
            if (type.equals("error") && id.equals(message.optString("id"))) {
                return new JSONObject().put("error", message.optString("code"));
            }
        }
    }

    static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
            if (sb.length() >= 32) break;
        }
        return sb.substring(0, 32);
    }

    static int passed = 0;
    static int failed = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + what + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else { failed++; System.out.println("FAIL  " + what + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
    }

    // ---------- 模式一：电脑当成员 ----------

    static void runClient(String host, int port, String myName) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(20000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            writeJson(out, new JSONObject()
                    .put("type", "hello")
                    .put("protocolVersion", 1)
                    .put("deviceId", "pc-probe")
                    .put("name", myName));

            JSONObject welcome = readJson(in);
            check("收到 welcome", welcome.optString("type").equals("welcome"), welcome.optString("type"));
            long revision = welcome.optLong("revision");
            JSONObject state = welcome.getJSONObject("state");
            JSONArray members = welcome.optJSONArray("members");
            List<String> names = new ArrayList<>();
            for (int i = 0; members != null && i < members.length(); i++) names.add(members.getString(i));
            check("房主把我加进成员列表", names.contains(myName), names.toString());
            check("默认不放权", !welcome.optBoolean("allowRecover") && !welcome.optBoolean("allowEdit"), "");
            check("存档 schema 是 v3", state.optInt("version") == 3, "version=" + state.optInt("version"));

            int lotsBefore = state.optJSONArray("lots").length();
            int resultsBefore = state.optJSONArray("results").length();
            System.out.println("      房间：" + welcome.optString("roomName")
                    + " 房主：" + welcome.optString("hostName")
                    + " 签种：" + lotsBefore + " 已有结果：" + resultsBefore
                    + " revision=" + revision);

            // --- 抽签：应写入署名 ---
            JSONObject ack = request(in, out, "r1", new JSONObject().put("kind", "draw").put("count", 1));
            check("抽签请求被接受", ack.has("ack"), ack.toString());
            JSONObject afterDrawEnvelope = drainState(in);
            JSONObject afterDraw = afterDrawEnvelope.getJSONObject("state");
            JSONArray results = afterDraw.getJSONArray("results");
            check("结果数 +1", results.length() == resultsBefore + 1,
                    resultsBefore + " -> " + results.length());
            JSONObject last = results.getJSONObject(results.length() - 1);
            check("抽出的签自动署名抽签人", myName.equals(last.optString("drawnBy")),
                    "drawnBy=" + last.optString("drawnBy"));
            check("revision 递增", afterDrawEnvelope.optLong("revision") > revision,
                    revision + " -> " + afterDrawEnvelope.optLong("revision"));
            int seq = last.optInt("seq");

            // --- 备注：人人可写 ---
            JSONObject noteAck = request(in, out, "r2",
                    new JSONObject().put("kind", "setNote").put("seq", seq).put("note", "PC note"));
            check("备注请求被接受", noteAck.has("ack"), noteAck.toString());
            JSONObject afterNote = drainState(in).getJSONObject("state");
            JSONObject noted = findResult(afterNote, seq);
            check("备注写入成功", noted != null && "PC note".equals(noted.optString("note")),
                    noted == null ? "找不到结果" : noted.optString("note"));

            // --- 越权：未放权时撤销/加签都应被拒 ---
            JSONObject undo = request(in, out, "r3", new JSONObject().put("kind", "undo"));
            check("未开放放回时撤销被拒", "not_allowed".equals(undo.optString("error")), undo.toString());
            JSONObject addLot = request(in, out, "r4", new JSONObject()
                    .put("kind", "addLot")
                    .put("lot", new JSONObject().put("id", "pc-lot").put("kind", "TEXT")
                            .put("text", "电脑加的签").put("image", JSONObject.NULL).put("qty", 1)));
            check("未开放编辑时加签被拒", "not_allowed".equals(addLot.optString("error")), addLot.toString());
            JSONObject reset = request(in, out, "r5", new JSONObject().put("kind", "resetPool"));
            check("成员重置永远被拒", "not_allowed".equals(reset.optString("error")), reset.toString());

            // --- 图片按需拉取 ---
            String imagePath = firstImagePath(afterNote);
            if (imagePath == null) {
                System.out.println("SKIP  图片拉取（房主签池里没有图片签）");
            } else {
                String key = sha256(imagePath);
                writeJson(out, new JSONObject().put("type", "imageRequest").put("id", "i1").put("imageKey", key));
                int expected = -1;
                int received = 0;
                boolean jpeg = false;
                byte[] firstBytes = null;
                while (true) {
                    int length = in.readInt();
                    int type = in.readByte();
                    byte[] body = new byte[length - 1];
                    in.readFully(body);
                    if (type == 0x02) {
                        int headerLength = ((body[0] & 0xFF) << 24) | ((body[1] & 0xFF) << 16)
                                | ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
                        byte[] data = Arrays.copyOfRange(body, 4 + headerLength, body.length);
                        if (firstBytes == null) firstBytes = data;
                        received += data.length;
                    } else {
                        JSONObject message = new JSONObject(new String(body, "UTF-8"));
                        String t = message.optString("type");
                        if (t.equals("imageBegin")) {
                            expected = message.optInt("size");
                        } else if (t.equals("imageEnd")) {
                            break;
                        } else if (t.equals("error")) {
                            check("图片请求失败", false, message.optString("code"));
                            expected = -2;
                            break;
                        }
                    }
                }
                if (expected >= 0) {
                    jpeg = firstBytes != null && firstBytes.length > 2
                            && (firstBytes[0] & 0xFF) == 0xFF && (firstBytes[1] & 0xFF) == 0xD8;
                    check("图片分片字节数一致", received == expected, received + " / " + expected);
                    check("拿到的是 JPEG 图片", jpeg, "");
                }
            }

            System.out.println();
            System.out.println("RESULT passed=" + passed + " failed=" + failed);
            System.out.println("（手机屏幕上应能看到一条署名 @" + myName + " 的新结果，成员数 2）");
            Thread.sleep(30000); // 留时间给手机截图
        }
    }

    /** 等广播来的最新 state（跳过 ack/error/ping 等）。 */
    static JSONObject drainState(DataInputStream in) throws IOException {
        while (true) {
            JSONObject message = readJson(in);
            if (message.optString("type").equals("state")) return message;
        }
    }

    static JSONObject findResult(JSONObject state, int seq) {
        JSONArray results = state.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null && item.optInt("seq") == seq) return item;
        }
        return null;
    }

    static String firstImagePath(JSONObject state) {
        JSONArray lots = state.optJSONArray("lots");
        for (int i = 0; lots != null && i < lots.length(); i++) {
            JSONObject lot = lots.optJSONObject(i);
            if (lot != null && !lot.isNull("image")) return lot.optString("image");
        }
        return null;
    }

    // ---------- 模式二：电脑当极简房主（让手机加入） ----------

    /** 本机在局域网里的 IPv4：优先选跟手机同网段的那个（避免挑到 VPN/虚拟网卡）。 */
    static String localIp() throws Exception {
        String fallback = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress address : ni.getInterfaceAddresses()) {
                InetAddress ip = address.getAddress();
                if (!(ip instanceof Inet4Address) || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) continue;
                String host = ip.getHostAddress();
                if (host.startsWith("192.168.1.")) return host;
                if (fallback == null) fallback = host;
            }
        }
        return fallback == null ? "127.0.0.1" : fallback;
    }

    /**
     * 每秒广播一次信标（并回应加入方的探测包），这样手机可以用「自动发现」加入。
     * 字段与 App 的 LanBeaconCodec 一致。
     */
    static DatagramSocket startBeacon(int port, String roomName, String hostName, String code, String unicastTarget)
            throws Exception {
        String ip = localIp();
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(47002));
        byte[] beacon = ("{\"magic\":\"DRAWLOTS1\",\"h\":\"" + ip + "\",\"p\":" + port
                + ",\"c\":\"" + code + "\",\"r\":\"" + roomName + "\",\"n\":\"" + hostName
                + "\",\"rev\":0}").getBytes("UTF-8");
        Thread sender = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    socket.send(new DatagramPacket(beacon, beacon.length,
                            InetAddress.getByName("255.255.255.255"), 47002));
                } catch (Exception e) {
                    // 广播可能被 VPN/虚拟网卡挡住，下面还有单播兜底
                }
                if (unicastTarget != null) {
                    try {
                        socket.send(new DatagramPacket(beacon, beacon.length,
                                InetAddress.getByName(unicastTarget), 47002));
                    } catch (Exception e) {
                        // 忽略：手机不在线时正常
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sender.setDaemon(true);
        sender.start();
        Thread responder = new Thread(() -> {
            byte[] buffer = new byte[2048];
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    if (text.contains("PROBE") || text.contains("\"probe\":true")) {
                        socket.send(new DatagramPacket(beacon, beacon.length, packet.getAddress(), packet.getPort()));
                    }
                } catch (Exception e) {
                    return;
                }
            }
        });
        responder.setDaemon(true);
        responder.start();
        System.out.println("beacon on 47002 advertising " + ip + ":" + port + " code=" + code);
        return socket;
    }

    static void runHost(int port, String roomName, String hostName, String roomCode, String unicastTarget)
            throws Exception {
        startBeacon(port, roomName, hostName, roomCode, unicastTarget);
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("toy host listening on " + port + " code=" + roomCode);
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(120000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                JSONObject hello = readJson(in);
                String guest = hello.optString("name");
                System.out.println("guest joined: " + guest);

                // 极简状态：两种签（A ×2、B ×1），不放回
                JSONObject state = new JSONObject()
                        .put("version", 3)
                        .put("mode", "WITHOUT_REPLACEMENT")
                        .put("lots", new JSONArray()
                                .put(lot("toy-a", "A 签", 2))
                                .put(lot("toy-b", "B 签", 1)))
                        .put("results", new JSONArray());
                long revision = 0;
                Map<String, Integer> consumed = new HashMap<>();

                writeJson(out, new JSONObject()
                        .put("type", "welcome")
                        .put("roomName", roomName)
                        .put("hostName", hostName)
                        .put("allowRecover", false)
                        .put("allowEdit", false)
                        .put("revision", revision)
                        .put("members", new JSONArray().put(hostName).put(guest))
                        .put("state", state));

                boolean drew = false;
                long drawAt = 0;
                boolean byeScheduled = false;
                while (true) {
                    JSONObject message;
                    try {
                        message = readJson(in);
                    } catch (Exception e) {
                        break;
                    }
                    String type = message.optString("type");
                    if (type.equals("ping")) {
                        writeJson(out, new JSONObject().put("type", "pong").put("ts", message.optLong("ts")));
                        continue;
                    }
                    if (!type.equals("request")) continue;

                    String id = message.optString("id");
                    JSONObject action = message.optJSONObject("action");
                    String kind = action == null ? "" : action.optString("kind");
                    System.out.println("request: " + kind);

                    if (kind.equals("draw")) {
                        // 不放回：按剩余数量挑一个
                        List<String> pool = new ArrayList<>();
                        JSONArray lots = state.getJSONArray("lots");
                        for (int i = 0; i < lots.length(); i++) {
                            JSONObject item = lots.getJSONObject(i);
                            int remaining = item.optInt("qty") - consumed.getOrDefault(item.optString("id"), 0);
                            for (int n = 0; n < remaining; n++) pool.add(item.optString("id"));
                        }
                        if (pool.isEmpty()) {
                            writeJson(out, error(id, "bad_request", "池子空了"));
                            continue;
                        }
                        String picked = pool.get(new Random().nextInt(pool.size()));
                        consumed.merge(picked, 1, Integer::sum);
                        JSONObject lot = findLot(state, picked);
                        int seq = state.getJSONArray("results").length() + 1;
                        state.getJSONArray("results").put(new JSONObject()
                                .put("seq", seq)
                                .put("lotId", picked)
                                .put("kind", "TEXT")
                                .put("text", lot.optString("text"))
                                .put("image", JSONObject.NULL)
                                .put("consumed", true)
                                .put("mode", "WITHOUT_REPLACEMENT")
                                .put("at", System.currentTimeMillis())
                                .put("note", "")
                                .put("drawnBy", guest));
                        revision++;
                        drew = true;
                        drawAt = System.currentTimeMillis();
                        writeJson(out, new JSONObject().put("type", "ack").put("id", id).put("revision", revision));
                        broadcast(out, revision, state, hostName, guest);
                        // 25 秒后由独立线程发 bye（不能只靠请求到达来推进计时，否则收不到请求就永远不发）
                        if (!byeScheduled) {
                            byeScheduled = true;
                            final DataOutputStream outRef = out;
                            Thread closer = new Thread(() -> {
                                try {
                                    Thread.sleep(25000);
                                    System.out.println("sending bye (host closing)");
                                    writeJson(outRef, new JSONObject().put("type", "bye").put("reason", "host_closed"));
                                    Thread.sleep(500);
                                } catch (Exception e) {
                                    System.out.println("bye failed: " + e.getMessage());
                                }
                            });
                            closer.setDaemon(true);
                            closer.start();
                        }
                        continue;
                    }
                    if (kind.equals("setNote")) {
                        JSONArray results = state.getJSONArray("results");
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject item = results.getJSONObject(i);
                            if (item.optInt("seq") == action.optInt("seq")) {
                                item.put("note", action.optString("note"));
                            }
                        }
                        revision++;
                        writeJson(out, new JSONObject().put("type", "ack").put("id", id).put("revision", revision));
                        broadcast(out, revision, state, hostName, guest);
                        continue;
                    }
                    // 未放权的操作一律拒绝
                    writeJson(out, error(id, "not_allowed", "房主未开放这个操作"));
                }

                Thread.sleep(1000);
            }
        }
        System.out.println("toy host done");
    }

    static JSONObject lot(String id, String text, int qty) throws Exception {
        return new JSONObject().put("id", id).put("kind", "TEXT").put("text", text)
                .put("image", JSONObject.NULL).put("qty", qty);
    }

    static JSONObject findLot(JSONObject state, String id) {
        JSONArray lots = state.getJSONArray("lots");
        for (int i = 0; i < lots.length(); i++) {
            if (id.equals(lots.getJSONObject(i).optString("id"))) return lots.getJSONObject(i);
        }
        return new JSONObject();
    }

    static void broadcast(DataOutputStream out, long revision, JSONObject state, String hostName, String guest)
            throws IOException {
        writeJson(out, new JSONObject()
                .put("type", "state")
                .put("revision", revision)
                .put("allowRecover", false)
                .put("allowEdit", false)
                .put("members", new JSONArray().put(hostName).put(guest))
                .put("state", state));
    }

    static JSONObject error(String id, String code, String message) throws Exception {
        return new JSONObject().put("type", "error").put("id", id).put("code", code).put("message", message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: client <host> <port> <name> | host <port> <roomName> <hostName> <code>");
            return;
        }
        if (args[0].equals("client")) {
            runClient(args[1], Integer.parseInt(args[2]), args.length > 3 ? args[3] : "电脑客户端");
        } else {
            runHost(Integer.parseInt(args[1]), args[2], args[3], args[4], args.length > 5 ? args[5] : null);
        }
    }
}
