Put your own workflow templates here (`*.yml`). They are loaded by the orchestrator at start-up
(mounted at `/workflows`). A file whose `id` matches a built-in template replaces it.
See `services/orchestrator-service/src/main/resources/workflows/` for the built-in ones.
