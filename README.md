# orphanet-pipeline

Loads Orphanet (ORDO) ids as cross-references into the **RGD Disease Ontology (RDO)**.

## Source data

[Orphadata "product1"](https://sciences.orphadata.com/alignments/) — the authoritative
Orphanet rare-disease alignment file (CC BY 4.0, refreshed twice a year).

- File: `https://www.orphadata.com/data/xml/en_product1.xml` (English, XML)
- Each `<Disorder>` has an `<OrphaCode>` plus a list of `<ExternalReference>` entries
  mapping it to **OMIM, MONDO, MeSH, UMLS, MedDRA, GARD, ICD-10 and ICD-11**.
- Every reference carries a mapping-quality relation:
  - `E` — exact (the two concepts are equivalent) — the ones we load
  - `NTBT` / `BTNT` — ORPHAcode is narrower / broader than the target code
  - `ND`, `W`, ... — other relations
- Orphanet ids are stored in RDO under the `ORDO:` prefix (RGD convention; matches the
  `Orphanet:` -> `ORDO:` rewrite in `ontology-load-pipeline`).

## How it joins to RDO

RDO terms already carry cross-references that act as join keys, notably
`MONDO:` (~6.3k terms) and `MIM:`/OMIM (~7.3k terms). The load matches an Orphanet
disorder's exact OMIM / MONDO (and optionally MeSH / GARD) reference to the RDO term
that already carries that same xref, then adds `ORDO:<OrphaCode>` to it.

> RDO already contains ~2.5k `ORDO:` xrefs inherited from the Human Disease Ontology;
> this pipeline expands and refreshes that coverage from the authoritative source.

## Current status

First slice only: **download + parse + report counts** (no database writes yet).
It downloads product1, parses it with a streaming StAX parser, and logs the number of
cross-references per target vocabulary, all relations vs. exact `E` mappings.

## Build & run

```bash
JAVA_HOME=/c/app/java/jdk-17.0.8 ./gradlew clean createDistro
# deployed copy is driven by src/main/dist/run.sh
```

Configuration (source url, local file) lives in
`src/main/dist/properties/AppConfigure.xml`; logging in
`src/main/dist/properties/log4j2.xml`.
