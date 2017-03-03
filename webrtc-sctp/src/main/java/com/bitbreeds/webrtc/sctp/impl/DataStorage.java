package com.bitbreeds.webrtc.sctp.impl;

/**
 * Copyright (c) 29/06/16, Jonas Waage
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

import com.bitbreeds.webrtc.sctp.model.SCTPFlags;
import com.bitbreeds.webrtc.common.SCTPPayloadProtocolId;

/**
 * Stores the parts of the payload needed for ordering
 *
 * Only necessary if I care about implementing ordering.
 */
public class DataStorage {

    private final long TSN;
    private final int streamId;
    private final int streamSequence;
    private final SCTPFlags flags;
    private final SCTPPayloadProtocolId protocolId;
    private final byte[] payload;

    public DataStorage(long TSN,
                       int streamId,
                       int streamSequence,
                       SCTPFlags flags,
                       SCTPPayloadProtocolId protocolId,
                       byte[] payload) {

        this.TSN = TSN;
        this.streamId = streamId;
        this.streamSequence = streamSequence;
        this.flags = flags;
        this.protocolId = protocolId;
        this.payload = payload;
    }

    public long getTSN() {
        return TSN;
    }

    public int getStreamId() {
        return streamId;
    }

    public int getStreamSequence() {
        return streamSequence;
    }

    public SCTPFlags getFlags() {
        return flags;
    }

    public SCTPPayloadProtocolId getProtocolId() {
        return protocolId;
    }

    public byte[] getPayload() {
        return payload;
    }
}