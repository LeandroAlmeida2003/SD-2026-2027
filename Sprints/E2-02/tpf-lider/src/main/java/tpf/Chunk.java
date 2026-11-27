package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * E2-02: grupo de registos em memória antes da cifra e publicação.
 * Arquivo decide quando selar e passa a usar outro Chunk após a publicação.
 */
final class Chunk {
    // Guarda os bytes UTF-8 de cada linha de registo, sem a quebra de linha final.
    private final List<byte[]> registos = new ArrayList<>();
    // Os 4 bytes iniciais vão guardar a quantidade de registos.
    private int tamanho = Integer.BYTES;
    // Estes dois campos indicam o intervalo de LSN incluído no grupo.
    private long primeiro;
    private long ultimo;

    /** Junta um registo ao grupo e atualiza o tamanho e o intervalo de LSN. */
    void adicionar(Registo registo) {
        byte[] bytes = registo.linha().getBytes(StandardCharsets.UTF_8);
        // Conta os dados mais 4 bytes para guardar o comprimento deste registo.
        tamanho = Math.addExact(tamanho, Math.addExact(Integer.BYTES, bytes.length));
        if (vazio()) {
            primeiro = registo.lsn();
        }
        ultimo = registo.lsn();
        registos.add(bytes);
    }

    /** Identifica o primeiro registo e ajuda a evitar uma selagem sem dados. */
    boolean vazio() {
        return registos.isEmpty();
    }

    /** É testado após adicionar: o último registo pode fazer ultrapassar o limite. */
    boolean cheio() {
        return tamanho >= Wire.CHUNK_MAX_BYTES;
    }

    /** LSN inicial, consultado quando o grupo contém pelo menos um registo. */
    long primeiro() {
        return primeiro;
    }

    /** LSN final, incluído no intervalo do evento SELADO. */
    long ultimo() {
        return ultimo;
    }

    /** Produz contagem + pares (comprimento, dados) + padding; inteiros em big-endian. */
    byte[] serializar() {
        // Se já for múltiplo de PAD_BYTES, o padding é zero.
        int padding = (Wire.PAD_BYTES - tamanho % Wire.PAD_BYTES) % Wire.PAD_BYTES;
        // O buffer começa com zeros; o espaço final não escrito fica como padding.
        ByteBuffer buffer = ByteBuffer.allocate(Math.addExact(tamanho, padding));
        buffer.putInt(registos.size());
        for (byte[] registo : registos) {
            buffer.putInt(registo.length).put(registo);
        }
        return buffer.array();
    }

    /** Usa a contagem e os comprimentos para reconstruir os registos originais. */
    static List<Registo> ler(byte[] dados) {
        ByteBuffer buffer = ByteBuffer.wrap(dados);
        int count = buffer.getInt();
        if (count < 1 || count > buffer.remaining() / Integer.BYTES) {
            throw new IllegalArgumentException("Contagem inválida no chunk.");
        }
        List<Registo> resultado = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = buffer.getInt();
            if (length <= 0 || length > buffer.remaining()) {
                throw new IllegalArgumentException("Comprimento inválido no chunk.");
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            resultado.add(Registo.ler(new String(bytes, StandardCharsets.UTF_8)));
        }
        // Os bytes que sobram são padding; devolve uma lista que não pode ser alterada.
        return List.copyOf(resultado);
    }
}
