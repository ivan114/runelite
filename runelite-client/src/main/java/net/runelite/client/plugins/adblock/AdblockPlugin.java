package net.runelite.client.plugins.adblock;

import com.google.common.base.Splitter;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ClanManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@PluginDescriptor(
        name = "Adblock",
        description = "Adblock for Runelite, block spam messages",
        enabledByDefault = false
)
@Slf4j
public class AdblockPlugin extends Plugin {
    private static final Splitter NEWLINE_SPLITTER = Splitter
            .on("\n")
            .omitEmptyStrings()
            .trimResults();

    public static final File ADBLOCK_DIR = new File(RUNELITE_DIR, "adblock");

    public static final File PLAYER_LIST_FIR = new File(ADBLOCK_DIR, "playerlist.txt");
    public static final File MESSAGE_LIST_FIR = new File(ADBLOCK_DIR, "messagelist.txt");

    private final List<Pattern> filteredMessagePatterns = new ArrayList<>();
    private final List<Pattern> filteredUserPatterns = new ArrayList<>();

    @Inject
    private Client client;

    @Inject
    private AdblockConfig config;

    @Inject
    private ClanManager clanManager;

    @Provides
    AdblockConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig((AdblockConfig.class));
    }

    @Override
    protected void startUp() throws Exception {
        client.refreshChat();
        initLocalStorage();
        updateFilteredPatterns();
    }

    @Override
    protected void shutDown() throws Exception {
        client.refreshChat();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"adblock".equals(event.getGroup())) {
            return;
        }
        updateFilteredPatterns();

        //Refresh chat after config change to reflect current rules
        client.refreshChat();
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event)
    {
        if (!(event.getActor() instanceof Player))
        {
            return;
        }

        if(!shouldPlayerBeProcessed(event.getActor().getName())){
            return;
        }

        if(!shouldMessageCensored(event.getOverheadText()) && !shouldPlayerBeBlocked(event.getActor().getName())){
            return;
        }

        event.getActor().setOverheadText(config.censoredMessage());
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!"chatFilterCheck".equals(event.getEventName())) {
            return;
        }

        int[] intStack = client.getIntStack();
        int intStackSize = client.getIntStackSize();
        int messageType = intStack[intStackSize - 2];
        int messageId = intStack[intStackSize - 1];

        ChatMessageType chatMessageType = ChatMessageType.of(messageType);

        if(!isChatMessage(chatMessageType)){
            return;
        }

        MessageNode messageNode = (MessageNode) client.getMessages().get(messageId);
        String name = messageNode.getName();

        if(!shouldPlayerBeProcessed(name)){
            return;
        }

        if(shouldPlayerBeBlocked(name)){
            censorMessage();
            log.debug("Blocked User: " + name);
            return;
        }

        String[] stringStack = client.getStringStack();
        int stringStackSize = client.getStringStackSize();

        String message = stringStack[stringStackSize - 1];
        if(shouldMessageCensored(message)){
            censorMessage();
            log.debug("Blocked Message: " + message);
            return;
        }
    }

    void censorMessage(){
        String[] stringStack = client.getStringStack();
        int stringStackSize = client.getStringStackSize();

        stringStack[stringStackSize - 1] = config.censoredMessage();
    }

    void updateFilteredPatterns()
    {
        filteredMessagePatterns.clear();
        filteredUserPatterns.clear();

        {
            LineNumberReader userListReader = getFileLineReader(PLAYER_LIST_FIR);
            if (userListReader != null) {
                try {
                    String line = userListReader.readLine();
                    while (line != null) {
                        filteredUserPatterns.add(Pattern.compile(line, Pattern.CASE_INSENSITIVE));
                        log.debug("User list added: " + line);
                        line = userListReader.readLine();
                    }
                    log.info("Number of user list loaded: " + filteredUserPatterns.size());
                } catch (IOException e) {
                    log.error("Unable to read user list file");
                }
            }
        }

        {
            LineNumberReader messageListReader = getFileLineReader(MESSAGE_LIST_FIR);
            if (messageListReader != null) {
                try {
                    String line = messageListReader.readLine();
                    while (line != null) {
                        filteredMessagePatterns.add(Pattern.compile(line, Pattern.CASE_INSENSITIVE));
                        log.debug("Message list added: " + line);
                        line = messageListReader.readLine();
                    }
                    log.info("Number of message list loaded: " + filteredMessagePatterns.size());
                } catch (IOException e) {
                    log.error("Unable to read message list file");
                }
            }
        }
    }

    LineNumberReader getFileLineReader(File file){
        try {
            FileReader fileReader = new FileReader(file);
            LineNumberReader lineNumberReader = new LineNumberReader(fileReader);
            return lineNumberReader;
        } catch (FileNotFoundException e) {
            log.error("Unable to create reader, " + file.getName());
            return null;
        }
    }

    boolean shouldPlayerBeProcessed(String playerName){
        if(playerName == null){
            return true;
        }
        boolean isMessageFromSelf = playerName.equals(client.getLocalPlayer().getName());
        return !isMessageFromSelf &&
                (config.filterFriends() || !client.isFriended(playerName, false)) &&
                (config.filterClan() || !clanManager.isClanMember(playerName));
    }

    boolean shouldPlayerBeBlocked(String playerName){
        return filteredUserPatterns.stream().anyMatch(pattern -> pattern.matcher(playerName).find());
    }

    boolean shouldMessageCensored(String message){
        String strippedMessage = Text.JAGEX_PRINTABLE_CHAR_MATCHER.retainFrom(message)
                .replace('\u00A0', ' ');

        if(filteredMessagePatterns.stream().anyMatch(pattern -> pattern.matcher(strippedMessage).find())){
            return true;
        }

        String pureTextMessage = strippedMessage.replaceAll("\\W", "");

        if(filteredMessagePatterns.stream().anyMatch(pattern -> pattern.matcher(pureTextMessage).find())){
            return true;
        }

        return false;
    }

    private void initLocalStorage() {
        ADBLOCK_DIR.mkdir();

        try {
            PLAYER_LIST_FIR.createNewFile();
        } catch (IOException e) {
            log.error("Unable to create user list file.");
        }

        try {
            MESSAGE_LIST_FIR.createNewFile();
        } catch (IOException e) {
            log.error("Unable to create user list file.");
        }
    }

    private boolean isChatMessage(ChatMessageType chatMessageType) {
        switch (chatMessageType) {
            case PUBLICCHAT:
            case MODCHAT:
            case AUTOTYPER:
            case PRIVATECHAT:
            case MODPRIVATECHAT:
            case FRIENDSCHAT:
                return true;
            case LOGINLOGOUTNOTIFICATION:
            default:
                return false;
        }
    }

}
