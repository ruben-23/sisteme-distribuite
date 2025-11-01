import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MulticastHeartbeatSystem {
    // Configurare multicast - default values
    private static final String DEFAULT_MULTICAST_ADDRESS = "230.0.0.1";
    private static final int DEFAULT_MULTICAST_PORT = 4446;
    private static final int HEARTBEAT_INTERVAL = 2000; // 2 secunde
    private static final int TIMEOUT_THRESHOLD = 6000; // 6 secunde

    private final String processId;
    private final MulticastSocket socket;
    private final InetAddress group;
    private final int port;
    private final Map<String, Long> activeProcesses;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    // Tipuri de mesaje
    private static final String MSG_HEARTBEAT = "HEARTBEAT";
    private static final String MSG_USER = "USER_MSG";
    private static final String MSG_JOIN = "JOIN";
    private static final String MSG_LEAVE = "LEAVE";
    private static final String MSG_RESPONSE = "RESPONSE";

    public MulticastHeartbeatSystem(String processId, int port) throws IOException {
        this.processId = processId;
        this.port = port;

        // Inițializare multicast socket
        this.socket = new MulticastSocket(port);
        this.group = InetAddress.getByName(DEFAULT_MULTICAST_ADDRESS);

        // Join la grupul multicast
        NetworkInterface netIf = NetworkInterface.getByInetAddress(
                InetAddress.getLocalHost());
        socket.joinGroup(new InetSocketAddress(group, port), netIf);

        this.activeProcesses = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(3);
        this.running = true;

        System.out.println("===========================================");
        System.out.println("Proces pornit: " + processId);
        System.out.println("Adresa multicast: " + DEFAULT_MULTICAST_ADDRESS + ":" + port);
        System.out.println("===========================================\n");
    }

    public void start() {
        // Thread pentru primire mesaje (trebuie pornit primul)
        scheduler.execute(this::receiveMessages);

        // Asteapta putin ca thread-ul de receptie sa porneasca
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Trimite mesaj de JOIN pentru a se anunta celorlalte procese
        sendMessage(MSG_JOIN, "");

        // Thread pentru trimitere heartbeat
        scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);

        // Thread pentru verificare timeout
        scheduler.scheduleAtFixedRate(this::checkTimeouts,
                TIMEOUT_THRESHOLD, TIMEOUT_THRESHOLD, TimeUnit.MILLISECONDS);

        // Thread pentru citire input de la consola
        scheduler.execute(this::handleUserInput);
    }

    private void sendHeartbeat() {
        sendMessage(MSG_HEARTBEAT, "");
    }

    private void sendMessage(String type, String content) {
        try {
            String message = type + "|" + processId + "|" + content;
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    buffer, buffer.length, group, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("Eroare la trimitere mesaj: " + e.getMessage());
        }
    }

    private void receiveMessages() {
        byte[] buffer = new byte[8192];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                processReceivedMessage(message);

            } catch (IOException e) {
                if (running) {
                    System.err.println("Eroare la primire mesaj: " + e.getMessage());
                }
            }
        }
    }

    private void processReceivedMessage(String message) {
        String[] parts = message.split("\\|", 3);
        if (parts.length < 2) return;

        String type = parts[0];
        String senderId = parts[1];
        String content = parts.length > 2 ? parts[2] : "";

        // Ignora propriile mesaje
        if (senderId.equals(processId)) return;

        switch (type) {
            case MSG_HEARTBEAT:
                // Daca primim heartbeat de la un proces care nu este in lista
                // (a revenit online dupa defectare), il adaugam inapoi
                boolean wasOffline = !activeProcesses.containsKey(senderId);
                updateProcessStatus(senderId);

                if (wasOffline) {
                    System.out.println("\n[SISTEM] Proces revenit ONLINE: " + senderId);
                    displayActiveProcesses();
                    System.out.print("Comanda> ");
                }
                break;

            case MSG_JOIN:
                boolean isNewProcess = !activeProcesses.containsKey(senderId);

                if (isNewProcess) {
                    System.out.println("\n[SISTEM] Proces nou detectat: " + senderId);
                } else {
                    System.out.println("\n[SISTEM] Proces revenit ONLINE: " + senderId);
                }

                updateProcessStatus(senderId);

                // Raspunde cu un mesaj RESPONSE pentru a te anunta
                sendMessage(MSG_RESPONSE, "");

                displayActiveProcesses();
                System.out.print("Comanda> ");
                break;

            case MSG_RESPONSE:
                // Un proces existent raspunde la JOIN-ul nostru
                if (!activeProcesses.containsKey(senderId)) {
                    System.out.println("\n[SISTEM] Proces existent descoperit: " + senderId);
                    updateProcessStatus(senderId);
                    displayActiveProcesses();
                    System.out.print("Comanda> ");
                }
                break;

            case MSG_LEAVE:
                if (activeProcesses.containsKey(senderId)) {
                    activeProcesses.remove(senderId);
                    System.out.println("\n[SISTEM] Procesul s-a deconectat: " + senderId);
                    displayActiveProcesses();
                    System.out.print("Comanda> ");
                }
                break;

            case MSG_USER:
                System.out.println("\n[MESAJ de la " + senderId + "]: " + content);
                System.out.print("\nComanda> ");
                break;
        }
    }

    private void updateProcessStatus(String processIdToUpdate) {
        activeProcesses.put(processIdToUpdate, System.currentTimeMillis());
    }

    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, Long> entry : activeProcesses.entrySet()) {
            if (currentTime - entry.getValue() > TIMEOUT_THRESHOLD) {
                toRemove.add(entry.getKey());
            }
        }

        for (String deadProcess : toRemove) {
            activeProcesses.remove(deadProcess);

            // Afișare mesaj vizibil de defectare
            System.out.println("\n");
            System.out.println("===============================================================");
            System.out.println("              PROCES DEFECT DETECTAT                          ");
            System.out.println("===============================================================");
            System.out.println(" Proces ID: " + deadProcess);
            System.out.println();
            System.out.println(" Motiv: Nu mai raspunde la heartbeat");
            System.out.println(" Timeout: " + (TIMEOUT_THRESHOLD/1000) + " secunde");
            System.out.println();
            System.out.println(" Procesul a fost eliminat din lista proceselor active.");
            System.out.println("===============================================================");
            System.out.println();

            displayActiveProcesses();
            System.out.print("Comanda> ");
        }
    }

    private void handleUserInput() {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in));

        System.out.println("Comenzi disponibile:");
        System.out.println("  msg <mesaj>  - Trimite mesaj tuturor proceselor");
        System.out.println("  list         - Afiseaza procesele active");
        System.out.println("  exit         - Inchide procesul\n");

        while (running) {
            try {
                System.out.print("Comanda> ");
                String input = reader.readLine();

                if (input == null || input.trim().isEmpty()) continue;

                String[] parts = input.trim().split("\\s+", 2);
                String command = parts[0].toLowerCase();

                switch (command) {
                    case "msg":
                        if (parts.length > 1) {
                            String userMessage = parts[1];
                            sendMessage(MSG_USER, userMessage);
                            System.out.println("[EU]: " + userMessage);
                        } else {
                            System.out.println("Utilizare: msg <mesaj>");
                        }
                        break;

                    case "list":
                        displayActiveProcesses();
                        break;

                    case "exit":
                        shutdown();
                        return;

                    default:
                        System.out.println("Comanda necunoscuta: " + command);
                        System.out.println("Comenzi: msg, list, exit");
                }

            } catch (IOException e) {
                if (running) {
                    System.err.println("Eroare la citire input: " + e.getMessage());
                }
            }
        }
    }

    private void displayActiveProcesses() {
        System.out.println("\n--- Procese Active (" + (activeProcesses.size() + 1) + ") ---");
        System.out.println("  * " + processId + " (EU)");

        if (activeProcesses.isEmpty()) {
            System.out.println("  (niciun alt proces detectat)");
        } else {
            int i = 1;
            for (String pid : activeProcesses.keySet()) {
                System.out.println("  " + i + ". " + pid);
                i++;
            }
        }
        System.out.println("------------------------\n");
    }

    public void shutdown() {
        System.out.println("\nInchidere proces...");
        running = false;

        // Trimite mesaj de LEAVE
        sendMessage(MSG_LEAVE, "");

        try {
            Thread.sleep(500); // Așteaptă să se trimită mesajul
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            NetworkInterface netIf = NetworkInterface.getByInetAddress(
                    InetAddress.getLocalHost());
            socket.leaveGroup(new InetSocketAddress(group, port), netIf);
            socket.close();
        } catch (IOException e) {
            System.err.println("Eroare la inchidere socket: " + e.getMessage());
        }

        System.out.println("Proces oprit.");
    }

    private static void printUsage() {
        System.out.println("Utilizare: java MulticastHeartbeatSystem <ID_PROCES> [PORT]");
        System.out.println();
        System.out.println("Parametri:");
        System.out.println("  ID_PROCES    - Identificator unic pentru acest proces (obligatoriu)");
        System.out.println("  PORT         - Port multicast (optional, default: " + DEFAULT_MULTICAST_PORT + ")");
        System.out.println();
        System.out.println("Exemple:");
        System.out.println("  java MulticastHeartbeatSystem Process1");
        System.out.println("  java MulticastHeartbeatSystem Process2 4446");
        System.out.println("  java MulticastHeartbeatSystem ServerA 5000");
        System.out.println();
        System.out.println("Nota: Toate procesele trebuie sa foloseasca acelasi port pentru a comunica.");
    }

    public static void main(String[] args) {
        // Validare argumente
        if (args.length < 1) {
            System.err.println("Eroare: ID proces lipseste!\n");
            printUsage();
            System.exit(1);
        }

        String processId = args[0];
        int port = DEFAULT_MULTICAST_PORT;

        // Parsare port dacă este furnizat
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
                if (port < 1024 || port > 65535) {
                    System.err.println("Eroare: Portul trebuie sa fie intre 1024 si 65535!");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("Eroare: Port invalid '" + args[1] + "'. Trebuie sa fie un numar intreg.");
                printUsage();
                System.exit(1);
            }
        }

        try {
            MulticastHeartbeatSystem system = new MulticastHeartbeatSystem(processId, port);

            // Adaugă hook pentru shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                system.shutdown();
            }));

            system.start();

        } catch (IOException e) {
            System.err.println("Eroare la initializare: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}