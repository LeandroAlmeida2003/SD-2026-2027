package rep02;

import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import java.util.Scanner;

/** Leituras individuais e rajadas encaminhadas pela chave do sensor. */
public class Producer {
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }
        Partitioner partitioner;
        try {
            partitioner = new Partitioner(Integer.parseInt(args[0]));
        } catch (IllegalArgumentException e) {
            System.out.println("N deve ser um número inteiro positivo.");
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub pubSub = new IpfsPubSub(api);

        try (Scanner console = new Scanner(System.in)) {
            System.out.println("Partições: " + partitioner.count());
            System.out.println("Formato: <sensor> <temperatura> | rajada <n> <s> | rajada-quente <n> <s> | sair");
            while (true) {
                System.out.print("> ");
                if (!console.hasNextLine()) {
                    break;
                }
                String line = console.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                String[] fields = line.split("\\s+");
                boolean hot = fields[0].equalsIgnoreCase("rajada-quente");
                if (fields[0].equalsIgnoreCase("rajada") || hot) {
                    if (fields.length != 3) {
                        System.out.println("Formato: " + fields[0] + " <n> <s>. Exemplo: " + fields[0] + " 100 10");
                        continue;
                    }
                    int count;
                    int sensorCount;
                    try {
                        count = Integer.parseInt(fields[1]);
                        sensorCount = Integer.parseInt(fields[2]);
                        if (count <= 0 || sensorCount <= 0) {
                            System.out.println("O número de leituras e de sensores deve ser positivo.");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("O número de leituras e de sensores deve ser inteiro.");
                        continue;
                    }
                    if (hot && sensorCount < 2) {
                        System.out.println("A rajada quente precisa de pelo menos dois sensores.");
                        continue;
                    }
                    try {
                        publishBurst(pubSub, partitioner, count, sensorCount, hot);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("Rajada interrompida.");
                        return;
                    }
                    continue;
                }

                if (fields.length != 2) {
                    System.out.println("Formato inválido. Exemplo: S01 23.5");
                    continue;
                }
                String sensor = fields[0];
                double temperature;
                try {
                    temperature = Double.parseDouble(fields[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida. Usa um ponto como separador decimal.");
                    continue;
                }

                String message = Wire.in(sensor, temperature);
                int partition = partitioner.of(sensor);
                String topic = Wire.topicIn(partition);
                try {
                    pubSub.publish(topic, message);
                    // A API aceitou a publicação; não é uma confirmação do líder.
                    System.out.println("Publicado em " + topic + ": " + message + " → p" + partition);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Publicação interrompida.");
                    return;
                } catch (IOException e) {
                    System.out.println("Falha na publicação → p" + partition + ": " + e.getMessage());
                }
            }
        }
    }

    private static void publishBurst(IpfsPubSub pubSub, Partitioner partitioner, int count, int sensorCount, boolean hot)
            throws InterruptedException {
        Random random = new Random();
        long firstSend = 0;
        long lastSend = 0;
        int accepted = 0;
        int failed = 0;
        int[] attemptedByPartition = new int[partitioner.count()];
        int[] acceptedByPartition = new int[partitioner.count()];
        for (int i = 0; i < count; i++) {
            // Na rajada quente, cada escolha tem 80% de probabilidade de ser S01.
            int sensorNumber = hot
                    ? (random.nextDouble() < 0.8 ? 1 : random.nextInt(sensorCount - 1) + 2)
                    : random.nextInt(sensorCount) + 1;
            String sensor = String.format(Locale.ROOT, "S%02d", sensorNumber);
            double temperature = 15 + Math.random() * 15;
            String message = Wire.in(sensor, temperature);
            int partition = partitioner.of(sensor);
            String topic = Wire.topicIn(partition);
            attemptedByPartition[partition]++;

            if (i == 0) firstSend = System.currentTimeMillis();
            try {
                pubSub.publish(topic, message);
                lastSend = System.currentTimeMillis();
                accepted++;
                acceptedByPartition[partition]++;
                System.out.println("Publicado em " + topic + ": " + message + " → p" + partition);
            } catch (IOException e) {
                lastSend = System.currentTimeMillis();
                // Uma tentativa por leitura, sem retransmissão. A falha fica visível na contagem.
                failed++;
                System.out.println("PUBLICAÇÃO FALHOU: " + message + " → p" + partition + " | " + e.getMessage());
            }
        }
        System.out.println("Rajada" + (hot ? " quente" : "") + ": " + count + " tentativas de publicação");
        System.out.println("Aceites pela API: " + accepted + "; falhas: " + failed);
        for (int k = 0; k < partitioner.count(); k++) {
            System.out.println("p" + k + ": " + attemptedByPartition[k] + " tentativas; "
                    + acceptedByPartition[k] + " aceites pela API; "
                    + (attemptedByPartition[k] - acceptedByPartition[k]) + " falhas");
        }
        System.out.println("Primeiro envio (ms): " + firstSend);
        System.out.println("Último envio (ms): " + lastSend);
        System.out.println("Duração (ms): " + (lastSend - firstSend));
    }
}
