package rep01;

import java.io.*;

import java.util.Scanner;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        // Argumento opcional: API HTTP do daemon, não endereço de uma réplica.
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub pubSub = new IpfsPubSub(api);
        try (Scanner sc = new Scanner(System.in)) {
            RecordFile file = new RecordFile("lider.txt");
            // Retoma a sequência do ficheiro; apagar o ficheiro perde a continuidade.
            long seq = file.lastSeq();
            System.out.println("Líder pronto. Formato: <sensor> <temperatura> | rajada <n> | 'sair' para terminar");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break; // espera por uma linha na consola ou pelo fim da entrada
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                // Divide a entrada por um ou mais espaços.
                String[] p = line.split("\\s+");
                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                if (p[0].equalsIgnoreCase("rajada")) {
                    int n;
                    try {
                        n = Integer.parseInt(p[1]);
                        if (n <= 0) {
                            System.out.println("A quantidade deve ser um inteiro positivo.");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Quantidade inválida: " + p[1]);
                        continue;
                    }

                    // Relógio de parede em ms: pode ser ajustado pelo sistema.
                    // A duração inclui o trabalho local entre as propagações.
                    long firstSend = 0;
                    long lastSend = 0;
                    // n repetições, índices 0 a n-1; sem pausas artificiais.
                    for (int i = 0; i < n; i++) {
                        // ++seq incrementa antes de usar; temperatura em [15, 30).
                        SensorRecord record = SensorRecord.now(++seq, "SIM", 15 + Math.random() * 15);
                        // BLOQUEANTE: espera pela escrita; se falhar, não propaga este registo.
                        file.append(record);

                        if (i == 0) firstSend = System.currentTimeMillis();
                        // BLOQUEANTE: espera pela resposta do daemon, sem confirmação das réplicas.
                        // toLine transforma o objeto em texto para publicar no tópico.
                pubSub.publish(TOPIC, record.toLine());
                        if (i == n - 1) lastSend = System.currentTimeMillis();
                    }
                    System.out.println("Rajada: " + n + " registos");
                    System.out.println("Primeiro envio (ms): " + firstSend);
                    System.out.println("Último envio (ms): " + lastSend);
                    System.out.println("Duração (ms): " + (lastSend - firstSend));
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                // BLOQUEANTE: escrita local antes da propagação.
                file.append(r); // escreve localmente antes de propagar
                // toLine transforma o objeto em texto para publicar no tópico.
                pubSub.publish(TOPIC, r.toLine());
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (InterruptedException e) {
            // Preserva o sinal de interrupção após tratar a exceção.
            Thread.currentThread().interrupt();
            System.out.println("Publicação interrompida.");
        } catch (IOException e) {
            System.out.println("IO: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
