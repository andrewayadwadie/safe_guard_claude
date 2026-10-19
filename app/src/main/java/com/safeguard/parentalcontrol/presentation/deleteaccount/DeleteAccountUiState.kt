package com.safeguard.parentalcontrol.presentation.deleteaccount

/** Only used to word the consequences list. It never allows or blocks the request (FR-013). */
enum class DeleteRole { PARENT, CHILD }

enum class ConfirmMode { PASSWORD, GOOGLE_DELETE }

const val DELETE_CONFIRMATION_WORD = "DELETE"

data class DeleteAccountUiState(
    val role: DeleteRole = DeleteRole.PARENT,
    val linkedChildCount: Int? = null,
    val hasReviewOriginals: Boolean = false,
    val mode: ConfirmMode = ConfirmMode.PASSWORD,
    /** Kept in ViewModel memory only: never in SavedStateHandle, never logged. */
    val credential: String = "",
    val passwordVisible: Boolean = false,
    val credentialError: String? = null,
    val showFinalConfirm: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorRetryable: Boolean = false,
    val blockingNotice: String? = null,
    val deleted: Boolean = false
) {
    /** Matching is case-sensitive: the server checks the exact word. */
    val canSubmit: Boolean
        get() = !isLoading && when (mode) {
            ConfirmMode.PASSWORD -> credential.isNotEmpty()
            ConfirmMode.GOOGLE_DELETE -> credential == DELETE_CONFIRMATION_WORD
        }

    override fun toString(): String =
        "DeleteAccountUiState(role=$role, mode=$mode, credential=<redacted>, isLoading=$isLoading, " +
            "deleted=$deleted, hasError=${error != null}, hasCredentialError=${credentialError != null})"
}
