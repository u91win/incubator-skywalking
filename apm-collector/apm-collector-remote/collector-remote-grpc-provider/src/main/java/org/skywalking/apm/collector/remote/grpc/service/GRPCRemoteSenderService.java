/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.collector.remote.grpc.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.skywalking.apm.collector.cluster.ClusterModuleListener;
import org.skywalking.apm.collector.core.UnexpectedException;
import org.skywalking.apm.collector.core.data.Data;
import org.skywalking.apm.collector.remote.RemoteModule;
import org.skywalking.apm.collector.remote.grpc.RemoteModuleGRPCProvider;
import org.skywalking.apm.collector.remote.grpc.service.selector.ForeverFirstSelector;
import org.skywalking.apm.collector.remote.grpc.service.selector.HashCodeSelector;
import org.skywalking.apm.collector.remote.grpc.service.selector.RollingSelector;
import org.skywalking.apm.collector.remote.service.RemoteClient;
import org.skywalking.apm.collector.remote.service.RemoteSenderService;
import org.skywalking.apm.collector.remote.service.Selector;

/**
 * @author peng-yongsheng
 */
public class GRPCRemoteSenderService extends ClusterModuleListener implements RemoteSenderService {

    public static final String PATH = "/" + RemoteModule.NAME + "/" + RemoteModuleGRPCProvider.NAME;
    private final GRPCRemoteClientService service;
    private List<RemoteClient> remoteClients;
    private final String selfAddress;
    private final HashCodeSelector hashCodeSelector;
    private final ForeverFirstSelector foreverFirstSelector;
    private final RollingSelector rollingSelector;

    @Override public Mode send(int graphId, int nodeId, Data data, Selector selector) {
        RemoteClient remoteClient;
        switch (selector) {
            case HashCode:
                remoteClient = hashCodeSelector.select(remoteClients, data);
                return sendToRemoteWhenNotSelf(remoteClient, graphId, nodeId, data);
            case Rolling:
                remoteClient = rollingSelector.select(remoteClients, data);
                return sendToRemoteWhenNotSelf(remoteClient, graphId, nodeId, data);
            case ForeverFirst:
                remoteClient = foreverFirstSelector.select(remoteClients, data);
                return sendToRemoteWhenNotSelf(remoteClient, graphId, nodeId, data);
        }
        throw new UnexpectedException("Selector not match, Just support hash, rolling, forever first selector.");
    }

    private Mode sendToRemoteWhenNotSelf(RemoteClient remoteClient, int graphId, int nodeId, Data data) {
        if (remoteClient.equals(selfAddress)) {
            return Mode.Local;
        } else {
            remoteClient.send(graphId, nodeId, data);
            return Mode.Remote;
        }
    }

    public GRPCRemoteSenderService(String host, int port) {
        this.service = new GRPCRemoteClientService();
        this.remoteClients = new ArrayList<>();
        this.selfAddress = host + ":" + String.valueOf(port);
        this.hashCodeSelector = new HashCodeSelector();
        this.foreverFirstSelector = new ForeverFirstSelector();
        this.rollingSelector = new RollingSelector();
    }

    @Override public String path() {
        return PATH;
    }

    @Override public synchronized void serverJoinNotify(String serverAddress) {
        List<RemoteClient> newRemoteClients = new ArrayList<>();
        newRemoteClients.addAll(remoteClients);

        String host = serverAddress.split(":")[0];
        int port = Integer.parseInt(serverAddress.split(":")[1]);
        RemoteClient remoteClient = service.create(host, port);
        newRemoteClients.add(remoteClient);

        Collections.sort(newRemoteClients);

        this.remoteClients = newRemoteClients;
    }

    @Override public synchronized void serverQuitNotify(String serverAddress) {
        List<RemoteClient> newRemoteClients = new ArrayList<>();
        newRemoteClients.addAll(remoteClients);

        for (int i = newRemoteClients.size() - 1; i >= 0; i--) {
            RemoteClient remoteClient = newRemoteClients.get(i);
            if (remoteClient.equals(serverAddress)) {
                newRemoteClients.remove(i);
            }
        }

        this.remoteClients = newRemoteClients;
    }
}
