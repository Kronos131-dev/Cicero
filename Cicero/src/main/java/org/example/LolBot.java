package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import io.github.cdimascio.dotenv.Dotenv;
import org.example.command.*;
import org.example.service.AiContextService;
import org.example.service.DailyRecapService;
import org.example.service.MistralService;
import org.example.service.RiotService;
import org.example.service.TavilyService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LolBot extends ListenerAdapter {

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.load();

        // Initialisation des services
        ExecutorService executor = Executors.newFixedThreadPool(10);
        DatabaseManager db = new DatabaseManager();
        RiotService riotService = new RiotService(dotenv.get("RIOT_API_KEY"));
        TavilyService tavilyService = new TavilyService();
        MistralService mistralService = new MistralService(riotService, tavilyService);
        AiContextService aiContextService = new AiContextService(db, riotService);

        // Injection des utilisateurs par défaut
        injectDefaultUsers(db, riotService);

        // Création du contexte global
        BotContext context = new BotContext(db, riotService, mistralService, aiContextService, executor);

        // Gestionnaire de commandes
        CommandManager commandManager = new CommandManager(context);
        commandManager.addCommand(new LinkCommand());
        commandManager.addCommand(new AnalyzeCommand());
        commandManager.addCommand(new LeaderboardCommand());
        commandManager.addCommand(new RankCommand());
        commandManager.addCommand(new AskCommand());
        commandManager.addCommand(new NewAskCommand());
        commandManager.addCommand(new HelpCommand());
        commandManager.addCommand(new PerformanceCommand());
        commandManager.addCommand(new TraceCommand());
        commandManager.addCommand(new TraceTavilyCommand());
        commandManager.addCommand(new SetRecapChannelCommand());

        // Démarrage du bot
        JDA jda = JDABuilder.createDefault(dotenv.get("DISCORD_TOKEN"))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(commandManager)
                .build()
                .awaitReady(); // On attend que le bot soit prêt et connecté

        // Enregistrement des commandes Slash
        jda.updateCommands().addCommands(
                commandManager.getCommandDataList()
        ).queue();
        
        // Démarrage du service de récap quotidien
        new DailyRecapService(db, riotService, jda);

        System.out.println("Bot démarré !");
    }

    private static void injectDefaultUsers(DatabaseManager db, RiotService riotService) {
        String[][] defaultUsers = {
            {"384388224912719874", "Yvain", "FDC"},
            {"1182366478691991653", "RUSHCIEL", "CIEL"},
            {"203249597169008640", "FDC Adrisir", "0059"},
            {"321614400677216257", "Hakuryuu974", "EUW"},
            {"311532666044416000", "THE PGM OF KFC", "EUW"},
            {"374495079676641291", "ADAM", "NIKEL"},
            {"353936065436057630", "3arbi macabre", "DOOM"},
            {"386606978031550465", "SCN1erT", "EUW"}
        };

        System.out.println("Vérification des utilisateurs par défaut...");
        for (String[] user : defaultUsers) {
            String discordId = user[0];
            String gameName = user[1];
            String tagLine = user[2];

            if (db.getUser(discordId) == null) {
                System.out.println("Injection de " + gameName + "#" + tagLine + "...");
                String puuid = riotService.getPuuid(gameName, tagLine);
                if (puuid != null && !puuid.startsWith("Error")) {
                    db.saveUser(discordId, puuid, gameName + "#" + tagLine, "euw1");
                    System.out.println(" -> Succès !");
                } else {
                    System.out.println(" -> Échec : " + puuid);
                }
            }
        }
        System.out.println("Vérification terminée.");
    }
}