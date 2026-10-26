package bb;

import bb.Api.Res;
import bb.Api.TestUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 – Accept, Deliver and Thanks.
 *
 * Status flow in the real code: open -> accepted -> done.
 *   accept      POST /api/requests/{id}/accept       a buddy in the audience
 *   handed-over POST /api/requests/{id}/handed-over  the buddy who accepted (status stays accepted)
 *   delivered   POST /api/requests/{id}/delivered    the REQUESTER confirms "Got it" (accepted -> done)
 *
 * Users are made once for the class (keeps Supabase sign-in rate limits happy).
 * Each test posts its own fresh request.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M3AcceptTest {

    private TestUser requester;
    private TestUser buddy1;
    private TestUser buddy2;

    @BeforeAll
    void setUp() throws Exception {
        requester = Api.createReadyUser("vitstudent.ac.in");
        buddy1 = Api.createReadyUser("vitstudent.ac.in");
        buddy2 = Api.createReadyUser("vitstudent.ac.in");
        Api.makeBuddies(requester, buddy1);
        Api.makeBuddies(requester, buddy2);
    }

    @AfterAll
    void tearDown() {
        Api.deleteUser(requester);
        Api.deleteUser(buddy1);
        Api.deleteUser(buddy2);
    }

    private String newRequest() throws Exception {
        return Api.postRequest(requester, List.of(buddy1.userId, buddy2.userId));
    }

    @Test
    @DisplayName("J-07 A buddy can accept an open request")
    void j07_buddyAccepts() throws Exception {
        String id = newRequest();
        Res r = Api.accept(buddy1, id);
        assertEquals(200, r.status(), r.toString());
        assertTrue(r.json().get("ok").asBoolean());

        assertEquals("accepted", Api.detail(requester, id).json().get("status").asText());
        assertTrue(Api.detail(buddy1, id).json().get("iAmHelper").asBoolean());
    }

    @Test
    @DisplayName("J-08 Requester cannot accept their own request")
    void j08_cannotAcceptOwn() throws Exception {
        String id = newRequest();
        Res r = Api.accept(requester, id);
        assertEquals(400, r.status(), r.toString());
        assertEquals("own_request", r.error());
        assertEquals("open", Api.detail(requester, id).json().get("status").asText());
    }

    @Test
    @DisplayName("J-09 Two buddies accept at the same time: exactly one wins")
    void j09_onlyOneWinsTheRace() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String id = newRequest();
                CountDownLatch go = new CountDownLatch(1);
                CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> raceAccept(go, buddy1, id), pool);
                CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> raceAccept(go, buddy2, id), pool);
                go.countDown(); // release both at once

                Res ra = a.get();
                Res rb = b.get();
                long wins = List.of(ra, rb).stream().filter(x -> x.status() == 200).count();
                long losses = List.of(ra, rb).stream()
                        .filter(x -> x.status() == 409 && "already_taken".equals(x.error())).count();
                assertEquals(1, wins, "round " + round + ": " + ra + " / " + rb);
                assertEquals(1, losses, "round " + round + ": " + ra + " / " + rb);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Res raceAccept(CountDownLatch go, TestUser u, String id) {
        try {
            go.await();
            return Api.accept(u, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("J-10 Only the right person finishes: accepting buddy hands over, requester confirms")
    void j10_onlyRightPersonFinishes() throws Exception {
        String id = newRequest();
        assertEquals(200, Api.accept(buddy1, id).status());

        // The other buddy cannot ping "handed over".
        Res otherPing = Api.handedOver(buddy2, id);
        assertEquals(403, otherPing.status(), otherPing.toString());
        assertEquals("not_helper", otherPing.error());

        // The accepting buddy can.
        assertEquals(200, Api.handedOver(buddy1, id).status());

        // Buddies cannot mark it delivered, not even the one who accepted.
        Res byHelper = Api.delivered(buddy1, id);
        assertEquals(409, byHelper.status(), byHelper.toString());
        assertEquals("not_deliverable", byHelper.error());
        Res byOther = Api.delivered(buddy2, id);
        assertEquals(409, byOther.status(), byOther.toString());

        // Only the requester can.
        Res byRequester = Api.delivered(requester, id);
        assertEquals(200, byRequester.status(), byRequester.toString());
    }

    @Test
    @DisplayName("J-11 Open request cannot jump straight to delivered")
    void j11_cannotSkipAccept() throws Exception {
        String id = newRequest();
        Res r = Api.delivered(requester, id);
        assertEquals(409, r.status(), r.toString());
        assertEquals("not_deliverable", r.error());
        assertEquals("open", Api.detail(requester, id).json().get("status").asText());
    }

    @Test
    @DisplayName("J-12 A done request cannot be accepted again")
    void j12_doneCannotBeAccepted() throws Exception {
        String id = newRequest();
        assertEquals(200, Api.accept(buddy1, id).status());
        assertEquals(200, Api.delivered(requester, id).status());

        Res again = Api.accept(buddy2, id);
        assertEquals(409, again.status(), again.toString());
        assertEquals("already_taken", again.error());

        // A done request is gone from the app: detail returns 404.
        assertEquals(404, Api.detail(requester, id).status());
    }
}
