package edu.mcw.rgd.OrphanetPipeline;

import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.FileDownloader2;
import edu.mcw.rgd.process.MemoryMonitor;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Orphanet pipeline - loads Orphanet (ORDO) ids as cross-references into the
 * RGD Disease Ontology (RDO).
 *
 * <p>For every Orphanet disorder we find the single matching RDO term and attach
 * an 'ORDO:&lt;OrphaCode&gt;' xref (synonym type 'xref', source 'ORDO'):
 * <ol>
 *   <li>TIER1 - match by the disorder's OMIM reference(s) against RDO 'MIM:' synonyms</li>
 *   <li>TIER2 - if TIER1 gives no match or more than one, match by MONDO reference(s)</li>
 *   <li>otherwise the disorder is reported as NO_MATCH or MULTI_MATCH</li>
 * </ol>
 * Only the ORDO xrefs owned by this pipeline (source 'ORDO') are reconciled
 * (inserted / kept up-to-date / deleted).
 */
public class Manager {

    private String version;
    private FileDownloader2 downloader;
    private Product1Parser parser;
    private Dao dao;
    private boolean useExactMappingsOnly = true;
    private boolean dryRun = false;

    Logger log = LogManager.getLogger("summary");
    Logger insertedLog = LogManager.getLogger("inserted");
    Logger deletedLog = LogManager.getLogger("deleted");
    Logger noMatchLog = LogManager.getLogger("no_match");
    Logger multiMatchLog = LogManager.getLogger("multi_match");
    Logger obsoleteLog = LogManager.getLogger("obsolete");

    enum Status { TIER1, TIER2, TIER3, NO_MATCH, MULTI_MATCH }

    public static void main(String[] args) throws Exception {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        Manager manager = (Manager) bf.getBean("main");

        for (String arg : args) {
            if (arg.equals("-dryRun") || arg.equals("-dry_run")) {
                manager.dryRun = true;
            }
        }

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
        log.info(dao.getConnectionInfo());
        if (dryRun) {
            log.info("*** DRY RUN - no database changes will be made ***");
        }

        String localFile = downloader.downloadNew();
        log.info("downloaded Orphanet product1 file: " + localFile);

        List<OrphanetDisorder> disorders = parser.parse(localFile);
        log.info("parsed Orphanet disorders: " + Utils.formatThousands(disorders.size()));

        reportCrossReferenceCounts(disorders);
        loadOrdoXrefs(disorders);
    }

    /** match each disorder to a single RDO term and reconcile the ORDO xrefs. */
    void loadOrdoXrefs(List<OrphanetDisorder> disorders) throws Exception {

        Date runDate = new Date();

        // build OMIM/MONDO match maps (synonym name -> set of term accs, ANY synonym type),
        // and the set of ORDO xrefs already owned by this pipeline (source 'ORDO')
        Map<String, Set<String>> mimMap = new HashMap<>();
        Map<String, Set<String>> mondoMap = new HashMap<>();
        Map<String, Set<String>> meshMap = new HashMap<>();
        Map<String, TermSynonym> existingOrdo = new HashMap<>();   // ORDO: xrefs owned by this pipeline (source 'ORDO')
        Set<String> otherSourceOrdo = new HashSet<>();             // same ORDO: xref already present from another source (e.g. OBO)
        List<TermSynonym> allOrdoSyns = new ArrayList<>();          // every ORDO: xref (any source), for the obsolete report

        for (TermSynonym s : dao.getActiveSynonyms()) {
            String name = s.getName();
            if (name == null) {
                continue;
            }
            if (name.startsWith("MIM:")) {
                mimMap.computeIfAbsent(name, k -> new HashSet<>()).add(s.getTermAcc());
            } else if (name.startsWith("MONDO:")) {
                mondoMap.computeIfAbsent(normalizeMondo(name), k -> new HashSet<>()).add(s.getTermAcc());
            } else if (name.startsWith("MESH:")) {
                meshMap.computeIfAbsent(name, k -> new HashSet<>()).add(s.getTermAcc());
            } else if (name.startsWith("ORDO:")) {
                allOrdoSyns.add(s);
                String key = s.getTermAcc() + "|" + name;
                if (dao.getXrefSource().equals(s.getSource())) {
                    existingOrdo.put(key, s);
                } else {
                    otherSourceOrdo.add(key);
                }
            }
        }
        log.info("RDO match keys: " + Utils.formatThousands(mimMap.size()) + " OMIM, "
                + Utils.formatThousands(mondoMap.size()) + " MONDO, "
                + Utils.formatThousands(meshMap.size()) + " MESH; existing ORDO xrefs: "
                + Utils.formatThousands(existingOrdo.size()) + " (source " + dao.getXrefSource() + "), "
                + Utils.formatThousands(otherSourceOrdo.size()) + " (other sources)");

        int tier1 = 0, tier2 = 0, tier3 = 0, noMatch = 0, multiMatch = 0, matchDiffSource = 0, obsolete = 0;
        Set<String> obsoleteOrphaCodes = new HashSet<>(); // codes of obsolete disorders (for the obsolete report)
        Set<String> desired = new HashSet<>();            // termAcc|name we want present
        List<String[]> toInsert = new ArrayList<>();      // {termAcc, name}
        List<TermSynonym> toTouch = new ArrayList<>();     // up-to-date synonyms to refresh

        for (OrphanetDisorder d : disorders) {
            if (d.getOrphaCode() == null || d.getOrphaCode().isEmpty()) {
                continue;
            }
            if (d.isObsolete()) {
                obsolete++;
                obsoleteOrphaCodes.add(d.getOrphaCode());
                continue;
            }
            Match m = match(d, mimMap, mondoMap, meshMap);
            if (m.status == Status.NO_MATCH) {
                noMatch++;
                noMatchLog.info("ORDO:" + d.getOrphaCode() + "\t" + d.getName() + "\t" + refsForLog(d));
                continue;
            }
            if (m.status == Status.MULTI_MATCH) {
                multiMatch++;
                multiMatchLog.info("ORDO:" + d.getOrphaCode() + "\t" + d.getName()
                        + "\tcandidates=" + m.candidates + "\t" + refsForLog(d));
                continue;
            }

            // matched a single RDO term
            String name = "ORDO:" + d.getOrphaCode();
            String key = m.termAcc + "|" + name;

            // if the term already carries this ORDO xref from another source (e.g. OBO), do not add our
            // own duplicate; just count it. Any pre-existing source 'ORDO' duplicate is left out of
            // 'desired' and so gets removed by the stale-delete sweep below.
            if (otherSourceOrdo.contains(key)) {
                matchDiffSource++;
                continue;
            }

            switch (m.status) {
                case TIER1 -> tier1++;
                case TIER2 -> tier2++;
                case TIER3 -> tier3++;
                default -> { }
            }
            if (!desired.add(key)) {
                continue; // same term/code already accounted for
            }
            TermSynonym existing = existingOrdo.get(key);
            if (existing != null) {
                toTouch.add(existing);
            } else {
                toInsert.add(new String[]{m.termAcc, name});
            }
        }

        // stale = ORDO xrefs (source 'ORDO') present in RDO but no longer asserted by Orphanet
        List<TermSynonym> toDelete = new ArrayList<>();
        for (Map.Entry<String, TermSynonym> e : existingOrdo.entrySet()) {
            if (!desired.contains(e.getKey())) {
                toDelete.add(e.getValue());
            }
        }

        // report any RDO terms (any source) that still carry an ORDO xref for a now-obsolete Orphanet id
        Map<String, String> termNames = dao.getTermNameMap();
        int obsoleteAssigned = 0;
        for (TermSynonym s : allOrdoSyns) {
            String code = s.getName().substring("ORDO:".length());
            if (obsoleteOrphaCodes.contains(code)) {
                obsoleteLog.info(s.getTermAcc() + "\t" + termNames.getOrDefault(s.getTermAcc(), "")
                        + "\t" + s.getName() + "\t" + s.getSource());
                obsoleteAssigned++;
            }
        }

        int inserted = applyChanges(toInsert, toTouch, toDelete, runDate);

        log.info("");
        log.info(String.format("%-18s: %s", "MATCH_TIER1_OMIM", Utils.formatThousands(tier1)));
        log.info(String.format("%-18s: %s", "MATCH_TIER2_MONDO", Utils.formatThousands(tier2)));
        log.info(String.format("%-18s: %s", "MATCH_TIER3_MESH", Utils.formatThousands(tier3)));
        log.info(String.format("%-18s: %s", "MATCH_DIFF_SOURCE", Utils.formatThousands(matchDiffSource)));
        log.info(String.format("%-18s: %s", "NO_MATCH", Utils.formatThousands(noMatch)));
        log.info(String.format("%-18s: %s", "MULTI_MATCH", Utils.formatThousands(multiMatch)));
        log.info(String.format("%-18s: %s", "OBSOLETE_SKIPPED", Utils.formatThousands(obsolete)));
        log.info(String.format("%-18s: %s", "OBSOLETE_ASSIGNED", Utils.formatThousands(obsoleteAssigned)));
        log.info("");
        log.info(String.format("%-18s: %s", "INSERTED", Utils.formatThousands(inserted)));
        log.info(String.format("%-18s: %s", "UP_TO_DATE", Utils.formatThousands(toTouch.size())));
        log.info(String.format("%-18s: %s", "DELETED", Utils.formatThousands(toDelete.size())));
    }

    /** apply the reconciliation to the database (unless dry-run); returns number inserted. */
    private int applyChanges(List<String[]> toInsert, List<TermSynonym> toTouch,
                             List<TermSynonym> toDelete, Date runDate) throws Exception {
        int inserted = 0;
        if (dryRun) {
            return toInsert.size();
        }
        for (String[] ins : toInsert) {
            dao.insertXref(ins[0], ins[1], runDate);
            insertedLog.info(ins[1] + "\t" + ins[0]);
            inserted++;
        }
        dao.touchXrefs(toTouch);
        for (TermSynonym s : toDelete) {
            deletedLog.info(s.getName() + "\t" + s.getTermAcc());
        }
        dao.deleteXrefs(toDelete);
        return inserted;
    }

    /** two-tier match: OMIM first, then MONDO. */
    Match match(OrphanetDisorder d, Map<String, Set<String>> mimMap, Map<String, Set<String>> mondoMap,
                Map<String, Set<String>> meshMap) {

        Set<String> omimNames = new HashSet<>();
        Set<String> mondoNames = new HashSet<>();
        Set<String> meshNames = new HashSet<>();
        for (Xref x : d.getXrefs()) {
            if (useExactMappingsOnly && !x.isExact()) {
                continue;
            }
            if ("OMIM".equals(x.getSource())) {
                omimNames.add("MIM:" + x.getReference());
            } else if ("MONDO".equals(x.getSource())) {
                mondoNames.add(normalizeMondo("MONDO:" + x.getReference()));
            } else if ("MeSH".equals(x.getSource())) {
                meshNames.add("MESH:" + x.getReference());
            }
        }

        // first tier with a single hit wins: TIER1 OMIM, TIER2 MONDO, TIER3 MeSH
        Set<String> t1 = lookup(omimNames, mimMap);
        if (t1.size() == 1) {
            return new Match(Status.TIER1, t1.iterator().next(), t1);
        }
        Set<String> t2 = lookup(mondoNames, mondoMap);
        if (t2.size() == 1) {
            return new Match(Status.TIER2, t2.iterator().next(), t2);
        }
        Set<String> t3 = lookup(meshNames, meshMap);
        if (t3.size() == 1) {
            return new Match(Status.TIER3, t3.iterator().next(), t3);
        }
        // no single hit anywhere: ambiguous if any tier had >1 candidate, else no match
        if (t1.size() > 1) {
            return new Match(Status.MULTI_MATCH, null, t1);
        }
        if (t2.size() > 1) {
            return new Match(Status.MULTI_MATCH, null, t2);
        }
        if (t3.size() > 1) {
            return new Match(Status.MULTI_MATCH, null, t3);
        }
        return new Match(Status.NO_MATCH, null, t1);
    }

    private static Set<String> lookup(Set<String> names, Map<String, Set<String>> map) {
        Set<String> terms = new HashSet<>();
        for (String n : names) {
            Set<String> accs = map.get(n);
            if (accs != null) {
                terms.addAll(accs);
            }
        }
        return terms;
    }

    /**
     * normalize a MONDO accession to its canonical 7-digit zero-padded form so that padded and
     * unpadded variants compare equal, e.g. "MONDO:43361" -&gt; "MONDO:0043361".
     */
    static String normalizeMondo(String name) {
        int c = name.indexOf(':');
        if (c < 0) {
            return name;
        }
        String num = name.substring(c + 1);
        if (num.isEmpty() || !num.chars().allMatch(Character::isDigit)) {
            return name;
        }
        String stripped = num.replaceFirst("^0+", "");
        if (stripped.isEmpty()) {
            stripped = "0";
        }
        if (stripped.length() < 7) {
            stripped = "0".repeat(7 - stripped.length()) + stripped;
        }
        return "MONDO:" + stripped;
    }

    /** the OMIM/MONDO references of a disorder, for the curation logs. */
    private String refsForLog(OrphanetDisorder d) {
        StringBuilder sb = new StringBuilder();
        for (Xref x : d.getXrefs()) {
            if ("OMIM".equals(x.getSource()) || "MONDO".equals(x.getSource())) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(x.getSource()).append(':').append(x.getReference());
                if (!x.isExact()) {
                    sb.append("(").append(x.getRelation()).append(")");
                }
            }
        }
        return sb.toString();
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
        log.info(String.format("%-12s %12s %12s", "SOURCE", "ALL", "EXACT(E)"));
        for (String src : all.keySet()) {
            log.info(String.format("%-12s %12s %12s", src,
                    Utils.formatThousands(all.get(src)),
                    Utils.formatThousands(exact.getOrDefault(src, 0))));
        }
    }

    /** result of matching a disorder to RDO. */
    static final class Match {
        final Status status;
        final String termAcc;      // the single matched term (null unless TIER1/TIER2)
        final Set<String> candidates;

        Match(Status status, String termAcc, Set<String> candidates) {
            this.status = status;
            this.termAcc = termAcc;
            this.candidates = candidates;
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

    public Dao getDao() {
        return dao;
    }

    public void setDao(Dao dao) {
        this.dao = dao;
    }

    public boolean getUseExactMappingsOnly() {
        return useExactMappingsOnly;
    }

    public void setUseExactMappingsOnly(boolean useExactMappingsOnly) {
        this.useExactMappingsOnly = useExactMappingsOnly;
    }

    public boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
}
