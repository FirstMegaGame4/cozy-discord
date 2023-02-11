/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.cozy.modules.logs.types

import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import org.koin.core.component.inject
import org.quiltmc.community.cozy.modules.logs.LogParserExtension
import org.quiltmc.community.cozy.modules.logs.data.Order
import java.net.URL

public abstract class LogRetriever : Ordered, KordExKoinComponent {
	public abstract val identifier: String
	public abstract override val order: Order

	private val bot: ExtensibleBot by inject()
	protected val extension: LogParserExtension get() = bot.findExtension()!!

	protected val client: HttpClient = HttpClient(CIO)

	public abstract suspend fun process(url: URL): Set<String>

	public open suspend fun setup() {}

	protected open suspend fun predicate(url: URL) : Boolean =
		true

	/** @suppress Internal function; use for intermediary types only. **/
	public open suspend fun _predicate(url: URL) : Boolean =
		predicate(url)
}
