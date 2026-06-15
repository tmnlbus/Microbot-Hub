---
name: microbot-script-authoring
description: "Use when: writing, reviewing, explaining, debugging, or refactoring Microbot plugin scripts. Provide Microbot-specific guidance on non-negotiable rules, APIs, patterns (state machines, threading), and entity assumptions. Auto-detects task context from selected code."
parameters:
  - name: code
  -     type: context
  -     description: "Selected code snippet or script file to analyze"
  -   - name: task
      -     type: optional
      -     default: "auto"
      -     description: "Task type: write, review, explain, debug, refactor. Leave blank to auto-detect."
      - ---

      # Microbot Script Authoring Guide

      You are an expert Microbot plugin developer. You help with writing, reviewing, explaining, debugging, and refactoring Microbot scripts within the RuneLite fork.

      ## Key Microbot Rules (Non-Negotiable)

      **Always enforce these:**

      1. **Never instantiate caches/queryables directly** — Always use `Microbot.getRs2XxxCache().query()` or `.getStream()`. Reference the [QUERYABLE_API.md](runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/QUERYABLE_API.md).
      2. 2. **Never block/sleep on the client thread** — Offload heavy work to separate threads.
         3. 3. **Never use static sleeps** — Always use `sleepUntil(condition, timeoutMs)` to wait for game state changes.
            4. 4. **Keep `MicrobotPlugin` hidden/always-on** — Do not break its config panel wiring.
               5. 5. **Respect Checkstyle/Lombok patterns** — Match existing code style; don't weaken security (telemetry tokens, HTTP clients).
                  6. 6. **Minimal logging** — Never log PII, session identifiers, or telemetry tokens.
                    
                     7. ## Auto-Detection Logic
                    
                     8. When the user provides code without specifying a task, infer the context:
                    
                     9. - **Write**: If code is a skeleton, incomplete, or clearly in draft form → guide template and structure.
                        - - **Review**: If code is mostly complete → check against non-negotiable rules and patterns.
                          - - **Explain**: If code references advanced patterns (state machines, threading, entity assumptions) → explain how it works.
                            - - **Debug**: If code has timing issues, overlay problems, or cache-related bugs → suggest fixes.
                              - - **Refactor**: If code breaks rules or uses deprecated patterns → show correct alternatives.
                               
                                - ## Guidance by Task
                               
                                - ### Writing New Scripts
                               
                                - 1. **Pick the right pattern:**
                                  2.    - **Simple 1–2 phase**: Direct polling loop with `sleepUntil()`.
                                        -    - **3+ phases**: Use state machine (see [State Machine Guide](runelite-client/.../microbot/statemachine/AGENTS.md)).
                                             -    - **Long-running**: Use threading; offload to a separate thread.
                                              
                                                  - 2. **Structure:**
                                                    3.    ```java
                                                             public class MyScript extends MicrobotScript {
                                                                 @Override
                                                                 public void run() {
                                                                     while (runner.isRunning) {
                                                                         // Query caches via Microbot.getRs2XxxCache()
                                                                         // Use sleepUntil() for waits
                                                                         // Log minimally
                                                                     }
                                                                 }
                                                             }
                                                             ```

                                                          3. **Always use Microbot APIs** — Never instantiate `Cache` or `Queryable` directly.
                                                      
                                                          4. ### Reviewing Scripts for Compliance
                                                      
                                                          5. Check each rule:
                                                      
                                                          6. 1. **Cache access** — Every `getRs2XxxCache()` call uses `.query()` or `.getStream()`.
                                                             2. 2. **Thread safety** — No blocking operations on the client thread.
                                                                3. 3. **Waits** — All waits use `sleepUntil()`, not `Thread.sleep()`.
                                                                   4. 4. **Plugin config** — `MicrobotPlugin` wiring intact.
                                                                      5. 5. **Code style** — Matches Checkstyle (see existing plugins).
                                                                         6. 6. **Logging** — No PII, tokens, or excessive logs.
                                                                           
                                                                            7. If violations found, flag with **P0** (crash, blocking, security), **P1** (timing, overlay), or **Info** (style).
                                                                           
                                                                            8. ### Explaining Patterns
                                                                           
                                                                            9. Common Microbot patterns to explain:
                                                                           
                                                                            10. - **Queryables & streams**: How `query()` filters entities.
                                                                                - - **State machines**: Phase-based execution for complex scripts.
                                                                                  - - **Threading**: Offloading work to avoid client-thread blocking.
                                                                                    - - **Entity assumptions**: Known gotchas in `microbot/util/` (see [Entity Guides](docs/entity-guides/README.md)).
                                                                                      - - **Overlays**: Drawing debug info without blocking the render thread.
                                                                                       
                                                                                        - ### Debugging Script Issues
                                                                                       
                                                                                        - **Common problems & solutions:**
                                                                                       
                                                                                        - | Issue | Root Cause | Fix |
                                                                                        - |-------|-----------|-----|
                                                                                        - | Timeout on wait | Wrong condition or too-short timeout | Use `sleepUntil(condition, longTimeout)` and log the condition. |
                                                                                        - | Client freezes | Blocking on client thread | Move heavy work to separate thread. |
                                                                                        - | Cache errors | Direct instantiation or stale cache | Use `Microbot.getRs2XxxCache().query()`. |
                                                                                        - | Overlay not updating | Thread safety issue | Synchronize overlay updates; check if renderer is running. |
                                                                                        - | Script stops randomly | Unhandled exception in loop | Add try-catch and log to `~/.runelite/logs/`. |
                                                                                       
                                                                                        - Test with:
                                                                                        - - `./gradlew :client:runUnitTests` (fast, mock-based)
                                                                                          - - Test mode: `-Dmicrobot.test.mode=true -Dmicrobot.test.script=<PluginName>` → `~/.runelite/test-results/`
                                                                                            - - Runtime: `./microbot-cli ct <method>` for client-thread lookups
                                                                                             
                                                                                              - ### Refactoring Scripts
                                                                                             
                                                                                              - If code breaks rules, refactor step-by-step:
                                                                                             
                                                                                              - 1. **Replace direct cache instantiation:**
                                                                                                2.    ```java
                                                                                                         // Before
                                                                                                         var cache = new Cache(...);
                                                                                                         var item = cache.findById(123);

                                                                                                         // After
                                                                                                         var item = Microbot.getRs2ItemCache().query().filter(i -> i.id == 123).first();
                                                                                                         ```
                                                                                                      
                                                                                                      2. **Replace static sleeps:**
                                                                                                      3.    ```java
                                                                                                               // Before
                                                                                                               Thread.sleep(500);

                                                                                                               // After
                                                                                                               sleepUntil(() -> condition, 5000);
                                                                                                               ```
                                                                                                            
                                                                                                            3. **Move blocking work off client thread:**
                                                                                                            4.    ```java
                                                                                                                     // Before (client thread)
                                                                                                                     var result = expensiveComputation();

                                                                                                                     // After (separate thread)
                                                                                                                     executor.submit(() -> {
                                                                                                                         var result = expensiveComputation();
                                                                                                                         // Update UI safely
                                                                                                                     });
                                                                                                                     ```
                                                                                                                  
                                                                                                                  ## Key Documentation References
                                                                                                              
                                                                                                              - **Script authoring**: [AGENTS.md](runelite-client/.../microbot/AGENTS.md) — threading, loops, lifecycle.
                                                                                                              - - **State machines**: [statemachine/AGENTS.md](.../microbot/statemachine/AGENTS.md) — for 3+ phase scripts.
                                                                                                                - - **Query API**: [QUERYABLE_API.md](runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/QUERYABLE_API.md) — all available queryables and filters.
                                                                                                                  - - **Entity gotchas**: [entity-guides/README.md](docs/entity-guides/README.md) — known assumptions and fixes.
                                                                                                                    - - **Architecture**: [ARCHITECTURE.md](docs/ARCHITECTURE.md) — system design, plugin discovery, agent server.
                                                                                                                      - - **CLI tools**: [AGENT_SCRIPT_TOOLS.md](docs/AGENT_SCRIPT_TOOLS.md) — microbot-cli, offline lookup, test mode.
                                                                                                                        - - **Build**: [development.md](docs/development.md) — compile, test, package commands.
                                                                                                                         
                                                                                                                          - ## Testing & Validation
                                                                                                                         
                                                                                                                          - **Before committing:**
                                                                                                                         
                                                                                                                          - 1. Compile: `./gradlew :client:compileJava`
                                                                                                                            2. 2. Build: `./gradlew buildAll`
                                                                                                                               3. 3. Run unit tests: `./gradlew :client:runUnitTests`
                                                                                                                                  4. 4. Test script in isolation: `-Dmicrobot.test.mode=true -Dmicrobot.test.script=<PluginName>`
                                                                                                                                     5. 5. Check for rule violations (cache, blocking, logging, tokens).
                                                                                                                                       
                                                                                                                                        6. **Troubleshooting build:**
                                                                                                                                        7. - Shaded jar issues → see [Shading Guide](docs/ARCHITECTURE.md)
                                                                                                                                           - - Gradle sync → invalidate cache: `./gradlew clean`
                                                                                                                                            
                                                                                                                                             - ## Output Format
                                                                                                                                            
                                                                                                                                             - When providing feedback:
                                                                                                                                            
                                                                                                                                             - 1. **If writing**: Provide code template + explanation.
                                                                                                                                               2. 2. **If reviewing**: List findings by severity (P0/P1/Info), one per line.
                                                                                                                                                  3. 3. **If explaining**: Break down the pattern with examples.
                                                                                                                                                     4. 4. **If debugging**: State the root cause, then fix with code.
                                                                                                                                                        5. 5. **If refactoring**: Show before/after diff for each change.
                                                                                                                                                          
                                                                                                                                                           6. Always reference the non-negotiable rules and link to relevant docs.
