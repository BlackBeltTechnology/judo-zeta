# p2/src/assembly — agent notes

Assembly descriptor for the `p2` module, which republishes Zeta bundles as an Eclipse p2 update site.

| File | Purpose |
|---|---|
| `assembly.xml` | maven-assembly-plugin descriptor (schema `assembly/1.1.2`) that zips the Tycho-generated p2 repository into the distributable update site. Declares `<id>site</id>`, single `<format>zip</format>`, `includeBaseDirectory=false`, and one `<fileSet>` mapping `target/repository` → `/`. Assembly id `site` becomes the artifact classifier, so renaming it renames the published `*-site.zip`; `includeBaseDirectory=false` is required or `artifacts.jar`/`content.jar` land one level deep and p2 cannot read the site. Runs after the Tycho p2 publisher has populated `target/repository` — invoking assembly earlier produces an empty zip without failing. |
