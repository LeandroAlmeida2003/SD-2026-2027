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

final class LeaderCommands extends ListenerAdapter {
    private static final Set<String> ADMIN_COMMANDS =
            Set.of("config", "replica-token", "replica-remover", "rajada");

    private final long guildId;
    private final Arquivo arquivo;
    private final ExecutorService rajadas = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "arquivo-rajadas");
        thread.setDaemon(true);
        return thread;
    });

    LeaderCommands(long guildId, Arquivo arquivo) {
        this.guildId = guildId;
        this.arquivo = arquivo;
    }

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
                event.deferReply(true).queue(hook -> rajadas.execute(() ->
                        hook.editOriginal(executarRajada(event, n, intervalo)).queue()));
                return;
            }
            String arguments = describeArguments(event);
            reply(event, "/" + event.getFullCommandName() + " recebido" + arguments
                    + ". Funcionalidade por implementar.");
        } catch (IllegalArgumentException exception) {
            reply(event, exception.getMessage());
        }
    }

    private void registar(SlashCommandInteractionEvent event, String texto) {
        event.deferReply(true).queue(hook -> {
            try {
                long lsn = arquivo.registar(event.getUser().getId(), event.getChannel().getId(), texto);
                hook.editOriginal("Registo guardado com LSN " + lsn + ".").queue();
            } catch (IOException exception) {
                System.err.println("Falhou o registo: " + exception.getMessage());
                hook.editOriginal("Falhou a escrita do registo.").queue();
            }
        });
    }

    private String executarRajada(SlashCommandInteractionEvent event, long n, long intervalo) {
        long gravados = 0;
        try {
            while (gravados < n) {
                String texto = "Registo sintético " + (gravados + 1) + " " + "x".repeat(512);
                arquivo.registar(event.getUser().getId(), event.getChannel().getId(), texto);
                gravados++;
                if (gravados < n && intervalo > 0) {
                    Thread.sleep(intervalo);
                }
            }
            return "Rajada concluída. Registos guardados: " + gravados + ".";
        } catch (IOException exception) {
            System.err.println("Falhou a rajada: " + exception.getMessage());
            return "Falhou a rajada após " + gravados + " registos guardados.";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Rajada interrompida após " + gravados + " registos guardados.";
        }
    }

    private static boolean isArchiveAdmin(Member member) {
        return member != null && member.getRoles().stream()
                .anyMatch(role -> role.getName().equals("admin-arquivo"));
    }

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

    private static String textOption(SlashCommandInteractionEvent event, String name) {
        OptionMapping option = event.getOption(name);
        if (option == null || option.getAsString().isBlank()) {
            throw new IllegalArgumentException("Preenche o argumento " + name + ".");
        }
        return option.getAsString();
    }

    private static long integerOption(SlashCommandInteractionEvent event, String name, long minimum) {
        OptionMapping option = event.getOption(name);
        if (option == null || option.getAsLong() < minimum) {
            throw new IllegalArgumentException("O argumento " + name + " tem de ser um inteiro igual ou superior a "
                    + minimum + ".");
        }
        return option.getAsLong();
    }

    private static void reply(SlashCommandInteractionEvent event, String content) {
        event.reply(new MessageCreateBuilder()
                        .setContent(content)
                        .setAllowedMentions(Set.of())
                        .build())
                .setEphemeral(true)
                .queue();
    }
}
