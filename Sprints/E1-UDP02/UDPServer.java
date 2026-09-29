import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UDPServer {

    // Mensagens que já foram entregues por ordem
    private static final List<String> listaRececao = new ArrayList<>();

    // Mensagens que chegaram adiantadas
    // Chave = número da mensagem
    // Valor = conteúdo da mensagem
    private static final Map<Integer, String> mensagensTemporarias = new HashMap<>();

    // Guarda as mensagens entregues no datagrama atual,
    // apenas para podermos mostrar o estado no terminal.
    private static final List<String> entreguesNestePasso = new ArrayList<>();

    /**
     * Processes delivered messages
     *
     * @return the last message processed in order
     */
    public static int processDeliveredMessages(
            int nLastMessageInOrder,
            int nCurrentMessage,
            String currentMessage) {

        entreguesNestePasso.clear();

        /*
         * CASO 1:
         * É exatamente a próxima mensagem esperada.
         */
        if (nCurrentMessage == nLastMessageInOrder + 1) {

            String mensagemCompleta =
                    nCurrentMessage + "," + currentMessage;

            listaRececao.add(mensagemCompleta);
            entreguesNestePasso.add(mensagemCompleta);

            nLastMessageInOrder = nCurrentMessage;

            /*
             * ENTREGA EM CASCATA
             *
             * Depois de entregar a mensagem atual, verifica
             * se a próxima já está guardada na estrutura temporária.
             *
             * Enquanto existirem mensagens consecutivas,
             * são retiradas da estrutura temporária e entregues.
             */
            while (mensagensTemporarias.containsKey(
                    nLastMessageInOrder + 1)) {

                int proximaMensagem =
                        nLastMessageInOrder + 1;

                String conteudo =
                        mensagensTemporarias.remove(proximaMensagem);

                String mensagemGuardada =
                        proximaMensagem + "," + conteudo;

                listaRececao.add(mensagemGuardada);
                entreguesNestePasso.add(mensagemGuardada);

                nLastMessageInOrder = proximaMensagem;
            }

        } else {

            /*
             * CASO 2:
             * A mensagem não é a próxima esperada.
             *
             * Fica guardada temporariamente.
             */
            mensagensTemporarias.put(
                    nCurrentMessage,
                    currentMessage
            );
        }

        return nLastMessageInOrder;
    }


    public static void main(String[] args) {

        DatagramSocket aSocket = null;

        /*
         * L = número da última mensagem entregue por ordem.
         *
         * Inicialmente ainda nenhuma mensagem foi entregue.
         */
        int L = 0;

        try {

            aSocket = new DatagramSocket(6789);

            System.out.println("Servidor iniciado na porta 6789.");
            System.out.println("L inicial = " + L);

            while (true) {

                byte[] buffer = new byte[1000];

                DatagramPacket request =
                        new DatagramPacket(
                                buffer,
                                buffer.length
                        );

                aSocket.receive(request);

                String mensagem = new String(
                        request.getData(),
                        0,
                        request.getLength()
                );

                System.out.println();
                System.out.println("----------------------------------");
                System.out.println("Recebido: " + mensagem);

                try {

                    /*
                     * As mensagens têm o formato:
                     *
                     * N,mensagem
                     *
                     * Exemplo:
                     *
                     * 3,mundo
                     */

                    int virgula = mensagem.indexOf(',');

                    if (virgula <= 0) {
                        throw new Exception();
                    }

                    String numeroTexto =
                            mensagem.substring(0, virgula);

                    String conteudo =
                            mensagem.substring(virgula + 1);

                    if (conteudo.isEmpty()) {
                        throw new Exception();
                    }

                    int N =
                            Integer.parseInt(numeroTexto);

                    /*
                     * Guardamos o valor anterior de L.
                     *
                     * Depois conseguimos saber se o processamento
                     * entregou ou não mensagens.
                     */
                    int Lanterior = L;

                    L = processDeliveredMessages(
                            L,
                            N,
                            conteudo
                    );

                    String resposta;

                    /*
                     * Se L não mudou, a mensagem recebida
                     * não pôde ser entregue.
                     */
                    if (L == Lanterior) {

                        resposta =
                                "waitingfor," + (L + 1);

                    } else {

                        /*
                         * A mensagem foi entregue.
                         * Mantemos o comportamento de echo.
                         */
                        resposta = mensagem;
                    }

                    byte[] dados =
                            resposta.getBytes();

                    DatagramPacket reply =
                            new DatagramPacket(
                                    dados,
                                    dados.length,
                                    request.getAddress(),
                                    request.getPort()
                            );

                    aSocket.send(reply);

                    /*
                     * Informação necessária para demonstrar
                     * o funcionamento do servidor.
                     */
                    System.out.println("L = " + L);

                    System.out.println(
                            "Estrutura temporaria = "
                                    + mensagensTemporarias
                    );

                    System.out.println(
                            "Mensagens entregues neste passo = "
                                    + entreguesNestePasso
                    );

                    System.out.println(
                            "Lista de rececao = "
                                    + listaRececao
                    );

                    System.out.println(
                            "Resposta = "
                                    + resposta
                    );


                } catch (Exception e) {

                    /*
                     * Uma mensagem mal formada não pode
                     * terminar o servidor.
                     */
                    System.out.println(
                            "Mensagem mal formada."
                    );

                    String resposta =
                            "error,malformed";

                    byte[] dados =
                            resposta.getBytes();

                    DatagramPacket reply =
                            new DatagramPacket(
                                    dados,
                                    dados.length,
                                    request.getAddress(),
                                    request.getPort()
                            );

                    aSocket.send(reply);

                    System.out.println("L = " + L);

                    System.out.println(
                            "Estrutura temporaria = "
                                    + mensagensTemporarias
                    );

                    System.out.println(
                            "Lista de rececao = "
                                    + listaRececao
                    );
                }
            }

        } catch (SocketException e) {

            System.out.println(
                    "Socket: " + e.getMessage()
            );

        } catch (IOException e) {

            System.out.println(
                    "IO: " + e.getMessage()
            );

        } finally {

            if (aSocket != null) {
                aSocket.close();
            }
        }
    }
}