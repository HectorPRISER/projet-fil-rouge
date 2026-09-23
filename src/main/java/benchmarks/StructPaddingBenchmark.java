package benchmarks;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

// En C/Go, l'ordre de declaration des champs determine le layout memoire.
// En Java, HotSpot reordonne deja les champs par taille decroissante au
// chargement de la classe, quel que soit l'ordre source : ce benchmark
// verifie ca empiriquement (memes champs, ordre "naif" vs "compacte").
public class StructPaddingBenchmark {

    private static final int COUNT = 1_000_000;

    static class CandidateUnordered {
        boolean active;
        long id;
        int score;
        byte category;
        short flags;
    }

    static class CandidateOrdered {
        long id;
        int score;
        short flags;
        byte category;
        boolean active;
    }

    public static void main(String[] args) {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        allocateUnordered(1000); // rechauffe JIT + classloading
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

        long diffPerInstance = Math.abs(allocatedUnordered - allocatedOrdered) / COUNT;
        if (diffPerInstance == 0) {
            System.out.println("=> Meme taille dans les deux cas : HotSpot reordonne deja les champs, "
                    + "reordonner a la main n'a aucun effet en Java.");
        } else {
            System.out.printf("=> Gain reel observe : %d octets/instance (x%.2f)%n",
                    allocatedUnordered - allocatedOrdered,
                    (double) allocatedUnordered / allocatedOrdered);
        }

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
