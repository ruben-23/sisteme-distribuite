import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class UDPServer {
    public static void main(String[] args) {
        int port = 9876;

        try (DatagramSocket serverSocket = new DatagramSocket(port)) {
            System.out.println("Server UDP pornit pe portul " + port);

            byte[] receiveBuffer = new byte[2048];

            while (true) {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                serverSocket.receive(receivePacket);

                // extragere date primite
                byte[] data = receivePacket.getData();
                int length = receivePacket.getLength();

                // pregatire pachet de raspuns
                DatagramPacket sendPacket = new DatagramPacket(
                        data, length, receivePacket.getAddress(), receivePacket.getPort()
                );
                serverSocket.send(sendPacket);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
