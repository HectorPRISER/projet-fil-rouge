package benchmarks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

// Worker REST : poll GET /block, calcule le bloc (CrackUtil), poste le resultat en JSON
// sur POST /report, boucle jusqu'a {"stop":true}.
//
// Lancement : java -cp out benchmarks.RestWorker <baseUrl>   (ex: http://localhost:6100)
public class RestWorker {

    public static void main(String[] args) throws NoSuchAlgorithmException {
        String baseUrl = args[0];
        HttpClient client = HttpClient.newHttpClient();
        int blocksHandled = 0;

        while (true) {
            Map<String, String> block = getJson(client, baseUrl + "/block");
            if (Boolean.parseBoolean(block.get("stop"))) {
                break;
            }

            int length = Integer.parseInt(block.get("length"));
            char firstChar = block.get("firstChar").charAt(0);
            byte[] targetHash = CrackUtil.decodeHex(block.get("targetHash"));

            CrackUtil.ScanResult result = CrackUtil.scanBlock(length, firstChar, targetHash);
            blocksHandled++;

            String report = JsonUtil.object("length", length, "firstChar", String.valueOf(firstChar),
                    "attempts", result.attempts(), "found", result.found());
            postJson(client, baseUrl + "/report", report);

            if (result.found() != null) {
                break;
            }
        }
        System.out.printf("Termine : %d blocs traites%n", blocksHandled);
    }

    private static Map<String, String> getJson(HttpClient client, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return JsonUtil.parse(response.body());
        } catch (Exception e) {
            throw new RuntimeException("Requete GET " + url + " echouee", e);
        }
    }

    private static void postJson(HttpClient client, String url, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new RuntimeException("Requete POST " + url + " echouee", e);
        }
    }
}
