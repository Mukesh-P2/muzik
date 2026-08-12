package com.muzik.app

import com.muzik.app.model.Membership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MembershipStoreTest {
    @Test
    fun storedMembershipRoundTrips() {
        val membership = Membership(
            roomCode = "ABC123",
            memberId = "member-1",
            memberToken = "secret-token",
            displayName = "Listener",
            isHost = true,
        )

        assertEquals(membership, decodeStoredMembership(encodeStoredMembership(membership)))
    }

    @Test
    fun storedMembershipIgnoresUnknownFutureFields() {
        val encoded = """
            {
              "roomCode":"ABC123",
              "memberId":"member-1",
              "memberToken":"secret-token",
              "displayName":"Listener",
              "isHost":false,
              "futureField":"ignored"
            }
        """.trimIndent()

        assertEquals("member-1", decodeStoredMembership(encoded)?.memberId)
    }

    @Test
    fun malformedOrIncompleteMembershipIsRejected() {
        assertNull(decodeStoredMembership("not-json"))
        assertNull(
            decodeStoredMembership(
                """{"roomCode":"","memberId":"member-1","memberToken":"token","displayName":"Name","isHost":false}""",
            ),
        )
    }

    @Test
    fun leavingARoomClearsTheStoredMembership() {
        val membership = Membership(
            roomCode = "ABC123",
            memberId = "member-1",
            memberToken = "secret-token",
            displayName = "Listener",
            isHost = false,
        )
        val store = RecordingMembershipStore(membership)
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(membership = membership),
            searchRequest = { _, _ -> error("Unused") },
            membershipStore = store,
        )

        viewModel.leaveRoom()

        assertNull(store.membership)
        assertNull(viewModel.uiState.value.membership)
    }

    @Test
    fun inviteToAnotherRoomRequiresConfirmationBeforeLeaving() {
        val membership = Membership(
            roomCode = "ABC123",
            memberId = "member-1",
            memberToken = "secret-token",
            displayName = "Listener",
            isHost = false,
        )
        val store = RecordingMembershipStore(membership)
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(displayName = "Listener", membership = membership),
            searchRequest = { _, _ -> error("Unused") },
            membershipStore = store,
        )

        viewModel.handleInvite("xyz789")

        assertEquals("XYZ789", viewModel.uiState.value.pendingInviteCode)
        assertEquals(membership, viewModel.uiState.value.membership)
        assertEquals(membership, store.membership)

        viewModel.switchToInvitedRoom()

        assertNull(store.membership)
        assertNull(viewModel.uiState.value.membership)
        assertNull(viewModel.uiState.value.pendingInviteCode)
        assertEquals("XYZ789", viewModel.uiState.value.roomCodeInput)
        assertEquals("Listener", viewModel.uiState.value.displayName)
    }

    @Test
    fun inviteWithoutMembershipPrefillsTheJoinCode() {
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(),
            searchRequest = { _, _ -> error("Unused") },
        )

        viewModel.handleInvite("abc-123")

        assertEquals("ABC123", viewModel.uiState.value.roomCodeInput)
        assertNull(viewModel.uiState.value.pendingInviteCode)
    }

    @Test
    fun inviteDuringRoomRequestIsDeferredInsteadOfReplacingTheActiveRequest() {
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(
                roomCodeInput = "OLD123",
                loading = true,
                roomRequest = RoomRequest.Join,
            ),
            searchRequest = { _, _ -> error("Unused") },
        )

        viewModel.handleInvite("new456")

        assertEquals("OLD123", viewModel.uiState.value.roomCodeInput)
        assertEquals("NEW456", viewModel.uiState.value.pendingInviteCode)
    }
}

private class RecordingMembershipStore(
    var membership: Membership?,
) : MembershipStore {
    override fun load(): Membership? = membership
    override fun save(membership: Membership) {
        this.membership = membership
    }
    override fun clear() {
        membership = null
    }
}
