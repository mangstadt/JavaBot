package com.gmail.inverseconduit.chat;

import com.gmail.inverseconduit.SESite;

/**
 * Interface specifying least common methods for a class that acts as interface
 * to a chat.
 * The Instances of this class are supposed to be threadsafe.
 * A ChatInterface currently produces
 * {@link com.gmail.inverseconduit.datatype.ChatMessage ChatMessages},
 * subscribers must implement {@link ChatWorker}
 * 
 * @author Unihedron<<a href="mailto:vincentyification@gmail.com"
 *         >vincentyification@gmail.com</a>>
 * @author Vogel612<<a href="mailto:vogel612@gmx.de">vogel612@gmx.de</a>>
 */
public interface ChatInterface extends Subscribable<ChatWorker> {

    void queryMessages();

    boolean sendMessage(SESite site, int chatId, String message);

    boolean joinChat(SESite site, int chatId);
    
    boolean leaveChat(SESite site, int chatId);

    boolean login(SESite site, String email, String password);

    void broadcast(String message);
}