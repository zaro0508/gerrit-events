/*
 *  The MIT License
 *
 *  Copyright 2010 Sony Ericsson Mobile Communications. All rights reserved.
 *  Copyright 2012 Sony Mobile Communications AB. All rights reserved.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package com.sonymobile.tools.gerrit.gerritevents;

import com.sonymobile.tools.gerrit.gerritevents.dto.GerritEvent;
import com.sonymobile.tools.gerrit.gerritevents.dto.attr.Account;
import com.sonymobile.tools.gerrit.gerritevents.dto.attr.Provider;
import com.sonymobile.tools.gerrit.gerritevents.dto.events.CommentAdded;
import com.sonymobile.tools.gerrit.gerritevents.workers.Coordinator;
import com.sonymobile.tools.gerrit.gerritevents.workers.EventThread;
import com.sonymobile.tools.gerrit.gerritevents.workers.GerritEventWork;
import com.sonymobile.tools.gerrit.gerritevents.workers.JSONEventWork;
import com.sonymobile.tools.gerrit.gerritevents.workers.StreamEventsStringWork;
import com.sonymobile.tools.gerrit.gerritevents.workers.Work;

import net.sf.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;

//CS IGNORE LineLength FOR NEXT 1 LINES. REASON: static import.
import static com.sonymobile.tools.gerrit.gerritevents.GerritDefaultValues.DEFAULT_NR_OF_RECEIVING_WORKER_THREADS;

/**
 * Main class for this module. Contains the main loop for connecting and reading streamed events from Gerrit.
 *
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 */
public class GerritHandler implements Coordinator, Handler {

    /**
     * Time to wait between connection attempts.
     */
    private static final Logger logger = LoggerFactory.getLogger(GerritHandler.class);
    private BlockingQueue<Work> workQueue;
    private int numberOfWorkerThreads;
    private final Set<GerritEventListener> gerritEventListeners = new CopyOnWriteArraySet<GerritEventListener>();
    private final List<EventThread> workers;
    private Map<String, String> ignoreEMails = new ConcurrentHashMap<String, String>();

    /**
     * Creates a GerritHandler with all the default values set.
     *
     * @see GerritDefaultValues#DEFAULT_NR_OF_RECEIVING_WORKER_THREADS
     */
    public GerritHandler() {
        this(DEFAULT_NR_OF_RECEIVING_WORKER_THREADS);
    }

    /**
     * Standard Constructor.
     *
     * @param numberOfWorkerThreads the number of event threads.
     */
    public GerritHandler(int numberOfWorkerThreads) {
        this.numberOfWorkerThreads = numberOfWorkerThreads;

        workQueue = new LinkedBlockingQueue<Work>();
        workers = new ArrayList<EventThread>(numberOfWorkerThreads);
        for (int i = 0; i < numberOfWorkerThreads; i++) {
            EventThread eventThread = createEventThread("Gerrit Worker EventThread_" + i);
            eventThread.start();
            workers.add(eventThread);
        }
    }

    /**
     * Create the Event Thread.
     * @param threadName Name of thread to be created.
     * @return new EventThread to be used by worker
     */
    protected EventThread createEventThread(String threadName) {
        return new EventThread(this, threadName);
    }

    /**
     * Standard getter for the ignoreEMail.
     *
     * @param serverName the server name.
     * @return the e-mail address to ignore CommentAdded events from.
     */
    public String getIgnoreEMail(String serverName) {
        if (serverName != null) {
            return ignoreEMails.get(serverName);
        } else {
            return null;
        }
    }

    /**
     * Standard setter for the ignoreEMail.
     *
     * @param serverName the server name.
     * @param ignoreEMail the e-mail address to ignore CommentAdded events from.
     * If you want to remove, please set null.
     */
    public void setIgnoreEMail(String serverName, String ignoreEMail) {
        if (serverName != null) {
            if (ignoreEMail != null) {
                ignoreEMails.put(serverName, ignoreEMail);
            } else {
                ignoreEMails.remove(serverName);
            }
        }
    }

    @Override
    public void post(String data) {
        post(data, null);
    }

    @Override
    public void post(JSONObject json) {
        post(json, null);
    }

    @Override
    public void post(String data, Provider provider) {
        logger.debug("Trigger event string: {}", data);
        post(new StreamEventsStringWork(data, provider));
    }

    @Override
    public void post(JSONObject json, Provider provider) {
        logger.debug("Trigger event json object: {}", json);
        post(new JSONEventWork(json, provider));
    }

    @Override
    public void post(GerritEvent event) {
        logger.debug("Internally trigger event: {}", event);
        post(new GerritEventWork(event));
    }

    /**
     * Post work object to work queue.
     *
     * @param work the work object.
     */
    private void post(Work work) {
        try {
            logger.trace("putting work on queue.");
            workQueue.put(work);
        } catch (InterruptedException ex) {
            logger.warn("Interrupted while putting work on queue!", ex);
            //TODO check if shutdown
            //TODO try again since it is important
        }
    }

    @Override
    public void addListener(GerritEventListener listener) {
        synchronized (this) {
            if (!gerritEventListeners.add(listener)) {
                logger.warn("The listener was doubly-added: {}", listener);
            }
        }
    }

    /**
     * Adds all the provided listeners to the internal list of listeners.
     *
     * @param listeners the listeners to add.
     */
    @Deprecated
    public void addEventListeners(Map<Integer, GerritEventListener> listeners) {
        addEventListeners(listeners.values());
    }

    /**
     * Adds all the provided listeners to the internal list of listeners.
     *
     * @param listeners the listeners to add.
     */
    public void addEventListeners(Collection<? extends GerritEventListener> listeners) {
        synchronized (this) {
            gerritEventListeners.addAll(listeners);
        }
    }

    @Override
    public void removeListener(GerritEventListener listener) {
        synchronized (this) {
            gerritEventListeners.remove(listener);
        }
    }

    /**
     * Removes all event listeners and returns those that where removed.
     *
     * @return the former list of listeners.
     */
    public Collection<GerritEventListener> removeAllEventListeners() {
        synchronized (this) {
            HashSet<GerritEventListener> listeners = new HashSet<GerritEventListener>(gerritEventListeners);
            gerritEventListeners.clear();
            return listeners;
        }
    }

    /**
     * The number of added e{@link GerritEventListener}s.
     * @return the size.
     */
    public int getEventListenersCount() {
        return gerritEventListeners.size();
    }

    /**
     * Returns an unmodifiable view of the set of {@link GerritEventListener}s.
     *
     * @return a list of the registered event listeners.
     * @see Collections#unmodifiableSet(Set)
     */
    public Set<GerritEventListener> getGerritEventListenersView() {
        return Collections.unmodifiableSet(gerritEventListeners);
    }

    /**
     * Gets the number of event worker threads.
     *
     * @return the number of threads.
     */
    public int getNumberOfWorkerThreads() {
        return numberOfWorkerThreads;
    }

    /**
     * Sets the number of worker event threads.
     *
     * @param numberOfWorkerThreads the number of threads
     */
    public void setNumberOfWorkerThreads(int numberOfWorkerThreads) {
        this.numberOfWorkerThreads = numberOfWorkerThreads;
        //TODO what if nr of workers are increased/decreased in runtime.
    }

    @Override
    public BlockingQueue<Work> getWorkQueue() {
        return workQueue;
    }

    /**
     * Notifies all listeners of a Gerrit event. This method is meant to be called by one of the Worker Threads {@link
     * com.sonymobile.tools.gerrit.gerritevents.workers.EventThread} and not on this Thread which would
     * defeat the purpose of having workers.
     *
     * @param event the event.
     */
    @Override
    public void notifyListeners(GerritEvent event) {
        if (event instanceof CommentAdded) {
            if (ignoreEvent((CommentAdded)event)) {
                logger.trace("CommentAdded ignored");
                return;
            }
        }
        for (GerritEventListener listener : gerritEventListeners) {
            try {
                notifyListener(listener, event);
            } catch (Exception ex) {
                logger.error("When notifying listener: {} about event: {}", listener, event);
                logger.error("Notify-error: ", ex);
            }
        }
    }

    /**
     * Sub method of {@link #notifyListeners(com.sonymobile.tools.gerrit.gerritevents.dto.GerritEvent) }.
     * This is where most of the reflection magic in the event notification is done.
     *
     * @param listener the listener to notify
     * @param event    the event.
     */
    private void notifyListener(GerritEventListener listener, GerritEvent event) {
        logger.trace("Notifying listener {} of event {}", listener, event);
        try {
            logger.trace("Reflecting closest method");
            Method method = listener.getClass().getMethod("gerritEvent", event.getClass());
            method.invoke(listener, event);
        } catch (IllegalAccessException ex) {
            logger.debug("Not allowed to invoke the reflected method. Calling default.", ex);
            listener.gerritEvent(event);
        } catch (IllegalArgumentException ex) {
            logger.debug("Not allowed to invoke the reflected method with specified parameter (REFLECTION BUG). "
               + "Calling default.", ex);
            listener.gerritEvent(event);
        } catch (InvocationTargetException ex) {
            logger.error("Exception thrown during event handling.", ex);
        } catch (NoSuchMethodException ex) {
            logger.debug("No apropriate method found during reflection. Calling default.", ex);
            listener.gerritEvent(event);
        } catch (SecurityException ex) {
            logger.debug("Not allowed to reflect/invoke a method on this listener (DESIGN BUG). Calling default", ex);
            listener.gerritEvent(event);
        }
    }

    /**
     * Checks if the event should be ignored, due to a circular CommentAdded.
     * @param event the event to check.
     * @return true if it should be ignored, false if not.
     */
    private boolean ignoreEvent(CommentAdded event) {
        Account account = event.getAccount();
        if (account == null) {
            return false;
        }
        Provider provider = event.getProvider();
        if (provider != null) {
            String ignoreEMail = ignoreEMails.get(provider.getName());
            if (ignoreEMail != null && ignoreEMail.equals(account.getEmail())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the handler.
     *
     * @param join if the method should wait for the thread to finish before returning.
     */
    public void shutdown(boolean join) {
        for (EventThread worker : workers) {
            worker.shutdown();
        }
        workers.clear();
    }

    /**
     * "Triggers" an event by adding it to the internal queue and be taken by one of the worker threads. This way it
     * will be put into the normal flow of events as if it was coming from the stream-events command.
     *
     * @param event the event to trigger.
     */
    @Deprecated
    public void triggerEvent(GerritEvent event) {
        logger.debug("Internally trigger event: {}", event);
        try {
            logger.trace("putting work on queue.");
            workQueue.put(new GerritEventWork(event));
        } catch (InterruptedException ex) {
            logger.error("Interrupted while putting work on queue!", ex);
        }
    }
}
