/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of TivoLibre. TivoLibre is derived from
 * TivoDecode 0.4.4 by Jeremy Drake. See the LICENSE-TivoDecode
 * file for the licensing terms for TivoDecode.
 *
 * TivoLibre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TivoLibre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TivoLibre.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.straylightlabs.tivolibre;

import java.io.OutputStream;
import java.util.*;

class TransportStream extends Stream {
    private final OutputStream outputStream;
    private final TuringDecoder turingDecoder;
    private StreamType type;
    private byte[] pesBuffer;
    private int pesBufferLength;

    private final Deque<TransportStreamPacket> packets;
    private final Deque<Integer> pesHeaderLengths;

    public static final int FRAME_SIZE = 188;

    public TransportStream(OutputStream outputStream, TuringDecoder decoder) {
        super();
        this.outputStream = outputStream;
        this.turingDecoder = decoder;
        this.type = StreamType.NONE;
        pesBuffer = new byte[FRAME_SIZE * 10];
        packets = new ArrayDeque<>();
        pesHeaderLengths = new ArrayDeque<>();
    }

    public TransportStream(OutputStream outputStream, TuringDecoder decoder, StreamType type) {
        this(outputStream, decoder);
        this.type = type;
    }

    public void setKey(byte[] val) {
        turingKey = val;
    }

    public StreamType getType() {
        return type;
    }

    public boolean addPacket(TransportStreamPacket packet) {
        boolean flushBuffers;

        // If this packet's Payload Unit Start Indicator is set,
        // or one of the stream's previous packet's was set, we
        // need to buffer the packet, such that we can make an
        // attempt to determine where the end of the PES headers
        // lies. Only after we've done that can we determine
        // the packet offset at which decryption is to occur.
        // This accounts for the situation where the PES headers
        // straddles two packets and decryption is needed on the 2nd.
        if (packet.isPayloadStart() || packets.size() != 0) {
            packets.addLast(packet);

            try {
                combineBufferedPacketPayloads();
                flushBuffers = calculatePesHeaderOffset(packet);
            } catch (RuntimeException e) {
                return false;
            }
        } else {
            flushBuffers = true;
            packets.addLast(packet);
        }

        if (flushBuffers) {
            if (!decryptAndFlushBuffers()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Form one contiguous buffer containing all buffered packet payloads
     */
    private void combineBufferedPacketPayloads() {
        pesBufferLength = 0;
        packets.stream().forEach(p -> {
            byte[] data = p.getData();
            System.arraycopy(data, 0, pesBuffer, pesBufferLength, data.length);
            pesBufferLength += data.length;
        });
    }

    /**
     * Figure out the PES header offset for each packet. We don't decrypt PES headers, so we need to know exactly
     * where in the buffer to start the decrypt process.
     * @return True if we should flush our buffer after processing this packet.
     */
    private boolean calculatePesHeaderOffset(TransportStreamPacket packet) {
        boolean flushBuffers = false;

        // Scan the contiguous buffer for PES headers in order to find the end of PES headers.
        pesHeaderLengths.clear();
        if (!getPesHeaderLengths(pesBuffer, pesBufferLength)) {
            TivoDecoder.logger.severe(String.format("Failed to parse PES headers after adding packet %d%n", packet.getPacketId()));
            throw new RuntimeException();
        }

        int sumOfPesHeaderLengths = pesHeaderLengths.stream().mapToInt(i -> i).sum() / MpegParser.BITS_PER_BYTE;

        if (sumOfPesHeaderLengths < pesBufferLength) {
            // The PES headers end in this packet
            flushBuffers = true;

            Iterator<TransportStreamPacket> iterator = packets.iterator();
            TransportStreamPacket p = iterator.next();
            while (sumOfPesHeaderLengths > 0) {
                int payloadLength = TransportStream.FRAME_SIZE - p.getPayloadOffset();
                if (sumOfPesHeaderLengths >= payloadLength) {
                    // PES headers occupy this entire packet
                    p.setPesHeaderOffset(payloadLength);
                    p = iterator.next();
                    sumOfPesHeaderLengths -= payloadLength;
                } else {
                    // PES headers end in this packet
                    p.setPesHeaderOffset(sumOfPesHeaderLengths);
                    sumOfPesHeaderLengths = 0;
                }
            }
        }

        return flushBuffers;
    }

    private boolean getPesHeaderLengths(byte[] buffer, int bufferLength) {
        MpegParser parser = new MpegParser(buffer, bufferLength);
        boolean done = false;

        while (!done && !parser.isEOF()) {
            if (0x000001 != parser.nextBits(24)) {
                done = true;
                continue;
            }

            int len = 0;
            int startCode = parser.nextBits(32);
            parser.clear();
            switch (MpegParser.ControlCode.valueOf(startCode)) {
                case EXTENSION_START_CODE:
                    len = parser.extensionHeader();
                    break;
                case GROUP_START_CODE:
                    len = parser.groupOfPicturesHeader();
                    break;
                case USER_DATA_START_CODE:
                    len = parser.userData();
                    break;
                case PICTURE_START_CODE:
                    len = parser.pictureHeader();
                    break;
                case SEQUENCE_HEADER_CODE:
                    len = parser.sequenceHeader();
                    break;
                case SEQUENCE_END_CODE:
                    len = parser.sequenceEnd();
                    break;
                case ANCILLARY_DATA_CODE:
                    len = parser.ancillaryData();
                    break;
                default:
                    if (startCode >= 0x101 && startCode <= 0x1AF) {
                        done = true;
                    } else if ((startCode == 0x1BD) || (startCode >= 0x1C0 && startCode <= 0x1EF)) {
                        len = parser.pesHeader();
                    } else {
                        TivoDecoder.logger.severe(String.format("Error: Unhandled PES header: 0x%08x", startCode));
                        return false;
                    }
            }

            if (len != 0) {
                pesHeaderLengths.addLast(len);
            }
        }

        return true;
    }

    public boolean decrypt(byte[] buffer) {
        if (!doHeader()) {
            TivoDecoder.logger.severe("Problem parsing Turing header");
            return false;
        }
        TuringStream turingStream = turingDecoder.prepareFrame(streamId, turingBlockNumber);
        turingDecoder.decryptBytes(turingStream, buffer);
        return true;
    }

    private boolean decryptAndFlushBuffers() {
        // Loop through each buffered packet. If it's encrypted, decrypt it and write it out.
        // Otherwise, just write it out.
        try {
            while (!packets.isEmpty()) {
                TransportStreamPacket p = packets.removeFirst();
                byte[] packetBytes;
                if (p.isScrambled()) {
                    p.clearScrambled();
                    byte[] encryptedData = p.getData();
                    int encryptedLength = encryptedData.length - p.getPesHeaderOffset();
                    byte[] data = new byte[encryptedLength];
                    System.arraycopy(encryptedData, p.getPesHeaderOffset(), data, 0, encryptedLength);
                    if (!decrypt(data)) {
                        TivoDecoder.logger.severe("Decrypting packet failed");
                        return false;
                    }
//                    TivoDecoder.logger.info("Decrypted data:\n" + TivoDecoder.bytesToHexString(data));
                    packetBytes = p.getScrambledBytes(data);
                } else {
                    packetBytes = p.getBytes();
                }
                outputStream.write(packetBytes);
            }
        } catch (Exception e) {
            TivoDecoder.logger.severe("Error writing file: " + e.getLocalizedMessage());
            return false;
        }

        return true;
    }

    public enum StreamType {
        AUDIO,
        VIDEO,
        PRIVATE_DATA,
        OTHER,
        NONE;

        private static Map<Integer, StreamType> typeMap;

        static {
            typeMap = new HashMap<>();
            typeMap.put(0x01, VIDEO);
            typeMap.put(0x02, VIDEO);
            typeMap.put(0x10, VIDEO);
            typeMap.put(0x1b, VIDEO);
            typeMap.put(0x80, VIDEO);
            typeMap.put(0xea, VIDEO);

            typeMap.put(0x03, AUDIO);
            typeMap.put(0x04, AUDIO);
            typeMap.put(0x11, AUDIO);
            typeMap.put(0x0f, AUDIO);
            typeMap.put(0x81, AUDIO);
            typeMap.put(0x8a, AUDIO);

            typeMap.put(0x08, OTHER);
            typeMap.put(0x0a, OTHER);
            typeMap.put(0x0b, OTHER);
            typeMap.put(0x0c, OTHER);
            typeMap.put(0x0d, OTHER);
            typeMap.put(0x14, OTHER);
            typeMap.put(0x15, OTHER);
            typeMap.put(0x16, OTHER);
            typeMap.put(0x17, OTHER);
            typeMap.put(0x18, OTHER);
            typeMap.put(0x19, OTHER);

            typeMap.put(0x05, OTHER);
            typeMap.put(0x06, OTHER);
            typeMap.put(0x07, OTHER);
            typeMap.put(0x09, OTHER);
            typeMap.put(0x0e, OTHER);
            typeMap.put(0x12, OTHER);
            typeMap.put(0x13, OTHER);
            typeMap.put(0x1a, OTHER);
            typeMap.put(0x7f, OTHER);

            typeMap.put(0x97, PRIVATE_DATA);

            typeMap.put(0x00, NONE);
        }

        public static StreamType valueOf(int val) {
            return typeMap.getOrDefault(val, PRIVATE_DATA);
        }
    }
}
