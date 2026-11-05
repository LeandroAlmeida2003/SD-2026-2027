# COMINDIR04 — implementação e resultados

## Ambiente e método

Ensaios executados em 30 de outubro de 2026, no Windows, com JDK 25 a compilar
para Java 11 e Kubo 0.43.1 real. Foi usado um daemon isolado em
`127.0.0.1:15001`, com todos os participantes na mesma máquina. São três
partições e três réplicas: r1 guarda p0/p1, r2 guarda p1/p2 e r3 guarda p0/p2.
As réplicas arrancaram antes dos líderes, seguidas pelo processador e produtor.

Os ficheiros brutos e os relatórios dos analisadores estão nas pastas locais
`dados/<ensaio>-<período>-<instante>`, ignoradas pelo Git. As contagens incluem
o arranque e a espera final para aplicar todos os registos. As rajadas têm
300 leituras, intervalo de 10 ms e temperaturas aleatórias; os registos incluem
também os alarmes que o processador conseguiu publicar.

Não houve sincronização externa de relógios nem substituição de líderes.
O teste de convergência comparou integralmente o histórico de cada líder com
os ficheiros das duas réplicas que guardam a sua partição.

## Falha antes da publicação do último registo

Na execução `a-5000-1793381535462`, os registos 1 e 2 foram aplicados e chegou
um lote vazio. O registo 3 foi escrito no líder e o processo foi morto antes
do lote seguinte. As duas réplicas mantiveram apenas os registos 1 e 2.

Após o reinício, o primeiro lote tinha uma nova incarnação, hb=1, n=0 e
ultimoSeq=3. Ambas registaram `REINICIO`, `RETOMA` e `RECUPERACAO` de 3 até 3.
Os históricos ficaram iguais ao do líder. Houve duas recuperações TCP e
nenhuma falsa suspeita. Os tempos desde a falha até à suspeita foram:

| Participante | Deteção (ms) |
| --- | ---: |
| Processador | 15389 |
| Produtor | 15332 |
| Réplica 1 | 15337 |
| Réplica 3 | 15335 |

Todos cabem em ]10000, 16000] ms. `HeartbeatCheck` e `CausalCheck` terminaram
com resultado OK.

## Rajada e três falhas com período de 5000 ms

Métricas da rajada sem falhas `b-5000-1793381720575`, do minuto sem leituras
`idle-5000-1793381744598` e das três falhas em `c-5000-1793381812431`:

| Métrica | Valor medido |
| --- | ---: |
| Lotes publicados p0 / p1 / p2 na rajada | 5 / 4 / 5 |
| Registos por lote, média / máximo | 24,071 / 108 |
| Lotes vazios, rajada / produtor parado | 57,143% / 100% |
| Intervalo entre publicações, média / máximo | 5430,727 / 9913 ms |
| Latência de lote, média / máximo | 2728,877 / 4970 ms |
| Deteção das três falhas, mínimo / média / máximo | 13,364 / 13,786 / 14,360 s |
| Falsas suspeitas nesses ensaios | 0 |
| Mensagens equivalentes COMINDIR03 / COMINDIR04 na rajada | 337 / 14 |
| Bytes por registo, REG equivalente / LOTE | 84,300 / 67,988 |

As latências agregam a primeira receção de cada registo em cada participante,
incluindo os cabeçalhos observados pelo produtor. Os custos contam os bytes
UTF-8 das mensagens de aplicação e incluem a sobrecarga dos lotes vazios.
A redução medida foi de 95,846% das mensagens no tópico de dados e de 19,350%
dos bytes por registo, comparando exatamente os registos transportados.

Durante a rajada, o daemon recusou uma publicação de p1 com HTTP 500 e
`validation ignored`. Daí o intervalo de 9913 ms e dois eventos `HB_PERDIDO`
nos participantes já subscritos. O líder continuou a publicar; esse lote
recusado era vazio. Não ocorreu perda de registos nem falsa suspeita.

Nas três falhas de p1, o líder foi morto durante uma rajada e reiniciado
30 segundos depois. Houve seis recuperações TCP, sem falsas suspeitas.
Os tempos de deteção por execução foram:

| Execução | Processador | Produtor | Réplica 1 | Réplica 2 |
| --- | ---: | ---: | ---: | ---: |
| 1 | 14360 ms | 14345 ms | 14270 ms | 14270 ms |
| 2 | 13691 ms | 13675 ms | 13595 ms | 13594 ms |
| 3 | 13459 ms | 13444 ms | 13364 ms | 13364 ms |

As diferenças resultam da fase do último heartbeat e da verificação local
a cada segundo. Todos os históricos de réplica convergiram e todos os
analisadores terminaram com resultado OK.

## Comparação dos períodos

Rajadas sem falhas, seguidas de uma falha de p1 com cada período. Para 5000 ms,
o tempo de deteção é a média das três execuções e dos quatro participantes.
Para os outros períodos é a média dos quatro participantes de uma execução.
As taxas publicadas abaixo são estimadas por 60000 dividido pelo intervalo
médio observado, por partição; não contam artificialmente o heartbeat inicial
como se tivesse decorrido mais um período.

| Δ | Lotes/minuto/partição, média observada | Lotes vazios na rajada | Latência média | Deteção média | Falsas suspeitas |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1000 ms | 60,453 | 45,455% | 524,447 ms | 3,239 s | 0 |
| 5000 ms | 11,048 | 57,143% | 2728,877 ms | 13,786 s | 0 |
| 10000 ms | 6,014 | 50,000% | 7657,981 ms | 29,334 s | 0 |

As pastas são `b-1000-1793381972161`, `c-1000-1793381985278`,
`b-10000-1793382023996` e `c-10000-1793382063328`, além das de 5000 ms acima.
Os limites teóricos são ]2,4] s, ]10,16] s e ]20,31] s, respetivamente,
ignorando o atraso da rede. As medições ficaram dentro desses limites.

Também se repetiu o minuto sem leituras com 1000 e 10000 ms. As pastas
`idle-1000-1793382174027` e `idle-10000-1793382238865` contêm, respetivamente,
63 e 8 lotes por partição, todos vazios. Com 5000 ms foram 14 por partição.
As contagens incluem o arranque e a preparação do ensaio, além do minuto
de espera. Não houve falsas suspeitas nestes três ensaios.

A latência média de 1000 ms aproxima-se de Δ/2. Para 10000 ms, a rajada ocupa
apenas uma parte do período e começa pouco depois de um heartbeat; as chegadas
não se distribuem uniformemente no intervalo. Assim, 7658 ms não contradizem
a estimativa Δ/2, que pressupõe chegadas uniformes.

## Suspensão e retoma

Em `d-5000-1793382135098`, o helper suspendeu o líder de p2 durante 20 s e
retomou o mesmo processo. Os quatro participantes que acompanham p2
registaram falsa suspeita e `RETOMA` na mesma incarnação. Não houve `REINICIO`.

O intervalo entre as publicações hb=2 e hb=3 foi de 25033 ms. A pausa não
coincide necessariamente com a última publicação; o intervalo observado
também inclui a espera até a thread voltar a executar. Em seguida, os
intervalos entre hb=3 e hb=7 foram 12, 11, 11 e 13 ms: as execuções atrasadas
de `scheduleAtFixedRate` correram em sucessão, sem sobreposição.

A leitura `S03 22.0`, enviada durante a suspensão, foi recebida pelo líder
depois da retoma, ganhou seq=1 e chegou às duas réplicas. Nesta execução,
a ligação de subscrição manteve a leitura em espera. Isto não garante retenção
ou reenvio pelo produtor: com o processo morto ou o canal perdido, essa
leitura pode desaparecer antes de lhe ser atribuído um número de sequência.

Os analisadores deram OK. A exatidão do detetor foi violada durante a pausa,
pois um processo que não tinha falhado foi considerado suspeito.

## Cinco falhas introduzidas deliberadamente

Cada variante foi compilada numa pasta temporária independente, com período
de 1000 ms. A implementação entregue manteve o código correto durante estes
ensaios. A variante de concorrência incluiu uma pausa de 150 ms entre a troca
da lista e a leitura de `ultimoSeq`, para tornar observável a condição de
corrida. Esta pausa só existe na cópia de ensaio.

| Falha introduzida | Onde se manifestou | HeartbeatCheck / CausalCheck | Resultado observado |
| --- | --- | --- | --- |
| Publicar só com pendentes | Participantes sem heartbeat nas partições inativas | Erro / OK | Faltaram hb-p1 e hb-p2; surgiram suspeitas com os líderes vivos. Um sinal de vida tem de existir mesmo sem dados. |
| Incrementar L e V[k] em cada heartbeat | Líder, ao receber a primeira leitura | OK / OK | A verificação em execução rejeitou V[0]=4 para seq=1 e terminou a entrada, antes de gravar um registo inválido. Os analisadores não conseguem descobrir nos ficheiros um erro que a guarda impediu de persistir. |
| Ler ultimoSeq fora do monitor | Cabeçalhos observados e recuperação das réplicas | 45 erros / OK | O último seq do lote não coincidia com ultimoSeq; ocorreram 30 pedidos de recuperação. Os relógios persistidos continuaram válidos, mas o snapshot deixou de ser coerente. |
| ultimoSeq=0 e descartar cada décimo lote | Réplicas com lacunas sem recuperação | 104 erros / OK | 36 eventos HB_PERDIDO e zero recuperações; p0 tinha 249 registos no líder e apenas 24 em cada réplica. Os relógios corretos do líder não garantem que uma réplica recebeu o histórico. |
| Fator de timeout igual a 1 | Monitores das réplicas no arranque | OK / OK | Seis suspeitas antes do primeiro heartbeat, classificadas como inconclusivas; depois da primeira receção não se observaram falsas suspeitas nesta execução. A margem menor não implica que todas as execuções produzam falsas suspeitas. |

As pastas são `fault-empty-1000-1793382311769`,
`fault-clock-1000-1793382324148`, `fault-race-1000-1793382337080`,
`fault-tail-1000-1793382353045` e `fault-factor-1000-1793382381374`.

Na variante sem heartbeats vazios, a classificação mecânica pode indicar
FALHA_REAL quando não existe nova receção na mesma incarnação. O ensaio mostra
que isso não prova a morte física do líder: o código defeituoso apenas deixou
de emitir. Conhecer a falha provocada e fornecer `falhas.txt` é indispensável
para interpretar corretamente os resultados. Não foram inventados instantes
de falha para estes líderes que continuavam vivos.

## Verificação da implementação entregue

- Os quatro projetos compilaram com `--release 11`.
- Os onze cenários normais documentados passaram nos dois analisadores,
  incluindo a comparação integral dos históricos das réplicas.
- A verificação isolada de `Batch` e `HeartbeatMonitor` confirmou lotes vazios,
  contagem e dimensão inválidas, leitura exclusiva do cabeçalho pelo produtor,
  saltos, duplicados, incarnações antigas, uma única suspeita por transição,
  retoma e reinício.
- `HeartbeatCheck` aceitou também um `falhas.txt` UTF-8 com BOM, como o que
  pode ser produzido pelo PowerShell no Windows.
- As nove classes comuns são idênticas nos quatro projetos; os scripts
  PowerShell passaram na análise sintática.

## Fundamentação e limites

Um detetor de falhas fornece suspeitas locais. Completude significa que um
líder que falha acaba por ser suspeito; exatidão significa não suspeitar de
líderes corretos. Num sistema assíncrono, uma pausa do líder ou do daemon IPFS
pode ser indistinguível de uma falha. Um timeout não constitui prova de morte.

Se a última receção ocorreu em t_hb, o teste de suspeita ocorre depois de
t_hb + TIMEOUT e até mais um período de verificação depois. Como a falha pode
ocorrer até Δ depois do último heartbeat, a demora desde a falha pertence a
]TIMEOUT − Δ, TIMEOUT + VERIFICACAO_MS], sob as hipóteses do enunciado.

Com fator 1, a margem para atrasos diminui e as falsas suspeitas tornam-se
mais prováveis. Com fator 10 e Δ=5 s, a deteção passa para ]45,51] s, mas
atrasos moderados causam menos suspeitas. Nenhum timeout fixo garante
exatidão num sistema totalmente assíncrono.

O `ultimoSeq` permite descobrir uma perda no fim do histórico, mesmo que
nunca chegue outro registo. O mecanismo antigo de lacunas só descobria a
falta quando observava um seq posterior. Basta um lote vazio anunciar que
o líder já tem um seq maior para desencadear a recuperação TCP.

O snapshot da lista e de `ultimoSeq` tem de usar o mesmo monitor que a
atribuição de sequência. Caso contrário, o cabeçalho pode anunciar registos
que ficaram na lista seguinte. Publicar fora do monitor evita que a espera
pelo daemon bloqueie a numeração e o append das novas leituras.

Os heartbeats transportam informação sobre o estado já existente; não
representam novas leituras nem novos eventos na cadeia causal definida
pela ficha. Incrementar V[k] num heartbeat quebraria a igualdade V[k]=seq.
Os carimbos dos alarmes mantêm as regras da COMINDIR03. Duas esperas de lote
podem acrescentar até 2Δ ao percurso leitura → alarme → réplica; o tempo
total inclui ainda publicações, execução e rede, sem limite num sistema
assíncrono. `scheduleWithFixedDelay` acrescentaria o tempo de cada publicação
ao intervalo, em vez de manter a grelha temporal de `scheduleAtFixedRate`.

O envio em lote é pouco adequado a alarmes que exijam resposta imediata.
Uma arquitetura diferente poderia enviar os dados imediatamente e reservar
uma mensagem periódica para o heartbeat, desacoplando os dois intervalos.
Essa alternativa não foi introduzida nesta implementação, que segue a ficha.

Detetar uma falha não substitui o líder. As leituras enviadas enquanto o líder
está morto podem perder-se sem seq, pelo que a recuperação TCP não as conhece.
Evitar essa perda exigiria retenção, confirmação e reenvio. Substituir o líder
com segurança exigiria coordenação e impedir que dois líderes atribuíssem
sequências concorrentes. Esses mecanismos estão fora do âmbito da ficha.

As réplicas não suspeitam simultaneamente: têm receções, escalonamento e fases
de verificação diferentes. `System.nanoTime()` mede intervalos locais e evita
que ajustes do relógio civil alterem o timeout. Já a análise offline subtrai
instantes de processos diferentes e depende de estes correrem na mesma
máquina. Em máquinas distintas, relógios físicos desalinhados poderiam
falsear latências e tempos de deteção, embora o detetor local continuasse
a funcionar com o seu próprio relógio monotónico.
