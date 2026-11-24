package tpf;

import java.io.IOException;

import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

final class ArchiveMessages extends ListenerAdapter {
    private final long guildId;
    private final Arquivo arquivo;

    ArchiveMessages(long guildId, Arquivo arquivo) {
        this.guildId = guildId;
        this.arquivo = arquivo;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()
                || event.isWebhookMessage()
                || !event.isFromType(ChannelType.TEXT)
                || event.getGuild().getIdLong() != guildId
                || !event.getChannel().getName().equals("arquivo")) {
            return;
        }

        try {
            long lsn = arquivo.registar(event.getAuthor().getId(), event.getChannel().getId(),
                    event.getMessage().getContentRaw());
            event.getChannel().sendMessage("Registo guardado com LSN " + lsn + ".").queue();
        } catch (IOException exception) {
            System.err.println("Falhou o registo: " + exception.getMessage());
            event.getChannel().sendMessage("Falhou a escrita do registo.").queue();
        }
    }
}
