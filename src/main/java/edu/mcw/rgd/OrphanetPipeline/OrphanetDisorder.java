package edu.mcw.rgd.OrphanetPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * One Orphanet disorder (a &lt;Disorder&gt; element of Orphadata product1):
 * its ORPHAcode, preferred name, and the list of external cross-references.
 */
public class OrphanetDisorder {

    private String orphaCode;  // numeric ORPHAcode, e.g. 166024 (stored in RDO as ORDO:166024)
    private String name;
    private boolean obsolete;  // true if Orphanet flags the disorder as obsolete/inactive
    private final List<Xref> xrefs = new ArrayList<>();

    public String getOrphaCode() {
        return orphaCode;
    }

    public void setOrphaCode(String orphaCode) {
        this.orphaCode = orphaCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isObsolete() {
        return obsolete;
    }

    public void setObsolete(boolean obsolete) {
        this.obsolete = obsolete;
    }

    public List<Xref> getXrefs() {
        return xrefs;
    }
}
