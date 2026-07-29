package com.prof18.feedflow.shared.data

class NoOpApiKeyStorage : ApiKeyStorage {
    override fun getApiKey(): String? = null

    // Reports failure rather than pretending: nothing is stored on these platforms, and claiming
    // success would leave the UI saying a key is set when no request could ever use one.
    override fun setApiKey(key: String): Boolean = false
}
