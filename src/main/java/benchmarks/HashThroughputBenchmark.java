package benchmarks;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class HashThroughputBenchmark {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    public static void main(String[] args) throws NoSuchAlgorithmException {
        int wordLength = 4;
        int limit = 1_000_000;

        List<String> raw = generateCandidates(wordLength, limit);

        List<String> contiguous = new ArrayList<>(raw);
        List<String> scattered = new LinkedList<>(raw);

        // Rechauffe JIT (memes structures, memes donnees).
        hashAll(contiguous);
        hashAll(scattered);

        long t0 = System.nanoTime();
        long checksumContiguous = hashAll(contiguous);
        long msContiguous = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        long checksumScattered = hashAll(scattered);
        long msScattered = (System.nanoTime() - t1) / 1_000_000;

        double throughputContiguous = contiguous.size() / (msContiguous / 1000.0);
        double throughputScattered = scattered.size() / (msScattered / 1000.0);

        System.out.printf("ArrayList (contigu)  : %d ms, %.0f hash/s (checksum=%d)%n",
                msContiguous, throughputContiguous, checksumContiguous);
        System.out.printf("LinkedList (disperse): %d ms, %.0f hash/s (checksum=%d)%n",
                msScattered, throughputScattered, checksumScattered);
        System.out.printf("Gain debit tableau contigu : x%.2f%n",
                throughputContiguous / throughputScattered);
    }

    // Boucle de hachage strictement identique pour les deux structures : seule la source
    // des String change (List<String> via iterator), aucune ligne de logique SHA-256 modifiee.
    private static long hashAll(List<String> candidates) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long checksum = 0;
        for (String candidate : candidates) {
            String hash = sha256(candidate, digest);
            checksum += hash.hashCode();
        }
        return checksum;
    }

    private static String sha256(String input, MessageDigest digest) {
        digest.reset();
        byte[] hash = digest.digest(input.getBytes());
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static List<String> generateCandidates(int length, int limit) {
        List<String> result = new ArrayList<>(limit);
        char[] buffer = new char[length];
        fill(buffer, 0, result, limit);
        return result;
    }

    private static void fill(char[] buffer, int position, List<String> result, int limit) {
        if (result.size() >= limit) {
            return;
        }
        if (position == buffer.length) {
            result.add(new String(buffer));
            return;
        }
        for (char c : ALPHABET) {
            buffer[position] = c;
            fill(buffer, position + 1, result, limit);
            if (result.size() >= limit) {
                return;
            }
        }
    }
}
