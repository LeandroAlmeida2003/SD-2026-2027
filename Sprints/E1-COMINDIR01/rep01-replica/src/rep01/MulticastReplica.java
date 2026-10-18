package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            long last = file.lastSeq();
            // Long é tipo objeto; chave seq ordena os registos retidos.
            TreeMap<Long, SensorRecord> pending = new TreeMap<>();
            // Contadores desta execução: usar uma execução nova por rajada medida.
            long received = 0;
            long written = 0;
            long duplicates = 0;
            long firstReceive = 0;
            long lastReceive = 0;
            boolean summaryPending = false; // não repetir sem novos dados
            InetAddress group = InetAddress.getByName(GROUP);
            // Entra no grupo; não recebe histórico. API antiga permitida na ficha.
            socket.joinGroup(group);
            // Silêncio de 2 s é observação, não prova de entrega nem falha do líder.
            socket.setSoTimeout(2000);
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT);
            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(p); // espera por um datagrama, até ao timeout
                } catch (SocketTimeoutException e) {
                    if (summaryPending) {
                        System.out.println("Resumo da execução — réplica " + id);
                        System.out.println("Primeira receção (ms): " + firstReceive);
                        System.out.println("Última receção (ms): " + lastReceive);
                        System.out.println("Intervalo de receção (ms): " + (lastReceive - firstReceive));
                        System.out.println("Recebidos (incluindo duplicados): " + received);
                        System.out.println("Recebidos distintos: " + (received - duplicates));
                        System.out.println("Escritos: " + written);
                        System.out.println("Duplicados: " + duplicates);
                        System.out.println("Retidos: " + pending.size());
                        summaryPending = false; // não repetir sem novos dados
                    }
                    continue;
                }
                long receptionTime = System.currentTimeMillis();
                // Só interpreta os bytes efetivamente recebidos, não todo o buffer.
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line);
                    continue;
                }

                if (received == 0) firstReceive = receptionTime;
                lastReceive = receptionTime;
                received++; // inclui duplicados; rejeitados não entram neste contador
                summaryPending = true;

                // Já escrito OU já retido: duplicado, sem nova escrita.
                if (r.getSeq() <= last || pending.containsKey(r.getSeq())) {
                    duplicates++;
                    System.out.println("DUPLICADO: " + r.getSeq());
                } else if (r.getSeq() == last + 1) { // próximo esperado, sem saltos
                    file.append(r);
                    written++; // uma escrita concluída, mesmo quando veio de pending
                    last = r.getSeq();
                    System.out.println("Aplicado: " + r.toLine());

                    // Continua enquanto houver em memória o próximo registo esperado.
                    while (pending.containsKey(last + 1)) {
                        SensorRecord next = pending.get(last + 1);
                        file.append(next);
                        written++; // uma escrita concluída, mesmo quando veio de pending
                        last = next.getSeq();
                        pending.remove(last); // retira só depois de escrever com sucesso
                        System.out.println("Aplicado: " + next.toLine());
                    }
                } else {
                    // Apenas em memória: perde-se no reinício e pode crescer sem limite.
                    pending.put(r.getSeq(), r);
                    System.out.println("EM ESPERA: " + r.getSeq()
                            + " (falta o registo " + (last + 1) + ")");
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
