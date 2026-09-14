// SPDX-License-Identifier: MIT OR Apache-2.0

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

/** Independently written Java peer for the selected Java-compatible TCP slice. */
public final class TcpJavaPeer {
    public static void main(String[] args) throws Exception {
        boolean server = args[0].equals("server");
        byte[] raw = new byte[args[2].equals("6") ? 16 : 4];
        if (raw.length == 4) raw[0] = 127;
        raw[raw.length - 1] = 1;
        InetAddress address = InetAddress.getByAddress(raw);
        int rounds = Integer.parseInt(args[3]);
        Socket socket;
        if (server) {
            try (ServerSocket listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress(address, 0));
                System.out.println("PORT " + listener.getLocalPort());
                System.out.flush();
                socket = listener.accept();
            }
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(address, Integer.parseInt(args[1])), 1000);
        }
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(2000);
            var input = socket.getInputStream();
            var output = socket.getOutputStream();
            byte[] sent = new byte[257];
            for (int index = 0; index < sent.length; index++) sent[index] = (byte) index;
            for (int round = 0; round < rounds; round++) {
                if (!server) {
                    output.write(sent, 0, 1);
                    output.write(sent, 1, sent.length - 1);
                }
                byte[] received = input.readNBytes(sent.length);
                if (!Arrays.equals(sent, received)) throw new AssertionError("binary exchange differs");
                if (server) output.write(received);
            }
            if (!server) socket.shutdownOutput();
            if (server && input.read() != -1) throw new AssertionError("missing EOF");
        }
    }
}
