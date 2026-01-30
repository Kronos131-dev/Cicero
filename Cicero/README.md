# Cicero - League of Legends Discord Bot

Cicero est un bot Discord avancé pour League of Legends, propulsé par l'IA (Mistral AI) et intégrant les données en temps réel de Riot Games et des recherches web via Tavily.

## Fonctionnalités

### 🤖 IA & Analyse
- **/ask [question]** : Posez n'importe quelle question sur LoL. L'IA peut accéder à votre historique de match, votre rang, et faire des recherches sur la méta actuelle.
- **/analyze [question]** : Analyse approfondie de votre dernière partie. L'IA examine les builds, les runes, l'ordre des compétences et compare vos stats avec les données optimales (Master+).
- **/performance** : Génère un rapport de performance pour les 10 joueurs de votre dernière partie, avec des notes sur 100 et des commentaires personnalisés.
- **/new-ask** : Réinitialise la mémoire de conversation de l'IA.

### 📊 Statistiques & Classement
- **/rank [membre]** : Affiche le rang SoloQ, les LP et le winrate d'un membre du serveur.
- **/leaderboard** : Affiche le classement (Ladder) des membres du serveur, basé sur leur rang SoloQ.

### 🔗 Compte
- **/link [riot_id] [region]** : Lie votre compte Riot (ex: `Pseudo#TAG`) au bot pour permettre l'analyse de vos parties.

## Architecture Technique

- **Langage** : Java 21
- **Framework Discord** : JDA (Java Discord API)
- **IA** : LangChain4j + Mistral AI (Large Latest)
- **API Riot** : Intégration native avec gestion intelligente du Rate Limiting et cache.
- **Recherche Web** : Tavily API pour les infos en temps réel (Esport, Méta, Patchs).
- **Base de données** : SQLite pour le stockage des utilisateurs et de l'historique de chat.

## Installation

1. **Prérequis** :
   - Java 21 ou supérieur
   - Maven
   - Un bot Discord créé sur le [Portail Développeur Discord](https://discord.com/developers/applications)
   - Clés API : Riot Games, Mistral AI, Tavily.

2. **Configuration** :
   Créez un fichier `.env` à la racine du projet avec les variables suivantes :
   ```env
   DISCORD_TOKEN=votre_token_discord
   RIOT_API_KEY=votre_cle_riot
   MISTRAL_API_KEY=votre_cle_mistral
   TAVILY_API_KEY=votre_cle_tavily
   ```

3. **Lancement** :
   ```bash
   mvn clean package
   java -jar target/Cicero-1.0-SNAPSHOT.jar
   ```

## Structure du Projet

- `org.example` : Point d'entrée (`LolBot`).
- `org.example.command` : Gestionnaires de commandes Slash (`/ask`, `/analyze`, etc.).
- `org.example.service` : Services métier (Riot, Mistral, Tavily, Context).
- `org.example.service.ai` : Registre des prompts et configurations IA.
- `org.example.data` : Gestion de la base de données (SQLite).

## Contribution

Les contributions sont les bienvenues ! N'hésitez pas à ouvrir une issue ou une Pull Request.

## Licence

Ce projet est sous licence MIT.
