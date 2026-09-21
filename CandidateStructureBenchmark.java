import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class CandidateStructureBenchmark {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    public static void main(String[] args) {
        int wordLength = 4;
        int limit = 2_000_000;

        List<String> candidates = generateCandidates(wordLength, limit);
        int n = candidates.size();
        System.out.println("Candidats generes : " + n);

        ArrayList<String> array = new ArrayList<>(candidates);
        LinkedList<String> linked = new LinkedList<>(candidates);

        benchmark("ArrayList (tableau contigu)", array);
        benchmark("LinkedList (noeuds disperses)", linked);
    }

    // Genere jusqu'a `limit` mots de `length` caracteres, sans construire tout l'espace.
    private static List<String> generateCandidates(int length, int limit) {
        List<String> result = new ArrayList<>(limit);
        char[] buffer = new char[length];
        fill(buffer, 0, result, limit);
        return result;
    }

    private static void fill(char[] buffer, int position, List<String> result, int limit) {
        if (result.size() >= limit) {
            return;
        }
        if (position == buffer.length) {
            result.add(new String(buffer));
            return;
        }
        for (char c : ALPHABET) {
            buffer[position] = c;
            fill(buffer, position + 1, result, limit);
            if (result.size() >= limit) {
                return;
            }
        }
    }

    private static void benchmark(String label, List<String> list) {
        // Parcours sequentiel : bon cas pour LinkedList (iterator), pire cas pour acces indexe.
        long startSeq = System.nanoTime();
        long checksum = 0;
        for (String s : list) {
            checksum += s.hashCode();
        }
        long seqMs = (System.nanoTime() - startSeq) / 1_000_000;

        // Acces aleatoire par index : catastrophique pour LinkedList (O(n) par acces).
        int sampleSize = Math.min(20_000, list.size());
        long startRandom = System.nanoTime();
        long checksum2 = 0;
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < sampleSize; i++) {
            int idx = random.nextInt(list.size());
            checksum2 += list.get(idx).hashCode();
        }
        long randomMs = (System.nanoTime() - startRandom) / 1_000_000;

        System.out.printf("%s : parcours sequentiel = %d ms, acces aleatoire (%d requetes) = %d ms (checksum=%d/%d)%n",
                label, seqMs, sampleSize, randomMs, checksum, checksum2);
    }
}
