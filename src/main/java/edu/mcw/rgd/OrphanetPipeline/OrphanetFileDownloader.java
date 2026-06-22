package edu.mcw.rgd.OrphanetPipeline;

import edu.mcw.rgd.process.FileDownloader;

/**
 * Downloads the Orphadata "product1" cross-reference file.
 *
 * The external url, the local target file and the append-date-stamp flag are
 * injected from AppConfigure.xml (setters inherited from {@link FileDownloader}).
 */
public class OrphanetFileDownloader extends FileDownloader {

    /** download the configured product1 file and return the path of the local copy. */
    public String downloadFile() throws Exception {
        return downloadNew();
    }
}
