package net.runelite.client.plugins.questlist;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("questlist")
public interface QuestListConfig extends Config {
    @ConfigItem(
            keyName = "defaultState",
            name = "Default State",
            description = "Default state for quest list"
    )
    default QuestState defaultState()
    {
        return QuestState.ALL;
    }
}
