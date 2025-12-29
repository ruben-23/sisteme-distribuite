import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class BullyRegistryServer {

    private static final int PING_INTERVAL_MS = 5000;      // la fiecare 5 secunde
    private static final int PING_TIMEOUT_MS   = 8000;      // daca nu raspunde in 8s → inactiv

    // Informatii despre un nod inregistrat
    private static class Node {
        final int id;
        final String ip;
        final int port;
        final Socket socket;
        final PrintWriter out;
        final long registeredAt;
        volatile long lastPong = System.currentTimeMillis();

        Node(int id, String ip, int port, Socket socket, PrintWriter out) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.socket = socket;
            this.out = out;
            this.registeredAt = System.currentTimeMillis();
        }

        String getAddress() {
            return id + "@" + ip + ":" + port;
        }

        @Override
        public String toString() {
            return getAddress();
        }
    }

    private final int serverPort;
    private final Map<Integer, Node> nodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public BullyRegistryServer(int serverPort) {
        this.serverPort = serverPort;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(serverPort);
        System.out.println("BullyRegistryServer pornit pe portul " + serverPort);
        System.out.println("Astept conexiuni de la noduri...");

        // Thread pentru PING periodic + curatare noduri inactive
        scheduler.scheduleAtFixedRate(this::sendPingsAndCleanup,
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            new Thread(() -> handleClient(clientSocket),
                    "ClientHandler-" + clientSocket.getRemoteSocketAddress()).start();
        }
    }

    private void handleClient(Socket socket) {
        String clientIp = socket.getInetAddress().getHostAddress();
        System.out.println("Conexiune noua de la " + clientIp + ":" + socket.getPort());

        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                System.out.println("[CLIENT " + clientIp + "] " + line);
                String[] parts = line.split("\\s+");

                if ("REGISTER".equalsIgnoreCase(parts[0]) && parts.length == 3) {
                    int id = Integer.parseInt(parts[1]);
                    int port = Integer.parseInt(parts[2]);

                    if (nodes.containsKey(id)) {
                        System.out.println("REJECT ID " + id + " (deja folosit de " + nodes.get(id) + ")");
                        out.println("REJECT ID_DUPLICATE");
                        socket.close();
                        return;
                    }

                    Node node = new Node(id, clientIp, port, socket, out);
                    nodes.put(id, node);
                    System.out.println("ACCEPT nod " + node);
                    out.println("ACCEPT " + id);

                    broadcastNodeList();
                    broadcastCurrentLeaderIfKnown();

                } else if ("PONG".equalsIgnoreCase(line)) {
                    // Gasim nodul dupa socket (sau IP+port daca vrei mai sigur)
                    Node node = findNodeBySocket(socket);
                    if (node != null) {
                        node.lastPong = System.currentTimeMillis();
                        // System.out.println("PONG primit de la " + node.id);
                    }

                } else if ("LIST".equalsIgnoreCase(line)) {
                    sendNodeList(out);

                } else if (line.startsWith("LEADER ") && parts.length == 2) {
                    int leaderId = Integer.parseInt(parts[1]);
                    System.out.println("Nodul " + leaderId + " anunta ca este lider.");
                    broadcastLeader(leaderId);
                }
            }

        } catch (Exception e) {
            System.out.println("Eroare la client " + clientIp + ": " + e.getMessage());
        } finally {
            // Clientul s-a deconectat brusc → il stergem
            Node disconnected = findNodeBySocket(socket);
            if (disconnected != null) {
                System.out.println("Client deconectat brusc: " + disconnected);
                nodes.remove(disconnected.id);
                broadcastNodeList();
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private Node findNodeBySocket(Socket s) {
        for (Node n : nodes.values()) {
            if (n.socket == s) return n;
        }
        return null;
    }

    private void sendPingsAndCleanup() {
        long now = System.currentTimeMillis();
        List<Node> toRemove = new ArrayList<>();

        for (Node node : nodes.values()) {
            try {
                node.out.println("PING");
                // System.out.println("PING trimis catre " + node.id);
            } catch (Exception e) {
                System.out.println("Nu pot trimite PING catre " + node.id + " (probabil deconectat)");
                toRemove.add(node);
            }

            // Verificam timeout PONG
            if (now - node.lastPong > PING_TIMEOUT_MS) {
                System.out.println("Timeout PONG de la nod " + node.id + " → eliminat");
                toRemove.add(node);
            }
        }

        if (!toRemove.isEmpty()) {
            for (Node n : toRemove) {
                nodes.remove(n.id);
                try { n.socket.close(); } catch (IOException ignored) {}
            }
            broadcastNodeList();
            System.out.println("Noduri ramase: " + nodes.keySet());
        }
    }

    private void broadcastNodeList() {
        StringBuilder listMsg = new StringBuilder("LIST");
        for (Node n : nodes.values()) {
            listMsg.append(" ").append(n.getAddress());
        }

        String message = listMsg.toString();
        System.out.println("Trimit LIST tuturor: " + message);

        for (Node n : nodes.values()) {
            try {
                n.out.println(message);
            } catch (Exception e) {
                System.out.println("Eroare trimitere LIST catre " + n.id);
            }
        }
    }

    private void sendNodeList(PrintWriter out) {
        StringBuilder listMsg = new StringBuilder("LIST");
        for (Node n : nodes.values()) {
            listMsg.append(" ").append(n.getAddress());
        }
        out.println(listMsg.toString());
    }

    private void broadcastCurrentLeaderIfKnown() {
        // Daca exista deja un lider cunoscut (ex: dupa crash si realegere), il retransmitem
        // (optional – in codul tau actual liderul e anuntat doar de noduri)
        // Poti pastra un camp privat int currentLeader = -1; daca vrei sa tii evidenta aici
    }

    private void broadcastLeader(int leaderId) {
        String msg = "LEADER " + leaderId;
        System.out.println("Anunt lider tuturor: " + msg);
        for (Node n : nodes.values()) {
            try {
                n.out.println(msg);
            } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Utilizare: java BullyRegistryServer <port>");
            System.out.println("Exemplu: java BullyRegistryServer 5000");
            return;
        }

        int port = Integer.parseInt(args[0]);
        BullyRegistryServer server = new BullyRegistryServer(port);

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Eroare server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}