package benchmarks;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class BruteForceGeneratorNaive {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static long attempts = 0;
    private static String found = null;

    public static void main(String[] args) throws NoSuchAlgorithmException {
        // Niveau 1
        crack("a532ca5e11e2b06ccc911e0d962a4864cdb87da05723f3a050a376d0f0895e63", 1, 3, "Niveau 1");
        // Niveau 2
        crack("bd7d0ea8cf7ade4a446ba4efc46fd99071ec3f423770991ac51f70ec5a894dc7", 1, 4, "Niveau 2");
    }

    private static void crack(String targetHash, int minLength, int maxLength, String label)
            throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        attempts = 0;
        found = null;
        long start = System.nanoTime();

        for (int length = minLength; length <= maxLength && found == null; length++) {
            char[] candidate = new char[length];
            generate(candidate, 0, digest, targetHash);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (found != null) {
            System.out.printf("%s : mot de passe trouve = \"%s\" (%d tentatives, %d ms)%n",
                    label, found, attempts, elapsedMs);
        } else {
            System.out.printf("%s : echec, aucun mot ne correspond (%d tentatives, %d ms)%n",
                    label, attempts, elapsedMs);
        }
    }

    private static void generate(char[] candidate, int position, MessageDigest digest, String targetHash) {
        if (found != null) {
            return;
        }
        if (position == candidate.length) {
            attempts++;
            String word = new String(candidate);
            if (sha256(word, digest).equals(targetHash)) {
                found = word;
            }
            return;
        }
        for (char c : ALPHABET) {
            candidate[position] = c;
            generate(candidate, position + 1, digest, targetHash);
            if (found != null) {
                return;
            }
        }
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
}
