# COMINDIR05 — relatório de execução

Resultados obtidos nesta máquina com JDK 21 (compilação `--release 11`) e Kubo 0.43.1, usando os temporizadores da ficha.

Os 21 ensaios selecionados terminaram com `RaftCheck` e `CausalCheck` a devolver 0. Os históricos finais dos três nós coincidem, incluindo os comprimentos, nos ensaios de recuperação.

As evidências estão em [resultados/evidencias.zip](resultados/evidencias.zip) e o resumo estruturado em [resultados/resumo.json](resultados/resumo.json). O arquivo contém ficheiros de aplicação; exclui o repositório privado de Kubo e as suas chaves.

## Métricas pedidas

| Métrica | Valor medido |
|---|---|
| Primeiro líder, mínimo / média / máximo (s), ensaios a–c com prazo aleatório | 6.289 / 7.676 / 10.785 |
| Termos até ao primeiro líder, média aleatório / fixo | 1.000 / 1.267 |
| Votos divididos nos dez arranques aleatórios / cinco fixos (três partições por arranque) | 0 / 4 |
| Indisponibilidade de uma falha do líder, mínimo / média / máximo (s), a e três c | 4.902 / 7.082 / 10.419 |
| Termos com mais de um líder, todos os ensaios | 0 |
| Termos decrescentes | 0 |
| Seq divergentes no prefixo final comum | 0 |

Os arranques aleatórios e fixos têm 30 e 15 observações de partição, respetivamente. Os processos são lançados pelo mesmo script em rápida sucessão; o sistema operativo não garante arranque simultâneo exato. Um prazo fixo não obriga a haver voto dividido em todas as execuções.

| Ensaio | Indisponibilidade (ms) | Leituras perdidas | Registos truncados | Reapareceram com outro seq |
|---|---:|---:|---:|---:|
| 9a | [10419] | 2 | 1 | 0 |
| 9c-1 | [5467] | 83 | 6 | 0 |
| 9c-2 | [4902] | 92 | 3 | 0 |
| 9c-3 | [7538] | 96 | 6 | 0 |
| 9d | não aplicável | 89 | 105 | 0 |
| 9e | [35698] | 30 | 0 | 0 |

9(e) inclui 30 s de ausência deliberada de maioria; a sua indisponibilidade não é comparável à janela teórica de uma falha única.

### Leituras perdidas por falha

| Ensaio | Perdidas enviadas durante a eleição | Perdidas fora desse intervalo |
|---|---:|---:|
| 9a | 1 | 1 |
| 9c-1 | 78 | 5 |
| 9c-2 | 89 | 3 |
| 9c-3 | 90 | 6 |

Perdas fora do intervalo devem ser confrontadas com a auditoria de truncagem. Os ficheiros truncados incluem também alarmes, enquanto `produtor.txt` inclui apenas as leituras do produtor. Não se devem igualar automaticamente estes dois totais.

As três indisponibilidades de 9(c) podem ser comparadas com ]4 s;12,2 s], sob as hipóteses de rede rápida e eleição sem colisão. `rajada 300 10` pode terminar antes da eleição: perder mais tempo de serviço não implica perder leituras a uma taxa constante depois de acabar a rajada.

## CAP e isolamento

| Cenário | Maioria aceita? | Minoria aceita? | Após reparação | Interpretação |
|---|---|---|---|---|
| Falha do líder, 9(c) | Após eleição | Líder parado não recebe | Antigo líder segue e trunca cauda divergente | Eleição exige maioria; indisponibilidade transitória |
| Líder isolado, 9(d) | Após eleição | Antigo líder aceita IN localmente | Escritas divergentes são descartadas | Não é CP completo: faltam confirmações de commit |
| Seguidor isolado, 9(d) | Líder saudável continua | Seguidor não aceita como líder | Termo elevado pode demitir líder saudável | Falta Pre-Vote; perturbação no regresso |
| Duas falhas, 9(e) | Não há maioria | Sobrevivente era seguidor e não se elege | Eleição volta quando regressa um nó | Disponibilidade de escrita sacrificada por falta de quórum |

Snapshot dos papéis durante o isolamento (termo só é mostrado para líderes):

```json
{
  "1": [
    "NOVO_LIDER",
    0
  ],
  "2": [
    "LIDER",
    2
  ],
  "3": [
    "LIDER",
    1
  ]
}
```

O seguidor n1 atingiu o termo 8 antes da reparação. As linhas TERMO/DEMISSAO seguintes nos eventos permitem acompanhar a substituição do líder saudável.

O processador só observa os lotes que chegam: não vê os IN gravados exclusivamente no líder isolado. O regresso de um líder de termo superior faz os lotes inferiores serem ignorados; isto não desfaz alarmes ou leituras que já tenham sido observados antes.

### Termos envolvidos e regresso dos nós

| Ensaio | Termos com candidaturas/líderes em p1 |
|---|---|
| 9a | [1, 2] |
| 9c-1 | [1, 2] |
| 9c-2 | [1, 2] |
| 9c-3 | [1, 2] |
| 9d | [1, 2, 3, 4, 5, 6, 7, 8, 9] |
| 9e | [1, 2, 3, 4, 5] |

Durante o isolamento de n3 em 9(d), esse antigo líder atribuiu 102 registos IN localmente. Esses eventos continuam auditáveis mesmo depois da truncagem.

Em 9(e), desde o pedido de reinício de um nó até o script observar um líder decorreram 5687 ms. Este valor inclui o arranque da JVM e até um intervalo de observação do script.

## Falhas deliberadas

As variantes seguintes foram compiladas separadamente e executadas por `testes/mutacoes.py`. São contraprovas da máquina de estados e dos handlers com fixtures explícitas, não repetições integrais dos ensaios de rede do ponto 10. O código correto não foi alterado durante a execução.

| Falha | Manifestação observada | RaftCheck | CausalCheck | Conclusão |
|---|---|---|---|---|
| Maioria incorreta | Voto duplicado na API permitiu dois líderes no termo 1 | Detetou dois líderes | Sem erro causal | O limiar tem de ser estritamente maior que G/2; o filtro de origem própria do nó mascara este caso no tráfego normal de três membros |
| Sem persistência | Reinício de n3 permitiu votar em n1 e n2 no mesmo termo | Detetou dois líderes e estado incoerente | Sem erro causal | Guardar voto só em RAM quebra a prova por interseção |
| Sem upToDate | Nó com seq 1 eleito por eleitor com seq 2 do mesmo último termo | Não detetou violação neste fixture; avisou comprimentos diferentes | Sem erro causal | Um líder único e um prefixo comum não provam escolha do candidato atualizado |
| Aceitar LOTE obsoleto | Seguidor do termo 2 aceitou lote do termo 1; fixture final diverge | Detetou seq divergente | Sem erro causal | Termos inferiores têm de ser rejeitados antes de aplicar dados |
| Reiniciar L/V a zero | Handler real produziu seq 2 com V[k]=1 e L regressivo | Não detetou eleição incorreta | Detetou V[k]!=seq/L | Relógios têm de ser restaurados do último registo |

A avaliação dos analisadores é específica ao que o fixture evidencia. Um retorno 0 não demonstra ausência da falha. Os ensaios reais adicionais estão descritos na secção seguinte; estes fixtures continuam separados das medições de rede.

## Falhas deliberadas em rede — ponto 10

Além das contraprovas anteriores, as cinco variantes foram executadas em processos Java e Kubo reais. Cada variante usa o seu próprio classpath, com o código correto reposto por seleção da compilação; os analisadores mantêm-se corretos.

| Variante | RaftCheck (código) | CausalCheck (código) | Perdidas | Truncados |
|---|---:|---:|---:|---:|
| maioria | 0 | 0 | 2 | 1 |
| persistencia | 1 | 0 | 0 | 0 |
| atualizacao | 0 | 0 | 4 | 5 |
| obsoleto | 0 | 0 | 91 | 224 |
| relogios | 0 | 1 | 2 | 1 |

- Maioria incorreta: o cenário a foi repetido. No fluxo normal, o voto próprio já está no Set e a eleição só é reavaliada ao chegar um voto externo concedido; por isso este limiar errado não se manifestou como dois líderes. A contraprova da API demonstra o erro sem atribuir à rede um resultado que não ocorreu.
- Sem persistência: um eleitor foi terminado logo após o script observar um voto concedido e reiniciado durante a eleição. Os ficheiros de estado ausentes e o termo perdido no reinício foram detetados; a prova dirigida anterior demonstra a sequência que permite votar duas vezes.
- Sem restrição de atualização: o cenário a foi repetido com um seguidor isolado para ficar sem as duas leituras, uma terceira pendente no líder e um envio durante a eleição. O atrasado foi reiniciado com prazo de 1 s para ser o primeiro candidato. Um eleitor que tinha seq 2 concedeu voto ao candidato com seq 0; no fim ficou apenas a leitura enviada ao novo líder, perdendo-se as anteriores. Os analisadores reportaram as perdas, mas um único líder e históricos finais iguais não tornam esta eleição correta.
- LOTE obsoleto: o cenário d foi repetido com a validação de termo removida. O estado persistido foi retomado numa pasta nova para injetar a mensagem atrasada; `retomado.json` identifica a origem e os eventos preservam a cronologia anterior. Depois de três novas leituras, foi reenviado o primeiro heartbeat vazio do termo antigo. Um seguidor do termo novo truncou indevidamente todo o histórico, incluindo essas leituras; o heartbeat atual seguinte recuperou-o. `lote-obsoleto-aceite.json` regista o estado transitório, que uma comparação apenas final não deteta.
- L/V a zero: o cenário a foi repetido com os handlers reais. O novo líder escreveu a continuação com relógios reiniciados, e CausalCheck assinalou a violação. RaftCheck não substitui a verificação causal.

Os códigos não zero nestas variantes são resultados esperados da injeção deliberada de erros, separados dos 21 ensaios do código correto. As evidências de rede estão em `mutacoes-rede/` dentro do arquivo.

## Validação e limites

- Os três projetos compilam com `--release 11`; classes partilhadas iguais nos três projetos.
- Testes dirigidos de voto, persistência, maioria, demissão, codecs e monitores passaram.
- A recuperação com prefixo vazio tem um teste de regressão próprio. A primeira bateria revelou uma lista imutável nesse caminho; o erro foi corrigido e os ensaios a/c/d/e foram repetidos. Só as repetições corrigidas entram nas tabelas; os arranques b, que não exercitam truncagem, são conservados da primeira bateria.
- As tabelas usam os ficheiros efetivamente produzidos. Não são uma prova de todas as intercalações possíveis nem de durabilidade após falha de energia.
- Sem commit, Pre-Vote e reenvio/deduplicação, persistem as limitações descritas na ficha e em `ESTUDO.md`.

## Critérios de aceitação

| Critério | Evidência |
|---|---|
| CA1 | Fundamentos e aplicação à Figura 1 em ESTUDO.md; observar termos nos eventos |
| CA2 | RaftState e PartitionNode; persistência antes de mensagens; monitor comum para IN/transições; restauração dos relógios |
| CA3 | 21 ensaios, dois analisadores, relatórios e históricos no arquivo de evidências |
| CA4 | Isolamentos reais e discussão de commit, Pre-Vote, perdas e duplicados em ESTUDO.md |

A compreensão do estudante tem de ser confirmada durante o estudo e a apresentação. O código e os relatórios não demonstram, por si, essa compreensão. Antes da entrega, rever o trabalho, fazer commit na branch e abrir o PR conforme o Readme.md da raiz.
