package edu.mcw.rgd.OrphanetPipeline;

/**
 * One external cross-reference of an Orphanet disorder, as found in the
 * &lt;ExternalReference&gt; elements of Orphadata product1.
 */
public class Xref {

    private String source;     // target vocabulary, e.g. OMIM, MONDO, MeSH, UMLS, ICD-10, ICD-11, GARD, MedDRA
    private String reference;  // the code in that vocabulary (raw, no prefix), e.g. 607131, 0011778, Q77.3
    private String relation;   // mapping relation code: E (exact), NTBT, BTNT, ND, W, ... (null if not provided)

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }

    /** true when this is an exact ('E') Orphanet mapping (the two concepts are equivalent). */
    public boolean isExact() {
        return "E".equals(relation);
    }
}
