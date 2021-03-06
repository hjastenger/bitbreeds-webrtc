package com.bitbreeds.webrtc.sctp.impl;

import com.bitbreeds.webrtc.model.webrtc.*;
import com.bitbreeds.webrtc.model.sctp.SCTPPayloadProtocolId;
import com.bitbreeds.webrtc.sctp.impl.buffer.*;
import com.bitbreeds.webrtc.sctp.impl.model.*;
import com.bitbreeds.webrtc.sctp.impl.util.SCTPUtil;
import com.bitbreeds.webrtc.sctp.model.*;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * Copyright (c) 19/05/16, Jonas Waage
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */


/**
 *
 * This class deals with handling SCTP messages and the flow control.
 * This includes keeping track of messages received on either side.
 * Resending messages, computing checksums, creating heartbeats. and so on.
 *
 * The handling of specific chunks is passed to the correct {@link MessageHandler}.
 *
 * TODO implement shutdown messages
 * @see <a href="https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-09#section-8.2.1">peerconnection spec</a>
 *
 * TODO implement state transitions, and more correct handling of messages when receiven in different states
 * TODO implement resend functionality
 */
public class SCTPImpl implements SCTP  {

    private static final Logger logger = LoggerFactory.getLogger(SCTPImpl.class);

    private static int DEFAULT_BUFFER_SIZE = 160000;

    private final int localBufferSize = DEFAULT_BUFFER_SIZE;

    private final static int DEFAULT_SEND_BUFFER_SIZE = 2000000;

    private final AtomicReference<SCTPState> state = new AtomicReference<>(SCTPState.CLOSED);

    /**
     * The impl access to write data to the socket
     */
    private final ConnectionInternalApi connection;

    private final ReceiveBuffer receiveBuffer =  new ReceiveBuffer(1000,localBufferSize);
    private final SendBuffer sendBuffer = new SendBuffer(DEFAULT_SEND_BUFFER_SIZE);
    private final PayloadCreator payloadCreator = new PayloadCreator();
    private final HeartBeatService heartBeatService = new HeartBeatService();
    private final RetransmissionScheduler retransmissionCalculator = new RetransmissionScheduler(this::doRetransmission);
    private final SingleTimedAction sackTimer = new SingleTimedAction(this::sendSack,200); //Not in use
    private SCTPContext context;

    /**
     * ChannelParameters
     */
    private final ConcurrentHashMap<Integer,ReliabilityParameters> dataChannels = new ConcurrentHashMap<>();

    /**
     *
     * @param connection interface to socket
     */
    public SCTPImpl(ConnectionInternalApi connection) {
        this.connection = connection;
    }

    /**
     *
     */
    private final Map<SCTPMessageType,MessageHandler> handlerMap = createHandlerMap();

    private Map<SCTPMessageType,MessageHandler> createHandlerMap() {
        HashMap<SCTPMessageType, MessageHandler> map = new HashMap<>();
        map.put(SCTPMessageType.INITIATION,new InitiationHandler());
        map.put(SCTPMessageType.COOKIE_ECHO,new CookieEchoHandler());
        map.put(SCTPMessageType.HEARTBEAT_ACK,new HeartBeatAckHandler());
        map.put(SCTPMessageType.HEARTBEAT,new HeartBeatHandler());
        map.put(SCTPMessageType.DATA,new PayloadHandler());
        map.put(SCTPMessageType.SELECTIVE_ACK,new SelectiveAckHandler());
        return map;
    }

    public void establish() {
        state.updateAndGet(SCTPState::moveToEstablished);
    }


    HeartBeatService getHeartBeatService() {
        return heartBeatService;
    }

    public void setContext(SCTPContext context) {
        this.context = context;
    }


    private void doRetransmission() {
        logger.info("Retransmission started {}" );
        List<BufferedSent> toSend = sendBuffer.getDataToRetransmit();
        retransmissionCalculator.restart();
        toSend.forEach(i ->
                getConnection().putDataOnWire(i.getData().getSctpPayload())
        );
    }

    /**
     * @param sackData acknowledgement
     */
    void updateAcknowledgedTSNS(SackData sackData) {
        logger.debug("Got sack {}",sackData );

        SackResult result = sendBuffer.receiveSack(sackData);
        if(sendBuffer.getInflightSize() == 0) {
            retransmissionCalculator.stop();
        }
        else if (result.isUpdatedCumulative()){
            retransmissionCalculator.restart();
        }

        result.getFastRetransmits().forEach(i ->
                getConnection().putDataOnWire(i.getData().getSctpPayload())
        );

        List<BufferedSent> toSend = sendBuffer.getDataToSend();

        toSend.forEach(i ->
                getConnection().putDataOnWire(i.getData().getSctpPayload())
        );
    }

    void initializeRemote(int remoteReceiveBufferSize,long initialTSN) {
        sendBuffer.initializeRemote(remoteReceiveBufferSize,initialTSN);
    }

    /**
     * Receive initial TSN
     */
    public void handleReceiveInitialTSN(long tsn) {
        receiveBuffer.setInitialTSN(tsn);
    }


    long getFirstTSN() {
        return payloadCreator.getFirstTSN();
    }

    /**
     *
     * @param data payload to send
     * @return messages to send now
     */
    public List<WireRepresentation> bufferForSending(byte[] data, SCTPPayloadProtocolId ppid, Integer stream) {
        List<SendData> messages = payloadCreator.createPayloadMessage(
                data,ppid,
                SCTPUtil.baseHeader(context),
                false,
                stream);

        sendBuffer.buffer(messages);
        List<BufferedSent> toSend = sendBuffer.getDataToSend();
        if(!toSend.isEmpty()) {
            retransmissionCalculator.start();
        }
        return toSend.stream()
                .map(i-> new WireRepresentation(i.getData().getSctpPayload(),SCTPMessageType.DATA))
                .collect(Collectors.toList());
    }

    /**
     * @return message with acks
     */
    private Optional<WireRepresentation> createSackMessage() {
        if(context == null) {
            return Optional.empty();
        }
        SackData sackData = receiveBuffer.getSackDataToSend();
        Optional<SCTPMessage> message = SackCreator.createSack(SCTPUtil.baseHeader(context),sackData);
        message.ifPresent(
                i-> logger.info("Created sack {} to send",i)
        );
        return message.map(i->new WireRepresentation(i.toBytes(),SCTPMessageType.SELECTIVE_ACK));
    }


    /**
     * @return heartbeat message
     */
    @Override
    public Optional<WireRepresentation> createHeartBeat() {
        return Optional.of(new WireRepresentation(
                heartBeatService.createHeartBeat(SCTPUtil.baseHeader(context)).toBytes(),
                SCTPMessageType.HEARTBEAT));
    }


    /**
     * Handle message and create a immediate response if needed
     * @param input the incoming message
     * @return responses
     */
    public List<WireRepresentation> handleRequest(byte[] input) {
        SCTPMessage inFullMessage = SCTPMessage.fromBytes(input);

        logger.debug("Input Parsed: " + inFullMessage );
        logger.debug("Flags: " + Hex.encodeHexString(new byte[]{input[13]}));

        SCTPHeader inHdr = inFullMessage.getHeader();
        List<SCTPChunk> inChunks = inFullMessage.getChunks();

        return inChunks.stream()
                .map(i->handleChunk(i,inHdr))
                .flatMap(i->i)
                .map(i->new WireRepresentation(SCTPUtil.addChecksum(i).toBytes(),i.getChunks().get(0).getType()))
                .collect(Collectors.toList());
    }

    /**
     *
     * @param chunk chunk data
     * @param hdr hdr of chunk
     * @return handle chunk by finding correct processing in handlermap
     */
    private Stream<SCTPMessage> handleChunk(SCTPChunk chunk, SCTPHeader hdr) {
        MessageHandler handler = handlerMap.get(chunk.getType());
        if (handler != null) {
            Optional<SCTPMessage> out = handler.handleMessage(this, context, hdr, chunk);
            return out.map(Stream::of).orElse(Stream.empty());
        } else {
            logger.warn("Not handled messagetype: " + chunk.getType());
            return Stream.empty();
        }
    }


    long getBufferCapacity() {
        return receiveBuffer.getCapacity();
    }


    /**
     * Handles reception of a payload
     *
     * @param data payload representation
     */
    void handleSctpPayload(ReceivedData data) {

        Objects.requireNonNull(data);

        logger.trace("Flags: " + data.getFlag() + " Stream: " + data.getStreamId() + " Stream seq: " + data.getStreamSequence());
        logger.trace("Data as hex: " + Hex.encodeHexString(data.getPayload()));
        logger.trace("Data as string: " + new String(data.getPayload()) + ":");

        receiveBuffer.store(data);
        List<Deliverable> deliverables = receiveBuffer.getMessagesForDelivery();
        sendSack(); //Sack all messages immediately
        deliverables.forEach(
                i -> getConnection().presentToUser(i)
        );
    }

    /**
     *
     */
    private void sendSack() {
        createSackMessage().ifPresent(i ->
                getConnection().putDataOnWire(i.getPayload())
        );
    }

    void receiveShutDown() {
        state.updateAndGet(SCTPState::receivedShutdown);

        /*
         * Todo send and wait for acks
         */

        state.updateAndGet(SCTPState::shutDownAck);

    }


    @Override
    public void shutdown() {

        state.updateAndGet(SCTPState::shutDown);
        /*
         * Todo move to shutdown state, if all sent and acked
         */
        state.updateAndGet(SCTPState::sendShutdown);

    }

    /**
     * Print relevant monitoring and debugging data
     */
    public void runMonitoring() {
        logger.info("---------------------------------------------");
        logger.info("Inflight: " + sendBuffer.getInflightSize());
        logger.info("CumulativeReceivedTSN: " + receiveBuffer.getCumulativeTSN());
        logger.info("MyTsn: " + payloadCreator.currentTSN());
        logger.info("Total received bytes: " + receiveBuffer.getReceivedBytes());
        logger.info("Total delivered bytes to user: " + receiveBuffer.getDeliveredBytes());
        logger.info("Total sent bytes: " + sendBuffer.getBytesSent());
        logger.info("RTT: " + heartBeatService.getRttMillis());
        logger.info("Remote buffer: " + sendBuffer.getRemoteBufferSize());
        logger.info("Local send buffer: " + sendBuffer.getCapacity());
        logger.info("Local buffer: " + receiveBuffer.getCapacity());
        logger.info("State: " + state.get());
    }

    @Override
    public ConnectionInternalApi getConnection() {
        return connection;
    }
}
