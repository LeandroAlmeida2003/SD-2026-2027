import java.net.*;
import java.io.*;

public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        int L = 0;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];

            while (true) {

                DatagramPacket request =
                        new DatagramPacket(buffer, buffer.length);

                aSocket.receive(request);

                String mensagem = new String(
                        request.getData(),
                        0,
                        request.getLength()
                );

                System.out.println("Recebido: " + mensagem);

                try {

                    int virgula = mensagem.indexOf(',');

                    if (virgula == -1) {
                        throw new Exception();
                    }

                    String numeroTexto =
                            mensagem.substring(0, virgula);

                    int N = Integer.parseInt(numeroTexto);

                    if (N != L + 1) {

                        String resposta =
                                "waitingfor," + (L + 1);

                        byte[] dados = resposta.getBytes();

                        DatagramPacket reply =
                                new DatagramPacket(
                                        dados,
                                        dados.length,
                                        request.getAddress(),
                                        request.getPort()
                                );

                        aSocket.send(reply);

                    } else {

                        DatagramPacket reply =
                                new DatagramPacket(
                                        request.getData(),
                                        request.getLength(),
                                        request.getAddress(),
                                        request.getPort()
                                );

                        aSocket.send(reply);

                        L = N;
                    }

                    System.out.println("L = " + L);

                } catch (Exception e) {

                    System.out.println("Mensagem mal formada.");

                    String resposta = "error,malformed";

                    byte[] dados = resposta.getBytes();

                    DatagramPacket reply =
                            new DatagramPacket(
                                    dados,
                                    dados.length,
                                    request.getAddress(),
                                    request.getPort()
                            );

                    aSocket.send(reply);
                }
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());

        } finally {
            if (aSocket != null) aSocket.close();
        }
    }
}