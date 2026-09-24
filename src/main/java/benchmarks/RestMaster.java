package benchmarks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// Version A (REST/JSON) : API HTTP/1.1 classique, deux routes texte/JSON :
//   GET  /block   -> le maitre distribue le prochain bloc (ou {"stop":true})
//   POST /report  -> un worker rapporte le resultat d'un bloc (tentatives, mot trouve ou non)
// Equivalent fonctionnel de DistributedMaster (TCP+texte), mais sur HTTP/JSON standard :
// chaque worker fait une requete independante par bloc (stateless), pas de connexion
// persistante a gerer cote maitre.
//
// Lancement : java -cp out benchmarks.RestMaster <port> <minLength> <maxLength> <targetHashHex>
public class RestMaster {

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
        long start = System.nanoTime();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/block", exchange -> handleBlock(exchange, blocks, targetHashHex, found));
        server.createContext("/report", exchange -> handleReport(exchange, found, totalAttempts));
        server.start();
        System.out.printf("Maitre REST en ecoute sur le port %d (%d blocs)%n", port, totalBlocks);

        while (found.get() == null && !blocks.isEmpty()) {
            sleep(20);
        }
        sleep(300); // grace : laisse les requetes en cours se terminer
        server.stop(0);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (found.get() != null) {
            System.out.printf("Mot de passe trouve = \"%s\" (%d tentatives, %d ms)%n",
                    found.get(), totalAttempts.get(), elapsedMs);
        } else {
            System.out.printf("Echec, aucun mot ne correspond (%d tentatives, %d ms)%n",
                    totalAttempts.get(), elapsedMs);
        }
    }

    private static void handleBlock(HttpExchange exchange, BlockingQueue<Block> blocks,
                                      String targetHashHex, AtomicReference<String> found) throws IOException {
        String body;
        if (found.get() != null) {
            body = JsonUtil.object("stop", true);
        } else {
            Block block = blocks.poll();
            body = block == null
                    ? JsonUtil.object("stop", true)
                    : JsonUtil.object("stop", false, "length", block.length(), "firstChar",
                            String.valueOf(block.firstChar()), "targetHash", targetHashHex);
        }
        writeJson(exchange, 200, body);
    }

    private static void handleReport(HttpExchange exchange, AtomicReference<String> found,
                                       AtomicLong totalAttempts) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> report = JsonUtil.parse(requestBody);
        totalAttempts.addAndGet(Long.parseLong(report.get("attempts")));
        String word = report.get("found");
        if (word != null && !word.equals("null")) {
            found.compareAndSet(null, word);
        }
        writeJson(exchange, 200, JsonUtil.object("ack", true));
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
