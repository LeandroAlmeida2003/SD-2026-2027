package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            // TTL limita saltos IP, não segundos; TTL 0 limita ao próprio host.
            socket.setTimeToLive(1);
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
                        byte[] message = record.toLine().getBytes(StandardCharsets.UTF_8);
                        if (i == 0) firstSend = System.currentTimeMillis();
                        // Pode esperar por recursos locais; não espera por ACK das réplicas.
                        // Destino é o grupo; não conhece os IPs nem quantas réplicas existem.
                socket.send(new DatagramPacket(message, message.length, group, PORT));
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
                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                // Destino é o grupo; não conhece os IPs nem quantas réplicas existem.
                socket.send(new DatagramPacket(m, m.length, group, PORT));
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
