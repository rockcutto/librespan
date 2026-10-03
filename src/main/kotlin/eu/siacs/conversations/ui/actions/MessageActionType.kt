package eu.siacs.conversations.ui.actions

enum class MessageActionType {
    REPLY,
    EDIT,
    FORWARD,
    COPY,
    SAVE_TO_SAVED_MESSAGES,
    SELECT,
    SAVE_TO_DOWNLOADS,
    SAVE_TO_GALLERY,
    SHOW_IN_CHAT,
    OPEN_WITH,
    COPY_URL,
    COPY_LINK,
    DOWNLOAD,
    DELETE_FILE,
    DELETE_LOCALLY,
    MODERATE_MESSAGE,
    RETRY,
    RETRY_AS_P2P,
    SHOW_ERROR,
    CANCEL_TRANSFER,

    // Legacy placeholders retained until the remaining action migration is complete.
    REACT,
    SHARE,
    SAVE,
    FAVORITE,
    INFO,
    DELETE,
    MORE
}
