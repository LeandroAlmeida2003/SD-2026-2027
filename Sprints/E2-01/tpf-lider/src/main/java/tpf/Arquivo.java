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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class Arquivo implements AutoCloseable {
    private final Path eventos;
    private final Path selados;
    private final byte[] masterKey;
    private final FileChannel log;
    private final FileChannel lock;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Chunk aberto = new Chunk();
    private ScheduledFuture<?> prazo;
    private long ultimoLsn;
    private boolean escritaFalhou;
    private boolean fechado;

    Arquivo(Path pasta, byte[] masterKey) throws IOException {
        this.masterKey = masterKey.clone();
        eventos = pasta.resolve("eventos-lider.txt");
        selados = Files.createDirectories(pasta.resolve("selados"));
        Path registos = pasta.resolve("registos.log");
        lock = FileChannel.open(pasta.resolve(".arquivo.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (lock.tryLock() == null) {
                throw new IOException("Já existe um líder a usar registos.log.");
            }
            log = FileChannel.open(registos, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                descartarLinhaIncompleta(registos);
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

    synchronized long registar(String autor, String canal, String texto) throws IOException {
        if (fechado || escritaFalhou) {
            throw new IOException("O arquivo não está disponível para escrita.");
        }
        Registo registo = new Registo(Math.incrementExact(ultimoLsn), Instant.now(), autor, canal, texto);
        ByteBuffer linha = StandardCharsets.UTF_8.encode(registo.linha() + "\n");
        try {
            log.position(log.size());
            while (linha.hasRemaining()) {
                log.write(linha);
            }
            log.force(true);
        } catch (IOException exception) {
            escritaFalhou = true;
            throw exception;
        }
        ultimoLsn = registo.lsn();
        evento("REGISTO", "lsn=" + ultimoLsn);
        adicionar(registo);
        return ultimoLsn;
    }

    private void adicionar(Registo registo) {
        boolean primeiro = aberto.vazio();
        aberto.adicionar(registo);
        if (aberto.cheio()) {
            tentarSelar(aberto);
        } else if (primeiro) {
            agendar();
        }
    }

    private void agendar() {
        Chunk alvo = aberto;
        if (prazo != null) {
            prazo.cancel(false);
        }
        prazo = timer.schedule(() -> tentarSelar(alvo), Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void tentarSelar(Chunk alvo) {
        if (fechado || aberto != alvo || alvo.vazio()) {
            return;
        }
        try {
            selar();
        } catch (IOException | GeneralSecurityException exception) {
            System.err.println("Falhou a selagem: " + exception.getMessage());
            agendar();
        }
    }

    private void selar() throws IOException, GeneralSecurityException {
        Sealed sealed = ChunkCrypto.seal(masterKey, aberto.serializar());
        byte[] ciphertext = sealed.ciphertext();
        publicar(sealed.id() + ".hdr", sealed.header().getBytes(StandardCharsets.UTF_8));
        publicar(sealed.id() + ".bin", ciphertext);
        evento("SELADO", "chunkId=" + sealed.id() + ",lsn=" + aberto.primeiro()
                + "-" + aberto.ultimo() + ",bytes=" + ciphertext.length);
        aberto = new Chunk();
        if (prazo != null) {
            prazo.cancel(false);
            prazo = null;
        }
    }

    private void publicar(String nome, byte[] dados) throws IOException {
        Path destino = selados.resolve(nome);
        Path temporario = Files.createTempFile(selados, ".chunk-", ".tmp");
        try {
            Files.write(temporario, dados, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    private void evento(String tipo, String detalhe) {
        try {
            Files.writeString(eventos, Instant.now() + ";" + tipo + ";" + detalhe + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("Falhou a escrita de " + tipo + ": " + exception.getMessage());
        }
    }

    private void recuperar(Path registos) throws IOException {
        long ultimoSelado = ultimoLsnSelado();
        try (var reader = Files.newBufferedReader(registos)) {
            for (String linha; (linha = reader.readLine()) != null; ) {
                Registo registo = Registo.ler(linha);
                if (registo.lsn() != ultimoLsn + 1) {
                    throw new IOException("Numeração inválida em registos.log.");
                }
                ultimoLsn = registo.lsn();
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IOException("Linha inválida em registos.log.", exception);
        }
        if (ultimoSelado > ultimoLsn) {
            throw new IOException("Há chunks com LSN posteriores a registos.log.");
        }
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

    private long ultimoLsnSelado() throws IOException {
        long ultimo = 0;
        try (var ficheiros = Files.newDirectoryStream(selados, "*.bin")) {
            for (Path bin : ficheiros) {
                List<Registo> registos = abrir(bin, masterKey);
                ultimo = Math.max(ultimo, registos.get(registos.size() - 1).lsn());
            }
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IOException("Não foi possível validar os chunks existentes.", exception);
        }
        return ultimo;
    }

    static List<Registo> abrir(Path bin, byte[] masterKey) throws IOException, GeneralSecurityException {
        String nome = bin.getFileName().toString();
        String id = nome.substring(0, nome.length() - ".bin".length());
        String header = Files.readString(bin.resolveSibling(id + ".hdr"));
        Sealed sealed = Sealed.read(id, header, Files.readAllBytes(bin));
        return Chunk.ler(ChunkCrypto.open(masterKey, sealed));
    }

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

    @Override
    public synchronized void close() throws IOException {
        if (fechado) {
            return;
        }
        fechado = true;
        timer.shutdownNow();
        try (lock; log) {
            if (!aberto.vazio()) {
                selar();
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Falhou a selagem no encerramento.", exception);
        }
    }
}
