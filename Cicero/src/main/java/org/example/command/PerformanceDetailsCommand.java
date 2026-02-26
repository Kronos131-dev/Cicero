package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.FileUpload;
import org.example.DatabaseManager;

import java.nio.charset.StandardCharsets;

public class PerformanceDetailsCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("performance-details", "Récupère l'audit complet de ta dernière analyse de performance.")
                .addOption(OptionType.USER, "joueur", "Le joueur pour lequel tu veux voir l'audit (optionnel)", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();

        // Déterminer la cible (soit l'utilisateur, soit l'option spécifiée)
        var targetUser = event.getUser();
        OptionMapping option = event.getOption("joueur");
        if (option != null) targetUser = option.getAsUser();

        // Récupération de l'enregistrement en BDD
        DatabaseManager.UserRecord dbUser = ctx.db().getUser(targetUser.getId());

        if (dbUser == null) {
            event.getHook().sendMessage("❌ Ce joueur n'est pas lié au bot (commande `/link`).").queue();
            return;
        }

        // On va chercher le dernier audit sauvegardé en base de données
        // Assure-toi que DatabaseManager possède bien une méthode pour récupérer le champ last_audit
        String lastAudit = ctx.db().getLastAudit(targetUser.getId());

        if (lastAudit == null || lastAudit.isEmpty()) {
            event.getHook().sendMessage("⚠️ Aucun audit trouvé. Lance d'abord la commande `/performance` pour générer une analyse.")
                    .queue();
            return;
        }

        try {
            // Conversion de l'audit en fichier TXT pour éviter les limites de caractères Discord
            byte[] fileData = lastAudit.getBytes(StandardCharsets.UTF_8);
            String fileName = "audit_" + targetUser.getName().toLowerCase() + ".txt";

            event.getHook().sendMessage("📜 **Audit détaillé pour " + targetUser.getName() + "**\n*Cet audit contient les calculs du Mathématicien, l'analyse Macro de l'IA et les punchlines du Caster.*")
                    .addFiles(FileUpload.fromData(fileData, fileName))
                    .queue();

        } catch (Exception e) {
            e.printStackTrace();
            event.getHook().sendMessage("❌ Erreur lors de la récupération de l'audit.").queue();
        }
    }
}