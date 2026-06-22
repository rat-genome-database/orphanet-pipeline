package edu.mcw.rgd.OrphanetPipeline;

import edu.mcw.rgd.process.FileDownloader2;
import edu.mcw.rgd.process.MemoryMonitor;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Orphanet pipeline - loads Orphanet (ORDO) ids as cross-references into the
 * RGD Disease Ontology (RDO).
 *
 * <p>Current slice: download the Orphadata product1 file and report how many
 * cross-references it carries per target vocabulary, split by mapping quality
 * (all relations vs. exact 'E' mappings). No database writes yet.
 */
public class Manager {

    private String version;
    private FileDownloader2 downloader;
    private Product1Parser parser;

    Logger log = LogManager.getLogger("summary");

    public static void main(String[] args) throws Exception {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        Manager manager = (Manager) bf.getBean("main");

        long time0 = System.currentTimeMillis();

        MemoryMonitor memoryMonitor = new MemoryMonitor();
        memoryMonitor.start();

        try {
            manager.run();
        } catch (Exception e) {
            Utils.printStackTrace(e, manager.log);
            throw e;
        } finally {
            memoryMonitor.stop();
            manager.log.info(memoryMonitor.getSummary());
        }

        manager.log.info("=== Elapsed time " + Utils.formatElapsedTime(time0, System.currentTimeMillis()) + " ===\n");
    }

    public void run() throws Exception {

        log.info(getVersion());

        String localFile = downloader.downloadNew();
        log.info("downloaded Orphanet product1 file: " + localFile);

        List<OrphanetDisorder> disorders = parser.parse(localFile);
        log.info("parsed Orphanet disorders: " + Utils.formatThousands(disorders.size()));

        reportCrossReferenceCounts(disorders);
    }

    /** tally cross-references by source, all relations vs. exact ('E') mappings. */
    void reportCrossReferenceCounts(List<OrphanetDisorder> disorders) {

        Map<String, Integer> all = new TreeMap<>();
        Map<String, Integer> exact = new TreeMap<>();
        int totalRefs = 0;

        for (OrphanetDisorder d : disorders) {
            for (Xref x : d.getXrefs()) {
                totalRefs++;
                all.merge(x.getSource(), 1, Integer::sum);
                if (x.isExact()) {
                    exact.merge(x.getSource(), 1, Integer::sum);
                }
            }
        }

        log.info("total external references: " + Utils.formatThousands(totalRefs));
        log.info("");
        log.info(String.format("%-12s %12s %12s", "SOURCE", "ALL", "EXACT(E)"));
        log.info(String.format("%-12s %12s %12s", "------", "---", "--------"));
        for (String src : all.keySet()) {
            log.info(String.format("%-12s %12s %12s", src,
                    Utils.formatThousands(all.get(src)),
                    Utils.formatThousands(exact.getOrDefault(src, 0))));
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public FileDownloader2 getDownloader() {
        return downloader;
    }

    public void setDownloader(FileDownloader2 downloader) {
        this.downloader = downloader;
    }

    public Product1Parser getParser() {
        return parser;
    }

    public void setParser(Product1Parser parser) {
        this.parser = parser;
    }
}
