package com.gmail.inverseconduit.bot;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.gmail.inverseconduit.AppContext;
import com.gmail.inverseconduit.BotConfig;
import com.gmail.inverseconduit.chat.ChatInterface;
import com.gmail.inverseconduit.chat.Subscribable;
import com.gmail.inverseconduit.commands.CommandHandle;
import com.gmail.inverseconduit.datatype.ChatMessage;
import com.gmail.inverseconduit.datatype.SeChatDescriptor;

/**
 * Defines bot core functionality. A bot manages {@link CommandHandle
 * CommandHandles}. Additionally messages should be enqueued to him, using
 * {@link DefaultBot#enqueueMessage(ChatMessage) enqueueMessage}. <br />
 * <br />
 * These messages will get preprocessed and then passed to their respective
 * commandHandlers
 * 
 * @author Unihedron<<a href="mailto:vincentyification@gmail.com"
 *         >vincentyification@gmail.com</a>>
 * @author Vogel612<<a href="mailto:vogel612@gmx.de">vogel612@gmx.de</a>>
 */
public class DefaultBot extends AbstractBot implements Subscribable<CommandHandle> {

    private final Logger               LOGGER   = Logger.getLogger(DefaultBot.class.getName());

    protected final ChatInterface      chatInterface;

    protected final Set<CommandHandle> commands = new HashSet<>();
    protected final Set<CommandHandle> listeners = new HashSet<>();

    public DefaultBot(ChatInterface chatInterface) {
        this.chatInterface = chatInterface;
    }

    @Override
    public void start() {
        executor.scheduleAtFixedRate(this::processMessageQueue, 1, 500, TimeUnit.MILLISECONDS);
    }

    private void processMessageQueue() {
        while (messageQueue.peek() != null) {
            LOGGER.finest("processing message from queue");
            processingThread.submit(() -> processMessage(messageQueue.poll()));
        }
    }

    private void processMessage(final ChatMessage chatMessage) {
    	listeners.stream().map(l -> l.execute(chatMessage)).filter(l -> null != l).forEach(result -> chatInterface.sendMessage(SeChatDescriptor.buildSeChatDescriptorFrom(chatMessage), result));
    	
        final String trigger = AppContext.INSTANCE.get(BotConfig.class).getTrigger();
        if ( !chatMessage.getMessage().startsWith(trigger)) { return; }

        commands.stream().filter(c -> chatMessage.getMessage().replace(trigger, "").startsWith(c.getName())).findFirst().map(c -> c.execute(chatMessage))
                .ifPresent(result -> chatInterface.sendMessage(SeChatDescriptor.buildSeChatDescriptorFrom(chatMessage), result));
    }

    public Set<CommandHandle> getCommands() {
        return Collections.unmodifiableSet(commands);
    }
    
    public Set<CommandHandle> getListeners(){
    	return Collections.unmodifiableSet(listeners);
    }

    @Override
    public void subscribe(CommandHandle subscriber) {
    	if (subscriber.getName() == null){
    		listeners.add(subscriber);
    	} else {
    		commands.add(subscriber);
    	}
    }

    @Override
    public void unSubscribe(CommandHandle subscriber) {
    	listeners.remove(subscriber);
        commands.remove(subscriber);
    }

    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public Collection<CommandHandle> getSubscriptions() {
    	Set<CommandHandle> set = new HashSet<CommandHandle>(commands);
    	set.addAll(listeners);
        return set;
    }

}
