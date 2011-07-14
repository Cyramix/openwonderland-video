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

import com.xuggle.xuggler.Global;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IMediaData;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IRational;
import com.xuggle.xuggler.IStream;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IVideoPicture;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author jkaplan
 */
public class VideoQueueFiller implements Runnable {
    private static final Logger LOGGER =
            Logger.getLogger(VideoQueueFiller.class.getName());

    // protocols that don't support seek
    private static final String[] NO_SEEK_PROTOCOLS = new String[] {
        "rtmp"
    };
    
    private final VideoQueue queue;

    private String mediaURI;

    private IContainer container;
    private int videoStreamId;
    private IStreamCoder videoCoder;
    private int audioStreamId;
    private IStreamCoder audioCoder;

    private boolean mediaLoaded = false;    
    private boolean canSeek = false;
    private boolean quit = false;

    private Thread thread;
    
    private SeekOperation seek;
    
    public VideoQueueFiller(VideoQueue queue) {
        this.queue = queue;
    }

    public Dimension getSize() {
        Dimension out = new Dimension();

        if (videoCoder != null) {
            out.setSize(videoCoder.getWidth(), videoCoder.getHeight());
        }

        return out;
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
    
    /**
     * Get the duration of this video, in seconds. If the value is less
     * than 0, the duration cannot be determined because this is a streaming
     * source.
     * @return the duration in seconds
     */
    public synchronized double getDuration() {
        if (container == null) {
            return -1.0;
        }
        
        long duration = container.getDuration();
        if (duration == Global.NO_PTS) {
            return -1.0;
        }
        
        // convert from microseconds
        return (double) duration / 1000000.0;
    }
    
    /**
     * Find out whether seeking is supported on the current content
     * @return true if seeking is supported, or false if not
     */
    public synchronized boolean canSeek() {
        if (mediaLoaded) {
            return canSeek;
        } else {
            return false;
        }
    }
    
    /**
     * Seek to the given time.
     * @param time the time to seek to
     */
    public void seek(double time) {
        // set the target time to seek to
        synchronized (this) {
            if (!canSeek) {
                LOGGER.warning("Unable to seek");
                return;
            }
            
            seek = new SeekOperation(time);
        }
        
        LOGGER.fine("Seek to " + time);
        
        // clear the client buffers (which will force fillQueue to run)
        queue.clear();
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
        videoStreamId = -1;
        videoCoder = null;
        audioStreamId = -1;
        audioCoder = null;

        for (int i = 0; i < numStreams; i++) {
            // get the next stream object
            IStream stream = container.getStream(i);
            // find a pre-configured decoder that can decode this stream;
            IStreamCoder coder = stream.getStreamCoder();
            if (videoStreamId == -1 && coder.getCodecType() == ICodec.Type.CODEC_TYPE_VIDEO) {
                // found video stream
                videoStreamId = i;
                videoCoder = coder;
            } else if (audioStreamId == -1 && coder.getCodecType() == ICodec.Type.CODEC_TYPE_AUDIO) {
                // found audio stream
                audioStreamId = i;
                audioCoder = coder;
            }
        }
        if (videoStreamId == -1 && audioStreamId == -1) {
            LOGGER.warning("could not find audio or video stream in container: " + mediaURI);
            return;
        }

        // check if we have a video stream in this file. If so let's
        // open up our decoder so it can do work.
        if (videoCoder != null) {
            if (videoCoder.open() < 0) {
                throw new RuntimeException("could not open video decoder for container: " + mediaURI);
            }

            // notify queue of this new video
            queue.newStream(videoStreamId, videoCoder);
        }

        if (audioCoder != null) {
            if (audioCoder.open() < 0) {
                throw new RuntimeException("could not open audio decoder for container: " + mediaURI);
            }

            // notify queue of this new audio
            queue.newStream(audioStreamId, audioCoder);
        }
        
        synchronized (this) {
            // see if we should bother testing seek
            boolean testSeek = true;
            for (String protocol : NO_SEEK_PROTOCOLS) {
                if (mediaURI.toLowerCase().startsWith(protocol)) {
                    testSeek = false;
                    break;
                }
            }
            
            // set up an initial seek to the start of the media. This will 
            // determine if seeking is enabled
            if (testSeek) {
                seek = new SeekOperation(0d);
            }
        }
    }

    /**
     * Called by the queue filler thread to block until the next packet is
     * available
     */
    private void fillQueue() throws InterruptedException {
        // check if we need to seek
        SeekOperation curSeek;
        boolean loaded;
        synchronized (this) {
            curSeek = seek;
            loaded = mediaLoaded;
        }
        boolean seeking = (curSeek != null);
        
        // have we performed the seek() call yet? If not, do it now
        if (seeking && !curSeek.isSeekPerformed()) {
            performSeek(curSeek);
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
            
            // Now we have a packet, let's see if it belongs to our video stream
            if (packet.getStreamIndex() == videoStreamId) {
                // We allocate a new picture to get the data out of Xuggler
                IVideoPicture picture = IVideoPicture.make(videoCoder.getPixelType(),
                        videoCoder.getWidth(), videoCoder.getHeight());

                // Now, we decode the video, checking for any errors.
                int bytesDecoded = videoCoder.decodeVideo(picture, packet, 0);
                if (bytesDecoded < 0) {
                    throw new RuntimeException("got error decoding video");
                }

                // check if we are seeking for video
                boolean seekingVideo = false;
                if (seeking) {
                    seekingVideo = !isSeekComplete(curSeek, picture, true);
                }
                
                // Some decoders will consume data in a packet, but will not be able to construct
                // a full video picture yet.  Therefore you should always check if you
                // got a complete picture from the decoder
                if (picture.isComplete() && !seekingVideo) {
                    // at this point, we have a complete picture. Add it
                    // to the queue to view when we request the next frame.
                    LOGGER.fine("Add picture to queue at " + 
                                (picture.getTimeStamp() / 1000000.0));
                    
                    queue.add(picture);
                }
            } else if (packet.getStreamIndex() == audioStreamId) {
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

                // Keep going until we've processed all data
                while (offset < packet.getSize()) {
                    int bytesDecoded = audioCoder.decodeAudio(samples, packet, offset);
                    if (bytesDecoded < 0) {
                        throw new RuntimeException("got error decoding audio");
                    }
                    offset += bytesDecoded;

                    // check if we are seeking for audio
                    boolean seekingAudio = false;
                    if (seeking) {
                        seekingAudio = !isSeekComplete(curSeek, samples, false);
                    }
                    
                    // Some decoder will consume data in a packet, but will not be able to construct
                    // a full set of samples yet.  Therefore you should always check if you
                    // got a complete set of samples from the decoder
                    if (samples.isComplete() && !seekingAudio) {
                        // note: this call will block if Java's sound buffers fill up, and we're
                        // okay with that.  That's why we have the video "sleeping" occur
                        // on another thread.
                        LOGGER.fine("Add audio to queue at " +
                                    (samples.getTimeStamp() / 1000000.0));
                        
                        queue.add(samples);
                    }
                }
            }
        }
    }
    
    private void performSeek(SeekOperation curSeek) {
        long seekTarget = (long) (curSeek.getTargetTime() * 1000000);
        long min = seekTarget - 100;

        LOGGER.fine("Perform seek for " + seekTarget);

        // rescale to audio queue
        IRational containerTimeBase = IRational.make(1, 1000000);
        min = audioCoder.getTimeBase().rescale(min, containerTimeBase);
        seekTarget = audioCoder.getTimeBase().rescale(seekTarget, containerTimeBase);

        LOGGER.fine("Translate to " + audioCoder.getTimeBase() + 
                       " = " + seekTarget);

        int res = container.seekKeyFrame(audioStreamId, min, seekTarget, seekTarget, 0);
        if (res < 0) {
            synchronized (this) {
                canSeek = false;
                seek.setVideoFound();
                seek.setAudioFound();
            }
            
            LOGGER.warning("Unable to seek: " + res);
        } else {
            synchronized (this) {
                canSeek = true;
            }
        }
        
        // flush buffers
        if (videoCoder != null) {
            //videoCoder.flushBuffers();
        }

        if (audioCoder != null) {
            //audioCoder.flushBuffers();
        }

        // notify the listener to clear as well. We already did this once
        // during the call to seek() to ensure the fillQueues() would run,
        // but we need to do it again here to get rid of any data that
        // was added between the call to seek and when we got here
        queue.clear();
        
        curSeek.setSeekPerformed();
    }
    
    private boolean isSeekComplete(SeekOperation curSeek, IMediaData frame,
                                   boolean video) 
    {
        if ((video && !curSeek.isSeekingVideo()) ||
            (!video) && !curSeek.isSeekingAudio())
        {
            // the seek for this type is complete
            return true;
        }
        
        double frameTime = frame.getTimeStamp() / 1000000.0;
        double timeDiff = curSeek.getTargetTime() - frameTime;

        LOGGER.fine((video?"Video ":"Audio ") + frameTime + " seeking for " +
                    curSeek.getTargetTime() + " diff: " + timeDiff);

        // we have found the frame if the time difference is withing 0.05
        // seconds of the target (TODO: adjust based on frame rate).
        //
        // The second case is a hack: sometimes due to buffering, the first
        // packet we see is from the old time. We generally want to ignore 
        // these. If there are more than 1 packets at a later time, it means no
        // packets were found at the target time, and we have actually found
        // the best option
        int skipCount = video?curSeek.getSkipVideoCount():curSeek.getSkipAudioCount();
        if (Math.abs(timeDiff) < 0.05d || (timeDiff < 0d && skipCount > 0)) {
            // we have found our frame
            if (video) {
                curSeek.setVideoFound();
            } else {
                curSeek.setAudioFound();
            }
            
            // see if we need to do any more seeking
            if (!curSeek.isSeekingAudio() && !curSeek.isSeekingVideo()) {
                synchronized (this) {
                    seek = null;
                }
            }
            
            return true;
        } else {
            // note that we are skipping packets
            if (video) {
                curSeek.skipVideo();
            } else {
                curSeek.skipAudio();
            }
            
            return false;
        }
    }

    private void closeMedia() {
        if (videoCoder != null) {
            videoCoder.close();
            videoCoder = null;
            videoStreamId = -1;
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

    private class SeekOperation {
        private final double targetTime;
        private boolean seekPerformed = false;
        private boolean seekingVideo = true;
        private boolean seekingAudio = true;
        private int skipVideoCount = 0;
        private int skipAudioCount = 0;
        
        public SeekOperation(double targetTime) {
            this.targetTime = targetTime;
        }
        
        public double getTargetTime() {
            return targetTime;
        }
        
        public boolean isSeekPerformed() {
            return seekPerformed;
        }
        
        public void setSeekPerformed() {
            this.seekPerformed = true;
        }
        
        public boolean isSeekingAudio() {
            return seekingAudio;
        }
        
        public void setAudioFound() {
            seekingAudio = false;
        }
        
        public boolean isSeekingVideo() {
            return seekingVideo;
        }
        
        public void setVideoFound() {
            seekingVideo = false;
        }
        
        public int getSkipVideoCount() {
            return skipVideoCount;
        }
        
        public void skipVideo() {
            skipVideoCount++;
        }
        
        public int getSkipAudioCount() {
            return skipAudioCount;
        }
        
        public void skipAudio() {
            skipAudioCount++;
        }
    }
    
    public interface VideoQueue {
        /**
         * Notification that a new stream has been added
         * @param id the id of the stream
         * @param coder the stream coder
         */
        public void newStream(int id, IStreamCoder coder);

        /**
         * Add the next packet to the queue, blocking until there is
         * room.
         */
        public void add(IMediaData data) throws InterruptedException;
        
        /**
         * Clear the current queue of packets.
         */
        public void clear();
    }
}
