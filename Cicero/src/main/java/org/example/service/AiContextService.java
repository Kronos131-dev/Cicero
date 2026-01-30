package org.example.service;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.example.DatabaseManager;
import org.example.service.ai.PromptRegistry;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for analyzing user intent and building a relevant context for the AI.
 * Optimized for Agentic workflow: Identifies entities and intent, but delegates data fetching to the Agent tools.
 */
public class AiContextService {

    private final DatabaseManager db;
    private final RiotService riotService;

    private static final List<String> ESPORT_LEAGUES = Arrays.asList("lec", "lfl", "lck", "lpl", "lcs", "worlds", "msi");
    private static final List<String> ESPORT_KEYWORDS = Arrays.asList("esport", "compétitif", "pro", "match", "résultat", "score", "méta", "build", "stats", "joueur", "équipe", "niveau de", "stats de");
    private static final List<String> SEARCH_KEYWORDS = Arrays.asList("build", "méta", "runes", "counter", "news", "patch", "tierlist", "meilleur", "stats de");
    private static final List<String> SELF_KEYWORDS = Arrays.asList("je", "moi", "mon", "ma", "mes", "suis", "ai-je", "j'ai", "pourquoi", "comment", "analyse");

    public AiContextService(DatabaseManager db, RiotService riotService) {
        this.db = db;
        this.riotService = riotService;
    }

    public ContextPayload buildContext(SlashCommandInteractionEvent event, String question) {
        return buildContext(event, question, false);
    }

    /**
     * Analyzes the user's question to build a comprehensive context string and determine AI configuration.
     * @param isAnalyzeCommand If true, forces deep analysis mode and specific instructions for /analyze.
     * @return A ContextPayload containing the context string and the analyzed intent.
     */
    public ContextPayload buildContext(SlashCommandInteractionEvent event, String question, boolean isAnalyzeCommand) {
        StringBuilder context = new StringBuilder();
        String lowerQ = question.toLowerCase();
        
        Intent intent = analyzeIntent(lowerQ);

        if (isAnalyzeCommand) {
            intent.isDeepAnalysis = true;
            intent.useSearch = true;
            if (intent.persona == Persona.DEFAULT) {
                intent.persona = Persona.SERIOUS_COACH;
            }
        }

        context.append(getSystemPromptForIntent(intent)).append("\n");
        context.append("Date du jour : ").append(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("\n");

        if (isAnalyzeCommand) {
             context.append(PromptRegistry.ANALYZE_COMMAND_CONTEXT);
        }

        injectEntityContext(event, context, question, intent, isAnalyzeCommand);

        return new ContextPayload(context.toString(), intent, findTargets(event, question, intent, isAnalyzeCommand));
    }

    private void injectEntityContext(SlashCommandInteractionEvent event, StringBuilder context, String question, Intent intent, boolean isAnalyzeCommand) {
        List<Target> targets = findTargets(event, question, intent, isAnalyzeCommand);
        
        if (!targets.isEmpty()) {
            context.append("\n[DONNÉES DES JOUEURS MENTIONNÉS / CIBLES]\n");
            context.append("Voici la correspondance entre les mentions Discord (@Nom) et les données Riot (PUUID) :\n");
            
            for (Target target : targets) {
                context.append("--------------------------------------------------\n");
                context.append("👤 MENTION: ").append(target.discordLabel).append("\n");
                context.append("   • Riot ID: ").append(target.riotId).append("\n");
                context.append("   • Région: ").append(target.region).append("\n");
                context.append("   • PUUID: ").append(target.puuid).append("\n");
            }
            context.append("--------------------------------------------------\n");
            context.append("INSTRUCTION CLÉ: Si la question concerne un de ces joueurs, utilise IMMÉDIATEMENT son PUUID et sa Région pour appeler les outils (getLastMatchId, getRank, etc.).\n");
            context.append("Si la question demande de COMPARER deux joueurs, appelle les outils pour CHACUN des PUUIDs ci-dessus.\n");
        }

        if (intent.queueId != null) {
            context.append("- File de jeu détectée (QueueID): ").append(intent.queueId).append("\n");
        }
        
        if (intent.championName != null) {
             context.append("- Champion détecté: ").append(intent.championName).append("\n");
        }
        
        if (intent.kdaScore != null) {
            context.append("- KDA cible: ").append(intent.kdaScore).append("\n");
        }
    }

    private List<Target> findTargets(SlashCommandInteractionEvent event, String question, Intent intent, boolean isAnalyzeCommand) {
        List<Target> targets = new ArrayList<>();
        
        // 1. Recherche des mentions Discord <@123456>
        Pattern mentionPattern = Pattern.compile("<@!?(\\d+)>");
        Matcher mentionMatcher = mentionPattern.matcher(question);
        while (mentionMatcher.find()) {
            String id = mentionMatcher.group(1);
            DatabaseManager.UserRecord user = db.getUser(id);
            if (user != null) {
                // On essaie de récupérer le nom Discord pour que l'IA fasse le lien visuel
                String discordName = "Utilisateur";
                try {
                    User discordUser = event.getJDA().retrieveUserById(id).complete();
                    discordName = "@" + discordUser.getName();
                } catch (Exception e) {
                    discordName = "@" + user.summonerName; // Fallback
                }
                
                targets.add(new Target(user.puuid, user.region, discordName, user.summonerName));
            } else {
                // NOUVEAU : Si l'utilisateur mentionné n'est pas en base, on essaie de le récupérer via l'API Discord
                // et on l'ajoute comme cible potentielle (sans PUUID pour l'instant)
                try {
                    User discordUser = event.getJDA().retrieveUserById(id).complete();
                    String discordName = "@" + discordUser.getName();
                    // On ajoute une cible "inconnue" pour que l'IA sache qu'il y a une mention mais pas de lien Riot
                    targets.add(new Target(null, null, discordName, "Inconnu"));
                } catch (Exception ignored) {}
            }
        }

        // 2. Recherche des Riot ID explicites dans le texte (Name#Tag)
        Pattern riotIdPattern = Pattern.compile("\\b([a-zA-Z0-9 ]{3,16})#([a-zA-Z0-9]{3,5})\\b");
        Matcher riotIdMatcher = riotIdPattern.matcher(question);
        while (riotIdMatcher.find()) {
            try {
                String name = riotIdMatcher.group(1);
                String tag = riotIdMatcher.group(2);
                String fullName = name + "#" + tag;
                String puuid = riotService.getPuuid(name, tag);
                if (!puuid.startsWith("Error")) {
                    targets.add(new Target(puuid, "euw1", fullName, fullName));
                }
            } catch (Exception ignored) {}
        }

        // 3. Gestion de l'auto-référence ("Moi", "Je") ou commande /analyze implicite
        if (targets.isEmpty()) {
            boolean isSelfRelated = isSelfRelated(question);
            // Si c'est une commande /analyze explicite OU si la question contient "je/moi"
            if (isAnalyzeCommand || (authorIsRegistered(event) && isSelfRelated)) {
                DatabaseManager.UserRecord author = db.getUser(event.getUser().getId());
                if (author != null) {
                    targets.add(new Target(author.puuid, author.region, "Moi (L'utilisateur)", author.summonerName));
                }
            }
        }
        
        return targets;
    }

    private boolean authorIsRegistered(SlashCommandInteractionEvent event) {
        return db.getUser(event.getUser().getId()) != null;
    }

    private boolean isSelfRelated(String question) {
        String lowerQ = question.toLowerCase();
        for (String keyword : SELF_KEYWORDS) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b");
            if (p.matcher(lowerQ).find()) {
                return true;
            }
        }
        return false;
    }

    public Intent analyzeIntent(String lowerQ) {
        Intent intent = new Intent();
        
        intent.isEsport = ESPORT_LEAGUES.stream().anyMatch(lowerQ::contains) || ESPORT_KEYWORDS.stream().anyMatch(lowerQ::contains);
        
        Pattern proPlayerPattern = Pattern.compile("(stats|niveau|avis|infos?)\\s+d[eu]\\s+([a-zA-Z0-9]+)");
        Matcher m = proPlayerPattern.matcher(lowerQ);
        if (m.find()) {
            intent.isEsport = true; 
        }

        intent.useSearch = SEARCH_KEYWORDS.stream().anyMatch(lowerQ::contains) || intent.isEsport;

        if (lowerQ.contains("pourquoi") || lowerQ.contains("comment") || lowerQ.contains("analyse") || lowerQ.contains("détaillé")) {
            intent.isDeepAnalysis = true;
            intent.useSearch = true;
        }
        if (lowerQ.contains("profil") || lowerQ.contains("historique") || lowerQ.contains("stats générales")) {
            intent.isDeepAnalysis = false;
        }

        if (lowerQ.contains("sérieux") || lowerQ.contains("précis")) intent.persona = Persona.SERIOUS_COACH;
        else if (lowerQ.contains("fun") || lowerQ.contains("taquin") || lowerQ.contains("avis")) intent.persona = Persona.TAQUIN_ANALYST;
        else if (intent.isDeepAnalysis) intent.persona = Persona.SERIOUS_COACH;
        else if (intent.isEsport) intent.persona = Persona.ESPORT_EXPERT;

        if (lowerQ.contains("flex")) intent.queueId = RiotService.QUEUE_FLEX;
        else if (lowerQ.contains("clash")) intent.queueId = RiotService.QUEUE_CLASH;
        else if (lowerQ.contains("soloq") || lowerQ.contains("solo q") || lowerQ.contains("ranked")) intent.queueId = RiotService.QUEUE_SOLOQ;
        else if (lowerQ.contains("aram")) intent.queueId = RiotService.QUEUE_ARAM;
        
        return intent;
    }

    private String getSystemPromptForIntent(Intent intent) {
        String base = switch (intent.persona) {
            case SERIOUS_COACH -> PromptRegistry.COACH_PERSONA;
            case TAQUIN_ANALYST -> PromptRegistry.TAQUIN_PERSONA;
            case ESPORT_EXPERT -> PromptRegistry.ESPORT_PERSONA;
            default -> PromptRegistry.BASE_SYSTEM_PROMPT;
        };
        
        String strategy = PromptRegistry.STRATEGY_INSTRUCTIONS_GENERIC;
        if (intent.isEsport) {
            strategy = PromptRegistry.STRATEGY_INSTRUCTIONS_ESPORT;
        } else if (intent.isDeepAnalysis) {
            strategy = PromptRegistry.STRATEGY_INSTRUCTIONS_GAME_ANALYSIS;
        }

        String formatConstraint = intent.isDeepAnalysis 
            ? PromptRegistry.FORMAT_DISCORD_LONG
            : PromptRegistry.FORMAT_DISCORD_SHORT;

        return base + strategy + formatConstraint;
    }

    public enum Persona { DEFAULT, SERIOUS_COACH, TAQUIN_ANALYST, ESPORT_EXPERT }

    public static class Intent {
        public boolean isEsport = false;
        public boolean isDeepAnalysis = false;
        public boolean useSearch = false;
        public Persona persona = Persona.DEFAULT;
        public Integer queueId = null;
        public String championName = null;
        public String kdaScore = null;
        public int indexOffset = 0;
    }

    public record Target(String puuid, String region, String discordLabel, String riotId) {}
    
    public record ContextPayload(String context, Intent intent, List<Target> targets) {}
}