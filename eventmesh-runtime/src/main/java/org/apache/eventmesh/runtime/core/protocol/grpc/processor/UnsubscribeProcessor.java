/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.core.protocol.grpc.processor;

import org.apache.eventmesh.common.protocol.SubscriptionItem;
import org.apache.eventmesh.common.protocol.grpc.cloudevents.CloudEvent;
import org.apache.eventmesh.common.protocol.grpc.common.EventMeshCloudEventUtils;
import org.apache.eventmesh.common.protocol.grpc.common.StatusCode;
import org.apache.eventmesh.common.utils.JsonUtils;
import org.apache.eventmesh.runtime.boot.EventMeshGrpcServer;
import org.apache.eventmesh.runtime.core.protocol.grpc.consumer.ConsumerManager;
import org.apache.eventmesh.runtime.core.protocol.grpc.consumer.EventMeshConsumer;
import org.apache.eventmesh.runtime.core.protocol.grpc.consumer.consumergroup.ConsumerGroupClient;
import org.apache.eventmesh.runtime.core.protocol.grpc.service.EventEmitter;
import org.apache.eventmesh.runtime.core.protocol.grpc.service.ServiceUtils;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UnsubscribeProcessor {

    private final EventMeshGrpcServer eventMeshGrpcServer;

    public UnsubscribeProcessor(EventMeshGrpcServer eventMeshGrpcServer) {
        this.eventMeshGrpcServer = eventMeshGrpcServer;
    }

    public void process(CloudEvent subscription, EventEmitter<CloudEvent> emitter) throws Exception {

        if (!ServiceUtils.validateCloudEventAttributes(subscription)) {
            ServiceUtils.completed(StatusCode.EVENTMESH_PROTOCOL_HEADER_ERR, emitter);
            return;
        }

        if (!ServiceUtils.validateSubscription(null, subscription)) {
            ServiceUtils.completed(StatusCode.EVENTMESH_PROTOCOL_BODY_ERR, emitter);
            return;
        }

        ConsumerManager consumerManager = eventMeshGrpcServer.getConsumerManager();

        String consumerGroup = EventMeshCloudEventUtils.getConsumerGroup(subscription);
        String url = EventMeshCloudEventUtils.getURL(subscription);
        List<SubscriptionItem> subscriptionItems = JsonUtils.parseTypeReferenceObject(subscription.getTextData(),
            new TypeReference<List<SubscriptionItem>>() {
            });
        final String env = EventMeshCloudEventUtils.getEnv(subscription);
        final String idc = EventMeshCloudEventUtils.getIdc(subscription);
        final String sys = EventMeshCloudEventUtils.getSys(subscription);
        final String ip = EventMeshCloudEventUtils.getIp(subscription);
        final String pid = EventMeshCloudEventUtils.getPid(subscription);
        // Collect clients to remove in the unsubscribe
        List<ConsumerGroupClient> removeClients = new LinkedList<>();
        for (SubscriptionItem item : subscriptionItems) {
            ConsumerGroupClient newClient = ConsumerGroupClient.builder()
                .env(env)
                .idc(idc)
                .sys(sys)
                .ip(ip)
                .pid(pid)
                .consumerGroup(consumerGroup)
                .topic(item.getTopic())
                .subscriptionMode(item.getMode())
                .url(url)
                .lastUpTime(new Date())
                .build();
            removeClients.add(newClient);
        }

        // deregister clients from ConsumerManager
        for (ConsumerGroupClient client : removeClients) {
            consumerManager.deregisterClient(client);
        }

        // deregister clients from EventMeshConsumer
        EventMeshConsumer eventMeshConsumer = consumerManager.getEventMeshConsumer(consumerGroup);

        boolean requireRestart = false;
        for (ConsumerGroupClient client : removeClients) {
            if (eventMeshConsumer.deregisterClient(client)) {
                requireRestart = true;
            }
        }

        // restart consumer group if required
        if (requireRestart) {
            log.info("ConsumerGroup {} topic info changed, restart EventMesh Consumer", consumerGroup);
            consumerManager.restartEventMeshConsumer(consumerGroup);
        } else {
            log.warn("EventMesh consumer [{}] didn't restart.", consumerGroup);
        }

        ServiceUtils.completed(StatusCode.SUCCESS, "unsubscribe success", emitter);
    }
}
