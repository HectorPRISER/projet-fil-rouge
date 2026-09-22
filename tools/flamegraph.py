#!/usr/bin/env python3
"""Equivalent de "go tool pprof -http=:8080" pour un fichier JFR (JDK Flight
Recorder). Parse les echantillons jdk.ExecutionSample via `jfr print`,
construit un arbre d'appels et genere un flamegraph interactif autonome
(HTML + SVG + JS inline, sans dependance externe/CDN) qu'on ouvre dans un
navigateur, comme la vue web de pprof.

Usage:
    python3 tools/flamegraph.py cpu.jfr flamegraph.html
"""
import html
import json
import subprocess
import sys


def parse_stacks(jfr_path):
    proc = subprocess.run(
        ["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "128", jfr_path],
        capture_output=True, text=True, check=True,
    )
    stacks = []
    current = []
    in_stack = False
    for line in proc.stdout.splitlines():
        stripped = line.strip()
        if stripped == "stackTrace = [":
            in_stack = True
            current = []
            continue
        if in_stack:
            if stripped == "]":
                in_stack = False
                if current:
                    # jfr liste la feuille (frame executee) en premier, la racine en dernier.
                    # Le flamegraph veut la racine en premier -> on inverse.
                    stacks.append(list(reversed(current)))
                continue
            # Format : "pkg.Class.method(args) line: 42" -> on ne garde que la signature.
            frame = stripped.rsplit(" line:", 1)[0]
            current.append(frame)
    return stacks


def build_tree(stacks):
    root = {"name": "root", "count": 0, "children": {}}
    for stack in stacks:
        node = root
        node["count"] += 1
        for frame in stack:
            node = node["children"].setdefault(frame, {"name": frame, "count": 0, "children": {}})
            node["count"] += 1
    return root


def tree_to_list(node):
    return {
        "name": node["name"],
        "count": node["count"],
        "children": [tree_to_list(c) for c in node["children"].values()],
    }


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<title>Flamegraph CPU - %(title)s</title>
<style>
  body { font-family: -apple-system, Segoe UI, Arial, sans-serif; margin: 0; background: #1e1e1e; color: #ddd; }
  #header { padding: 12px 16px; background: #252526; border-bottom: 1px solid #333; }
  #header h1 { font-size: 15px; margin: 0 0 4px; }
  #header p { font-size: 12px; margin: 0; color: #999; }
  #chart { width: 100%%; }
  .frame { stroke: #1e1e1e; stroke-width: 0.5; cursor: pointer; }
  .frame:hover { stroke: #fff; stroke-width: 1; }
  .frame-label { font-size: 11px; fill: #111; pointer-events: none; font-family: monospace; }
  #tooltip {
    position: fixed; display: none; background: #000; color: #fff; padding: 6px 10px;
    border-radius: 4px; font-size: 12px; font-family: monospace; pointer-events: none; z-index: 10;
    max-width: 600px; word-break: break-all;
  }
  #reset { margin-left: 12px; font-size: 12px; color: #6cf; cursor: pointer; }
</style>
</head>
<body>
<div id="header">
  <h1>Flamegraph CPU (JFR jdk.ExecutionSample) - %(title)s <span id="reset">[reinitialiser le zoom]</span></h1>
  <p>%(samples)d echantillons. Clic = zoom sur un frame. Survol = detail. Equivalent de "go tool pprof -http".</p>
</div>
<svg id="chart" height="900"></svg>
<div id="tooltip"></div>
<script>
const data = %(data_json)s;

const svg = document.getElementById("chart");
const tooltip = document.getElementById("tooltip");
const ROW_H = 18;
let currentRoot = data;
const totalCount = data.count;

function color(name) {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  const hue = hash %% 360;
  return `hsl(${hue}, 60%%, 55%%)`;
}

function render(root) {
  svg.innerHTML = "";
  const width = svg.clientWidth || window.innerWidth;
  svg.setAttribute("width", width);

  function layout(node, x, y, w, depth) {
    if (w < 0.5) return;
    const el = document.createElementNS("http://www.w3.org/2000/svg", "rect");
    el.setAttribute("x", x);
    el.setAttribute("y", y);
    el.setAttribute("width", Math.max(w, 0.5));
    el.setAttribute("height", ROW_H);
    el.setAttribute("fill", color(node.name));
    el.setAttribute("class", "frame");
    el.addEventListener("click", () => { currentRoot = node; render(currentRoot); });
    el.addEventListener("mousemove", (e) => {
      const pct = (100 * node.count / totalCount).toFixed(1);
      tooltip.style.display = "block";
      tooltip.style.left = e.clientX + 12 + "px";
      tooltip.style.top = e.clientY + 12 + "px";
      tooltip.textContent = `${node.name}  —  ${node.count} echantillons (${pct}%%)`;
    });
    el.addEventListener("mouseleave", () => { tooltip.style.display = "none"; });
    svg.appendChild(el);

    if (w > 30) {
      const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
      label.setAttribute("x", x + 3);
      label.setAttribute("y", y + ROW_H - 5);
      label.setAttribute("class", "frame-label");
      const shortName = node.name.length > w / 6 ? node.name.slice(0, Math.max(3, Math.floor(w / 6))) + "…" : node.name;
      label.textContent = shortName;
      svg.appendChild(label);
    }

    let childX = x;
    const scale = w / Math.max(node.count, 1);
    for (const child of node.children.sort((a, b) => b.count - a.count)) {
      const childW = child.count * scale;
      layout(child, childX, y + ROW_H, childW, depth + 1);
      childX += childW;
    }
  }

  layout(root, 0, 0, width, 0);
  svg.setAttribute("height", 900);
}

document.getElementById("reset").addEventListener("click", () => { currentRoot = data; render(currentRoot); });
window.addEventListener("resize", () => render(currentRoot));
render(currentRoot);
</script>
</body>
</html>
"""


def main():
    if len(sys.argv) != 3:
        print("Usage: flamegraph.py <fichier.jfr> <sortie.html>", file=sys.stderr)
        sys.exit(1)
    jfr_path, out_path = sys.argv[1], sys.argv[2]

    stacks = parse_stacks(jfr_path)
    if not stacks:
        print("Aucun echantillon jdk.ExecutionSample trouve dans " + jfr_path, file=sys.stderr)
        sys.exit(1)

    tree = tree_to_list(build_tree(stacks))
    output = HTML_TEMPLATE % {
        "title": html.escape(jfr_path),
        "samples": len(stacks),
        "data_json": json.dumps(tree),
    }
    with open(out_path, "w") as f:
        f.write(output)
    print(f"{len(stacks)} echantillons -> {out_path}")


if __name__ == "__main__":
    main()
