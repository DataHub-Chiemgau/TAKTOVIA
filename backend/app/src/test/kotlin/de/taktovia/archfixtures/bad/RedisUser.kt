package de.taktovia.archfixtures.bad

import io.lettuce.core.RedisClient

/** Verstoß 3: Redis außerhalb von platform.cache. */
class RedisUser { var client: RedisClient? = null }
