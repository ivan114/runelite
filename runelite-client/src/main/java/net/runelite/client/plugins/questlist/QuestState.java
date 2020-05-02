package net.runelite.client.plugins.questlist;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.SpriteID;

@AllArgsConstructor
@Getter
public enum QuestState
{
    NOT_STARTED(0xff0000, "Not started", SpriteID.MINIMAP_ORB_HITPOINTS),
    IN_PROGRESS(0xffff00, "In progress", SpriteID.MINIMAP_ORB_HITPOINTS_DISEASE),
    COMPLETE(0xdc10d, "Completed", SpriteID.MINIMAP_ORB_HITPOINTS_POISON),
    ALL(0, "All", SpriteID.MINIMAP_ORB_PRAYER),
    NOT_COMPLETED(0, "Not Completed", SpriteID.MINIMAP_ORB_RUN);

    private final int color;
    private final String name;
    private final int spriteId;

    static QuestState getByColor(int color)
    {
        for (QuestState value : values())
        {
            if (value.getColor() == color)
            {
                return value;
            }
        }

        return null;
    }
}