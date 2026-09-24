package benchmarks;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// Serveur REST dedie au benchmark protocole-a-protocole avec vegeta : une seule route
// POST /scan (1 bloc = 1 requete/reponse), pour une comparaison directe avec
// GrpcBenchServer (ScanOne) sous charge equivalente.
//
// Lancement : java -cp out benchmarks.RestBenchServer <port>
public class RestBenchServer {

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Integer.parseInt(args[0]);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/scan", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> block = JsonUtil.parse(requestBody);
            int length = Integer.parseInt(block.get("length"));
            char firstChar = block.get("firstChar").charAt(0);
            byte[] targetHash = CrackUtil.decodeHex(block.get("targetHash"));

            CrackUtil.ScanResult result;
            try {
                result = CrackUtil.scanBlock(length, firstChar, targetHash);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            String body = JsonUtil.object("attempts", result.attempts(), "found", result.found());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        System.out.printf("RestBenchServer en ecoute sur le port %d (/scan)%n", port);
        Thread.currentThread().join();
    }
}
