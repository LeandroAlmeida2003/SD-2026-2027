# COMINDIR04 — heartbeat, lotes e deteção de falhas

Implementação Java 11 sem dependências externas, adaptada da COMINDIR03 da
branch `origin/E1-COMINDIR03`. A especificação é a
[ficha do docente](https://github.com/cunhaestgv/SD-2026-2027/issues/17).

## Compilar e executar

É necessário um JDK 11 ou superior e Kubo com PubSub ativo. O comando `javac`
deve apontar para o JDK. O script de execução usa o `java.exe` desse mesmo JDK,
evitando executar por engano com o Java 8 que também pode estar no PATH.

```powershell
cd Sprints/E1-COMINDIR04
./compilar.ps1
```

Com o daemon IPFS a escutar em `127.0.0.1:5001`, executar cada comando seguinte
num terminal distinto, pela ordem indicada. Todos usam `dados/manual` como
pasta de trabalho, incluindo os analisadores. Usar uma pasta nova por ensaio;
os processos preservam os ficheiros existentes para permitir reiniciar líderes.

```powershell
./executar.ps1 -Projeto replica -Argumentos @('1','0,1')
./executar.ps1 -Projeto replica -Argumentos @('2','1,2')
./executar.ps1 -Projeto replica -Argumentos @('3','0,2')

./executar.ps1 -Projeto lider -Argumentos @('0','3')
./executar.ps1 -Projeto lider -Argumentos @('1','3')
./executar.ps1 -Projeto lider -Argumentos @('2','3')

./executar.ps1 -Projeto processador -Argumentos @('3')
./executar.ps1 -Projeto produtor -Argumentos @('3')
```

Comandos do produtor: `S01 21.0`, `rajada 300 10` e `sair`. O segundo argumento
da rajada é o intervalo em **milissegundos**, entre tentativas consecutivas,
e são usados dez sensores. A aceitação da publicação pelo daemon não confirma
a receção pelo líder. Temperaturas a partir de 28 graus originam alarmes.

Para outra API, os líderes recebem `k N endereço api`, o processador e o produtor
recebem `N api`, e as réplicas recebem `id partições portoApi`. Por exemplo:

```powershell
./executar.ps1 -Projeto lider -Argumentos @('0','3','127.0.0.1','http://127.0.0.1:15001/api/v0')
./executar.ps1 -Projeto replica -Argumentos @('1','0,1','15001')
```

Para variar os temporizadores, passar `-Periodo 1000` ou `-Periodo 10000` a
**todos** os processos. `-Fator 3` é o valor normal; `-Fator 1` permite estudar
falsas suspeitas. Os equivalentes Java são `-Drep04.periodo=5000` e
`-Drep04.fator=3`. A verificação ocorre a cada 1000 ms.

Depois de terminar um ensaio, executar:

```powershell
./executar.ps1 -Projeto heartbeat -Argumentos @('3','.')
./executar.ps1 -Projeto causal -Argumentos @('3','.')
./executar.ps1 -Projeto heartbeat -Argumentos @('3','.','falhas.txt')
```

`HeartbeatCheck` escreve também `relatorio-heartbeat.txt`; `CausalCheck` escreve
`vista-lamport.txt`. Ambos devolvem código diferente de zero se os ficheiros
estiverem ausentes ou mal formados; `CausalCheck` também assinala violações dos
relógios. Uma partição sem leituras tem um histórico vazio válido.

## Organização e garantias

- `rep04-lider`: `PartitionLeader` mantém a numeração, os relógios e o histórico.
  O registo entra em `pendentes` na mesma secção sincronizada que atribui `seq`
  e faz `append`. `enviarLote()` captura a lista, `ultimoSeq` e `hb` sob o mesmo
  monitor e publica fora dele. A publicação lenta não bloqueia a entrada de leituras.
- `rep04-replica`: `PartitionReplica` tem uma instância por partição guardada.
  Aplica registos contíguos, ignora duplicados e guarda os restantes em espera.
  Se ainda faltar algum registo até `ultimoSeq`, pede-o por TCP, mesmo num lote
  vazio. Uma falha TCP é registada e o próximo lote volta a tentar.
- `rep04-processador`: `AlarmProcessor` conserva as regras causais da COMINDIR03.
  Processa cada registo do lote, evita alarmes duplicados e ciclos de alarmes,
  e publica mesmo quando o líder de destino está suspeito, emitindo um aviso.
- `rep04-produtor`: `Producer` interpreta apenas o cabeçalho dos lotes. Avisa
  quando um destino está suspeito e envia a leitura na mesma, sem a reter.
- Os quatro projetos incluem `Batch`, `HeartbeatMonitor` e `MonitorLog`.
  A receção e a verificação do monitor são sincronizadas. A thread de verificação
  é independente das threads de subscrição e da entrada interativa do produtor.

O líder emite um lote no arranque e depois com `scheduleAtFixedRate`. Se uma
publicação falhar, não repõe os registos na lista: já estão no histórico e são
recuperáveis por TCP. Os heartbeats não incrementam L nem V. No reinício,
L, V e a sequência são restaurados do último registo; `inc` muda e `hb` recomeça
em 1. Mensagens antigas não renovam o temporizador, mas os seus registos ainda
passam pela regra de aplicação e deduplicação da réplica.

## Ficheiros e medições

O protocolo conserva `IN`, `PEDIDO <de> <até>`, `FIM` e as seis colunas dos
registos da COMINDIR03. Os tópicos passam a `rep04-in-p<k>` e `rep04-p<k>`.
O lote tem o cabeçalho `LOTE;endereço;porto;inc;hb;ultimoSeq;n`, seguido de
exatamente n linhas de registo. `Batch.decode` valida esta contagem e a dimensão
dos vetores. O produtor valida apenas o cabeçalho.

- `lider-p<k>.txt`: histórico do líder, preservado nos reinícios.
- `replica-<r>-p<k>.txt`: histórico aplicado por cada réplica.
- `hb-p<k>.txt`: `inc;hb;instante;ultimoSeq;n;bytes`, apenas publicações aceites
  pelo daemon; bytes são os bytes UTF-8 da mensagem de aplicação.
- `monitor-<id>.txt`: `instante;evento;p<k>;detalhe`, com receções, suspeitas,
  retomas, reinícios, saltos de heartbeat e pedidos de recuperação.
- `falhas.txt`: `p<k>;instante`, registado imediatamente antes de matar o líder.

Os instantes dos registos mantêm o formato ISO local, agora com precisão
subsegundo, para não introduzir um erro de até um segundo na latência medida.
`HeartbeatCheck` também aceita instantes ISO com zona UTC. O detetor usa
exclusivamente diferenças locais de `System.nanoTime()`.

O relatório calcula tamanhos e intervalos por partição/incarnação, latências
da primeira receção de cada registo em cada participante e o agregado dessas
receções. Registos recuperados exclusivamente por TCP não são apresentados
como receções de lote. Uma `RETOMA` na mesma incarnação identifica uma falsa
suspeita; uma suspeita sem heartbeat inicial fica inconclusiva. A classificação
de falha real segue a janela observada, pelo que é preciso deixar o ensaio correr
até à retoma/reinício e fornecer `falhas.txt` para medir tempos de deteção reais.

O custo médio de LOTE divide todos os bytes publicados, incluindo lotes vazios,
pelo número de registos transportados. O custo de REG equivalente reconstrói os
mesmos registos e o tamanho do endereço/porto a partir dos bytes do cabeçalho.
Estes custos excluem HTTP, multipart e o protocolo de transporte do IPFS.

## Ensaios da ficha

Executar todos na mesma máquina e conservar uma pasta distinta por execução.
Em cada ensaio, terminar com os dois analisadores. Os resultados efetivamente
obtidos nesta máquina estão em `RELATORIO.md`; os dados locais ficam em `dados`.

1. Figura 1: enviar `S01 21.0` e `S04 22.5`; esperar a aplicação e um lote vazio.
   Enviar `S01 21.4`, confirmar o append e matar o líder de p0 em menos de 1 s.
   Esperar a suspeita e reiniciar o mesmo líder na mesma pasta. Verificar
   `REINICIO`, `RETOMA` e `RECUPERACAO` nas duas réplicas que guardam p0.
2. Sem falhas: executar `rajada 300 10`. Noutro ensaio, manter o produtor parado
   durante um minuto e comparar os lotes vazios, os intervalos e a latência.
3. Durante a rajada, matar o líder de p1, esperar 30 s e reiniciá-lo. Repetir três
   vezes e comparar a deteção nos participantes que acompanham p1.
4. Suspender o líder de p2 durante 20 s, introduzir leituras para p2 e retomar
   o processo. No Windows, usar o helper abaixo com o PID do líder desse ensaio.
5. Repetir a rajada e uma falha de p1 com períodos de 1000 e 10000 ms, sempre
   mudando o período em todos os processos.

Para registar uma falha no Windows, identificar o PID do líder do ensaio e usar:

```powershell
$leaderProcessId = 12345
$instant = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Add-Content -LiteralPath './dados/manual/falhas.txt' -Value "p1;$instant" -Encoding UTF8
Stop-Process -Id $leaderProcessId -Force
```

Para suspender e retomar, sem criar uma nova incarnação:

```powershell
./suspender-processo.ps1 -ProcessId 12345 -Action Suspend
./suspender-processo.ps1 -ProcessId 12345 -Action Resume
```

Para uma pausa de 20 s com retoma automática, usar
`./suspender-processo.ps1 -ProcessId 12345 -Action Suspend -RetomarAposSegundos 20`.

Após a suspensão, `scheduleAtFixedRate` executa as invocações atrasadas em
sucessão, sem sobreposição numa única thread. Já `scheduleWithFixedDelay`
esperaria o intervalo inteiro depois de cada publicação acabar.

As cinco alterações deliberadamente incorretas do ponto 9 devem ser feitas
uma de cada vez numa cópia de ensaio, compilando e repondo o código correto
entre execuções. Os seus efeitos e os resultados observados constam do relatório.
