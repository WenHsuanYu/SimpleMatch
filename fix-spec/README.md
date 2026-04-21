# FIX Spec / DataDictionary

This repo uses FIX 4.4 dictionaries from this directory.

## Where is `FIX44.xml`?

The repository-local runtime dictionary is now:

- `fix-spec/FIX44.xml`

The default acceptor config points to it:

- `config/fix/acceptor.cfg` → `DataDictionary=fix-spec/FIX44.xml`

This path is intentionally independent of the old vendored C++ QuickFIX layout so the Java gateway can keep running even after the C++ service and vendored shared library assets are removed.

## Custom tags / counterparty dictionaries

If your counterparty requires custom tags or a modified dictionary, you have two common options:

1) Create a new dictionary file under `fix-spec/` (e.g. `fix-spec/FIX44-custom.xml`) and update `DataDictionary=...` in `config/fix/acceptor.cfg`.

2) Keep your custom dictionary outside the repo and set `DataDictionary` via an environment-specific config.

Keep the runtime dictionary path stable per environment to avoid validation mismatches.
