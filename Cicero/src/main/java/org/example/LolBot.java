package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import io.github.cdimascio.dotenv.Dotenv;
import org.example.command.*;
import org.example.service.AiContextService;
import org.example.service.GeminiService;
import org.example.service.RiotService;
import org.example.service.TavilyService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LolBot {

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.load();

        // Initialisation des services
        ExecutorService executor = Executors.newFixedThreadPool(10);
        DatabaseManager db = new DatabaseManager();
        RiotService riotService = new RiotService(dotenv.get("RIOT_API_KEY"));
        TavilyService tavilyService = new TavilyService();
        GeminiService geminiService = new GeminiService(riotService, tavilyService);
        AiContextService aiContextService = new AiContextService(db, riotService);

        // Création du contexte global
        BotContext context = new BotContext(db, riotService, geminiService, aiContextService, executor);

        // Gestionnaire de commandes
        CommandManager commandManager = new CommandManager(context);
        commandManager.addCommand(new LinkCommand());
        commandManager.addCommand(new AnalyzeCommand());
        commandManager.addCommand(new LeaderboardCommand());
        commandManager.addCommand(new RankCommand());
        commandManager.addCommand(new AskCommand());
        commandManager.addCommand(new NewAskCommand());
        commandManager.addCommand(new HelpCommand());

        // Démarrage du bot
        JDA jda = JDABuilder.createDefault(dotenv.get("DISCORD_TOKEN"))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(commandManager)
                .build();

        // Enregistrement des commandes slash auprès de Discord
        commandManager.registerCommands(jda);

        System.out.println("Bot started successfully!");
    }
}