import java.net.*;
import java.io.*;
import java.util.Scanner;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner scanner = new Scanner(System.in);

        try {
            aSocket = new DatagramSocket();

            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            System.out.println("Escolha o modo:");
            System.out.println("1 - Automatico");
            System.out.println("2 - Manual");
            System.out.print("Modo: ");

            int modo = Integer.parseInt(scanner.nextLine());

            int numeroAutomatico = 1;

            while (true) {

                System.out.print("Mensagem (ou sair): ");
                String mensagem = scanner.nextLine();

                if (mensagem.equalsIgnoreCase("sair")) {
                    break;
                }

                int numero;

                if (modo == 1) {

                    numero = numeroAutomatico;
                    numeroAutomatico++;

                } else {

                    System.out.print("Numero de sequencia: ");
                    numero = Integer.parseInt(scanner.nextLine());
                }

                String mensagemCompleta = numero + "," + mensagem;

                byte[] m = mensagemCompleta.getBytes();

                DatagramPacket request =
                        new DatagramPacket(
                                m,
                                m.length,
                                aHost,
                                serverPort
                        );

                aSocket.send(request);

                byte[] buffer = new byte[1000];

                DatagramPacket reply =
                        new DatagramPacket(
                                buffer,
                                buffer.length
                        );

                aSocket.receive(reply);

                String resposta =
                        new String(
                                reply.getData(),
                                0,
                                reply.getLength()
                        );

                if (resposta.startsWith("waitingfor,")) {

                    System.out.println(
                            "Mensagem fora de ordem! Servidor respondeu: "
                                    + resposta
                    );

                } else {

                    System.out.println(
                            "Echo: " + resposta
                    );
                }
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());

        } finally {

            if (aSocket != null) {
                aSocket.close();
            }

            scanner.close();
        }
    }
}