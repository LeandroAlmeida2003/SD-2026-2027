package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Persistência local de registos em texto; não envia nem recupera mensagens. */
public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        // Caminho relativo: resolvido a partir da pasta de execução, não da pasta src.
        // Criar um Path ainda não cria o ficheiro.
        this.path = Paths.get(fileName);
    }

    // acrescenta uma linha ao fim do ficheiro (cria-o se não existir)
    // synchronized pode esperar pelo monitor deste objeto; não coordena outros processos.
    // BLOQUEANTE: escrita síncrona; espera pela operação de ficheiro ou por um erro.
    // IOException é propagada ao chamador. Sucesso não equivale a fsync contra falha de energia.
    public synchronized void append(SensorRecord r) throws IOException {
        // CREATE cria se faltar; APPEND preserva o conteúdo; uma mudança de linha por registo.
        Files.writeString(path, r.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // número de sequência do último registo guardado (0 se o ficheiro não existir)
    // BLOQUEANTE: sincronização e leitura do ficheiro. Percorre todas as linhas em memória.
    // Devolve a sequência da última linha não vazia, não o máximo nem a quantidade de linhas.
    public synchronized long lastSeq() throws IOException {
        // ! significa negação: se não existe, ainda não há sequência guardada.
        if (!Files.exists(path)) {
            return 0;
        }
        long last = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = SensorRecord.fromLine(line).getSeq();
            }
        }
        return last;
    }

    // registos com from <= seq <= to, pela ordem do ficheiro (lista vazia se o ficheiro não existir)
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            SensorRecord r = SensorRecord.fromLine(line);
            if (r.getSeq() >= from && r.getSeq() <= to) {
                out.add(r);
            }
        }
        return out;
    }
}
