# Bounded DEX fingerprints

`DexMethodIndex` is an Android-free, execution-free reader for the exact class selected by its descriptor. It does not load APK classes, execute code, use JADX at runtime, extract files, or add native dependencies.

API:

```java
List<DexMethodIndex.Member> read(File apkOrDex, String ownerDescriptor) throws IOException;
List<DexMethodIndex.Member> read(byte[] dex, String ownerDescriptor) throws IOException;
```

Members preserve declared owner, method name, full descriptor and access flags. `calls` identifies normal and polymorphic method references using their declared method-table prototype. `fields` identifies actual instance/static field references and preserves the opcode; `isRead()` distinguishes field reads. Repeated identical references within a member are deduplicated in instruction order. Lists are immutable.

Dynamic `invoke-custom` uses a call-site index, not a method index. It is explicitly marked by `hasDynamicCalls`; the reader does not pretend to resolve its target. Calls include all structurally parsed instructions, including code in branches, not proven runtime reachability.

## Bounds and format handling

- APK entries are inventoried with a 200,000-entry bound; up to 128 canonical root `classes*.dex` entries are sorted numerically. Duplicate DEX ordinals are rejected. Scanning stops as soon as the selected class is defined, even if it declares no methods; unrelated later DEX files are not opened.
- A single DEX is at most 64 MiB. ZIP declared size is checked before inflation, exact length is checked while reading, and DEX header file size, header size and endian tag are checked.
- The reader supports ordinary DEX 035 and 037–040. Other versions, including the newer container layout of 041, fail explicitly.
- Table sizes and ranges are checked. Member/type/prototype tables are limited to 65,536 entries, the string table to one million entries. Only selected class method bodies are read; strings are decoded lazily with bounded MUTF-8 lengths and a bounded cache.
- Target-class parsing is limited to 65,536 fields/methods, eight million code units, 200,000 reference instructions and eight million identity characters. Offset arithmetic uses checked ranges and long arithmetic for variable-size tables and payloads.
- Normal instructions advance by their actual format width. Packed-switch, sparse-switch and array payloads are recognized by their complete pseudo-opcode, checked for alignment/size and skipped in full. Data inside constants and payloads is never scanned as invoke instructions. Unused, unknown and truncated opcodes fail explicitly.

The bytecode widths, invocation indexes and payload sizes were checked against [AOSP's Dalvik bytecode specification](https://source.android.com/docs/core/runtime/dalvik-bytecode). The field/prototype/class/code layout follows [AOSP's DEX format](https://source.android.com/docs/core/runtime/dex-format). This reader is deliberately not a complete DEX verifier: it does not authenticate APK signatures, validate DEX checksum/SHA-1, reconstruct all branch reachability, validate every unused table item, or decode exception-handler semantics.

## Two supplied Kuaishou APKs

`dex-fingerprints.json` contains APK SHA-256, complete selected-class methods/calls/fields, footer wrapper methods and the production `KuaishouSharedSymbols.resolve(...)` result. It is local static evidence, not REA Evidence or observed execution.

| Role | 14.9.20.50799 | 14.9.30.50933 |
| --- | --- | --- |
| Parser | `w1(Lw2b/q;)V` | `x1(Lm3b/q;)V` |
| First-page state | `i()Z` | `i()Z` |
| Loading | `k()Z` | `j()Z` |
| Latest result | `j0()Object` | `k0()Object` |
| Cancellation | `o()V` | `n()V` |
| Completion | `q(KMPPageList$c)V` | `o(KMPPageList$c)V` |
| Failure | `p(Throwable)V` | `m(Throwable)V` |
| Owner load action | `k()V` | `k()V` |
| Login lock | `h0()Z` | `h0()Z` |

These names are **outputs** of the selector; the shared selector does not select parser/loading/latest/cancellation/completion by these old/new names. Both parsers call the same four response-interface getters with the same full signatures, despite the response interface's different obfuscated name. The selector also recovers first-page and completion methods by calls and signatures rather than relying on `i` or `$c` as stable names.

Footer wrapper observations: old `pcl.f`, new `tdl.f`; add-footer entry old `Q1(View)V`, new `c2(View)V`, each calls `e2(View,LayoutParams)V`, which reads the footer group field `b`. Header entry `h2(View)V` calls `i2(View,LayoutParams)V`, which reads group `a`. A `(View)Z` signature and a `b` read alone are ambiguous: `E2` and `l2` both match those traits. `E2` additionally calls the wrapper's update method; `l2` only calls the group's membership query. These complete references are retained so the production footer selector can reject ambiguity instead of guessing.

## Verification

Eleven focused unit tests cover actual D8-generated interface/overloaded/polymorphic/custom invokes, field reads and writes, switch/array payloads, invoke-like wide constant data, numeric multidex order, raw DEX files, immutable output, malformed headers/tables/indexes/ULEB/code, reserved opcodes and oversized ZIP entries.

The production selector resolved the full pager profile on both supplied APKs using this reader. Desktop measurements are included only for reproducibility, not a phone startup-performance guarantee. No target APK code was run, and live LSPosed filtering/pagination/footers still need device validation.
