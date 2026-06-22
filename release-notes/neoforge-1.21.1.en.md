## NeoForge 1.21.1 build — same source, two loaders

BetterAutoSave now ships a NeoForge 1.21.1 build from the same 0.11.0 source. Features, configuration, commands and compatibility levels are identical to the Forge 1.20.1 build: the async-save algorithm core (scheduler, relay state machine, workers, diagnostics) is zero-Minecraft pure Java, source-merged and shared by both loaders, so the crown-jewel save relay protocol lives once and never forks per loader

### Requirements

- NeoForge 21.1 line, Java 21 or newer
- Server-side only, clients do not need to install it
- jar: `shinoyuki_betterautosave-neoforge-0.11.0.jar`

### Porting highlights

- Serialization and disk-IO glue verified point by point against the decompiled 1.21.1 sources: chunk-status getter rename, the registries argument on block-entity and SavedData saves, the Path overload for compressed NBT writes, the DataResult getter shape, the chunk-holder latest-chunk rename
- All 7 mixin injection points survive on 1.21.1; entity storage's region IO is pushed down into SimpleRegionStorage on 1.21.1, and the async entity pipeline is reused unchanged behind two accessor hops (EntityStorage -> SimpleRegionStorage -> worker)
- Async chunk saving now also writes NeoForge data attachments and the LevelChunk custom-light field, matching the vanilla on-disk layout — mods that use data attachments do not lose them during async saves

### Verification

- Real NeoForge 1.21.1 server: all mixins applied, periodic and operational `/save-all` saves with no main-thread stall, chunks and entities written asynchronously, workers drained cleanly on shutdown, zero load errors on restart
- 114 unit tests green (including the cases that need a Minecraft runtime bootstrap)

### Installation

Drop `shinoyuki_betterautosave-neoforge-0.11.0.jar` into the server's `mods/`; config path, commands and compatibility levels match the Forge build
