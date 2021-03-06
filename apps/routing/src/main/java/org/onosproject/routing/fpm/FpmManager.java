/*
 * Copyright 2016 Open Networking Laboratory
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
 */
package org.onosproject.routing.fpm;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Service;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onosproject.routing.RouteEntry;
import org.onosproject.routing.RouteListener;
import org.onosproject.routing.RouteSourceService;
import org.onosproject.routing.RouteUpdate;
import org.onosproject.routing.fpm.protocol.FpmHeader;
import org.onosproject.routing.fpm.protocol.Netlink;
import org.onosproject.routing.fpm.protocol.RouteAttribute;
import org.onosproject.routing.fpm.protocol.RouteAttributeDst;
import org.onosproject.routing.fpm.protocol.RouteAttributeGateway;
import org.onosproject.routing.fpm.protocol.RtNetlink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.onlab.util.Tools.groupedThreads;

/**
 * Forwarding Plane Manager (FPM) route source.
 */
@Service
@Component(immediate = true, enabled = false)
public class FpmManager implements RouteSourceService {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private ServerBootstrap serverBootstrap;
    private Channel serverChannel;
    private ChannelGroup allChannels = new DefaultChannelGroup();

    private Map<IpPrefix, RouteEntry> fpmRoutes = new ConcurrentHashMap<>();

    private RouteListener routeListener;

    private static final int FPM_PORT = 2620;

    @Activate
    protected void activate() {
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        stopServer();
        log.info("Stopped");
    }

    private void startServer() {
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(
                newCachedThreadPool(groupedThreads("onos/fpm", "sm-boss-%d")),
                newCachedThreadPool(groupedThreads("onos/fpm", "sm-worker-%d")));
        ChannelPipelineFactory pipelineFactory = () -> {
            // Allocate a new session per connection
            FpmSessionHandler fpmSessionHandler =
                    new FpmSessionHandler(new InternalFpmListener());
            FpmFrameDecoder fpmFrameDecoder =
                    new FpmFrameDecoder();

            // Setup the processing pipeline
            ChannelPipeline pipeline = Channels.pipeline();
            pipeline.addLast("FpmFrameDecoder", fpmFrameDecoder);
            pipeline.addLast("FpmSession", fpmSessionHandler);
            return pipeline;
        };

        InetSocketAddress listenAddress = new InetSocketAddress(FPM_PORT);

        serverBootstrap = new ServerBootstrap(channelFactory);
        serverBootstrap.setOption("child.reuseAddr", true);
        serverBootstrap.setOption("child.keepAlive", true);
        serverBootstrap.setOption("child.tcpNoDelay", true);
        serverBootstrap.setPipelineFactory(pipelineFactory);
        try {
            serverChannel = serverBootstrap.bind(listenAddress);
            allChannels.add(serverChannel);
        } catch (ChannelException e) {
            log.debug("Exception binding to FPM port {}: ",
                    listenAddress.getPort(), e);
            stopServer();
        }
    }

    private void stopServer() {
        allChannels.close().awaitUninterruptibly();
        allChannels.clear();
        if (serverBootstrap != null) {
            serverBootstrap.releaseExternalResources();
        }
    }

    @Override
    public void start(RouteListener routeListener) {
        this.routeListener = routeListener;

        startServer();
    }

    @Override
    public void stop() {
        fpmRoutes.clear();
        stopServer();
    }

    private void fpmMessage(FpmHeader fpmMessage) {
        Netlink netlink = fpmMessage.netlink();
        RtNetlink rtNetlink = netlink.rtNetlink();

        if (log.isTraceEnabled()) {
            log.trace("Received FPM message: {}", fpmMessage);
        }

        IpAddress dstAddress = null;
        IpAddress gateway = null;

        for (RouteAttribute attribute : rtNetlink.attributes()) {
            if (attribute.type() == RouteAttribute.RTA_DST) {
                RouteAttributeDst raDst = (RouteAttributeDst) attribute;
                dstAddress = raDst.dstAddress();
            } else if (attribute.type() == RouteAttribute.RTA_GATEWAY) {
                RouteAttributeGateway raGateway = (RouteAttributeGateway) attribute;
                gateway = raGateway.gateway();
            }
        }

        if (dstAddress == null) {
            log.error("Dst address missing!");
            return;
        }

        IpPrefix prefix = IpPrefix.valueOf(dstAddress, rtNetlink.dstLength());

        RouteUpdate routeUpdate = null;
        RouteEntry entry;
        switch (netlink.type()) {
        case RTM_NEWROUTE:
            if (gateway == null) {
                // We ignore interface routes with no gateway for now.
                return;
            }
            entry = new RouteEntry(prefix, gateway);

            fpmRoutes.put(entry.prefix(), entry);

            routeUpdate = new RouteUpdate(RouteUpdate.Type.UPDATE, entry);
            break;
        case RTM_DELROUTE:
            RouteEntry existing = fpmRoutes.remove(prefix);
            if (existing == null) {
                log.warn("Got delete for non-existent prefix");
                return;
            }

            entry = new RouteEntry(prefix, existing.nextHop());

            routeUpdate = new RouteUpdate(RouteUpdate.Type.DELETE, entry);
            break;
        case RTM_GETROUTE:
        default:
            break;
        }

        if (routeUpdate == null) {
            log.warn("Unsupported FPM message: {}", fpmMessage);
            return;
        }

        routeListener.update(Collections.singletonList(routeUpdate));
    }

    private class InternalFpmListener implements FpmMessageListener {
        @Override
        public void fpmMessage(FpmHeader fpmMessage) {
            FpmManager.this.fpmMessage(fpmMessage);
        }
    }

}
