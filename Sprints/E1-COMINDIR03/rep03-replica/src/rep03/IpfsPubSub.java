package rep03;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Adaptador HTTP para o daemon local Kubo; PubSub não cria ficheiros/CIDs no IPFS. */
public class IpfsPubSub {
    // A API é o destino do Java; os nós IPFS comunicam entre si pelas ligações swarm.
    private final String api;
    private final HttpClient http = HttpClient.newHttpClient();

    public IpfsPubSub(String api) {
        this.api = api;
    }

    // Base64url sem padding, com o prefixo multibase 'u'.
    // Codificação reversível, não encriptação; UTF-8 define os bytes do texto.
    static String toMultibase(String s) {
        return "u" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // Remove o prefixo u, descodifica base64url e recupera o texto.
    // Ainda não interpreta os quatro campos de SensorRecord.
    static String fromMultibase(String s) {
        if (!s.startsWith("u")) {
            throw new IllegalArgumentException("Codificação multibase não suportada: " + s);
        }
        return new String(Base64.getUrlDecoder().decode(s.substring(1)), StandardCharsets.UTF_8);
    }

    // A mensagem segue no corpo como ficheiro multipart.
    public void publish(String topic, String message) throws IOException, InterruptedException {
        // Delimitador multipart separa o conteúdo; nanoTime aqui não mede latência.
        String boundary = "rep01-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        // BLOQUEANTE: espera pela resposta HTTP completa do daemon. Não espera pelas réplicas.
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        // 200 confirma aceitação pelo daemon, não receção/escrita pelos subscritores.
        if (resp.statusCode() != 200) {
            throw new IOException("pub falhou (" + resp.statusCode() + "): " + resp.body());
        }
    }

    // Mantém a resposta aberta; cada linha é uma mensagem JSON.
    // Consumer<String> é uma função que recebe texto (por exemplo, replica::onMessage).
    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        // BLOQUEANTE: espera pela resposta inicial; ofLines permite consumir o corpo por linhas.
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        // 200 confirma aceitação pelo daemon, não receção/escrita pelos subscritores.
        if (resp.statusCode() != 200) {
            throw new IOException("sub falhou (" + resp.statusCode() + ")");
        }
        // BLOQUEANTE: consumir o stream espera pelas linhas seguintes até fechar/falhar a ligação.
        // filter seleciona mensagens com data; forEach chama a função após descodificar.
        resp.body()
                .filter(json -> json.contains("\"data\""))
                .forEach(json -> onMessage.accept(fromMultibase(extractField(json, "data"))));
    }

    // Extração mínima do campo, sem bibliotecas externas.
    // Parser específico do formato fornecido: não é um parser JSON geral
    // e não trata todas as variantes de espaços ou escapes.
    static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("Campo '" + field + "' ausente: " + json);
        }
        start += key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
