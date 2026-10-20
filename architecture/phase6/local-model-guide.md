# Running NEXUS with a local model (Ollama)

The default is no model: every AI feature uses its deterministic implementation and nothing leaves the process. A local model is an optional addition.

## Enable it

```
nexus.ai.ollama.enabled=true
nexus.ai.ollama.base-url=http://localhost:11434      # must be a loopback address
nexus.ai.ollama.model=llama3.2:3b                     # an installed, locally hosted model
nexus.ai.ollama.max-output-tokens=512
nexus.ai.ollama.request-timeout-ms=60000
nexus.ai.guard.timeout-ms=60000                       # the guard's own limit is at most 60 s
nexus.ai.send-device-evidence=true                    # the separate consent: without it no device data reaches the model
```

`nexus.ai.enabled=true` (the hosted OpenAI-compatible provider) and `nexus.ai.ollama.enabled=true` are alternatives; enabling both stops startup.

## What is enforced in code

* The base URL must be localhost, 127.0.0.0/8 or ::1, with no user information and no path. Anything else stops startup. There is no override: sending evidence to another machine needs a separate, explicit decision and a change in code.
* A model that Ollama forwards to a hosted service (its cloud models) is refused by name at startup and, before the first call, by the server's own description of the model.
* The model only receives text and returns text. Everything it returns is parsed as a strict JSON object of a fixed schema and validated by code; text a model writes is never shown to users as fact.
* If the server is down, slow or returns an error, the call ends with a fixed failure code and the deterministic path answers.

## Choose a model from facts

`OllamaProbe.inspect(url, model, timeout)` reports whether the server answers, whether the model is installed, its digest, size, parameter count, quantisation, the capabilities the server lists (for example `tools`) and whether it is hosted remotely. The real-model evaluation (`RealModelOllamaEvaluationTest`, enabled with `NEXUS_EVAL_OLLAMA=true`) refuses to run unless these checks pass, and records them with the result.

## What to expect

Measured on one machine (16 GB RAM, 4 GB GPU) with `llama3.2:3b`: a call takes about 3 seconds typically and up to about 23 seconds, and the model did not follow the assistant's routing contract reliably (see `evaluation/realmodel/README.md`). Treat the model as an optional aid whose output is always validated, not as the source of answers.

## Limits

NEXUS has no user authentication; a local model does not change that. Device evidence is sent only to the loopback server, but anything that runs on that machine can read the same data. Model files are the operator's responsibility.
