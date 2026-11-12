"""Compila cinco variantes isoladas e executa contraprovas dirigidas.
Nunca altera os fontes de produção. Os dados são fixtures de teste, não ensaios 9.
"""
import json
from pathlib import Path
import shutil
import subprocess
import time
from ensaios import ROOT, JAVA, FLAGS


def main():
    root = ROOT / "dados" / ("mutacoes-" + time.strftime("%Y%m%d-%H%M%S"))
    root.mkdir(parents=True)
    variants = {
        "maioria": ("RaftState.java", "if (votes.size() <= groupSize / 2)", "if (votes.size() < groupSize / 2)"),
        "persistencia": ("RaftState.java", "private void persist() {", "private void persist() { if (true) return;"),
        "atualizacao": ("RaftState.java", "&& upToDate;", ";"),
        "obsoleto": ("RaftState.java", "if (bTerm < term) return false;", "/* validação do termo removida */"),
        "relogios": ("PartitionNode.java", "restoreClocks(); hb = 0; pending.clear();", "seq = file.lastSeq(); lamport = 0; vector = new VectorClock(n); hb = 0; pending.clear();"),
    }
    results = []
    for name, (filename, before, after) in variants.items():
        folder = root / name
        src, build, data = folder / "src", folder / "build", folder / "dados"
        src.mkdir(parents=True)
        build.mkdir()
        data.mkdir()
        for source in (ROOT / "raft04-no/src/raft04").glob("*.java"):
            text = source.read_text(encoding="utf-8-sig")
            if source.name == filename:
                assert before in text, (name, "Padrão não encontrado")
                text = text.replace(before, after, 1)
            (src / source.name).write_text(text, encoding="utf-8")
        shutil.copyfile(ROOT / "testes/MutationProbe.java", src / "MutationProbe.java")
        subprocess.run([shutil.which("javac"), "--release", "11", "-encoding", "UTF-8", "-d", str(build),
                        *map(str, src.glob("*.java"))], check=True, **FLAGS)
        cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Draft04.eleicaoMin=1", "-Draft04.eleicaoMax=1", "-cp", str(build)]
        probe = subprocess.run([*cmd, "raft04.MutationProbe", name], cwd=data, capture_output=True,
                               text=True, encoding="utf-8", errors="replace", timeout=30, **FLAGS)
        (folder / "contraprova.txt").write_text(probe.stdout + probe.stderr, encoding="utf-8")
        result = {"mutacao": name, "contraprova": probe.returncode}
        for cls in ("RaftCheck", "CausalCheck"):
            # Analisadores corretos; só a variante sob teste foi corrompida.
            p = subprocess.run([JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", str(ROOT / "build/no"),
                                "raft04." + cls, "1", str(data)], capture_output=True, text=True,
                               encoding="utf-8", errors="replace", **FLAGS)
            (folder / (cls + ".txt")).write_text(p.stdout + p.stderr, encoding="utf-8")
            result[cls] = p.returncode
        results.append(result)
        expected = {"maioria": (1, 0), "persistencia": (1, 0), "atualizacao": (0, 0),
                    "obsoleto": (1, 0), "relogios": (0, 1)}[name]
        assert (result["RaftCheck"], result["CausalCheck"]) == expected, result
        summary = json.loads((data / "resumo-raft.json").read_text(encoding="utf-8"))
        if name in ("maioria", "persistencia"):
            assert summary["multiplosLideres"] == 1, summary
        if name == "obsoleto":
            assert summary["divergencias"] == 1, summary
        print(result, flush=True)
    (root / "resultados.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(root, flush=True)
    if any(r["contraprova"] for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
