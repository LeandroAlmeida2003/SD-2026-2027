# LeaderBot — Sprint E2-02

O bot usa Java 17 ou superior. O Maven Wrapper descarrega a versão de Maven usada pelo projeto no primeiro arranque. A aplicação Discord precisa de estar instalada no servidor de teste com os scopes `bot` e `applications.commands`, ter o **Message Content Intent** ativo e poder ver e enviar mensagens em `#arquivo`.

Na raiz do repositório, abre um PowerShell e executa:

```powershell
Set-Location Sprints/E2-02/tpf-lider
$env:GUILD_ID = '1570800502105571379'
$botToken = Read-Host 'Token do LeaderBot' -AsSecureString
$env:DISCORD_TOKEN = [System.Net.NetworkCredential]::new('', $botToken).Password
Remove-Variable botToken

.\mvnw.cmd compile dependency:build-classpath '-Dmdep.outputFile=target/discord-classpath.txt'
if ($LASTEXITCODE -eq 0) {
    $botClasspath = 'target/classes;' + (Get-Content target/discord-classpath.txt -Raw).Trim()
    java -cp $botClasspath tpf.LeaderBot
}
Remove-Item Env:DISCORD_TOKEN
```

O arranque confirma o servidor na consola e atualiza os comandos do bot nesse servidor. Para parar, usa Ctrl+C. Mantém o token fora do código e do Git.

## Parâmetros do E2-02

O líder cria `master.key` na pasta de trabalho no primeiro arranque. Conserva esse ficheiro entre arranques e mantém-no fora do Git. O líder recusa uma chave existente cujo tamanho seja diferente de 32 bytes.

Para alterar os valores predefinidos, passa as opções antes de `-cp` no comando `java`:

- `-Dtpf.chunk.max.bytes=65536` define o limite de tamanho do chunk.
- `-Dtpf.pad.bytes=4096` define o múltiplo do padding.
- `-Dtpf.selagem.ms=5000` define o tempo de selagem em milissegundos.

## Verificação no Discord

1. Confirma que o LeaderBot fica online e que aparecem os sete comandos slash, incluindo `/rajada`.
2. Experimenta `/registar` com texto e `/recuperar` com um intervalo invertido. Confirma a resposta com o LSN no primeiro caso e a rejeição no segundo.
3. Com uma conta sem o cargo `admin-arquivo`, confirma que `/config`, `/replica-token`, `/replica-remover` e `/rajada` são recusados.
4. Envia uma mensagem em `#arquivo` e outra em `#geral`. Confirma que só a primeira recebe resposta e que o bot não responde às suas próprias mensagens.

## Registo e selagem do E2-02

O diretório de trabalho contém `registos.log`, `eventos-lider.txt`, `master.key` e a pasta `selados/`. Usa o mesmo diretório depois de reiniciar. Em `registos.log`, cada linha UTF-8 tem o formato `lsn;instante;autor;canal;textoBase64`. Autor e canal são IDs do Discord, o instante está em ISO-8601 e o texto é codificado em Base64 a partir de UTF-8. O líder força o log para disco antes de devolver o LSN. Base64 permite representar texto com quebras de linha e ponto e vírgula.

O formato binário do chunk começa com a contagem de registos em 4 bytes. Para cada registo seguem-se 4 bytes com o comprimento e os bytes UTF-8 da linha, sem a quebra de linha. Todos os inteiros são big-endian. A seguir vêm bytes zero até o tamanho total ser múltiplo de `PAD_BYTES`. A leitura usa a contagem e os comprimentos para reconstruir os registos e ignora o padding.

O ficheiro `<chunkId>.bin` contém o resultado de AES-256-GCM, incluindo a tag final de 16 bytes. O tamanho do texto com padding é múltiplo de `PAD_BYTES` e o `.bin` tem mais 16 bytes. O `chunkId` é o SHA-256 em hexadecimal dos bytes do `.bin`. O ficheiro `<chunkId>.hdr` contém uma linha UTF-8 no formato `keyVersion;saltBase64;nonceBase64`, com versão decimal `1`, salt de 32 bytes e nonce de 12 bytes. O evento `SELADO` indica o ID, o intervalo inclusivo de LSN e o tamanho do `.bin`.

Cada chunk recebe salt e nonce novos de `SecureRandom`. A DEK de 32 bytes é derivada por HKDF-SHA256: extração `PRK = HMAC(salt, master.key)` e expansão `DEK = HMAC(PRK, UTF-8("tpf-chunk-v1") || 0x01)`. O AAD é `UTF-8("tpf-v1") || keyVersion || salt || nonce`, com a versão num inteiro big-endian de 4 bytes. A abertura verifica primeiro o SHA-256, depois a versão e finalmente a autenticação GCM. Uma chave ou metadados diferentes impedem a abertura.

`/registar`, mensagens humanas em `#arquivo` e `/rajada` usam o mesmo método sincronizado de `Arquivo`. A rajada exige o cargo `admin-arquivo`, `n >= 1` e `intervaloms >= 0`; executa em segundo plano e é interrompida ao encerrar o líder. Os comandos `/estado`, `/config`, `/recuperar`, `/replica-token` e `/replica-remover` conservam as respostas provisórias. A recuperação implementada neste sprint retoma apenas os dados locais do líder.

Se a escrita no disco falhar, o líder suspende novos registos até reiniciar. No arranque, repõe os eventos em falta para os registos duráveis e chunks publicados, e retoma a selagem dos registos pendentes.

### Testes

- Executa `.\mvnw.cmd test` no PowerShell. Os testes usam diretórios temporários e incluem registo antes da confirmação, escritores concorrentes, reinício, recuperação de eventos, selagem por tempo e tamanho, leitura dos intervalos e rejeição de alterações na cifra, mesmo com o hash recalculado. Os eventos do Discord são simulados com Mockito para verificar filtros de mensagens, permissões, argumentos, respostas provisórias e `/rajada 500 0`, sem precisar de token ou ligação ao Discord.
- No Discord, com os valores predefinidos, regista uma mensagem e espera cerca de cinco segundos. Confirma a linha em `registos.log` e o evento `SELADO`. Reinicia o bot e confirma que o próximo registo recebe o LSN seguinte.
- Com o cargo `admin-arquivo`, executa `/rajada` com `n=500` e `intervaloms=0`. Confirma que são gravados 500 registos e que aparecem vários pares `.bin` e `.hdr` em `selados/`.

## O que foi acrescentado no Sprint E2-02

O projeto deste sprint está em `Sprints/E2-02/tpf-lider`. O objetivo é implementar o caminho de escrita local do líder: confirmar um registo só depois de o tornar durável, agrupá-lo num chunk e guardar esse chunk cifrado. A implementação, os testes e o Maven Wrapper estão nesta pasta.

| Ficheiro | Responsabilidade e motivo |
| --- | --- |
| `Wire.java` | Centraliza os valores predefinidos: limite de selagem de 65536 bytes, padding de 4096 bytes e prazo de 5000 ms. Permite alterá-los através de propriedades `-Dtpf.*` e rejeita valores inválidos. |
| `MasterKey.java` | Cria uma chave mestra de 32 bytes com `SecureRandom`, restringe o acesso ao dono e reutiliza a chave nos arranques seguintes. Rejeita ficheiros com tamanho diferente de 32 bytes. |
| `Registo.java` | Representa LSN, instante, autor, canal e texto. Converte o registo para uma linha de log e faz a leitura inversa. O texto é codificado em Base64 para admitir quebras de linha e ponto e vírgula. |
| `Arquivo.java` | Coordena a escrita sincronizada, atribuição de LSN, `FileChannel.force(true)`, eventos, temporizador, selagem e publicação dos ficheiros. No reinício, lê o log para continuar os LSN e retoma os registos locais ainda por selar. |
| `Chunk.java` | Agrupa registos, deteta o limite de tamanho, serializa-os com contagem e comprimentos e acrescenta padding. Também reconstrói os registos, ignorando o padding. |
| `ChunkCrypto.java` | Deriva uma DEK por chunk com HKDF-SHA256, cifra com AES-256-GCM e calcula o SHA-256 do texto cifrado. Na abertura valida hash, versão e autenticação. |
| `Sealed.java` | Guarda o ID, a versão, o salt, o nonce e os bytes cifrados. Lê e escreve o cabeçalho `.hdr`; usa cópias dos arrays para proteger os dados contra alterações externas. |
| `LeaderBot.java` | Carrega a chave, abre o arquivo local e liga os listeners ao Discord. Ao encerrar, interrompe as rajadas, fecha o arquivo e sela o chunk pendente. |
| `LeaderCommands.java` | Encaminha `/registar` para o arquivo e implementa `/rajada n intervaloms` em segundo plano. Verifica o cargo `admin-arquivo` e mantém as respostas provisórias dos comandos dos próximos sprints. |
| `ArchiveMessages.java` | Envia as mensagens humanas de `#arquivo` para o mesmo método de registo. Ignora bots, webhooks, mensagens privadas, outros canais e outros servidores. |

### Percurso de um registo

1. `/registar`, uma mensagem em `#arquivo` ou a rajada chama `Arquivo.registar(autor, canal, texto)`.
2. O método sincronizado atribui o próximo LSN. Só uma chamada executa esta secção de cada vez, evitando LSN repetidos quando chegam pedidos simultâneos.
3. A linha é acrescentada a `registos.log` e `force(true)` força a escrita para o armazenamento antes de ser enviada a confirmação ao Discord.
4. É escrito o evento `REGISTO` e o registo entra no chunk aberto.
5. O chunk sela quando atinge o limite ou quando passam cinco segundos desde o primeiro registo. O registo que faz ultrapassar o limite fica nesse chunk; o seguinte abre um chunk novo. Um chunk também pode ser selado ao encerrar o líder.
6. Os registos são serializados e completados com zeros até um múltiplo de 4096 bytes. Depois são cifrados e publicados como `.bin` e `.hdr`, e é escrito o evento `SELADO` com ID, intervalo de LSN e tamanho.

### Conceitos para explicar na apresentação

- **LSN:** é o número sequencial do registo. Permite ordenar registos e identificar o intervalo contido num chunk. Depois de reiniciar, o próximo LSN vem do último valor do log.
- **Durabilidade:** a confirmação significa que a escrita do registo no log já foi forçada para disco. O chunk pode ainda estar aberto; é o log que permite retomar esses registos após um reinício.
- **Base64:** é uma codificação reversível para o formato de texto do log. Não é cifra e não protege a confidencialidade de `registos.log`.
- **Padding:** completa a serialização até um múltiplo de `PAD_BYTES`. O `.bin` tem ainda a tag GCM de 16 bytes; por exemplo, 4096 bytes com padding dão um `.bin` de 4112 bytes.
- **Chave mestra e DEK:** a chave mestra permanece no líder. HKDF deriva uma chave de cifra para cada chunk a partir dela e de um salt aleatório. O salt pode ficar no cabeçalho; não substitui a chave mestra.
- **Nonce e AAD:** cada chunk recebe um nonce aleatório. O AAD inclui o identificador do formato, a versão da chave, o salt e o nonce; estes metadados participam na autenticação GCM.
- **SHA-256 e GCM:** o hash dá o nome ao chunk e deteta diferenças em relação ao ID esperado. A autenticação GCM também impede aceitar uma alteração quando alguém recalcula o hash do conteúdo adulterado.
- **Concorrência e temporizador:** o registo e a selagem usam o mesmo monitor de `Arquivo`. O temporizador identifica o chunk a que pertence, para uma tarefa antiga não selar um chunk novo.
- **Âmbito:** este sprint guarda e recupera os dados locais do líder. Réplicas, colocação, catálogo, manifesto e recuperação a partir de réplicas pertencem a sprints seguintes.

### Testes acrescentados

| Classe de teste | O que demonstra |
| --- | --- |
| `ArquivoTest` | Confirmação durável, reinício com continuação de LSN, recuperação de registos pendentes e eventos, selagem por tempo e tamanho, leitura dos intervalos e 120 pedidos concorrentes sem LSN repetidos ou saltos. |
| `ChunkCryptoTest` | Cifrar e decifrar, rejeitar alterações no conteúdo, salt, nonce, versão e chave mestra, incluindo conteúdo alterado com hash recalculado. |
| `MasterKeyTest` | Criação e reutilização da chave, permissões de acesso e rejeição de tamanhos inválidos. |
| `DiscordHandlersTest` | Confirmação só depois da escrita no log, filtros de mensagens, permissões, validação de argumentos, respostas provisórias e rajada de 500 registos. Usa Mockito para simular o Discord. |

A execução automática contém 23 testes. Nos testes manuais desta sessão foram confirmados o registo slash, mensagens normais em `#arquivo`, reinício com continuação dos LSN e uma rajada de 500 registos. Os 503 registos produzidos ficaram em 10 chunks; todos foram abertos e comparados com o log, sem diferenças nem duplicados. Os testes manuais de mensagens em `#geral` e de recusa sem o cargo devem ser confirmados no servidor de teste.
