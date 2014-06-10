/*
 * Copyright (c) 2014 Hewlett-Packard Development Company, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hpcloud.mon.infrastructure.thresholding;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.base.BaseRichSpout;
import com.hpcloud.configuration.KafkaConsumerConfiguration;
import com.hpcloud.configuration.KafkaConsumerProperties;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public abstract class KafkaSpout extends BaseRichSpout implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSpout.class);

    private static final long serialVersionUID = 744004533863562119L;

    private final KafkaConsumerConfiguration kafkaConsumerConfig;

    private transient ConsumerConnector consumerConnector;

    private transient List<KafkaStream<byte[], byte[]>> streams = null;

    private SpoutOutputCollector collector;

    private volatile boolean shouldContinue;

    private byte[] message;

    private Thread readerThread;

    private String spoutName;

    protected KafkaSpout(KafkaConsumerConfiguration kafkaConsumerConfig) {
        this.kafkaConsumerConfig = kafkaConsumerConfig;
    }

    @Override
    public void activate() {
        LOG.info("Activated");
        if (streams == null) {
            Map<String, Integer> topicCountMap = new HashMap<>();
            topicCountMap.put(kafkaConsumerConfig.getTopic(), new Integer(1));
            Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumerConnector.createMessageStreams(topicCountMap);
            streams = consumerMap.get(kafkaConsumerConfig.getTopic());
        }
    }

    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        LOG.info("Opened");
        this.collector = collector;
        LOG.info(" topic = " + kafkaConsumerConfig.getTopic());
        this.spoutName = String.format("%s-%d", context.getThisComponentId(), context.getThisTaskId());

        Properties kafkaProperties = KafkaConsumerProperties.createKafkaProperties(kafkaConsumerConfig);
        // Have to use a different consumer.id for each spout so use the storm taskId. Otherwise,
        // zookeeper complains about a conflicted ephemeral node when there is more than one spout
        // reading from a topic
        kafkaProperties.setProperty("consumer.id", String.valueOf(context.getThisTaskId()));
        ConsumerConfig consumerConfig = new ConsumerConfig(kafkaProperties);
        this.consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);
    }

    @Override
    public synchronized void deactivate() {
        LOG.info("deactivated");
        this.consumerConnector.shutdown();
        this.shouldContinue = false;
        // Wake up the reader thread if it is waiting 
        notify();
    }

    @Override
    public void run() {
        while (this.shouldContinue) {
            final ConsumerIterator<byte[], byte[]> it = streams.get(0).iterator();
            if (it.hasNext()) {
                LOG.debug("streams iterator has next");
                final byte[] message = it.next().message();
                synchronized (this) {
                    this.message = message;
                    while (this.message != null && this.shouldContinue)
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            LOG.info("Wait interrupted", e);
                        }
                }
            }    
        }
        LOG.info("readerThread {} exited", this.readerThread.getName());
        this.readerThread = null;
    }

    @Override
    public void nextTuple() {
        LOG.debug("nextTuple called");
        checkReaderRunning();
        final byte[] message = getMessage();
        if (message != null) {
            LOG.debug("streams iterator has next");
            processMessage(message, collector);
        }
    }

    private void checkReaderRunning() {
        this.shouldContinue = true;
        if (this.readerThread == null) {
            final String threadName = String.format("%s reader", this.spoutName);
            this.readerThread = new Thread(this, threadName);
            this.readerThread.start();
            LOG.info("Started Reader Thread {}", this.readerThread.getName());
        }
    }

    private synchronized byte[] getMessage() {
        final byte[] result = this.message;
        if (result != null) {
            this.message = null;
            notify();
        }
        else {
            // Storm docs recommend a short sleep
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                LOG.info("Sleep interrupted", e);
            }
        }
        return result;
    }

    protected abstract void processMessage(byte[] message, SpoutOutputCollector collector2);
}
