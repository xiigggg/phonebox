package top.xingzhenxi.phonebox.net

import top.xingzhenxi.phonebox.config.ConfigRepository

/** 令牌经 ConfigRepository 加密后存入 DataStore */
class ConfigTokenStore(private val repo: ConfigRepository) : TokenStore {
    override suspend fun load(): Credential? = Credential.decode(repo.sessionToken())
    override suspend fun save(credential: Credential?) = repo.saveSessionToken(credential?.encode())
}
