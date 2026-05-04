
=== Translation service ops measurements ===
Generated: 2026-05-03 20:18:06
Hardware: M-series, 24 GB unified

=== 0. Baseline (no models loaded) ===
ollama ps:
  NAME    ID    SIZE    PROCESSOR    CONTEXT    UNTIL 
memory: free_pg=5892 comp_pg=109481 wired_pg=289761 pressure=73%

=== A. Co-residency test ===
Loading gemma4:e4b...
  call latency=14220ms (includes cold load)
  response: Mana Sakura
  model load duration (from API): 7.3s
ollama ps:
  NAME          ID              SIZE     PROCESSOR    CONTEXT    UNTIL              
  gemma4:e4b    c6eb396dbd59    10 GB    100% CPU     4096       4 minutes from now    
memory: free_pg=4892 comp_pg=608747 wired_pg=280193 pressure=41%

Now also loading qwen2.5:14b (without unloading gemma4)...
  call latency=50627ms
  response: Sakura Mana
  model load duration: 3.9s
ollama ps (do BOTH appear?):
  NAME           ID              SIZE      PROCESSOR    CONTEXT    UNTIL              
  qwen2.5:14b    7cdf5a0187d5    8.9 GB    100% CPU     4096       4 minutes from now    
memory: free_pg=4019 comp_pg=219731 wired_pg=298374 pressure=65%

=== B. Swap cost (forced unload + reload) ===
Both stopped.
memory after stop: free_pg=406156 comp_pg=181267 wired_pg=295801 pressure=68%

Cycle 1: gemma4 → qwen2.5 → gemma4
  gemma4:e4b: wall=13243ms  load=s  eval=s
  qwen2.5:14b: wall=48528ms  load=s  eval=s
  gemma4:e4b: wall=11836ms  load=s  eval=s

Cycle 2: gemma4 → qwen2.5 → gemma4
  gemma4:e4b: wall=735ms  load=s  eval=s
  qwen2.5:14b: wall=37234ms  load=s  eval=s
  gemma4:e4b: wall=11819ms  load=s  eval=s

Cycle 3: gemma4 → qwen2.5 → gemma4
  gemma4:e4b: wall=755ms  load=s  eval=s
  qwen2.5:14b: wall=40197ms  load=s  eval=s
  gemma4:e4b: wall=13279ms  load=s  eval=s

=== C. In-model concurrency throughput ===
Note: depends on OLLAMA_NUM_PARALLEL daemon setting (current: ${OLLAMA_NUM_PARALLEL:-default}).
Test does not modify daemon config — just measures observed concurrent throughput.


Parallel=1 (sending 1 concurrent requests)
  total wall: 7005ms for 1 requests
  per-request wall (parallel, observed): 7005ms
    [0] 6940ms: Beautiful proprietress

Parallel=2 (sending 2 concurrent requests)
  total wall: 8678ms for 2 requests
  per-request wall (parallel, observed): 4339ms
    [0] 8602ms: Beautiful proprietress
    [1] 4673ms: Large-breasted young wife

Parallel=4 (sending 4 concurrent requests)
  total wall: 13996ms for 4 requests
  per-request wall (parallel, observed): 3499ms
    [0] 1298ms: Beautiful proprietress
    [1] 13917ms: Busty young wife
    [2] 9653ms: Creepy train / Molester train
    [3] 4842ms: Camming

=== Done ===
See /Users/pyoung/workspace/organizer3/reference/translation_poc/MEASURE_OPS.md for full output.
