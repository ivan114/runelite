package net.runelite.client.plugins.adblock;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("adblock")
public interface AdblockConfig extends Config {

    @ConfigItem(
            keyName = "censoredMessage",
            name = "Censored Message",
            description = "Message to display when a message is censored",
            position = 1
    )
    default String censoredMessage() {
        return "§§§";
    }

    @ConfigItem(
            keyName = "filterFriends",
            name = "Filter Friends",
            description = "Filter your friends' messages",
            position = 2
    )
    default boolean filterFriends()
    {
        return false;
    }

    @ConfigItem(
            keyName = "filterClan",
            name = "Filter Clan Chat Members",
            description = "Filter your clan chat members' messages",
            position = 3
    )
    default boolean filterClan()
    {
        return false;
    }

}
