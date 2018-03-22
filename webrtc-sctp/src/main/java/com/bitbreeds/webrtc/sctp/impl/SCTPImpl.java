package com.bitbreeds.webrtc.sctp.impl;

import com.bitbreeds.webrtc.common.ByteRange;
import com.bitbreeds.webrtc.common.DataChannel;
import com.bitbreeds.webrtc.common.SCTPPayloadProtocolId;
import com.bitbreeds.webrtc.common.SignalUtil;
import com.bitbreeds.webrtc.sctp.impl.buffer.BufferedReceived;
import com.bitbreeds.webrtc.sctp.impl.buffer.BufferedSent;
import com.bitbreeds.webrtc.sctp.impl.buffer.ReceiveBuffer;
import com.bitbreeds.webrtc.sctp.impl.buffer.WireRepresentation;
import com.bitbreeds.webrtc.sctp.model.*;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bitbreeds.webrtc.common.SignalUtil.*;

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
 * TODO implement proper congestion control (HUGE!!!)
 * TODO figure out why transfer is slow (BIG!!)
 * TODO implement JMX hooks, so transfer parameters can be adjusted at runtime
 *
 * @see <a href="https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-09#section-8.2.1">datachannel spec</a>
 *
 */
public class SCTPImpl implements SCTP  {

    private static final Logger logger = LoggerFactory.getLogger(SCTPImpl.class);

    private ReliabilityParameters parameters =  null;

    private static int DEFAULT_BUFFER_SIZE = 160000;

    private final int localBufferSize = DEFAULT_BUFFER_SIZE;


    /**
     * The impl access to write data to the socket
     */
    private final DataChannel writer;

    /**
     * Handles received messages and sending if acknowledgements.
     * Also handles buffering of received messages and presentation to app layer.
     */
    private final ReceiveService receiver = new ReceiveService(this,localBufferSize);

    /**
     * Handles sending of messages and reception of acknowledgements.
     * Also handles buffering of sent messages and picture of remote state.
     */
    private final SendService sender = new SendService(this);

    private final HeartBeatService heartBeatService = new HeartBeatService();

    /**
     *
     * @param writer interface to socket
     */
    public SCTPImpl(DataChannel writer) {
        this.writer = writer;
    }

    /**
     *
     */
    private SCTPContext context;

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

    public ReceiveService getReceiver() {
        return receiver;
    }

    public SendService getSender() {
        return sender;
    }

    public HeartBeatService getHeartBeatService() {
        return heartBeatService;
    }

    public void setContext(SCTPContext context) {
        this.context = context;
    }




    /**
     *
     * @param data payload to send
     * @return messages to send now
     */
    public List<WireRepresentation> bufferForSending(byte[] data,SCTPPayloadProtocolId ppid,Integer stream) {

        List<BufferedSent> toSend = sender.bufferForSending(data,ppid,stream,SCTPUtil.baseHeader(context));

        return toSend.stream()
                .map(i-> new WireRepresentation(i.getData().getSctpPayload(),SCTPMessageType.DATA))
                .collect(Collectors.toList());
    }



    /**
     * @return message with acks
     */
    public Optional<WireRepresentation> createSackMessage() {
        if(context == null) {
            return Optional.empty();
        }
        Optional<SCTPMessage> message = receiver.createSack(SCTPUtil.baseHeader(context));
        return message.map(i->new WireRepresentation(i.toBytes(),SCTPMessageType.SELECTIVE_ACK));
    }


    /**
     * @return messages to resend
     */
    @Override
    public List<WireRepresentation> getMessagesForResend() {

        return sender.getMessagesForResend();
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

    /**
     * Run the open callback
     */
    protected void runOpen() {
        this.writer.runOpen();
    }

    /**
     * Run the onMessage callback
     * @param data input to callback
     */
    protected void runOnMessage(ReceivedData data) {

        /*
         * @see <a href="https://tools.ietf.org/html/draft-ietf-rtcweb-data-channel-12">data channel spec</a>
         */
        if(parameters == null && data.getProtocolId() == SCTPPayloadProtocolId.WEBRTC_DCEP) {
            final byte[] msgData = data.getPayload();
            DataChannelMessageType msg = DataChannelMessageType.fromInt(unsign(msgData[0]));

            if(DataChannelMessageType.OPEN.equals(msg)) {
                logger.debug("Received open: "+ Hex.encodeHexString(msgData));
                DataChannelType type = DataChannelType.fromInt(unsign(msgData[2]));
                DataChannelPriority priority = DataChannelPriority.fromInt(intFromTwoBytes(
                        copyRange(msgData,new ByteRange(2,4))));
                int relParam = intFromFourBytes( copyRange(msgData,new ByteRange(4,8) ));
                int labelLength = SignalUtil.intFromTwoBytes(copyRange(msgData,new ByteRange(8,10)));
                int protocolLength = SignalUtil.intFromTwoBytes(copyRange(msgData,new ByteRange(10,12)));
                byte[] label = SignalUtil.copyRange(msgData,new ByteRange(12,12+labelLength));
                byte[] protocol = SignalUtil.copyRange(msgData,
                        new ByteRange(12+labelLength,12+labelLength+protocolLength));

                /*
                 * Store params
                 */
                parameters = new ReliabilityParameters(
                        relParam,
                        type,
                        priority,
                        label,
                        protocol);

                logger.info("Updated channel with parameters: " + parameters);

                /*
                 * Send ack and do not process anymore
                 */
                byte[] ack = new byte[] {sign(DataChannelMessageType.ACK.getType())};
                this.writer.send(ack,SCTPPayloadProtocolId.WEBRTC_DCEP);

                logger.debug("Sending ack: "+ Hex.encodeHexString(ack));
            }
            else {
                throw new IllegalArgumentException("PPID " +SCTPPayloadProtocolId.WEBRTC_DCEP + " should be sent with " + DataChannelMessageType.OPEN);
            }
        }
        else {
            logger.trace("Flags: " + data.getFlag() + " Stream: " + data.getStreamId() + " Stream seq: " + data.getStreamSequence());
            logger.trace("Data as hex: " + Hex.encodeHexString(data.getPayload()));
            logger.trace("Data as string: " + new String(data.getPayload()) + ":");

            boolean shouldSack = receiver.handleReceive(data);
            if(shouldSack) {
                createSackMessage().ifPresent(i->
                    getDataChannel().putDataOnWire(i.getPayload())
                );
            }

        }
    }


    /**
     * Print relevant monitoring and debugging data.
     */
    public void runMonitoring() {
        logger.info("---------------------------------------------");
        logger.info("Inflight: " + sender.inflightMessages());
        logger.info("CumulativeReceivedTSN: " + receiver.getCumulativeTSN());
        logger.info("MyTsn: " + sender.currentTSN());
        logger.info("Total received bytes: " + receiver.getReceivedBytes());
        logger.info("Total delivered bytes to user: " + receiver.getDeliveredBytes());
        logger.info("Total sent bytes: " + sender.getSentBytes());
        logger.info("RTT: " + heartBeatService.getRttMillis());
        logger.info("Remote buffer: " + sender.remoteBufferSize());
        logger.info("Local send buffer: " + sender.capacity());
        logger.info("Local buffer: " + receiver.getBufferCapacity());
    }

    @Override
    public DataChannel getDataChannel() {
        return writer;
    }
}
