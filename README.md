# BlockBuddy JUnit tests

JUnit 5 tests for the BlockBuddy API (Java 17+, Maven).

| File | What it tests |
|---|---|
| `src/test/java/bb/Api.java` | Helper: makes test users, signs them in, calls the API |
| `src/test/java/bb/M1HandleTest.java` | J-01 to J-06: college email flag and user handle rules |
| `src/test/java/bb/M3AcceptTest.java` | J-07 to J-12: accept, handed over, delivered |
| `seed_10k_test_requests.sql` | 10,000 test requests for JMeter (test database only) |

## Important: never run against the live app

These tests create and delete users. They must run against a **test** Supabase project.
`Api.java` stops at once if it is pointed at the live project or at blockbuddy-app.vercel.app.

## How to run

1. Get from the app owner: a running copy of the app connected to a **test** Supabase project,
   plus that project's URL, anon key and service role key.
   (In the test project, Auth > Providers > Email must allow email + password sign-in.)
2. Set these environment variables:

   ```bash
   BB_BASE_URL=http://localhost:3000              # the test copy of the app
   SUPABASE_URL=https://<test-ref>.supabase.co
   SUPABASE_ANON_KEY=...
   SUPABASE_SERVICE_ROLE_KEY=...
   ```

   Windows PowerShell: `$env:BB_BASE_URL="http://localhost:3000"` (same for the others).
3. Run:

   ```bash
   mvn test
   ```

   One class only: `mvn test -Dtest=M1HandleTest`

Every test user is deleted at the end of the test.

## How the app really behaves (read before writing more tests)

- **Sign-in is a cookie**, `sb-<project-ref>-auth-token`. The app does not read an
  `Authorization: Bearer` header. `Api.java` builds this cookie for you.
- **Non-VIT emails are not blocked.** Anyone can sign up. The server only sets
  `verified_vit = true` when the email ends with `@vitstudent.ac.in`. J-01 to J-03 check that flag.
- **Handle rule:** `^[a-z0-9_]{3,15}$`. Uppercase is rejected (400 `bad_request`).
  A taken handle gives 409 `handle_taken`.
- **Status flow:** `open -> accepted -> done`.
  - Accept: a buddy in the request's audience. Two at once: one gets 200, the other 409 `already_taken`.
  - Handed over: only the buddy who accepted (others get 403 `not_helper`). Status stays `accepted`.
  - Delivered: only the **requester** ("Got it"). Anyone else, or an open request: 409 `not_deliverable`.
  - A done request cannot be accepted again (409 `already_taken`) and its detail returns 404.
- Errors always look like `{"error":"<code>"}`.
