package benchmarks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class BruteForceGenerator {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    // Vue "mot de 64 bits" sur un byte[] : lit/compare 8 octets d'un coup au lieu
    // d'octet par octet, equivalent Java de encoding/binary.Uint64 en Go.
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private static long attempts = 0;
    private static String found = null;

    public static void main(String[] args) throws NoSuchAlgorithmException {
        // Niveau 1
        crack("a532ca5e11e2b06ccc911e0d962a4864cdb87da05723f3a050a376d0f0895e63", 1, 3, "Niveau 1");
        // Niveau 2
        crack("bd7d0ea8cf7ade4a446ba4efc46fd99071ec3f423770991ac51f70ec5a894dc7", 1, 4, "Niveau 2");
    }

    private static void crack(String targetHashHex, int minLength, int maxLength, String label)
            throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // Decode le hash cible UNE SEULE FOIS ("au boot"), plus jamais de conversion
        // textuelle dans la boucle chaude : on compare des octets bruts, pas des String.
        byte[] targetHash = decodeHex(targetHashHex);

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

    private static void generate(char[] candidate, int position, MessageDigest digest, byte[] targetHash) {
        if (found != null) {
            return;
        }
        if (position == candidate.length) {
            attempts++;
            String word = new String(candidate);
            digest.reset();
            byte[] hash = digest.digest(word.getBytes());
            if (hashEquals(hash, targetHash)) {
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

    // Compare 32 octets (SHA-256) par blocs de 64 bits au lieu d'octet par octet :
    // 4 comparaisons de long au lieu de 32 comparaisons de byte, et surtout aucune
    // conversion hexadecimale/String au passage.
    private static boolean hashEquals(byte[] a, byte[] b) {
        for (int i = 0; i < 32; i += 8) {
            if ((long) LONG_VIEW.get(a, i) != (long) LONG_VIEW.get(b, i)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] decodeHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
