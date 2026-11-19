package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Chunk {
    private final List<byte[]> registos = new ArrayList<>();
    private int tamanho = Integer.BYTES;
    private long primeiro;
    private long ultimo;

    void adicionar(Registo registo) {
        byte[] bytes = registo.linha().getBytes(StandardCharsets.UTF_8);
        tamanho = Math.addExact(tamanho, Math.addExact(Integer.BYTES, bytes.length));
        if (vazio()) {
            primeiro = registo.lsn();
        }
        ultimo = registo.lsn();
        registos.add(bytes);
    }

    boolean vazio() {
        return registos.isEmpty();
    }

    boolean cheio() {
        return tamanho >= Wire.CHUNK_MAX_BYTES;
    }

    long primeiro() {
        return primeiro;
    }

    long ultimo() {
        return ultimo;
    }

    byte[] serializar() {
        int padding = (Wire.PAD_BYTES - tamanho % Wire.PAD_BYTES) % Wire.PAD_BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(Math.addExact(tamanho, padding));
        buffer.putInt(registos.size());
        for (byte[] registo : registos) {
            buffer.putInt(registo.length).put(registo);
        }
        return buffer.array();
    }

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
        return List.copyOf(resultado);
    }
}
