# Projet fil rouge — Optimisation backend

Benchmarks Java sur l'impact des structures de donnees, de l'acces
memoire et des allocations sur la performance.

## Build & run

```bash
javac -d out src/main/java/benchmarks/*.java
java -cp out benchmarks.Main          # tous les benchmarks
java -cp out benchmarks.<NomClasse>   # un seul
```

## Fichiers

| Fichier | Role |
|---|---|
| `BruteForceGenerator` | Casse 2 hash SHA-256 par force brute (mesure de reference CPU) |
| `BruteForceGeneratorNaive` | Version pre-optimisation, gardee pour comparaison (benchstat) |
| `BruteForceGeneratorParallel` | Partitionne par 1er caractere, `BlockingQueue` (channel), pool borne sur `availableProcessors()`, arret precoce via `Thread.interrupt()` (equivalent `context.WithCancel`) |
| `CacheAccessBenchmark` | Acces memoire sequentiel vs disperse → cout d'un cache miss RAM |
| `CandidateStructureBenchmark` | ArrayList vs LinkedList : parcours + acces indexe |
| `HashThroughputBenchmark` | ArrayList vs LinkedList sous charge de hachage |
| `StringConcatEscapeBenchmark` | Octets alloues : concat `+=` vs `StringBuilder` |
| `StructPaddingBenchmark` | Padding memoire : ordre des champs (long→boolean) |
| `FixedBufferBenchmark` | Buffer fixe + index vs `String.format`/`StringBuilder` |
| `ZeroAllocValidationBenchmark` | Valide 0 B/op sur une boucle 100% buffers reutilises |
| `Main` | Lance tous les benchmarks a la suite |

`tools/` : scripts d'analyse (Python), independants du code Java.

| Script | Role |
|---|---|
| `flamegraph.py` | `.jfr` → flamegraph HTML interactif autonome |
| `analyze_bottleneck.py` | % CPU passe dans le hex vs le SHA-256 reel |
| `benchstat.py` | Compare 2 classes sur N runs (moyenne/ecart-type/delta) |
| `annotate_flamegraph.py` | Annote un screenshot de flamegraph |

`profiles/` : sorties generees (`.jfr`, flamegraphs, `audit_report.md`).

## Resultats cles

| Constat | Chiffre |
|---|---|
| `LinkedList.get(i)` vs `ArrayList.get(i)` (2M elements) | O(n) vs O(1), ~13,9 s vs ~2 ms |
| Cache miss RAM vs acces sequentiel | ~x558 plus lent |
| `StringBuilder` vs concat `+=` (allocation) | ~x600+ moins d'octets alloues |
| Padding de structure (Java vs C/Go) | Sans effet : la JVM reordonne deja les champs |
| Buffer fixe + index vs `String.format` (allocation) | ~x48 moins d'octets alloues |
| Boucle critique 100% buffers reutilises | 0 B/op, ~12M ops/s (PASS) |
| CPU reellement gaspille dans `String.format("%02x")` | **96,7 %** (0,56 % dans le SHA-256 reel) |
| `BruteForceGenerator` : comparaison hex → comparaison binaire 64 bits | **x91,3** plus rapide (verifie sur 5 runs, intervalles disjoints) |
| Partitionnement + N workers (`BlockingQueue`) vs sequentiel | **~x2** (20 workers, 779 ms → ~400 ms, Niveau 2) |
| Pool borne sur `availableProcessors()` (20) vs sur/sous-dimensionne (scan exhaustif, meme travail) | optimal a 680 ms ; 10 workers = 734 ms, 40-320 workers = 812-883 ms |

Details du profiling + preuve statistique : voir
[`profiles/audit_report.md`](profiles/audit_report.md).

## Profiling (JFR) & preuve statistique

```bash
# Profiler + flamegraph
java -XX:StartFlightRecording=filename=profiles/cpu.jfr,settings=profile -cp out benchmarks.BruteForceGenerator
python3 tools/flamegraph.py profiles/cpu.jfr profiles/flamegraph.html   # ouvrir dans un navigateur

# Quantifier le goulet
python3 tools/analyze_bottleneck.py profiles/cpu.jfr

# Comparer avant/apres (N repetitions)
python3 tools/benchstat.py 5
```
