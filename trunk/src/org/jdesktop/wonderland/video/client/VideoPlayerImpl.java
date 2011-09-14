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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.jdesktop.wonderland.video.client.AudioInputStream.ReadTimeout;
import org.jdesktop.wonderland.video.client.FrameListener.FrameQueue;
import org.jdesktop.wonderland.video.client.VideoQueueFiller.AudioFrame;
import org.jdesktop.wonderland.video.client.VideoQueueFiller.VideoQueue;

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
    //private final SystemClockTimeSource timeSource = new SystemClockTimeSource();
    
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
    
    private boolean finished = false;
    
    public VideoPlayerImpl() {
        audioQueue = new AudioThread();
        frameQueue = new LinkedBlockingQueue<IVideoPicture>(4);
    
        queueFiller = createQueueFiller(this);
    }
    
    /**
     * Create a video queue filler
     * @param queue the video queue to use
     * @return the queue filler to use
     */
    protected VideoQueueFiller createQueueFiller(VideoQueue queue) {
        return new VideoQueueFiller(queue);
    }
    
    /**
     * Get the video queue filler
     * @return the video queue filler
     */
    protected VideoQueueFiller getQueueFiller() {
        return queueFiller;
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
                    setState(VideoPlayerState.NO_MEDIA);
                    setFinished(false);
                    if (queueFiller.openMedia(mediaURI)) {
                        setState(VideoPlayerState.MEDIA_READY);
                    } else {
                        LOGGER.warning("Unable to open " + uri);
                        setState(VideoPlayerState.NO_MEDIA);
                    }
                           
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
        if (!audioQueue.isRunning()) {
            return null;
        }
                
        // find the target time
        long targetPTS = audioQueue.getCurrentPTS();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("Video packet line time: %d. " +
                        "Wall time: %d", targetPTS, audioQueue.getWallTime()));
        }
        
        boolean logStats = LOGGER.isLoggable(Level.FINE);
        StringBuilder stats = new StringBuilder();

        if (logStats) {
            stats.append("Target PTS: ").append(targetPTS);
            stats.append(" frame time: ").append(frameTime).append("\n");
        }

        // make sure the queue is not empty
        IVideoPicture out = frameQueue.peek();
        if (out == null) {
            // empty queue -- is the video finished?
            if (isFinished()) {
                stop();
            }
            
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
            setFinished(false);
            queueFiller.enable();
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
            audioQueue.close();
            frameQueue.clear();
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
        LOGGER.warning("stop -- restart: " + restart);

        if (isPlayable() && (getState() == VideoPlayerState.PLAYING || 
                             getState() == VideoPlayerState.PAUSED)) 
        {
            // stop the current video
            queueFiller.disable();
            audioQueue.close();
            frameQueue.clear();
            
            // remove any leftover frames
            notifyFrameListenersStop();
            
            // we should preview the next frame we see
            setNeedsPreview(true);
            
            // set the state
            setState(VideoPlayerState.STOPPED);
            
            // restart the queue filler at the beginning of the media
            if (restart) {
                setFinished(false);
                queueFiller.enable();
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
    
        if (state == VideoPlayerState.MEDIA_READY || 
            state == VideoPlayerState.STOPPED) 
        {
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
            audioQueue.setAudioCoder(coder);
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
        updateTimeSource(picture.getTimeStamp());

        // do we need a preview frame
        if (isNeedsPreview()) {
            notifyFrameListenersPreview(picture);
            setNeedsPreview(false);
            lastFrameTime = picture.getTimeStamp() / 1000000.0;
        }

        frameQueue.put((IVideoPicture) picture);   
    }
    
    @Override
    public void add(AudioFrame frame) throws InterruptedException {
        updateTimeSource(frame.getPTS());
        audioQueue.add(frame);
    }
    
    @Override
    public void clear() {
        LOGGER.warning("Clear");
        
        // remove all pending video frames
        frameQueue.clear();
        
        // stop the audio queue. If the video is still playing, the queue
        // will automatically be restarted the first time a packet is added
        audioQueue.close();
    }
    
    @Override
    public void finished() {
        // notification that there is no more data
        setFinished(true);
    }
    
    private synchronized void setFinished(boolean finished) {
        this.finished = finished;
    }
    
    private synchronized boolean isFinished() {
        return finished;
    }
    
    private void updateTimeSource(long timestamp) {
        // open the queue if this is the first packet we see. This automatically
        // sets the start time of the queue to the PTS of the first packet after
        // a clear
        if (!audioQueue.isOpen()) {
            LOGGER.fine("Open time source at time " + (timestamp / 1000000.0));
            audioQueue.open(timestamp);
        }
        
        // automatically restart the time source if the video is currently
        // playing and we aren't seeking. In the case of a seek, this will 
        // restart the playback as soon as both the audio and video queues
        // have content. Note that we wait for the frame queue to fill to
        // one less than capacity, indicating that all video frames have
        // be cached for writing
        if (!audioQueue.isRunning() &&  
            getState() == VideoPlayerState.PLAYING && 
            !queueFiller.isSeeking() &&
            frameQueue.remainingCapacity() == 1) 
        {
            audioQueue.start();
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
    
    class AudioThread implements Runnable, ReadTimeout {
        private IStreamCoder audioCoder;
        private SourceDataLine line;
        private int frameSize;
        private byte[] buffer;
        
        private Thread thread;
        private boolean quit;
        private AudioInputStream audioStream;
        
        private long startPTS;
        private long lineStartTime;
        private long bytesWritten;
        private long wallTime;
        private boolean firstRead;
        
        private int underrunCount;
        
        public synchronized void setAudioCoder(IStreamCoder audioCoder) {
            this.audioCoder = audioCoder;
        
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Set audio coder. Sample rate: %d, " +
                            " format: %s, channels: %d.", 
                            audioCoder.getSampleRate(), 
                            audioCoder.getSampleFormat().name(),
                            audioCoder.getChannels()));
            }
        }
        
        public synchronized void open(long startPTS) {
            this.startPTS = startPTS;
            audioStream = new AudioInputStream();
            underrunCount = 0;
            
            int sampleRate = audioCoder.getSampleRate();
            int channels = audioCoder.getChannels();
            float sampleSize = 
                    IAudioSamples.findSampleBitDepth(audioCoder.getSampleFormat());
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Open audio. Sample rate: %d, " +
                            " sampleSize: %f, channels: %d.",
                            sampleRate, sampleSize, channels));
            }
            
            audioStream.start(startPTS, sampleRate,
                              (int) (sampleSize * channels));
            
        }
        
        public synchronized boolean isOpen() {
            return audioStream != null;
        }
        
        public void add(AudioFrame frame) {
            audioStream.add(frame);
        }
        
        public synchronized void start() {
            if (isRunning()) {
                throw new IllegalStateException("Already started");
            }
            
            quit = false;
            
            thread = new Thread(this, "Audio player thread");
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    LOGGER.log(Level.WARNING, "Error in audio thread", e);
                }
            });
            thread.start();
        }
        
        public synchronized void stop() {
            quit = true;
            
            try {
                while (isRunning()) {
                    thread.interrupt();
                    wait();
                } 
            } catch (InterruptedException ie) {
            }
            
            if (line != null) {
                line.close();
            }
        }
        
        public synchronized void close() {
            stop();
            
            if (audioStream != null) {
                audioStream.clear();
                audioStream = null;
            }
            
            if (line != null) {
                line.flush();
                line = null;
            }
        }
        
        public synchronized boolean isRunning() {
            return thread != null;
        }
        
        public synchronized long getCurrentPTS() {
            if (line == null) {
                return startPTS;
            }
            
            return startPTS + line.getMicrosecondPosition() - lineStartTime;
        }
        
        public synchronized long getWallTime() {
            return (System.nanoTime() - wallTime) / 1000;
        }
        
        public void run() {
            try {
                synchronized (this) {
                    line = openJavaSound(audioCoder);
                    setLine(line);  
                
                    line.start();
            
                    // start with the number of bytes already in the line
                    bytesWritten = line.getLongFramePosition() * frameSize;
            
                    // record the start time of the line
                    lineStartTime = line.getMicrosecondPosition();
            
                    // record the wall time we started as well for comparison
                    wallTime = System.nanoTime();
                }
            
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("Start audio thread: bytes = " +
                                "%d lineStartTime = %d", bytesWritten, 
                                lineStartTime));
                }
            
                while (!isQuit()) {
                    try {
                        fillBuffer();
                    } catch (InterruptedException ie) {
                        // if quit is true, we will now break out of the loop
                    }
                }
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("Stop audio thread: bytes = %d " +
                                " lineEndTime = %d", bytesWritten, 
                                line.getMicrosecondPosition()));
                }
                
                closeJavaSound(line);
            } catch (LineUnavailableException lue) {
                LOGGER.log(Level.WARNING, "Line unavailable", lue);
            } finally {                
                synchronized (this) {
                    thread = null;
                    notifyAll();
                }
            }
        }      
        
        private void fillBuffer() throws InterruptedException {
            // calculate the minimum amound of data to read, rounded up
            // to the nearest frame
            int minBytes = audioStream.microsecondsToBytes(20000);
            if (minBytes % frameSize != 0) {
                minBytes += frameSize - (minBytes % frameSize);
            }            
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Fill audio buffer at %d " +
                            "microseconds. Wall time: %d.", 
                            line.getMicrosecondPosition(), getWallTime()));
            }
            
            // read data from the input stream 
            int read = audioStream.read(buffer, minBytes, this);
            
            // adjust volume manually, since doing it via javasound is
            // unreliable
            byte[] adjusted = adjustVolume(line, volume, buffer, 0, read);
            
            // check for buffer underrun
            long lineBytes = line.getLongFramePosition() * frameSize;
            if (lineBytes == bytesWritten) {
                LOGGER.warning("Audio underrun");
                underrunCount++;
                
                // if we get more than one consecutive audio underrun, it
                // means something is wrong -- we are behind in a stream
                // or something similar. Clear out the various buffers and
                // restart the stream when we have data again
                if (underrunCount >= 2) {
                    VideoPlayerImpl.this.clear();
                    return;
                }
            } else {
                underrunCount = 0;
            }
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Fill audio buffer read %d bytes at " +
                            "%d microseconds. Write %d microseconds to " +
                            " line. Wall time: %d.", 
                            read, line.getMicrosecondPosition(), 
                            audioStream.bytesToMicroseconds(read),
                            getWallTime()));
            }
            
            // send data to JavaSound
            playJavaSound(line, adjusted, read);
            
            // update our internal tracking
            synchronized (this) {
                bytesWritten += read;
            }
        }
        
        public synchronized long getReadTimeout(int bytesRead) {
            // estimate how long it will take to use all the data in the
            // audio buffer
            long lineBytes = line.getLongFramePosition() * frameSize;
            long bufferBytes = bytesWritten - lineBytes;
            long bufferMicros = audioStream.bytesToMicroseconds(bufferBytes);
            
            // special case -- if this is the first read after the buffer
            // has started, give some time to fill the initial buffer
            if (firstRead) {
                firstRead = false;
                bufferMicros = 20000;
            }
            
            // if there is data in the buffer, pad our timing
            // so we don't have audio underruns while we are waiting to
            // fill the buffer. The number used for padding (12000 microseconds)
            // is the approximate resolution observed on the Mac implementation
            // of JavaSound. A similar resolution was estimated on Windows.
            // By padding by the timer resolution, we guarantee the audio 
            // won't underrun because the timer hasn't updated yet.
            if (bytesRead > 0) {
                bufferMicros -= 12000;
            }
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("Read timeout %d (line %d, " +
                            "written %d) at wall time %d",
                            bufferMicros, lineBytes, bytesWritten,
                            getWallTime()));
            }
            
            return Math.max(bufferMicros, 0);
        }
        
        private void setLine(SourceDataLine line) {
            this.line = line;
            this.frameSize = line.getFormat().getFrameSize();
            
            // buffer size is approximately 1/4 the audio buffer size, rounded
            // to the nearest frame
            int bufferSize = line.getBufferSize() / 4;
            bufferSize -= bufferSize % frameSize;
            this.buffer = new byte[bufferSize];
            
            this.firstRead = true;
            
            if (LOGGER.isLoggable(Level.FINE)) {
                line.addLineListener(new LineListener() {
                    public void update(LineEvent event) {
                        long bytePosition = event.getFramePosition() * frameSize;
                        LOGGER.fine(String.format("Line %s at %d", 
                                    event.getType(), bytePosition));
                    }                
                });
            }
        }
        
        private synchronized boolean isQuit() {
            return quit;
        }
    }
}
