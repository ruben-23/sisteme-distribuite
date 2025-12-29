    import java.rmi.Naming;
    import java.rmi.RemoteException;
    import java.rmi.server.UnicastRemoteObject;
    import java.util.*;

    /**
     * Implementarea MasterServer.
     *
     * IMPORTANT:
     *   - momentam Master-ul tine evidenta replicilor si
     *     va contine un CATALOG de fisiere (hash -> lista de replici).
     *
     * TODO (2): gestionarea replicilor cazute
     *   - cand Master incearca sa contacteze o replica (Naming.lookup, replica.write, replica.read),
     *     trebuie sa prindeti exceptiile corespunzatoare si sa NU opriti sistemul.
     *   - afisati un mesaj de eroare in consola, dar continuati cu celelalte replici disponibile.
     *
     * TODO (3): implementarea catalogului de fisiere in Master
     *   - folositi structura fileCatalog pentru a retine, pentru fiecare hash, pe ce replici a fost salvat.
     *   - actualizati fileCatalog in metoda write(...)
     *   - folositi fileCatalog in metoda read(...) pentru a sti pe ce replici sa incercati citirea.
     */
    public class MasterServer extends UnicastRemoteObject implements MasterServerInterface {

        /**
         * replicaId (ex: "1") -> localizare replica
         */
        private final Map<String, ReplicaLoc> replicaLocations = new HashMap<>();

        /**
         * Catalog de fisiere:
         *   - cheia este hash-ul (numele "fisierului"),
         *   - valoarea este lista de replici pe care hash-ul a fost scris.
         *
         * TODO (3): folositi acest catalog in write(...) si read(...).
         */
        private final Map<String, List<ReplicaLoc>> fileCatalog = new HashMap<>();

        protected MasterServer() throws RemoteException {
            super();
        }

        @Override
        public synchronized void registerReplicaServer(String replicaId, ReplicaLoc location) throws RemoteException {
            replicaLocations.put(replicaId, location);
            System.out.println("Master: registered replica " + replicaId + " at " + location.getHost());
        }

        @Override
        public synchronized List<ReplicaLoc> listReplicas() throws RemoteException {
            return new ArrayList<>(replicaLocations.values());
        }

        /**
         * Determina o ordine de replici pentru un anumit hash si alege primele 2 (daca exista).
         * In felul acesta, aceeasi valoare de hash va fi mapata intotdeauna pe aceleasi replici.
         */
        private synchronized List<ReplicaLoc> chooseReplicasForHash(String hash) {
            List<ReplicaLoc> all = new ArrayList<>(replicaLocations.values());
            all.sort(Comparator.comparing(ReplicaLoc::getId)); // id-urile "1","2","3", ...

            if (all.isEmpty()) {
                return all;
            }

            int n = all.size();
            int base = Math.abs(hash.hashCode());
            int firstIndex = base % n;
            int secondIndex = (firstIndex + 1) % n;

            List<ReplicaLoc> result = new ArrayList<>();
            result.add(all.get(firstIndex));
            if (secondIndex != firstIndex && n > 1) {
                result.add(all.get(secondIndex));
            }
            return result;
        }

        @Override
        public synchronized void write(String hash, String message) throws RemoteException {
            List<ReplicaLoc> targets = chooseReplicasForHash(hash);
            if (targets.isEmpty()) {
                throw new RemoteException("No replicas registered.");
            }

            System.out.println("Master: WRITE hash=" + hash + " -> message=\"" + message + "\"");
            System.out.println("Master: selected targets = " + targets);

            // TODO (3): Initializam lista de replici pentru acest hash daca nu exista
            fileCatalog.putIfAbsent(hash, new ArrayList<>());
            List<ReplicaLoc> successfulReplicas = fileCatalog.get(hash);

            // Curatam lista de replici pentru acest hash (in caz de rescriere)
            successfulReplicas.clear();

            boolean atLeastOneSuccess = false;

            for (ReplicaLoc loc : targets) {
                try {
                    String replicaName = "ReplicaServer" + loc.getId();
                    ReplicaServerInterface replica =
                            (ReplicaServerInterface) Naming.lookup("//" + loc.getHost() + "/" + replicaName);

                    replica.write(hash, message);

                    // TODO (3): actualizati catalogul de fisiere (fileCatalog)
                    //  - dupa ce scrieti pe o replica, inregistrati in fileCatalog
                    //    faptul ca hash-ul exista pe aceasta replica (loc).
                    if (!successfulReplicas.contains(loc)) {
                        successfulReplicas.add(loc);
                    }

                    atLeastOneSuccess = true;
                    System.out.println("Master: WRITE reusit pe replica " + loc.getId());

                } catch (Exception e) {
                    // TODO (2): gestionati replicile cazute
                    //  - prindeti exceptia (deja este prinsa generic aici)
                    //  - afisati un mesaj prietenos in consola
                    //  - NU aruncati mai departe exceptia, ca sa puteti continua
                    //    cu celelalte replici din lista "targets".

                    System.out.println("Master: EROARE la WRITE pe replica " + loc.getId()
                            + " (host=" + loc.getHost() + ")");
                }
            }

            // Verificam daca am reusit sa scriem pe cel putin o replica
            if (!atLeastOneSuccess) {
                System.out.println("Master: ATENTIE - Nu s-a putut scrie pe nicio replica!");
                throw new RemoteException("Toate replicile tinta sunt indisponibile.");
            } else {
                System.out.println("Master: WRITE finalizat cu succes pe " + successfulReplicas.size() + " replica(e)");
            }

        }

        @Override
        public synchronized String read(String hash) throws RemoteException {
            System.out.println("Master: READ hash=" + hash);

            // TODO (3): utilizati fileCatalog pentru a afla pe ce replici ar trebui sa existe hash-ul.
            //  PENTRU MOMENT, aveti mai jos o varianta simplificata care foloseste din nou
            //  chooseReplicasForHash(hash). In exercitiu, va fi modificata de studenti.
            List<ReplicaLoc> candidates;

            if (fileCatalog.containsKey(hash)) {
                candidates = new ArrayList<>(fileCatalog.get(hash));
                System.out.println("Master: folosesc catalogul de fisiere pentru hash=" + hash);
                System.out.println("Master: replici candidate din catalog: " + candidates);
            } else {
                candidates = chooseReplicasForHash(hash);
                System.out.println("Master: NU exista intrare in catalog pentru hash=" + hash
                        + ", folosesc chooseReplicasForHash(...), candidati: " + candidates);
            }

            if (candidates.isEmpty()) {
                System.out.println("Master: Nu exista replici candidate pentru hash=" + hash);
                return "File not found";
            }


            for (ReplicaLoc loc : candidates) {
                try {
                    String replicaName = "ReplicaServer" + loc.getId();
                    ReplicaServerInterface replica =
                            (ReplicaServerInterface) Naming.lookup("//" + loc.getHost() + "/" + replicaName);

                    String result = replica.read(hash);
                    if (result != null && !"File not found".equals(result)) {
                        System.out.println("Master: citit cu succes de pe replica " + loc.getId());
                        return result;
                    } else {
                        System.out.println("Master: replica " + loc.getId() + " nu are hash-ul " + hash);
                    }

                } catch (Exception e) {
                    // TODO (2): gestionati replicile cazute la READ
                    //  - afisati un mesaj si continuati cu urmatoarea replica din lista.
//                    System.out.println("Master: EROARE la READ pe replica " + loc.getId()
//                            + " (host=" + loc.getHost() + "): " + e.getMessage());
                    System.out.println("  -> Replica este indisponibila");
                    System.out.println("  -> Incerc urmatoarea replica...");
                }
            }

            // Nu am gasit hash-ul pe nicio replica disponibila
            System.out.println("Master: Hash-ul " + hash + " nu a fost gasit pe nicio replica disponibila");
            return "File not found";
        }

        /**
         * Metoda main simpla pentru a porni MasterServer-ul.
         * Se porneste un registry pe portul 1099 daca nu exista si se face rebind pe numele "MasterServer".
         */
        public static void main(String[] args) {
            try {
                try {
                    java.rmi.registry.LocateRegistry.createRegistry(1099);
                    System.out.println("RMI registry started on port 1099.");
                } catch (RemoteException e) {
                    System.out.println("RMI registry already running.");
                }

                MasterServer master = new MasterServer();
                Naming.rebind("MasterServer", master);
                System.out.println("MasterServer is running...");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
