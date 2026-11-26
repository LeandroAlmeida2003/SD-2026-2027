package tpf;

import java.io.IOException;

import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/** E2-02: regista as mensagens normais de #arquivo pelo mesmo caminho dos comandos. */
final class ArchiveMessages extends ListenerAdapter {
    private final long guildId;
    private final Arquivo arquivo;

    /** Recebe o servidor permitido e o mesmo Arquivo que é usado pelos comandos. */
    ArchiveMessages(long guildId, Arquivo arquivo) {
        this.guildId = guildId;
        this.arquivo = arquivo;
    }

    /** Filtra mensagens e só confirma depois de terminar o registo durável. */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignorar bots evita registar as próprias respostas e criar um ciclo.
        // O teste de tipo exclui mensagens privadas antes de consultar o servidor.
        if (event.getAuthor().isBot()
                || event.isWebhookMessage()
                || !event.isFromType(ChannelType.TEXT)
                || event.getGuild().getIdLong() != guildId
                || !event.getChannel().getName().equals("arquivo")) {
            return;
        }

        try {
            // Autor, canal e texto seguem o mesmo caminho de /registar.
            long lsn = arquivo.registar(event.getAuthor().getId(), event.getChannel().getId(),
                    event.getMessage().getContentRaw());
            // A mensagem de sucesso só é enviada depois de o método de registo retornar.
            event.getChannel().sendMessage("Registo guardado com LSN " + lsn + ".").queue();
        } catch (IOException exception) {
            System.err.println("Falhou o registo: " + exception.getMessage());
            // A falta de confirmação não prova que nada foi escrito: o log pode já existir.
            event.getChannel().sendMessage("Não foi possível confirmar o registo.").queue();
        }
    }
}
