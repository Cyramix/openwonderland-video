/**
 * Open Wonderland
 *
 * Copyright (c) 2010 - 2011, Open Wonderland Foundation, All Rights Reserved
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
import com.xuggle.xuggler.IMediaData;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IVideoPicture;
import java.awt.Dimension;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.jdesktop.wonderland.client.utils.VideoLibraryLoader;
import org.jdesktop.wonderland.video.client.FrameListener.FrameQueue;
import org.jdesktop.wonderland.video.client.VideoQueueFiller.AudioFrame;

/**
 * Streaming video player for xuggler video library.
 */
public class VideoPlayerImpl implements VideoStateSource,
                                          FrameListener.FrameQueue,
                                          VideoQueueFiller.VideoQueue
{

    private static final Logger LOGGER = Logger.getLogger(VideoPlayerImpl.class.getName());

    // check whether video is available. Be sure to do this in the static
    // initialize for the class, so we load the libraries before xuggler
    // tries to
    private static final boolean VIDEO_AVAILABLE =
            VideoLibraryLoader.loadVideoLibraries();

    // listeners
    private final List<VideoStateListener> stateListeners =
            new CopyOnWriteArrayList<VideoStateListener>();
    private final List<FrameListener> frameListeners =
            new CopyOnWriteArrayList<FrameListener>();

    // time source
    private final SystemClockTimeSource timeSource = new SystemClockTimeSource();
    
    private String mediaURI;
    private VideoPlayerState mediaState = VideoPlayerState.NO_MEDIA;

    private final VideoQueueFiller queueFiller;
    private final AudioThread audioQueue;
    private final BlockingQueue<IVideoPicture> frameQueue;

    private SourceDataLine line;
    private boolean mute = false;
    private float volume = 1.0f;
    private long frameTime = 100000;

    private Thread mediaOpener;
    
    private boolean needsPreview = true;
    private double lastFrameTime;
    
    public VideoPlayerImpl() {
        audioQueue = new AudioThread();
        frameQueue = new LinkedBlockingQueue<IVideoPicture>(4);

        queueFiller = new VideoQueueFiller(this);
    }

    /**
     * Return whether or not video is available on this platform
     * @return true if video is available or false if not
     */
    public static boolean isVideoAvailable() {
        return VIDEO_AVAILABLE;
    }

    /**
     * Add a listener for new frames
     * @param listener a frame listener to be notified of new frames
     */
    @Override
    public void addFrameListener(FrameListener listener) {
        frameListeners.add(listener);
    }

    /**
     * Remove a listener for new frames
     * @param listener a frame listener to be removed
     */
    @Override
    public void removeFrameListener(FrameListener listener) {
        frameListeners.remove(listener);
    }

    /**
     * Notify all frame listeners of a new video
     * @param width the width of the video
     * @param height the height of the video
     * @param format the format of the video
     */
    protected void notifyFrameListenersOpen(int width, int height,
                                            IPixelFormat.Type type) 
    {
        for (FrameListener listener : frameListeners) {
            listener.openVideo(width, height, type);
        }
    }

    /**
     * Notify all frame listeners that the video is stopped
     */
    protected void notifyFrameListenersClose() {
        for (FrameListener listener : frameListeners) {
            listener.closeVideo();
        }
    }

    /**
     * Notify all frame listeners of a new preview frame
     * @param frame the preview frame
     */
    protected void notifyFrameListenersPreview(IVideoPicture frame) 
    {
        for (FrameListener listener : frameListeners) {
            listener.previewFrame(frame);
        }
    }
    
    /**
     * Notify all the frame listeners that video has started
     * @param frame a new frame
     */
    protected void notifyFrameListenersPlay(FrameQueue frames) {
        for (FrameListener listener : frameListeners) {
            listener.playVideo(frames);
        }
    }

    /**
     * Notify all the frame listeners that video has stopped
     */
    protected void notifyFrameListenersStop() {
        for (FrameListener listener : frameListeners) {
            listener.stopVideo();
        }
    }

    /**
     * Add a listener for changes in state
     * @param listener a state listener to be notified of state changes
     */
    @Override
    public void addStateListener(VideoStateListener listener) {
        stateListeners.add(listener);
    }

    /**
     * Remove a listener for state changes
     * @param listener a state listener to be removed
     */
    @Override
    public void removeStateListener(VideoStateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * Notify all the state listeners of a state change
     * @param oldState the previous state
     * @param newState the new state
     */
    protected void notifyStateListeners(VideoPlayerState oldState, 
                                        VideoPlayerState newState) 
    {
        for (VideoStateListener listener : stateListeners) {
            listener.mediaStateChanged(oldState, newState);
        }
    }

    /**
     * Open video media
     * @param uri the URI of the video media to open
     */
    @Override
    public void openMedia(final String uri) {
        this.mediaURI = uri;

        // perform the actual open in another thread, so as not to block
        // the caller. The caller can wait for the state to change to
        // media ready once the media is actually open
        Runnable open = new Runnable() {
            @Override
            public void run() {
                // stop any existing video
                stop(false);
                
                try {
                    queueFiller.openMedia(mediaURI);
                    setState(VideoPlayerState.MEDIA_READY);
                } finally {
                    synchronized (VideoPlayerImpl.this) {
                        mediaOpener = null;
                    }
                }
            }
        };
        
        synchronized (this) {
            if (mediaOpener != null && mediaOpener.isAlive()) {
                // stop the existing load
                mediaOpener.interrupt();
            }
            
            mediaOpener = new Thread(open, "Media Opener thread");
            mediaOpener.start();
        }
    }

    @Override
    public synchronized IVideoPicture nextFrame() {
        // if the time source is not running, there is no next frame
        if (!timeSource.isStarted()) {
            return null;
        }
        
        // find the target time
        long targetPTS = timeSource.getCurrentPTS();

        boolean logStats = LOGGER.isLoggable(Level.FINE);
        StringBuilder stats = new StringBuilder();

        if (logStats) {
            stats.append("Target PTS: ").append(targetPTS);
            stats.append(" frame time: ").append(frameTime).append("\n");
        }

        // make sure the queue is not empty
        IVideoPicture out = frameQueue.peek();
        if (out == null) {
            // empty queue
            return null;
        }

        // find the first frame after the targetPTS
        while (out != null && out.getTimeStamp() < targetPTS - frameTime) {
            frameQueue.poll();

            if (logStats) {
                stats.append("    Early: ").append(out.getTimeStamp()).append("\n");
            }

            out = frameQueue.peek();
        }

        // see if it is too far in the future
        if (out != null && Math.abs(out.getTimeStamp() - targetPTS) < frameTime) {
            if (logStats) {
                stats.append("    GOOD : ").append(out.getTimeStamp()).append("\n");
            }

            frameQueue.poll();
        } else {
            if (logStats && out != null) {
                stats.append("    Late : ").append(out.getTimeStamp()).append("\n");
            }

            // don't take the frame, just return null
            out = null;
        }

        if (logStats) {
            LOGGER.fine(stats.toString());
        }

        // update last frame time
        if (out != null) {
            lastFrameTime = out.getTimeStamp() / 1000000.0;
        }
            
        return out;
    }

    /**
     * Close video media
     */
    @Override
    public void closeMedia() {
        LOGGER.fine("closing video");
        stop(false);

        // remove any leftover frames
        nextFrame();

        notifyFrameListenersClose();

        mediaURI = null;
        setState(VideoPlayerState.NO_MEDIA);
    }

    /**
     * Gets the URL of the currently loaded media as a String
     * @return the URL of the media
     */
    @Override
    public String getMedia() {
        return mediaURI;
    }

    /**
     * Get the dimension of video frames in this video
     * @return the frame size
     */
    public synchronized Dimension getFrameSize() {
        Dimension dimension = new Dimension();

        if (queueFiller != null) {
            return queueFiller.getSize();
        }

        return dimension;
    }

    /**
     * Determine if media player is ready to play media
     * @return true if the media player is ready to play, false otherwise
     */
    @Override
    public boolean isPlayable() {
        return (getState() != VideoPlayerState.NO_MEDIA);
    }

    /**
     * Play media
     */
    @Override
    public void play() {
        LOGGER.warning("play");

        if (isPlayable() && (getState() != VideoPlayerState.PLAYING)) {
            setState(VideoPlayerState.PLAYING);

            // read packets from the queue
            queueFiller.enable();

            // start the time source with the first packet to play
            startTimeSource();
            
            // start audio
            audioQueue.start();

            // notify listeners
            notifyFrameListenersPlay(this);
        }
    }

    /**
     * Gets whether the media is currently playing
     * @return true if the media is playing, false otherwise
     */
    @Override
    public boolean isPlaying() {
        return (isPlayable() && (getState() == VideoPlayerState.PLAYING));
    }
    
    @Override
    public void pause() {
        LOGGER.warning("pause");

        if (isPlayable() && (getState() != VideoPlayerState.PAUSED)) {
            setState(VideoPlayerState.PAUSED);

            audioQueue.stop();
            frameQueue.clear();
            timeSource.stop();
            
            setNeedsPreview(true);
        }
    }

    /**
     * Stop playing media
     */
    @Override
    public void stop() {
        stop(true);
    }
    
    /**
     * Stop playing the media, and optionally restart it from the beginning
     * @param restart true to restart from the beginning
     */
    protected void stop(boolean restart) {
        LOGGER.warning("stop");

        if (isPlayable() && (getState() == VideoPlayerState.PLAYING || 
                             getState() == VideoPlayerState.PAUSED)) 
        {
            // stop the current video
            queueFiller.disable();
            audioQueue.stop();
            frameQueue.clear();
            timeSource.stop();
            
            // remove any leftover frames
            notifyFrameListenersStop();
            
            // we should preview the next frame we see
            setNeedsPreview(true);
            
            // restart the queue filler at the beginning of the media
            if (restart) {
                queueFiller.enable();
                setState(VideoPlayerState.MEDIA_READY);
            }
        }
    }
    
    public boolean isSeekEnabled() {
        return queueFiller.canSeek();
    }
    
    @Override
    public synchronized void rewind(double offset) {
        double time = Math.max(lastFrameTime - offset, 0.0f);
        setPosition(time);
    }

    @Override
    public synchronized void forward(double offset) {
        double time = lastFrameTime + offset;
        setPosition(time);
    }

    @Override
    public synchronized void setPosition(double mediaPosition) {
        queueFiller.seek(mediaPosition);
        setNeedsPreview(true);
    }

    @Override
    public synchronized double getPosition() {
        return lastFrameTime;
    }

    @Override
    public double getDuration() {
        return queueFiller.getDuration();
    }

    @Override
    public synchronized void mute() {
        mute = true;
    }

    @Override
    public synchronized void unmute() {
        mute = false;
    }

    @Override
    public synchronized boolean isMuted() {
        return mute;
    }

    @Override
    public synchronized void setVolume(float volume) {
        this.volume = volume;
    }

    @Override
    public synchronized float getVolume() {
        if (isMuted()) {
            return 0f;
        }

        return this.volume;
    }

    /**
     * Sets the state of the player
     * @param mediaState the new player state
     */
    private void setState(VideoPlayerState state) {
        VideoPlayerState oldState;
        
        synchronized (this) {
            oldState = mediaState;
            mediaState = state;
        }
        
        notifyStateListeners(oldState, mediaState);
    
        if (state == VideoPlayerState.MEDIA_READY) {
            setNeedsPreview(true);
        }
    }

    /**
     * Gets the state of the player
     * @return the player state
     */
    @Override
    public synchronized VideoPlayerState getState() {
        return mediaState;
    }
    
    @Override
    public void newStream(int id, IStreamCoder coder) {
        if (coder.getCodecType() == ICodec.Type.CODEC_TYPE_AUDIO) {
            try {
                LOGGER.warning("Open java sound");
                line = openJavaSound(coder);
            } catch (LineUnavailableException ex) {
                LOGGER.log(Level.WARNING, "Error opening line", ex);
            }
        } else if (coder.getCodecType() == ICodec.Type.CODEC_TYPE_VIDEO) {
            // calculate how long each frame should be visible -- used
            // in picking frames during getNextFrame();
            frameTime = (long) (1000000 * (1.0 / coder.getFrameRate().getDouble()));

            notifyFrameListenersOpen(coder.getWidth(), coder.getHeight(),
                                     coder.getPixelType());
        }
    }

    @Override
    public void add(IVideoPicture picture) throws InterruptedException {
        // do we need a preview frame
        if (isNeedsPreview()) {
            notifyFrameListenersPreview(picture);
            setNeedsPreview(false);
            lastFrameTime = picture.getTimeStamp() / 1000000.0;
        }

        frameQueue.put((IVideoPicture) picture);   
        pingTimeSource();
    }
    
    @Override
    public void add(AudioFrame frame) throws InterruptedException {
        audioQueue.add(frame);
        pingTimeSource();
    }
    
    @Override
    public void clear() {
        clear(true);
    }
    
    private void clear(boolean resetTimeSource) {
        LOGGER.warning("Clear");
        
        frameQueue.clear();
        audioQueue.clear();
        
        // stop the time source -- it will be restarted the next time data
        // is added to the queue
        if (resetTimeSource) {
            timeSource.stop();
        }
    }
    
    private void startTimeSource() {
        if (timeSource.isStarted()) {
            return;
        }
        
        // find the first time by looking at the first element in the audio
        // and video queue
        long videoTime = Long.MAX_VALUE;
        long audioTime = Long.MAX_VALUE;
        
        if (!frameQueue.isEmpty()) {
            videoTime = frameQueue.peek().getPts();
        }
        
        if (!audioQueue.isEmpty()) {
            audioTime = audioQueue.peek().getPTS();
        }
        
        LOGGER.fine("Start time source: audio: " + audioTime + 
                       " video: " + videoTime);
        
        timeSource.start(Math.min(audioTime, videoTime));
    }
    
    private void pingTimeSource() {
        // if we are playing but the time source is not started, start it
        // with the time from the first packet we receive
        if (getState() == VideoPlayerState.PLAYING && !timeSource.isStarted()) {
            startTimeSource();
        } else {
            timeSource.ping();
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

    /**
     * Shutdown JavaSound
     */
    private static void closeJavaSound(SourceDataLine line) {
        // close the line.
        if (line != null) {
            line.close();
        }
    }
    
    private synchronized boolean isNeedsPreview() {
        return needsPreview;
    }
    
    private synchronized void setNeedsPreview(boolean needsPreview) {
        this.needsPreview = needsPreview;
    }

    class AudioThread extends LinkedBlockingQueue<AudioFrame> implements Runnable {
        private Thread thread;
        private boolean quit = false;
        private boolean cleared = false;
        
        private float frameSize;
        private float frameRate;
        
        public AudioThread() {
            super (65536 / 128);
        }

        public synchronized void start() {
            if (thread != null) {
                // already started
                LOGGER.warning("Audio thread already started");
                return;
            }

            thread = new Thread(this, "Audio queue filler");
            quit = false;
            thread.start();
        }

        public synchronized void stop() {
            if (thread == null) {
                // already stopped
                LOGGER.warning("Audio thread already stopped");
                return;
            }

            quit = true;
            if (thread != null) {
                thread.interrupt();
            }

            clear();

            // wait for thread to exit
            try {
                while (thread != null) {
                    wait();
                }
            } catch (InterruptedException ie) {
                // break out of loop
            }
        }

        @Override
        public void run() {
            try {
                line.start();
                
                // variables used in calculating buffer delay
                frameSize = line.getFormat().getFrameSize() *
                            line.getFormat().getChannels();
                frameRate = line.getFormat().getFrameRate();
             
                while (!isQuit()) {
                    AudioFrame sample = poll();
                    if (sample != null) {
                        // figure out the estimaged play time (the current PTS + the
                        // time needed for this packet to get through the buffer)
                        long playTime = timeSource.getCurrentPTS() + bufferDelay();
                        
                        // wait until it is time to put this packet on the buffer
                        long timeDiff = sample.getPTS() - playTime;
                        
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("Load audio at " + (sample.getPTS() / 1000000.0) + 
                                        " Play time: " + (playTime / 1000000.0) + 
                                        " Time diff: " + (timeDiff / 1000000.0));
                        }
                        
                        while (timeDiff > 1000 && !isCleared() && !isQuit()) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine("Audio early: " + (timeDiff / 1000000.0));;
                            }
                            
                            // sleep up to 50 milliseconds and recheck
                            long millisDelay = Math.min(timeDiff / 1000, 50);
                            int nanosDelay = ((int) (timeDiff % 1000)) * 1000;
                            
                            Thread.sleep(millisDelay, nanosDelay);
                            
                            playTime = timeSource.getCurrentPTS() + bufferDelay();
                            timeDiff = sample.getPTS() - playTime;
                        }
                        
                        // before we play the packet, make sure we are not
                        // quit or cleared
                        if (isQuit()) {
                            break;
                        }
                        
                        if (isCleared()) {
                            // acknowledge the clear and move on
                            setCleared(false);
                            continue;
                        }
                        
                        // figure out what time we think it is now, based on
                        // the sample we are putting on the queue
                        long actualTime = sample.getPTS() - bufferDelay();
                        
                        // at this point, add the sample to the queue
                        byte[] rawBytes = sample.getData();
                        int length = sample.getLength();
                        
                        byte[] adjustedBytes = adjustVolume(line, getVolume(), 
                                                            rawBytes, 0, length);
                        playJavaSound(line, adjustedBytes, length);
                    
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("Play audio at " + (sample.getPTS() / 1000000.0) + 
                                        " Play time: " + (playTime / 1000000.0) + 
                                        " Time diff: " + (timeDiff / 1000000.0) + 
                                        " Actual time: " + (actualTime / 1000000.0) + 
                                           " Time source: " + (timeSource.getCurrentPTS() / 1000000.0));
                        }
                        
                        // if the time difference was too high (because we are 
                        // getting behind, adjust the time source to match what
                        // we think is the correct time
                        if (timeSource.getCurrentPTS() - actualTime > 50000) {
                            timeSource.adjust(actualTime);
                        }
                    }
                }

                line.stop();
            } catch (InterruptedException ie) {
                // break out of loop
            } finally {
                synchronized (this) {
                    thread = null;
                    notify();
                }
            }
        }
        
        /**
         * Calculate the approximate buffer delay (in microseconds) for the
         * buffer.
         * @return the buffer delay in microseconds 
         */
        private long bufferDelay() {
            // get the number of bytes available
            int bufferRemaining = line.getBufferSize() - line.available();

            // convert to number of frames
            bufferRemaining /= frameSize;

            // calculate time in buffer in seconds
            float bufferTime = bufferRemaining / frameRate;
            long bufferTimeMicros = (long) (bufferTime * 1000000);
            
            return bufferTimeMicros;
        }

        @Override
        public void clear() {
            super.clear();
        
            // set a variable for the queue filler
            setCleared(true);
            
            // clear javasound
            line.flush();
        }
        
        private synchronized boolean isCleared() {
            return cleared;
        }
        
        private synchronized void setCleared(boolean cleared) {
            this.cleared = cleared;
        }
        
        private synchronized boolean isQuit() {
            return quit;
        }
    }
 
    private class SystemClockTimeSource implements Runnable {
        // ping timeout (in milliseconds)
        private static final long PING_TIMEOUT = 1000; 
        
        // the last time that was set
        private long setPTS;
        private long setTime;
        
        // last ping time
        private long lastPing;
        
        private Thread thread = null;
        private boolean quit = false;
        
        public synchronized void start(long pts) {
            LOGGER.warning("Start time source at " + (pts / 1000000.0));
            
            if (thread != null) {
                throw new IllegalStateException("Already started");
            }
            
            setPTS = pts;
            setTime = System.nanoTime();
            
            quit = false;
            
            thread = new Thread(this, "Video time source");
            thread.start();
        }
        
        public synchronized void adjust(long pts) {
            LOGGER.warning("Adjust time source to " + (pts / 1000000.0));
            
            setPTS = pts;
            setTime = System.nanoTime();
        }
        
        public synchronized boolean isStarted() {
            return thread != null;
        }
        
        public synchronized void stop() {
            LOGGER.warning("Stop time source");
            
            quit = true;
            notify();
            
            try {
                while (thread != null) {
                    wait();
                }
            } catch (InterruptedException ie) {}
        }

        public synchronized long getCurrentPTS() {
            // the time difference in nanoseconds
            long timeDiff = System.nanoTime() - setTime;
            
            // convert to microseconds
            timeDiff /= 1000;
            
            // add the difference to the initial PTS
            return setPTS + timeDiff;
        }

        public synchronized void ping() {
            lastPing = System.currentTimeMillis();
        }

        public void run() {
            // initial time
            lastPing = System.currentTimeMillis();
            
            try {
                while (!isQuit()) {
                    // see if there has been a timeout
                    
                    if (System.currentTimeMillis() - getLastPing() > PING_TIMEOUT) {
                        LOGGER.warning("Ping timeout");
                        clear(false);
                        break;
                    }
                    
                    // wait until the next timeout
                    synchronized (this) {
                        try {
                            wait(PING_TIMEOUT);
                        } catch (InterruptedException ie) {}
                    }
                }
            } finally {
                synchronized (this) {
                    thread = null;
                    notify();
                }
            }
        }
        
        private synchronized long getLastPing() {
            return lastPing;
        }
        
        private synchronized boolean isQuit() {
            return quit;
        }
    }
}
