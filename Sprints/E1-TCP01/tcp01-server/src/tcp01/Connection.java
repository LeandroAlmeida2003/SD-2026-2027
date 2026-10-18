package tcp01;

import java.io.*;
import java.net.*;

public class Connection extends Thread {

    ObjectInputStream in;
    DataOutputStream out;
    Socket clientSocket;

    public Connection(Socket aClientSocket) {

        try {

            clientSocket = aClientSocket;

            // O cliente envia objetos neste sentido
            in = new ObjectInputStream(
                    clientSocket.getInputStream()
            );

            // O servidor responde com texto
            out = new DataOutputStream(
                    clientSocket.getOutputStream()
            );

            // Inicia run() numa nova thread
            this.start();

        } catch (IOException e) {

            System.out.println(
                    "Connection: " + e.getMessage()
            );
        }
    }

    @Override
    public void run() {

        try {

            // readObject() devolve Object
            Object objeto = in.readObject();

            // Cast para Person
            Person person =
                    (Person) objeto;

            System.out.println(
                    "Nome: " + person.getName()
            );

            System.out.println(
                    "Ano: " + person.getYear()
            );

            System.out.println(
                    "Codigo postal: "
                            + person.getPlace().getPostalCode()
            );

            System.out.println(
                    "Localidade: "
                            + person.getPlace().getLocality()
            );

            // A ficha pede para devolver a localidade
            String resposta =
                    person.getPlace().getLocality();

            out.writeUTF(resposta);

        } catch (ClassNotFoundException e) {

            System.out.println(
                    "Class: " + e.getMessage()
            );

        } catch (EOFException e) {

            System.out.println(
                    "EOF: " + e.getMessage()
            );

        } catch (IOException e) {

            System.out.println(
                    "IO: " + e.getMessage()
            );

        } catch (ClassCastException e) {

            System.out.println(
                    "Cast: " + e.getMessage()
            );

        } finally {

            try {

                clientSocket.close();

            } catch (IOException e) {

                System.out.println(
                        "Erro ao fechar socket: "
                                + e.getMessage()
                );
            }
        }
    }
}