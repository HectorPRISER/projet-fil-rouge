# Projet fil rouge - Optimisation backend

Suite de benchmarks Java illustrant l'impact des structures de donnees et de
l'acces memoire sur les performances (cache CPU, localite, complexite
algorithmique).

## Structure

```
src/main/java/benchmarks/
    BruteForceGenerator.java        Casse un hash SHA-256 par force brute
    CacheAccessBenchmark.java       Acces memoire sequentiel vs disperse (cache miss)
    CandidateStructureBenchmark.java ArrayList vs LinkedList (parcours + acces indexe)
    HashThroughputBenchmark.java    ArrayList vs LinkedList sur un calcul de hachage
    StringConcatEscapeBenchmark.java Diagnostic d'echappement vers le tas (concat String vs StringBuilder)
    StructPaddingBenchmark.java     Compactage de structure (padding) : ordre des champs
    FixedBufferBenchmark.java       Buffer fixe + mutation par index vs StringBuilder/String.format
    ZeroAllocValidationBenchmark.java Validation 0 B/op, 0 allocs/op sur la boucle critique
    Main.java                       Lance les 8 benchmarks a la suite
```

Tous les fichiers sont dans le package `benchmarks`.

## Compiler et lancer

Depuis la racine du projet (`projet-fil-rouge/`) :

```bash
# Compiler tout
javac -d out src/main/java/benchmarks/*.java

# Lancer tout (les 8 benchmarks a la suite)
java -cp out benchmarks.Main

# Lancer un benchmark en particulier
java -cp out benchmarks.BruteForceGenerator
java -cp out benchmarks.CacheAccessBenchmark
java -cp out benchmarks.CandidateStructureBenchmark
java -cp out benchmarks.HashThroughputBenchmark
java -cp out benchmarks.StringConcatEscapeBenchmark
java -cp out benchmarks.StructPaddingBenchmark
java -cp out benchmarks.FixedBufferBenchmark
java -cp out benchmarks.ZeroAllocValidationBenchmark
```

## Detail de chaque fichier

### BruteForceGenerator

Casse deux hash SHA-256 en testant toutes les combinaisons de caracteres
(alphanumerique, majuscule+minuscule+chiffres) par ordre croissant de
longueur, jusqu'a trouver le mot dont le hash correspond.

- Niveau 1 : mots de 1 a 3 caracteres
- Niveau 2 : mots de 1 a 4 caracteres

Sert de reference "cout brut" d'un calcul intensif (hachage) avant
d'introduire les questions de structure de donnees / cache.

### CacheAccessBenchmark

Compare, sur un tableau de 64 millions d'entiers (~256 Mo, largement au-dela
des caches L1/L2/L3) :

- un parcours **sequentiel** (index croissant, prefetch materiel efficace)
- un parcours **disperse** (pointer-chasing via une permutation cyclique
  aleatoire, aucun prefetch possible, cache miss quasi systematique)

Mesure le temps par acces, le debit (Mops/s) et estime le nombre de cycles
CPU perdus a attendre la RAM lors d'un cache miss.

### CandidateStructureBenchmark

Genere 2 millions de mots de 4 caracteres, les stocke dans un `ArrayList`
(tableau contigu) et une `LinkedList` (noeuds chaines disperses en memoire),
puis compare :

- le parcours sequentiel (via iterator)
- l'acces aleatoire par index (`get(i)`)

Illustre le cout O(n) de `LinkedList.get(i)` face au O(1) de `ArrayList`.

### HashThroughputBenchmark

Meme principe que `CandidateStructureBenchmark`, mais la charge de travail
est un calcul SHA-256 sur chaque candidat (parcours sequentiel uniquement,
pas d'acces indexe). Permet de voir si le cout du calcul (hachage) masque ou
non l'ecart de localite memoire entre les deux structures.

### StringConcatEscapeBenchmark

Equivalent Java du diagnostic d'echappement (analogue a `go build
-gcflags="-m"` en Go) : au lieu de lire un rapport du compilateur, on mesure
directement les octets alloues sur le tas via `ThreadMXBean.getThreadAllocatedBytes`
(package `com.sun.management`).

Compare, sur 20 000 iterations (volontairement limite : la concatenation
`+=` est O(n^2), 1 million d'iterations prendrait plusieurs minutes) :

- concatenation avec `+=` : chaque tour cree une nouvelle `String` immuable
  (l'ancienne devient inaccessible), l'objet echappe systematiquement vers
  le tas car sa reference est reassignee a chaque iteration -> des millions
  d'objets alloues
- `StringBuilder.append` : mutation en place d'un seul buffer interne,
  quasiment aucune allocation supplementaire

Confirme concretement (par des octets alloues mesures, pas juste une
estimation) que la concatenation `+=` en boucle est le principal generateur
d'allocations "qui echappent" evoquees par l'analyse d'echappement.

### StructPaddingBenchmark

Equivalent Java du compactage de structure par ordre de taille decroissante
(technique classique en C/Go : le compilateur respecte l'ordre de
declaration des champs, donc mal les ordonner cree du padding invisible).

Definit deux classes identiques (memes champs `long`/`int`/`short`/`byte`/
`boolean`) mais dans un ordre different :

- `CandidateUnordered` : ordre "naif" (boolean, long, int, byte, short)
- `CandidateOrdered` : tailles decroissantes (long, int, short, byte,
  boolean)

Mesure la taille reelle de chaque instance via `ThreadMXBean.getThreadAllocatedBytes`
(meme technique que `StringConcatEscapeBenchmark`) sur 1 million d'objets de
chaque type.

**Resultat constate et explication :** les deux classes occupent exactement
la meme taille memoire (36 octets/instance dans nos mesures). Contrairement
a Go ou C, le JVM (HotSpot) reordonne deja automatiquement les champs par
taille decroissante au chargement de la classe (long/double, puis int/float,
puis short/char, puis byte/boolean, puis references), quel que soit l'ordre
ecrit dans le code source. Reordonner les champs a la main en Java n'a donc
aucun effet sur le layout memoire reel : cette optimisation, utile en C/Go,
est deja faite pour nous par la JVM.

### FixedBufferBenchmark

Equivalent Java du "buffer fixe sur la pile" (`[8]byte` en Go, mute par
index) : remplacer les allocations dynamiques et concatenations par un
tableau de taille fixe connue a l'avance, rempli par acces direct a
l'index plutot que par concatenation/formatage.

Compare deux implementations de la conversion hash SHA-256 -> hexadecimal
sur 200 000 hachages :

- **naive** : `StringBuilder` + `String.format("%02x", b)` par octet
  (chaque appel a `String.format` alloue un `Formatter` et ses objets
  internes)
- **buffer fixe** : `char[64]` (taille connue : 32 octets de hash -> 64
  caracteres hexa), rempli directement via `buffer[i*2]` / `buffer[i*2+1]`
  a partir d'une table `HEX_DIGITS` precalculee, une seule `String` creee
  a la fin

Resultat mesure (octets alloues via `ThreadMXBean`) : la version buffer
fixe reduit les allocations d'un facteur ~48x. Le tableau local ne
s'echappant jamais de la methode, il est aussi un candidat naturel a la
scalar replacement du JIT (variante Java de l'allocation sur pile).

### ZeroAllocValidationBenchmark

Equivalent Java de `go test -bench . -benchmem` validant 0 B/op et 0
allocs/op : mesure les octets alloues par operation de la boucle critique
via `ThreadMXBean` et affiche un verdict PASS/FAIL, avec le debit (ops/s).

La boucle critique (5 millions d'iterations) reutilise systematiquement
les memes buffers, sans jamais faire de `new` a l'interieur de la boucle
chronometree :

- `input` (`byte[8]`) mute par index (encodage manuel de l'entier, pas de
  `String`/`Integer.toString`)
- `digest.digest(hashOut, 0, hashOut.length)` ecrit le resultat SHA-256
  dans un buffer fourni (`hashOut`), au lieu de `digest()` qui alloue un
  nouveau tableau a chaque appel
- `hexOut` (`char[64]`, meme technique que `FixedBufferBenchmark`) rempli
  par index

Resultat mesure : ~0 B/op (quelques centaines d'octets de bruit ponctuel
sur 5M iterations, negligeable), verdict **PASS**, debit de l'ordre de
12 millions d'operations/seconde.

### Main

Point d'entree unique qui appelle les `main()` des 8 classes ci-dessus dans
l'ordre, avec un separateur affiche avant chacune.

## Historique du projet

1. Ecriture de `BruteForceGenerator` : cassage de hash SHA-256 par force
   brute, pour avoir un calcul de reference couteux en CPU.
2. Ecriture de `HashThroughputBenchmark` : comparaison ArrayList/LinkedList
   sur ce meme calcul de hachage, pour voir l'effet de la structure de
   donnees sur un traitement CPU-bound.
3. Ecriture de `CandidateStructureBenchmark` : meme comparaison ArrayList vs
   LinkedList mais isolee sur le cout memoire pur (parcours + acces indexe,
   sans hachage), pour separer l'effet "calcul" de l'effet "structure".
4. Ecriture de `CacheAccessBenchmark` : experience plus bas niveau, sur un
   tableau brut, pour isoler et quantifier precisement le cout d'un cache
   miss RAM (acces sequentiel vs disperse via pointer-chasing).
5. Ajout de `Main` : point d'entree unique pour lancer les benchmarks a la
   suite sans les demarrer un par un.
6. Reorganisation en package `benchmarks` sous `src/main/java/benchmarks/`
   (au lieu de fichiers .java a la racine) pour une architecture Java
   standard.
7. Ecriture de `StringConcatEscapeBenchmark` : diagnostic d'echappement
   vers le tas (equivalent Java du `go build -gcflags="-m"`), pour montrer
   que la concatenation `+=` en boucle alloue massivement contrairement a
   `StringBuilder`.
8. Ecriture de `StructPaddingBenchmark` : tentative de compactage de
   structure par ordre de taille decroissante (equivalent Java du padding
   C/Go). Resultat : sans effet en Java, car HotSpot reordonne deja les
   champs automatiquement au chargement de la classe.
9. Ecriture de `FixedBufferBenchmark` : remplacement des concatenations/
   `String.format` par un buffer fixe (`char[64]`) mute par index
   (equivalent Java du buffer fixe `[8]byte` sur la pile en Go). Gain
   mesure : ~48x moins d'octets alloues par hachage.
10. Ecriture de `ZeroAllocValidationBenchmark` : validation 0 B/op, 0
    allocs/op sur une boucle critique entierement basee sur buffers
    reutilises (equivalent Java du `go test -bench . -benchmem`). Verdict
    PASS mesure, ~12M ops/s.
