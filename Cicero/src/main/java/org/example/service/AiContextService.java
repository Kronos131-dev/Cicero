package org.example.service;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.example.DatabaseManager;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiContextService {

    private final DatabaseManager db;
    private final RiotService riotService;
    private final ExecutorService executor;

    private static final List<String> ESPORT_LEAGUES = Arrays.asList("lec", "lfl", "lck", "lpl", "lcs", "worlds", "msi");
    private static final List<String> ESPORT_KEYWORDS = Arrays.asList("esport", "compétitif", "pro", "match", "résultat", "score", "méta", "build", "stats", "joueur", "équipe");

    public AiContextService(DatabaseManager db, RiotService riotService, ExecutorService executor) {
        this.db = db;
        this.riotService = riotService;
        this.executor = executor;
    }

    public boolean isEsportQuery(String lowerQ) {
        return ESPORT_LEAGUES.stream().anyMatch(lowerQ::contains) || ESPORT_KEYWORDS.stream().anyMatch(lowerQ::contains);
    }

    public void injectEsportContext(StringBuilder context, String question, String lowerQ) {
        context.append("\n--- CONTEXTE ESPORT ---\n");
        context.append("(Utilise tes connaissances générales sur l'Esport.)");
    }

    public void injectPlayerContext(SlashCommandInteractionEvent event, StringBuilder context, String question, String discordId, boolean isEsport) {
        Pattern pattern = Pattern.compile("<@!?(\\d+)>");
        Matcher matcher = pattern.matcher(question);
        boolean hasMentions = false;
        String lowerQ = question.toLowerCase();

        // 1. Gestion des mentions explicites
        while (matcher.find()) {
            hasMentions = true;
            String mentionedId = matcher.group(1);
            processUserContext(event, context, mentionedId, question);
        }

        // 2. Si pas de mention et pas Esport -> Contexte de l'auteur
        if (!hasMentions && !isEsport) {
            processUserContext(event, context, discordId, question);
        }
    }

    private void processUserContext(SlashCommandInteractionEvent event, StringBuilder context, String targetDiscordId, String question) {
        DatabaseManager.UserRecord user = db.getUser(targetDiscordId);
        User discordUser = event.getJDA().retrieveUserById(targetDiscordId).complete();
        String userName = (discordUser != null) ? discordUser.getName() : "Inconnu";

        if (user == null) {
            context.append("\n[INFO] L'utilisateur ").append(userName).append(" n'a pas lié son compte Riot.\n");
            return;
        }

        try {
            System.out.println("DEBUG: Traitement contexte pour " + userName + " (" + user.puuid + ")");
            context.append("\n--- DONNÉES POUR : ").append(userName).append(" (Région: ").append(user.region).append(") ---\n");

            // Rangs
            Map<String, RiotService.RankInfo> ranks = riotService.getAllRanks(user.puuid, user.region);
            RiotService.RankInfo solo = ranks.get("SOLO");
            context.append("Rang Solo/Duo: ").append(solo.tier).append(" ").append(solo.rank)
                   .append(" (").append(solo.lp).append(" LP) - Winrate: ")
                   .append(solo.wins).append("W/").append(solo.losses).append("L\n");

            // Analyse Intelligente de l'Intention
            MatchSearchCriteria criteria = analyzeIntent(question);
            System.out.println("DEBUG: Intent Analysis -> Deep: " + criteria.isDeepAnalysis + ", Queue: " + criteria.queueId);

            if (criteria.isDeepAnalysis) {
                context.append("\n[ANALYSE DÉTAILLÉE RECHERCHÉE]\n");
                String matchId = riotService.findSpecificMatch(
                    user.puuid, 
                    user.region, 
                    criteria.queueId, 
                    criteria.championName, 
                    criteria.kdaScore, 
                    criteria.indexOffset
                );

                if (matchId != null) {
                    System.out.println("DEBUG: Match trouvé pour analyse: " + matchId);
                    String analysis = riotService.getMatchAnalysis(matchId, user.puuid, user.region);
                    context.append(analysis);
                } else {
                    System.out.println("DEBUG: Aucun match trouvé pour les critères.");
                    context.append("Aucune partie correspondant aux critères trouvée (Champion: " 
                        + criteria.championName + ", KDA: " + criteria.kdaScore + ").\n");
                }
            } else {
                context.append("\n[HISTORIQUE RÉCENT (Résumé 10 games)]\n");
                String history = riotService.getMatchHistorySummary(user.puuid, user.region, criteria.queueId, 10);
                System.out.println("DEBUG: Historique récupéré (taille: " + history.length() + ")");
                context.append(history);
            }

        } catch (Exception e) {
            e.printStackTrace();
            context.append("Erreur récupération données Riot : ").append(e.getMessage()).append("\n");
        }
    }

    private MatchSearchCriteria analyzeIntent(String question) {
        String lowerQ = question.toLowerCase();
        MatchSearchCriteria criteria = new MatchSearchCriteria();

        // 1. Détection du type de file (Queue)
        if (lowerQ.contains("flex")) criteria.queueId = RiotService.QUEUE_FLEX;
        else if (lowerQ.contains("clash")) criteria.queueId = RiotService.QUEUE_CLASH;
        else if (lowerQ.contains("soloq") || lowerQ.contains("solo q") || lowerQ.contains("ranked")) criteria.queueId = RiotService.QUEUE_SOLOQ;

        // 2. Détection Champion
        Pattern champPattern = Pattern.compile("\\b([A-Z][a-z]+)\\b");
        Matcher m = champPattern.matcher(question);
        while (m.find()) {
            String word = m.group(1);
            if (!Arrays.asList("Le", "La", "Les", "Un", "Une", "Mon", "Ma", "Ta", "Sa", "Ce", "Cette", "Game", "Partie", "Match", "Soloq", "Ranked", "Flex", "Clash", "Question", "Profile", "Profil").contains(word)) {
                criteria.championName = word; 
                criteria.isDeepAnalysis = true;
                break; 
            }
        }

        // 3. Détection KDA
        Pattern kdaPattern = Pattern.compile("(\\d{1,2}/\\d{1,2}/\\d{1,2})");
        Matcher kdaMatcher = kdaPattern.matcher(question);
        if (kdaMatcher.find()) {
            criteria.kdaScore = kdaMatcher.group(1);
            criteria.isDeepAnalysis = true;
        }

        // 4. Détection Index
        if (lowerQ.contains("avant-dernière") || lowerQ.contains("avant dernière")) {
            criteria.indexOffset = 1;
            criteria.isDeepAnalysis = true;
        } else if (lowerQ.contains("dernière") || lowerQ.contains("cette game") || lowerQ.contains("analyse la game")) {
            criteria.indexOffset = 0;
            criteria.isDeepAnalysis = true;
        }

        // 5. Mots clés déclencheurs d'analyse (CORRIGÉ)
        // On ne déclenche plus sur "analyse" seul, mais sur des combinaisons spécifiques
        if (lowerQ.contains("pourquoi j'ai perdu") || lowerQ.contains("comment gagner") || lowerQ.contains("analyse ma game") || lowerQ.contains("analyse cette partie")) {
            criteria.isDeepAnalysis = true;
        }
        
        // Si on demande explicitement un profil, on force le mode léger (même si "analyse" est présent)
        if (lowerQ.contains("profil") || lowerQ.contains("profile") || lowerQ.contains("historique") || lowerQ.contains("stats")) {
            criteria.isDeepAnalysis = false;
        }

        return criteria;
    }

    private static class MatchSearchCriteria {
        boolean isDeepAnalysis = false;
        Integer queueId = null;
        String championName = null;
        String kdaScore = null;
        int indexOffset = 0;
    }
}