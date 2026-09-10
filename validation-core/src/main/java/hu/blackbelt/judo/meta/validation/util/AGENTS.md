# validation-core/src/main/java/hu/blackbelt/judo/meta/validation/util — agent notes

Migration ergonomics for rule bodies ported from `.evl`/EOL. Directory path is `meta/validation/util`, declared package is `hu.blackbelt.judo.zeta.validation.util` — layout does not mirror package.

| File | Purpose |
|---|---|
| `EolStyleCollections.java` | Gives ported EVL rule bodies the EOL collection vocabulary over plain Java Streams, so an `.evl` line survives migration near-verbatim (`c.select(x \| p)` → `select(c, x -> p)`). Exports static `select`, `reject`, `collect`, `forAll`, `exists`, `one`, `flatten`, `including`, `excluding`, `includingAll`, `excludingAll`, `first`, `asSet`, `isEmpty`, `notEmpty`. Every method is static and non-mutating — inputs are never modified, results are fresh `List`/`Set`. `one` means exactly-one match (`filter(p).count() == 1`), NOT any-match. `first` returns `null` on an empty collection rather than throwing or returning `Optional`. `excluding` compares by `Objects.equals`, `excludingAll` buckets `other` into a `HashSet` so element `equals`/`hashCode` govern removal — EMF proxies that do not override them exclude by identity only. `asSet` returns a `HashSet`, dropping both duplicates and encounter order. Import statically in rule classes. |
