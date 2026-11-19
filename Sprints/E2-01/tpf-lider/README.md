# LeaderBot — Sprint E2-01

O bot usa Java 17 ou superior. O Maven Wrapper descarrega a versão de Maven usada pelo projeto no primeiro arranque. A aplicação Discord precisa de estar instalada no servidor de teste com os scopes `bot` e `applications.commands`, ter o **Message Content Intent** ativo e poder ver e enviar mensagens em `#arquivo`.

Na raiz do repositório, abre um PowerShell e executa:

```powershell
Set-Location Sprints/E2-01/tpf-lider
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

1. Confirma que o LeaderBot fica online e que aparecem os sete comandos slash.
2. Experimenta `/registar` com texto e `/recuperar` com um intervalo invertido. Confirma a resposta com o LSN no primeiro caso e a rejeição no segundo.
3. Com uma conta sem o cargo `admin-arquivo`, confirma que `/config`, `/replica-token`, `/replica-remover` e `/rajada` são recusados.
4. Envia uma mensagem em `#arquivo` e outra em `#geral`. Confirma que só a primeira recebe resposta e que o bot não responde às suas próprias mensagens.

## Registo e selagem do E2-02

O diretório de trabalho contém `registos.log`, `eventos-lider.txt`, `master.key` e a pasta `selados/`. Usa o mesmo diretório depois de reiniciar. Em `registos.log`, cada linha UTF-8 tem o formato `lsn;instante;autor;canal;textoBase64`. Autor e canal são IDs do Discord, o instante está em ISO-8601 e o texto é codificado em Base64 a partir de UTF-8. O líder força o log para disco antes de devolver o LSN. Base64 permite representar texto com quebras de linha e ponto e vírgula.

O formato binário do chunk começa com a contagem de registos em 4 bytes. Para cada registo seguem-se 4 bytes com o comprimento e os bytes UTF-8 da linha, sem a quebra de linha. Todos os inteiros são big-endian. A seguir vêm bytes zero até o tamanho total ser múltiplo de `PAD_BYTES`. A leitura usa a contagem e os comprimentos para reconstruir os registos e ignora o padding.

O ficheiro `<chunkId>.bin` contém o resultado de AES-256-GCM, incluindo a tag final de 16 bytes. O tamanho do texto com padding é múltiplo de `PAD_BYTES` e o `.bin` tem mais 16 bytes. O `chunkId` é o SHA-256 em hexadecimal dos bytes do `.bin`. O ficheiro `<chunkId>.hdr` contém uma linha UTF-8 no formato `keyVersion;saltBase64;nonceBase64`, com versão decimal `1`, salt de 32 bytes e nonce de 12 bytes. O evento `SELADO` indica o ID, o intervalo inclusivo de LSN e o tamanho do `.bin`.

### Testes

- Executa `.\mvnw.cmd test` no PowerShell para testar localmente o registo, reinício, selagem, leitura e rejeição de alterações na cifra.
- No Discord, com os valores predefinidos, regista uma mensagem e espera cerca de cinco segundos. Confirma a linha em `registos.log` e o evento `SELADO`. Reinicia o bot e confirma que o próximo registo recebe o LSN seguinte.
- Com o cargo `admin-arquivo`, executa `/rajada` com `n=500` e `intervaloms=0`. Confirma que são gravados 500 registos e que aparecem vários pares `.bin` e `.hdr` em `selados/`.
