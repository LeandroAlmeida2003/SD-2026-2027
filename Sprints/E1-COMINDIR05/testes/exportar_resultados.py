"""Gera relatório apenas a partir dos resultados existentes e arquiva evidências.
Uso: python testes/exportar_resultados.py --arranques dados/RUN1 --recuperacao dados/RUN2 --mutacoes dados/mutacoes-RUN
"""
import argparse
import json
from pathlib import Path
import re
import statistics
import zipfile
from ensaios import ROOT, read


def stats(values):
    return " / ".join(f"{x / 1000:.3f}" for x in (min(values), statistics.mean(values), max(values))) if values else "não medido"


def elections(folder):
    first_terms, split, total = [], 0, 0
    for k in range(3):
        candidates, leaders = set(), []
        for i in range(1, 4):
            for line in read(folder / f"eventos-p{k}-n{i}.txt"):
                fields = line.split(";", 3)
                if len(fields) != 4 or fields[1] not in ("CANDIDATO", "LIDER"):
                    continue
                term = int(re.search(r"termo=(\d+)", fields[3])[1])
                if fields[1] == "CANDIDATO":
                    candidates.add(term)
                else:
                    leaders.append((int(fields[0]), term))
        if leaders:
            first_terms.append(min(leaders)[1])
        split += len(candidates - {term for _, term in leaders})
        total += len(candidates)
    return first_terms, split, total


def terms_p1(folder):
    terms = set()
    for i in range(1, 4):
        for line in read(folder / f"eventos-p1-n{i}.txt"):
            if ';CANDIDATO;' in line or ';LIDER;' in line:
                terms.add(int(re.search(r'termo=(\d+)', line)[1]))
    return sorted(terms)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arranques", type=Path, required=True)
    parser.add_argument("--recuperacao", type=Path, required=True)
    parser.add_argument("--mutacoes", type=Path, required=True)
    parser.add_argument("--rede-mutacoes", type=Path)
    parser.add_argument("--rede-adicional", type=Path, action='append', default=[])
    args = parser.parse_args()
    args.arranques = args.arranques.resolve()
    args.recuperacao = args.recuperacao.resolve()
    args.mutacoes = args.mutacoes.resolve()
    live = []
    live_folders = {}
    if args.rede_mutacoes:
        args.rede_mutacoes = args.rede_mutacoes.resolve()
        live = json.loads((args.rede_mutacoes / "resultados.json").read_text(encoding="utf-8"))
        assert len(live) == 5 and not any(r.get("problema") for r in live), "Há variantes de rede por resolver"
        live_folders = {r["pasta"]: args.rede_mutacoes / r["pasta"] for r in live}
        for additional_path in args.rede_adicional:
            additional = additional_path.resolve()
            replacements = json.loads((additional / "resultados.json").read_text(encoding="utf-8"))
            updated = {r["pasta"]: r for r in live}
            for r in replacements:
                updated[r["pasta"]] = r
                live_folders[r["pasta"]] = additional / r["pasta"]
            live = list(updated.values())
        assert not any(r.get('problema') for r in live), 'Há variantes adicionais por resolver'
    base = json.loads((args.arranques / "resultados.json").read_text(encoding="utf-8"))
    recovery = json.loads((args.recuperacao / "resultados.json").read_text(encoding="utf-8"))
    selected = [(args.arranques / r["pasta"], r) for r in base if r["pasta"].startswith("9b-")]
    selected += [(args.recuperacao / r["pasta"], r) for r in recovery]
    assert len(selected) == 21, "Esperados 15 arranques + a + 3c + d + e"
    assert all(not r.get("problema") and r["RaftCheck"] == r["CausalCheck"] == 0 for _, r in selected), "Há ensaios por resolver"
    random_runs = [(f, r) for f, r in selected if r["pasta"].startswith("9b-aleatorio")]
    fixed_runs = [(f, r) for f, r in selected if r["pasta"].startswith("9b-fixo")]
    typical = [r for _, r in selected if not r["pasta"].startswith("9b-fixo") and r["pasta"] not in ("9d", "9e")]
    startup = [v for r in typical for v in r["arranquesMs"]]
    failures = [v for r in typical for v in r["indisponibilidadesMs"]]
    rand_terms = [v for f, _ in random_runs for v in elections(f)[0]]
    fixed_terms = [v for f, _ in fixed_runs for v in elections(f)[0]]
    rand_split = sum(elections(f)[1] for f, _ in random_runs)
    fixed_split = sum(elections(f)[1] for f, _ in fixed_runs)
    dest = ROOT / "resultados"
    dest.mkdir(exist_ok=True)
    summaries = [{**r, "origem": str(folder.relative_to(ROOT)).replace('\\', '/')} for folder, r in selected]
    (dest / "resumo.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
    mutation_results = json.loads((args.mutacoes / "resultados.json").read_text(encoding="utf-8"))
    (dest / "mutacoes.json").write_text(json.dumps(mutation_results, indent=2), encoding="utf-8")
    if live:
        (dest / "mutacoes-rede.json").write_text(json.dumps(live, indent=2), encoding="utf-8")
    with zipfile.ZipFile(dest / "evidencias.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for folder, result in selected:
            for path in folder.iterdir():
                if path.is_file() and path.suffix in (".txt", ".json") and ".console." not in path.name:
                    archive.write(path, f"rede/{result['pasta']}/{path.name}")
        for path in args.mutacoes.rglob("*.txt"):
            if "build" not in path.parts:
                archive.write(path, "mutacoes/" + path.relative_to(args.mutacoes).as_posix())
        for result in live:
            folder = live_folders[result["pasta"]]
            for path in folder.iterdir():
                if path.is_file() and path.suffix in (".txt", ".json") and ".console." not in path.name:
                    archive.write(path, f"mutacoes-rede/{result['pasta']}/{path.name}")
        archive.writestr("LE-ME.txt", "rede/: processos Java e Kubo reais. mutacoes/: contraprovas dirigidas com fixtures, nao medicoes de rede. Nenhum repositorio Kubo ou chave privada esta incluido.\n")
    report = ["# COMINDIR05 — relatório de execução", "",
              "Resultados obtidos nesta máquina com JDK 21 (compilação `--release 11`) e Kubo 0.43.1, usando os temporizadores da ficha.", "",
              "Os 21 ensaios selecionados terminaram com `RaftCheck` e `CausalCheck` a devolver 0. Os históricos finais dos três nós coincidem, incluindo os comprimentos, nos ensaios de recuperação.", "",
              "As evidências estão em [resultados/evidencias.zip](resultados/evidencias.zip) e o resumo estruturado em [resultados/resumo.json](resultados/resumo.json). O arquivo contém ficheiros de aplicação; exclui o repositório privado de Kubo e as suas chaves.", "",
              "## Métricas pedidas", "", "| Métrica | Valor medido |", "|---|---|",
              f"| Primeiro líder, mínimo / média / máximo (s), ensaios a–c com prazo aleatório | {stats(startup)} |",
              f"| Termos até ao primeiro líder, média aleatório / fixo | {statistics.mean(rand_terms):.3f} / {statistics.mean(fixed_terms):.3f} |",
              f"| Votos divididos nos dez arranques aleatórios / cinco fixos (três partições por arranque) | {rand_split} / {fixed_split} |",
              f"| Indisponibilidade de uma falha do líder, mínimo / média / máximo (s), a e três c | {stats(failures)} |",
              f"| Termos com mais de um líder, todos os ensaios | {sum(r['multiplosLideres'] for _, r in selected)} |",
              f"| Termos decrescentes | {sum(r['termosDecrescentes'] for _, r in selected)} |",
              f"| Seq divergentes no prefixo final comum | {sum(r['divergencias'] for _, r in selected)} |", "",
              "Os arranques aleatórios e fixos têm 30 e 15 observações de partição, respetivamente. Os processos são lançados pelo mesmo script em rápida sucessão; o sistema operativo não garante arranque simultâneo exato. Um prazo fixo não obriga a haver voto dividido em todas as execuções.", "",
              "| Ensaio | Indisponibilidade (ms) | Leituras perdidas | Registos truncados | Reapareceram com outro seq |",
              "|---|---:|---:|---:|---:|"]
    for folder, r in selected:
        if r["pasta"].startswith("9b-"):
            continue
        report.append(f"| {r['pasta']} | {r['indisponibilidadesMs'] or 'não aplicável'} | {r['perdidas']} | {r['truncados']} | {r['reaparecidos']} |")
    report += ["", "9(e) inclui 30 s de ausência deliberada de maioria; a sua indisponibilidade não é comparável à janela teórica de uma falha única.", "",
               "### Leituras perdidas por falha", "", "| Ensaio | Perdidas enviadas durante a eleição | Perdidas fora desse intervalo |", "|---|---:|---:|"]
    for folder, r in selected:
        if r["pasta"] != "9a" and not r["pasta"].startswith("9c-"):
            continue
        text = (folder / "relatorio-raft.txt").read_text(encoding="utf-8")
        lost = set(re.findall(r"  PERDIDA=(.+)", text))
        during = set(re.findall(r"    leitura_na_indisponibilidade=(.+)", text))
        report.append(f"| {r['pasta']} | {len(lost & during)} | {len(lost - during)} |")
    report += ["", "Perdas fora do intervalo devem ser confrontadas com a auditoria de truncagem. Os ficheiros truncados incluem também alarmes, enquanto `produtor.txt` inclui apenas as leituras do produtor. Não se devem igualar automaticamente estes dois totais.", "",
               "As três indisponibilidades de 9(c) podem ser comparadas com ]4 s;12,2 s], sob as hipóteses de rede rápida e eleição sem colisão. `rajada 300 10` pode terminar antes da eleição: perder mais tempo de serviço não implica perder leituras a uma taxa constante depois de acabar a rajada.", "",
               "## CAP e isolamento", "", "| Cenário | Maioria aceita? | Minoria aceita? | Após reparação | Interpretação |", "|---|---|---|---|---|",
               "| Falha do líder, 9(c) | Após eleição | Líder parado não recebe | Antigo líder segue e trunca cauda divergente | Eleição exige maioria; indisponibilidade transitória |",
               "| Líder isolado, 9(d) | Após eleição | Antigo líder aceita IN localmente | Escritas divergentes são descartadas | Não é CP completo: faltam confirmações de commit |",
               "| Seguidor isolado, 9(d) | Líder saudável continua | Seguidor não aceita como líder | Termo elevado pode demitir líder saudável | Falta Pre-Vote; perturbação no regresso |",
               "| Duas falhas, 9(e) | Não há maioria | Sobrevivente era seguidor e não se elege | Eleição volta quando regressa um nó | Disponibilidade de escrita sacrificada por falta de quórum |", ""]
    d_folder = next(f for f, r in selected if r["pasta"] == "9d")
    snapshot = json.loads((d_folder / "dois-lideres.json").read_text(encoding="utf-8"))
    report += ["Snapshot dos papéis durante o isolamento (termo só é mostrado para líderes):", "", "```json", json.dumps(snapshot, ensure_ascii=False, indent=2), "```", ""]
    partitions = read(d_folder / "particoes.txt")
    if len(partitions) == 4:
        target = partitions[2].split(';')[1]
        repair = int(partitions[3].split(';')[2])
        events = read(d_folder / f"eventos-p1-{target}.txt")
        before = [line for line in events if int(line.split(';')[0]) <= repair and ';CANDIDATO;' in line]
        reached_term = re.search(r'termo=(\d+)', before[-1])[1] if before else 'não determinado'
        report.append(f"O seguidor {target} atingiu o termo {reached_term} antes da reparação. As linhas TERMO/DEMISSAO seguintes nos eventos permitem acompanhar a substituição do líder saudável.")
    report += ["", "O processador só observa os lotes que chegam: não vê os IN gravados exclusivamente no líder isolado. O regresso de um líder de termo superior faz os lotes inferiores serem ignorados; isto não desfaz alarmes ou leituras que já tenham sido observados antes.", "",
               "## Falhas deliberadas", "", "As variantes seguintes foram compiladas separadamente e executadas por `testes/mutacoes.py`. São contraprovas da máquina de estados e dos handlers com fixtures explícitas, não repetições integrais dos ensaios de rede do ponto 10. O código correto não foi alterado durante a execução.", "",
               "| Falha | Manifestação observada | RaftCheck | CausalCheck | Conclusão |", "|---|---|---|---|---|",
               "| Maioria incorreta | Voto duplicado na API permitiu dois líderes no termo 1 | Detetou dois líderes | Sem erro causal | O limiar tem de ser estritamente maior que G/2; o filtro de origem própria do nó mascara este caso no tráfego normal de três membros |",
               "| Sem persistência | Reinício de n3 permitiu votar em n1 e n2 no mesmo termo | Detetou dois líderes e estado incoerente | Sem erro causal | Guardar voto só em RAM quebra a prova por interseção |",
               "| Sem upToDate | Nó com seq 1 eleito por eleitor com seq 2 do mesmo último termo | Não detetou violação neste fixture; avisou comprimentos diferentes | Sem erro causal | Um líder único e um prefixo comum não provam escolha do candidato atualizado |",
               "| Aceitar LOTE obsoleto | Seguidor do termo 2 aceitou lote do termo 1; fixture final diverge | Detetou seq divergente | Sem erro causal | Termos inferiores têm de ser rejeitados antes de aplicar dados |",
               "| Reiniciar L/V a zero | Handler real produziu seq 2 com V[k]=1 e L regressivo | Não detetou eleição incorreta | Detetou V[k]!=seq/L | Relógios têm de ser restaurados do último registo |", "",
               "A avaliação dos analisadores é específica ao que o fixture evidencia. Um retorno 0 não demonstra ausência da falha. Para a reprodução literal do ponto 10 em rede, usar os mesmos cenários da ficha com cada variante isolada; as contraprovas não são apresentadas como essas medições.", "",
               "## Validação e limites", "",
               "- Os três projetos compilam com `--release 11`; classes partilhadas iguais nos três projetos.",
               "- Testes dirigidos de voto, persistência, maioria, demissão, codecs e monitores passaram.",
               "- A recuperação com prefixo vazio tem um teste de regressão próprio. A primeira bateria revelou uma lista imutável nesse caminho; o erro foi corrigido e os ensaios a/c/d/e foram repetidos. Só as repetições corrigidas entram nas tabelas; os arranques b, que não exercitam truncagem, são conservados da primeira bateria.",
               "- As tabelas usam os ficheiros efetivamente produzidos. Não são uma prova de todas as intercalações possíveis nem de durabilidade após falha de energia.",
               "- Sem commit, Pre-Vote e reenvio/deduplicação, persistem as limitações descritas na ficha e em `ESTUDO.md`.", "",
               "## Critérios de aceitação", "", "| Critério | Evidência |", "|---|---|",
               "| CA1 | Fundamentos e aplicação à Figura 1 em ESTUDO.md; observar termos nos eventos |",
               "| CA2 | RaftState e PartitionNode; persistência antes de mensagens; monitor comum para IN/transições; restauração dos relógios |",
               "| CA3 | 21 ensaios, dois analisadores, relatórios e históricos no arquivo de evidências |",
               "| CA4 | Isolamentos reais e discussão de commit, Pre-Vote, perdas e duplicados em ESTUDO.md |", "",
               "A compreensão do estudante tem de ser confirmada durante o estudo e a apresentação. O código e os relatórios não demonstram, por si, essa compreensão. Antes da entrega, rever o trabalho, fazer commit na branch e abrir o PR conforme o Readme.md da raiz.", ""]
    point = report.index("## Falhas deliberadas")
    details = ["### Termos envolvidos e regresso dos nós", "", "| Ensaio | Termos com candidaturas/líderes em p1 |", "|---|---|"]
    for folder, r in selected:
        if not r['pasta'].startswith('9b-'):
            details.append(f"| {r['pasta']} | {terms_p1(folder)} |")
    if len(partitions) == 4:
        first = partitions[0].split(';')
        last = partitions[1].split(';')
        old_id = first[1]
        start, end = int(first[2]), int(last[2])
        count = sum(start <= int(line.split(';')[0]) <= end and ';RECEBIDO;p1;IN;' in line
                    for line in read(d_folder / f'eventos-p1-{old_id}.txt'))
        details += ["", f"Durante o isolamento de {old_id} em 9(d), esse antigo líder atribuiu {count} registos IN localmente. Esses eventos continuam auditáveis mesmo depois da truncagem."]
    e_folder = next(f for f, r in selected if r['pasta'] == '9e')
    comeback = json.loads((e_folder / 'regresso.json').read_text(encoding='utf-8'))
    details += ["", f"Em 9(e), desde o pedido de reinício de um nó até o script observar um líder decorreram {comeback['liderMs'] - comeback['reinicioMs']} ms. Este valor inclui o arranque da JVM e até um intervalo de observação do script.", ""]
    report[point:point] = details
    if live:
        point = report.index("## Validação e limites")
        extra = ["## Falhas deliberadas em rede — ponto 10", "",
                 "Além das contraprovas anteriores, as cinco variantes foram executadas em processos Java e Kubo reais. Cada variante usa o seu próprio classpath, com o código correto reposto por seleção da compilação; os analisadores mantêm-se corretos.", "",
                 "| Variante | RaftCheck (código) | CausalCheck (código) | Perdidas | Truncados |", "|---|---:|---:|---:|---:|"]
        for r in live:
            extra.append(f"| {r['pasta']} | {r['RaftCheck']} | {r['CausalCheck']} | {r.get('perdidas', 'n/d')} | {r.get('truncados', 'n/d')} |")
        extra += ["", "- Maioria incorreta: o cenário a foi repetido. No fluxo normal, o voto próprio já está no Set e a eleição só é reavaliada ao chegar um voto externo concedido; por isso este limiar errado não se manifestou como dois líderes. A contraprova da API demonstra o erro sem atribuir à rede um resultado que não ocorreu.",
                  "- Sem persistência: um eleitor foi terminado logo após o script observar um voto concedido e reiniciado durante a eleição. Os ficheiros de estado ausentes e o termo perdido no reinício foram detetados; a prova dirigida anterior demonstra a sequência que permite votar duas vezes.",
                  "- Sem restrição de atualização: o cenário a foi repetido com um seguidor isolado para ficar sem as duas leituras, uma terceira pendente no líder e um envio durante a eleição. O atrasado foi reiniciado com prazo de 1 s para ser o primeiro candidato. Um eleitor que tinha seq 2 concedeu voto ao candidato com seq 0; no fim ficou apenas a leitura enviada ao novo líder, perdendo-se as anteriores. Os analisadores reportaram as perdas, mas um único líder e históricos finais iguais não tornam esta eleição correta.",
                  "- LOTE obsoleto: o cenário d foi repetido com a validação de termo removida. O estado persistido foi retomado numa pasta nova para injetar a mensagem atrasada; `retomado.json` identifica a origem e os eventos preservam a cronologia anterior. Depois de três novas leituras, foi reenviado o primeiro heartbeat vazio do termo antigo. Um seguidor do termo novo truncou indevidamente todo o histórico, incluindo essas leituras; o heartbeat atual seguinte recuperou-o. `lote-obsoleto-aceite.json` regista o estado transitório, que uma comparação apenas final não deteta.",
                  "- L/V a zero: o cenário a foi repetido com os handlers reais. O novo líder escreveu a continuação com relógios reiniciados, e CausalCheck assinalou a violação. RaftCheck não substitui a verificação causal.", "",
                  "Os códigos não zero nestas variantes são resultados esperados da injeção deliberada de erros, separados dos 21 ensaios do código correto. As evidências de rede estão em `mutacoes-rede/` dentro do arquivo.", ""]
        report[point:point] = extra
        report = [line.replace("Para a reprodução literal do ponto 10 em rede, usar os mesmos cenários da ficha com cada variante isolada; as contraprovas não são apresentadas como essas medições.", "Os ensaios reais adicionais estão descritos na secção seguinte; estes fixtures continuam separados das medições de rede.") for line in report]
    (ROOT / "RELATORIO.md").write_text('\n'.join(report), encoding="utf-8")
    print("Relatório e evidências exportados:", dest)


if __name__ == "__main__":
    main()
