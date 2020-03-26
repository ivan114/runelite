/*
 * Copyright (c) 2018, Magic fTail
 * Copyright (c) 2019, osrs-music-map <osrs-music-map@users.noreply.github.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.chatfilter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.inject.Inject;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ClanManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.StringUtils;

@PluginDescriptor(
	name = "Chat Filter",
	description = "Censor user configurable words or patterns from chat",
	enabledByDefault = false
)
public class ChatFilterPlugin extends Plugin
{
	private static final Splitter NEWLINE_SPLITTER = Splitter
		.on("\n")
		.omitEmptyStrings()
		.trimResults();

	private static final String MESSAGE_QUANTITY_PREFIX = " x ";
	private static final int MESSAGE_QUANTITY_DEFAULT = 1;
	private static final int VISIBLE_MESSAGES = 8;
	
	@VisibleForTesting
	static final String CENSOR_MESSAGE = "Hey, everyone, I just tried to say something very silly!";

	private final CharMatcher jagexPrintableCharMatcher = Text.JAGEX_PRINTABLE_CHAR_MATCHER;
	private final List<Pattern> filteredPatterns = new ArrayList<>();
	private final List<Pattern> filteredNamePatterns = new ArrayList<>();

	@Inject
	private Client client;

	@Inject
	private ChatFilterConfig config;

	@Inject
	private ClanManager clanManager;

	@Provides
	ChatFilterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChatFilterConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		updateFilteredPatterns();
		client.refreshChat();
	}

	@Override
	protected void shutDown() throws Exception
	{
		filteredPatterns.clear();
		client.refreshChat();
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!"chatFilterCheck".equals(event.getEventName()))
		{
			return;
		}

		int[] intStack = client.getIntStack();
		int intStackSize = client.getIntStackSize();
		int messageType = intStack[intStackSize - 2];
		int messageId = intStack[intStackSize - 1];

		ChatMessageType chatMessageType = ChatMessageType.of(messageType);

		// Only filter public chat and private messages
		switch (chatMessageType)
		{
			case PUBLICCHAT:
			case MODCHAT:
			case AUTOTYPER:
			case PRIVATECHAT:
			case MODPRIVATECHAT:
			case FRIENDSCHAT:
				break;
			case LOGINLOGOUTNOTIFICATION:
				if (config.filterLogin())
				{
					// Block the message
					intStack[intStackSize - 3] = 0;
				}
				return;
			default:
				return;
		}

		MessageNode messageNode = client.getMessages().get(messageId);
		String name = messageNode.getName();
		if (!shouldFilterPlayerMessage(name))
		{
			return;
		}

		String[] stringStack = client.getStringStack();
		int stringStackSize = client.getStringStackSize();

		String message = stringStack[stringStackSize - 1];
		String censoredMessage = censorMessage(name, message);

		if (censoredMessage == null)
		{
			// Block the message
			intStack[intStackSize - 3] = 0;
		}
		else
		{
			// Replace the message
			stringStack[stringStackSize - 1] = censoredMessage;
		}
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (!(event.getActor() instanceof Player) || !shouldFilterPlayerMessage(event.getActor().getName()))
		{
			return;
		}

		String message = censorMessage(event.getActor().getName(), event.getOverheadText());

		if (message == null)
		{
			message = " ";
		}

		event.getActor().setOverheadText(message);
	}

	boolean shouldFilterPlayerMessage(String playerName)
	{
		boolean isMessageFromSelf = playerName.equals(client.getLocalPlayer().getName());
		return !isMessageFromSelf &&
			(config.filterFriends() || !client.isFriended(playerName, false)) &&
			(config.filterClan() || !clanManager.isClanMember(playerName));
	}

	String censorMessage(final String username, final String message)
	{
		String strippedMessage = jagexPrintableCharMatcher.retainFrom(message)
			.replace('\u00A0', ' ');
		if (shouldFilterByName(username))
		{
			switch (config.filterType())
			{
				case CENSOR_WORDS:
					return StringUtils.repeat('*', strippedMessage.length());
				case CENSOR_MESSAGE:
					return CENSOR_MESSAGE;
				case REMOVE_MESSAGE:
					return null;
			}
		}

		boolean filtered = false;
		for (Pattern pattern : filteredPatterns)
		{
			Matcher m = pattern.matcher(strippedMessage);

			StringBuffer sb = new StringBuffer();

			while (m.find())
			{
				switch (config.filterType())
				{
					case CENSOR_WORDS:
						m.appendReplacement(sb, StringUtils.repeat('*', m.group(0).length()));
						filtered = true;
						break;
					case CENSOR_MESSAGE:
						return CENSOR_MESSAGE;
					case REMOVE_MESSAGE:
						return null;
				}
			}
			m.appendTail(sb);

			strippedMessage = sb.toString();
		}

		return filtered ? strippedMessage : message;
	}

	void updateFilteredPatterns()
	{
		filteredPatterns.clear();
		filteredNamePatterns.clear();

		Text.fromCSV(config.filteredWords()).stream()
			.map(s -> Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE))
			.forEach(filteredPatterns::add);

		NEWLINE_SPLITTER.splitToList(config.filteredRegex()).stream()
			.map(ChatFilterPlugin::compilePattern)
			.filter(Objects::nonNull)
			.forEach(filteredPatterns::add);

		NEWLINE_SPLITTER.splitToList(config.filteredNames()).stream()
			.map(ChatFilterPlugin::compilePattern)
			.filter(Objects::nonNull)
			.forEach(filteredNamePatterns::add);
	}

	private static Pattern compilePattern(String pattern)
	{
		try
		{
			return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
		}
		catch (PatternSyntaxException ex)
		{
			return null;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"chatfilter".equals(event.getGroup()))
		{
			return;
		}

		updateFilteredPatterns();

		//Refresh chat after config change to reflect current rules
		client.refreshChat();
	}

	@VisibleForTesting
	boolean shouldFilterByName(final String playerName)
	{
		String sanitizedName = Text.standardize(playerName);
		for (Pattern pattern : filteredNamePatterns)
		{
			Matcher m = pattern.matcher(sanitizedName);
			if (m.find())
			{
				return true;
			}
		}
		return false;
	}
	
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.collapseChatMessages())
		{
			return;
		}

		collapseDuplicateMessages(event.getMessageNode());
	}

	private void collapseDuplicateMessages(MessageNode node)
	{
		MessageNode oldNode = findMessage(node);
		if (oldNode == null)
		{
			return;
		}
		oldNode.setValue(addMessageQuantity(oldNode.getValue()));
		for (ChatLineBuffer b : client.getChatLineMap().values())
		{
			b.removeMessageNode(node);
		}
		client.refreshChat();
	}

	private MessageNode findMessage(MessageNode node)
	{
		List<MessageNode> nodeList = new ArrayList<>();
		for (ChatLineBuffer b : client.getChatLineMap().values())
		{
			for (MessageNode n : b.getLines())
			{
				if (n != null)
				{
					nodeList.add(n);
				}
			}
		}
		nodeList.sort(Comparator.comparing(MessageNode::getId).reversed());
		for (int i = 0; i < nodeList.size(); i++)
		{
			if (i > VISIBLE_MESSAGES)
			{
				break;
			}
			MessageNode n = nodeList.get(i);
			if (isEqual(n, node))
			{
				return n;
			}
		}
		return null;
	}

	private boolean isEqual(MessageNode oldNode, MessageNode newNode)
	{
		return oldNode.getId() != newNode.getId() && oldNode.getType().equals(newNode.getType()) &&
			Objects.equals(oldNode.getSender(), newNode.getSender()) &&
			oldNode.getName().equals(newNode.getName()) &&
			Text.removeStyleTags(stripMessageQuantity(oldNode.getValue())).equals(Text.removeStyleTags(newNode.getValue()));
	}

	private String stripMessageQuantity(String message)
	{
		int quantity = findMessageQuantity(message);
		if (quantity > MESSAGE_QUANTITY_DEFAULT)
		{
			String end = ColorUtil.colorTag(config.chatMessageCountColor()) + MESSAGE_QUANTITY_PREFIX + quantity;
			if (message.endsWith(end) || message.endsWith(end + ColorUtil.CLOSING_COLOR_TAG))
			{
				// Jagex sometimes append "</col>" to the end of messages
				return message.substring(0, message.lastIndexOf(end));
			}
		}
		return message;
	}

	private String addMessageQuantity(String message)
	{
		int quantity = findMessageQuantity(message) + 1;
		return stripMessageQuantity(message) + ColorUtil.colorTag(config.chatMessageCountColor()) +
			MESSAGE_QUANTITY_PREFIX + quantity;
	}

	private int findMessageQuantity(String message)
	{
		String quantityPrefix = ColorUtil.colorTag(config.chatMessageCountColor()) + MESSAGE_QUANTITY_PREFIX;
		int start = message.lastIndexOf(quantityPrefix);
		if (start >= 0)
		{
			String quantity = Text.removeTags(message.substring(start + quantityPrefix.length()));
			return Integer.parseInt(quantity);
		}
		return MESSAGE_QUANTITY_DEFAULT;
	}
}
