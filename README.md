# Resilience Demo: Circuit Breaker, Retry, Rate Limiting + k6

A small Java 21 / Spring Boot 3.5 project with two microservices. It shows how **Resilience4j** Circuit Breaker, Retry and Rate Limiter behave under load, and uses **Grafana k6** to compare the same service **with** and **without** them.

---

## 1. What this project demonstrates

| Pattern | What it does | Where in the code |
|---|---|---|
| **Circuit Breaker** | Stops calling a failing dependency for a while (fail fast) | `@CircuitBreaker` in [ProtectedInventoryClient.java](order-service/src/main/java/com/example/order/ProtectedInventoryClient.java) |
| **Retry** | Tries a failed call again (max 3 attempts, 500 ms apart) | `@Retry` in the same file |
| **Rate Limiter** | Lets in ~10 requests per second and rejects the rest with HTTP 429 | `@RateLimiter` in the same file |
| **Load testing** | Sends controlled local traffic and measures the effect | [k6/](k6/) |

All resilience settings are in [order-service/src/main/resources/application.yml](order-service/src/main/resources/application.yml).

## 2. Architecture: how the services communicate

```
 k6 / curl                 Order Service :8080                         Inventory Service :8081
 ─────────►  GET /api/orders/check?mode=X  ──RestClient──►  GET /api/inventory/check?mode=X
                  │                                              │
                  │  profile "protected":                        │  NORMAL  -> 200 immediately
                  │   RateLimiter -> Retry -> CircuitBreaker     │  SLOW    -> 200 after 2 s
                  │  profile "unprotected": plain call           │  FAILURE -> 500
                  ▼                                              │  RANDOM  -> 200 or 500
             JSON response                                       │  counts every call (/stats)
```

* Order Service forwards the optional `mode` query parameter, so **each request** picks the Inventory behaviour. You don't need to restart anything to change it. You can also set a default with `INVENTORY_MODE`.
* Inventory Service runs with **only 20 Tomcat threads** to act like a dependency with limited capacity.
* The RestClient uses the **same timeouts in both profiles**: 1 s to connect and 3 s to read.

```
order-service/src/main/java/com/example/order/
  OrderController.java             REST endpoint, same in both profiles
  InventoryClient.java             interface
  UnprotectedInventoryClient.java  @Profile("unprotected"): plain HTTP call
  ProtectedInventoryClient.java    @Profile("protected"): same call + 3 annotations + fallback
  ResilienceEventLogger.java       logs retries, circuit state changes, rate-limit rejections
  ErrorHandler.java                exceptions -> 429 / 500 / 503
  RestClientConfig.java            shared RestClient and timeouts
inventory-service/src/main/java/com/example/inventory/
  InventoryController.java         simulated behaviours + call counters
k6/                                normal-traffic.js, heavy-traffic.js, dependency-failure.js
```

### Order Service HTTP responses

| Status | Meaning |
|---|---|
| 200 | Inventory answered |
| 429 | Rejected by the rate limiter (protected only). Inventory was **not** called |
| 500 | Inventory returned 500 (in the protected profile, this happens after all 3 attempts fail) |
| 503 | Circuit breaker OPEN, so the fallback ran and Inventory was **not** called. Also returned when Inventory times out or can't be reached |

The fallback never pretends the request succeeded. It returns `"fallback": true` with **HTTP 503**.

## 3. How the Circuit Breaker works

```
          ≥50% of last 10 calls failed (min. 5 calls)
 CLOSED ───────────────────────────────────────────► OPEN  (all calls rejected instantly → fallback 503)
   ▲                                                   │
   │ 3 test calls OK                                   │ after 10 s
   └──────────────── HALF_OPEN ◄───────────────────────┘
                      │ test calls fail → back to OPEN
```

* **CLOSED**: calls go through normally, and every result is recorded.
* **OPEN**: Inventory is not called at all. Each request gets the fallback in about 1 ms, which gives the dependency time to recover.
* **HALF_OPEN**: 3 trial calls are let through. If they succeed the circuit goes back to CLOSED, and if they fail it goes back to OPEN.

**Why a Circuit Breaker does not prevent an initial overload:** it needs failures before it can act. At least 5 calls have to be recorded, so the first requests always reach the struggling service. A circuit breaker reacts to damage. It does not stop a traffic spike from arriving, which is what the rate limiter is for.

## 4. How Retry works

* A failed call (HTTP 5xx, timeout or connection error) is tried again, up to **3 attempts in total**, with **500 ms** between them.
* It does **not** retry rate-limit rejections (`RequestNotPermitted`) or circuit-open rejections (`CallNotPermittedException`).
* Retry sits *outside* the circuit breaker, so every attempt counts toward the failure rate, and retrying stops as soon as the circuit opens.

**Why Retry can increase load on a struggling service:** with 3 attempts, one failing user request turns into **3 calls** to the dependency. If the dependency is failing *because* it's overloaded, retries can triple the traffic hitting it, and the user also waits over 1 s for an error. That's why Retry is paired with a Circuit Breaker here, which cuts the retries off quickly. Only retry errors that are likely to be transient.

## 5. How Rate Limiting works

* 10 permits per second (`limit-for-period: 10`, `limit-refresh-period: 1s`).
* `timeout-duration: 0`: a request that can't get a permit is rejected **immediately** with HTTP 429. It doesn't queue.
* It runs **first** (outermost), so rejected requests never trigger a retry or reach Inventory.

**Why rate limiting can produce *more* errors while protecting the service:** under heavy load the protected service returns lots of 429s, while the unprotected one may return mostly 200s at first. But each 429 is a request that **did not** reach Inventory. The 429 count is the load that was deliberately kept away from the dependency. Rejecting cheaply and quickly is better than letting every request queue up, time out and take the dependency down for everyone. See the SLOW results in section 10.

### Annotation order (and why it matters)

Resilience4j aspects are ordered in `application.yml`. As with Spring `@Order`, the **lowest number is the outermost**:

```
rate-limiter-aspect-order: 1  →  RateLimiter( Retry( CircuitBreaker( HTTP call ) ) )
retry-aspect-order: 2
circuit-breaker-aspect-order: 3
```

### Spring proxies

The annotations only work because `OrderController` calls `ProtectedInventoryClient` **through the injected Spring proxy**, and because `spring-boot-starter-aop` is on the classpath. Calling an annotated method from inside the same class (`this.checkInventory()`) would skip the proxy, and none of the three patterns would run.

## 6. How to start the applications

Prerequisites: Java 21 and Maven, or Docker. Install k6 with `winget install k6`, `choco install k6` or `brew install k6`, or use the Docker option below.

### Option A: IntelliJ

1. Open the root folder. IntelliJ detects the root `pom.xml` and imports both modules.
2. Run `InventoryServiceApplication` (port 8081).
3. Run `OrderServiceApplication` (port 8080). It uses the `protected` profile by default.

### Option B: Maven command line

```bash
mvn clean package
java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar
java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=protected
```

### Option C: Docker Compose

```bash
docker compose up --build                                   # protected
ORDER_PROFILE=unprotected docker compose up --build         # unprotected (bash)
$env:ORDER_PROFILE="unprotected"; docker compose up --build # unprotected (PowerShell)
```

## 7. Switching between protected and unprotected

Both profiles expose the same endpoint and run the same business logic. The only difference is which `InventoryClient` bean Spring creates.

| How you run it | protected | unprotected |
|---|---|---|
| IntelliJ | Run Configuration → *Active profiles*: `protected` | `unprotected` |
| Command line | `--spring.profiles.active=protected` | `--spring.profiles.active=unprotected` |
| Environment variable | `SPRING_PROFILES_ACTIVE=protected` | `SPRING_PROFILES_ACTIVE=unprotected` |
| Docker Compose | `ORDER_PROFILE=protected` (default) | `ORDER_PROFILE=unprotected` |

If you don't set a profile, `protected` is used (`spring.profiles.default`). The JSON response includes `"profile"` so you can always see which one is running.

> **Tip:** restart Order Service between test runs so the circuit breaker starts out CLOSED. Each k6 test resets the Inventory counters automatically.

## 8. Manual testing with curl

```bash
# Behaviours (via Order Service)
curl -i "http://localhost:8080/api/orders/check"                # NORMAL -> 200
curl -i "http://localhost:8080/api/orders/check?mode=SLOW"      # 200 after ~2 s
curl -i "http://localhost:8080/api/orders/check?mode=FAILURE"   # 500 (protected: after 3 attempts, ~1 s)
curl -i "http://localhost:8080/api/orders/check?mode=RANDOM"    # 200 or 500

# Inventory directly
curl -i "http://localhost:8081/api/inventory/check?mode=FAILURE"
curl    "http://localhost:8081/api/inventory/stats"             # how many calls reached Inventory
curl -X POST "http://localhost:8081/api/inventory/stats/reset"

# Open the circuit: run this 2-3 times, then call NORMAL. You get 503 + fallback for 10 s
curl -i "http://localhost:8080/api/orders/check?mode=FAILURE"
curl -i "http://localhost:8080/api/orders/check"

# Trigger the rate limiter: 20 parallel requests, some return 429 (bash)
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/orders/check & done; wait

# Resilience4j state (protected profile)
curl http://localhost:8080/actuator/circuitbreakers        # state: CLOSED / OPEN / HALF_OPEN
curl http://localhost:8080/actuator/circuitbreakerevents
curl http://localhost:8080/actuator/retryevents
curl http://localhost:8080/actuator/ratelimiters
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls
```

## 9. Running the k6 tests

All tests are **bounded**: they have a fixed duration and a maximum number of virtual users (VUs). They target **localhost only**. [k6/lib/common.js](k6/lib/common.js) refuses to run against any other host. Each test resets the Inventory counters first and prints how many calls reached Inventory at the end.

| Test | Load | Command |
|---|---|---|
| 1. Normal traffic | 5 VUs, 15 s, ~5 req/s | `k6 run k6/normal-traffic.js` |
| 2. Heavy traffic | ramps 5 → 50 VUs over 30 s | `k6 run k6/heavy-traffic.js` and `k6 run -e MODE=SLOW k6/heavy-traffic.js` |
| 3. Dependency failure | constant 8 req/s for 40 s, Inventory returns 500 | `k6 run k6/dependency-failure.js` |

Run each test once with **unprotected** and once with **protected**, restarting Order Service in between. The traffic, failure mode and timeouts are identical between the two runs.

**Without installing k6 (Docker):**

```bash
# services running in Docker Compose:
docker compose run --rm k6 run /scripts/heavy-traffic.js

# services running in IntelliJ on your machine:
docker run --rm -v "%cd%/k6:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e INVENTORY_URL=http://host.docker.internal:8081 grafana/k6:0.54.0 run /scripts/heavy-traffic.js
```

(In PowerShell use `${PWD}/k6` instead of `%cd%/k6`.)

### What output to expect

Each test ends with the normal k6 summary, followed by a results block like this:

```
=== Calls that reached inventory-service: {"totalCalls":305,"successCalls":305,"failedCalls":0} ===

================ TEST 2 HEAVY TRAFFIC (mode=NORMAL) : RESULTS ================
Requests per second   : 166.1
Total requests        : 5018
Successful (200)      : 305
Failed (non-200)      : 4713
Average response time : 2.5 ms
p95 response time     : 7.3 ms
HTTP 429 (rate limit) : 4713
HTTP 500 (dependency) : 0
HTTP 503 (cb open)    : 0
```

**Order Service logs (protected)** show the patterns at work:

```
WARN  ResilienceEventLogger : >>> RATE LIMITER rejected a request
WARN  ResilienceEventLogger : >>> RETRY attempt #1 after error: InternalServerError (waiting 500 ms)
WARN  ResilienceEventLogger : >>> RETRY attempt #2 after error: InternalServerError (waiting 500 ms)
ERROR ResilienceEventLogger : >>> RETRY gave up after 3 attempts
WARN  ResilienceEventLogger : >>> CIRCUIT BREAKER: State transition from CLOSED to OPEN
WARN  ProtectedInventoryClient : FALLBACK: circuit breaker is OPEN - inventory-service NOT called
WARN  ResilienceEventLogger : >>> CIRCUIT BREAKER: State transition from OPEN to HALF_OPEN
```

## 10. Results: protected vs unprotected

Measured on a developer laptop with k6 0.54. Your numbers will be different, but the pattern should be the same.

| Scenario | Profile | Total req | 200 OK | 429 | 500 | 503 | Avg | p95 | **Calls reaching Inventory** |
|---|---|---|---|---|---|---|---|---|---|
| Test 2 heavy, NORMAL | unprotected | 4993 | 4993 | 0 | 0 | 0 | 3.5 ms | 6.6 ms | **4993** |
| Test 2 heavy, NORMAL | protected | 5018 | 305 | 4713 | 0 | 0 | 2.5 ms | 7.3 ms | **305** |
| Test 2 heavy, **SLOW** | unprotected | 369 | 114 | 0 | 0 | 255 | 2768 ms | 3018 ms | **369** (pool saturated, timeouts) |
| Test 2 heavy, **SLOW** | protected | 2081 | **282** | 1799 | 0 | 0 | 312 ms | 2261 ms | **283** |
| Test 3 FAILURE | unprotected | 310 | 0 | 0 | 290 | 0 | 6.9 ms | 9.0 ms | **290** |
| Test 3 FAILURE | protected | 318 | 0 | 0 | 0 | 317 | 26 ms | 9.8 ms | **14** |

**How to read the table:**

* **Heavy, NORMAL:** Inventory is healthy, so the unprotected run looks "better" with 100% 200s. The protected run caps Inventory at about 10 req/s, a **94% reduction** in dependency load. This is the trade-off: the rate limiter protects capacity, so it only pays off once the dependency can't keep up.
* **Heavy, SLOW:** here the dependency **can't** keep up. Without protection, all 50 users pile onto Inventory's 20 threads, requests queue past the 3 s read timeout, and **69% fail** with an average wait of 2.8 s. With protection, excess requests get a 429 within milliseconds, Inventory gets work it can actually handle, and **more requests succeed** (282 vs 114), even though the failure count looks higher.
* **FAILURE:** without protection, every request hammers the broken dependency (290 calls). With protection, the first couple of requests retry (3 attempts each), the circuit opens, and after that requests fail fast with a 503 fallback. Inventory only sees the half-open test calls every 10 s: **14 calls instead of 290**.

**Why intentionally rejected requests (429/503) are a good sign:** each one is a request that was answered *immediately*, without using a thread, a connection or Inventory capacity. A fast, explicit "no" lets clients back off or retry later. A slow timeout ties up resources on both sides and can spread the failure upstream. The number to watch is **calls reaching Inventory** and **successful requests under overload**, not just the error rate.

## 11. Debugging in IntelliJ: where to put breakpoints

Start Order Service in **Debug** mode with the `protected` profile.

| What to observe | Breakpoint location |
|---|---|
| Incoming request | `OrderController.check()`, first line |
| REST call between services | `ProtectedInventoryClient.checkInventory()` on `restClient.get()` (or the same line in `UnprotectedInventoryClient`). On the Inventory side: `InventoryController.check()` |
| Retry attempts | the `restClient.get()` line above is hit once **per attempt**. Also `ResilienceEventLogger`, inside the `onRetry(...)` lambda |
| Circuit Breaker state changes | `ResilienceEventLogger`, inside the `onStateTransition(...)` lambda |
| Rate Limiter rejection | `ErrorHandler.rateLimited()` and the `onFailure(...)` lambda in `ResilienceEventLogger` |
| Fallback execution | `ProtectedInventoryClient.circuitOpenFallback()` |

Tips:

* Use a **non-suspending** breakpoint ("Suspend: off" + "Evaluate and log") for the rate limiter. Pausing the thread changes the timing you're trying to observe.
* The 10 s OPEN timer keeps running while you're paused at a breakpoint.

## Tech stack

Java 21 · Spring Boot 3.5.6 · Resilience4j 2.3.0 · Spring Web (RestClient) · Spring Boot Actuator · Maven · Docker Compose · Grafana k6
