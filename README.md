# orphanet-pipeline

Loads Orphanet (ORDO) ids as cross-references into the **RGD Disease Ontology (RDO)**.

## Source data

[Orphadata "product1"](https://sciences.orphadata.com/alignments/) — the authoritative
Orphanet rare-disease alignment file (CC BY 4.0, refreshed twice a year).

- File: `https://www.orphadata.com/data/xml/en_product1.xml` (English, XML), downloaded
  gzipped with a date-stamped name via `FileDownloader2`.
- Each `<Disorder>` has an `<OrphaCode>` plus `<ExternalReference>` entries to
  **OMIM, MONDO, MeSH, UMLS, MedDRA, GARD, ICD-10 and ICD-11**, each with a mapping
  relation: `E` (exact), `NTBT`/`BTNT` (narrower/broader), etc.

## What it does

1. Download + StAX-parse product1.
2. Build match maps from the active RDO synonyms (`MIM:` and `MONDO:` names, **any**
   synonym type — RDO stores OMIM ids as `xref`/`alt_id`/`primary_id`).
3. Match each disorder to a **single** RDO term, using only **exact (`E`)** references:
   - **TIER1** — by the disorder's OMIM reference(s) → RDO `MIM:` synonyms
   - **TIER2** — if TIER1 gives no/ambiguous match, by MONDO reference(s) → RDO `MONDO:` synonyms
4. Attach `ORDO:<OrphaCode>` to the matched term as a synonym of type `xref`, source `ORDO`.
5. Reconcile (insert / up-to-date / delete) only the xrefs owned by this pipeline
   (source `ORDO`); xrefs maintained by other sources (OBO, RGD, BULKLOAD, ...) are left alone.

### Duplicate avoidance

If the matched term already carries the same `ORDO:<code>` xref from **another** source
(e.g. the DO/`OBO` import), the pipeline does **not** add a `source='ORDO'` duplicate — it
counts the disorder as `MATCH_DIFF_SOURCE`, and any pre-existing `source='ORDO'` duplicate
is deleted.

### Reported metrics

```
MATCH_TIER1_OMIM    matched via OMIM (xref kept)
MATCH_TIER2_MONDO   matched via MONDO (xref kept)
MATCH_DIFF_SOURCE   matched, but ORDO xref already present from another source (skipped)
NO_MATCH            no single RDO term found (logged to logs/no_match.log)
MULTI_MATCH         ambiguous - more than one candidate term (logged to logs/multi_match.log)
INSERTED            new ORDO xrefs added
UP_TO_DATE          existing ORDO xrefs kept (last-modified refreshed)
DELETED             stale/duplicate ORDO xrefs removed
```

> RDO uses the `ORDO:` prefix for Orphanet ids (RGD convention; matches the
> `Orphanet:` → `ORDO:` rewrite in `ontology-load-pipeline`).

## Configuration

`src/main/dist/properties/AppConfigure.xml`:
- `downloader` — product1 url + local file (`FileDownloader2`, `prependDateStamp`, `useCompression`)
- `dao` — `ontId` (RDO), `xrefSource` (ORDO)
- `main.useExactMappingsOnly` — restrict matching to exact (`E`) references (default true)

Pass `-dryRun` on the command line to compute and report all counts **without** writing
to the database.

## Build & run

```bash
JAVA_HOME=/c/app/java/jdk-17.0.8 ./gradlew clean createDistro
# deployed copy is driven by src/main/dist/run.sh
```

Logging is configured in `src/main/dist/properties/log4j2.xml`.
