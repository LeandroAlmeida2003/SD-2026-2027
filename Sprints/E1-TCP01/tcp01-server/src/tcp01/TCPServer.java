package tcp01;

import java.io.*;
import java.net.*;

public class TCPServer {

    public static void main(String[] args) {

        try {

            int serverPort = 7896;

            ServerSocket listenSocket =
                    new ServerSocket(serverPort);

            System.out.println(
                    "Servidor iniciado na porta "
                            + serverPort
            );

            while (true) {

                // Bloqueia ate um cliente estabelecer ligacao
                Socket clientSocket =
                        listenSocket.accept();

                System.out.println(
                        "Cliente ligado."
                );

                // Cada cliente e processado numa thread
                new Connection(clientSocket);
            }

        } catch (IOException e) {

            System.out.println(
                    "Listen: " + e.getMessage()
            );
        }
    }
}