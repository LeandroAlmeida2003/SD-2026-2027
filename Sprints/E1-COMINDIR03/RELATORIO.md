# COMINDIR03 — Relógios lógicos e vetoriais

Relatório organizado a partir dos ensaios realizados e das explicações dadas pelo estudante.

## Configuração e implementação

- Java: compilação dos quatro projetos com `javac --release 11 -encoding UTF-8`.
- Kubo: 0.43.1, API local `http://127.0.0.1:5001/api/v0`.
- Três partições, com recuperação TCP nos portos 7000, 7001 e 7002.
- Réplica 1: p0 e p1; réplica 2: p1 e p2; réplica 3: p2 e p0.
- Todos os processos executados no mesmo computador.
- Arranque: réplicas, líderes, processador e produtor.

O produtor envia L=0 e um vetor de N zeros. O líder atualiza os relógios, atribui o `seq`, verifica `V[k] == seq` e grava o registo na mesma secção sincronizada. O monitor é a instância de `PartitionLeader`. Ao arrancar, recupera `seq`, L e V do último registo persistido; sem registos, inicia-os a zero.

O processador acumula os máximos de todas as leituras válidas não ignoradas, mesmo que não atinjam o limiar de alarme. Não incrementa relógios: neste modelo, os eventos são registados pelos líderes. Ignora sensores com prefixo `ALARM-` e duplicados identificados pelo par `(partição, seq)`.

A verificação/marcação de duplicados, a atualização dos relógios e a captura do carimbo usam o mesmo monitor do processador. A versão correta constrói a mensagem dentro dessa secção, com uma cópia do vetor, e publica fora dela. A partição do alarme é calculada a partir do identificador completo `ALARM-<sensor>`.

## 7(a) — Exemplo manual

| Registo | Partição | seq | L | V |
|---|---|---:|---:|---|
| S02, 29.3 | p1 | 1 | 1 | (0,1,0) |
| ALARM-S02, 29.3 | p2 | 1 | 2 | (0,1,1) |
| S01, 21.0 | p0 | 1 | 1 | (1,0,0) |
| S04, 22.5 | p0 | 2 | 2 | (2,0,0) |

Os carimbos coincidem com a Figura 1. Nesta execução, aguardou-se a criação do alarme antes de introduzir as leituras de p0.

Entre partições: 1 par causal, 4 concorrentes, 2 concorrentes com L distinto (50%) e zero violações de Lamport. O par causal é S02 → ALARM-S02: `(0,1,0) < (0,1,1)`.

## 7(b) — Rajada sem processador

Comando do produtor: `rajada 300 10`.

- Publicações do produtor: 300 tentativas, 293 aceites pela API e 7 falhas, conforme o resumo observado.
- Registos dos líderes: p0=111, p1=94, p2=88; total=293.
- Pares causais entre partições: 0.
- Pares concorrentes: 28 474.
- Concorrentes com L distinto: 28 204 (99,05%).
- Violações de Lamport: 0.

Sem o processador não são transportadas dependências entre partições. Continua a existir ordem dentro de cada partição, mas os eventos de partições diferentes são concorrentes. A coincidência das contagens de publicações aceites e registos guardados foi observada neste ensaio; não constitui uma garantia geral de entrega.

## 7(c) — Três rajadas com processador

Cada execução usou ficheiros novos e o comando `rajada 300 10`.

| Métrica | Execução 1 | Execução 2 | Execução 3 |
|---|---:|---:|---:|
| Publicações do produtor aceites / falhadas | 288 / 12 | 290 / 10 | 294 / 6 |
| Registos p0 | 133 | 125 | 131 |
| Registos p1 | 101 | 92 | 108 |
| Registos p2 | 91 | 114 | 93 |
| Leituras originais gravadas | 288 | 290 | 294 |
| Alarmes publicados, confirmados no log | 37 | 41 | 38 |
| Pares causais entre partições | 27 398 | 32 036 | 29 692 |
| Pares concorrentes | 7 329 | 4 202 | 6 683 |
| Concorrentes com L distinto | 7 092 | 3 971 | 6 412 |
| Proporção de concorrentes com L distinto | 96,7663% | 94,5026% | 95,9449% |
| Violações de Lamport | 0 | 0 | 0 |
| Falhas de publicação de REG nos logs dos líderes | 15 | 12 | 7 |
| Falhas de publicação de alarmes no log do processador | 1 | 2 | 1 |

Cada alarme transporta o conhecimento acumulado das leituras já observadas, incluindo as que não geraram alarmes. Por transitividade, os eventos seguintes na partição que recebe o alarme herdam essas dependências. Por isso, o número de pares causais pode ser muito superior ao número de alarmes: contam-se pares relacionados, não apenas ligações diretas leitura–alarme.

A terceira amostra foi preservada antes do ensaio de reinício; foi essa amostra que se usou nas médias.

## 7(d) — Reinício de p2

| Estado | seq | L | V |
|---|---:|---:|---|
| Último registo antes de parar p2 | 93 | 125 | (110,92,93) |
| Registo S03, 21.0 após reiniciar p2 | 94 | 126 | (110,92,94) |

O estado foi recuperado do ficheiro. Como a mensagem do produtor trazia L e V a zero, o máximo não reduziu os valores recuperados e apenas a componente de p2 avançou. O analisador terminou com `RESULTADO: OK`, sem violações.

## 7(e) — Escrita na réplica antes da dependência

Na réplica 1, na terceira execução:

| Registo | escrita(ms) |
|---|---:|
| p1#24, ALARM-S01 | 1793227765415 |
| p0#30, S01, leitura que originou o alarme | 1793227765449 |

O alarme foi escrito 34 ms antes da leitura. A correspondência foi confirmada com o log `ALARME: S01 p0#30 → p1`, o sensor e a temperatura dos registos.

Compararam-se os valores `escrita(ms)` da própria réplica, não o campo `instante` atribuído pelo líder. Valores iguais ao milissegundo não permitiriam concluir a ordem por essa medição.

A réplica respeita a sequência de cada partição, mas não espera pelas dependências de outras partições indicadas nos vetores. Assim, pode escrever o alarme de p1 enquanto a leitura de p0 está atrasada.

## 8 — Falhas deliberadas

As variantes foram compiladas em pastas isoladas. Os fontes corretos foram repostos após a compilação de cada variante e antes de preparar a falha seguinte.

| Falha | Manifestação observada | Detetada por CausalCheck? | Conclusão explicada pelo estudante |
|---|---|---|---|
| Omitir `vector.merge(vectorMsg)` no líder | S02 tinha V=(0,1,0), mas o seu alarme tinha V=(0,0,1). O log do processador confirmava a origem. | Não: 0 pares causais, 3 concorrentes e resultado OK. | A dependência omitida não pode ser recuperada só dos vetores incorretos; L crescente também não a prova. |
| Usar apenas `lamport = lamport + 1` | Leitura e alarme tinham L=1, apesar de os vetores mostrarem leitura → alarme. | Sim: 1 violação de Lamport. | Uma dependência causal exige desigualdade estrita de L. |
| Reiniciar com L e V a zero, mantendo seq recuperado | A proteção assinalou `V[2] != seq: 0,0,1 / 2`; o novo registo não foi escrito. | Não nos ficheiros: resultado OK. | A inconsistência foi bloqueada antes da persistência; a evidência está no log do líder. |
| Capturar referência a V sem cópia e serializar fora do monitor | Primeira tentativa: nenhum dos 40 carimbos mudou. Com pausa de teste de 50 ms fora do monitor: 33 dos 37 carimbos mudaram entre captura e envio. | Na tentativa com pausa: 58 violações de Lamport. | A mensagem pode combinar L capturado com um vetor alterado por outra thread. Não observar a corrida numa tentativa não prova segurança. |
| Produtor N=4, restantes processos N=3 | p1 rejeitou `IN;S02;29.3;0;0,0,0,0`; os três ficheiros mantiveram só os registos iniciais válidos. | Não nos ficheiros: resultado OK. | A dimensão inválida é rejeitada antes de criar um evento ou avançar os relógios. |

## 9 — Médias e custo das mensagens

Aplicou-se a média aritmética das três execuções. Para as proporções, usou-se a média das três proporções, não a divisão dos totais agregados.

| Métrica | Média das três execuções |
|---|---:|
| Registos por partição p0 / p1 / p2 | 129,67 / 100,33 / 99,33 |
| Alarmes publicados | 38,67 |
| Pares entre partições causalmente relacionados | 29 708,67 |
| Pares entre partições concorrentes | 6 071,33 |
| Concorrentes com L distinto: número médio | 5 825 |
| Concorrentes com L distinto: proporção média | 95,74% |
| Tamanho médio de REG sem carimbos | 64,35 bytes |
| Tamanho médio de REG com carimbos | 76,45 bytes |

As mensagens foram reconstruídas a partir dos registos persistidos, usando o endereço `127.0.0.1` e o porto `7000+k`, como na execução local. Contaram-se bytes UTF-8 do texto REG, antes do encapsulamento HTTP/PubSub. A versão sem carimbos retirou apenas o sufixo `;L;V`.

Para cada execução, somaram-se os bytes e dividiu-se pelo número de mensagens; depois calculou-se a média dos três tamanhos médios. Foram incluídos os registos persistidos, incluindo aqueles cuja tentativa de publicação REG consta como falhada no log.

O acréscimo médio de 12,10 bytes inclui L, V e os dois separadores `;`. Medindo apenas o texto de V, incluindo vírgulas, obtiveram-se 7,88 bytes em média; nas amostras, os vetores ocuparam entre 5 e 10 bytes.

Resultados numéricos: [resumo-7c.csv](ensaios/resumo-7c.csv), [medicoes-mensagens.csv](ensaios/medicoes-mensagens.csv) e [medicoes-vetor.csv](ensaios/medicoes-vetor.csv). Método de medição: [medir-mensagens.ps1](medir-mensagens.ps1).

## 10 — Reflexão já justificada

### Relação «aconteceu antes»

A relação define-se por três condições:

1. Ordem local: se A e B ocorrem no mesmo processo e A ocorre antes de B, então A → B.
2. Mensagens: o envio de uma mensagem acontece antes da sua receção.
3. Transitividade: se A → B e B → C, então A → C.

### Lamport e causalidade

L(A) < L(B) não prova que A precede causalmente B. Vetores incomparáveis representam eventos concorrentes, mesmo quando os valores de Lamport são distintos. A vista ordenada por `(L, partição)` inclui eventos concorrentes e não prova que uma leitura provocou um alarme.

Com relógios corretos, A → B implica L(A) < L(B). Ordenar primeiro por L coloca, portanto, A antes de B. Se dois eventos distintos têm o mesmo L, nenhum pode preceder causalmente o outro; desempatar pela partição k cria uma ordem determinística sem contrariar a causalidade.

Nestes ensaios, a proporção de pares concorrentes com L distinto diminuiu: de 99,05% sem processador para 94,50%–96,77% com processador. Isso não permite concluir que aumentar a frequência de alarmes produz sempre essa diminuição. Foram apenas três execuções, e os resultados dependem também da ordem de receção, dos atrasos e da distribuição dos eventos pelas partições. A tendência observada não constitui uma regra geral.

### Dimensão do vetor

Mantendo aproximadamente o mesmo número de dígitos por componente, o custo cresce linearmente com N: cada nova partição acrescenta um contador e uma vírgula. Ao passar de N=3 para N=4, os vetores antigos continuam com três componentes e `parse(..., 4)` rejeita-os. Seria necessário definir uma migração e interpretação compatível; essa adaptação dinâmica está fora do âmbito da ficha.

### Vetores e números de sequência

Cada novo registo incrementa uma vez o seq e a componente do próprio líder, justificando `V[k] == seq`. O conhecimento recebido sobre a própria componente refere-se a eventos já registados por esse líder.

O protocolo continua a usar seq para ordenar/aplicar registos, detetar duplicados e identificar lacunas. Na recuperação TCP, identifica os registos a pedir e reenviar. Embora V[k] contenha o mesmo valor, eliminar seq exigiria adaptar o protocolo. O vetor acrescenta conhecimento causal sobre as restantes partições.

### Entrega causal

Para escrever o alarme de p1 com V=(30,24,20), a réplica 1 teria de ter aplicado p0 até seq=30, além de respeitar a ordem local de p1. Não pode verificar localmente p2 até seq=20 porque não guarda essa partição. Esperar pelas dependências antes de escrever seria entrega causal, excluída do âmbito desta ficha.

### Perdas antes do líder

Os ficheiros dos líderes só mostram as leituras registadas. Tentativas que não originam eventos nos líderes não avançam os seus relógios. Como o produtor envia carimbos a zero, os relógios não permitem distinguir essas tentativas de leituras que nunca foram tentadas. Para conhecer o número de tentativas, consultou-se o resumo do produtor.

## Correspondência com os critérios de aceitação

| Critério | Implementação e evidência justificadas |
|---|---|
| CA1 — Fundamentos | Definição de «aconteceu antes», análise dos vetores do exemplo, distinção entre Lamport e vetores e justificação da ordem `(L,k)`. |
| CA2 — Implementação | Atualizações no [PartitionLeader](rep03-lider/src/rep03/PartitionLeader.java), acumulação no [AlarmProcessor](rep03-processador/src/rep03/AlarmProcessor.java), serialização no [SensorRecord](rep03-lider/src/rep03/SensorRecord.java) e em [Wire](rep03-lider/src/rep03/Wire.java), e recuperação no [RecordFile](rep03-lider/src/rep03/RecordFile.java). Justificação do monitor comum e da cópia do vetor. |
| CA3 — Análise | Relatórios do [CausalCheck](rep03-replica/src/rep03/CausalCheck.java), verificações de `V[k] == seq` e Lamport, proporções medidas e análise das falhas que o verificador consegue ou não detetar. |
| CA4 — Limites | Dimensão fixa do vetor, ausência de entrega causal e impossibilidade de detetar pelos relógios leituras que não originaram eventos nos líderes. |
