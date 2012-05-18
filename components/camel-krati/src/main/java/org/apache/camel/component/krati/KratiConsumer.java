/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.krati;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import krati.store.DataStore;
import org.apache.camel.BatchConsumer;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ShutdownRunningTask;
import org.apache.camel.impl.ScheduledPollConsumer;
import org.apache.camel.spi.ShutdownAware;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.util.CastUtils;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The Krati consumer.
 */
public class KratiConsumer extends ScheduledPollConsumer implements BatchConsumer, ShutdownAware {

    private static final transient Logger LOG = LoggerFactory.getLogger(KratiConsumer.class);

    protected final KratiEndpoint endpoint;
    protected DataStore dataStore;
    protected int maxMessagesPerPoll = 10;

    protected volatile ShutdownRunningTask shutdownRunningTask;
    protected volatile int pendingExchanges;

    public KratiConsumer(KratiEndpoint endpoint, Processor processor, DataStore dataStore) {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.dataStore = dataStore;
    }

    @Override
    protected int poll() throws Exception {
        shutdownRunningTask = null;
        pendingExchanges = 0;

        Queue<Exchange> queue = new LinkedList<Exchange>();

        Iterator keyIterator = dataStore.keyIterator();
        while (keyIterator.hasNext()) {
            Object key = keyIterator.next();
            Object value = dataStore.get(key);
            Exchange exchange = endpoint.createExchange();
            exchange.setProperty(KratiConstants.KEY, key);
            exchange.getIn().setBody(value);
            queue.add(exchange);
        }
        return queue.isEmpty() ? 0 : processBatch(CastUtils.cast(queue));
    }

    /**
     * Sets a maximum number of messages as a limit to poll at each polling.
     * <p/>
     * Can be used to limit eg to 100 to avoid when starting and there are millions
     * of messages for you in the first poll.
     * <p/>
     * Default value is 10.
     *
     * @param maxMessagesPerPoll maximum messages to poll.
     */
    @Override
    public void setMaxMessagesPerPoll(int maxMessagesPerPoll) {
        this.maxMessagesPerPoll = maxMessagesPerPoll;
    }

    @Override
    public int processBatch(Queue<Object> exchanges) throws Exception {
        int total = exchanges.size();

        for (int index = 0; index < total && isBatchAllowed(); index++) {
            // only loop if we are started (allowed to run)
            Exchange exchange = ObjectHelper.cast(Exchange.class, exchanges.poll());
            // add current index and total as properties
            exchange.setProperty(Exchange.BATCH_INDEX, index);
            exchange.setProperty(Exchange.BATCH_SIZE, total);
            exchange.setProperty(Exchange.BATCH_COMPLETE, index == total - 1);

            // update pending number of exchanges
            pendingExchanges = total - index - 1;

            // add on completion to handle after work when the exchange is done
            exchange.addOnCompletion(new Synchronization() {
                public void onComplete(Exchange exchange) {
                    try {
                        dataStore.delete(exchange.getProperty(KratiConstants.KEY));
                    } catch (Exception e) {
                        LOG.warn("Failed to remove from datastore.", e);
                    }
                }

                public void onFailure(Exchange exchange) {
                  //emtpy
                }
            });

            LOG.trace("Processing exchange [{}]...", exchange);
            getProcessor().process(exchange);
        }

        return total;
    }

    @Override
    public boolean isBatchAllowed() {
        // stop if we are not running
        boolean answer = isRunAllowed();
        if (!answer) {
            return false;
        }

        if (shutdownRunningTask == null) {
            // we are not shutting down so continue to run
            return true;
        }

        // we are shutting down so only continue if we are configured to complete all tasks
        return ShutdownRunningTask.CompleteAllTasks == shutdownRunningTask;
    }

    @Override
    public boolean deferShutdown(ShutdownRunningTask shutdownRunningTask) {
        // store a reference what to do in case when shutting down and we have pending messages
        this.shutdownRunningTask = shutdownRunningTask;
        // do not defer shutdown
        return false;
    }

    @Override
    public int getPendingExchangesSize() {
        int answer;
        // only return the real pending size in case we are configured to complete all tasks
        if (ShutdownRunningTask.CompleteAllTasks == shutdownRunningTask) {
            answer = pendingExchanges;
        } else {
            answer = 0;
        }

        if (answer == 0 && isPolling()) {
            // force at least one pending exchange if we are polling as there is a little gap
            // in the processBatch method and until an exchange gets enlisted as in-flight
            // which happens later, so we need to signal back to the shutdown strategy that
            // there is a pending exchange. When we are no longer polling, then we will return 0
            log.trace("Currently polling so returning 1 as pending exchanges");
            answer = 1;
        }

        return answer;
    }

    @Override
    public void prepareShutdown() {
    }
}