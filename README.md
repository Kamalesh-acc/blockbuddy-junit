# BlockBuddy JUnit tests

[![JUnit tests](https://github.com/Kamalesh-acc/blockbuddy-junit/actions/workflows/junit.yml/badge.svg)](https://github.com/Kamalesh-acc/blockbuddy-junit/actions/workflows/junit.yml)

JUnit 5 tests for the BlockBuddy API (Java 17+, Maven). **No setup needed.**

## See the results

Open the **Actions** tab:
https://github.com/Kamalesh-acc/blockbuddy-junit/actions/workflows/junit.yml
Click the latest run. The summary lists every test with pass or fail.

## Run it yourself

You need Java 17+ and Maven.

```bash
git clone https://github.com/Kamalesh-acc/blockbuddy-junit.git
cd blockbuddy-junit
mvn test
```

That's all. No keys, no accounts. Results are in `target/surefire-reports/`.
Run one class only: `mvn test -Dtest=M3AcceptTest`

## Where the tests run

They run against a **test copy** of the app, https://blockbuddy-test.vercel.app, which has its
own separate test database. Real users are never touched. Every test makes its own
test accounts and deletes them at the end.

## Test cases

| ID | Test | Expected result |
|---|---|---|
| J-01 | VIT email `@vitstudent.ac.in` | 200, `verified_vit = true` |
| J-02 | Gmail address | 200, `verified_vit = false` |
| J-03 | Lookalike `@vitstudent.ac.in.com` | 200, `verified_vit = false` |
| J-04 | New valid handle | 200, handle saved |
| J-05 | Duplicate handle | 409 `handle_taken` |
| J-06 | Invalid handles (8 cases: too short, too long, uppercase, space, dash, dot, accent, empty) | 400 `bad_request` |
| J-07 | Buddy accepts an open request | 200, status `accepted` |
| J-08 | Requester accepts own request | 400 `own_request` |
| J-09 | Two buddies accept at the same moment (5 rounds) | exactly one 200, one 409 `already_taken` |
| J-10 | Who can finish: other buddy pings "handed over" / buddies mark delivered / requester marks delivered | 403 `not_helper` / 409 `not_deliverable` / 200 |
| J-11 | Open request marked delivered without accept | 409 `not_deliverable` |
| J-12 | Accept a request that is already done | 409 `already_taken`, detail 404 |

## Files

| File | What it is |
|---|---|
| `src/test/java/bb/Api.java` | Helper: makes test users, signs them in, calls the API |
| `src/test/java/bb/M1HandleTest.java` | J-01 to J-06 (sign-up and handle) |
| `src/test/java/bb/M3AcceptTest.java` | J-07 to J-12 (accept, deliver, thanks) |
| `seed_10k_test_requests.sql` | 10,000 test requests for JMeter (test database only) |

## How the app really behaves

- **Sign-in is a cookie**, `sb-<project-ref>-auth-token`. The app does not read an
  `Authorization: Bearer` header. `Api.java` builds this cookie for you.
- **Non-VIT emails are not blocked.** Anyone can sign up. The server only sets
  `verified_vit = true` when the email ends with `@vitstudent.ac.in`.
- **Handle rule:** `^[a-z0-9_]{3,15}$` (3 to 15 chars, lowercase letters, numbers, `_`).
  Uppercase is rejected, not changed to lowercase.
- **Status flow:** `open -> accepted -> done`.
  - Accept: one guarded database UPDATE (`where status = 'open'`), so only one buddy can win.
  - Handed over: only the buddy who accepted. Status stays `accepted`.
  - Delivered: only the **requester** ("Got it"). This moves it to `done`.
- Errors always look like `{"error":"<code>"}`.
- The test copy has one extra helper, `/api/test-support/user`, that creates and removes
  test accounts. It is turned off in the live app.
