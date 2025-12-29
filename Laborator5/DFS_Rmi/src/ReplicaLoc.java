import java.io.Serializable;

/**
 * Informatii despre localizarea unei replici.
 * In aceasta versiune simplificata avem doar id si host.
 */
public class ReplicaLoc implements Serializable {

    private final String id;
    private final String host;

    public ReplicaLoc(String id, String host) {
        this.id = id;
        this.host = host;
    }

    public String getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    @Override
    public String toString() {
        return "ReplicaLoc{id='" + id + "', host='" + host + "'}";
    }
}
