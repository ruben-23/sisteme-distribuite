import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sistem Heartbeat - Versiune Unicast
 */
public class HeartbeatNode {
    private final String nodeId;
    private final int port;
    private final Map<String, NodeInfo> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    private static final int HEARTBEAT_INTERVAL = 3000; // 3 secunde
    private static final int TIMEOUT_THRESHOLD = 10000; // 10 secunde

    static class NodeInfo {
        String id;
        String host;
        int port;
        long lastHeartbeat;
        boolean isAlive;

        NodeInfo(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.lastHeartbeat = System.currentTimeMillis();
            this.isAlive = true;
        }

        void updateHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
            if (!isAlive) {
                isAlive = true;
                System.out.println(" Nodul " + id + " s-a reconectat!");
            }
        }

        boolean checkTimeout(long currentTime) {
            if (currentTime - lastHeartbeat > TIMEOUT_THRESHOLD) {
                if (isAlive) {
                    isAlive = false;
                    return true; // S-a schimbat starea in FAILED
                }
            }
            return false;
        }
    }

    public HeartbeatNode(String nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
    }

    public void addPeer(String peerId, String host, int port) {
        peers.put(peerId, new NodeInfo(peerId, host, port));
        System.out.println("Peer adaugat: " + peerId + " la " + host + ":" + port);
    }

    public void start() {
        // Thread pentru ascultarea mesajelor
        threadPool.execute(this::listenForMessages);

        // Scheduler pentru trimiterea heartbeat-urilor
        scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                0,
                HEARTBEAT_INTERVAL,
                TimeUnit.MILLISECONDS
        );

        // Scheduler pentru verificarea timeout-urilor
        scheduler.scheduleAtFixedRate(
                this::checkTimeouts,
                TIMEOUT_THRESHOLD,
                HEARTBEAT_INTERVAL,
                TimeUnit.MILLISECONDS
        );

        // Thread pentru citirea comenzilor de la consola
        threadPool.execute(this::handleConsoleInput);

        System.out.println("\n=== Nod " + nodeId + " pornit pe portul " + port + " ===");
        System.out.println("Comenzi disponibile:");
        System.out.println("  status           - Afiseaza starea tuturor nodurilor");
        System.out.println("  send <id> <msg>  - Trimite mesaj catre un nod");
        System.out.println("  exit             - Opreste nodul\n");
    }

    private void listenForMessages() {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                handleMessage(message, packet.getAddress().getHostAddress());
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Eroare la ascultarea mesajelor: " + e.getMessage());
            }
        }
    }

    private void handleMessage(String message, String senderHost) {
        String[] parts = message.split("\\|", 3);
        String type = parts[0];
        String senderId = parts[1];

        switch (type) {
            case "HEARTBEAT":
                NodeInfo peer = peers.get(senderId);
                if (peer != null) {
                    peer.updateHeartbeat();
                }
                break;

            case "MESSAGE":
                if (parts.length == 3) {
                    System.out.println("\n Mesaj de la " + senderId + ": " + parts[2]);
                    System.out.print("> ");
                }
                break;

            case "NODE_FAILED":
                if (parts.length == 3) {
                    String failedNodeId = parts[2];
                    System.out.println("\n NOTIFICARE de la " + senderId +
                            ": Nodul " + failedNodeId + " a cazut!");
                    System.out.print("> ");

                    // Actualizeaza si propria vedere asupra nodului cazut
                    NodeInfo failedNode = peers.get(failedNodeId);
                    if (failedNode != null && failedNode.isAlive) {
                        failedNode.isAlive = false;
                        System.out.println(" Marchez si local nodul " + failedNodeId + " ca INACTIV");
                        System.out.print("> ");
                    }
                }
                break;

            case "NODE_RECOVERED":
                if (parts.length == 3) {
                    String recoveredNodeId = parts[2];
                    System.out.println("\n NOTIFICARE de la " + senderId +
                            ": Nodul " + recoveredNodeId + " s-a reconectat!");
                    System.out.print("> ");

                    // Actualizeaza si propria vedere
                    NodeInfo recoveredNode = peers.get(recoveredNodeId);
                    if (recoveredNode != null && !recoveredNode.isAlive) {
                        recoveredNode.isAlive = true;
                        recoveredNode.updateHeartbeat();
                        System.out.println(" Marchez si local nodul " + recoveredNodeId + " ca ACTIV");
                        System.out.print("> ");
                    }
                }
                break;
        }
    }

    private void sendHeartbeats() {
        for (NodeInfo peer : peers.values()) {
            sendMessage(peer, "HEARTBEAT|" + nodeId);
        }
    }

    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        List<String> failedNodes = new ArrayList<>();

        for (NodeInfo peer : peers.values()) {
            if (peer.checkTimeout(currentTime)) {
                // Nodul tocmai a cazut
                failedNodes.add(peer.id);
                System.out.println("\n ALERTA: Nodul " + peer.id + " nu mai raspunde!");
                System.out.print("> ");
            }
        }

        // Trimite notificari catre toate celelalte noduri pentru fiecare nod cazut
        for (String failedNodeId : failedNodes) {
            broadcastNodeFailure(failedNodeId);
        }
    }

    /**
     * Anunta toate nodurile active ca un nod a cazut
     */
    private void broadcastNodeFailure(String failedNodeId) {
        String notification = "NODE_FAILED|" + nodeId + "|" + failedNodeId;
        int notificationsSent = 0;

        for (NodeInfo peer : peers.values()) {
            // Nu trimite notificare catre nodul cazut sau catre sine
            if (!peer.id.equals(failedNodeId) && peer.isAlive) {
                sendMessage(peer, notification);
                notificationsSent++;
            }
        }

        if (notificationsSent > 0) {
            System.out.println(" Notificare NODE_FAILED trimisa catre " +
                    notificationsSent + " noduri pentru " + failedNodeId);
            System.out.print("> ");
        }
    }

    /**
     * Anunta toate nodurile ca un nod s-a reconectat
     */
    private void broadcastNodeRecovery(String recoveredNodeId) {
        String notification = "NODE_RECOVERED|" + nodeId + "|" + recoveredNodeId;
        int notificationsSent = 0;

        for (NodeInfo peer : peers.values()) {
            if (peer.isAlive && !peer.id.equals(recoveredNodeId)) {
                sendMessage(peer, notification);
                notificationsSent++;
            }
        }

        if (notificationsSent > 0) {
            System.out.println(" Notificare NODE_RECOVERED trimisa catre " +
                    notificationsSent + " noduri pentru " + recoveredNodeId);
            System.out.print("> ");
        }
    }

    private void sendMessage(NodeInfo peer, String message) {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = message.getBytes();
            InetAddress address = InetAddress.getByName(peer.host);
            DatagramPacket packet = new DatagramPacket(data, data.length, address, peer.port);
            socket.send(packet);
        } catch (IOException e) {
            // Eroare silentioasa pentru heartbeat-uri
        }
    }

    private void handleConsoleInput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            System.out.print("> ");

            while (running && (line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    System.out.print("> ");
                    continue;
                }

                String[] parts = line.split("\\s+", 3);
                String command = parts[0].toLowerCase();

                switch (command) {
                    case "status":
                        showStatus();
                        break;

                    case "send":
                        if (parts.length < 3) {
                            System.out.println("Utilizare: send <id_nod> <mesaj>");
                        } else {
                            sendUserMessage(parts[1], parts[2]);
                        }
                        break;

                    case "exit":
                        stop();
                        return;

                    default:
                        System.out.println("Comanda necunoscuta: " + command);
                }

                if (running) {
                    System.out.print("> ");
                }
            }
        } catch (IOException e) {
            System.err.println("Eroare la citirea de la consola: " + e.getMessage());
        }
    }

    private void showStatus() {
        System.out.println("\n=== Status noduri ===");
        System.out.println("Nodul curent: " + nodeId + " (port " + port + ")");
        System.out.println("\nPeers:");

        for (NodeInfo peer : peers.values()) {
            String status = peer.isAlive ? "✓ ACTIV" : "✗ INACTIV";
            long lastSeen = (System.currentTimeMillis() - peer.lastHeartbeat) / 1000;
            System.out.printf("  %s: %s (ultimul heartbeat: %ds)%n",
                    peer.id, status, lastSeen);
        }
        System.out.println();
    }

    private void sendUserMessage(String targetId, String message) {
        NodeInfo peer = peers.get(targetId);
        if (peer == null) {
            System.out.println("Nodul " + targetId + " nu exista!");
            return;
        }

        if (!peer.isAlive) {
            System.out.println("Nodul " + targetId + " este inactiv!");
            return;
        }

        String fullMessage = "MESSAGE|" + nodeId + "|" + message;
        sendMessage(peer, fullMessage);
        System.out.println("Mesaj trimis catre " + targetId);
    }

    public void stop() {
        System.out.println("\nOprire nod " + nodeId + "...");
        running = false;
        scheduler.shutdown();
        threadPool.shutdown();

        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            threadPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Nod oprit.");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Utilizare: java HeartbeatNode <id_nod> <port> [<peer_id> <peer_host> <peer_port>]...");
            System.out.println("\nExemplu pentru 3 noduri:");
            System.out.println("  Masina 1: java HeartbeatNode node1 5001 node2 192.168.1.102 5002 node3 192.168.1.103 5003");
            System.out.println("  Masina 2: java HeartbeatNode node2 5002 node1 192.168.1.101 5001 node3 192.168.1.103 5003");
            System.out.println("  Masina 3: java HeartbeatNode node3 5003 node1 192.168.1.101 5001 node2 192.168.1.102 5002");
            System.exit(1);
        }

        String nodeId = args[0];
        int port = Integer.parseInt(args[1]);

        HeartbeatNode node = new HeartbeatNode(nodeId, port);

        // Adauga peers din argumentele liniei de comanda
        for (int i = 2; i < args.length; i += 3) {
            if (i + 2 < args.length) {
                String peerId = args[i];
                String peerHost = args[i + 1];
                int peerPort = Integer.parseInt(args[i + 2]);
                node.addPeer(peerId, peerHost, peerPort);
            }
        }

        node.start();


        Runtime.getRuntime().addShutdownHook(new Thread(node::stop));
    }
}