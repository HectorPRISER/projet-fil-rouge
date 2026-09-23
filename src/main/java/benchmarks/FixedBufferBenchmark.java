package benchmarks;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Remplace les allocations dynamiques (StringBuilder, String.format) par un
// tableau de taille fixe mute par index. Un tableau local qui n'echappe pas
// de la methode est candidat a la scalar replacement du JIT (pas de tas).
public class FixedBufferBenchmark {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int ITERATIONS = 200_000;

    public static void main(String[] args) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        hexStringBuilder("warmup", digest);
        hexFixedBuffer("warmup", digest);

        long before1 = threadBean.getThreadAllocatedBytes(threadId);
        String r1 = null;
        for (int i = 0; i < ITERATIONS; i++) {
            r1 = hexStringBuilder(Integer.toString(i), digest);
        }
        long allocatedBuilder = threadBean.getThreadAllocatedBytes(threadId) - before1;

        long before2 = threadBean.getThreadAllocatedBytes(threadId);
        String r2 = null;
        for (int i = 0; i < ITERATIONS; i++) {
            r2 = hexFixedBuffer(Integer.toString(i), digest);
        }
        long allocatedFixed = threadBean.getThreadAllocatedBytes(threadId) - before2;

        System.out.printf("String.format + StringBuilder (%d hachages) : %.1f octets/hachage alloues%n",
                ITERATIONS, (double) allocatedBuilder / ITERATIONS);
        System.out.printf("Buffer fixe char[64], mutation par index (%d hachages) : %.1f octets/hachage alloues%n",
                ITERATIONS, (double) allocatedFixed / ITERATIONS);
        System.out.printf("Reduction d'allocation : x%.2f%n", (double) allocatedBuilder / allocatedFixed);
        System.out.printf("(controle : dernier hash identique = %b)%n", r1.equals(r2));
    }

    // Chaque appel a String.format alloue un Formatter, un Locale lookup, etc.
    private static String hexStringBuilder(String input, MessageDigest digest) {
        digest.reset();
        byte[] hash = digest.digest(input.getBytes());
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // Rempli par index, sans concatenation ni formatage : une seule String finale.
    private static String hexFixedBuffer(String input, MessageDigest digest) {
        digest.reset();
        byte[] hash = digest.digest(input.getBytes());
        char[] buffer = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int v = hash[i] & 0xFF;
            buffer[i * 2] = HEX_DIGITS[v >>> 4];
            buffer[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
        }
        return new String(buffer);
    }
}
