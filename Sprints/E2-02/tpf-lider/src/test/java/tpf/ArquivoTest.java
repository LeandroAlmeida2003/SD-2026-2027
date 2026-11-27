package tpf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** E2-02: testa escrita durável, recuperação e selagem usando apenas pastas temporárias. */
public class ArquivoTest {
    // Cada teste isola os seus ficheiros; não altera os dados do bot nesta pasta.
    @Rule
    public final TemporaryFolder pasta = new TemporaryFolder();

    /** Confirma selagem pelo temporizador e continuação do LSN após reiniciar, incluindo uma cauda incompleta. */
    @Test
    public void sealsByTimeAndContinuesLsnAfterRestart() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        Path log = raiz.resolve("registos.log");
        byte[] key = new byte[32];
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            assertEquals(1, arquivo.registar("autor", "canal", "Olá;\nDiscord"));
            Registo expected = Registo.ler(Files.readString(log).strip());
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Wire.SELAGEM_MS + 3000L);
            List<Path> chunks = bins(raiz);
            while (chunks.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(25);
                chunks = bins(raiz);
            }
            assertEquals(1, chunks.size());
            assertEquals(List.of(expected), Arquivo.abrir(chunks.get(0), key));
        }

        Files.writeString(log, "linha incompleta", StandardOpenOption.APPEND);
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            assertEquals(2, arquivo.registar("autor", "canal", "segundo"));
        }
        assertEquals(List.of(1L, 2L), Files.readAllLines(log).stream()
                .map(Registo::ler).map(Registo::lsn).toList());
        String eventos = Files.readString(raiz.resolve("eventos-lider.txt"));
        assertTrue(eventos.contains(";REGISTO;lsn=2"));
        assertTrue(eventos.contains(";SELADO;"));
    }

    /** Simula um registo que chegou ao log mas ainda não tinha sido selado quando o líder parou. */
    @Test
    public void recoversRecordThatWasDurableBeforeSealing() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        byte[] key = new byte[32];
        Registo anterior = new Registo(1, Instant.now(), "autor", "canal", "pendente");
        Files.writeString(raiz.resolve("registos.log"), anterior.linha() + "\n");
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            assertEquals(2, arquivo.registar("autor", "canal", "novo"));
        }
        List<Path> chunks = bins(raiz);
        assertEquals(1, chunks.size());
        assertEquals(anterior, Arquivo.abrir(chunks.get(0), key).get(0));
    }

    /** Gera 500 registos e verifica padding, hash, eventos e o conteúdo exato de todos os chunks. */
    @Test
    public void sealsFiveHundredRecordsBySizeAndChecksStoredChunks() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        byte[] key = new byte[32];
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            for (int i = 1; i <= 500; i++) {
                assertEquals(i, arquivo.registar("autor", "canal", "sintético " + "x".repeat(512)));
            }
            assertTrue(bins(raiz).size() > 1);
        }
        List<Registo> log = Files.readAllLines(raiz.resolve("registos.log")).stream().map(Registo::ler).toList();
        List<String> eventos = Files.readAllLines(raiz.resolve("eventos-lider.txt"));
        var actual = new ArrayList<Registo>();
        for (Path bin : bins(raiz)) {
            byte[] ciphertext = Files.readAllBytes(bin);
            assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains("x".repeat(512)));
            assertEquals(0, (ciphertext.length - 16) % Wire.PAD_BYTES);
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
            assertEquals(id + ".bin", bin.getFileName().toString());
            String prefixo = ";SELADO;chunkId=" + id + ",lsn=";
            List<String> eventosDoChunk = eventos.stream().filter(linha -> linha.contains(prefixo)).toList();
            assertEquals(1, eventosDoChunk.size());
            String detalhe = eventosDoChunk.get(0).split(";SELADO;", 2)[1];
            String[] campos = detalhe.split(",");
            assertEquals("bytes=" + ciphertext.length, campos[2]);
            String[] intervalo = campos[1].substring("lsn=".length()).split("-");
            int primeiroLsn = Integer.parseInt(intervalo[0]);
            int ultimoLsn = Integer.parseInt(intervalo[1]);
            List<Registo> reabertos = Arquivo.abrir(bin, key);
            assertEquals(log.subList(primeiroLsn - 1, ultimoLsn), reabertos);
            actual.addAll(reabertos);
        }
        actual.sort(Comparator.comparingLong(Registo::lsn));
        assertEquals(log, actual);
    }

    /** Remove eventos para confirmar que o arranque os repõe sem repetir os que já existem. */
    @Test
    public void restoresMissingEventsWithoutDuplicatingExistingOnes() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        Path eventos = raiz.resolve("eventos-lider.txt");
        byte[] key = new byte[32];
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            arquivo.registar("autor", "canal", "primeiro");
            arquivo.registar("autor", "canal", "segundo");
        }
        List<String> originais = Files.readAllLines(eventos);
        Files.writeString(eventos, originais.get(0) + "\n2026-01-01T00:00:00Z;SELADO;chunk");

        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            assertEquals(3, arquivo.registar("autor", "canal", "terceiro"));
        }
        List<String> recuperados = Files.readAllLines(eventos);
        assertEquals(originais.get(0), recuperados.get(0));
        assertEquals(originais.stream().map(ArquivoTest::detalheEvento).toList(),
                recuperados.subList(0, 3).stream().map(ArquivoTest::detalheEvento).toList());
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            assertEquals(recuperados, Files.readAllLines(eventos));
        }
        assertEquals(2, bins(raiz).size());
    }

    /** Provoca falha no ficheiro de eventos e confirma suspensão da escrita e recuperação do LSN durável. */
    @Test
    public void keepsTheLsnWhenTheEventCannotBeWritten() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        Path eventos = raiz.resolve("eventos-lider.txt");
        byte[] key = new byte[32];
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            Files.createDirectory(eventos);
            assertThrows(IOException.class, () -> arquivo.registar("autor", "canal", "durável"));
            assertThrows(IOException.class, () -> arquivo.registar("autor", "canal", "recusado"));
        }
        assertEquals(List.of(1L), Files.readAllLines(raiz.resolve("registos.log")).stream()
                .map(Registo::ler).map(Registo::lsn).toList());
        Files.delete(eventos);

        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            assertEquals(2, arquivo.registar("autor", "canal", "novo"));
        }
        assertEquals("REGISTO;lsn=1", detalheEvento(Files.readAllLines(eventos).get(0)));
        assertEquals("durável", Arquivo.abrir(bins(raiz).get(0), key).get(0).texto());
    }

    /** Retira o instante para comparar o tipo e o detalhe dos eventos. */
    private static String detalheEvento(String linha) {
        return linha.split(";", 2)[1];
    }

    /** Faz 120 pedidos em seis threads e exige LSN consecutivos e únicos, incluindo depois de reiniciar. */
    @Test
    public void concurrentWritersReceiveContiguousUniqueLsn() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        var workers = Executors.newFixedThreadPool(6);
        try (Arquivo arquivo = new Arquivo(raiz, new byte[32])) {
            List<Callable<Long>> pedidos = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                final int index = i;
                pedidos.add(() -> arquivo.registar("autor-" + index, "canal", "texto-" + index));
            }
            List<Long> lsn = new ArrayList<>();
            for (var result : workers.invokeAll(pedidos, 30, TimeUnit.SECONDS)) {
                lsn.add(result.get());
            }
            lsn.sort(Long::compare);
            List<Registo> registos = Files.readAllLines(raiz.resolve("registos.log")).stream()
                    .map(Registo::ler).toList();
            assertEquals(120, registos.size());
            for (int i = 0; i < 120; i++) {
                assertEquals(i + 1L, lsn.get(i).longValue());
                assertEquals(i + 1L, registos.get(i).lsn());
            }
        } finally {
            workers.shutdownNow();
        }
        try (Arquivo arquivo = new Arquivo(raiz, new byte[32])) {
            assertEquals(121, arquivo.registar("autor", "canal", "após reinício"));
        }
    }

    /** Um registo grande sela logo; o seguinte entra noutro chunk sem alterar o ficheiro anterior. */
    @Test
    public void oversizedRecordSealsImmediatelyAndNextRecordOpensANewChunk() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        byte[] key = new byte[32];
        try (Arquivo arquivo = new Arquivo(raiz, key)) {
            arquivo.registar("autor", "canal", "x".repeat(Wire.CHUNK_MAX_BYTES));
            List<Path> chunks = bins(raiz);
            assertEquals(1, chunks.size());
            assertEquals(List.of(1L), Arquivo.abrir(chunks.get(0), key).stream().map(Registo::lsn).toList());
            byte[] original = Files.readAllBytes(chunks.get(0));
            arquivo.registar("autor", "canal", "seguinte");
            org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(chunks.get(0)));
        }
        assertEquals(2, bins(raiz).size());
    }

    /** Lista só os ficheiros .bin, que representam chunks cifrados publicados. */
    private static List<Path> bins(Path raiz) throws Exception {
        try (var files = Files.list(raiz.resolve("selados"))) {
            return files.filter(path -> path.toString().endsWith(".bin")).toList();
        }
    }
}
