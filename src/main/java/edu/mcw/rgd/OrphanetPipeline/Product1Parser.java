package edu.mcw.rgd.OrphanetPipeline;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Streaming (StAX) parser for the Orphadata "product1" cross-reference file
 * (e.g. en_product1.xml, optionally gzip-compressed). StAX keeps the memory
 * footprint flat regardless of file size (the English file is ~50 MB /
 * 11k+ disorders).
 *
 * <p>Relevant structure:
 * <pre>
 *   &lt;Disorder&gt;
 *     &lt;OrphaCode&gt;166024&lt;/OrphaCode&gt;
 *     &lt;Name lang="en"&gt;...&lt;/Name&gt;
 *     &lt;ExternalReferenceList&gt;
 *       &lt;ExternalReference&gt;
 *         &lt;Source&gt;MONDO&lt;/Source&gt;
 *         &lt;Reference&gt;0011778&lt;/Reference&gt;
 *         &lt;DisorderMappingRelation&gt;&lt;Name&gt;E (Exact mapping: ...)&lt;/Name&gt;&lt;/DisorderMappingRelation&gt;
 *       &lt;/ExternalReference&gt;
 *     &lt;/ExternalReferenceList&gt;
 *   &lt;/Disorder&gt;
 * </pre>
 * Note that &lt;Name&gt; appears in several contexts (the disorder itself,
 * DisorderType, DisorderGroup, DisorderMappingRelation), so the parser keys on
 * the parent element to disambiguate.
 */
public class Product1Parser {

    public List<OrphanetDisorder> parse(String xmlFile) throws Exception {

        List<OrphanetDisorder> disorders = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // harden against XXE - we never expect external entities or a DTD here
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        try (InputStream in = openStream(xmlFile)) {
            XMLStreamReader r = factory.createXMLStreamReader(in, "UTF-8");

            Deque<String> stack = new ArrayDeque<>();
            StringBuilder text = new StringBuilder();
            OrphanetDisorder cur = null;
            Xref xref = null;

            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT: {
                        String name = r.getLocalName();
                        if (name.equals("Disorder")) {
                            cur = new OrphanetDisorder();
                        } else if (name.equals("ExternalReference")) {
                            xref = new Xref();
                        }
                        stack.push(name);
                        text.setLength(0);
                        break;
                    }
                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                        text.append(r.getText());
                        break;
                    case XMLStreamConstants.END_ELEMENT: {
                        String name = stack.pop();
                        String parent = stack.peek();
                        String val = text.toString().trim();

                        if (name.equals("OrphaCode") && "Disorder".equals(parent)) {
                            if (cur != null) cur.setOrphaCode(val);
                        } else if (name.equals("Name") && "Disorder".equals(parent)) {
                            if (cur != null) {
                                cur.setName(val);
                                if (val.toUpperCase().startsWith("OBSOLETE")) {
                                    cur.setObsolete(true);
                                }
                            }
                        } else if (name.equals("Label") && "DisorderFlag".equals(parent)) {
                            if (cur != null && "Obsolete entity".equals(val)) {
                                cur.setObsolete(true);
                            }
                        } else if (name.equals("Source") && "ExternalReference".equals(parent)) {
                            if (xref != null) xref.setSource(val);
                        } else if (name.equals("Reference") && "ExternalReference".equals(parent)) {
                            if (xref != null) xref.setReference(val);
                        } else if (name.equals("Name") && "DisorderMappingRelation".equals(parent)) {
                            if (xref != null) xref.setRelation(relationCode(val));
                        } else if (name.equals("ExternalReference")) {
                            if (cur != null && xref != null) cur.getXrefs().add(xref);
                            xref = null;
                        } else if (name.equals("Disorder")) {
                            if (cur != null) disorders.add(cur);
                            cur = null;
                        }
                        text.setLength(0);
                        break;
                    }
                }
            }
            r.close();
        }

        return disorders;
    }

    /** open the product1 file, transparently decompressing when it is gzipped. */
    private static InputStream openStream(String file) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        return file.endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    /** "E (Exact mapping: the two concepts are equivalent)" -&gt; "E"; "NTBT (...)" -&gt; "NTBT". */
    static String relationCode(String relationName) {
        if (relationName == null || relationName.isEmpty()) {
            return "";
        }
        int sp = relationName.indexOf(' ');
        return sp > 0 ? relationName.substring(0, sp) : relationName;
    }
}
