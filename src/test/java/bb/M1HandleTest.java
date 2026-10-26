package bb;

import bb.Api.Res;
import bb.Api.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 – Authentication.
 *
 * From the real code: the server does NOT block non-VIT emails. Anyone with a Google
 * account can sign up. The server only sets users.verified_vit = true when the email
 * ends with "@vitstudent.ac.in" (src/app/api/profile/claim-handle/route.ts).
 * So J-01..J-03 check that flag, not a rejection.
 */
class M1HandleTest {

    private final List<TestUser> made = new ArrayList<>();

    private TestUser user(String email) throws Exception {
        TestUser u = Api.createUser(email);
        made.add(u);
        return u;
    }

    @AfterEach
    void cleanUp() {
        made.forEach(Api::deleteUser);
        made.clear();
    }

    @Test
    @DisplayName("J-01 VIT email (@vitstudent.ac.in) is marked verified")
    void j01_vitEmailAccepted() throws Exception {
        TestUser u = user("bbtest." + Api.rand(8) + "@vitstudent.ac.in");
        Res claim = Api.claimHandle(u, Api.newHandle(), "JUnit VIT");
        assertEquals(200, claim.status(), claim.toString());

        Res me = Api.profile(u);
        assertEquals(200, me.status(), me.toString());
        assertTrue(me.json().get("verified_vit").asBoolean(), "VIT email must be verified_vit=true");
    }

    @Test
    @DisplayName("J-02 Gmail address is NOT marked verified")
    void j02_gmailNotVerified() throws Exception {
        TestUser u = user("bbtest." + Api.rand(8) + "@gmail.com");
        Res claim = Api.claimHandle(u, Api.newHandle(), "JUnit Gmail");
        // The real app lets non-VIT users in. It only withholds the verified flag.
        assertEquals(200, claim.status(), claim.toString());
        assertFalse(Api.profile(u).json().get("verified_vit").asBoolean(), "gmail must be verified_vit=false");
    }

    @Test
    @DisplayName("J-03 Lookalike domain (@vitstudent.ac.in.com) is NOT marked verified")
    void j03_lookalikeNotVerified() throws Exception {
        TestUser u = user("bbtest." + Api.rand(8) + "@vitstudent.ac.in.com");
        Res claim = Api.claimHandle(u, Api.newHandle(), "JUnit Lookalike");
        assertEquals(200, claim.status(), claim.toString());
        assertFalse(Api.profile(u).json().get("verified_vit").asBoolean(), "lookalike must be verified_vit=false");
    }

    @Test
    @DisplayName("J-04 New valid handle is accepted")
    void j04_newHandleAccepted() throws Exception {
        TestUser u = user("bbtest." + Api.rand(8) + "@vitstudent.ac.in");
        String handle = Api.newHandle();
        Res claim = Api.claimHandle(u, handle, "JUnit New");
        assertEquals(200, claim.status(), claim.toString());
        assertTrue(claim.json().get("ok").asBoolean());
        assertEquals(handle, claim.json().get("user").get("handle").asText());
    }

    @Test
    @DisplayName("J-05 Duplicate handle is rejected with 409 handle_taken")
    void j05_duplicateHandleRejected() throws Exception {
        String handle = Api.newHandle();
        TestUser first = user("bbtest." + Api.rand(8) + "@vitstudent.ac.in");
        assertEquals(200, Api.claimHandle(first, handle, "JUnit First").status());

        TestUser second = user("bbtest." + Api.rand(8) + "@vitstudent.ac.in");
        Res dup = Api.claimHandle(second, handle, "JUnit Second");
        assertEquals(409, dup.status(), dup.toString());
        assertEquals("handle_taken", dup.error());
    }

    @ParameterizedTest(name = "J-06 invalid handle \"{0}\" is rejected")
    @ValueSource(strings = {
            "ab",                // too short (min 3)
            "abcdefghijklmnop",  // too long (16, max 15)
            "Kamal_1",           // uppercase: the server does not lower-case it
            "has space",
            "bad-dash",
            "dot.name",
            "émile",
            "",
    })
    void j06_invalidHandleRejected(String bad) throws Exception {
        TestUser u = user("bbtest." + Api.rand(8) + "@vitstudent.ac.in");
        Res r = Api.claimHandle(u, bad, "JUnit Bad");
        assertEquals(400, r.status(), r.toString());
        assertEquals("bad_request", r.error());
    }
}
