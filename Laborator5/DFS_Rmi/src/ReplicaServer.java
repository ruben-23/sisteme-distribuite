import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementarea unei replici simple.
 *
 * Pentru simplitate, datele sunt stocate doar in memorie:
 *   - nu persista pe disc,
 *   - se pierd la repornirea procesului.
 */
public class ReplicaServer extends UnicastRemoteObject implements ReplicaServerInterface {

    private final String id;
    private final Map<String, String> fileStorage = new HashMap<>();

    protected ReplicaServer(String id) throws RemoteException {
        super();
        this.id = id;
    }

    @Override
    public synchronized void write(String fileName, String content) throws RemoteException {
        fileStorage.put(fileName, content);
        System.out.println("Replica " + id + ": stored " + fileName + " -> " + content);
    }

    @Override
    public synchronized String read(String fileName) throws RemoteException {
        String v = fileStorage.get(fileName);
        if (v == null) {
            return "File not found";
        }
        return v;
    }

    @Override
    public synchronized Map<String, String> listFiles() throws RemoteException {
        // intoarcem o copie a map-ului intern
        return new HashMap<>(fileStorage);
    }

    /**
     * Porneste o replica cu un anumit id si o inregistreaza la MasterServer.
     * Se asteapta un argument in linia de comanda: id-ul (ex: 1, 2, 3, ...).
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java ReplicaServer <id>");
            System.exit(1);
        }

        String id = args[0];

        try {
            ReplicaServer server = new ReplicaServer(id);

            String name = "ReplicaServer" + id;
            Naming.rebind(name, server);

            // Inregistrare la Master
            MasterServerInterface master =
                    (MasterServerInterface) Naming.lookup("//localhost/MasterServer");
            ReplicaLoc loc = new ReplicaLoc(id, "localhost");
            master.registerReplicaServer(id, loc);

            System.out.println("ReplicaServer " + id + " running...");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
