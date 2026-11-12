"""Ensaios reais com Kubo privado e processos Java. Python 3, JDK 11+, Kubo.
Cada execução cria uma pasta nova; não elimina dados anteriores.
Uso: python testes/ensaios.py --ipfs C:/.../ipfs.exe [--suite smoke|completa]
"""
import argparse
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(shutil.which("javac")).with_name("java.exe" if os.name == "nt" else "java"))
FLAGS = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}


def now():
    return time.time_ns() // 1_000_000


def wait(predicate, timeout=35, label="condição"):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = predicate()
        if result:
            return result
        time.sleep(.08)
    raise RuntimeError("Timeout: " + label)


def read(path):
    try:
        return path.read_text(encoding="utf-8-sig").splitlines()
    except (FileNotFoundError, PermissionError):
        return []


class Trial:
    def __init__(self, folder, api, fixed=False, n=3):
        self.folder = folder
        folder.mkdir(parents=True, exist_ok=False)
        self.api, self.n, self.fixed = api, n, fixed
        self.nodes, self.processes, self.handles = {}, [], []
        self.node_starts = {}
        self.producer = None
        self.builds, self.properties = {}, {}
        (folder / "produtor.txt").touch()

    def start(self, project, cls, args=(), name=None):
        name = name or project
        out = open(self.folder / (name + ".console.txt"), "a", encoding="utf-8")
        self.handles.append(out)
        cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Xmx96m", "-XX:ActiveProcessorCount=2", "-Draft04.api=" + self.api]
        if self.fixed:
            cmd += ["-Draft04.eleicaoMin=6000", "-Draft04.eleicaoMax=6000"]
        cmd += self.properties.get(name, [])
        cmd += ["-cp", str(self.builds.get(project, ROOT / "build" / project)), "raft04." + cls, *map(str, args)]
        p = subprocess.Popen(cmd, cwd=self.folder, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", **FLAGS)
        self.processes.append(p)
        return p

    def node(self, k, i):
        self.node_starts[k, i] = now()
        self.nodes[k, i] = self.start("no", "PartitionNode", (k, i, self.n), f"p{k}-n{i}")

    def role(self, k, i):
        p = self.nodes.get((k, i))
        if p is None or p.poll() is not None:
            return None
        role, term = None, 0
        for line in read(self.folder / f"eventos-p{k}-n{i}.txt"):
            f = line.split(";", 3)
            if len(f) != 4:
                continue
            if int(f[0]) < self.node_starts.get((k, i), 0):
                continue
            kind, detail = f[1], f[3]
            if kind in ("TERMO", "CANDIDATO", "NOVO_LIDER", "DEMISSAO"):
                role = kind
            if kind == "LIDER":
                role = "LIDER"
                term = int(detail.split("termo=")[1].split(";")[0])
        return (role, term)

    def leader(self, k, exclude=()):
        found = []
        for i in range(1, 4):
            if i in exclude or (self.folder / f"isolar-p{k}-n{i}").exists():
                continue
            role = self.role(k, i)
            if role and role[0] == "LIDER":
                found.append((role[1], i))
        return max(found)[1] if found else None

    def boot(self, order=None, clients=True):
        order = list(order or [(k, i) for k in range(self.n) for i in range(1, 4)])
        for k, i in order:
            self.node(k, i)
        if clients:
            self.start("processador", "AlarmProcessor", (self.n,))
            self.producer = self.start("produtor", "Producer", (self.n,))
        wait(lambda: all(self.leader(k) for k in range(self.n)), 60, "primeiros líderes")
        time.sleep(1)

    def command(self, text):
        self.producer.stdin.write(text + "\n")
        self.producer.stdin.flush()

    def records(self, k, i):
        return read(self.folder / f"no-p{k}-n{i}.txt")

    def converged(self, k):
        histories = [self.records(k, i) for i in range(1, 4)]
        return histories[0] == histories[1] == histories[2]

    def heartbeat(self, k, i):
        lines = read(self.folder / f"hb-p{k}-n{i}.txt")
        return lines[-1] if lines else None

    def kill(self, k, i, annotate=True):
        if annotate:
            with open(self.folder / "falhas.txt", "a", encoding="utf-8") as out:
                out.write(f"p{k};n{i};{now()}\n")
        p = self.nodes[k, i]
        p.kill()
        p.wait(timeout=10)

    def isolate(self, k, i, value):
        with open(self.folder / "particoes.txt", "a", encoding="utf-8") as out:
            out.write(f"p{k};n{i};{now()};{'ISOLAR' if value else 'REPARAR'}\n")
        marker = self.folder / f"isolar-p{k}-n{i}"
        if value:
            marker.touch()
        else:
            marker.unlink()

    def close(self):
        for p in self.processes:
            if p.poll() is None:
                p.kill()
        for p in self.processes:
            p.wait(timeout=10)
        for handle in self.handles:
            handle.close()

    def check(self):
        result = {}
        for cls in ("RaftCheck", "CausalCheck"):
            cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", str(ROOT / "build/no"), "raft04." + cls, str(self.n), str(self.folder)]
            if cls == "RaftCheck" and (self.folder / "falhas.txt").exists():
                cmd.append("falhas.txt")
            p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **FLAGS)
            (self.folder / (cls + ".console.txt")).write_text(p.stdout + p.stderr, encoding="utf-8")
            result[cls] = p.returncode
        if (self.folder / "resumo-raft.json").exists():
            result.update(json.loads((self.folder / "resumo-raft.json").read_text(encoding="utf-8")))
        result["pasta"] = self.folder.name
        return result


def scenario_a(t):
    t.boot()
    leader = t.leader(1)
    t.command("S02 20.1")
    t.command("S05 23.7")
    wait(lambda: t.converged(1) and len(t.records(1, leader)) == 2, label="primeiras duas leituras")
    old = t.heartbeat(1, leader)
    wait(lambda: t.heartbeat(1, leader) != old, label="lote anterior à falha")
    t.command("S02 20.4")
    wait(lambda: len(t.records(1, leader)) == 3, 1, "terceira leitura pendente")
    t.kill(1, leader)
    t.command("S05 23.9")
    new = wait(lambda: t.leader(1, (leader,)), label="novo líder")
    t.command("S02 20.6")
    wait(lambda: len(t.records(1, new)) == 3, label="leitura do termo novo")
    t.node(1, leader)
    wait(lambda: t.converged(1), label="truncagem do antigo líder")
    assert any(";TRUNCADO;" in line for line in read(t.folder / f"eventos-p1-n{leader}.txt"))
    time.sleep(2)


def scenario_c(t):
    t.boot()
    leader = t.leader(1)
    t.command("rajada 300 10")
    wait(lambda: len(t.records(1, leader)) > 2, label="rajada iniciada")
    t.kill(1, leader)
    time.sleep(.2)
    orphaned = len(t.records(1, leader)) > max(len(t.records(1, i)) for i in range(1, 4) if i != leader)
    time.sleep(30)
    t.node(1, leader)
    wait(lambda: all(t.converged(k) for k in range(t.n)), label="recuperação após falha")
    if orphaned:
        assert any(";TRUNCADO;" in line for line in read(t.folder / f"eventos-p1-n{leader}.txt")), "Cauda órfã não foi truncada"
    time.sleep(2)


def scenario_d(t):
    t.boot()
    leader = t.leader(1)
    t.command("rajada 300 10")
    wait(lambda: len(t.records(1, leader)) > 2, label="rajada iniciada")
    t.isolate(1, leader, True)
    time.sleep(30)
    new = wait(lambda: t.leader(1, (leader,)), label="líder maioritário")
    snapshot = {str(i): t.role(1, i) for i in range(1, 4)}
    assert snapshot[str(leader)][0] == snapshot[str(new)][0] == "LIDER"
    assert snapshot[str(leader)][1] != snapshot[str(new)][1]
    (t.folder / "dois-lideres.json").write_text(json.dumps(snapshot), encoding="utf-8")
    t.isolate(1, leader, False)
    wait(lambda: t.converged(1), label="reconciliação após isolamento")
    assert any(";TRUNCADO;" in line for line in read(t.folder / f"eventos-p1-n{leader}.txt")), "Isolamento não produziu truncagem esperada"
    time.sleep(3)
    current = t.leader(1)
    follower = next(i for i in range(1, 4) if i != current)
    t.isolate(1, follower, True)
    time.sleep(60)
    t.isolate(1, follower, False)
    wait(lambda: t.leader(1), 40, "eleição após regresso de seguidor")
    time.sleep(15)
    wait(lambda: all(t.converged(k) for k in range(t.n)), label="coincidência final")


def scenario_e(t):
    t.boot()
    leader = t.leader(1)
    follower = next(i for i in range(1, 4) if i != leader)
    survivor = next(i for i in range(1, 4) if i not in (leader, follower))
    t.kill(1, leader)
    t.kill(1, follower, annotate=False)
    for j in range(30):
        t.command(f"S02 {19 + j / 100}")
        time.sleep(1)
    assert t.role(1, survivor)[0] != "LIDER", "Nó sozinho não pode eleger-se"
    restart = now()
    t.node(1, follower)
    wait(lambda: t.leader(1), label="líder após regresso de um nó")
    (t.folder / "regresso.json").write_text(json.dumps({"reinicioMs": restart, "liderMs": now()}), encoding="utf-8")
    t.node(1, leader)
    t.command("S02 22.123")
    time.sleep(5)
    wait(lambda: t.converged(1), label="recuperação dos três nós")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ipfs", required=True)
    parser.add_argument("--suite", choices=("smoke", "completa", "recuperacao", "falhas"), default="smoke")
    parser.add_argument("--mutacoes", type=Path)
    parser.add_argument("--only", help="Variantes de falhas separadas por vírgulas")
    parser.add_argument("--retomar-obsoleto", type=Path)
    args = parser.parse_args()
    run = ROOT / "dados" / time.strftime("%Y%m%d-%H%M%S")
    run.mkdir(parents=True)
    env = dict(os.environ, IPFS_PATH=str(run / "kubo"))
    def ipfs(*cmd):
        return subprocess.run([args.ipfs, *cmd], env=env, capture_output=True, text=True, check=True, **FLAGS)
    ipfs("init", "--profile=test")
    ipfs("config", "Addresses.API", "/ip4/127.0.0.1/tcp/15051")
    ipfs("config", "Addresses.Gateway", "/ip4/127.0.0.1/tcp/15080")
    ipfs("config", "--json", "Addresses.Swarm", '["/ip4/127.0.0.1/tcp/15052"]')
    ipfs("bootstrap", "rm", "--all")
    daemon_log = open(run / "kubo.console.txt", "w", encoding="utf-8")
    daemon = subprocess.Popen([args.ipfs, "daemon", "--enable-pubsub-experiment"], env=env,
                              stdout=daemon_log, stderr=subprocess.STDOUT, **FLAGS)
    api = "http://127.0.0.1:15051/api/v0"
    def ready():
        try:
            urllib.request.urlopen(urllib.request.Request(api + "/version", method="POST"), timeout=1).close()
            return True
        except OSError:
            return False
    summaries = []
    try:
        wait(ready, 30, "Kubo")
        trials = [("9a", scenario_a, False)]
        if args.suite == "falhas":
            from falhas_rede import scenarios
            if not args.mutacoes:
                raise ValueError("--mutacoes deve indicar a pasta com as variantes compiladas")
            trials = [(name, fn, False) for name, fn in scenarios.items()]
            if args.only:
                requested = set(args.only.split(','))
                if not requested.issubset(scenarios):
                    raise ValueError("Variante desconhecida")
                trials = [trial for trial in trials if trial[0] in requested]
        if args.suite == "completa":
            trials += [(f"9b-aleatorio-{i+1:02}", None, False) for i in range(10)]
            trials += [(f"9b-fixo-{i+1:02}", None, True) for i in range(5)]
        if args.suite in ("completa", "recuperacao"):
            trials += [(f"9c-{i+1}", scenario_c, False) for i in range(3)]
            trials += [("9d", scenario_d, False), ("9e", scenario_e, False)]
        for name, scenario, fixed in trials:
            print("INÍCIO", name, flush=True)
            t = Trial(run / name, api, fixed=fixed)
            if args.suite == "falhas":
                t.builds["no"] = (args.mutacoes / name / "build").resolve()
                if name == 'obsoleto' and args.retomar_obsoleto:
                    t.resume_obsolete = args.retomar_obsoleto.resolve()
            problem = None
            try:
                if scenario:
                    scenario(t)
                else:
                    order = [(k, i) for k in range(3) for i in range(1, 4)]
                    if not fixed:
                        random.shuffle(order)
                    t.boot(order, clients=False)
                    time.sleep(2)
            except Exception as exc:
                problem = str(exc)
                print("FALHOU", name, problem, flush=True)
            finally:
                t.close()
            result = t.check()
            if problem:
                result["problema"] = problem
            summaries.append(result)
            (run / "resultados.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
            print("FIM", name, json.dumps(result), flush=True)
            if problem and args.suite == "smoke":
                raise RuntimeError(problem)
    finally:
        daemon.terminate()
        try:
            daemon.wait(timeout=10)
        except subprocess.TimeoutExpired:
            daemon.kill()
            daemon.wait()
        daemon_log.close()
    print("RESULTADOS", run, flush=True)
    if any(r.get("problema") or (args.suite != "falhas" and (r["RaftCheck"] or r["CausalCheck"])) for r in summaries):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
