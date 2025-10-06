package com.denisnumb.discord_chat_mod;

import com.denisnumb.discord_chat_mod.commands.MentionCommand;
import com.denisnumb.discord_chat_mod.commands.SayCommand;
import com.denisnumb.discord_chat_mod.commands.TellrawCommand;
import com.denisnumb.discord_chat_mod.discord.ChannelMembersProvider;
import com.denisnumb.discord_chat_mod.discord.DiscordUtils;
import com.denisnumb.discord_chat_mod.discord.model.DiscordMemberData;
import com.denisnumb.discord_chat_mod.discord.model.DiscordMentionData;
import com.denisnumb.discord_chat_mod.markdown.MarkdownParser;
import com.denisnumb.discord_chat_mod.markdown.MarkdownToComponentConverter;
import com.denisnumb.discord_chat_mod.markdown.MarkdownToken;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.denisnumb.discord_chat_mod.ColorUtils.Color.GOLD;
import static com.denisnumb.discord_chat_mod.ColorUtils.Color.PURPLE;
import static com.denisnumb.discord_chat_mod.DiscordChatMod.*;
import static com.denisnumb.discord_chat_mod.advancement.AdvancementParser.*;
import static com.denisnumb.discord_chat_mod.ColorUtils.Color.*;
import static com.denisnumb.discord_chat_mod.MinecraftUtils.*;
import static com.denisnumb.discord_chat_mod.discord.DiscordUtils.*;
import static com.denisnumb.discord_chat_mod.discord.ServerStatusController.updateServerStatusWithDelay;

@Mod.EventBusSubscriber(modid = DiscordChatMod.MODID)
public class MinecraftEvents {
    private static final Map<UUID, LivingDeathEvent> pendingDeaths = new HashMap<>();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MentionCommand.register(event.getDispatcher());
        SayCommand.register(event.getDispatcher());
        TellrawCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onChatMessage(ServerChatEvent event) {
        String message = event.getRawText();
        Map<String, DiscordMentionData> mentions = Map.of();

        if (isDiscordConnected()) {
            List<DiscordMemberData> memberData = ChannelMembersProvider.getMemberData(discordChannel);

            for (DiscordMemberData member : memberData)
                if (message.contains(member.prettyMention))
                    message = message.replace(member.prettyMention, member.mentionString);

            mentions = new HashMap<>() {
                {
                    for (DiscordMemberData member : memberData)
                        put(member.mentionString, new DiscordMentionData(member));
                }
            };

            prepareDiscordTextMessage(String.format("`<%s>` %s", event.getPlayer().getName().getString(),
                    replaceEmojiCodesToDiscordMentions(message)))
                    .ifPresent(DiscordUtils::sendMessage);
        }

        event.setMessage(
                new MarkdownToComponentConverter(MarkdownParser.parseMarkdown(message), mentions)
                        .convertMarkdownTokensToComponent());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDieEvent(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }

        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        pendingDeaths.put(player.getUUID(), event);
    }

    @SubscribeEvent
    public static void onAdvancementMade(AdvancementEvent.AdvancementEarnEvent event) {
        if (!isDiscordConnected())
            return;

        // Check if advancement messages are disabled
        if (Config.ACHIEVEMENT_MESSAGE_STYLE.get() == Config.AchievementMessageStyle.NONE)
            return;

        DisplayInfo displayInfo = event.getAdvancement().getDisplay();
        if (displayInfo == null)
            return;
        if (!displayInfo.shouldAnnounceChat())
            return;

        String message = displayInfo.getFrame() == FrameType.TASK
                ? getTranslate("chat.type.advancement.task")
                : displayInfo.getFrame() == FrameType.GOAL
                        ? getTranslate("chat.type.advancement.goal")
                        : getTranslate("chat.type.advancement.challenge");

        ResourceLocation advancementId = event.getAdvancement().getId();
        ResourceLocation advancementResourceLocation = new ResourceLocation(
                advancementId.getNamespace(),
                "advancements/" + advancementId.getPath() + ".json");

        String title = displayInfo.getTitle().getString();
        String description = displayInfo.getDescription().getString();

        var advancementJson = getAdvancementFileAsJsonObject(advancementResourceLocation);
        if (advancementJson != null) {
            title = getTranslatedAdvancementTitle(advancementJson, title);
            description = getTranslatedAdvancementDescription(advancementJson, description);
        }

        String formattedPlayerName = "**" + event.getEntity().getName().getString() + "**";
        String formattedTitle = MarkdownParser.parseMarkdown(title).stream().allMatch(MarkdownToken::hasNoMarkdown)
                ? "**`" + title + "`**"
                : title;

        // Get the appropriate channel for advancements
        var advancementChannel = getAdvancementChannel();

        if (Config.ACHIEVEMENT_MESSAGE_STYLE.get() == Config.AchievementMessageStyle.EMBED) {
            int color = displayInfo.getFrame() == FrameType.CHALLENGE ? PURPLE : GOLD;
            if (advancementChannel != null) {
                DiscordUtils.sendEmbedMessageToChannel(
                        String.format(
                                message,
                                formattedPlayerName,
                                formattedTitle),
                        Config.SEND_ACHIEVEMENT_DESCRIPTION.get() ? description : "",
                        color,
                        advancementChannel);
            } else {
                sendEmbedMessage(
                        String.format(
                                message,
                                formattedPlayerName,
                                formattedTitle),
                        Config.SEND_ACHIEVEMENT_DESCRIPTION.get() ? description : "",
                        color);
            }
        } else {
            String plainTextMessage = String.format(
                    message,
                    formattedPlayerName,
                    formattedTitle);
            if (advancementChannel != null) {
                DiscordUtils.prepareDiscordTextMessageToChannel(plainTextMessage, advancementChannel)
                        .ifPresent(DiscordUtils::sendMessage);
            } else {
                prepareDiscordTextMessage(plainTextMessage).ifPresent(DiscordUtils::sendMessage);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }

        Player player = event.getEntity();
        LivingDeathEvent deathEvent = pendingDeaths.remove(player.getUUID());

        if (deathEvent == null) {
            return;
        }

        // The player has truly died and respawned. Send the message.
        if (!isDiscordConnected())
            return;

        String message;
        try {
            message = getLocalizedDeathMessage(deathEvent.getSource(), deathEvent.getEntity());
        } catch (Exception ignored) {
            message = deathEvent.getSource().getLocalizedDeathMessage(deathEvent.getEntity()).getString();
        }
        sendShortEmbedMessage(message, DEFAULT);
    }

    @SubscribeEvent
    public static void onPlayerJoinEvent(PlayerEvent.PlayerLoggedInEvent event) {
        joinLeaveEvent(event);
    }

    @SubscribeEvent
    public static void onPlayerLeaveEvent(PlayerEvent.PlayerLoggedOutEvent event) {
        joinLeaveEvent(event);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (pendingDeaths.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, LivingDeathEvent>> iterator = pendingDeaths.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LivingDeathEvent> entry = iterator.next();
            UUID playerUuid = entry.getKey();
            Player player = server.getPlayerList().getPlayer(playerUuid);

            // Player is online and not dead (revived), so we remove them from the pending
            // list.
            if (player != null && !player.isDeadOrDying()) {
                iterator.remove();
            }
        }
    }

    private static void joinLeaveEvent(PlayerEvent event) {
        if (!isDiscordConnected())
            return;

        boolean isJoin = event instanceof PlayerEvent.PlayerLoggedInEvent;
        String message = getTranslate(isJoin ? "multiplayer.player.joined" : "multiplayer.player.left");
        int color = isJoin ? GREEN : RED;

        sendShortEmbedMessage(String.format(message, "**" + event.getEntity().getName().getString() + "**"), color);
        updateServerStatusWithDelay();
    }

    private static GuildMessageChannel getAdvancementChannel() {
        String advancementChannelId = Config.DISCORD_ADVANCEMENT_CHANNEL_ID.get();
        if (advancementChannelId == null || advancementChannelId.isEmpty()) {
            return null; // Use default channel
        }

        try {
            return jda.getChannelById(GuildMessageChannel.class, advancementChannelId);
        } catch (Exception e) {
            // If advancement channel is invalid, fall back to default channel
            return null;
        }
    }
}
