package benchmarks;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BruteForceGenerator ===");
        BruteForceGenerator.main(args);

        System.out.println("=== BruteForceGeneratorParallel ===");
        BruteForceGeneratorParallel.main(args);

        System.out.println("=== CacheAccessBenchmark ===");
        CacheAccessBenchmark.main(args);

        System.out.println("=== CandidateStructureBenchmark ===");
        CandidateStructureBenchmark.main(args);

        System.out.println("=== HashThroughputBenchmark ===");
        HashThroughputBenchmark.main(args);

        System.out.println("=== StringConcatEscapeBenchmark ===");
        StringConcatEscapeBenchmark.main(args);

        System.out.println("=== StructPaddingBenchmark ===");
        StructPaddingBenchmark.main(args);

        System.out.println("=== FixedBufferBenchmark ===");
        FixedBufferBenchmark.main(args);

        System.out.println("=== ZeroAllocValidationBenchmark ===");
        ZeroAllocValidationBenchmark.main(args);
    }
}
