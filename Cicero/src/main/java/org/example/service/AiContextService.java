package org.example.service;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.example.DatabaseManager;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for analyzing user intent and building a relevant context for the AI.
 */
public class AiContextService {

    private final DatabaseManager db;
    private final RiotService riotService;

    private static final List<String> ESPORT_LEAGUES = Arrays.asList("lec", "lfl", "lck", "lpl", "lcs", "worlds", "msi");
    private static final List<String> ESPORT_KEYWORDS = Arrays.asList("esport", "compétitif", "pro", "match", "résultat", "score", "méta", "build", "stats", "joueur", "équipe", "niveau de", "stats de");
    private static final List<String> SEARCH_KEYWORDS = Arrays.asList("build", "méta", "runes", "counter", "news", "patch", "tierlist", "meilleur", "stats de");
    private static final List<String> SELF_KEYWORDS = Arrays.asList("je", "moi", "mon", "ma", "mes", "suis", "ai-je", "j'ai");

    public AiContextService(DatabaseManager db, RiotService riotService) {
        this.db = db;
        this.riotService = riotService;
    }

    /**
     * Analyzes the user's question to build a comprehensive context string and determine AI configuration.
     * @return A ContextPayload containing the context string and the analyzed intent.
     */
    public ContextPayload buildContext(SlashCommandInteractionEvent event, String question) {
        StringBuilder context = new StringBuilder();
        String lowerQ = question.toLowerCase();
        
        Intent intent = analyzeIntent(lowerQ);
        context.append(getSystemPromptForIntent(intent)).append("\n");

        injectPlayerData(event, context, question, intent);

        return new ContextPayload(context.toString(), intent);
    }

    private void injectPlayerData(SlashCommandInteractionEvent event, StringBuilder context, String question, Intent intent) {
        List<Target> targets = findTargets(event, question, intent);
        if (targets.isEmpty()) return;

        boolean isComparison = targets.size() > 1;

        for (Target target : targets) {
            try {
                context.append("\n--- DONNÉES POUR : ").append(target.name).append(" (Région: ").append(target.region).append(") ---\n");
                
                Map<String, RiotService.RankInfo> ranks = riotService.getAllRanks(target.puuid, target.region);
                RiotService.RankInfo solo = ranks.get("SOLO");
                context.append("Rang Solo/Duo: ").append(solo.tier).append(" ").append(solo.rank)
                       .append(" (").append(solo.lp).append(" LP) - Winrate: ")
                       .append(solo.wins).append("W/").append(solo.losses).append("L\n");

                if (intent.isDeepAnalysis && !isComparison) {
                    String matchId = riotService.findSpecificMatch(target.puuid, target.region, intent.queueId, intent.championName, intent.kdaScore, intent.indexOffset);
                    if (matchId != null) {
                        String analysisJson = riotService.getMatchAnalysis(matchId, target.puuid, target.region);
                        context.append("\n[DONNÉES BRUTES DU MATCH (JSON)]\n").append(analysisJson).append("\n");
                        
                        // Génération d'instructions de recherche spécifiques basées sur le JSON
                        try {
                            JSONObject json = new JSONObject(analysisJson);
                            JSONObject player = json.getJSONObject("target_player");
                            String champion = player.getString("champion");
                            String role = player.getString("role");
                            String rank = solo.tier; // ex: EMERALD
                            
                            context.append("\n[INSTRUCTION DE COACHING AVANCÉE]\n")
                                   .append("1. Analyse le JSON ci-dessus pour comprendre la performance du joueur (KDA, Dégâts, Vision, Items).\n")
                                   .append("2. UTILISE GOOGLE SEARCH pour effectuer DEUX recherches distinctes :\n")
                                   .append("   a) '").append(champion).append(" ").append(role).append(" build ").append(rank).append(" patch current site:lolalytics.com' (Pour comparer à son Elo).\n")
                                   .append("   b) '").append(champion).append(" ").append(role).append(" build Master+ patch current site:lolalytics.com' (Pour voir ce que font les meilleurs joueurs).\n")
                                   .append("3. Compare les choix du joueur avec la méta de son Elo ET la méta Master+.\n")
                                   .append("4. Identifie les écarts critiques (Items, Runes, Ordre des sorts) qui séparent son gameplay de celui d'un Master+.\n")
                                   .append("5. Génère un tableau comparatif : [Joueur] vs [Méta Elo Actuel] vs [Méta Master+].\n");
                        } catch (Exception e) {
                            context.append("(Impossible de générer les instructions de recherche spécifiques: ").append(e.getMessage()).append(")\n");
                        }

                    } else {
                        context.append("Aucune partie correspondant aux critères trouvée.\n");
                    }
                } 
                else if (!isComparison) {
                    context.append("\n[HISTORIQUE RÉCENT (JSON ARRAY)]\n");
                    context.append(riotService.getMatchHistorySummary(target.puuid, target.region, intent.queueId, 10));
                    context.append("\n\nINSTRUCTION : Analyse cet historique JSON pour détecter des tendances (Champions forts/faibles, rôles, séries de victoires/défaites).");
                }

            } catch (Exception e) {
                context.append("Erreur récupération données Riot pour ").append(target.name).append(": ").append(e.getMessage()).append("\n");
            }
        }
    }

    private List<Target> findTargets(SlashCommandInteractionEvent event, String question, Intent intent) {
        List<Target> targets = new ArrayList<>();
        
        Pattern mentionPattern = Pattern.compile("<@!?(\\d+)>");
        Matcher mentionMatcher = mentionPattern.matcher(question);
        while (mentionMatcher.find()) {
            String id = mentionMatcher.group(1);
            DatabaseManager.UserRecord user = db.getUser(id);
            if (user != null) {
                targets.add(new Target(user.puuid, user.region, event.getJDA().retrieveUserById(id).complete().getName()));
            }
        }

        Pattern riotIdPattern = Pattern.compile("\\b([a-zA-Z0-9 ]{3,16})#([a-zA-Z0-9]{3,5})\\b");
        Matcher riotIdMatcher = riotIdPattern.matcher(question);
        while (riotIdMatcher.find()) {
            try {
                String name = riotIdMatcher.group(1);
                String tag = riotIdMatcher.group(2);
                String puuid = riotService.getPuuid(name, tag);
                targets.add(new Target(puuid, "euw1", name + "#" + tag));
            } catch (Exception ignored) {}
        }

        // On ajoute l'auteur SEULEMENT si :
        // 1. Aucune autre cible n'a été trouvée (pas de mention, pas de Riot ID)
        // 2. ET l'intention suggère que l'utilisateur parle de lui-même (mots clés "je", "mon", etc.)
        // 3. OU si ce n'est PAS une question purement Esport/Générale
        if (targets.isEmpty()) {
            boolean isSelfRelated = isSelfRelated(question);
            
            // Si c'est une question Esport sans référence à soi-même, on n'ajoute PAS l'auteur.
            if (intent.isEsport && !isSelfRelated) {
                return targets; // Liste vide
            }

            // Si c'est une question générale (ex: "qui est le meilleur champ ?") sans "je", on peut éviter d'envoyer l'historique
            // Mais pour l'instant, gardons le comportement par défaut sauf pour l'Esport clair.

            DatabaseManager.UserRecord author = db.getUser(event.getUser().getId());
            if (author != null && isSelfRelated) {
                targets.add(new Target(author.puuid, author.region, "Moi"));
            }
        }
        
        return targets;
    }

    private boolean isSelfRelated(String question) {
        String lowerQ = question.toLowerCase();
        for (String keyword : SELF_KEYWORDS) {
            // Utilisation de regex pour matcher le mot entier uniquement (\b)
            // On échappe le mot clé au cas où il contiendrait des caractères spéciaux regex (peu probable ici mais bonne pratique)
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
            // Si on demande une analyse profonde, on active aussi la recherche pour comparer avec la méta
            intent.useSearch = true;
        }
        if (lowerQ.contains("profil") || lowerQ.contains("historique") || lowerQ.contains("stats générales")) {
            intent.isDeepAnalysis = false;
        }

        if (lowerQ.contains("sérieux") || lowerQ.contains("précis")) intent.persona = Persona.SERIOUS_COACH;
        else if (lowerQ.contains("fun") || lowerQ.contains("taquin") || lowerQ.contains("avis")) intent.persona = Persona.TAQUIN_ANALYST;
        else if (intent.isDeepAnalysis) intent.persona = Persona.SERIOUS_COACH;
        else if (intent.isEsport) intent.persona = Persona.ENTHUSIAST_CASTER;

        if (lowerQ.contains("flex")) intent.queueId = RiotService.QUEUE_FLEX;
        else if (lowerQ.contains("clash")) intent.queueId = RiotService.QUEUE_CLASH;
        else if (lowerQ.contains("soloq") || lowerQ.contains("solo q") || lowerQ.contains("ranked")) intent.queueId = RiotService.QUEUE_SOLOQ;
        else if (lowerQ.contains("aram")) intent.queueId = RiotService.QUEUE_ARAM;
        
        return intent;
    }

    private String getSystemPromptForIntent(Intent intent) {
        return switch (intent.persona) {
            case SERIOUS_COACH -> "Tu es un coach expert de League of Legends, analytique et précis. Base tes réponses sur les données factuelles fournies et les comparaisons avec la méta.";
            case TAQUIN_ANALYST -> "Tu es un analyste LoL expérimenté avec un ton léger et un peu taquin. N'hésite pas à faire des blagues sur les builds douteux.";
            case ENTHUSIAST_CASTER -> "Tu es un commentateur Esport passionné. Utilise les résultats de recherche Google pour donner des infos précises et à jour.";
            default -> "Tu es une IA experte de League of Legends. Réponds de manière neutre et informative.";
        };
    }

    // --- INNER CLASSES FOR INTENT ---
    public enum Persona { DEFAULT, SERIOUS_COACH, TAQUIN_ANALYST, ENTHUSIAST_CASTER }

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

    public record Target(String puuid, String region, String name) {}
    
    public record ContextPayload(String context, Intent intent) {}
}