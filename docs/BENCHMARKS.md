# KAGAYAKI Performance Benchmarks

> **Engine:** KAGAYAKI v1.0.0 (vJava 1.1)  
> **Environment:** Java 21.0.12.1 · Mac OS X (aarch64)

| Benchmark | Iterations | Total (ms) | Avg (us/op) | Throughput (ops/s) | Notes |
|---|---:|---:|---:|---:|---|
| VJavaLexer.tokenize | 1500 | 10.14 | 6.758 | 147966.1 | 15-line hybrid .vjava script |
| VJavaParser.parse | 1500 | 35.43 | 23.622 | 42334.3 | AST construction from tokens |
| ObjectMotionController.update | 5000 | 3.11 | 0.622 | 1608751.6 | WASD + gravity + tween tick |
| SceneGraph.traverseOrdered | 5000 | 15.21 | 3.043 | 328630.3 | 100-node layered traversal |
| StyledTextLayout.layout | 2000 | 39.15 | 19.574 | 51087.0 | Tag parse + word-wrap layout |

Generated deterministically via `de.swfdtf.tools.PerformanceBenchmarkRunner` (`#0947`).
