import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Map;

/**
 * Interfata pentru serverele replica.
 * Replicile sunt simple: memoreaza in memorie perechi (fileName/hash -> mesaj).
 */
public interface ReplicaServerInterface extends Remote {

    void write(String fileName, String content) throws RemoteException;

    String read(String fileName) throws RemoteException;

    /**
     * Intoarce toate fisierele stocate local sub forma (nume -> continut).
     * Folosita de Client5 pentru operatia List.
     */
    Map<String, String> listFiles() throws RemoteException;
}
