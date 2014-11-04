/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.server.dhcp.mina.protocol;

import java.net.InetSocketAddress;
import javax.annotation.Nonnull;
import org.anarres.dhcp.common.address.AddressUtils;
import org.anarres.dhcp.common.address.InterfaceAddress;
import org.apache.directory.server.dhcp.messages.DhcpMessage;
import org.apache.directory.server.dhcp.messages.MessageType;
import org.apache.directory.server.dhcp.service.DhcpService;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a DHCP protocol handler which delegates the work of
 * generating replys to a DhcpService implementation.
 * 
 * @see org.apache.directory.server.dhcp.service.DhcpService
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class DhcpProtocolHandler extends IoHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DhcpProtocolHandler.class);
    /**
     * The DHCP service implementation. The implementation is supposed to be
     * thread-safe.
     */
    private final DhcpService dhcpService;

    /**
     * 
     */
    public DhcpProtocolHandler(@Nonnull DhcpService service) {
        this.dhcpService = service;
    }

    @Override
    public void sessionCreated(IoSession session) throws Exception {
        logger.debug("{} CREATED", session.getLocalAddress());
        session.getFilterChain().addFirst("codec",
                new ProtocolCodecFilter(DhcpProtocolCodecFactory.getInstance()));
    }

    @Override
    public void sessionOpened(IoSession session) {
        logger.debug("{} -> {} OPENED", session.getRemoteAddress(), session
                .getLocalAddress());
    }

    @Override
    public void sessionClosed(IoSession session) {
        logger.debug("{} -> {} CLOSED", session.getRemoteAddress(), session
                .getLocalAddress());
    }

    @Override
    public void sessionIdle(IoSession session, IdleStatus status) {
        // ignore
    }

    @Override
    public void messageReceived(IoSession session, Object message)
            throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("{} -> {} RCVD: {} " + message, session.getRemoteAddress(),
                    session.getLocalAddress());
        }

        // This doesn't work in practice. Pass the InterfaceAddress to the constructor.
        InetSocketAddress localSocketAddress = (InetSocketAddress) session.getLocalAddress();
        InterfaceAddress localAddress = new InterfaceAddress(localSocketAddress.getAddress(), 0);

        InetSocketAddress remoteAddress = (InetSocketAddress) session.getRemoteAddress();

        DhcpMessage request = (DhcpMessage) message;
        DhcpMessage reply = dhcpService.getReplyFor(
                localAddress, remoteAddress,
                request);

        if (reply != null) {
            InetSocketAddress isa = determineMessageDestination(request, reply, localAddress, remoteAddress.getPort());
            session.write(reply, isa);
        }
    }

    /**
     * Determine where to send the message: <br>
     * If the 'giaddr' field in a DHCP message from a client is non-zero, the
     * server sends any return messages to the 'DHCP server' port on the BOOTP
     * relay agent whose address appears in 'giaddr'. If the 'giaddr' field is
     * zero and the 'ciaddr' field is nonzero, then the server unicasts DHCPOFFER
     * and DHCPACK messages to the address in 'ciaddr'. If 'giaddr' is zero and
     * 'ciaddr' is zero, and the broadcast bit is set, then the server broadcasts
     * DHCPOFFER and DHCPACK messages to 0xffffffff. If the broadcast bit is not
     * set and 'giaddr' is zero and 'ciaddr' is zero, then the server unicasts
     * DHCPOFFER and DHCPACK messages to the client's hardware address and
     * 'yiaddr' address. In all cases, when 'giaddr' is zero, the server
     * broadcasts any DHCPNAK messages to 0xffffffff.
     */
    //This will suppress PMD.AvoidUsingHardCodedIP warnings in this class
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private InetSocketAddress determineMessageDestination(DhcpMessage request, DhcpMessage reply,
            InterfaceAddress localAddress, int remotePort) {
        if (!AddressUtils.isZeroAddress(request.getRelayAgentAddress())) {
            // send to agent, if received via agent.
            return new InetSocketAddress(request.getRelayAgentAddress(), DhcpService.SERVER_PORT);
        } else if (reply.getMessageType() == MessageType.DHCPNAK) {
            // force broadcast for DHCPNAKs
            return new InetSocketAddress(localAddress.getBroadcastAddress(), remotePort);
        } else if (!AddressUtils.isZeroAddress(request.getCurrentClientAddress())) {
            // have a current address? unicast to it.
            return new InetSocketAddress(request.getCurrentClientAddress(), remotePort);
        } else {
            // not a NAK...
            return new InetSocketAddress(localAddress.getBroadcastAddress(), remotePort);
        }
    }

    @Override
    public void messageSent(IoSession session, Object message) {
        if (logger.isDebugEnabled()) {
            logger.debug("{} -> {} SENT: " + message, session.getRemoteAddress(),
                    session.getLocalAddress());
        }
    }

    @Override
    public void exceptionCaught(IoSession session, Throwable cause) {
        logger.error("EXCEPTION CAUGHT ", cause);
        cause.printStackTrace(System.out);
        session.close(true);
    }
}
