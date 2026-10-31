# DEX fingerprint test fixtures

`fingerprint.dex` and `other.dex` were compiled from the adjacent Java sources with JDK 17.0.14 (`javac -source 8 -target 8 -g:none`) and Android SDK Build Tools 35.0.0 D8. D8 uses `--min-api 28 --no-desugaring` with `platforms/android-30/android.jar` as the library. `--no-desugaring` intentionally retains an `invoke-custom` call site and the MethodHandle method retains `invoke-polymorphic`.

The generated DEX files are static data read by unit tests; no fixture application is installed or executed.

Rebuild each source into separate class and DEX output directories under ignored `.tools/dex-fixture/`. For `FingerprintFixture`, pass both its own `.class` and the nested `$Response.class` to D8. Copy `classes.dex` to `fingerprint.dex`; compile `Other.java` separately and copy its `classes.dex` to `other.dex`.

Generate `members.json` with the independent existing declaration parser:

```python
import json, re, sys
from pathlib import Path
sys.path.insert(0, 'scripts')
from inspect_dex import inventory
data = Path('app/src/test/resources/dex/fingerprint.dex').read_bytes()
rows = list(inventory(data, re.compile('Lfixture/dex/FingerprintFixture;'), True))
Path('app/src/test/resources/dex/members.json').write_text(json.dumps(rows, indent=2) + '\n')
```

The tests use declaration offsets from this independent inventory when corrupting a chosen method. The nested interface sorts before the enclosing class in D8's `class_defs`; the bad-ULEB case modifies the enclosing class's `class_data`.

`arrayPayload` embeds values whose low code units look like invoke opcodes. `wideLiteral` does the same inside a five-unit constant instruction. Packed/sparse switches provide both switch payload formats. These prevent a reader that scans arbitrary 16-bit values from inventing calls.
