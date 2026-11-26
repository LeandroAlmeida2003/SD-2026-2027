package tpf;

import java.io.IOException;
import java.nio.file.Path;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

/** E2-02: prepara o armazenamento local e liga os listeners ao Discord. */
public final class LeaderBot {
    private LeaderBot() {
    }

    /** Valida configuração, carrega a chave e abre o arquivo antes de receber mensagens. */
    public static void main(String[] args) {
        try {
            // As credenciais são lidas do ambiente, em vez de ficarem no código.
            String token = requireEnvironmentVariable("DISCORD_TOKEN");
            long guildId = readGuildId();
            Wire.validate();
            // Guarda os dados na pasta de onde o programa é iniciado.
            // Voltar a usar essa pasta conserva a chave, o log e os chunks.
            Path pasta = Path.of(".");
            byte[] masterKey = MasterKey.loadOrCreate(pasta.resolve("master.key"));
            Arquivo arquivo = new Arquivo(pasta, masterKey);
            // Os comandos e o listener de mensagens recebem o mesmo Arquivo.
            LeaderCommands commands = new LeaderCommands(guildId, arquivo);
            try {
                connect(token, guildId, arquivo, commands);
            } catch (RuntimeException | InterruptedException exception) {
                commands.close();
                try {
                    arquivo.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
            // Ao terminar normalmente, incluindo Ctrl+C, pede para parar rajadas e fechar.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                commands.close();
                fechar(arquivo);
            }, "arquivo-encerramento"));
        } catch (IOException exception) {
            System.err.println("Erro no arquivo local: " + exception.getMessage());
            System.exit(1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("O arranque do LeaderBot foi interrompido.");
            System.exit(1);
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    /** O arquivo tenta selar os registos pendentes antes de libertar os ficheiros. */
    private static void fechar(Arquivo arquivo) {
        try {
            arquivo.close();
        } catch (IOException exception) {
            System.err.println("Erro ao fechar o arquivo: " + exception.getMessage());
        }
    }

    /** Liga o bot, instala os listeners e regista os comandos no servidor configurado. */
    private static void connect(String token, long guildId, Arquivo arquivo, LeaderCommands commands)
            throws InterruptedException {
        JDA jda = null;
        boolean connected = false;
        try {
            // Estes intents permitem receber mensagens e ler o seu conteúdo.
            jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(commands, new ArchiveMessages(guildId, arquivo))
                    .build();
            jda.awaitReady();
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                throw new IllegalStateException("O LeaderBot não encontra o servidor indicado em GUILD_ID.");
            }
            // Publica os comandos slash no servidor escolhido em GUILD_ID.
            guild.updateCommands().addCommands(LeaderCommands.definitions()).complete();
            System.out.printf("LeaderBot ligado ao servidor %s (%s).%n", guild.getName(), guild.getId());
            connected = true;
        } catch (RuntimeException exception) {
            if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
                throw new IllegalStateException("Falhou a ligação ao Discord. Verifica o token e a configuração da aplicação.");
            }
            throw exception;
        } finally {
            if (!connected && jda != null) {
                jda.shutdownNow();
            }
        }
    }

    /** Converte o ID do servidor para número e rejeita uma configuração inválida. */
    private static long readGuildId() {
        String value = requireEnvironmentVariable("GUILD_ID");
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("GUILD_ID tem de ser um ID numérico do Discord.");
        }
    }

    /** Impede arrancar quando falta uma variável obrigatória ou está vazia. */
    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Define a variável de ambiente " + name + " antes de arrancar.");
        }
        return value;
    }
}
