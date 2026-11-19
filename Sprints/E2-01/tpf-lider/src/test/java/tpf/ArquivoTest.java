package tpf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArquivoTest {
    @Rule
    public final TemporaryFolder pasta = new TemporaryFolder();

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
        var actual = new ArrayList<Registo>();
        for (Path bin : bins(raiz)) {
            byte[] ciphertext = Files.readAllBytes(bin);
            assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains("x".repeat(512)));
            assertEquals(0, (ciphertext.length - 16) % Wire.PAD_BYTES);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext))
                    + ".bin", bin.getFileName().toString());
            actual.addAll(Arquivo.abrir(bin, key));
        }
        actual.sort(Comparator.comparingLong(Registo::lsn));
        assertEquals(Files.readAllLines(raiz.resolve("registos.log")).stream().map(Registo::ler).toList(),
                actual);
    }

    private static List<Path> bins(Path raiz) throws Exception {
        try (var files = Files.list(raiz.resolve("selados"))) {
            return files.filter(path -> path.toString().endsWith(".bin")).toList();
        }
    }
}
