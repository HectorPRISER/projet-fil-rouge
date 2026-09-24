package benchmarks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// Noeud maitre : partitionne l'espace de recherche par (longueur, 1er caractere) et
// distribue les blocs a des noeuds esclaves distants via TCP. Chaque connexion entrante
// est un worker ; le maitre lui envoie des blocs un par un tant que la file n'est pas
// vide et que personne n'a trouve le mot, puis STOP. Des qu'un worker annonce FOUND, le
// maitre diffuse STOP a tous les autres (equivalent reseau du cancel() de
// BruteForceGeneratorParallel) pour liberer leur CPU immediatement.
//
// Lancement :
//   java -cp out benchmarks.DistributedMaster <port> <minLength> <maxLength> <targetHashHex>
//   java -cp out benchmarks.DistributedWorker <host> <port>   (sur chaque machine esclave)
public class DistributedMaster {

    private record Block(int length, char firstChar) {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        int minLength = Integer.parseInt(args[1]);
        int maxLength = Integer.parseInt(args[2]);
        String targetHashHex = args[3];

        BlockingQueue<Block> blocks = new LinkedBlockingQueue<>();
        for (int length = minLength; length <= maxLength; length++) {
            for (char c : CrackUtil.ALPHABET) {
                blocks.add(new Block(length, c));
            }
        }
        int totalBlocks = blocks.size();

        AtomicReference<String> found = new AtomicReference<>();
        AtomicLong totalAttempts = new AtomicLong();
        List<PrintWriter> activeWorkers = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();

        try (ServerSocket server = new ServerSocket(port)) {
            server.setSoTimeout(200); // pour re-verifier periodiquement blocks/found, pas bloquer indefiniment
            System.out.printf("Maitre en ecoute sur le port %d (%d blocs a distribuer)%n", port, totalBlocks);
            int workerCount = 0;
            while (!blocks.isEmpty() && found.get() == null) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                workerCount++;
                Thread handler = new Thread(() ->
                        handleWorker(socket, blocks, targetHashHex, found, totalAttempts, activeWorkers));
                handler.start();
            }
            // Attend que tous les workers deja connectes terminent leur bloc en cours.
            while (!activeWorkers.isEmpty()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.printf("(%d workers connectes)%n", workerCount);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (found.get() != null) {
            System.out.printf("Mot de passe trouve = \"%s\" (%d tentatives, %d ms)%n",
                    found.get(), totalAttempts.get(), elapsedMs);
        } else {
            System.out.printf("Echec, aucun mot ne correspond (%d tentatives, %d ms)%n",
                    totalAttempts.get(), elapsedMs);
        }
    }

    private static void handleWorker(Socket socket, BlockingQueue<Block> blocks, String targetHashHex,
                                      AtomicReference<String> found, AtomicLong totalAttempts,
                                      List<PrintWriter> activeWorkers) {
        PrintWriter out = null;
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            activeWorkers.add(out);
            out.println("TARGET " + targetHashHex);

            Block block;
            while (found.get() == null && (block = blocks.poll()) != null) {
                out.println("BLOCK " + block.length() + " " + block.firstChar());
                String reply = in.readLine();
                if (reply == null) {
                    return; // worker deconnecte
                }
                String[] parts = reply.split(" ", 3);
                totalAttempts.addAndGet(Long.parseLong(parts[1]));
                if ("FOUND".equals(parts[0])) {
                    if (found.compareAndSet(null, parts[2])) {
                        broadcastStop(activeWorkers);
                    }
                    return;
                }
            }
            out.println("STOP");
        } catch (IOException e) {
            System.err.println("Worker deconnecte : " + e.getMessage());
        } finally {
            if (out != null) {
                activeWorkers.remove(out);
            }
        }
    }

    private static void broadcastStop(List<PrintWriter> activeWorkers) {
        for (PrintWriter w : activeWorkers) {
            w.println("STOP");
        }
    }
}
