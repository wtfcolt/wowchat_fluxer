WoWChat for Fluxer -- README
============================

WoWChat relays chat between old versions of World of Warcraft and [Fluxer](https://fluxer.com) channels.
It works with fluxer.com and with self-hosted Fluxer instances.

**It does NOT support WoW Classic or Retail servers.**

Currently supported versions are:
  * Vanilla
  * The Burning Crusade
  * Wrath of the Lich King
  * Cataclysm (4.3.4 build 15595)
  * Mists of Pandaria (5.4.8 build 18414)

Some servers have their own modifications which deviate from the original client implementation. For those,
consider these dedicated forks of the original WoWChat, which add the custom features of those servers:
  * Ascension: https://github.com/Szyler/AscensionChat
  * Turtle WoW: https://github.com/Zebouski/WoWChat-Turtle

Features:
* Clientless (does not need the WoW client to be open to run)
* Chat relay between WoW and Fluxer channels
  * Guild chat, Officer chat, Say, Yell, Emotes, System messages, Whispers and custom channels.
  * In-game links (items, spells, ...) are shown as links to an item database, depending on the expansion.
  * Configurable message formats and regex filters.
* Tagging
  * Tag Fluxer users from WoW with @part_or_all_of_their_name, or "@Names With Spaces" (with the quotes).
  * Roles can be tagged the same way. @everyone and @here never notify anyone.
* Guild notifications: promotions, demotions, joins, leaves, kicks, MotD, achievements, and optionally online/offline.
* Commands in Fluxer: `?who` / `?online` (guildies online), `?who <name>` (look up a player), `?gmotd`.
* Runs as a Java program on Windows, Mac and Linux.

## How it works
The bot connects to your Fluxer instance as a bot account, then logs in to your WoW server as a character.
Once it sees the configured Fluxer channels and is in the world, it relays messages between Fluxer and WoW.

##### DO NOT, under any circumstances, use this bot on an account with existing characters!
Even though this bot does not do anything malicious, some servers may not like a bot connecting, and GMs may ban the account!

## Setup

### 1. Create a Fluxer bot
1. Create an application on your Fluxer instance. Creating an application also creates its bot account
   (API: `POST /v1/oauth2/applications`, see the [Fluxer application docs](https://docs.fluxer.com/http-api/applications/)).
2. Copy the **bot token**. It has the form `<application_id>.<secret>` and is shown only once.
   If you lose it, reset the bot token to get a new one; resetting invalidates the old token immediately.
   Keep the token secret. Anyone with it can act as your bot.
3. Note the **application ID**: it is the part of the token before the `.`.

### 2. Add the bot to your guild
Open this address in a browser while signed in to Fluxer, replacing `API_URL` with your instance's public API
address (`endpoints.api_public` in `https://<your instance>/.well-known/fluxer`) and `APPLICATION_ID` with the ID above:

```
API_URL/v1/oauth2/authorize?client_id=APPLICATION_ID&scope=bot&permissions=3072
```

You need the Manage Guild permission in the guild you add the bot to.

`permissions=3072` grants the minimum the bot needs, which Fluxer gives the bot through a role named after the application:

| Permission | Value | Why |
|---|---|---|
| VIEW_CHANNEL | 1024 | See the relayed channels and receive their messages |
| SEND_MESSAGES | 2048 | Post messages from WoW |

Make sure the bot can view and send messages in every channel you relay (check channel permission overwrites).
No other permission is required. The bot loads the guild member list (used for @tags) through an API route
that needs no permission. A role that is not marked mentionable only notifies its members when tagged if the
bot also has MENTION_EVERYONE.

### 3. Configure WoWChat
Edit `wowchat.conf` (or a copy of it). Values like `${?FLUXER_TOKEN}` are read from environment variables,
which keeps secrets out of the file.

#### Section `fluxer`
* **instance_url**: The address of your Fluxer instance, for example `https://fluxer.example.com`
  (the origin that serves `/.well-known/fluxer`). Or set the `FLUXER_INSTANCE_URL` environment variable.
  WoWChat reads the API and Gateway addresses from the instance's discovery document.
* **token**: The bot token, without a `Bot ` prefix. Set the `FLUXER_TOKEN` environment variable rather than
  writing the token into the file.
* **api_url**, **gateway_url** (optional): Override the API (`http`/`https`) and Gateway (`ws`/`wss`) addresses
  published by the instance. Only needed if the bot cannot reach the published addresses, for example behind a
  reverse proxy. When both are set, **instance_url** is not needed.

#### Section `wow`
* **platform**: Leave as **Mac** unless your target server has Warden (anticheat) disabled AND it is blocking/has disabled Mac logins. In this case put **Windows**.
* **locale**: Optionally specify a locale if you want to join locale-specific global channels. **enUS** is the default locale.
* **enable_server_motd**: **0** to ignore the server's MotD. **1** to send the server's MotD as a SYSTEM message.
* **version**: 1.12.1, 2.4.3, 3.3.5, 4.3.4, or 5.4.8 based on the server's expansion.
* **build**: you can include a build=<build number> setting if your server uses a custom build version. You can also use **realm_build** and **game_build** if the number is different for each server. See https://github.com/fjaros/wowchat/issues/90
* **realmlist**: the server's realmlist, same as in your realmlist.wtf file, for example logon.lightshope.org
* **realm**: The realm name the bot connects to, as shown on top of the character list window. Put ONLY the name, not the realm type like PVP or PVE.
  In this example the **realm** value is The Construct:
  ![realm-construct](images/example3.png)
* **account**: The bot's WoW game account, or set the `WOW_ACCOUNT` environment variable.
* **password**: The bot's WoW game account password, or set the `WOW_PASSWORD` environment variable.
* **character**: The character's name as shown in the character list, or set the `WOW_CHARACTER` environment variable.

#### Section `guild`
Guild notifications sent to Fluxer. For each of **online**, **offline**, **promoted**, **demoted**, **joined**,
**left**, **removed**, **motd** and **achievement**:
* **enabled**: **0** to not send it, **1** to send it.
* **format**: How to display the message.
* **channel**: Optional Fluxer channel **name** or **ID** for this notification. Without it, the notification goes to the channels that relay WoW guild chat.

#### Section `chat`
Shared chat settings:
* **enable_dot_commands**: If **1**, messages from Fluxer starting with "." are sent to the server unformatted, so you can send server commands like ".s in". If **0**, they are formatted like regular messages (with a leading space, so they never run as commands).
* **dot_commands_whitelist**: If empty, **enable_dot_commands** alone decides. Otherwise ONLY the listed commands are sent unformatted. Wildcards like "lookup *" are allowed.
* **enable_commands_channels**: Fluxer channel names where the `?who`/`?online`/`?gmotd` commands work. Empty means all channels.
* **enable_tag_failed_notifications**: If **1**, when an @tag from WoW matches nobody or too many people, the bot whispers the sender and posts the error in the channel.
* **item_database**: Base URL of the database used for item links. Empty uses the default for the expansion.

**channels**: The channel relays. You can have any number of them. Each one has:
* **direction**: **both**, **wow_to_fluxer** or **fluxer_to_wow**.
* **wow** section:
  * **type**: One of **Say**, **Guild**, **Officer**, **Emote**, **Yell**, **System**, **Whisper**, **Channel**.
    * With **type=Channel**, also set **channel=name of the channel**.
  * **format**: How messages from Fluxer are written in WoW. Supported placeholders: **%time**, **%user**, **%message**.
  * **filters**: See the filters section. If present, these replace the global filters for Fluxer->WoW messages of this relay.
  * **id**: Optionally, the channel ID if your server has a non-standard global channel.
* **fluxer** section:
  * **channel**: The Fluxer text channel **name** or **ID**. Using the ID is recommended so renaming the channel does not affect the relay.
  * **format**: How messages from WoW are written in Fluxer. Supported placeholders: **%time**, **%user**, **%message**, and **%target** (the WoW channel name) for **type=Channel**.
  * **filters**: See the filters section. If present, these replace the global filters for WoW->Fluxer messages of this relay.

#### Section `filters`
Messages to ignore, in both directions. Can be overridden per relay as described above.
* **enabled**: **0** to disable all filters, **1** to enable them.
* **patterns**: A list of Java regular expressions. A message matching any of them is not relayed, and is logged with the word FILTERED.

## Run
1. Build or download a release, then unzip it.
   * **Make sure you have a Java Runtime Environment (JRE) 1.8 or higher installed!**
2. Set the environment variables (or edit the file):
   ```
   export FLUXER_INSTANCE_URL=https://fluxer.example.com
   export FLUXER_TOKEN=<bot token>
   export WOW_ACCOUNT=<account> WOW_PASSWORD=<password> WOW_CHARACTER=<character>
   ```
3. **On Windows** run `run.bat`, **on Mac/Linux** run `run.sh`. Or run `java -jar wowchat.jar <config file>`.
   * If no config file is given, the bot uses `wowchat.conf`.

WoWChat exits with an error message if the configuration is invalid or Fluxer rejects the token. It reconnects
to Fluxer and to WoW automatically after network problems.

## Build
WoWChat is written in Scala and built with [Maven](https://maven.apache.org). It uses Java 1.8 and Scala 2.12.12.

```
mvn clean package
```

This runs the tests and produces `target/wowchat-<version>.zip`. Scala 2.12.12 cannot compile on newer JDKs such
as JDK 21, so build with a Java 8 JDK, for example `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn clean package`.

## Known limitations
* Fluxer's documentation does not describe the in-text syntax of user, role and channel mentions or custom emoji.
  WoWChat assumes `<@id>`, `<@&id>`, `<#id>` and `<:name:id>`; this still needs to be confirmed on a live instance.
* @tags from WoW are matched against all guild members, not only those who can see the channel.
* Messages from Fluxer are split for WoW by character count, not by UTF-8 byte count, so a message with many
  non-ASCII characters can exceed WoW's 255-byte limit.
* Fluxer threads are not relayed.
