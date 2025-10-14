
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MulticastChat {
    private static final String MULTICAST_ADDRESS = "230.0.0.1";
    private static final int PORT = 4446;
    private static final int MAX_HISTORY = 10;

    private String processId;
    private MulticastSocket socket;
    private InetAddress group;
    private NetworkInterface netIf;
    private InetSocketAddress groupAddress;

    private List<String> messageHistory;
    private Random random;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public MulticastChat(String processId) throws IOException {
        this.processId = processId;
        this.messageHistory = new CopyOnWriteArrayList<>();
        this.random = new Random();
        this.running = true;
        this.scheduler = Executors.newScheduledThreadPool(1);

        // Initializare socket multicast
        socket = new MulticastSocket(PORT);
        group = InetAddress.getByName(MULTICAST_ADDRESS);
        groupAddress = new InetSocketAddress(group, PORT);

        // Gaseste interfata de retea corespunzatoare
        netIf = NetworkInterface.getByInetAddress(
                InetAddress.getLocalHost()
        );

        // Join la grup
        socket.joinGroup(groupAddress, netIf);

        System.out.println("  Proces " + processId + " pornit                    ");
        System.out.println("  Multicast: " + MULTICAST_ADDRESS + ":" + PORT + "        ");

    }

    public void start() {
        // Trimite mesaj de intrare in sistem
        sendMessage("JOIN:" + processId + " a intrat in sistem");

        // Thread pentru primirea mesajelor
        Thread receiverThread = new Thread(this::receiveMessages);
        receiverThread.setDaemon(true);
        receiverThread.start();

        // Thread pentru citirea de la consola
        Thread consoleThread = new Thread(this::readConsoleInput);
        consoleThread.setDaemon(true);
        consoleThread.start();

        // Programeaza mesajul de inchidere dupa un timp aleator
        int delay = 15 + random.nextInt(16); // 5-20 secunde
        System.out.println("\n Procesul va trimite mesaj de inchidere dupa " +
                delay + " secunde...\n");

        scheduler.schedule(() -> {
            System.out.println("\n Trimit mesaj de inchidere...");
            sendMessage("SHUTDOWN:" + processId);
        }, delay, TimeUnit.SECONDS);

        // Asteapta pana cand procesul se inchide
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void receiveMessages() {
        byte[] buffer = new byte[1024];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(
                        packet.getData(),
                        0,
                        packet.getLength()
                ).trim();

                handleMessage(message);

            } catch (IOException e) {
                if (running) {
                    System.err.println("Eroare la primirea mesajului: " +
                            e.getMessage());
                }
            }
        }
    }

    private void handleMessage(String message) {
        // Verifica tipul mesajului
        if (message.startsWith("JOIN:")) {
            String content = message.substring(5);
            System.out.println(" " + content);
            addToHistory(message);

        } else if (message.startsWith("MSG:")) {
            String[] parts = message.substring(4).split(":", 2);
            if (parts.length == 2) {
                System.out.println(" [" + parts[0] + "]: " + parts[1]);
                addToHistory(message);
            }

        } else if (message.startsWith("REQUEST_HISTORY:")) {
            String requesterId = message.substring(16);
            if (!requesterId.equals(processId)) {
                System.out.println(" " + requesterId +
                        " solicita istoricul mesajelor");
                sendHistory(requesterId);
            }

        } else if (message.startsWith("HISTORY:")) {
            String content = message.substring(8);
            String[] parts = content.split(":", 2);
            if (parts.length == 2 && parts[0].equals(processId)) {
                System.out.println("\n Istoric primit:");
                System.out.println(parts[1]);
            }

        } else if (message.startsWith("SHUTDOWN:")) {
            String senderId = message.substring(9);
            System.out.println("\n Mesaj de inchidere primit de la " + senderId);
            System.out.println(" Procesul " + processId + " se inchide...");
            shutdown();
        }
    }

    private void addToHistory(String message) {
        messageHistory.add(message);
        if (messageHistory.size() > MAX_HISTORY) {
            messageHistory.remove(0);
        }
    }

    private void sendMessage(String message) {
        try {
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    buffer,
                    buffer.length,
                    group,
                    PORT
            );
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("Eroare la trimiterea mesajului: " +
                    e.getMessage());
        }
    }

    private void sendHistory(String requesterId) {
        if (messageHistory.isEmpty()) {
            return;
        }

        StringBuilder history = new StringBuilder();
        for (String msg : messageHistory) {
            history.append(msg).append("\n");
        }

        sendMessage("HISTORY:" + requesterId + ":" + history.toString());
    }

    private void readConsoleInput() {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in)
        );

        System.out.println(" Comenzi disponibile:");
        System.out.println("  - Scrie orice mesaj pentru a-l trimite grupului");
        System.out.println("  - /history - solicita istoricul mesajelor");
        System.out.println("  - /exit - inchide acest proces\n");

        while (running) {
            try {
                String input = reader.readLine();

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                if (input.equals("/exit")) {
                    System.out.println(" Iesire manuala...");
                    shutdown();
                    break;

                } else if (input.equals("/history")) {
                    sendMessage("REQUEST_HISTORY:" + processId);

                } else {
                    sendMessage("MSG:" + processId + ":" + input);
                }

            } catch (IOException e) {
                if (running) {
                    System.err.println("Eroare la citirea input-ului: " +
                            e.getMessage());
                }
            }
        }
    }

    private void shutdown() {
        running = false;
        scheduler.shutdown();

        try {
            socket.leaveGroup(groupAddress, netIf);
            socket.close();
        } catch (IOException e) {
            System.err.println("Eroare la inchidere: " + e.getMessage());
        }

        System.exit(0);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Utilizare: java MulticastProcess <ID_PROCES>");
            System.out.println("Exemplu: java MulticastProcess P1");
            System.exit(1);
        }

        try {
            MulticastChat process = new MulticastChat(args[0]);
            process.start();
        } catch (IOException e) {
            System.err.println("Eroare la pornirea procesului: " +
                    e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}