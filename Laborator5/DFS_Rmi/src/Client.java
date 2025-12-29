import java.rmi.Naming;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Client - varianta cu TODO's pentru studenti.
 *
 * Operatii:
 *   1. Write:
 *      - utilizatorul introduce un mesaj (sir de caractere)
 *      - se calculeaza un hash de 3 caractere (vezi computeHash3)
 *      - clientul apeleaza master.write(hash, mesaj)
 *
 *   2. Read:
 *      - utilizatorul introduce hash-ul
 *      - clientul apeleaza master.read(hash)
 *      - AFISAREA rezultatului este TODO (1) pentru studenti.
 *
 *   3. List:
 *      - clientul cere master.listReplicas()
 *      - pentru fiecare replica, apeleaza replica.listFiles() si afiseaza (hash -> mesaj).
 *
 * TODO (1): implementati metoda handleRead(...) din acest client.
 */
public class Client {

    private static final String MASTER_NAME = "MasterServer";
    private static final String REPLICA_PREFIX = "ReplicaServer";

    // clientul trebuie sa porneasca si sa opreasca Master + replici:
    private static final List<Process> startedProcesses = new ArrayList<>();

    public static void main(String[] args) {
        MasterServerInterface master = null;

        try {
            // OPTIONAL: daca nu vrei sa porneasca procesele de aici, comenteaza urmatoarea linie
//            startServers();

            // Conectare la Master prin RMI
            master = (MasterServerInterface) Naming.lookup("//localhost/" + MASTER_NAME);
            System.out.println("Conectat la MasterServer.");

            // pornim meniul interactiv
            runMenu(master);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // OPTIONAL: opreste procesele pornite de client
            stopServers();
        }
    }

    // ---------------------------------------------------------
    // Pornire / oprire procese server (optional, ca in Client3)
    // ---------------------------------------------------------
    private static void startServers() throws Exception {
        System.out.println("Pornesc MasterServer...");
        Process master = new ProcessBuilder("java", "MasterServer")
                .inheritIO()
                .start();
        startedProcesses.add(master);
        Thread.sleep(1500);

        for (int i = 1; i <= 3; i++) {
            System.out.println("Pornesc ReplicaServer " + i + "...");
            Process replica = new ProcessBuilder("java", "ReplicaServer", String.valueOf(i))
                    .inheritIO()
                    .start();
            startedProcesses.add(replica);
            Thread.sleep(1000);
        }

        System.out.println("Toate serverele au fost pornite.\n");
    }

    private static void stopServers() {
        if (startedProcesses.isEmpty()) return;

        System.out.println("\nOprire procese server...");
        for (int i = startedProcesses.size() - 1; i >= 0; i--) {
            try {
                startedProcesses.get(i).destroy();
            } catch (Exception ignored) {
            }
        }
        System.out.println("Toate procesele au fost oprite.");
    }



    private static void runMenu(MasterServerInterface master) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("=== MENIU (Client5) ===");
            System.out.println("1. Write");
            System.out.println("2. Read");
            System.out.println("3. List");
            System.out.println("4. Quit");
            System.out.print("Alege optiunea: ");

            String option = scanner.nextLine().trim();
            System.out.println();

            switch (option) {
                case "1":
                    handleWrite(master, scanner);
                    break;
                case "2":
                    handleRead(master, scanner);   // TODO (1) - de completat
                    break;
                case "3":
                    handleList(master);
                    break;
                case "4":
                    System.out.println("Ies din client.");
                    return;
                default:
                    System.out.println("Optiune invalida.\n");
            }

            System.out.println();
        }
    }

    /**
     * WRITE:
     *   - citeste mesajul de la tastatura,
     *   - calculeaza hash-ul (3 caractere),
     *   - trimite (hash, mesaj) la Master (metoda write).
     */
    private static void handleWrite(MasterServerInterface master, Scanner scanner) throws Exception {
        System.out.print("Introdu mesajul de salvat: ");
        String message = scanner.nextLine();

        String hash = computeHash3(message);
        System.out.println("Hash-ul asociat mesajului este: " + hash);

        try {
            master.write(hash, message);
            System.out.println("Write reusit prin Master pentru hash " + hash);
        } catch (Exception e) {
            System.out.println("Eroare la write prin Master" );
        }
    }

    /**
     * READ:
     *   - citeste hash-ul de la tastatura,
     *   - APELEAZA master.read(hash),
     *   - AFISEAZA rezultatul.
     *
     * TODO (1): implementati aceasta metoda.
     */
    private static void handleRead(MasterServerInterface master, Scanner scanner) throws Exception {
        // TODO (1):
        //  - cititi hash-ul de la tastatura
        //  - daca este gol, afisati un mesaj si iesiti din metoda
        //  - altfel, apelati master.read(hash)
        //  - daca rezultatul este "File not found", afisati un mesaj corespunzator
        //  - altfel, afisati mesajul obtinut de la Master
        // Citim hash-ul de la tastatura
        System.out.print("Introdu hash-ul de citit: ");
        String hash = scanner.nextLine().trim();

        // Verificam daca hash-ul este gol
        if (hash.isEmpty()) {
            System.out.println("Hash-ul nu poate fi gol!");
            return;
        }

        System.out.println("Cautam mesajul asociat hash-ului: " + hash);

        try {
            // Apelam metoda read de pe Master
            String result = master.read(hash);

            // Verificam rezultatul
            if ("File not found".equals(result)) {
                System.out.println("Nu s-a gasit niciun mesaj asociat hash-ului " + hash);
            } else {
                System.out.println("Mesajul gasit: " + result);
            }
        } catch (Exception e) {
            System.out.println("Eroare la citirea prin Master: " + e.getMessage());
        }
    }

    /**
     * LIST:
     *   - cere de la Master lista de replici,
     *   - pentru fiecare replica apeleaza listFiles() si afiseaza hash-urile si mesajele.
     */
    private static void handleList(MasterServerInterface master) throws Exception {
        List<ReplicaLoc> replicas = master.listReplicas();

        if (replicas == null || replicas.isEmpty()) {
            System.out.println("Nu exista replici inregistrate la Master.");
            return;
        }

        System.out.println("Continutul replicilor (hash -> mesaj):");
        for (ReplicaLoc loc : replicas) {
            System.out.println("- Replica id=" + loc.getId() + ", host=" + loc.getHost());

            try {
                String replicaName = REPLICA_PREFIX + loc.getId();
                ReplicaServerInterface replica =
                        (ReplicaServerInterface) Naming.lookup("//" + loc.getHost() + "/" + replicaName);

                Map<String, String> files = replica.listFiles();

                if (files.isEmpty()) {
                    System.out.println("    (nu exista mesaje stocate)");
                } else {
                    for (Map.Entry<String, String> e : files.entrySet()) {
                        System.out.println("    " + e.getKey() + " -> " + e.getValue());
                    }
                }

            } catch (Exception e) {
                System.out.println("    Replica " + loc.getId() + " indisponibila: ");
            }
        }
    }

    /**
     * Hash simplu de 3 caractere (baza 36), folosit ca nume de "fisier".
     */
    private static String computeHash3(String input) {
        int h = Math.abs(input.hashCode());
        String base36 = Integer.toString(h, 36);

        if (base36.length() >= 3) {
            return base36.substring(0, 3);
        } else {
            return String.format("%3s", base36).replace(' ', '0');
        }
    }
}
