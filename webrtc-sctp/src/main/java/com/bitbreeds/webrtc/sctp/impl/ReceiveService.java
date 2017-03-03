package com.bitbreeds.webrtc.sctp.impl;

import com.bitbreeds.webrtc.common.SackUtil;
import com.bitbreeds.webrtc.common.SignalUtil;
import com.bitbreeds.webrtc.sctp.model.*;
import org.pcollections.HashTreePSet;
import org.pcollections.MapPSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.bitbreeds.webrtc.common.SignalUtil.*;

/**
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
 * This class handles the state for received payloads and TSNs and creating a SACK from those.
 * This should implement the below, though currently it does probably not do so well.
 *
 * @see <a hred="https://tools.ietf.org/html/rfc4960#section-3.3.4">SCTP SACK spec</a>
 * @see <a href="https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-09#section-8.2.1">datachannel spec</a>
 *
 */
public class ReceiveService {

    private static final Logger logger = LoggerFactory.getLogger(ReceiveService.class);
    public enum TsnStatus {DUPLICATE,FIRST};

    /**
     * Accepted as a duplicate if within this range,
     * otherwise new message.
     */
    private static final int TSN_DIFF = 1000000000;

    /**
     * A set of received TSNS
     * This uses a persistent collection, which is easy to work with
     * when we need to pull it and create a SACK.
     */
    protected MapPSet<Long> receivedTSNS = HashTreePSet.empty();

    /**
     * A list of duplicates since the last SACK
     * Reset when creating SACK
     */
    protected List<Long> duplicatesSinceLast = new ArrayList<>();

    /**
     * Mutex to access TSNS and duplicate data
     */
    private final Object sackMutex = new Object();

    /**
     * Received
     */
    protected volatile long cumulativeTSN = -1L;

    public ReceiveService() {}

    /**
     * Data needed to create a SACK
     */
    private class SackData {
        final MapPSet<Long> tsns;
        final List<Long> duplicates;

        public SackData(MapPSet<Long> tsns, List<Long> duplicates) {
            this.tsns = tsns;
            this.duplicates = duplicates;
        }

        @Override
        public String toString() {
            return "SackData{" +
                    "tsns=" + tsns +
                    ", duplicates=" + duplicates +
                    '}';
        }
    }

    /**
     *
     * @param a a TSN
     * @param b a TSN
     * @return If the two TSNs are too far apart,the TSN has looped.
     */
    private long cmp(long a,long b) {
        if(Math.abs(a-b) < TSN_DIFF) {
            return Math.min(a,b);
        }
        else {
           return Math.max(a,b);
        }
    }



    /**
     *
     * @param tsn tsn
     * @param min min tsn given
     * @return whether we are below the given tsn or too far away.
     */
    private boolean isBelow(long tsn,long min) {
        return tsn < min && Math.abs(tsn-min) < TSN_DIFF;
    }

    /**
     *
     * @return list of received messages for batch sacking
     */
    private SackData getAndSetSackTSNList() {
        List<Long> duplicates;
        MapPSet<Long> tsnTmp;
        synchronized (sackMutex) {
            tsnTmp = receivedTSNS;
            duplicates = duplicatesSinceLast;
            duplicatesSinceLast = new ArrayList<>();
        }
        return new SackData(tsnTmp,duplicates);
    }

    /**
     * We can remove all received TSNS with no gaps before them
     * @param l the highest TSNS with no gap before it.
     * @return true if new value, false otherwise
     */
    private boolean updateLowestTSN(long l) {
        Collection<Long> ls = receivedTSNS.stream()
                .filter( i -> isBelow(i,l) )
                .collect(Collectors.toList());

        synchronized (sackMutex) {
            if(cumulativeTSN == l) {
                return false;
            }
            else {
                receivedTSNS = receivedTSNS.minusAll(ls);
                cumulativeTSN = l;
                return true;
            }
        }
    }


    /**
     *
     * @param tsn to evaluate
     * @return whether the TSN has been received before or not.
     */
    public TsnStatus handleTSN(long tsn) {
        synchronized (sackMutex) {
            if(receivedTSNS.contains(tsn) ||
                    (tsn <= cumulativeTSN && Math.abs(tsn-cumulativeTSN) < TSN_DIFF) ) {
                duplicatesSinceLast.add(tsn);
                return TsnStatus.DUPLICATE;
            }
            else {
                receivedTSNS = receivedTSNS.plus(tsn);
                return TsnStatus.FIRST;
            }
        }
    }



    /**
     * @return attempt to create a SCTP SACK message.
     */
    public Optional<SCTPMessage> createSack(SCTPHeader header ) {

        SackData tsn = getAndSetSackTSNList(); //Pull sack data

        logger.trace("Got sack data: " + tsn);
        if(tsn.tsns.isEmpty()) {
            return Optional.empty();
        }

        //Find the minimum in the relevant window
        final Long min = tsn.tsns.stream()
                .reduce(this::cmp)
                .orElseThrow(() -> new IllegalStateException("Should not happen!"));

        //Remove all non relevant data. If the tsn flipped this must happen
        Set<Long> relevant = tsn.tsns.stream().filter(i -> i >= min).collect(Collectors.toSet());

        //Calculate gap acks from only relevant data.
        List<SackUtil.GapAck> acks = SackUtil.getGapAckList(relevant);

        long lastBeforeGapTsn = acks.get(0).end;

        boolean updated = updateLowestTSN(lastBeforeGapTsn);

        /**
         * No sack needed already at latest
         */
        if(!updated && acks.size() == 1 && tsn.duplicates.size() == 0) {
            return Optional.empty();
        }

        List<byte[]> varData = new ArrayList<>();

        for(int i = 1; i<acks.size(); i++) {
            SackUtil.GapAck ack = acks.get(i);
            int start = (int)(ack.start - lastBeforeGapTsn);
            int end = (int)(ack.end - lastBeforeGapTsn);
            varData.add(twoBytesFromInt(start));
            varData.add(twoBytesFromInt(end));
        }

        for (Long l : tsn.duplicates) {
            varData.add(longToFourBytes(l));
        }

        HashMap<SCTPFixedAttributeType,SCTPFixedAttribute> fixed = new HashMap<>();
        SCTPFixedAttribute cum_tsn =
                new SCTPFixedAttribute(SCTPFixedAttributeType.CUMULATIVE_TSN_ACK,
                        longToFourBytes(lastBeforeGapTsn));

        SCTPFixedAttribute arcw =
                new SCTPFixedAttribute(SCTPFixedAttributeType.ARWC,
                        longToFourBytes(20000));

        SCTPFixedAttribute num_gap =
                new SCTPFixedAttribute(SCTPFixedAttributeType.NUM_GAP_BLOCKS,
                        twoBytesFromInt(varData.size()));

        SCTPFixedAttribute num_dupl =
                new SCTPFixedAttribute(SCTPFixedAttributeType.NUM_DUPLICATE,
                        twoBytesFromInt(tsn.duplicates.size()));


        fixed.put(SCTPFixedAttributeType.CUMULATIVE_TSN_ACK,
                cum_tsn);
        fixed.put(SCTPFixedAttributeType.ARWC,arcw);
        fixed.put(SCTPFixedAttributeType.NUM_GAP_BLOCKS,num_gap);
        fixed.put(SCTPFixedAttributeType.NUM_DUPLICATE,num_dupl);

        int sum = fixed.keySet().stream()
                .map(SCTPFixedAttributeType::getLgt).reduce(0, Integer::sum);

        byte[] data = joinBytesArrays(varData);

        SCTPChunk sack = new SCTPChunk(
                SCTPMessageType.SELECTIVE_ACK,
                SCTPFlags.fromValue((byte)0),
                4 + sum + data.length,
                fixed,
                new HashMap<>(),
                SignalUtil.padToMultipleOfFour(data)
        );

        SCTPMessage msg = new SCTPMessage(header, Collections.singletonList(sack));

        logger.debug("Sending sack data: " + msg);
        return Optional.of(SCTPUtil.addChecksum(msg));
    }


}