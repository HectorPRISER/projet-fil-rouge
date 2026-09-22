package benchmarks;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

// Equivalent Java du diagnostic "go build -gcflags=-m" : au lieu de lire un rapport
// d'echappement du compilateur, on mesure directement les octets alloues sur le Tas
// via ThreadMXBean (com.sun.management), qui compte les allocations reelles de la JVM.
public class StringConcatEscapeBenchmark {

    // Volontairement petit : la concatenation '+=' est O(n^2) (copie toute la
    // chaine a chaque tour), 1_000_000 iterations prendrait plusieurs minutes.
    private static final int ITERATIONS = 20_000;

    public static void main(String[] args) {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        // Rechauffe JIT.
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

    // Chaque '+=' cree une nouvelle String immuable sur le tas (l'ancienne devient
    // immediatement inaccessible) : l'objet "s" ne peut jamais rester sur la pile,
    // il echappe systematiquement car sa reference est reassignee a chaque tour.
    private static String concatPlus(int iterations) {
        String s = "";
        for (int i = 0; i < iterations; i++) {
            s += Integer.toString(i);
        }
        return s;
    }

    // Le buffer interne du StringBuilder est mute en place : une seule allocation
    // (redimensionnee au besoin) au lieu d'une par iteration.
    private static String concatBuilder(int iterations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < iterations; i++) {
            sb.append(Integer.toString(i));
        }
        return sb.toString();
    }
}
