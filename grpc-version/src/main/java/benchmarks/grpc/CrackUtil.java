package benchmarks.grpc;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Copie du CrackUtil du module principal (module Maven independant, pas d'acces direct
// aux sources javac de ../src). Scan exhaustif d'un bloc (longueur + 1er caractere fixe).
final class CrackUtil {

    static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private CrackUtil() {}

    record ScanResult(String found, long attempts) {}

    static ScanResult scanBlock(int length, char firstChar, byte[] targetHash) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        char[] candidate = new char[length];
        candidate[0] = firstChar;
        long[] attempts = new long[1];
        String found = generate(candidate, 1, digest, targetHash, attempts);
        return new ScanResult(found, attempts[0]);
    }

    private static String generate(char[] candidate, int position, MessageDigest digest, byte[] targetHash,
                                    long[] attempts) {
        if (position == candidate.length) {
            attempts[0]++;
            String word = new String(candidate);
            digest.reset();
            byte[] hash = digest.digest(word.getBytes());
            return hashEquals(hash, targetHash) ? word : null;
        }
        for (char c : ALPHABET) {
            candidate[position] = c;
            String found = generate(candidate, position + 1, digest, targetHash, attempts);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static boolean hashEquals(byte[] a, byte[] b) {
        for (int i = 0; i < 32; i += 8) {
            if ((long) LONG_VIEW.get(a, i) != (long) LONG_VIEW.get(b, i)) {
                return false;
            }
        }
        return true;
    }

    static byte[] decodeHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
