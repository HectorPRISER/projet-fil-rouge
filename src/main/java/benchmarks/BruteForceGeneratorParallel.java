package benchmarks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// Partitionne l'espace de recherche par premier caractere (un "bloc" = longueur + 1er
// caractere fixe) et distribue les blocs a des workers via une BlockingQueue, equivalent
// Java d'un channel Go : producteur remplit la queue, workers font queue.take() jusqu'a
// une "poison pill" (equivalent du close(channel) en Go), consomment en parallele.
public class BruteForceGeneratorParallel {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private record Block(int length, char firstChar) {}

    private static final Block POISON_PILL = new Block(-1, '\0');

    public static void main(String[] args) throws Exception {
        crack("a532ca5e11e2b06ccc911e0d962a4864cdb87da05723f3a050a376d0f0895e63", 1, 3, "Niveau 1");
        crack("bd7d0ea8cf7ade4a446ba4efc46fd99071ec3f423770991ac51f70ec5a894dc7", 1, 4, "Niveau 2");
    }

    private static void crack(String targetHashHex, int minLength, int maxLength, String label) throws Exception {
        byte[] targetHash = decodeHex(targetHashHex);
        int workers = Runtime.getRuntime().availableProcessors();

        BlockingQueue<Block> channel = new LinkedBlockingQueue<>();
        for (int length = minLength; length <= maxLength; length++) {
            for (char c : ALPHABET) {
                channel.add(new Block(length, c));
            }
        }
        for (int i = 0; i < workers; i++) {
            channel.add(POISON_PILL);
        }

        AtomicReference<String> found = new AtomicReference<>();
        AtomicLong attempts = new AtomicLong();
        long start = System.nanoTime();

        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(() -> worker(channel, targetHash, found, attempts));
            t.start();
            pool.add(t);
        }
        for (Thread t : pool) {
            t.join();
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String result = found.get();
        if (result != null) {
            System.out.printf("%s : mot de passe trouve = \"%s\" (%d tentatives, %d ms, %d workers)%n",
                    label, result, attempts.get(), elapsedMs, workers);
        } else {
            System.out.printf("%s : echec, aucun mot ne correspond (%d tentatives, %d ms, %d workers)%n",
                    label, attempts.get(), elapsedMs, workers);
        }
    }

    private static void worker(BlockingQueue<Block> channel, byte[] targetHash,
                                AtomicReference<String> found, AtomicLong attempts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            while (found.get() == null) {
                Block block = channel.take();
                if (block == POISON_PILL) {
                    return;
                }
                char[] candidate = new char[block.length()];
                candidate[0] = block.firstChar();
                generate(candidate, 1, digest, targetHash, found, attempts);
            }
        } catch (InterruptedException | NoSuchAlgorithmException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void generate(char[] candidate, int position, MessageDigest digest, byte[] targetHash,
                                  AtomicReference<String> found, AtomicLong attempts) {
        if (found.get() != null) {
            return;
        }
        if (position == candidate.length) {
            attempts.incrementAndGet();
            String word = new String(candidate);
            digest.reset();
            byte[] hash = digest.digest(word.getBytes());
            if (hashEquals(hash, targetHash)) {
                found.compareAndSet(null, word);
            }
            return;
        }
        for (char c : ALPHABET) {
            candidate[position] = c;
            generate(candidate, position + 1, digest, targetHash, found, attempts);
            if (found.get() != null) {
                return;
            }
        }
    }

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
