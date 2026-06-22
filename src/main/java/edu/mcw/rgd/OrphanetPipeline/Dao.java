package edu.mcw.rgd.OrphanetPipeline;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Thin wrapper around rgdcore's {@link OntologyXDAO}; ALL database access for the
 * pipeline goes through here.
 *
 * <p>Orphanet ids are stored in RDO as term synonyms of type 'xref' with
 * synonym_name 'ORDO:&lt;OrphaCode&gt;' and source 'ORDO'. The pipeline owns only
 * the synonyms tagged with that source and reconciles them (insert / touch /
 * delete); xrefs maintained by other sources (OBO, RGD, BULKLOAD, ...) are left
 * untouched.
 */
public class Dao {

    private final OntologyXDAO ontologyXdao = new OntologyXDAO();

    private String ontId = "RDO";
    private String xrefSource = "ORDO";
    private String xrefSynonymType = "xref";

    public String getConnectionInfo() {
        return ontologyXdao.getConnectionInfo();
    }

    /**
     * all active (non-obsolete) synonyms of the ontology, every synonym type.
     * Used to build the OMIM/MONDO match maps and to find the existing ORDO-sourced xrefs.
     */
    public List<TermSynonym> getActiveSynonyms() throws Exception {
        return ontologyXdao.getActiveSynonyms(ontId);
    }

    /** insert one ORDO xref (type 'xref', source 'ORDO') for the given term; returns the new synonym key. */
    public int insertXref(String termAcc, String synonymName, Date dt) throws Exception {
        TermSynonym syn = new TermSynonym();
        syn.setTermAcc(termAcc);
        syn.setName(synonymName);
        syn.setType(xrefSynonymType);
        syn.setSource(xrefSource);
        syn.setCreatedDate(dt);
        syn.setLastModifiedDate(dt);
        return ontologyXdao.insertTermSynonym(syn);
    }

    /** refresh last-modified-date of up-to-date xrefs (so they are not seen as stale). */
    public int touchXrefs(Collection<TermSynonym> synonyms) throws Exception {
        if (synonyms.isEmpty()) {
            return 0;
        }
        return ontologyXdao.updateTermSynonymLastModifiedDate(synonyms);
    }

    /** delete stale ORDO xrefs (those no longer asserted by Orphanet). */
    public int deleteXrefs(Collection<TermSynonym> synonyms) throws Exception {
        if (synonyms.isEmpty()) {
            return 0;
        }
        return ontologyXdao.deleteTermSynonyms(synonyms);
    }

    public String getOntId() {
        return ontId;
    }

    public void setOntId(String ontId) {
        this.ontId = ontId;
    }

    public String getXrefSource() {
        return xrefSource;
    }

    public void setXrefSource(String xrefSource) {
        this.xrefSource = xrefSource;
    }

    public String getXrefSynonymType() {
        return xrefSynonymType;
    }

    public void setXrefSynonymType(String xrefSynonymType) {
        this.xrefSynonymType = xrefSynonymType;
    }
}
