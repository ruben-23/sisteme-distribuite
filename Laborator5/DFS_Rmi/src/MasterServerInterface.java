import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interfata MasterServer folosita de client (Client5) si de replici.
 *
 * TODO-uri pentru studenti (legate de MasterServer):
 *   - implementarea unui catalog de fisiere in Master (vezi MasterServer.java)
 *   - gestionarea replicilor cazute atunci cand Master contacteaza un ReplicaServer
 */
public interface MasterServerInterface extends Remote {

    /**
     * Apelat de ReplicaServer pentru a se inregistra la master.
     */
    void registerReplicaServer(String replicaId, ReplicaLoc location) throws RemoteException;

    /**
     * Intoarce lista tuturor replicilor cunoscute de master.
     * Aceasta metoda este folosita de Client5 pentru operatia List.
     */
    List<ReplicaLoc> listReplicas() throws RemoteException;

    /**
     * Operatia WRITE apelata de client.
     * Master-ul decide pe ce replici se va salva mesajul pentru un anumit hash.
     */
    void write(String hash, String message) throws RemoteException;

    /**
     * Operatia READ apelata de client.
     * Master-ul determina pe ce replici ar putea exista hash-ul si incearca sa citeasca.
     */
    String read(String hash) throws RemoteException;
}
