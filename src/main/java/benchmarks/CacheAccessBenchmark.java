package benchmarks;

import java.util.Random;

public class CacheAccessBenchmark {

    // Assez grand pour depasser tout cache L2/L3 (ici ~64M entiers = 256 Mo).
    private static final int SIZE = 64 * 1024 * 1024;
    private static final int ACCESSES = SIZE;

    public static void main(String[] args) {
        int[] data = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            data[i] = i;
        }

        // Permutation cyclique aleatoire : chaque saut est un cache miss potentiel.
        int[] permutation = randomCycle(SIZE, 42);

        // Rechauffe JIT.
        sequentialSum(data);
        scatteredSum(data, permutation);

        long seqNanos = 0;
        long scatNanos = 0;
        long checksumSeq = 0;
        long checksumScat = 0;
        int rounds = 5;

        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            checksumSeq += sequentialSum(data);
            seqNanos += System.nanoTime() - t0;

            long t1 = System.nanoTime();
            checksumScat += scatteredSum(data, permutation);
            scatNanos += System.nanoTime() - t1;
        }

        double seqPerAccessNs = (double) seqNanos / rounds / ACCESSES;
        double scatPerAccessNs = (double) scatNanos / rounds / ACCESSES;

        double ghz = 3.0; // approximation pour convertir ns en cycles
        double seqCycles = seqPerAccessNs * ghz;
        double scatCycles = scatPerAccessNs * ghz;

        double seqThroughputMops = 1000.0 / seqPerAccessNs;
        double scatThroughputMops = 1000.0 / scatPerAccessNs;

        System.out.printf("Acces sequentiel  : %.3f ns/acces (~%.1f cycles), debit = %.1f Mops/s%n",
                seqPerAccessNs, seqCycles, seqThroughputMops);
        System.out.printf("Acces disperse    : %.3f ns/acces (~%.1f cycles), debit = %.1f Mops/s%n",
                scatPerAccessNs, scatCycles, scatThroughputMops);
        System.out.printf("Facteur ralentissement latence : x%.1f%n", scatPerAccessNs / seqPerAccessNs);
        System.out.printf("Effondrement du debit           : x%.1f (soit %.2f%% du debit sequentiel)%n",
                seqThroughputMops / scatThroughputMops, 100.0 * scatThroughputMops / seqThroughputMops);
        System.out.printf("Cycles d'attente RAM constates par acces disperse : ~%.0f (attendu ~200 pour un miss L1-L3->RAM)%n",
                scatCycles);
        System.out.printf("(checksums controle : seq=%d, scat=%d)%n", checksumSeq, checksumScat);
    }

    // Prefetch materiel efficace sur un parcours lineaire.
    private static long sequentialSum(int[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i];
        }
        return sum;
    }

    // Pointer-chasing : index imprevisible, cache miss quasi systematique.
    private static long scatteredSum(int[] data, int[] permutation) {
        long sum = 0;
        int idx = 0;
        for (int i = 0; i < permutation.length; i++) {
            idx = permutation[idx];
            sum += data[idx];
        }
        return sum;
    }

    // Cycle unique couvrant tous les index (Fisher-Yates + reliage en cycle).
    private static int[] randomCycle(int size, long seed) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        Random random = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        int[] next = new int[size];
        for (int i = 0; i < size; i++) {
            next[order[i]] = order[(i + 1) % size];
        }
        return next;
    }
}
