package benchmarks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

// Noeud esclave : se connecte au maitre, recoit des blocs (longueur + 1er caractere) un
// par un, les scanne exhaustivement (CrackUtil) et renvoie le resultat. S'arrete sur
// reception de STOP (le maitre a trouve le mot, ou n'a plus de travail).
//
// Lancement : java -cp out benchmarks.DistributedWorker <host> <port>
public class DistributedWorker {

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        String host = args[0];
        int port = Integer.parseInt(args[1]);

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            System.out.println("Connecte au maitre " + host + ":" + port);
            String targetLine = in.readLine();
            byte[] targetHash = CrackUtil.decodeHex(targetLine.substring("TARGET ".length()));

            int blocksHandled = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("STOP")) {
                    break;
                }
                String[] parts = line.split(" ");
                int length = Integer.parseInt(parts[1]);
                char firstChar = parts[2].charAt(0);

                CrackUtil.ScanResult result = CrackUtil.scanBlock(length, firstChar, targetHash);
                blocksHandled++;
                if (result.found() != null) {
                    out.println("FOUND " + result.attempts() + " " + result.found());
                    break;
                }
                out.println("DONE " + result.attempts());
            }
            System.out.printf("Termine : %d blocs traites%n", blocksHandled);
        }
    }
}
