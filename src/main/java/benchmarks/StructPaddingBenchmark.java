package benchmarks;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

// Equivalent Java du "compactage de structure" par ordre de taille decroissante
// (technique classique en C/Go, ou l'ordre de declaration des champs determine
// le layout memoire exact). En Java, le JVM (HotSpot) reordonne DEJA les champs
// automatiquement lors du chargement de la classe : il place toujours les long/
// double en premier, puis int/float, puis short/char, puis byte/boolean, puis
// les references, quel que soit l'ordre ecrit dans le code source.
//
// CandidateUnordered et CandidateOrdered ont exactement les memes champs, dans
// un ordre source different (mauvais puis bon), pour verifier empiriquement
// si cela change quelque chose en Java (contrairement a Go/C).
public class StructPaddingBenchmark {

    private static final int COUNT = 1_000_000;

    // Ordre "naif" (comme on l'ecrirait sans y penser) : petit, gros, moyen, petit, moyen.
    static class CandidateUnordered {
        boolean active; // 1 octet
        long id;        // 8 octets
        int score;      // 4 octets
        byte category;  // 1 octet
        short flags;    // 2 octets
    }

    // Ordre "compacte" : tailles decroissantes (long, int, short, byte, boolean).
    static class CandidateOrdered {
        long id;        // 8 octets
        int score;      // 4 octets
        short flags;    // 2 octets
        byte category;  // 1 octet
        boolean active; // 1 octet
    }

    public static void main(String[] args) {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        // Rechauffe JIT + classloading.
        allocateUnordered(1000);
        allocateOrdered(1000);

        long before1 = threadBean.getThreadAllocatedBytes(threadId);
        Object[] unordered = allocateUnordered(COUNT);
        long allocatedUnordered = threadBean.getThreadAllocatedBytes(threadId) - before1;

        long before2 = threadBean.getThreadAllocatedBytes(threadId);
        Object[] ordered = allocateOrdered(COUNT);
        long allocatedOrdered = threadBean.getThreadAllocatedBytes(threadId) - before2;

        System.out.printf("CandidateUnordered (ordre naif)     : %.1f octets/instance%n",
                (double) allocatedUnordered / COUNT);
        System.out.printf("CandidateOrdered   (tailles decroissantes) : %.1f octets/instance%n",
                (double) allocatedOrdered / COUNT);

        // Tolerance : quelques centaines d'octets sur 1M objets viennent du bruit de mesure
        // (TLAB, autres allocations du thread), pas d'une vraie difference de layout.
        long diffPerInstance = Math.abs(allocatedUnordered - allocatedOrdered) / COUNT;
        if (diffPerInstance == 0) {
            System.out.println("=> Meme taille dans les deux cas : le JVM (HotSpot) reordonne DEJA");
            System.out.println("   les champs par taille decroissante au chargement de la classe.");
            System.out.println("   Contrairement a Go/C, reordonner les champs a la main en Java n'a");
            System.out.println("   aucun effet sur le layout memoire reel.");
        } else {
            System.out.printf("=> Gain reel observe : %d octets/instance (x%.2f)%n",
                    allocatedUnordered - allocatedOrdered,
                    (double) allocatedUnordered / allocatedOrdered);
        }

        // References gardees pour empecher le JIT d'eliminer les allocations (dead code elimination).
        System.out.printf("(controle : %d/%d objets crees)%n", unordered.length, ordered.length);
    }

    private static Object[] allocateUnordered(int count) {
        Object[] refs = new Object[count];
        for (int i = 0; i < count; i++) {
            CandidateUnordered c = new CandidateUnordered();
            c.id = i;
            refs[i] = c;
        }
        return refs;
    }

    private static Object[] allocateOrdered(int count) {
        Object[] refs = new Object[count];
        for (int i = 0; i < count; i++) {
            CandidateOrdered c = new CandidateOrdered();
            c.id = i;
            refs[i] = c;
        }
        return refs;
    }
}
