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

        // server pt a primi mesaj de la procesul anterior
        ServerSocket serverSocket = new ServerSocket(portAscultare);

        // socket pentru a trimite mesajul la procesul urmator
        Socket socketToNext = null;
        PrintWriter outToNext = null;

        // conectare la urmatorul proces
        while (socketToNext == null) {
            try {
                socketToNext = new Socket(ipUrmator, portUrmator);
                outToNext = new PrintWriter(socketToNext.getOutputStream(), true);
            } catch (IOException e) {
                System.out.println(id + ": Nu pot conecta la urmatorul proces, retry in 1 secunda...");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        System.out.println(id + ": Conectat la urmatorul proces.");

        if (id.equals("P1")) {
            // P1 va trimite primul mesaj
            String mesajInitial = "Salut de la P1";
            System.out.println(id + ": Trimit mesaj initial: " + mesajInitial);
            outToNext.println(mesajInitial);
        }

        // se asterapta conexiunea de la procesul anterior
        Socket socketFromPrev = serverSocket.accept();
        System.out.println(id + ": Am primit conexiune de la procesul anterior.");
        BufferedReader inFromPrev = new BufferedReader(new InputStreamReader(socketFromPrev.getInputStream()));

        // citire mesaj
        String mesaj = inFromPrev.readLine();
        System.out.println(id + ": Am primit mesaj: " + mesaj);

        // trimitere mesaj la urmatorul proces
        System.out.println(id + ": Trimit mesaj mai departe: " + mesaj);
        outToNext.println(mesaj);

        inFromPrev.close();
        socketFromPrev.close();
        outToNext.close();
        socketToNext.close();
        serverSocket.close();

        System.out.println(id + ": Am terminat.");
    }
}

