package benchmarks;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

// Mesure les octets reellement alloues sur le tas (ThreadMXBean) plutot que de
// lire un rapport d'echappement du compilateur.
public class StringConcatEscapeBenchmark {

    private static final int ITERATIONS = 20_000; // '+=' est O(n^2), pas plus

    public static void main(String[] args) {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        concatPlus(1000);
        concatBuilder(1000);

        long before1 = threadBean.getThreadAllocatedBytes(threadId);
        String resultPlus = concatPlus(ITERATIONS);
        long allocatedPlus = threadBean.getThreadAllocatedBytes(threadId) - before1;

        long before2 = threadBean.getThreadAllocatedBytes(threadId);
        String resultBuilder = concatBuilder(ITERATIONS);
        long allocatedBuilder = threadBean.getThreadAllocatedBytes(threadId) - before2;

        System.out.printf("Concatenation par '+' (%d iterations) : %d Mo alloues sur le tas (%.1f octets/iteration)%n",
                ITERATIONS, allocatedPlus / (1024 * 1024), (double) allocatedPlus / ITERATIONS);
        System.out.printf("StringBuilder       (%d iterations) : %d Mo alloues sur le tas (%.1f octets/iteration)%n",
                ITERATIONS, allocatedBuilder / (1024 * 1024), (double) allocatedBuilder / ITERATIONS);
        System.out.printf("Facteur d'allocation en plus avec '+' : x%.1f%n",
                (double) allocatedPlus / allocatedBuilder);
        System.out.printf("(longueurs de controle : plus=%d, builder=%d)%n",
                resultPlus.length(), resultBuilder.length());
    }

    // Chaque '+=' cree une nouvelle String immuable sur le tas.
    private static String concatPlus(int iterations) {
        String s = "";
        for (int i = 0; i < iterations; i++) {
            s += Integer.toString(i);
        }
        return s;
    }

    // Buffer interne mute en place au lieu d'une allocation par iteration.
    private static String concatBuilder(int iterations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < iterations; i++) {
            sb.append(Integer.toString(i));
        }
        return sb.toString();
    }
}
