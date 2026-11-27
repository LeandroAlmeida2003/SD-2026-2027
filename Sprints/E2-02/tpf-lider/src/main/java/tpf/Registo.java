package tpf;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * E2-02: dados de cada mensagem: LSN sequencial, data/hora, IDs do autor e canal, e texto.
 * O record reúne estes campos e permite comparar registos pelo seu conteúdo.
 */
record Registo(long lsn, Instant instante, String autor, String canal, String texto) {
    /** Converte para lsn;instante;autor;canal;textoBase64, numa única linha do log. */
    String linha() {
        // Base64 admite texto com quebras de linha e ';'. É codificação, não cifra.
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";"
                + Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** Separa os cinco campos e descodifica o texto UTF-8 para reconstruir o registo. */
    static Registo ler(String linha) {
        String[] campos = linha.split(";", 5);
        return new Registo(Long.parseLong(campos[0]), Instant.parse(campos[1]), campos[2], campos[3],
                new String(Base64.getDecoder().decode(campos[4]), StandardCharsets.UTF_8));
    }
}
