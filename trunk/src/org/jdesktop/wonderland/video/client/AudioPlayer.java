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

import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IStream;
import com.xuggle.xuggler.IStreamCoder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 *
 * @author jkaplan
 */
public class AudioPlayer implements Runnable {
    private static final Logger LOGGER =
            Logger.getLogger(AudioPlayer.class.getName());

    private static final boolean VIDEO_AVAILABLE =
            VideoLibraryLoader.loadVideoLibraries();
    
    private String mediaURI;

    private IContainer container;
    private int audioStreamId;
    private IStreamCoder audioCoder;

    private boolean mediaLoaded = false;    
    private boolean quit = false;

    private Thread thread;
    
    private SourceDataLine line;
        
    public AudioPlayer() {
    }
    
    public synchronized boolean openMedia(String mediaURI) {
        // stop the player if we are already running
        if (isRunning()) {
            quit();
        }

        this.mediaURI = mediaURI;
        start();

        try {
            while (thread != null && !mediaLoaded) {
                wait();
            }
        } catch (InterruptedException ie) {
            // ignore
        }

        return mediaLoaded;
    }

    public synchronized void enable() {
        if (!isRunning()) {
            start();
        }
    }

    private synchronized void start() {
        thread = new Thread(this, "Video Queue Filler");
        thread.start();
    }

    private synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    public synchronized void disable() {
        if (isRunning()) {
            quit();
        }
    }

    @Override
    public void run() {         
        synchronized (this) {
            quit = false;
        }

        try {
            // open the video
            LOGGER.warning("Thread " + thread + " opening " + mediaURI);
            openMedia();
            LOGGER.warning("Thread " + thread + " done opening");
            
            // fill the queue until we are done running
            try {
                while (!Thread.interrupted() && !isQuit()) {
                    fillQueue();
                }
            } catch (InterruptedException ie) {
                // break out of loop
            }
            
            // close the media on exit
            closeMedia();            
        } finally {
            // update our state
            synchronized (this) {
                LOGGER.warning("Thread " + thread + " exiting");
                thread = null;
                notifyAll();
            }
        }
    }
    
    /**
     * Called to open the container for the given URI
     * @param uri the uri to open
     * @return a container for the given URI
     * @throws IllegalArgumentException if the media cannot be opened
     */
    protected IContainer openContainer(String uri) {
        IContainer out = IContainer.make();

        // handle windows bug opening file:/ URIs
        if (uri.startsWith("file:")) {
            return openFileContainer(out, uri);
        }
        
        // make sure the open suceeded
        int res = out.open(uri, IContainer.Type.READ, null);
        if (res < 0) {
            throw new IllegalArgumentException("could not open media: " + uri);
        }

        return out;
    }
    
    /**
     * Called to open the container when a file:/ URI is detected. This is 
     * needed on Windows because the standard file:/ URIs don't work in 
     * xuggler.
     * @param container the container
     * @param fileUri the file:/ uri to open
     * @return a container for the given URI
     * @throws IllegalArgumentException if the media cannot be opened 
     */
    protected IContainer openFileContainer(IContainer container, String fileURI) {
        try {
            URL fileURL = new URL(fileURI);
            File file = new File(fileURL.toURI());
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            
            int res = container.open(raf, IContainer.Type.READ, null);
            if (res < 0) {
                throw new IllegalArgumentException("could not open media: " + fileURI);
            }
            
            return container;
        } catch (IOException ex) {
            throw new IllegalArgumentException("could not parse URI: " + fileURI, ex);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("could not parse URI: " + fileURI, ex);
        }
    }

    private void openMedia() {
        // create a Xuggler container object
        container = openContainer(mediaURI);

        // query how many streams the call to open found
        int numStreams = container.getNumStreams();

        // now iterate through the streams to find the first video and audio
        // streams
        audioStreamId = -1;
        audioCoder = null;

        for (int i = 0; i < numStreams; i++) {
            // get the next stream object
            IStream stream = container.getStream(i);
            // find a pre-configured decoder that can decode this stream;
            IStreamCoder coder = stream.getStreamCoder();
            if (audioStreamId == -1 && coder.getCodecType() == ICodec.Type.CODEC_TYPE_AUDIO) {
                // found audio stream
                audioStreamId = i;
                audioCoder = coder;
            }
        }

        if (audioCoder != null) {
            if (audioCoder.open() < 0) {
                throw new RuntimeException("could not open audio decoder for container: " + mediaURI);
            }
            
            try {
                line = openJavaSound(audioCoder);
                line.start();
            } catch (LineUnavailableException ex) {
                LOGGER.log(Level.WARNING, "Line unavailable", ex);
            }
        }
    }

    /**
     * Called by the queue filler thread to block until the next packet is
     * available
     */
    private void fillQueue() throws InterruptedException {
        boolean loaded;
        synchronized (this) {
            loaded = mediaLoaded;
        }
        
        // Now, we start walking through the container looking at each packet.
        IPacket packet = IPacket.make();
        if (container.readNextPacket(packet) >= 0) {
            // if we found the first packet, notify everyone that the media is
            // completely loaded
            if (!loaded) {
                synchronized (this) {
                    mediaLoaded = true;            
                    notifyAll();
                }
            
                loaded = true;
            }
            
            if (packet.getStreamIndex() == audioStreamId) {
                // We allocate a set of samples with the same number of channels as the
                // coder tells us is in this buffer.
                //
                // We also pass in a buffer size (1024 in our example), although Xuggler
                // will probably allocate more space than just the 1024 (it's not important why).    
                IAudioSamples samples = IAudioSamples.make(1024, audioCoder.getChannels());

                // A packet can actually contain multiple sets of samples (or frames of samples
                // in audio-decoding speak).  So, we may need to call decode audio multiple
                // times at different offsets in the packet's data.  We capture that here.
                int offset = 0;

                // we only want to pass a single object to the receiver per packet, so we
                // need to store all audio data for this packet in a single buffer
                long dataSize = packet.getDuration() * audioCoder.getChannels() *
                                IAudioSamples.findSampleBitDepth(audioCoder.getSampleFormat());
                dataSize /= 8;
                
                byte[] data = new byte[(int) dataSize];
                int dataOffset = 0;
                int dataLength = 0;
                
                // we will use the first PTS value we find as our resulting PTS. This could
                // theoretically cause a problem if a single packet contains two audio samples
                // with a gap between them, but that doesn't seem to happen in real life
                long pts = 0;
                boolean ptsSet = false;
                
                // Keep going until we've processed all data
                while (offset < packet.getSize()) {
                    int bytesDecoded = audioCoder.decodeAudio(samples, packet, offset);
                    if (bytesDecoded < 0) {
                        throw new RuntimeException("got error decoding audio");
                    }
                    offset += bytesDecoded;
                    
                    // Some decoder will consume data in a packet, but will not be able to construct
                    // a full set of samples yet.  Therefore you should always check if you
                    // got a complete set of samples from the decoder
                    if (samples.isComplete()) {
                        // check if we have set the PTS, and if not do it now
                        if (!ptsSet) {
                            pts = samples.getPts();
                            ptsSet = true;
                        }
                        
                        // write the data at the current offset in the buffer
                        // and update our pointers
                        samples.getData().get(0, data, dataOffset, samples.getSize());
                        dataOffset += samples.getSize();
                        dataLength += samples.getSize();
                    }
                }
                
                // at this point, if we never set the PTS, it means we were
                // seeking and there is no packet to write
                if (ptsSet) {
                    LOGGER.fine("Play at " + (pts / 1000000.0));
                    playJavaSound(line, data, dataLength);
                }
            }
        }
    }
  
    private void closeMedia() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
        }
        
        if (audioCoder != null) {
            audioCoder.close();
            audioCoder = null;
            audioStreamId = -1;
        }

        if (container != null) {
            container.close();
            container = null;
        }
        
        synchronized (this) {
            mediaLoaded = false;
        }
    }
    
    /**
     * Initialize JavaSound
     * @param aAudioCoder an audio decoder
     * @throws LineUnavailableException
     */
    private static SourceDataLine openJavaSound(IStreamCoder aAudioCoder)
            throws LineUnavailableException
    {
        AudioFormat audioFormat = new AudioFormat(aAudioCoder.getSampleRate(),
                (int) IAudioSamples.findSampleBitDepth(aAudioCoder.getSampleFormat()),
                aAudioCoder.getChannels(),
                true, /* xuggler defaults to signed 16 bit samples */
                false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine out = (SourceDataLine) AudioSystem.getLine(info);

        // try opening the line.
        out.open(audioFormat);
        return out;
    }

    /**
     * Play audio samples
     * @param aSamples audio samples to play
     */
    private static void playJavaSound(SourceDataLine line, byte[] rawBytes,
                                      int length) 
    {
        // we're just going to dump all the samples into the line
        if (line != null) {
            line.write(rawBytes, 0, length);
        }
    }

    /**
     * Manually adjust the volume of a byte buffer. We do this since
     * JavaSound volume controls don't always work, and the gain
     * controls are unreliable.
     * @param line the data line
     * @param vol the volume adjustment
     * @param buffer the buffer to adjust (changes are made in place)
     * @param offset the start of the buffer to adjust
     * @param length the length of data to adjust
     */
    private static byte[] adjustVolume(SourceDataLine line, float vol,
                                       byte[] buffer, int offset, int length)
    {
        if (vol == 1f) {
            return buffer;
        }

        // create a buffer with the right endian-ness
        ByteBuffer bb = ByteBuffer.wrap(buffer, offset, length);
        bb.order(line.getFormat().isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

        // turn into a short buffer
        ShortBuffer sb = bb.asShortBuffer();

        for (int i = sb.position(); i < sb.limit(); i++) {
            // get the sample value
            int sample = sb.get(i);

            // multiply by the volume
            sample *= vol;

            // clamp to a short range
            sample = Math.min(sample, Short.MAX_VALUE);
            sample = Math.max(sample, Short.MIN_VALUE);

            // set the sample value
            sb.put(i, (short) sample);
        }

        return bb.array();
    }

    private synchronized void quit() {
        this.quit = true;

        if (thread != null) {
            LOGGER.warning("Interrupting thread " + thread);
            thread.interrupt();
        }
        
        try {
            while (isRunning()) {
                wait();
            }
        } catch (InterruptedException ie) {
        }
    }

    private synchronized boolean isQuit() {
        return quit;
    }
    
    public static void main(String[] args) {
        String video = "/Users/jkaplan/Downloads/JapaneseHouse-v2-ForReview-Web - Computer.m4v";
        
        try {
            File videoFile = new File(video);
            
            AudioPlayer player = new AudioPlayer();
            player.openMedia(videoFile.toURI().toURL().toExternalForm());
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, null, ex);
        }
    }
}
