#!/usr/bin/env python3
"""Equivalent Java de `benchstat old.txt new.txt` : lance N repetitions de
deux classes (avant/apres optimisation), extrait le temps du "Niveau 2"
depuis leur sortie, et calcule moyenne/ecart-type/delta comme benchstat.

Usage:
    python3 tools/benchstat.py [N]   (N = nombre de repetitions, defaut 7)
"""
import re
import statistics
import subprocess
import sys

RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 7
PATTERN = re.compile(r"Niveau 2 : .*\((\d+) tentatives, (\d+) ms\)")


def run(classname):
    times = []
    for _ in range(RUNS):
        out = subprocess.run(
            ["java", "-cp", "out", f"benchmarks.{classname}"],
            capture_output=True, text=True, check=True,
        ).stdout
        m = PATTERN.search(out)
        if not m:
            raise RuntimeError(f"Pattern 'Niveau 2' introuvable dans la sortie de {classname}:\n{out}")
        times.append(int(m.group(2)))
    return times


def report(name, times):
    mean = statistics.mean(times)
    stdev = statistics.stdev(times) if len(times) > 1 else 0.0
    pct = 100 * stdev / mean if mean else 0.0
    print(f"{name:32s} {mean:10.1f} ms ± {pct:4.1f}%   (runs: {times})")
    return mean, stdev


def main():
    print(f"benchstat Java (equivalent) - Niveau 2, {RUNS} repetitions chacun\n")
    print(f"{'':32s} {'temps moyen':>13s}")

    naive_times = run("BruteForceGeneratorNaive")
    naive_mean, _ = report("BruteForceGeneratorNaive (avant)", naive_times)

    optimized_times = run("BruteForceGenerator")
    opt_mean, _ = report("BruteForceGenerator (apres)", optimized_times)

    delta = 100 * (opt_mean - naive_mean) / naive_mean
    factor = naive_mean / opt_mean
    print(f"\ndelta: {delta:+.2f}% (x{factor:.2f} plus rapide)")

    # Test de significativite simple (Welch t-test degrade sans scipy) :
    # si les intervalles moyenne+-stdev ne se chevauchent pas, l'ecart est net.
    naive_sd = statistics.stdev(naive_times) if len(naive_times) > 1 else 0.0
    opt_sd = statistics.stdev(optimized_times) if len(optimized_times) > 1 else 0.0
    if naive_mean - naive_sd > opt_mean + opt_sd:
        print("=> Ecart largement superieur au bruit de mesure (intervalles disjoints) : gain confirme, pas du bruit.")
    else:
        print("=> Attention : les intervalles se chevauchent, gain non concluant statistiquement.")


if __name__ == "__main__":
    main()
