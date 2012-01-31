/**
 * Open Wonderland
 *
 * Copyright (c) 2011 - 2012, Open Wonderland Foundation, All Rights Reserved
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

import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IVideoPicture;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.IConverter;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.jdesktop.wonderland.video.client.VideoPlayer.VideoPlayerState;

/**
 *
 * @author jkaplan
 */
public class SwingVideoPlayer extends javax.swing.JFrame
    implements FrameListener, VideoStateListener
{
    private static final Logger LOGGER =
            Logger.getLogger(SwingVideoPlayer.class.getName());

    private final VideoStateSource player;

    private IConverter converter;
    private boolean playing = false;

    private final List<String> recents = new ArrayList<String>();
    
    private static ThreadLocal<Boolean> localChange = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }        
    };
    
    /** Creates new form VideoJFrame */
    public SwingVideoPlayer() {
        initComponents();

        player = new VideoPlayerImpl();
        player.addStateListener(this);
        player.addFrameListener(this);
        
        reloadRecents();
    }

    @Override
    public void mediaStateChanged(VideoPlayerState oldState,
                                  VideoPlayerState newState)
    {
        boolean canSeek = player.isSeekEnabled();
        
        LOGGER.warning("State: " + newState + " Seek: " + canSeek);
        
        switch (newState) {
            case NO_MEDIA:
                playButton.setEnabled(false);
                stopButton.setEnabled(false);
                pauseButton.setEnabled(false);
                forwardButton.setEnabled(false);
                backButton.setEnabled(false);
                timeSlider.setEnabled(false);
                break;
                
            case MEDIA_READY:
            case STOPPED:
                playButton.setEnabled(true);
                stopButton.setEnabled(false);
                pauseButton.setEnabled(false);
                forwardButton.setEnabled(canSeek);
                backButton.setEnabled(canSeek);
                timeSlider.setEnabled(canSeek);
                
                if (canSeek) {
                    localChange.set(true);
                    try {
                        timeSlider.setMaximum((int) (player.getDuration() * 100));
                        timeLabel.setText("0:0.0");
                    } finally {
                        localChange.set(false);
                    }
                        
                }
                break;
                
            case PAUSED:
                playButton.setEnabled(true);
                stopButton.setEnabled(true);
                pauseButton.setEnabled(false);
                forwardButton.setEnabled(canSeek);
                backButton.setEnabled(canSeek);
                timeSlider.setEnabled(canSeek);
                break;
                
            case PLAYING:
                playButton.setEnabled(false);
                stopButton.setEnabled(true);
                pauseButton.setEnabled(true);
                forwardButton.setEnabled(canSeek);
                backButton.setEnabled(canSeek);
                timeSlider.setEnabled(canSeek);
                break;
        }
        
        stateLabel.setText(newState.name());
    }

    @Override
    public void openVideo(int videoWidth, int videoHeight, IPixelFormat.Type videoFormat) {
        setVisible(false);
        videoLabel.setSize(videoWidth, videoHeight);
        videoLabel.setPreferredSize(new Dimension(videoWidth, videoHeight));
        
        converter = ConverterFactory.createConverter(ConverterFactory.XUGGLER_BGR_24,
                videoFormat, videoWidth, videoHeight, videoWidth, videoHeight);
    
        pack();
        setVisible(true);
    }

    @Override
    public void playVideo(final FrameQueue queue) {
        setPlaying(true);

        SwingWorker worker = new SwingWorker<Object, TimedImage>() {
            @Override
            protected Object doInBackground() throws Exception {
                try {
                    while (isPlaying()) {
                        // set the target time to 30ms from now
                        long sleepTarget = System.currentTimeMillis() + 30;
                        
                        IVideoPicture frame = queue.nextFrame();
                        if (frame != null) {
                            Image image = converter.toImage(frame);
                            publish(new TimedImage(image, frame.getTimeStamp()));
                        }

                        long now = System.currentTimeMillis();
                        while (now < sleepTarget) {
                            try {
                                Thread.sleep(sleepTarget - now);
                            } catch (InterruptedException ie) {
                            }
                            
                            now = System.currentTimeMillis();
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, null, t);
                }

                return null;
            }

            @Override
            protected void process(List<TimedImage> chunks) {
                processImage(chunks.get(chunks.size() - 1));         
            }
        };
        worker.execute();
    }
    
    @Override
    public void previewFrame(final IVideoPicture frame) {
        LOGGER.warning("Preview frame: " + frame);
        
        SwingWorker worker = new SwingWorker<Object, TimedImage>() {
            @Override
            protected Object doInBackground() throws Exception {
                try {
                    if (frame != null) {
                        Image image = converter.toImage(frame);
                        publish(new TimedImage(image, frame.getTimeStamp()));
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, null, t);
                }

                return null;
            }

            @Override
            protected void process(List<TimedImage> chunks) {
                processImage(chunks.get(chunks.size() - 1));
            }
        };
        worker.execute();
    }
    
    private void processImage(TimedImage i) {
        videoLabel.setIcon(new ImageIcon(i.getImage()));
                
        // don't update times if the user is moving the slider
        if (timeSlider.getValueIsAdjusting()) {
            return;
        }
        
        double secondsTime = i.getTime() / 1000000.0;
        int minutes = (int) secondsTime / 60;
        double seconds = secondsTime % 60.0;
        
        timeLabel.setText(String.format("%02d:%05.2f", minutes, seconds));
        
        int timelineTime = (int) (secondsTime * 100);
        
        localChange.set(true);
        try {
            timeSlider.setValue(timelineTime);
        } finally {
            localChange.set(false);
        }
    }

    @Override
    public void stopVideo() {
        setPlaying(false);
        
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                localChange.set(true);
                try {
                    timeSlider.setValue(0);
                    timeLabel.setText("00:00.00");
                } finally {
                    localChange.set(false);
                }
            }
        });
    }

    @Override
    public void closeVideo() {
        setPlaying(false);
        
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                localChange.set(true);
                try {
                    timeSlider.setValue(0);
                    timeLabel.setText("00:00.00");
                } finally {
                    localChange.set(false);
                }
            }
        });
    }

    private synchronized void setPlaying(boolean playing) {
        this.playing = playing;
    }

    private synchronized boolean isPlaying() {
        return playing;
    }


    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        videoChooser = new javax.swing.JFileChooser();
        videoLabel = new javax.swing.JLabel();
        filePanel = new javax.swing.JPanel();
        stopButton = new javax.swing.JButton();
        playButton = new javax.swing.JButton();
        forwardButton = new javax.swing.JButton();
        backButton = new javax.swing.JButton();
        pauseButton = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        stateLabel = new javax.swing.JLabel();
        timeSlider = new javax.swing.JSlider();
        jLabel3 = new javax.swing.JLabel();
        timeLabel = new javax.swing.JLabel();
        menuBar = new javax.swing.JMenuBar();
        fileMenu = new javax.swing.JMenu();
        openMenuItem = new javax.swing.JMenuItem();
        openURLMenuItem = new javax.swing.JMenuItem();
        recentsMenu = new javax.swing.JMenu();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        quitMenuItem = new javax.swing.JMenuItem();

        videoChooser.setDialogTitle("Choose Video");

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Video Player");

        videoLabel.setPreferredSize(new java.awt.Dimension(640, 480));
        getContentPane().add(videoLabel, java.awt.BorderLayout.CENTER);

        filePanel.setPreferredSize(new java.awt.Dimension(480, 82));

        stopButton.setText("Stop");
        stopButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopButtonActionPerformed(evt);
            }
        });

        playButton.setText("Play");
        playButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                playButtonActionPerformed(evt);
            }
        });

        forwardButton.setText("Forward");
        forwardButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                forwardButtonActionPerformed(evt);
            }
        });

        backButton.setText("Back");
        backButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                backButtonActionPerformed(evt);
            }
        });

        pauseButton.setText("Pause");
        pauseButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                pauseButtonActionPerformed(evt);
            }
        });

        jLabel1.setText("State:");

        stateLabel.setText("MEDIA READY");

        timeSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                timeSliderStateChanged(evt);
            }
        });

        jLabel3.setText("Time:");

        timeLabel.setText("3:31.25");

        javax.swing.GroupLayout filePanelLayout = new javax.swing.GroupLayout(filePanel);
        filePanel.setLayout(filePanelLayout);
        filePanelLayout.setHorizontalGroup(
            filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(filePanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(filePanelLayout.createSequentialGroup()
                        .addComponent(stopButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(playButton))
                    .addGroup(filePanelLayout.createSequentialGroup()
                        .addComponent(jLabel1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(stateLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel3)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(filePanelLayout.createSequentialGroup()
                        .addComponent(pauseButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(backButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(forwardButton))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, filePanelLayout.createSequentialGroup()
                        .addComponent(timeSlider, javax.swing.GroupLayout.DEFAULT_SIZE, 173, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(timeLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 78, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(294, 294, 294))
        );
        filePanelLayout.setVerticalGroup(
            filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(filePanelLayout.createSequentialGroup()
                .addGroup(filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(stopButton)
                    .addComponent(playButton)
                    .addComponent(pauseButton)
                    .addComponent(backButton)
                    .addComponent(forwardButton))
                .addGap(17, 17, 17)
                .addGroup(filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(filePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(jLabel1)
                        .addComponent(stateLabel)
                        .addComponent(jLabel3))
                    .addComponent(timeSlider, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(timeLabel))
                .addContainerGap())
        );

        getContentPane().add(filePanel, java.awt.BorderLayout.PAGE_END);

        fileMenu.setText("File");

        openMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, 0));
        openMenuItem.setText("Open");
        openMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(openMenuItem);

        openURLMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_U, 0));
        openURLMenuItem.setText("Open URL...");
        openURLMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openURLMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(openURLMenuItem);

        recentsMenu.setText("Recently Played");
        fileMenu.add(recentsMenu);
        fileMenu.add(jSeparator1);

        quitMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Q, 0));
        quitMenuItem.setText("Quit");
        quitMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                quitMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(quitMenuItem);

        menuBar.add(fileMenu);

        setJMenuBar(menuBar);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void playButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_playButtonActionPerformed
        player.play();
    }//GEN-LAST:event_playButtonActionPerformed

    private void stopButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_stopButtonActionPerformed
        player.stop();
    }//GEN-LAST:event_stopButtonActionPerformed

    private void pauseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_pauseButtonActionPerformed
        player.pause();
    }//GEN-LAST:event_pauseButtonActionPerformed

    private void forwardButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_forwardButtonActionPerformed
        player.forward(5.0);
    }//GEN-LAST:event_forwardButtonActionPerformed

    private void backButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_backButtonActionPerformed
        player.rewind(5.0);
    }//GEN-LAST:event_backButtonActionPerformed

    private void openMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openMenuItemActionPerformed
        int retVal = videoChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            File selected = videoChooser.getSelectedFile();
            
            try {
                player.openMedia(selected.toURI().toURL().toExternalForm());
                addToRecents(selected.toURI().toURL().toExternalForm());
            } catch (MalformedURLException ex) {
                LOGGER.log(Level.WARNING, "Error opening " + selected, ex);
            }
        }
    }//GEN-LAST:event_openMenuItemActionPerformed

    private void quitMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_quitMenuItemActionPerformed
        System.exit(0);
    }//GEN-LAST:event_quitMenuItemActionPerformed

    private void openURLMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openURLMenuItemActionPerformed
        String URL = JOptionPane.showInputDialog(this, "Enter Video URL", "Video URL", 
                                                 JOptionPane.QUESTION_MESSAGE);
        player.openMedia(URL);
        addToRecents(URL);
    }//GEN-LAST:event_openURLMenuItemActionPerformed

    private void timeSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_timeSliderStateChanged
        if (localChange.get()) {
            // ignore changes that originated automatically
            return;
        }
        
        double targetTime = timeSlider.getValue() / 100.0;
        int minutes = (int) targetTime / 60;
        double seconds = targetTime % 60.0;
        
        timeLabel.setText(String.format("%02d:%05.2f", minutes, seconds));
        
        if (!timeSlider.getValueIsAdjusting()) {
            player.setPosition(targetTime);
            
            LOGGER.warning("Slider at " + targetTime);
        }
    }//GEN-LAST:event_timeSliderStateChanged
    
    private void addToRecents(String uri) {
        // first see if this path exists in the list already. If so, remove it
        for (Iterator<String> i = recents.iterator(); i.hasNext();) {
            if (i.next().equals(uri)) {
                i.remove();
            }
        }
        
        // add this element to the front of the list
        recents.add(0, uri);
        
        // keep the last 5
        while (recents.size() > 5) {
            recents.remove(recents.size() - 1);
        }
        
        // update the list
        updateRecentsMenu();
        
        // store
        StringBuilder rb = new StringBuilder();
        for (String val : recents) {
            rb.append(val).append(",");
        }
        
        Preferences prefs = Preferences.userNodeForPackage(getClass());
        prefs.put("recents", rb.toString());
    }
    
    
    private void reloadRecents() {
        // clear existing list
        recents.clear();
        
        // load from preference
        Preferences prefs = Preferences.userNodeForPackage(getClass());
        String rs = prefs.get("recents", null);
        if (rs != null) {
            String[] vals = rs.split(",");
            for (String val : vals) {
                if (val.trim().length() > 0) {
                    recents.add(val);
                }
            }
        }
        
        updateRecentsMenu();
    }
    
    private void updateRecentsMenu() {
        recentsMenu.removeAll();
        
        for (final String uri : recents) {
            Action a = new AbstractAction(uri) {
                public void actionPerformed(ActionEvent e) {
                    player.openMedia(uri);
                }
            };
            
            recentsMenu.add(a);
        }
    }
    
    /**
    * @param args the command line arguments
    */
    public static void main(String args[]) {
        //final String videoFile = "rtmp://demo.wonderbuilders.com/oflaDemo/live";
        //final String videoFile = "out.flv";
        //final String videoFile = "/Users/jkaplan/Desktop/content/movies/TrueGrit.mov";

        java.awt.EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new SwingVideoPlayer().setVisible(true);
            }
        });
        
        try {
            URL u = SwingVideoPlayer.class.getResource("/logging.properties");
            LOGGER.warning("Load logging config from " + u);
            if (u != null) {
                LogManager.getLogManager().readConfiguration(u.openStream());
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Error loading logs", ex);
        }
    }

    private static class TimedImage {
        private final Image image;
        private final long time;
        
        public TimedImage(Image image, long time) {
            this.image = image;
            this.time = time;
        }
        
        public Image getImage() {
            return image;
        }
        
        public long getTime() {
            return time;
        }
    }
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton backButton;
    private javax.swing.JMenu fileMenu;
    private javax.swing.JPanel filePanel;
    private javax.swing.JButton forwardButton;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JMenuBar menuBar;
    private javax.swing.JMenuItem openMenuItem;
    private javax.swing.JMenuItem openURLMenuItem;
    private javax.swing.JButton pauseButton;
    private javax.swing.JButton playButton;
    private javax.swing.JMenuItem quitMenuItem;
    private javax.swing.JMenu recentsMenu;
    private javax.swing.JLabel stateLabel;
    private javax.swing.JButton stopButton;
    private javax.swing.JLabel timeLabel;
    private javax.swing.JSlider timeSlider;
    private javax.swing.JFileChooser videoChooser;
    private javax.swing.JLabel videoLabel;
    // End of variables declaration//GEN-END:variables

}
