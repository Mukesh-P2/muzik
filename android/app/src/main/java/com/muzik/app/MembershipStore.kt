package com.muzik.app

import android.content.Context
import com.muzik.app.model.Membership
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface MembershipStore {
    fun load(): Membership?
    fun save(membership: Membership)
    fun clear()
}

internal class AndroidMembershipStore(context: Context) : MembershipStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): Membership? {
        val encoded = preferences.getString(MEMBERSHIP_KEY, null) ?: return null
        return decodeStoredMembership(encoded).also { membership ->
            if (membership == null) clear()
        }
    }

    override fun save(membership: Membership) {
        preferences.edit()
            .putString(MEMBERSHIP_KEY, encodeStoredMembership(membership))
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(MEMBERSHIP_KEY).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "muzik_private_state"
        const val MEMBERSHIP_KEY = "room_membership_v1"
    }
}

internal object NoOpMembershipStore : MembershipStore {
    override fun load(): Membership? = null
    override fun save(membership: Membership) = Unit
    override fun clear() = Unit
}

private val membershipJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun encodeStoredMembership(membership: Membership): String =
    membershipJson.encodeToString(membership)

internal fun decodeStoredMembership(encoded: String): Membership? = runCatching {
    membershipJson.decodeFromString<Membership>(encoded)
}.getOrNull()?.takeIf { membership ->
    membership.roomCode.isNotBlank() &&
        membership.memberId.isNotBlank() &&
        membership.memberToken.isNotBlank() &&
        membership.displayName.isNotBlank()
}
