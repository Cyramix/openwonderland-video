/**
 * Open Wonderland
 *
 * Copyright (c) 2011, Open Wonderland Foundation, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * The Open Wonderland Foundation designates this particular file as
 * subject to the "Classpath" exception as provided by the Open Wonderland
 * Foundation in the License file that accompanied this code.
 */
package org.jdesktop.wonderland.video.client;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.video.client.VideoQueueFiller.AudioFrame;

/**
 * Input stream for audio data.
 * @author Jonathan Kaplan <jonathankap@gmail.com>
 */
public class AudioInputStream {
    private static final Logger LOGGER =
            Logger.getLogger(AudioInputStream.class.getName());
    
    // the exiting packets, PTS order
    private final Deque<PacketAccess> queue = 
            new LinkedList<PacketAccess>();
    
    // time the stream was started (in microseconds)
    private long startTime;
    
    // the number of bytes of data that have been processed so far
    private long bytePosition;
    
    // the sample rate (in samples per second)
    float sampleRate;
    
    // the size of a sample in bits -- this must take into account all channels
    int sampleSize;
    
    // the number of microseconds in a single sample
    int sampleMicroseconds;
    
    /**
     * Start the input stream at the given time.
     * @param startTime the time to start the stream
     * @param sampleRate the sample rate in milliseconds
     * @param sampleSize the sample size for all channels, in bits
     */
    public synchronized void start(long startTime, float sampleRate, 
                                   int sampleSize) 
    {
        this.startTime = startTime;
        this.bytePosition = 0;
        this.sampleRate = sampleRate;
        this.sampleSize = sampleSize;
        
        // calculate how long a single sample lasts in microseconds
        sampleMicroseconds = (int) ((1.0 / sampleRate) * 1000000);
    
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Start stream at {0}", (startTime / 1000000.0));
        }
    }
    
    /**
     * Add an audio packet
     * @param frame the audio data to add
     */
    public synchronized void add(AudioFrame frame) {
        if (queue.isEmpty()) {
            // first packet
            addFirst(frame);
        } else {
            // add to end of queue
            append(frame);
        }
    }
    
    /**
     * Add the first packet in the queue.
     * @param frame the audio packet to add
     */
    protected synchronized void addFirst(AudioFrame frame) {
        long curMicros = getMicrosecondPosition();
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Add first audio packet at %d, cur %d",
                        frame.getPTS(), curMicros));
        }
        
        // see how many microseconds off this packet is
        long gapMicros = frame.getPTS() - curMicros;
        if (gapMicros > 2 * sampleMicroseconds) {
            // packet is early -- insert a gap
            LOGGER.fine(String.format("%d microseconds = %d bytes", gapMicros, microsecondsToBytes(gapMicros)));
            
            PacketAccess gap = new AudioGap(microsecondsToBytes(gapMicros),
                                            bytePosition);
            queue.add(gap);
            queue.add(new AudioData(frame, gap.getEndPosition(), 0));
        
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Packet early: %d. Add gap from " +
                            "%d - %d bytes. Audio from %d - %d bytes", 
                            gapMicros, gap.getStartPosition(), 
                            gap.getEndPosition(), gap.getEndPosition(),
                            gap.getEndPosition() + frame.getLength()));
                 
            }
        } else if (gapMicros < -2 * sampleMicroseconds) {
            // packet is late -- figure out what to do. If the length of
            // the packet in microseconds is smaller than the size of the
            // gap, we just drop the packet since it won't play at all. If
            // the gap is smaller, we figure out how much of the packet is
            // left to play.
            
            long packetMicros = bytesToMicroseconds(frame.getLength());
            if (packetMicros > Math.abs(gapMicros) + (2 * sampleMicroseconds)) {
                // play some percentage of the packet. Calculate in samples
                // to make sure we are aligned properly
                double skipPercent = (double) Math.abs(gapMicros) / packetMicros;
                int frameSamples = (frame.getLength() * 8) / sampleSize;
                int samplesToSkip = (int) (skipPercent * frameSamples);
                
                // add the frame to the queue starting somewhere in the middle
                // of the data. Make sure to adjust the start position to 
                // reflect that we didn't start at the begining, in order to
                // keep the end position calculation correct.
                int bytesToSkip = (samplesToSkip * sampleSize) / 8;
                queue.add(new AudioData(frame, bytePosition - bytesToSkip,
                                        bytesToSkip));
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("Packet late: %d. Skip first " +
                                "%d bytes (%f percent), packet from %d to %d",
                                gapMicros, bytesToSkip, skipPercent * 100.0,
                                bytePosition - bytesToSkip, 
                                bytePosition + bytesToSkip));
                }
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("Dropping late packet. Gap: %d," +
                                " packet length: %d", gapMicros, packetMicros));
                }
            }
            
        } else {
            // add the packet directly
            queue.add(new AudioData(frame, bytePosition, 0));
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Add on-time packet from %d - %d",
                            bytePosition, bytePosition + frame.getLength()));
            }
        }
        
        // notify any listeners that the queue has changed
        notify();
    }
    
    /**
     * Add a packet to the end of the queue
     * @param frame the audio packet to add
     */
    protected void append(AudioFrame frame) {
        PacketAccess last = queue.getLast();
        
        // calculate the end time of the last packet to see if there is
        // a gap of more than two samples
        long endPositionMicros = startTime + bytesToMicroseconds(last.getEndPosition());
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Append packet at %d, last end %d",
                        frame.getPTS(), endPositionMicros));
        }
        
        long gapMicros = frame.getPTS() - endPositionMicros;
        if (gapMicros > 2 * sampleMicroseconds) {
            // insert a gap
            PacketAccess gap = new AudioGap(microsecondsToBytes(gapMicros),
                                            last.getEndPosition());
            queue.add(gap);
            last = gap;
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Packet early by %d, add gap from " +
                            "%d - %d", gapMicros, gap.getStartPosition(),
                            gap.getEndPosition()));
            }           
        }
        
        // insert packet
        queue.add(new AudioData(frame, last.getEndPosition(), 0));
        
        // no need to notify in this case -- the queue isn't empty, so
        // we can't be waiting
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Append packet from %d - %d",
                        last.getEndPosition(), 
                        last.getEndPosition() + frame.getLength()));
        }
        
    }
    
    /**
     * Read the next data from this stream. This is identical to calling
     * <code>read(data, 0, data.length, min, delay, unit)</code>.
     * 
     * @param data the buffer to read into
     * @param min the minimum number of bytes to wait for
     * @param timeout the read timeout detector
     * @return the number of bytes read
     * @throws InterruptedException if the thread is interrupted while reading
     */
    public int read(byte[] data, int min, ReadTimeout timeout) 
            throws InterruptedException
    {
        return read(data, 0, data.length, min, timeout);
    }
    
    /**
     * Read the next data from this stream, waiting up to delay to read at
     * least the minimum number of bytes specified.
     * 
     * @param data the buffer to read into
     * @param offset the offset in buffer to read into
     * @param length the maximum length of data to read
     * @param min the minimum number of bytes to wait for
     * @param timeout the read timeout detector
     * @return the number of bytes read
     * @throws InterruptedException if the thread is interrupted while reading
     */
    public synchronized int read(byte[] data, int offset, int length,
                                 int min, ReadTimeout timeout) 
            throws InterruptedException
    {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Read min: %d max: %d bytes of data. " +
                        "Position = %d. Queue = %d.", min, length, 
                        bytePosition, queue.size()));
        }
        
        // record the number of bytes written
        int written = 0;
        
        // the length of time to wait
        long readTimeout = timeout.getReadTimeout(0);
        
        // loop until either we have written length bytes or time runs
        // out
        while (written < length && readTimeout > 0) {
            // read the current packet from the queue
            PacketAccess cur = queue.peek();
            
            // if there is no current packet, wait up to the amount of
            // time we have available
            if (cur == null) {
                // calculate the amount of time to wait as a number of
                // milliseonds plus a number of nanoseconds
                long waitTime = readTimeout;
                long waitMillis = waitTime / 1000;
                int waitNanos = (int) ((waitTime * 1000) % 1000000);
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("No data. Wait %d milliseconds " +
                                " + %d nanoseconds.", waitMillis, waitNanos));
                }
                
                wait(waitMillis, waitNanos);
                
                // was a packet added?
                cur = queue.peek();
            }
            
            // is there a packet to read from?
            if (cur != null) {
                int read = cur.read(data, offset + written, length - written);
                
                // update how much data has been written to the buffer
                written += read;
                
                // update our current position based on consuming this packet
                bytePosition += read;
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("Read %d bytes from %s. " +
                                "Total = %d. Position = %d", read, cur, 
                                written, bytePosition));
                }
                
                // if the packet is now exhausted, remove it from the queue
                if (cur.isFinished()) {
                    queue.remove();
                }
            }
            
            // recalculate the current timeout
            readTimeout = timeout.getReadTimeout(written);
        }
        
        // at this point we need to return something. If we have written less
        // than the minimum, we need to pad the result with zeros up to
        // minimum
        if (written < min) {
            Arrays.fill(data, offset + written, offset + min, (byte) 0);
            bytePosition += min - written;
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Less than min written. Adding %d " +
                            "bytes of 0. Position = %d", min - written,
                            bytePosition));
            }
            
            written = min;
        }
        
        // return the number of bytes written
        return written;
    }
    
    /**
     * Get the position of the input stream in microseconds
     * @return the position of the stream in microseconds
     */
    public synchronized long getMicrosecondPosition() {
        // convert byte position to microseconds
        return startTime + bytesToMicroseconds(bytePosition);
    }
    
    /**
     * Clear this stream
     */
    public synchronized void clear() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Clear");
        }
        
        queue.clear();
        notify();
    }    
    
    /**
     * Convert bytes to microseconds
     * @param bytes the number of bytes
     * @return the approximate number of microseconds to represent that number 
     * of bytes
     */
    protected synchronized long bytesToMicroseconds(long bytes) {
        // convert byte position to microseconds
        long samples = (bytes * 8) / sampleSize;
        double microseconds = (samples * 1000000.0) / sampleRate;
        return (long) microseconds;
    }
    
    /**
     * Convert microseconds to bytes
     * @param micros the number of microseconds
     * @return the approximate number of bytes to represent that number of
     * microseconds
     */
    protected synchronized int microsecondsToBytes(long micros) {
        long samples = (long) ((micros * sampleRate) / 1000000); 
        int bytes = (int) ((samples * sampleSize) / 8);
        return bytes;
    }
    
    /**
     * Interface to determine if we can continue waiting or if we should
     * now return, even if we have not filled the buffer.
     */
    public interface ReadTimeout {
        /**
         * Return an approximation of how long this read can go before timing
         * out. This method will be called repeatedly after waiting the number
         * of microseconds returned by this method, until it returns 0, at 
         * which point the buffer will be returned, padded with zeros if
         * necessary.
         * 
         * @param bytesRead the number of bytes read so far
         * @return the current read timeout, in microseconds
         */
        long getReadTimeout(int bytesRead);
    }
    
    /** access to a packet or empty data */
    abstract class PacketAccess {
        private final long startBytePosition;
        private int curOffset;
        
        /**
         * Create a new PacketAccess with the given stream start position
         * and the given starting offset
         * @param startBytePosition the starting byte position of the packet
         * @param startOffset the initial offset into the packet data
         */
        PacketAccess(long startBytePosition, int startOffset) {
            this.startBytePosition = startBytePosition;
            this.curOffset = startOffset;
        }
        
        /**
         * Read data from this packet into the given buffer
         * @param data the buffer to write data into
         * @param offset the offset in data to write to
         * @param length the maximum length of data to write
         * @return the number of bytes written
         */
        public int read(byte[] data, int offset, int length) {
            // calculate how much data we can read
            int toRead = Math.min(length, getSize() - curOffset);
        
            // make sure the amount of data to read is sample-aligned, rounding
            // down so we are always below length and remaining
            long samples = Math.round(Math.floor((toRead * 8) / sampleSize)); 
            toRead = (int) (samples * sampleSize) / 8;
            
            // fill the buffer
            fillBuffer(data, curOffset, offset, toRead);
            
            // update our position and return
            curOffset += toRead;
            return toRead;
        }
        
        /**
         * Get the total length of this packet, in bytes
         * @return the size of the packet, in bytes
         */
        protected abstract int getSize();
        
        /**
         * Fill the output data buffer with data.
         * @param data the data buffer to fille
         * @param srcOffset the offset into our data to read from
         * @param dstOffset the offset into the data buffer to write to
         * @param length the number of bytes to write
         */
        protected abstract void fillBuffer(byte[] data, int srcOffset,
                                           int dstOffset, int length);
        
        
        /**
         * Determine if this packet has more data left to read
         * @return true if the packet has no more data, or false if there
         * is more data
         */
        boolean isFinished() {
            return curOffset >= getSize();
        }
        
        /**
         * Get the start position of this packet in the stream. The start
         * position is the bytePosition of the stream when the first byte
         * of this packet is read.
         * @return the byte position of the first byte of this packet
         */
        long getStartPosition() {
            return startBytePosition;
        }
        
        /**
         * Get the end position of this packet in the stream. The end position
         * is the byte position in the stream of the end of this packet.
         * @return the byte position one past the last byte of this packet
         */
        long getEndPosition() {
            return startBytePosition + getSize();
        }
    }
    
    class AudioData extends PacketAccess {
        private final AudioFrame frame;
        
        public AudioData(AudioFrame frame, long startBytePosition,
                         int startOffset) 
        {
            super (startBytePosition, startOffset);
            this.frame = frame;
        }

        @Override
        protected int getSize() {
            return frame.getLength();
        }
        
        @Override
        protected void fillBuffer(byte[] data, int srcOffset, int dstOffset, 
                                  int length) 
        {
            System.arraycopy(frame.getData(), srcOffset, 
                             data, dstOffset, length);
        }
        
        @Override
        public String toString() {
            return String.format("[Audio data from %d - %d]",
                                  getStartPosition(), getEndPosition());
        }
    }
    
    class AudioGap extends PacketAccess {
        private final int gapBytes;

        public AudioGap(int gapBytes, long startBytePosition) {
            super (startBytePosition, 0);
            this.gapBytes = gapBytes;
        }
        
        @Override
        protected int getSize() {
            return gapBytes;
        }
        
        @Override
        protected void fillBuffer(byte[] data, int srcOffset, int dstOffset, 
                                  int length) 
        {
            Arrays.fill(data, dstOffset, dstOffset + length, (byte) 0);
        }
        
        @Override
        public String toString() {
            return String.format("[Audio gap from %d - %d]",
                                  getStartPosition(), getEndPosition());
        }
    }
}
