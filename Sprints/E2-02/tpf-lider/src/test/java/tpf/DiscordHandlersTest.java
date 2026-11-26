package tpf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

/**
 * E2-02: simula os eventos e respostas do Discord com Mockito, sem token nem rede.
 * A escrita usa um Arquivo real numa pasta temporária, para verificar também o log.
 */
public class DiscordHandlersTest {
    private static final long GUILD_ID = 123;

    // Cada teste isola os seus ficheiros; não altera os dados do bot nesta pasta.
    @Rule
    public final TemporaryFolder pasta = new TemporaryFolder();

    /** No momento de responder ao slash, a linha já tem de existir com todos os dados no log. */
    @Test
    public void confirmsSlashRecordOnlyAfterItExistsInTheLog() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        var event = slash("registar");
        when(event.getOption("texto").getAsString()).thenReturn("Olá;\nDiscord");
        InteractionHook hook = mock(InteractionHook.class, RETURNS_DEEP_STUBS);
        acknowledge(event, hook);
        when(hook.editOriginal(anyString())).thenAnswer(invocation -> {
            Registo registo = Registo.ler(Files.readString(raiz.resolve("registos.log")).strip());
            assertEquals(1, registo.lsn());
            assertEquals("autor", registo.autor());
            assertEquals("canal", registo.canal());
            assertEquals("Olá;\nDiscord", registo.texto());
            return mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class);
        });

        try (Arquivo arquivo = new Arquivo(raiz, new byte[32]);
                LeaderCommands commands = new LeaderCommands(GUILD_ID, arquivo)) {
            commands.onSlashCommandInteraction(event);
            verify(hook).editOriginal("Registo guardado com LSN 1.");
        }
    }

    /** Uma mensagem normal de #arquivo também só é confirmada depois de aparecer no log. */
    @Test
    public void confirmsArchiveMessageThroughTheSameDurableLog() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        var event = message();
        when(event.getChannel().sendMessage(anyString())).thenAnswer(invocation -> {
            Registo registo = Registo.ler(Files.readString(raiz.resolve("registos.log")).strip());
            assertEquals(1, registo.lsn());
            assertEquals("autor", registo.autor());
            assertEquals("canal", registo.canal());
            assertEquals("mensagem", registo.texto());
            return mock(MessageCreateAction.class);
        });

        try (Arquivo arquivo = new Arquivo(raiz, new byte[32])) {
            new ArchiveMessages(GUILD_ID, arquivo).onMessageReceived(event);
            verify(event.getChannel()).sendMessage("Registo guardado com LSN 1.");
        }
    }

    /** Mensagens fora dos filtros não podem produzir resposta nem linhas no log. */
    @Test
    public void ignoresBotsWebhooksOtherChannelsOtherGuildsAndPrivateMessages() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        try (Arquivo arquivo = new Arquivo(raiz, new byte[32])) {
            ArchiveMessages listener = new ArchiveMessages(GUILD_ID, arquivo);
            var bot = message();
            when(bot.getAuthor().isBot()).thenReturn(true);
            var webhook = message();
            when(webhook.isWebhookMessage()).thenReturn(true);
            var outroCanal = message();
            when(outroCanal.getChannel().getName()).thenReturn("geral");
            var outroServidor = message();
            when(outroServidor.getGuild().getIdLong()).thenReturn(GUILD_ID + 1);
            var privada = message();
            when(privada.isFromType(ChannelType.TEXT)).thenReturn(false);
            for (var event : List.of(bot, webhook, outroCanal, outroServidor, privada)) {
                listener.onMessageReceived(event);
                verify(event.getChannel(), never()).sendMessage(anyString());
            }
            assertEquals(0, Files.size(raiz.resolve("registos.log")));
        }
    }

    /** Sem admin-arquivo, todos os comandos reservados são recusados antes de iniciar trabalho. */
    @Test
    public void refusesEveryAdminCommandWithoutTheRequiredRole() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        try (Arquivo arquivo = new Arquivo(raiz, new byte[32]);
                LeaderCommands commands = new LeaderCommands(GUILD_ID, arquivo)) {
            for (String name : List.of("config", "replica-token", "replica-remover", "rajada")) {
                var event = slash(name);
                commands.onSlashCommandInteraction(event);
                assertReply(event, "Este comando requer o cargo admin-arquivo.");
                verify(event, never()).deferReply(true);
            }
            assertEquals(0, Files.size(raiz.resolve("registos.log")));
        }
    }

    /** Simula /rajada 500 0 e espera pela conclusão para verificar LSN e vários chunks. */
    @Test
    public void adminBurstWritesFiveHundredRecordsAndSealsSeveralChunks() throws Exception {
        Path raiz = pasta.getRoot().toPath();
        var event = slash("rajada");
        admin(event);
        when(event.getOption("n").getAsLong()).thenReturn(500L);
        when(event.getOption("intervaloms").getAsLong()).thenReturn(0L);
        InteractionHook hook = mock(InteractionHook.class, RETURNS_DEEP_STUBS);
        acknowledge(event, hook);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> reply = new AtomicReference<>();
        when(hook.editOriginal(anyString())).thenAnswer(invocation -> {
            reply.set(invocation.getArgument(0));
            finished.countDown();
            return mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class);
        });

        try (Arquivo arquivo = new Arquivo(raiz, new byte[32]);
                LeaderCommands commands = new LeaderCommands(GUILD_ID, arquivo)) {
            commands.onSlashCommandInteraction(event);
            assertTrue("A rajada não terminou", finished.await(30, TimeUnit.SECONDS));
            assertEquals("Rajada concluída. Registos guardados: 500.", reply.get());
            List<Registo> registos = Files.readAllLines(raiz.resolve("registos.log")).stream()
                    .map(Registo::ler).toList();
            assertEquals(500, registos.size());
            for (int i = 0; i < registos.size(); i++) {
                assertEquals(i + 1, registos.get(i).lsn());
                assertEquals("autor", registos.get(i).autor());
                assertEquals("canal", registos.get(i).canal());
            }
            try (var files = Files.list(raiz.resolve("selados"))) {
                assertTrue(files.filter(path -> path.toString().endsWith(".bin")).count() > 1);
            }
        }
    }

    /** Recusa contagens e intervalos inválidos, incluindo um intervalo de recuperação invertido. */
    @Test
    public void rejectsInvalidBurstArgumentsAndInvertedRecoveryInterval() throws Exception {
        try (Arquivo arquivo = new Arquivo(pasta.getRoot().toPath(), new byte[32]);
                LeaderCommands commands = new LeaderCommands(GUILD_ID, arquivo)) {
            for (long[] args : List.of(new long[]{0, 0}, new long[]{1, -1})) {
                var event = slash("rajada");
                admin(event);
                when(event.getOption("n").getAsLong()).thenReturn(args[0]);
                when(event.getOption("intervaloms").getAsLong()).thenReturn(args[1]);
                commands.onSlashCommandInteraction(event);
                verify(event, never()).deferReply(true);
                verify(event).reply(any(MessageCreateData.class));
            }
            var event = slash("recuperar");
            when(event.getOption("de").getAsLong()).thenReturn(10L);
            when(event.getOption("ate").getAsLong()).thenReturn(2L);
            commands.onSlashCommandInteraction(event);
            assertReply(event, "O argumento ate tem de ser um inteiro igual ou superior a 10.");
        }
    }

    /** Confirma que os comandos dos próximos sprints conservam a resposta provisória. */
    @Test
    public void retainsProvisionalResponsesForTheOtherCommands() throws Exception {
        try (Arquivo arquivo = new Arquivo(pasta.getRoot().toPath(), new byte[32]);
                LeaderCommands commands = new LeaderCommands(GUILD_ID, arquivo)) {
            for (String name : List.of("estado", "config", "recuperar", "replica-token", "replica-remover")) {
                var event = slash(name);
                admin(event);
                when(event.getOption("n").getAsLong()).thenReturn(3L);
                when(event.getOption("de").getAsLong()).thenReturn(1L);
                when(event.getOption("ate").getAsLong()).thenReturn(2L);
                when(event.getOption("id").getAsString()).thenReturn("replica-1");
                commands.onSlashCommandInteraction(event);
                var reply = ArgumentCaptor.forClass(MessageCreateData.class);
                verify(event).reply(reply.capture());
                assertTrue(reply.getValue().getContent().endsWith("Funcionalidade por implementar."));
            }
        }
    }

    /** Prepara um comando simulado com servidor, autor, canal e resposta privada. */
    private static SlashCommandInteractionEvent slash(String name) {
        var event = mock(SlashCommandInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getGuild().getIdLong()).thenReturn(GUILD_ID);
        when(event.getName()).thenReturn(name);
        when(event.getFullCommandName()).thenReturn(name);
        when(event.getUser().getId()).thenReturn("autor");
        when(event.getChannel().getId()).thenReturn("canal");
        when(event.getMember().getRoles()).thenReturn(List.of());
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(any(MessageCreateData.class))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);
        return event;
    }

    /** Prepara uma mensagem humana simulada em #arquivo no servidor de teste. */
    private static MessageReceivedEvent message() {
        var event = mock(MessageReceivedEvent.class, RETURNS_DEEP_STUBS);
        when(event.isFromType(ChannelType.TEXT)).thenReturn(true);
        when(event.getGuild().getIdLong()).thenReturn(GUILD_ID);
        when(event.getAuthor().getId()).thenReturn("autor");
        when(event.getChannel().getId()).thenReturn("canal");
        when(event.getChannel().getName()).thenReturn("arquivo");
        when(event.getMessage().getContentRaw()).thenReturn("mensagem");
        return event;
    }

    /** Atribui ao membro simulado o cargo exato que permite executar /rajada. */
    private static void admin(SlashCommandInteractionEvent event) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn("admin-arquivo");
        when(event.getMember().getRoles()).thenReturn(List.of(role));
    }

    /** Executa o callback da resposta adiada no teste, sem fazer uma chamada ao Discord. */
    private static void acknowledge(SlashCommandInteractionEvent event, InteractionHook hook) {
        ReplyCallbackAction action = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenReturn(action);
        doAnswer(invocation -> {
            Consumer<InteractionHook> callback = invocation.getArgument(0);
            callback.accept(hook);
            return null;
        }).when(action).queue(any());
    }

    /** Captura o conteúdo enviado ao Discord simulado e compara-o com a resposta esperada. */
    private static void assertReply(SlashCommandInteractionEvent event, String expected) {
        var reply = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(event).reply(reply.capture());
        assertEquals(expected, reply.getValue().getContent());
    }
}
