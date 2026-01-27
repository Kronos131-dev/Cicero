# 🤖 Cicero - Bot Discord LoL & Esport

Un bot Discord intelligent capable d'analyser vos parties de League of Legends, de suivre vos rangs et de discuter stratégie/esport grâce à l'IA Mistral.

## 🚀 Fonctionnalités

- **🔗 Liaison de compte** : `/link GameName#Tag` pour lier votre compte Riot.
- **📊 Suivi de rang** : `/rank` et `/leaderboard` pour voir qui est le meilleur du serveur (Solo & Flex).
- **🧠 IA Coach & Expert** : `/ask` pour poser des questions techniques ou esport. L'IA connaît votre contexte (rang, champions, dernières games).
- **🔎 Analyse de game** : `/analyze` pour comprendre pourquoi vous avez gagné ou perdu.
- **🛡️ Modération** : Le bot refuse de parler de sujets hors-sujet (politique, etc.).

## 🛠️ Installation (Pour le développeur / Serveur)

### 1. Prérequis
- Java 17 ou supérieur installé.
- Un bot Discord créé sur le [Portail Développeur Discord](https://discord.com/developers/applications).
- Une clé API Riot Games (Attention à la régénérer toutes les 24h si c'est une clé perso).
- Une clé API Mistral AI.

### 2. Configuration
Créez un fichier `.env` à la racine du dossier (à côté du `.jar`) avec le contenu suivant :

```env
DISCORD_TOKEN=votre_token_discord_ici
RIOT_API_KEY=votre_cle_riot_ici
MISTRAL_API_KEY=votre_cle_mistral_ici
```

### 3. Compilation (Créer le .jar)
Si vous avez le code source, ouvrez un terminal dans le dossier du projet et lancez :

```bash
mvn clean package
```

Cela va créer un fichier `Cicero-1.0-SNAPSHOT.jar` dans le dossier `target/`.

### 4. Lancement sur le serveur
Transférez le fichier `.jar` et le fichier `.env` sur votre serveur, puis lancez :

```bash
java -jar Cicero-1.0-SNAPSHOT.jar
```

Pour le laisser tourner en arrière-plan (sur Linux) :
```bash
nohup java -jar Cicero-1.0-SNAPSHOT.jar > bot.log 2>&1 &
```

## ⚠️ Note importante
La base de données `lolbot.db` sera créée automatiquement au premier lancement. Ne la supprimez pas si vous voulez garder les liens des comptes utilisateurs !
