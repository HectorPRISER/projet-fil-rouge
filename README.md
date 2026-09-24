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
| `grpc-version/` | Version B : meme architecture en gRPC/Protobuf, flux bidirectionnel HTTP/2 (module Maven separe, voir plus bas) |
| `RestBenchServer` / `GrpcBenchServer` (+ `ScanOne` dans le .proto) | Route unaire dediee (1 bloc = 1 appel) pour comparer REST/JSON et gRPC/Protobuf a l'identique avec vegeta/ghz |
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
| Distribue gRPC/Protobuf (4 esclaves), meme charge | ~2170 ms — **~x2,9 plus rapide que REST**, ~x2,9 plus lent que TCP brut |
| Tir de charge vegeta (REST) vs ghz (gRPC), 50 req/s, meme travail/requete | P50 55 ms vs 15 ms (**~x3,6**) ; ~690 o vs ~442 o/requete (**~-36%**) |

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

### Version B (gRPC/Protobuf)

Module Maven separe (`grpc-version/`, protobuf-maven-plugin + grpc-java ;
le reste du projet reste pur javac). Contrat dans
[`grpc-version/src/main/proto/crack.proto`](grpc-version/src/main/proto/crack.proto) :
un service `CrackService` avec un seul RPC bidirectionnel `Distribute`
(le worker envoie des `WorkerReport`, le maitre repond par un flux de
`Block`, sur la meme connexion HTTP/2 — zero parsing texte, contrairement
a REST/JSON ou au protocole TCP en lignes).

```bash
cd grpc-version && mvn -q package
java -cp target/crack-grpc-1.0-all.jar benchmarks.grpc.GrpcMaster <port> <minLength> <maxLength> <targetHashHex>
java -cp target/crack-grpc-1.0-all.jar benchmarks.grpc.GrpcWorker <host_maitre> <port>
```

Teste (Niveau 2, 4 esclaves) : **~2170 ms**, contre 6331 ms pour REST/JSON
(**~x2,9 plus rapide**, flux persistant vs 1 requete HTTP par bloc) mais
encore plus lent que le protocole TCP texte brut a workers egaux (751 ms) :
le framing HTTP/2 + serialisation Protobuf a un cout, mais bien moindre que
REST/JSON pour un flux continu de petits messages.

### Tirs de charge (vegeta / ghz) : REST/JSON vs gRPC/Protobuf

Pour comparer les deux protocoles a travail strictement identique (pas
l'architecture distribuee complete, juste le cout protocole), `RestBenchServer`
(`POST /scan`) et `GrpcBenchServer` (RPC unaire `ScanOne`) exposent chacun
UN SEUL bloc de calcul (238 328 hachages SHA-256, ~17 ms de calcul pur) par
appel. Charge : 50 requetes/s, 10 concurrents, 10 s, avec
[vegeta](https://github.com/tsenart/vegeta) (REST) et
[ghz](https://github.com/bojand/ghz) (gRPC) ; CPU mesure via `pidstat`,
bande passante via les compteurs reseau (`/proc/net/dev`, interface loopback).

```bash
java -cp out benchmarks.RestBenchServer 6300
java -cp grpc-version/target/crack-grpc-1.0-all.jar benchmarks.grpc.GrpcBenchServer 6301

vegeta attack -targets=targets.txt -rate=50/s -duration=10s -workers=10 | vegeta report
ghz --insecure --proto=grpc-version/src/main/proto/crack.proto \
    --call=benchmarks.grpc.CrackService.ScanOne -d '{...}' --rps 50 -c 10 -z 10s localhost:6301
```

| Metrique | REST/JSON | gRPC/Protobuf |
|---|---|---|
| P50 | 55 ms | 15 ms |
| P90 | 57 ms | 16,5 ms |
| P99 | 59-75 ms | 18-19 ms |
| CPU serveur (moyenne) | 74,5 % | 80,9 % |
| Octets reseau / requete (RX, loopback) | ~690 o | ~442 o |

gRPC est **~3,6x plus rapide** en latence mediane pour un travail identique
(15 ms, tres proche des ~17 ms de calcul SHA-256 pur : overhead protocole
quasi nul), et consomme **~36 % moins de bande passante** par requete
(binaire + HTTP/2 HPACK vs JSON + en-tetes HTTP/1.1 repetes). REST/JSON
ajoute ~38 ms de latence par requete au-dessus du meme calcul (55 ms vs
17 ms) : le cout du parsing JSON + HTTP/1.1 texte devient le facteur
dominant, pas le calcul. Le CPU serveur legerement plus eleve pour gRPC
(80,9 % vs 74,5 %, a debit egal de 50 req/s) reste a confirmer sur un test
plus long ; l'ecart de latence est la mesure la plus fiable ici.

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
