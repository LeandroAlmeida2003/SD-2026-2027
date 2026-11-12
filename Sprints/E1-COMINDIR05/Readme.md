# COMINDIR05 — eleição do líder de partição

Implementação Java 11+, sem bibliotecas externas, baseada na COMINDIR04.
A [ficha do docente](https://github.com/cunhaestgv/SD-2026-2027/issues/18)
define os requisitos do exercício. A especificação mantém os
nomes `raft04` apesar de esta ser a ficha COMINDIR05.

## Compilar

Com JDK 11+ instalado:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./compilar.ps1
```

`Bypass` aplica-se apenas ao processo lançado. Os scripts não alteram a
política de execução do Windows. `executar.ps1` seleciona o Java do mesmo JDK
que fornece `javac`, evitando uma instalação Java 8 que esteja no PATH.

## Executar manualmente

Iniciar Kubo com PubSub ativo (`ipfs daemon --enable-pubsub-experiment`).
Executar cada comando num terminal separado, a partir desta pasta. Todos os
processos do mesmo ensaio devem usar a mesma pasta de dados na mesma máquina.

```powershell
./executar.ps1 -Projeto no -Argumentos @('0','1','3')
./executar.ps1 -Projeto no -Argumentos @('0','2','3')
./executar.ps1 -Projeto no -Argumentos @('0','3','3')
./executar.ps1 -Projeto no -Argumentos @('1','1','3')
./executar.ps1 -Projeto no -Argumentos @('1','2','3')
./executar.ps1 -Projeto no -Argumentos @('1','3','3')
./executar.ps1 -Projeto no -Argumentos @('2','1','3')
./executar.ps1 -Projeto no -Argumentos @('2','2','3')
./executar.ps1 -Projeto no -Argumentos @('2','3','3')
./executar.ps1 -Projeto processador -Argumentos @('3')
./executar.ps1 -Projeto produtor -Argumentos @('3')
```

Se os scripts estiverem bloqueados, abrir primeiro uma sessão com
`powershell -NoProfile -ExecutionPolicy Bypass` e executar nela os comandos.
Os nove nós podem arrancar por qualquer ordem. O produtor aceita
`S02 20.1`, `rajada 300 10` e `sair`; o intervalo da rajada está em milissegundos.
O produtor não conserva nem retransmite leituras durante uma eleição.

Os valores normais são Δ=2000 ms, eleição em [6000,12000] ms e verificação
a cada 200 ms. Para prazo fixo, usar `-EleicaoMin 6000 -EleicaoMax 6000` em
todos os nós. `-Periodo`, `-Fator`, `-Pasta` e `-Api` permitem configurar os
restantes parâmetros. O porto TCP é `7100 + 3*k + i - 1`.

## Falhas e partições de rede

Identificar o PID do nó que é atualmente líder nos seus eventos, registar o
instante imediatamente antes da falha e terminar apenas esse processo:

```powershell
$nodeProcessId = 12345 # substituir pelo PID do nó do ensaio
$instant = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Add-Content ./dados/manual/falhas.txt "p1;n2;$instant" -Encoding UTF8
Stop-Process -Id $nodeProcessId -Force
```

Para isolar n2 de p1, criar `dados/manual/isolar-p1-n2`; para reparar a rede,
apagar esse ficheiro. O nó continua a receber `IN`, mas não envia nem aplica
LOTE/votos e não participa em recuperação TCP. O seu relógio eleitoral continua
a funcionar. Reiniciar sempre com a mesma pasta para recuperar termo, voto e
histórico. Usar uma pasta nova por ensaio evita misturar medições de ensaios.

## Analisar

Depois de deixar a recuperação terminar e parar os processos:

```powershell
./executar.ps1 -Projeto raft -Argumentos @('3','.')
./executar.ps1 -Projeto raft -Argumentos @('3','.','falhas.txt')
./executar.ps1 -Projeto causal -Argumentos @('3','.')
```

`RaftCheck` produz `relatorio-raft.txt` e `resumo-raft.json`.
`CausalCheck` verifica todas as cópias, produz `relatorio-causal.txt` e uma
`vista-lamport.txt` ordenada por (L,k,termo). Ambos devolvem código diferente
de zero para erros estruturais/invariantes detetados. Perdas e truncagens são
resultados esperados em certos ensaios e não tornam, por si, o relatório inválido.
Prefixos iguais com comprimentos diferentes geram aviso: não provam recuperação
completa. Os analisadores trabalham sobre uma fotografia estável dos ficheiros.

Os registos de `produtor.txt` são comparados por sensor/temperatura e
multiplicidade, pois o protocolo não inclui ID único nem o instante do produtor
na mensagem IN. Não se pode determinar com certeza qual de duas leituras
idênticas se perdeu. Aceitação pela API de Kubo não significa entrega ao líder.

Além dos ficheiros da ficha, `truncados-p<k>-n<i>.txt` conserva
`instante;linha_original`. É uma auditoria local que permite procurar o conteúdo
descartado com outro seq; não é retransmitida nem usada para recuperar dados.
O primeiro evento `TERMO;...;arranque` regista o início de cada nó e permite
medir o primeiro líder. Um termo sem líder pode resultar de voto dividido
ou da ausência de maioria; os relatórios mostram candidatos e votos para
distinguir os dois casos.

## Testes reproduzíveis

Com Python 3 e Kubo instalado, o runner cria um repositório IPFS privado e
pastas novas, usa as portas locais 15051/15052/15080 e termina apenas os
processos que lançou. Não usa nem altera o repositório IPFS pessoal.

```powershell
javac --release 11 -encoding UTF-8 -cp build/no -d build/no testes/RaftStateTest.java
java -cp build/no raft04.RaftStateTest
python testes/ensaios.py --ipfs 'C:/caminho/para/ipfs.exe' --suite smoke
python testes/ensaios.py --ipfs 'C:/caminho/para/ipfs.exe' --suite completa
python testes/mutacoes.py
```

O equivalente agregado é `./testar.ps1`; acrescentar `-Ipfs 'C:/.../ipfs.exe'`
para o ensaio de rede e `-Completa` para toda a bateria.

`smoke` executa 9(a). `completa` executa 9(a), dez arranques aleatórios,
cinco arranques com prazo fixo, três falhas de líder com reinício após 30 s,
isolamento do líder por 30 s, isolamento de seguidor por 60 s e falha de dois
nós por 30 s. Cada ensaio termina com os dois analisadores. A execução completa
demora vários minutos devido às esperas exigidas pela ficha.

`mutacoes.py` compila cinco variantes em pastas separadas, executa contraprovas
dirigidas e usa os analisadores corretos. São testes da máquina de estados e
dos handlers, com fixtures declaradas; não são medições dos ensaios de rede.
Os fontes corretos não são substituídos em momento algum.

Para ensaiar também as variantes em processos e rede reais, depois de
`testar.ps1` e `mutacoes.py`, usar a pasta de variantes que este último indicar:

```powershell
python testes/ensaios.py --ipfs 'C:/caminho/para/ipfs.exe' --suite falhas --mutacoes dados/mutacoes-AAAAMMDD-HHMMSS
```

O ensaio sem restrição de voto dá ao candidato atrasado um prazo de 1 s para
dirigir a votação, mantendo os restantes nós nos valores normais. O ensaio
de lote obsoleto reenvia um heartbeat vazio válido do termo anterior, simulando
uma mensagem atrasada. Os erros dos analisadores são esperados nestas variantes;
o runner assinala como falha de execução apenas um cenário que não conseguiu
reproduzir a condição pretendida. `--suite recuperacao` repete apenas a/c/d/e.

Consultar `RELATORIO.md` para resultados efetivamente medidos e reflexão sobre os limites do sistema.

## Organização e limites

| Projeto | Responsabilidade |
|---|---|
| `raft04-no` | `PartitionNode`, `RaftState`, TCP, histórico e analisadores |
| `raft04-produtor` | entrada interativa, rajadas, monitores e registo dos envios |
| `raft04-processador` | observação dos lotes, deduplicação (partição,seq,termo) e alarmes |

As classes de protocolo são copiadas para cada projeto, como na ficha anterior.
Cada projeto compila sozinho. `RaftState` vem do código fornecido na ficha.
O monitor do nó protege as transições eleitorais e a atribuição de seq; a ordem
de locks é nó → RaftState → ficheiro/log. Nenhum callback de RaftState adquire
o monitor do nó. O servidor TCP copia o intervalo sob RecordFile e liberta-o
antes de enviar; não adquire o monitor do nó.

Este algoritmo não implementa commit, Pre-Vote, leases, snapshots nem reenvio
do produtor. Não garante consistência linearizável. Um líder isolado continua
a gravar leituras que podem desaparecer após a reparação da rede.

Antes da submissão, rever o diff, fazer commit na branch `comindir05`, enviar
a branch para o fork e abrir o PR para a `master` do docente, conforme o README
da raiz. A execução dos testes não substitui a compreensão para a avaliação oral.
