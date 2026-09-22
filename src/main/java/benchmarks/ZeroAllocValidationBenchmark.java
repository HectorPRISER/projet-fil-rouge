package benchmarks;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Equivalent Java de "go test -bench . -benchmem" valide 0 B/op, 0 allocs/op :
// au lieu d'un flag de test Go, on mesure directement les octets alloues par
// iteration via ThreadMXBean (meme technique que les benchmarks precedents) et
// on affiche un verdict PASS/FAIL, avec le debit (ops/s) de la boucle critique.
//
// La boucle re-utilise systematiquement les memes buffers (aucun `new` a
// l'interieur de la boucle chronometree) : c'est la condition necessaire pour
// atteindre 0 allocation par operation en Java, comme un buffer fixe sur la
// pile en Go.
public class ZeroAllocValidationBenchmark {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int ITERATIONS = 5_000_000;

    public static void main(String[] args) throws NoSuchAlgorithmException, DigestException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        // Buffers uniques, reutilises a chaque iteration : jamais de `new` dans la boucle critique.
        byte[] input = new byte[8];
        byte[] hashOut = new byte[32];
        char[] hexOut = new char[64];

        // Rechauffe JIT (memes buffers, meme boucle, pour stabiliser l'inlining avant mesure).
        long warmupChecksum = criticalLoop(digest, input, hashOut, hexOut, 100_000);

        long before = threadBean.getThreadAllocatedBytes(threadId);
        long t0 = System.nanoTime();
        long checksum = criticalLoop(digest, input, hashOut, hexOut, ITERATIONS);
        long elapsedNanos = System.nanoTime() - t0;
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - before;

        double allocPerOp = (double) allocatedBytes / ITERATIONS;
        double opsPerSec = ITERATIONS / (elapsedNanos / 1_000_000_000.0);

        System.out.printf("Boucle critique : %d iterations en %d ms%n",
                ITERATIONS, elapsedNanos / 1_000_000);
        System.out.printf("%.4f B/op (total=%d octets), debit = %.0f ops/s%n",
                allocPerOp, allocatedBytes, opsPerSec);
        // Tolerance : quelques centaines d'octets au total (pas par iteration) viennent
        // d'un residu ponctuel (JIT, GC bookkeeping), pas d'une allocation par operation.
        boolean pass = allocPerOp < 0.01;
        System.out.println(pass
                ? "PASS : 0 B/op, 0 allocs/op confirme"
                : "FAIL : allocation residuelle detectee dans la boucle critique");
        System.out.printf("(controle : checksum=%d, warmup=%d)%n", checksum, warmupChecksum);
    }

    // Aucune allocation : `input` est mute par index (encodage little-endian de `i`),
    // `digest.digest(buf, offset, len)` ecrit le resultat dans `hashOut` fourni
    // (au lieu de `digest()` qui alloue un nouveau tableau a chaque appel),
    // et `hexOut` est rempli par index comme dans FixedBufferBenchmark.
    private static long criticalLoop(MessageDigest digest, byte[] input, byte[] hashOut, char[] hexOut,
                                      int iterations) throws DigestException {
        long checksum = 0;
        for (int i = 0; i < iterations; i++) {
            for (int b = 0; b < 8; b++) {
                input[b] = (byte) (i >>> (b * 8));
            }

            digest.reset();
            digest.update(input);
            digest.digest(hashOut, 0, hashOut.length);

            for (int j = 0; j < hashOut.length; j++) {
                int v = hashOut[j] & 0xFF;
                hexOut[j * 2] = HEX_DIGITS[v >>> 4];
                hexOut[j * 2 + 1] = HEX_DIGITS[v & 0x0F];
            }

            checksum += hexOut[0];
        }
        return checksum;
    }
}
