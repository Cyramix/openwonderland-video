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
package org.jdesktop.wonderland.client.utils;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load video libraries
 * @author Jonathan Kaplan <jonathankap@gmail.com>
 */
public class VideoLibraryLoader {
    private static final Logger LOGGER =
            Logger.getLogger(VideoLibraryLoader.class.getName());

    private static final String[] LINUX_LIBRARIES = new String[] {
        // libraries with no dependencies
        "mp3lame", "ogg", "opencore-amrnb", "opencore-amrwb",
        "speex", "speexdsp", "x264", "avutil",
        // libraries that depend on above
        "swscale", "theora", "theoradec", "theoraenc", "vorbis",
        // everything else (order is important)
        "vorbisfile", "vorbisenc", "avcodec", "avformat", "avdevice",
        "avfilter", "xuggle-ferry", "xuggle-xuggler", "xuggle-xuggler-io"
    };
    
    private static final String[] WINDOWS_LIBRARIES = new String[] {
        // libraries with no dependencies
        "avutil-50", 
        // libraries that depend on above
        "swscale-0", "avcodec-52",
        // everything else (order is important)
        "avformat-52", "avdevice-52",
        "xuggle-ferry", "xuggle-xuggler-io", "xuggle-xuggler"
    };

    private static final String[] MACOSX_LIBRARIES = new String[] {
        // mac handles dependencies itself
        "xuggle-xuggler"
    };

    /**
     * Load video libraries, and return whether load succeeded
     */
    public static boolean loadVideoLibraries() {
        String[] libraries = null;

        if (System.getProperty("os.name").startsWith("Linux")) {
            libraries = LINUX_LIBRARIES;
        } else if (System.getProperty("os.name").startsWith("Windows")) {
            libraries = WINDOWS_LIBRARIES;
        } else if (System.getProperty("os.name").startsWith("Mac OS X")) {
            libraries = MACOSX_LIBRARIES;
        }

        // if we don't have libraries to load, the platform is not supported
        if (libraries == null) {
            return false;
        }

        try {
            for (String library : libraries) {
                System.loadLibrary(library);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Error loading library", t);
            return false;
        }
    }
}
