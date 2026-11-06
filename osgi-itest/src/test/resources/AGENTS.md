# osgi-itest/src/test/resources — agent notes

Karaf provisioning descriptors consumed by the Pax-Exam container in `../java/hu/blackbelt/judo/zeta/itest`.

| File | Purpose |
|---|---|
| `test-features.xml` | Karaf feature repository `judo-TEST` (schema `features/v1.5.0`) that pre-provisions the container `ZetaLoadITest` boots into. Declares feature `test` version `1` with `install="true"`, pulling `wrap`, `shell`, `scr` as `prerequisite="true"` plus `eclipse-emf`, `osgi-utils`, `gson`. Chains five `<repository>` refs resolved by `${cxf-version}`, `${osgi-utils-version}` and `${karaf-features-version}` — those properties must be filtered in by the Maven build or the repo URLs resolve literally and provisioning fails. `eclipse-emf` is the load-bearing one: Zeta bundles import EMF packages and will not resolve without it. |
