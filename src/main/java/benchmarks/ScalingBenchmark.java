package benchmarks;

// Mesure le speedup de BruteForceGeneratorParallel en fonction du nombre de workers
// (1, 2, 4, ..., N coeurs) et verifie la linearite du passage a l'echelle (speedup/workers).
// Utilise un hash cible IMPOSSIBLE (jamais trouve) pour forcer un scan exhaustif de taille
// fixe a chaque mesure : sans ca, l'arret precoce rendrait les temps incomparables (le mot
// peut etre trouve plus ou moins vite selon le hasard de repartition des blocs).
public class ScalingBenchmark {

    private static final String IMPOSSIBLE_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000".substring(0, 64);

    public static void main(String[] args) throws Exception {
        int maxCores = Runtime.getRuntime().availableProcessors();

        // Rechauffe JIT.
        BruteForceGeneratorParallel.crack(IMPOSSIBLE_HASH, 1, 3, maxCores);

        System.out.printf("%-8s %10s %10s %10s%n", "workers", "temps(ms)", "speedup", "efficacite");
        double baseline = -1;
        int repeats = 3;
        for (int workers : coreCounts(maxCores)) {
            long total = 0;
            for (int i = 0; i < repeats; i++) {
                total += BruteForceGeneratorParallel.crack(IMPOSSIBLE_HASH, 1, 4, workers).elapsedMs();
            }
            double meanMs = (double) total / repeats;
            if (baseline < 0) {
                baseline = meanMs;
            }
            double speedup = baseline / meanMs;
            double efficiency = 100 * speedup / workers;
            System.out.printf("%-8d %10.1f %9.2fx %9.1f%%%n", workers, meanMs, speedup, efficiency);
        }
    }

    // 1, 2, 4, 8, ... jusqu'a maxCores (borne incluse si pas deja une puissance de 2).
    private static int[] coreCounts(int maxCores) {
        java.util.List<Integer> counts = new java.util.ArrayList<>();
        for (int c = 1; c < maxCores; c *= 2) {
            counts.add(c);
        }
        counts.add(maxCores);
        return counts.stream().mapToInt(Integer::intValue).toArray();
    }
}
