import java.io.*;
import java.net.*;

public class Proces {
    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.out.println("Utilizare: java Proces <ID> <IP_urmator> <Port_urmator> <Port_ascultare>");
            System.exit(1);
        }

        String id = args[0];
        String ipUrmator = args[1];
        int portUrmator = Integer.parseInt(args[2]);
        int portAscultare = Integer.parseInt(args[3]);

        System.out.println(id + ": Pornesc. Ascult pe portul " + portAscultare + ", trimit la " + ipUrmator + ":" + portUrmator);

        // ServerSocket pentru a asculta
        try (ServerSocket serverSocket = new ServerSocket(portAscultare)) {
            // thread pentru conectarea la urmatorul proces
            final PrintWriter[] outToNext = {null}; // folosire array pt a partaja PrintWriter intre thread-uri
            Thread connectThread = new Thread(() -> {
                while (outToNext[0] == null) {
                    try {
                        Socket socketToNext = new Socket(ipUrmator, portUrmator);
                        outToNext[0] = new PrintWriter(socketToNext.getOutputStream(), true);
                        System.out.println(id + ": Conectat la urmatorul proces.");
                    } catch (IOException e) {
                        System.out.println(id + ": Nu pot conecta la urmatorul proces, retry in 1 secunda...");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {}
                    }
                }
            });
            connectThread.start();

            // P1 va trimite primul mesaj
            if (id.equals("P1")) {
                String mesajInitial = "Salut de la P1";
                System.out.println(id + ": Trimit mesaj initial: " + mesajInitial);
                // se asteapta o conexiune
                while (outToNext[0] == null) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                }
                outToNext[0].println(mesajInitial);
            }

            // thread pentru a asculta mesaje
            Thread listenThread = new Thread(() -> {
                try {
                    Socket socketFromPrev = serverSocket.accept();
                    System.out.println(id + ": Am primit conexiune de la procesul anterior.");
                    try (BufferedReader inFromPrev = new BufferedReader(new InputStreamReader(socketFromPrev.getInputStream()))) {
                        String mesaj;
                        while ((mesaj = inFromPrev.readLine()) != null) {
                            System.out.println(id + ": Am primit mesaj: " + mesaj);
                            synchronized (outToNext) {
                                if (outToNext[0] != null) {
                                    System.out.println(id + ": Trimit mesaj mai departe: " + mesaj);
                                    outToNext[0].println(mesaj);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    System.out.println(id + ": Eroare la citire: " + e.getMessage());
                }
            });
            listenThread.start();

            // asteapta terminarea celor 2 threaduri
            try {
                listenThread.join();
                connectThread.join();
            } catch (InterruptedException e) {
                System.out.println(id + ": Intrerupt: " + e.getMessage());
            }

            System.out.println(id + ": Am terminat.");
        }
    }
}

