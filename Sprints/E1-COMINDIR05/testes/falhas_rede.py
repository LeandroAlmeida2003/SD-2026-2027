"""Cenários do ponto 10 com processos reais, separados das contraprovas unitárias."""
import json
import subprocess
import shutil
import time
from ensaios import ROOT, JAVA, FLAGS, read, wait, scenario_a, scenario_d, now


def majority(t):
    # O filtro de origem própria impede o caso de um só voto no fluxo normal
    # de três membros. Registar esse resultado também é parte do ensaio.
    scenario_a(t)


def persistence(t):
    for k in range(3):
        for i in range(1, 4):
            t.node(k, i)
    def granted():
        for i in range(1, 4):
            for line in read(t.folder / f"eventos-p1-n{i}.txt"):
                if ";VOTO;" in line and ";concedido" in line:
                    return i
        return None
    voter = wait(granted, 30, "primeiro voto concedido")
    instant = now()
    t.kill(1, voter, annotate=False)
    t.node(1, voter)
    (t.folder / "reinicio-apos-voto.json").write_text(json.dumps({"eleitor": voter, "instanteMs": instant}), encoding="utf-8")
    wait(lambda: all(t.leader(k) for k in range(3)), 40, "líderes após reinício sem persistência")
    t.start("processador", "AlarmProcessor", (3,))
    t.producer = t.start("produtor", "Producer", (3,))
    time.sleep(3)


def freshness(t):
    t.boot()
    old = t.leader(1)
    lagging = next(i for i in range(1, 4) if i != old)
    healthy = next(i for i in range(1, 4) if i not in (old, lagging))
    t.isolate(1, lagging, True)
    t.command("S02 20.1")
    t.command("S05 23.7")
    wait(lambda: len(t.records(1, healthy)) == 2, label="candidato atrasado sem os dois registos")
    assert not t.records(1, lagging)
    previous = t.heartbeat(1, old)
    wait(lambda: t.heartbeat(1, old) != previous, label="heartbeat antes da leitura pendente")
    t.command("S02 20.4")
    wait(lambda: len(t.records(1, old)) == 3, 1, "terceira leitura pendente")
    t.kill(1, old)
    t.command("S05 23.9")
    t.kill(1, lagging, annotate=False)
    t.isolate(1, lagging, False)
    # Dirigir o cenário para que seja o nó atrasado a pedir votos primeiro.
    t.properties[f"p1-n{lagging}"] = ["-Draft04.eleicaoMin=1000", "-Draft04.eleicaoMax=1000"]
    t.node(1, lagging)
    elected = wait(lambda: t.leader(1, (old,)), label="eleição do candidato atrasado")
    assert elected == lagging
    wait(lambda: len(t.records(1, healthy)) == 0, label="perda da cauda do eleitor atualizado")
    t.command("S02 20.6")
    wait(lambda: len(t.records(1, lagging)) == 1, label="leitura do novo líder atrasado")
    t.node(1, old)
    wait(lambda: t.converged(1) and len(t.records(1, old)) == 1, label="perda final dos registos na eleição incorreta")
    (t.folder / "candidato-atrasado.json").write_text(json.dumps({"candidato": lagging, "ultimoSeqAntes": 0,
        "eleitorAtualizado": healthy, "ultimoSeqEleitorAntes": 2, "registosFinais": 1}), encoding="utf-8")
    time.sleep(2)


def obsolete(t):
    if getattr(t, 'resume_obsolete', None):
        # Retomar os ficheiros reais do ensaio d já concluído, numa pasta nova.
        # Preservar os eventos anteriores permite auditar toda a cronologia.
        for path in t.resume_obsolete.iterdir():
            if path.is_file() and path.suffix in ('.txt', '.json') and '.console.' not in path.name:
                shutil.copyfile(path, t.folder / path.name)
        (t.folder / 'retomado.json').write_text(json.dumps({'origem': str(t.resume_obsolete),
            'motivo': 'ensaio d concluído; retomar para injetar lote atrasado', 'instanteMs': now()}), encoding='utf-8')
        t.boot()
    else:
        scenario_d(t)
    expected = len(t.records(1, t.leader(1))) + 3
    t.command("S02 21.1")
    t.command("S02 21.2")
    t.command("S02 21.3")
    wait(lambda: t.converged(1) and len(t.records(1, t.leader(1))) == expected, label="três registos novos após ensaio d")
    # Primeiro LOTE do termo antigo era vazio. É uma mensagem válida atrasada.
    snapshot = json.loads((t.folder / "dois-lideres.json").read_text(encoding="utf-8"))
    old = min((term, int(i)) for i, (role, term) in snapshot.items() if role == "LIDER")[1]
    leader = t.leader(1)
    target = next(i for i in range(1, 4) if i not in (old, leader))
    old_term = int(read(t.folder / f"hb-p1-n{old}.txt")[0].split(';')[0])
    latest = t.heartbeat(1, leader)
    wait(lambda: t.heartbeat(1, leader) != latest, label="heartbeat antes da mensagem atrasada")
    message = f"LOTE;{old_term};{old};127.0.0.1;{7100 + 3 + old - 1};1;0;0;0"
    path = t.folder / "lote-antigo.txt"
    path.write_text(message, encoding="utf-8")
    subprocess.run([JAVA, "-cp", str(ROOT / "build/no"), "raft04.ReplayBatch", t.api, "1", str(path)],
                   check=True, capture_output=True, **FLAGS)
    wait(lambda: not t.records(1, target), 1, "aplicação indevida do LOTE obsoleto")
    (t.folder / "lote-obsoleto-aceite.json").write_text(json.dumps({"no": target, "termoLote": old_term,
        "seqAntes": expected, "seqDepois": 0, "instanteMs": now()}), encoding="utf-8")
    wait(lambda: t.converged(1) and len(t.records(1, target)) == expected, label="recuperação no heartbeat atual seguinte")


scenarios = {"maioria": majority, "persistencia": persistence, "atualizacao": freshness,
             "obsoleto": obsolete, "relogios": scenario_a}
