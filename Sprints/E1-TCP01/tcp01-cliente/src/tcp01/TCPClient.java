package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {

    public static void main(String[] args) {

        Socket s = null;

        try {

            int serverPort = 7896;

            // Estabelece a ligacao TCP com o servidor
            s = new Socket("localhost", serverPort);

            // Stream usada para enviar objetos
            ObjectOutputStream out =
                    new ObjectOutputStream(
                            s.getOutputStream()
                    );

            // Stream usada para receber a resposta em texto
            DataInputStream in =
                    new DataInputStream(
                            s.getInputStream()
                    );

            // Criacao do Place
            Place place =
                    new Place(
                            "3500-000",
                            "Viseu"
                    );

            // Criacao da Person que contem o Place
            Person person =
                    new Person(
                            "Joao",
                            place,
                            2005
                    );

            // Envia apenas Person.
            // O Place e enviado automaticamente porque faz parte da Person.
            out.writeObject(person);
            out.flush();

            // Espera pela resposta do servidor
            String resposta = in.readUTF();

            System.out.println(
                    "Received: " + resposta
            );

        } catch (UnknownHostException e) {

            System.out.println(
                    "Sock: " + e.getMessage()
            );

        } catch (EOFException e) {

            System.out.println(
                    "EOF: " + e.getMessage()
            );

        } catch (IOException e) {

            System.out.println(
                    "IO: " + e.getMessage()
            );

        } finally {

            if (s != null) {

                try {

                    s.close();

                } catch (IOException e) {

                    System.out.println(
                            "close: " + e.getMessage()
                    );
                }
            }
        }
    }
}