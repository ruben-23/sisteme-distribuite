import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

public class UDPClient {
    public static void main(String[] args) {
        String serverAddress = "192.168.37.252";
        int port = 9876;
        int[] packetSizes = {128, 512, 1024};

        try (DatagramSocket clientSocket = new DatagramSocket()) {
            InetAddress serverIP = InetAddress.getByName(serverAddress);

            for (int size : packetSizes) {
                byte[] sendData = new byte[size];
                Arrays.fill(sendData, (byte) 1); // completare cu bytes de valoare 1

                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverIP, port);

                long startTime = System.nanoTime(); // pornire cronometru
                clientSocket.send(sendPacket);

                byte[] receiveBuffer = new byte[size];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                clientSocket.receive(receivePacket);
                long endTime = System.nanoTime(); // oprire cronometru

                double rttMillis = (endTime - startTime) / 1_000_000.0; // calculare timp
                System.out.printf("Dimensiune pachet: %d bytes, RTT: %.3f ms%n", size, rttMillis);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
