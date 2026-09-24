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
| `ScalingBenchmark` | Speedup/efficacite en fonction du nombre de workers (1, 2, 4... N coeurs) sur un scan exhaustif de taille fixe |
| `CrackUtil` | Logique de scan partagee entre le mode local et le mode distribue |
| `DistributedMaster` / `DistributedWorker` | Architecture maitre/esclaves sur TCP : partitionne et distribue les blocs a des workers distants (processus/machines separes) |
| `JsonUtil` | JSON minimal fait main (objets plats), sans dependance externe |
| `RestMaster` / `RestWorker` | Version A : meme architecture maitre/esclaves, mais en HTTP/1.1 + JSON (`GET /block`, `POST /report`) |
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
| Scaling 1→20 workers, AVANT correction (compteur `AtomicLong` partage = contention) | efficacite 100%→11,6% : loin du lineaire |
| Scaling 1→20 workers, APRES correction (compteur local par worker, merge final) | quasi-lineaire jusqu'a 8 coeurs (efficacite 71,5%), plateau ensuite ; speedup x7,14 a 20 coeurs |
| Distribue TCP (4 esclaves) vs Distribue REST/JSON (4 esclaves), meme charge | 751 ms vs 6331 ms — **REST ~x8 plus lent** (overhead requete HTTP par bloc) |

Details du profiling + preuve statistique : voir
[`profiles/audit_report.md`](profiles/audit_report.md).

## Architecture distribuee (maitre/esclaves)

```bash
# Maitre (partitionne et distribue par TCP)
java -cp out benchmarks.DistributedMaster <port> <minLength> <maxLength> <targetHashHex>

# Un ou plusieurs esclaves (meme machine ou machines distantes)
java -cp out benchmarks.DistributedWorker <host_maitre> <port>
```

Exemple (Niveau 2, 4 esclaves) :
```bash
java -cp out benchmarks.DistributedMaster 6000 1 4 bd7d0ea8cf7ade4a446ba4efc46fd99071ec3f423770991ac51f70ec5a894dc7 &
for i in 1 2 3 4; do java -cp out benchmarks.DistributedWorker localhost 6000 & done
```

Teste localement (localhost) : 1 esclave = 1319 ms, 8 esclaves = 747 ms (~x1,77).
Le gain est plus faible qu'en local threads (~x7 a coeurs egaux) car chaque bloc
implique un aller-retour reseau synchrone (maitre attend la reponse avant d'envoyer
le bloc suivant) : la distribution reseau vaut le cout surtout sur de plus gros blocs.

### Version A (REST/JSON)

```bash
java -cp out benchmarks.RestMaster <port> <minLength> <maxLength> <targetHashHex>
java -cp out benchmarks.RestWorker http://<host_maitre>:<port>
```

Meme partitionnement, mais protocole HTTP/1.1 standard (`GET /block` /
`POST /report`, JSON) au lieu du protocole texte sur TCP brut. Teste (Niveau
2, 4 esclaves) : **6331 ms**, contre 751 ms pour la version TCP a workers
egaux — **~x8 plus lent**. Le cout vient de la requete HTTP par bloc (headers,
connexion), negligeable devant le calcul seulement si les blocs sont gros.
REST/JSON gagne en interoperabilite (client HTTP standard, JSON lisible) ;
le protocole TCP texte gagne en performance sur du grain fin.

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
