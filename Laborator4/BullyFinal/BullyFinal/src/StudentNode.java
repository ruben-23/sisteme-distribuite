import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Complete implementation of Bully algorithm node
 */
public class StudentNode {

    public static class PeerInfo {
        final int id;
        final String ip;
        final int port;

        public PeerInfo(int id, String ip, int port) {
            this.id = id;
            this.ip = ip;
            this.port = port;
        }

        @Override
        public String toString() {
            return id + "@" + ip + ":" + port;
        }
    }

    private final String serverHost;
    private final int serverPort;
    private final int myId;
    private final int listenPort;

    private Socket registrySocket;
    private PrintWriter registryOut;
    private BufferedReader registryIn;

    private final Map<Integer, PeerInfo> peers = new ConcurrentHashMap<>();

    private volatile Integer currentLeader = null;
    private final AtomicBoolean inElection = new AtomicBoolean(false);

    // Track OK responses received
    private volatile boolean receivedOK = false;

    // Election timeout in milliseconds
    private static final long ELECTION_TIMEOUT = 3000;

    // Heartbeat
    private static final long HEARTBEAT_INTERVAL = 2000L;   // 2 secunde
    private static final long HEARTBEAT_TIMEOUT  = 6000L;   // 6 secunde (3 intervale ratate)

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile long lastHeartbeatReceived = System.currentTimeMillis();

    // Thread-ul care trimite heartbeat (doar liderul)
    private ScheduledFuture<?> heartbeatSenderTask = null;

    // Timer-ul care detecteaza lipsa heartbeat-ului
    private ScheduledFuture<?> heartbeatWatcherTask = null;

    private volatile boolean running = true;
    private ServerSocket p2pServerSocket = null;

    public StudentNode(String serverHost, int serverPort, int myId, int listenPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.myId = myId;
        this.listenPort = listenPort;
    }

    public void start() throws IOException {
        System.out.println("Pornez nod student:");
        System.out.println("  ID          = " + myId);
        System.out.println("  ListenPort  = " + listenPort);
        System.out.println("  Registry    = " + serverHost + ":" + serverPort);

        Thread p2pServerThread = new Thread(this::p2pServerLoop, "P2P-Server");
        p2pServerThread.setDaemon(true);
        p2pServerThread.start();

        registrySocket = new Socket(serverHost, serverPort);
        registryOut = new PrintWriter(new OutputStreamWriter(registrySocket.getOutputStream()), true);
        registryIn  = new BufferedReader(new InputStreamReader(registrySocket.getInputStream()));

        String regCmd = "REGISTER " + myId + " " + listenPort;
        System.out.println("Catre registry: " + regCmd);
        registryOut.println(regCmd);

        Thread registryReaderThread = new Thread(this::registryReadLoop, "Registry-Reader");
        registryReaderThread.setDaemon(true);
        registryReaderThread.start();

        consoleLoop();

        try {
            registrySocket.close();
        } catch (IOException ignored) {}
        System.out.println("Nod student oprit.");
    }

    private void shutdownAndExit(int code) {
        running = false;
        stopHeartbeatSender();
        stopHeartbeatWatcher();
        scheduler.shutdownNow();
        System.out.println("Shutting down...");

        try {
            if (registrySocket != null && !registrySocket.isClosed()) {
                registrySocket.close();
            }
        } catch (IOException ignored) {}

        try {
            if (p2pServerSocket != null && !p2pServerSocket.isClosed()) {
                p2pServerSocket.close();
            }
        } catch (IOException ignored) {}

        System.exit(code);
    }

    // ================== SERVER P2P ==================

    private void p2pServerLoop() {
        try {
            p2pServerSocket = new ServerSocket(listenPort);
            System.out.println("P2P server asculta pe portul " + listenPort);
            while (running) {
                Socket s = p2pServerSocket.accept();
                new Thread(() -> handlePeerConnection(s),
                        "P2P-Client-" + s.getRemoteSocketAddress()).start();
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Eroare la P2P server: " + e.getMessage());
            }
        }
    }

    private void handlePeerConnection(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);

            String remote = s.getRemoteSocketAddress().toString();
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                System.out.println("[P2P from " + remote + "] " + line);
                handlePeerMessage(line, out);
            }

        } catch (IOException e) {
            // conexiune P2P inchisa
        }
        finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePeerMessage(String line, PrintWriter replyOut) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toUpperCase(Locale.ROOT);

        try {
            switch (cmd) {
                case "ELECTION":
                    if (parts.length == 2) {
                        int fromId = Integer.parseInt(parts[1]);
                        onElectionMessage(fromId, replyOut);
                    }
                    break;

                case "OK":
                    if (parts.length == 2) {
                        int fromId = Integer.parseInt(parts[1]);
                        onOkMessage(fromId);
                    }
                    break;

                case "COORDINATOR":
                    if (parts.length == 2) {
                        int leaderId = Integer.parseInt(parts[1]);
                        onCoordinatorMessage(leaderId);
                    }
                    break;

                case "HEARTBEAT":
                    if (parts.length == 2) {
                        int fromLeader = Integer.parseInt(parts[1]);

                        if (currentLeader == null || currentLeader != fromLeader) {

                            // Notificam si registry-ul ca stim liderul
                            if (registryOut != null) {
                                registryOut.println("LEADER " + fromLeader);
                            }

                            // verificare daca id-ul e mai mare decat liderul
                            checkAndStartElectionIfBetterLeader(fromLeader);

                            // Pornim watcher-ul daca nu suntem noi liderul
                            if (myId != fromLeader) {
                                startHeartbeatWatcher();
                                lastHeartbeatReceived = System.currentTimeMillis();
                            }
                        } else {
                            // Liderul e deja cunoscut -> normal heartbeat
                            lastHeartbeatReceived = System.currentTimeMillis();
                            System.out.println("Heartbeat de la liderul cunoscut " + fromLeader);
                        }
                    }
                    break;

                default:
                    System.out.println("Comanda P2P necunoscuta: " + line);
            }
        } catch (NumberFormatException e) {
            System.out.println("Eroare parsare mesaj P2P: " + line + " / " + e);
        }
    }

    // ================== REGISTRY SERVER ==================

    private void registryReadLoop() {
        String line;
        try {
            while ((line = registryIn.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if ("PING".equalsIgnoreCase(line)) {
                    System.out.println("[REGISTRY] PING (raspund cu PONG)");
                    registryOut.println("PONG");
                    continue;
                }

                System.out.println("[REGISTRY] " + line);
                handleRegistryMessage(line);
            }
        } catch (IOException e) {
            System.out.println("Conexiunea la registry s-a inchis: " + e.getMessage());
        }
    }

    private void handleRegistryMessage(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toUpperCase(Locale.ROOT);

        switch (cmd) {
            case "ACCEPT":
                System.out.println("Inregistrare acceptata pentru ID " + (parts.length >= 2 ? parts[1] : "?"));
                break;

            case "REJECT":
                System.out.println("Inregistrare respinsa: " + line);
                shutdownAndExit(1);
                break;

            case "LIST":
                updatePeersFromList(parts);
                break;

            case "LEADER":
                if (parts.length == 2) {
                    try {
                        int leaderId = Integer.parseInt(parts[1]);
                        System.out.println("Registry anunta liderul = " + leaderId);
                        currentLeader = leaderId;
                    } catch (NumberFormatException ignored) {}
                }
                break;

            default:
                break;
        }
    }

    private void updatePeersFromList(String[] parts) {
        peers.clear();
        for (int i = 1; i < parts.length; i++) {
            String token = parts[i];
            try {
                String[] idAndRest = token.split("@");
                int id = Integer.parseInt(idAndRest[0]);
                String[] ipAndPort = idAndRest[1].split(":");
                String ip = ipAndPort[0];
                int port = Integer.parseInt(ipAndPort[1]);

                if (id == myId) {
                    continue;
                }

                peers.put(id, new PeerInfo(id, ip, port));
            } catch (Exception e) {
                System.out.println("Nu pot parsa peer din LIST: " + token + " / " + e);
            }
        }
        System.out.println("Peers actualizati: " + peers.values());

        updateLeaderFromPeersIfUnknown();

    }


    /**
     * Daca nu stiu cine e liderul, calculez local cel mai mare ID dintre mine + toti peerii cunoscuti.
     */
    private void updateLeaderFromPeersIfUnknown() {
        if (currentLeader != null) {
            return; // deja stiu liderul => nimic de facut
        }

        if (peers.isEmpty()) {
            // Sunt singurul nod in sistem
            System.out.println(">>> Nu am peers si nu stiu liderul → eu sunt singurul → devin lider!");
            becomeLeader();
            return;
        }

        // Gasim cel mai mare ID dintre toti (inclusiv eu)
        int maxId = myId;
        for (Integer id : peers.keySet()) {
            if (id > maxId) {
                maxId = id;
            }
        }

        System.out.println(">>> Nu stiam liderul → calculat local din " + (peers.size() + 1)
                + " noduri → liderul este " + maxId);

        currentLeader = maxId;

        if (maxId == myId) {
            System.out.println(">>> EU am cel mai mare ID → devin lider acum!");
            becomeLeader();
        } else {
            System.out.println(">>> Accept liderul calculat local: " + maxId);
            if (registryOut != null) {
                registryOut.println("LEADER " + maxId);
            }
            startHeartbeatWatcher();
            lastHeartbeatReceived = System.currentTimeMillis();
        }
    }

    /**
     * Start election procedure following Bully algorithm
     */
    public void startElection() {
        if (!inElection.compareAndSet(false, true)) {
            System.out.println("Deja sunt in alegeri.");
            return;
        }

        System.out.println(">>> Pornesc ELECTION. Eu = " + myId);

        // Reset OK tracking
        receivedOK = false;

        // Send ELECTION to all nodes with higher ID
        List<PeerInfo> higherNodes = new ArrayList<>();
        for (PeerInfo p : peers.values()) {
            if (p.id > myId) {
                higherNodes.add(p);
                sendP2PMessage(p, "ELECTION " + myId);
            }
        }

        if (higherNodes.isEmpty()) {
            // No nodes with higher ID - become leader immediately
            System.out.println("Nu exista noduri cu ID mai mare. Devin lider.");
            becomeLeader();
        }

        System.out.println("Am trimis ELECTION la " + higherNodes.size() + " noduri superioare. Astept OK...");

        // daca nu primesc niciun OK → sunt cel mai mare
        scheduler.schedule(() -> {
            if (!receivedOK && inElection.get()) {
                System.out.println("Timeout: niciun OK primit → eu sunt liderul!");
                becomeLeader();
            } else if (receivedOK) {
                System.out.println("Am primit OK → un nod superior va deveni lider. Astept COORDINATOR.");
            }
            // Nu reiau alegerile automat!
        }, ELECTION_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    /**
     * Handle ELECTION message from another node
     */
    private void onElectionMessage(int fromId, PrintWriter replyOut) {
        System.out.println("<<< ELECTION de la " + fromId);

        // Always respond with OK if we have higher ID
        if (myId > fromId) {
            System.out.println("ID-ul meu (" + myId + ") > " + fromId + " - trimit OK");
            replyOut.println("OK " + myId);

            // Start our own election if not already in one
            if (!inElection.get()) {
                System.out.println("Pornesc propria alegere...");
                new Thread(() -> startElection(), "Auto-Election").start();
            }
        } else {
            System.out.println("ID-ul meu (" + myId + ") < " + fromId + " - nu raspund");
        }
    }

    /**
     * Handle OK message - indicates a higher node exists
     */
    private void onOkMessage(int fromId) {
        System.out.println("<<< OK de la " + fromId + " (nod superior exista)");
        receivedOK = true;

        // A higher node exists, so we won't become leader
        // We'll wait for COORDINATOR message
    }

    /**
     * Handle COORDINATOR message - new leader announced
     */
    private void onCoordinatorMessage(int leaderId) {
        System.out.println("<<< COORDINATOR: lider = " + leaderId);
        currentLeader = leaderId;
        inElection.set(false);

        // Notify registry server
        if (registryOut != null) {
            registryOut.println("LEADER " + leaderId);
        }

        if (leaderId != myId) {
            stopHeartbeatSender();        // opresc in caz ca eram lider inainte
            startHeartbeatWatcher();      // pornesc monitorizarea
            lastHeartbeatReceived = System.currentTimeMillis();
        }
    }

    /**
     * Become leader and announce to all nodes
     */
    private void becomeLeader() {
        System.out.println(">>> EU sunt liderul nou! ID = " + myId);
        currentLeader = myId;
        inElection.set(false);

        // Announce to all other nodes
        for (PeerInfo p : peers.values()) {
            sendP2PMessage(p, "COORDINATOR " + myId);
        }

        // Notify registry server
        if (registryOut != null) {
            registryOut.println("LEADER " + myId);
        }

        startHeartbeatSender();
        stopHeartbeatWatcher(); // nu mai am nevoie sa ascult heartbeat de la altcineva

    }

    /**
     * Verifica daca liderul curent are ID mai mic decat al meu → declansez alegeri
     */
    private void checkAndStartElectionIfBetterLeader(int knownLeaderId) {
        if (knownLeaderId < myId) {
            System.out.println("Liderul curent (" + knownLeaderId + ") are ID mai mic decat al meu (" + myId + ")!");
            System.out.println(">>> Pornesc automat alegeri pentru ca merit sa fiu lider!");
            new Thread(this::startElection, "Auto-Election-On-Join").start();
        } else {
            System.out.println("Liderul curent (" + knownLeaderId + ") are ID >= decat al meu (" + myId + ") → accept.");
        }
    }

    // ================== P2P MESSAGING ==================

    private void sendP2PMessage(PeerInfo peer, String message) {
        try (Socket s = new Socket(peer.ip, peer.port);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {

            out.println(message);
            System.out.println(">>> P2P catre " + peer + " : " + message);

        } catch (IOException e) {
            System.out.println("Nu pot trimite P2P catre " + peer + " : " + e.getMessage());
        }
    }

    private void startHeartbeatSender() {
        if (heartbeatSenderTask != null && !heartbeatSenderTask.isCancelled()) {
            return; // deja ruleaza
        }

        heartbeatSenderTask = scheduler.scheduleAtFixedRate(() -> {
            if (currentLeader != myId) {
                stopHeartbeatSender();
                return;
            }

            System.out.println("Trimit HEARTBEAT ca lider...");
            for (PeerInfo p : peers.values()) {
                sendP2PMessage(p, "HEARTBEAT " + myId);
            }
        }, 0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeatSender() {
        if (heartbeatSenderTask != null) {
            heartbeatSenderTask.cancel(true);
            heartbeatSenderTask = null;
        }
    }

    private void startHeartbeatWatcher() {
        if (heartbeatWatcherTask != null && !heartbeatWatcherTask.isCancelled()) {
            return;
        }

        heartbeatWatcherTask = scheduler.scheduleWithFixedDelay(() -> {
            if (currentLeader == myId || currentLeader == null) {
                stopHeartbeatWatcher();
                return;
            }

            long timeSinceLast = System.currentTimeMillis() - lastHeartbeatReceived;
            if (timeSinceLast > HEARTBEAT_TIMEOUT) {
                System.out.println("LEADER CRASH DETECTAT! Nu am primit heartbeat de " + timeSinceLast + "ms");
                currentLeader = null;
                stopHeartbeatWatcher();
                startElection(); // pornim alegeri imediat
            }
        }, 1, 1, TimeUnit.SECONDS); // verificam la fiecare secunda
    }

    private void stopHeartbeatWatcher() {
        if (heartbeatWatcherTask != null) {
            heartbeatWatcherTask.cancel(true);
            heartbeatWatcherTask = null;
        }
    }

    // ================== CONSOLE INTERFACE ==================

    private void consoleLoop() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Comenzi disponibile:");
        System.out.println("  E           - porneste ELECTION");
        System.out.println("  L           - afiseaza liderul curent");
        System.out.println("  P           - afiseaza peers cunoscuti");
        System.out.println("  LIST        - cere din nou lista de peers de la registry");
        System.out.println("  Q / QUIT    - iesire");
        System.out.println();

        while (true) {
            System.out.print("> ");
            String line;
            try {
                line = scanner.nextLine();
            } catch (Exception e) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.equals("Q") || upper.equals("QUIT")) {
                break;
            } else if (upper.equals("E")) {
                startElection();
            } else if (upper.equals("L")) {
                if (currentLeader == null) {
                    System.out.println("Lider curent = NECUNOSCUT");
                } else if (currentLeader == myId) {
                    System.out.println("Lider curent = EU (" + myId + ")");
                } else {
                    System.out.println("Lider curent = " + currentLeader);
                }
            } else if (upper.equals("P")) {
                System.out.println("Peers cunoscuti (" + peers.size() + "):");
                for (PeerInfo p : peers.values()) {
                    System.out.println("  " + p);
                }
            } else if (upper.equals("LIST")) {
                registryOut.println("LIST");
            } else {
                System.out.println("Comanda necunoscuta.");
            }
        }
    }

    // ================== MAIN ==================

    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("Utilizare: java StudentNode <serverHost> <serverPort> <ID> <listenPort>");
            System.out.println("Exemplu:  java StudentNode 192.168.0.10 5000 4500 6000");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int id = Integer.parseInt(args[2]);
        int listenPort = Integer.parseInt(args[3]);

        StudentNode node = new StudentNode(host, port, id, listenPort);
        try {
            node.start();
        } catch (IOException e) {
            System.err.println("Eroare la nodul student: " + e.getMessage());
        }
    }
}