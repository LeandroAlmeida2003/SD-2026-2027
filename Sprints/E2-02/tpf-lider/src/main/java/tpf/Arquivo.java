package tpf;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * E2-02: coordena mensagem -> log durável -> chunk -> cifra -> ficheiros.
 * O log permite retomar registos locais depois de reiniciar, mesmo antes da selagem.
 */
final class Arquivo implements AutoCloseable {
    private final Path eventos;
    private final Path selados;
    private final byte[] masterKey;
    // Canal do log, mantido aberto para acrescentar linhas e forçar a escrita.
    private final FileChannel log;
    // Impede dois processos de usarem ao mesmo tempo os dados desta pasta.
    private final FileChannel lock;
    // Uma thread agenda e executa a selagem por tempo.
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    // Grupo que recebe os registos atuais; é substituído depois de selar.
    private Chunk aberto = new Chunk();
    private ScheduledFuture<?> prazo;
    private long ultimoLsn;
    // Uma falha de armazenamento suspende novos registos até reiniciar.
    private boolean escritaFalhou;
    private boolean fechado;

    /** Abre os ficheiros, obtém o lock e recupera o estado local antes de receber pedidos. */
    Arquivo(Path pasta, byte[] masterKey) throws IOException {
        // A cópia evita que o array entregue pelo chamador altere a chave do arquivo.
        this.masterKey = masterKey.clone();
        eventos = pasta.resolve("eventos-lider.txt");
        selados = Files.createDirectories(pasta.resolve("selados"));
        Path registos = pasta.resolve("registos.log");
        lock = FileChannel.open(pasta.resolve(".arquivo.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            // O lock protege entre processos; synchronized protege entre threads.
            if (lock.tryLock() == null) {
                throw new IOException("Já existe um líder a usar registos.log.");
            }
            log = FileChannel.open(registos, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                // Uma interrupção pode deixar bytes finais sem uma linha completa.
                descartarLinhaIncompleta(registos);
                // Protege a recuperação das tarefas de selagem que ela pode agendar.
                synchronized (this) {
                    recuperar(registos);
                }
            } catch (IOException | RuntimeException exception) {
                log.close();
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            timer.shutdownNow();
            lock.close();
            throw exception;
        }
    }

    /**
     * Caminho único de /registar, mensagens de #arquivo e /rajada.
     * Só uma thread atribui e grava um LSN de cada vez; o retorno vem após force(true).
     */
    synchronized long registar(String autor, String canal, String texto) throws IOException {
        if (fechado || escritaFalhou) {
            throw new IOException("O arquivo não está disponível para escrita.");
        }
        // Calcula o próximo LSN; incrementExact também deteta overflow do long.
        Registo registo = new Registo(Math.incrementExact(ultimoLsn), Instant.now(), autor, canal, texto);
        ByteBuffer linha = StandardCharsets.UTF_8.encode(registo.linha() + "\n");
        try {
            // Posiciona no fim para acrescentar sem apagar registos anteriores.
            log.position(log.size());
            // write pode escrever só parte dos bytes; repete até terminar a linha.
            while (linha.hasRemaining()) {
                log.write(linha);
            }
            // Ponto de durabilidade: força dados e metadados antes da confirmação no Discord.
            log.force(true);
        } catch (IOException exception) {
            escritaFalhou = true;
            throw exception;
        }
        // Só atualiza o último LSN em memória depois da escrita forçada.
        ultimoLsn = registo.lsn();
        try {
            // Guarda o acontecimento REGISTO no ficheiro de eventos.
            evento(registo.instante(), detalheRegisto(registo));
        } catch (IOException exception) {
            escritaFalhou = true;
            throw new IOException("Falhou o evento REGISTO do LSN " + ultimoLsn + ".", exception);
        }
        try {
            adicionar(registo);
        } catch (IOException exception) {
            falhaDeSelagem(exception);
        }
        // A linha já está durável: mesmo se a selagem falhar, pode confirmar este LSN.
        // A falha suspende novos pedidos e os dados podem ser retomados pelo log.
        return ultimoLsn;
    }

    /** Junta ao chunk e sela por tamanho ou agenda o prazo desde o primeiro registo. */
    private void adicionar(Registo registo) throws IOException {
        boolean primeiro = aberto.vazio();
        // O registo que ultrapassa o limite ainda fica no grupo que vai ser selado.
        aberto.adicionar(registo);
        if (aberto.cheio()) {
            selar();
        } else if (primeiro) {
            // Identifica o grupo para uma tarefa antiga não selar um chunk novo.
            Chunk alvo = aberto;
            prazo = timer.schedule(() -> selarNoPrazo(alvo), Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Usa o mesmo monitor de registar para não modificar o chunk em simultâneo. */
    private synchronized void selarNoPrazo(Chunk alvo) {
        if (fechado || escritaFalhou || aberto != alvo) {
            return;
        }
        try {
            selar();
        } catch (IOException exception) {
            falhaDeSelagem(exception);
        }
    }

    /** Suspende novas escritas para retomar o estado pelo log no próximo arranque. */
    private void falhaDeSelagem(IOException exception) {
        escritaFalhou = true;
        System.err.println("Falhou a selagem. Verifica o armazenamento e reinicia o líder: "
                + exception.getMessage());
    }

    /** Serializa, cifra e publica o grupo atual, preparando outro para os próximos registos. */
    private void selar() throws IOException {
        Sealed sealed;
        try {
            // serializar acrescenta padding; seal deriva a DEK e cifra com AES-GCM.
            sealed = ChunkCrypto.seal(masterKey, aberto.serializar());
        } catch (GeneralSecurityException exception) {
            throw new IOException("Falhou a cifra do chunk.", exception);
        }

        byte[] ciphertext = sealed.ciphertext();
        String id = sealed.id();
        // O cabeçalho guarda versão, salt e nonce; a chave mestra não vai para o chunk.
        publicar(id + ".hdr", sealed.header().getBytes(StandardCharsets.UTF_8));
        try {
            // O .bin contém dados cifrados e a tag de autenticação GCM.
            publicar(id + ".bin", ciphertext);
        } catch (IOException exception) {
            try {
                // Se o .bin falhar, tenta não deixar apenas o cabeçalho publicado.
                Files.deleteIfExists(selados.resolve(id + ".hdr"));
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        // Associa o ficheiro ao intervalo de LSN e ao tamanho, incluindo a tag.
        evento(Instant.now(), detalheSelado(id, aberto.primeiro(), aberto.ultimo(), ciphertext.length));

        // Os próximos registos entram noutro grupo; o publicado deixa de ser alterado.
        aberto = new Chunk();
        if (prazo != null) {
            prazo.cancel(false);
            prazo = null;
        }
    }

    /** Força um temporário para disco e publica cada ficheiro com mudança de nome atómica. */
    private void publicar(String nome, byte[] dados) throws IOException {
        Path destino = selados.resolve(nome);
        Path temporario = Files.createTempFile(selados, ".chunk-", ".tmp");
        try {
            Files.write(temporario, dados, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            // O nome final só aparece com o ficheiro completo; .hdr e .bin são separados.
            Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporario);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /** Acrescenta instante;evento;detalhe com escrita síncrona do ficheiro de eventos. */
    private void evento(Instant instante, String detalhe) throws IOException {
        Files.writeString(eventos, instante + ";" + detalhe + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
    }

    /** Relaciona o evento REGISTO com o número sequencial atribuído. */
    private static String detalheRegisto(Registo registo) {
        return "REGISTO;lsn=" + registo.lsn();
    }

    /** Indica ID, LSN inicial e final inclusivos e bytes do .bin, já com a tag. */
    private static String detalheSelado(String id, long primeiro, long ultimo, long bytes) {
        return "SELADO;chunkId=" + id + ",lsn=" + primeiro + "-" + ultimo + ",bytes=" + bytes;
    }

    /** Lê os detalhes existentes para não duplicar eventos durante a recuperação. */
    private Set<String> lerEventos() throws IOException {
        Set<String> existentes = new HashSet<>();
        if (Files.exists(eventos)) {
            descartarLinhaIncompleta(eventos);
            try (var reader = Files.newBufferedReader(eventos)) {
                for (String linha; (linha = reader.readLine()) != null; ) {
                    int separador = linha.indexOf(';');
                    if (separador >= 0) {
                        existentes.add(linha.substring(separador + 1));
                    }
                }
            }
        }
        return existentes;
    }

    /** Escreve apenas um evento cujo detalhe ainda não está no conjunto conhecido. */
    private void reporEvento(Set<String> existentes, Instant instante, String detalhe) throws IOException {
        if (existentes.add(detalhe)) {
            evento(instante, detalhe);
        }
    }

    /** Lê o log, continua a numeração e retoma registos posteriores ao último LSN selado. */
    private void recuperar(Path registos) throws IOException {
        Set<String> existentes = lerEventos();
        try (var reader = Files.newBufferedReader(registos)) {
            for (String linha; (linha = reader.readLine()) != null; ) {
                Registo registo = Registo.ler(linha);
                // Uma repetição ou um salto na sequência impede o arranque.
                if (registo.lsn() != ultimoLsn + 1) {
                    throw new IOException("Numeração inválida em registos.log.");
                }
                ultimoLsn = registo.lsn();
                reporEvento(existentes, registo.instante(), detalheRegisto(registo));
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IOException("Linha inválida em registos.log.", exception);
        }
        // Abre e valida os chunks existentes antes de retomar os registos pendentes.
        long ultimoSelado = ultimoLsnSelado(existentes);
        if (ultimoSelado < ultimoLsn) {
            try (var reader = Files.newBufferedReader(registos)) {
                for (long index = 0; index < ultimoSelado; index++) {
                    reader.readLine();
                }
                for (String linha; (linha = reader.readLine()) != null; ) {
                    adicionar(Registo.ler(linha));
                }
            }
        }
    }

    /** Abre os chunks, identifica o maior LSN selado e repõe eventos em falta. */
    private long ultimoLsnSelado(Set<String> existentes) throws IOException {
        long ultimo = 0;
        try (var ficheiros = Files.newDirectoryStream(selados, "*.bin")) {
            for (Path bin : ficheiros) {
                List<Registo> registos = abrir(bin, masterKey);
                long primeiroLsn = registos.get(0).lsn();
                long ultimoLsnDoChunk = registos.get(registos.size() - 1).lsn();
                if (ultimoLsnDoChunk > ultimoLsn) {
                    throw new IOException("Há chunks com LSN posteriores a registos.log.");
                }
                reporEvento(existentes, Instant.now(),
                        detalheSelado(idDoChunk(bin), primeiroLsn, ultimoLsnDoChunk, Files.size(bin)));
                ultimo = Math.max(ultimo, ultimoLsnDoChunk);
            }
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IOException("Não foi possível validar os chunks existentes.", exception);
        }
        return ultimo;
    }

    /** Retira a extensão .bin para obter o ID usado no nome do ficheiro. */
    private static String idDoChunk(Path bin) {
        String nome = bin.getFileName().toString();
        return nome.substring(0, nome.length() - ".bin".length());
    }

    /** Lê metadados e cifra, valida e decifra, e reconstrói os registos sem o padding. */
    static List<Registo> abrir(Path bin, byte[] masterKey) throws IOException, GeneralSecurityException {
        String id = idDoChunk(bin);
        String header = Files.readString(bin.resolveSibling(id + ".hdr"));
        Sealed sealed = Sealed.read(id, header, Files.readAllBytes(bin));
        return Chunk.ler(ChunkCrypto.open(masterKey, sealed));
    }

    /** Conserva só linhas terminadas numa quebra de linha e trunca uma cauda incompleta. */
    private static void descartarLinhaIncompleta(Path registos) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(registos.toFile(), "rw")) {
            long end = file.length();
            long complete = end;
            while (complete > 0) {
                file.seek(complete - 1);
                if (file.read() == '\n') {
                    break;
                }
                complete--;
            }
            if (complete != end) {
                file.setLength(complete);
                file.getChannel().force(true);
            }
        }
    }

    /** Para o timer, tenta selar o grupo pendente e liberta log e lock, mesmo com erro. */
    @Override
    public synchronized void close() throws IOException {
        if (fechado) {
            return;
        }
        fechado = true;
        timer.shutdownNow();
        try (lock; log) {
            if (!escritaFalhou && !aberto.vazio()) {
                selar();
            }
        }
    }
}
