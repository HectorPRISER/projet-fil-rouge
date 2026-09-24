package benchmarks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// Partitionne l'espace de recherche par premier caractere (un "bloc" = longueur + 1er
// caractere fixe) et distribue les blocs a des workers via une BlockingQueue, equivalent
// Java d'un channel Go. L'arret precoce est fait avec Thread.interrupt() (equivalent Java
// de context.WithCancel + ctx.Done()) : des qu'un worker trouve le mot, il interrompt tous
// les autres, ce qui les reveille INSTANTANEMENT s'ils sont bloques sur channel.take()
// (contrairement a un simple flag scrute, qui ne libere pas un thread bloque) et fait
// sortir generate() au prochain niveau de recursion.
public class BruteForceGeneratorParallel {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private record Block(int length, char firstChar) {}

    private static final Block POISON_PILL = new Block(-1, '\0');

    record Result(String found, long attempts, long elapsedMs) {}

    public static void main(String[] args) throws Exception {
        // WORKERS permet de forcer un nombre de workers pour mesurer la penalite d'oversubscription.
        String override = System.getenv("WORKERS");
        int workers = override != null ? Integer.parseInt(override) : Runtime.getRuntime().availableProcessors();

        printResult("Niveau 1", workers,
                crack("a532ca5e11e2b06ccc911e0d962a4864cdb87da05723f3a050a376d0f0895e63", 1, 3, workers));
        printResult("Niveau 2", workers,
                crack("bd7d0ea8cf7ade4a446ba4efc46fd99071ec3f423770991ac51f70ec5a894dc7", 1, 4, workers));
    }

    private static void printResult(String label, int workers, Result r) {
        if (r.found() != null) {
            System.out.printf("%s : mot de passe trouve = \"%s\" (%d tentatives, %d ms, %d workers)%n",
                    label, r.found(), r.attempts(), r.elapsedMs(), workers);
        } else {
            System.out.printf("%s : echec, aucun mot ne correspond (%d tentatives, %d ms, %d workers)%n",
                    label, r.attempts(), r.elapsedMs(), workers);
        }
    }

    // Pool borne exactement sur `workers` (equivalent runtime.NumCPU() quand workers =
    // availableProcessors()) : au-dela, le CPU passe plus de temps a ordonnancer les
    // threads qu'a hacher. Package-private : reutilise par ScalingBenchmark.
    static Result crack(String targetHashHex, int minLength, int maxLength, int workers) throws Exception {
        byte[] targetHash = decodeHex(targetHashHex);

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

        // Threads crees avant demarrage : chaque worker peut ainsi interrompre tous les
        // autres (y compris ceux pas encore lances) des qu'il trouve le mot.
        Thread[] pool = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            pool[i] = new Thread(() -> worker(channel, targetHash, found, attempts, pool));
        }
        for (Thread t : pool) {
            t.start();
        }
        for (Thread t : pool) {
            t.join();
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new Result(found.get(), attempts.get(), elapsedMs);
    }

    private static void worker(BlockingQueue<Block> channel, byte[] targetHash,
                                AtomicReference<String> found, AtomicLong attempts, Thread[] pool) {
        // Compteur local (pas d'Atomic) : un AtomicLong partage incremente a chaque hash
        // (15M fois, par tous les workers) serait lui-meme un goulet de contention. On
        // accumule localement et on ne merge dans le compteur global qu'une fois a la fin.
        long[] local = new long[1];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            while (!Thread.currentThread().isInterrupted() && found.get() == null) {
                Block block = channel.take(); // se reveille immediatement si interrompu
                if (block == POISON_PILL) {
                    return;
                }
                char[] candidate = new char[block.length()];
                candidate[0] = block.firstChar();
                generate(candidate, 1, digest, targetHash, found, local, pool);
            }
        } catch (InterruptedException | NoSuchAlgorithmException e) {
            Thread.currentThread().interrupt();
        } finally {
            attempts.addAndGet(local[0]);
        }
    }

    private static void generate(char[] candidate, int position, MessageDigest digest, byte[] targetHash,
                                  AtomicReference<String> found, long[] local, Thread[] pool) {
        if (Thread.currentThread().isInterrupted() || found.get() != null) {
            return;
        }
        if (position == candidate.length) {
            local[0]++;
            String word = new String(candidate);
            digest.reset();
            byte[] hash = digest.digest(word.getBytes());
            if (hashEquals(hash, targetHash) && found.compareAndSet(null, word)) {
                cancelOthers(pool);
            }
            return;
        }
        for (char c : ALPHABET) {
            candidate[position] = c;
            generate(candidate, position + 1, digest, targetHash, found, local, pool);
            if (Thread.currentThread().isInterrupted() || found.get() != null) {
                return;
            }
        }
    }

    // Equivalent de cancel() sur un context.WithCancel : propage l'annulation a tous les
    // workers d'un coup, y compris ceux bloques sur channel.take() qui se reveillent aussitot.
    private static void cancelOthers(Thread[] pool) {
        Thread self = Thread.currentThread();
        for (Thread t : pool) {
            if (t != self) {
                t.interrupt();
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
