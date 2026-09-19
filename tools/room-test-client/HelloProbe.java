package roomtestclient;

import java.io.*;
import java.net.*;

/**
 * 最小协议客户端：连上房间、发 hello、把收到的第一帧原样打印出来。
 * 用来验证「成员 hello → 房主回 welcome（含完整状态）」这一段。
 *
 * 用法：java tools/room-test-client/HelloProbe.java <房主IP> <端口> [名称]
 * （IP/端口从房主屏幕上的二维码或房间列表里拿）
 */
public class HelloProbe {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String name = args.length > 2 ? args[2] : "电脑客户端";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(8000);

            String hello = "{\"type\":\"hello\",\"protocolVersion\":1,\"deviceId\":\"pc-probe\",\"name\":\"" + name + "\"}";
            byte[] payload = hello.getBytes("UTF-8");
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(1 + payload.length);
            out.writeByte(0x01);
            out.write(payload);
            out.flush();
            System.out.println("sent hello: " + hello);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            for (int i = 0; i < 2; i++) {
                int length = in.readInt();
                int type = in.readByte();
                if (length < 2 || length > (1 << 20)) {
                    System.out.println("bad frame length=" + length);
                    return;
                }
                byte[] body = new byte[length - 1];
                in.readFully(body);
                String text = new String(body, "UTF-8");
                System.out.println("frame#" + (i + 1) + " type=" + type + " bytes=" + length);
                System.out.println(text.length() > 700 ? text.substring(0, 700) + " …" : text);
            }
        }
    }
}
