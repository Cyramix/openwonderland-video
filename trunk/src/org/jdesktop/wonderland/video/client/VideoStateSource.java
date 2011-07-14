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

/**
 * Source of video state and frame information
 * @author Jonathan Kaplan <jonathankap@gmail.com>
 */
public interface VideoStateSource extends VideoPlayer {

    /**
     * Add a listener for state changes
     */
    public void addStateListener(VideoStateListener listener);

    /**
     * Remove a listener for state changes
     */
    public void removeStateListener(VideoStateListener listener);

    /**
     * Add a listener that will be notified when new frames are received
     */
    public void addFrameListener(FrameListener listener);

    /**
     * Remove a listener for new frames
     */
    public void removeFrameListener(FrameListener listener);
}
