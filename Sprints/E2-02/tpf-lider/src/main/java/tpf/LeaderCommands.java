package tpf;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * E2-02: trata /registar e acrescenta /rajada para testar a escrita local do líder.
 * Os comandos dos próximos sprints mantêm a resposta provisória prevista na issue.
 */
final class LeaderCommands extends ListenerAdapter implements AutoCloseable {
    // Estes comandos exigem o cargo com o nome exato admin-arquivo.
    private static final Set<String> ADMIN_COMMANDS =
            Set.of("config", "replica-token", "replica-remover", "rajada");

    private final long guildId;
    private final Arquivo arquivo;
    // Executa as rajadas em segundo plano e processa uma de cada vez.
    private final ExecutorService rajadas = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "arquivo-rajadas");
        thread.setDaemon(true);
        return thread;
    });

    /** Partilha a mesma instância de Arquivo que recebe as mensagens de #arquivo. */
    LeaderCommands(long guildId, Arquivo arquivo) {
        this.guildId = guildId;
        this.arquivo = arquivo;
    }

    /** Define os comandos e os nomes/tipos dos argumentos que aparecem no Discord. */
    static List<CommandData> definitions() {
        return List.of(
                Commands.slash("registar", "Registar texto")
                        .addOptions(new OptionData(OptionType.STRING, "texto", "Texto a registar", true)
                                .setMaxLength(1800)),
                Commands.slash("estado", "Consultar o estado do arquivo"),
                Commands.slash("config", "Configurar o arquivo")
                        .addSubcommands(new SubcommandData("r", "Configurar o número de réplicas")
                                .addOption(OptionType.INTEGER, "n", "Número de réplicas", true)),
                Commands.slash("recuperar", "Recuperar um intervalo de registos")
                        .addOption(OptionType.INTEGER, "de", "Primeiro LSN", true)
                        .addOption(OptionType.INTEGER, "ate", "Último LSN", true),
                Commands.slash("replica-token", "Gerar um token para uma réplica"),
                Commands.slash("replica-remover", "Remover uma réplica")
                        .addOptions(new OptionData(OptionType.STRING, "id", "ID da réplica", true)
                                .setMaxLength(1800)),
                Commands.slash("rajada", "Gerar registos sintéticos")
                        .addOption(OptionType.INTEGER, "n", "Número de registos", true)
                        .addOption(OptionType.INTEGER, "intervaloms", "Intervalo em milissegundos", true));
    }

    /** Valida servidor e permissões antes de encaminhar o comando. */
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getGuild().getIdLong() != guildId) {
            return;
        }
        if (ADMIN_COMMANDS.contains(event.getName()) && !isArchiveAdmin(event.getMember())) {
            reply(event, "Este comando requer o cargo admin-arquivo.");
            return;
        }

        try {
            if (event.getName().equals("registar")) {
                registar(event, textOption(event, "texto"));
                return;
            }
            if (event.getName().equals("rajada")) {
                long n = integerOption(event, "n", 1);
                long intervalo = integerOption(event, "intervaloms", 0);
                // Adia a resposta enquanto a rajada trabalha; true torna-a privada ao autor.
                event.deferReply(true).queue(hook -> rajadas.execute(() ->
                        hook.editOriginal(executarRajada(event, n, intervalo)).queue()));
                return;
            }
            // Os restantes comandos ainda não executam a lógica dos sprints seguintes.
            String arguments = describeArguments(event);
            reply(event, "/" + event.getFullCommandName() + " recebido" + arguments
                    + ". Funcionalidade por implementar.");
        } catch (IllegalArgumentException exception) {
            reply(event, exception.getMessage());
        }
    }

    /** Guarda pelo caminho durável e só depois confirma o LSN ao utilizador. */
    private void registar(SlashCommandInteractionEvent event, String texto) {
        // Adiar a resposta confirma só a receção do pedido, não a escrita no log.
        event.deferReply(true).queue(hook -> {
            try {
                // Arquivo força a linha para disco antes de devolver este LSN.
                long lsn = arquivo.registar(event.getUser().getId(), event.getChannel().getId(), texto);
                hook.editOriginal("Registo guardado com LSN " + lsn + ".").queue();
            } catch (IOException exception) {
                System.err.println("Falhou o registo: " + exception.getMessage());
                hook.editOriginal("Não foi possível confirmar o registo.").queue();
            }
        });
    }

    /** Gera n registos sintéticos, com uma espera opcional entre registos. */
    private String executarRajada(SlashCommandInteractionEvent event, long n, long intervalo) {
        long gravados = 0;
        try {
            while (gravados < n) {
                // Os 512 caracteres extra permitem atingir o tamanho de vários chunks.
                String texto = "Registo sintético " + (gravados + 1) + " " + "x".repeat(512);
                // Usa as mesmas regras de escrita, LSN e selagem das mensagens reais.
                arquivo.registar(event.getUser().getId(), event.getChannel().getId(), texto);
                gravados++;
                if (gravados < n && intervalo > 0) {
                    Thread.sleep(intervalo);
                }
            }
            return "Rajada concluída. Registos guardados: " + gravados + ".";
        } catch (IOException exception) {
            System.err.println("Falhou a rajada: " + exception.getMessage());
            return "Falhou a rajada. Registos confirmados: " + gravados + ".";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Rajada interrompida após " + gravados + " registos guardados.";
        }
    }

    /** Confirma se o membro tem o cargo admin-arquivo. */
    private static boolean isArchiveAdmin(Member member) {
        return member != null && member.getRoles().stream()
                .anyMatch(role -> role.getName().equals("admin-arquivo"));
    }

    /** Pede a interrupção da rajada ativa e impede executar as que estão na fila. */
    @Override
    public void close() {
        rajadas.shutdownNow();
    }

    /** Valida os argumentos dos comandos provisórios, incluindo intervalos de LSN. */
    private static String describeArguments(SlashCommandInteractionEvent event) {
        return switch (event.getName()) {
            case "estado", "replica-token" -> "";
            case "config" -> " com n=" + integerOption(event, "n", 1);
            case "recuperar" -> {
                long from = integerOption(event, "de", 1);
                long to = integerOption(event, "ate", from);
                yield " com de=" + from + ", ate=" + to;
            }
            case "replica-remover" -> " com id=" + textOption(event, "id");
            default -> throw new IllegalArgumentException("Comando desconhecido.");
        };
    }

    /** Exige um argumento de texto presente e que não contenha apenas espaços. */
    private static String textOption(SlashCommandInteractionEvent event, String name) {
        OptionMapping option = event.getOption(name);
        if (option == null || option.getAsString().isBlank()) {
            throw new IllegalArgumentException("Preenche o argumento " + name + ".");
        }
        return option.getAsString();
    }

    /** Exige um inteiro presente e igual ou superior ao mínimo indicado. */
    private static long integerOption(SlashCommandInteractionEvent event, String name, long minimum) {
        OptionMapping option = event.getOption(name);
        if (option == null || option.getAsLong() < minimum) {
            throw new IllegalArgumentException("O argumento " + name + " tem de ser um inteiro igual ou superior a "
                    + minimum + ".");
        }
        return option.getAsLong();
    }

    /** Responde só ao autor e evita menções provocadas pelo texto da resposta. */
    private static void reply(SlashCommandInteractionEvent event, String content) {
        event.reply(new MessageCreateBuilder()
                        .setContent(content)
                        .setAllowedMentions(Set.of())
                        .build())
                .setEphemeral(true)
                .queue();
    }
}
