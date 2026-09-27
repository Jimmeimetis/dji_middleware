package dji.v5.ux.sample.showcase.defaultlayout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpSender {
    private DatagramSocket socket;
    private InetAddress address;
    private int port;

    public UdpSender(String ip, int port) throws Exception {
        socket = new DatagramSocket();
        address = InetAddress.getByName(ip);
        this.port = port;
    }

    public void send(byte[] data, int offset, int length) {
        try {
            int maxPacketSize = 1200;
            for (int i = 0; i < length; i += maxPacketSize) {
                int len = Math.min(maxPacketSize, length - i);
                DatagramPacket packet = new DatagramPacket(
                        data, offset + i, len, address, port
                );
                socket.send(packet);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
