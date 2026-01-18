package org.example;

import org.example.peer.PeerNode;
import org.example.util.Logger;
import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Logger.setLevel(Logger.Level.INFO);

        try {
            // Choose random port in range 6881-6889
            int port = 6881 + (int)(Math.random() * 9);
            PeerNode node = new PeerNode(port);
            node.start();

            Scanner scanner = new Scanner(System.in);
            System.out.println("\n=== LAN Torrent ===");
            System.out.println("1. Share a file");
            System.out.println("2. Download from torrent");
            System.out.println("3. Exit");
            System.out.println("4. Show all active peers and shared files");

            while (true) {
                System.out.print("\nChoose option: ");
                String choice = scanner.nextLine();

                switch (choice) {
                    case "1":
                        shareFile(node, scanner);
                        break;
                    case "2":
                        downloadFile(node, scanner);
                        break;
                    case "3":
                        node.stop();
                        System.exit(0);
                        break;
                    case "4":
                        node.showNetworkStatus();
                        break;
                    default:
                        System.out.println("Invalid option");
                }
            }
        } catch (Exception e) {
            Logger.error("Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void shareFile(PeerNode node, Scanner scanner) {
        try {
            System.out.print("Enter file path: ");
            String filePath = scanner.nextLine();
            File file = new File(filePath);

            if (!file.exists()) {
                System.out.println("File not found!");
                return;
            }

            System.out.print("Enter download directory: ");
            String downloadDir = scanner.nextLine();

            node.shareFile(file, new File(downloadDir));
            System.out.println("File is now being shared!");
            System.out.println("Torrent file created: " + file.getName() + ".torrent");
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void downloadFile(PeerNode node, Scanner scanner) {
        try {
            System.out.print("Enter .torrent file path: ");
            String torrentPath = scanner.nextLine();
            File torrentFile = new File(torrentPath);

            if (!torrentFile.exists()) {
                System.out.println("Torrent file not found!");
                return;
            }

            System.out.print("Enter download directory: ");
            String downloadDir = scanner.nextLine();

            node.downloadFromTorrent(torrentFile, new File(downloadDir));
            System.out.println("Download started!");
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}